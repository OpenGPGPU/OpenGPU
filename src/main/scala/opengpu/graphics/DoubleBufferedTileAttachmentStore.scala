package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.util.CarrySaveAccumulator

class TileAttachmentByteCounts extends Bundle {
  val loadBytes = UInt(16.W)
  val storeBytes = UInt(16.W)
  val resolveBytes = UInt(16.W)
}

class DoubleBufferedTileAttachmentStoreIO(config: GpuConfig,transactions: Int,externalArbitration: Boolean,
  bankedSamples: Boolean=false) extends PersistentTileAttachmentStoreIO(bankedSamples=bankedSamples) {
  val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,transactions))
  val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
  /** Stable with pass.completion, in begin order, including after cancellation. */
  val completionBytes = Output(new TileAttachmentByteCounts)
  val occupied = Output(UInt(2.W))
  val acceptingDraws = Output(Bool())
  val slotMemoryRequest = if (externalArbitration) Some(Vec(2,
    Decoupled(new ComputeMemoryRequest(config,64,transactions/2)))) else None
  val slotMemoryResponse = if (externalArbitration) Some(Vec(2,
    Flipped(Decoupled(new ComputeMemoryResponse(64,transactions/2))))) else None
}

private class BufferedAttachmentRequest(config: GpuConfig,transactions: Int) extends Bundle {
  val memory = new ComputeMemoryRequest(config,64,transactions)
}

/** Two attachment slots sharing one raster/OM producer, not two renderers.
  * End hands off a fully drained producer and lets the next tile begin while
  * the former slot prepares stores/resolves and awaits external acks. Each
  * slot owns its own read/write transaction namespace. Completions retire in
  * begin order and retain their slot until the consumer accepts them.
  *
  * Simultaneous slots may share identical image views only for disjoint tile
  * rectangles; other allocations must be disjoint. VM aliases remain the
  * caller's responsibility. Cancel covers both slots; execution faults cover
  * the current producer. Faults use the tagged line response, not the unused
  * legacy word-port pass.memoryFault input. The legacy mem port is disabled.
  * A blocking coherent fabric should use externalArbitration and schedule
  * each slot separately with byte hazards and reserved shader-progress credits;
  * otherwise an older export at a combined port can hide the next slot's load.
  */
class DoubleBufferedTileAttachmentStore(config: GpuConfig = GpuConfig(),
  maxSampleCount: Int = 4,screenWidth: Int = 65535,screenHeight: Int = 65535,
  useSramBlackBoxes: Boolean = false,storeCredits: Int = 8,
  externalArbitration: Boolean = false,coalesceTransfers: Boolean = true,
  bufferedWriteAcks: Boolean = true,bankedSamples: Boolean = false) extends Module {
  require(storeCredits >= 1 && storeCredits <= 16)
  private val perSlot = storeCredits+1 // write window plus an independent read credit
  private val transactions = 2*perSlot
  private val idWidth = log2Ceil(transactions).max(1)
  val io = IO(new DoubleBufferedTileAttachmentStoreIO(config,transactions,externalArbitration,bankedSamples))
  private val stores = Seq.fill(2)(Module(new PersistentTileAttachmentStore(
    maxSampleCount,screenWidth,screenHeight,useSramBlackBoxes,storeCredits,
    externalTransfers=coalesceTransfers && bufferedWriteAcks,bankedSamples=bankedSamples)))
  private val gathered = if (coalesceTransfers) Some(Seq.fill(2)(Module(new TileAttachmentLinePort(
    config,perSlot,bufferedWriteAcks=bufferedWriteAcks)))) else None
  private val legacy = if (!coalesceTransfers) Some(Seq.fill(2)(Module(new OmWordToLinePort(config,64,perSlot,uncached=true)))) else None
  private val ports: Seq[OmWordToLineIO] = gathered.map(_.map(_.io)).getOrElse(legacy.get.map(_.io))
  private def portDrained(s: Int): Bool=gathered.map(_(s).io.drained).getOrElse(true.B)
  private val arbiter = Module(new RRArbiter(new BufferedAttachmentRequest(config,transactions),2))
  private val requests = Module(new Queue(new BufferedAttachmentRequest(config,transactions),2,pipe=true))
  private val order = Module(new Queue(UInt(1.W),2))
  private val occupied = RegInit(VecInit(Seq.fill(2)(false.B)))
  private val descriptors = Reg(Vec(2,new TileRenderPassDescriptor))
  private val currentValid = RegInit(false.B)
  private val cancelProducer=RegInit(false.B)
  private val clearOwners=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val current = RegInit(0.U(1.W))
  private val chosen = Mux(!occupied(0),0.U(1.W),1.U(1.W))
  private val incoming = io.pass.begin.bits
  private val noProducer = !currentValid && io.producerDrained && io.omDrained
  private def slotLocalDrained(s: Int): Bool= !clearOwners(s) && stores(s).io.localDrained.getOrElse(true.B)
  private val currentLocalDrained=Mux(current===0.U,slotLocalDrained(0),slotLocalDrained(1))
  private val producerDrained=io.producerDrained && io.omDrained && currentLocalDrained

  private def disjoint(a: UInt,b: UInt,c: UInt,d: UInt): Bool =
    a.pad(33) === b || c.pad(33) === d || b <= c.pad(33) || d <= a.pad(33)
  private def views(d: TileRenderPassDescriptor): Seq[(UInt,UInt,UInt,Bool)] = Seq(
    (d.color.base,d.color.limit,d.color.rowPitch,true.B),
    (d.depth.base,d.depth.limit,d.depth.rowPitch,true.B),
    (d.resolveBase,d.resolveLimit,d.resolveRowPitch,d.resolve))
  private val separate = (0 until 2).map { slot =>
    val old = descriptors(slot)
    val rectangles = (incoming.originX +& incoming.tileWidth) <= old.originX ||
      (old.originX +& old.tileWidth) <= incoming.originX ||
      (incoming.originY +& incoming.tileHeight) <= old.originY ||
      (old.originY +& old.tileHeight) <= incoming.originY
    val pairs = for (((a,b,p,en),i) <- views(incoming).zipWithIndex;
      ((c,d,q,on),j) <- views(old).zipWithIndex) yield {
      val compatible = if (i != j) false.B else a === c && b === d && p === q &&
        (if (i == 2) true.B else incoming.sampleMode === old.sampleMode) &&
        (if (i == 1) true.B else incoming.colorFormat === old.colorFormat) && rectangles
      !en || !on || disjoint(a,b,c,d) || compatible
    }
    !occupied(slot) || pairs.reduce(_ && _)
  }.reduce(_ && _)

  io.pass.begin.ready := noProducer && !occupied.asUInt.andR && order.io.enq.ready &&
    Mux(chosen === 0.U,stores(0).io.pass.begin.ready,stores(1).io.pass.begin.ready) && !io.pass.cancel
  order.io.enq.valid := io.pass.begin.fire
  order.io.enq.bits := chosen
  when(io.pass.begin.fire) {
    occupied(chosen) := true.B; descriptors(chosen) := incoming
    current := chosen; currentValid := true.B
    cancelProducer:=false.B
  }
  io.occupied := occupied.asUInt
  io.pass.active := currentValid && Mux(current === 0.U,
    stores(0).io.pass.active,stores(1).io.pass.active)
  io.pass.descriptor := Mux(current === 0.U,stores(0).io.pass.descriptor,stores(1).io.pass.descriptor)
  io.permit := currentValid && Mux(current === 0.U,stores(0).io.permit,stores(1).io.permit)
  io.pass.end.ready := currentValid && producerDrained &&
    Mux(current === 0.U,stores(0).io.pass.end.ready,stores(1).io.pass.end.ready)
  io.acceptingDraws := currentValid && !cancelProducer && !io.pass.cancel &&
    Mux(current === 0.U,stores(0).io.pass.end.ready,stores(1).io.pass.end.ready)
  io.pass.clear.ready := currentValid &&
    Mux(current === 0.U,stores(0).io.pass.clear.ready,stores(1).io.pass.clear.ready)
  io.pass.clearDone.valid := currentValid && Mux(current === 0.U,
    stores(0).io.pass.clearDone.valid,stores(1).io.pass.clearDone.valid)
  io.pass.clearDone.bits := Mux(current === 0.U,
    stores(0).io.pass.clearDone.bits,stores(1).io.pass.clearDone.bits)
  io.om.req.ready := currentValid && Mux(current === 0.U,stores(0).io.om.req.ready,stores(1).io.om.req.ready)
  io.om.resp.valid := Mux(current === 0.U,stores(0).io.om.resp.valid,stores(1).io.om.resp.valid)
  io.om.resp.bits := Mux(current === 0.U,stores(0).io.om.resp.bits,stores(1).io.om.resp.bits)
  when(io.pass.cancel && currentValid){cancelProducer:=true.B}
  when(io.pass.end.fire || (currentValid && (cancelProducer || io.pass.cancel) && producerDrained)) {
    currentValid := false.B
    cancelProducer:=false.B
  }
  if(bankedSamples) {
    // End may arrive while older upstream quads still need submission. Once
    // that producer is drained, do not accept work past its closing boundary.
    val admit=currentValid && !cancelProducer && !io.pass.cancel &&
      (!io.pass.end.valid || !io.producerDrained)
    io.quad.get.ready:=admit && Mux(current===0.U,stores(0).io.quad.get.ready,stores(1).io.quad.get.ready)
    io.quadCompletion.get.valid:=currentValid && Mux(current===0.U,
      stores(0).io.quadCompletion.get.valid,stores(1).io.quadCompletion.get.valid)
    io.quadCompletion.get.bits:=Mux(current===0.U,stores(0).io.quadCompletion.get.bits,stores(1).io.quadCompletion.get.bits)
    io.acceptedSamples.get:=stores.map(_.io.acceptedSamples.get).reduce(_+&_)
    io.committedSamples.get:=stores.map(_.io.committedSamples.get).reduce(_+&_)
    io.localDrained.get:= !currentValid || currentLocalDrained
    io.localAborted.get:=currentValid && (cancelProducer || io.pass.cancel || Mux(current===0.U,
      stores(0).io.localAborted.get,stores(1).io.localAborted.get))
    for(s <- 0 until 2) {
      stores(s).io.quad.get.valid:=io.quad.get.valid && admit && current===s.U
      stores(s).io.quad.get.bits:=io.quad.get.bits
      stores(s).io.quadCompletion.get.ready:=io.quadCompletion.get.ready && currentValid && current===s.U
    }
    assert(stores.map(_.io.committedSamples.get).reduce(_+&_)<=4.U,"one engine may commit at most one quad per cycle")
  }
  private val head = order.io.deq.bits
  io.pass.completion.valid := order.io.deq.valid && Mux(head === 0.U,
    stores(0).io.pass.completion.valid && portDrained(0),stores(1).io.pass.completion.valid && portDrained(1))
  io.pass.completion.bits := Mux(head === 0.U,
    stores(0).io.pass.completion.bits,stores(1).io.pass.completion.bits)
  order.io.deq.ready := io.pass.completion.fire
  when(io.pass.completion.fire) {
    occupied(head) := false.B
    when(currentValid && current === head) { currentValid := false.B;cancelProducer:=false.B }
  }

  io.mem.req.valid := false.B; io.mem.req.bits := 0.U.asTypeOf(io.mem.req.bits)
  io.mem.resp.ready := false.B
  requests.io.enq <> arbiter.io.out
  io.memoryRequest.valid := requests.io.deq.valid
  io.memoryRequest.bits := requests.io.deq.bits.memory
  requests.io.deq.ready := io.memoryRequest.ready
  io.memoryResponse.ready := false.B
  when(io.memoryResponse.valid) { assert(io.memoryResponse.bits.transactionId < transactions.U) }
  private val counts = Wire(Vec(2,new TileAttachmentByteCounts))
  private val wordEvents=Wire(Vec(2,new TileAttachmentEvents))
  for (s <- 0 until 2) {
    val child = stores(s).io; val port = ports(s)
    val selected = current === s.U
    child.pass.begin.valid := io.pass.begin.valid && io.pass.begin.ready && chosen === s.U
    child.pass.begin.bits := incoming
    child.pass.begin.bits.version := Mux(separate,incoming.version,0.U)
    child.pass.end.valid := io.pass.end.valid && currentValid && selected && producerDrained
    child.pass.end.bits := io.pass.end.bits
    child.pass.clear.valid := io.pass.clear.valid && currentValid && selected
    child.pass.clear.bits := io.pass.clear.bits
    child.pass.clearDone.ready := io.pass.clearDone.ready && selected && currentValid
    when(child.pass.clear.fire){clearOwners(s):=true.B}
    when(child.pass.clearDone.fire){clearOwners(s):=false.B}
    when(child.pass.begin.fire){assert(!clearOwners(s),"an accepted clear must drain before slot reuse")}
    child.pass.cancel := io.pass.cancel && occupied(s)
    child.pass.completion.ready := io.pass.completion.ready && order.io.deq.valid && head === s.U && portDrained(s)
    child.fragmentX := io.fragmentX; child.fragmentY := io.fragmentY
    child.wordIndex := io.wordIndex; child.depthPlane := io.depthPlane
    child.producerDrained := !currentValid || !selected || io.producerDrained
    child.omDrained := !currentValid || !selected || io.omDrained
    child.executionFault := currentValid && selected && io.executionFault
    child.om.req.valid := io.om.req.valid && currentValid && selected
    child.om.req.bits := io.om.req.bits
    child.om.resp.ready := io.om.resp.ready && selected
    port.in <> child.mem.req; child.mem.resp <> port.out
    wordEvents(s):=child.events
    if (coalesceTransfers) {
      val line=gathered.get(s).io
      line.begin:=child.pass.begin.fire
      line.abort:=child.pass.cancel || child.executionFault
      line.resolve:=child.events.resolveBytes=/=0.U
      // Fault is response metadata, independent of ready. A banked refill
      // uses it to reject invalid data before deciding response readiness.
      child.pass.memoryFault:=line.out.valid && line.responseFault
      child.externalDrained.foreach(_:=line.drained)
      child.externalWriteFault.foreach(_:=line.writeFault)
      wordEvents(s).loadBytes:=line.events.loadBytes
      wordEvents(s).storeBytes:=line.events.storeBytes
      wordEvents(s).resolveBytes:=line.events.resolveBytes
    } else child.pass.memoryFault := port.memoryResponse.valid && port.memoryResponse.bits.fault
    if (externalArbitration) {
      io.slotMemoryRequest.get(s) <> port.memoryRequest
      port.memoryResponse <> io.slotMemoryResponse.get(s)
      arbiter.io.in(s).valid := false.B
      arbiter.io.in(s).bits := 0.U.asTypeOf(arbiter.io.in(s).bits)
    } else {
      arbiter.io.in(s).valid := port.memoryRequest.valid
      arbiter.io.in(s).bits.memory := port.memoryRequest.bits
      arbiter.io.in(s).bits.memory.transactionId := port.memoryRequest.bits.transactionId.pad(idWidth)+(s*perSlot).U(idWidth.W)
      port.memoryRequest.ready := arbiter.io.in(s).ready
      val response = io.memoryResponse.bits.transactionId >= (s*perSlot).U &&
        io.memoryResponse.bits.transactionId < ((s+1)*perSlot).U
      port.memoryResponse.valid := io.memoryResponse.valid && response
      port.memoryResponse.bits := io.memoryResponse.bits
      port.memoryResponse.bits.transactionId := io.memoryResponse.bits.transactionId-(s*perSlot).U
      when(response) { io.memoryResponse.ready := port.memoryResponse.ready }
    }
    for ((field,event) <- Seq((counts(s).loadBytes,wordEvents(s).loadBytes),
      (counts(s).storeBytes,wordEvents(s).storeBytes),(counts(s).resolveBytes,wordEvents(s).resolveBytes))) {
      val counter = Module(new CarrySaveAccumulator(16,prefixValue=true))
      counter.io.clear := child.pass.begin.fire; counter.io.addend := event
      field := counter.io.value
    }
  }
  io.completionBytes := counts(head)
  io.events := 0.U.asTypeOf(io.events)
  io.events.resident := occupied.asUInt.orR
  io.events.admitted := stores.map(_.io.events.admitted).reduce(_ || _)
  io.events.completed := io.pass.completion.fire
  // Bank arbitration may reach each slot's byte pulse late in the cycle.
  // Register diagnostics before aggregation; the per-slot completion counts
  // above still accumulate the original service events without delay.
  io.events.loadBytes := wordEvents.map(e=>if(bankedSamples)RegNext(e.loadBytes,0.U)else e.loadBytes).reduce(_ +& _)
  io.events.storeBytes := wordEvents.map(e=>if(bankedSamples)RegNext(e.storeBytes,0.U)else e.storeBytes).reduce(_ +& _)
  io.events.resolveBytes := wordEvents.map(e=>if(bankedSamples)RegNext(e.resolveBytes,0.U)else e.resolveBytes).reduce(_ +& _)
  if (externalArbitration) {
    assert(PopCount(VecInit(io.slotMemoryRequest.get.map(_.fire))) <= 1.U,
      "enclosing attachment arbiter must accept at most one line per engine per cycle")
  }
  io.events.clearRead := stores.map(_.io.events.clearRead).reduce(_ || _)
  io.events.attachmentStall := stores.map(_.io.events.attachmentStall).reduce(_ || _)
  io.events.transferOccupancy := stores.map(_.io.events.transferOccupancy).reduce(_ +& _)
}
