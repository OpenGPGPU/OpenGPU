package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate, ComputeMemoryRequest, ComputeMemoryResponse,
  SharedAtomicRequest, SharedAtomicResponse, VectorTlbFlush}
import opengpu.system.{SharedCuGraphicsPort, SharedCuShaderConfig}
import opengpu.util.CarrySaveAccumulator

object TileBinRenderStatus {
  val width = 4
  val invalidEncoding = 9
  val attachmentFault = 10
  val shaderFault = 11
  val textureFault = 12
  def merge(a: UInt,b: UInt): UInt = {
    val priorities = Seq(attachmentFault,PrimitiveBinStatus.memoryFault,textureFault,
      shaderFault,invalidEncoding,ResolvedTileBinStatus.invalidHandle,
      PrimitiveBinReadStatus.invalidPage,PrimitiveBinStatus.invalidOrder,
      PrimitiveBinStatus.invalidDescriptor,PrimitiveBinStatus.closed,PrimitiveBinStatus.cancelled)
    priorities.foldRight(Mux(a === 0.U,b,a)) { (code,rest) =>
      Mux(a === code.U || b === code.U,code.U(width.W),rest)
    }
  }
  def attachment(status: UInt): UInt = Mux(status === TilePassStatus.memoryFault.U,
    attachmentFault.U,Mux(status === TilePassStatus.executionFault.U,shaderFault.U,
      Mux(status === TilePassStatus.textureFault.U,textureFault.U,status)))
}

class TileBinRenderTileCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val engine = UInt(8.W)
  val status = UInt(TileBinRenderStatus.width.W)
  /** A successfully exported committed prefix is restartable on exhaustion.
    * Faulted or cancelled tiles never advertise a usable attachment prefix.
    * Resume after the whole pass drains, load the exported attachments and
    * submit this reference exactly once before the remaining references.
    * The final pass status must also permit reuse (success or exhaustion);
    * a later fault in another tile invalidates the pass image.
    */
  val prefixUsable = Bool()
  val restartValid = Bool()
  val restart = new BinPrimitiveReference
  val references = UInt(32.W)
  val draws = UInt(32.W)
  val loadBytes = UInt(16.W)
  val storeBytes = UInt(16.W)
  val resolveBytes = UInt(16.W)
}

class TileBinRenderPassCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(TileBinRenderStatus.width.W)
  val replay = new ResolvedTileBinPassCompletion
  val tiles = UInt(32.W)
  val draws = UInt(32.W)
  /** Useful attachment word bytes; the line port also transports bin,
    * record and texture traffic and is not a DRAM bandwidth model.
    */
  val loadBytes = UInt(64.W)
  val storeBytes = UInt(64.W)
  val resolveBytes = UInt(64.W)
}

private class TileBinRenderPending extends Bundle {
  val tile = UInt(32.W)
  val status = UInt(TileBinRenderStatus.width.W)
  val input = new ResolvedTileBinCompletion
  val draws = UInt(32.W)
  val owner = Bool()
}

class TileBinRenderDatapathIO(gpuConfig: GpuConfig,engines: Int,transactions: Int,
  kernelWords: Int,sharedShader: Option[SharedCuShaderConfig] = None) extends Bundle {
    val memoryRequest = Decoupled(new ComputeMemoryRequest(gpuConfig,64,transactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val sharedOwners = sharedShader.map(pool => Output(Vec(engines,UInt(pool.numComputeUnits.W))))
    val shared = sharedShader.map(pool => Flipped(new SharedCuGraphicsPort(
      gpuConfig,pool.taskIdWidth,pool.numComputeUnits)))
    val kernelMemReq = Vec(engines,Decoupled(new ComputeMemoryRequest(gpuConfig)))
    val kernelMemResp = Vec(engines,Flipped(Decoupled(new ComputeMemoryResponse())))
    val kernelWordMemReq = Vec(engines,Decoupled(new ComputeMemoryRequest(gpuConfig,64,kernelWords)))
    val kernelWordMemResp = Vec(engines,Flipped(Decoupled(new ComputeMemoryResponse(64,kernelWords))))
    val kernelL1Invalidate = Vec(engines,Flipped(Decoupled(new CacheLineInvalidate(gpuConfig))))
    val kernelL1InvalidateDone = Vec(engines,Decoupled(new CacheLineInvalidate(gpuConfig)))
    val kernelGlobalAtomicRequest = Vec(engines,Decoupled(new SharedAtomicRequest(gpuConfig)))
    val kernelGlobalAtomicResponse = Vec(engines,Flipped(Decoupled(new SharedAtomicResponse(gpuConfig))))
    val instructionSatp = Input(UInt(32.W))
    val vectorSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new VectorTlbFlush(gpuConfig)))
    val vectorTlbFlush = Flipped(Valid(new VectorTlbFlush(gpuConfig)))
    val engineBusy = Output(Vec(engines,Bool()))
    val descriptorLookupBusy = Output(Vec(engines,Bool()))
    /** Accepted useful attachment loads, indexed by engine and SRAM slot. */
    val attachmentLoadAccepted = Output(Vec(engines,Vec(2,Bool())))
    /** Useful word traffic; physical line requests are observed separately. */
    val attachmentEvents = Output(Vec(engines,new TileAttachmentEvents))
    val busy = Output(Bool())
}

class TileBinRenderPipelineIO(gpuConfig: GpuConfig,engines: Int,transactions: Int,
  kernelWords: Int,sharedShader: Option[SharedCuShaderConfig] = None)
  extends TileBinRenderDatapathIO(gpuConfig,engines,transactions,kernelWords,sharedShader) {
    val begin = Flipped(Decoupled(new TileBinRenderPassDescriptor))
    val append = Flipped(Decoupled(new TileBinAppend))
    val appended = Decoupled(new TileBinAppendResult)
    val seal = Flipped(Decoupled(UInt(32.W)))
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    val tileDone = Vec(engines,Decoupled(new TileBinRenderTileCompletion))
    val completion = Decoupled(new TileBinRenderPassCompletion)
}

/** Scalable bin replay feeding independently resident RenderPipeline engines.
  *
  * All tile descriptors pass full admission before any bin memory request.
  * Tile IDs name a disjoint row-major grid in shared attachment views. End
  * seals every remaining tile, including empty tiles whose load/clear/store
  * operations still belong to the pass. Within each tile, all draws consume
  * the immutable two-line primitive and one-line state ABI in bin order.
  *
  * Bin, record, attachment and fixed-function texture traffic share one tagged
  * line client. Optional shader/shared-CU ports retain their existing producer
  * namespaces and must be connected to the enclosing CU/memory fabric; this
  * module never allocates private graphics CUs when sharedShader is selected.
  * Stores to immutable arenas must be published before begin; VM and every
  * allocation remain owned through the final completion handshake. A failed
  * pass can have already stored completed tiles; consumers discard a faulted
  * image. Exhaustion instead exports exact usable prefixes and restart tags;
  * resume those tiles with load operations after the pass completion drains.
  */
class TileBinRenderPipeline(
  config: GraphicsConfig,
  gpuConfig: GpuConfig = GpuConfig(l2StoreEntries=4),
  engines: Int = 2,
  pageBytes: Int = 4096,
  writeCredits: Int = 8,
  tileSlots: Int = 8,
  cacheEntries: Int = 8,
  storeCredits: Int = 8,
  fragCore: Boolean = false,
  sharedShader: Option[SharedCuShaderConfig] = None,
  useTileSramBlackBoxes: Boolean = false,
  pagedCapacity: Int = 0,
  /** Internal streaming-continuation mode: no external seals; end.bits marks
    * the final input batch. Exhausted batches retain attachments but resolve
    * only when the input stream ends. Whole-pass admission still validates
    * the original resolve allocation before any memory traffic.
    */
  deferResolveUntilEnd: Boolean = false,
  coalesceAttachments: Boolean = true
) extends Module {
  require(config.tileAttachments && config.tileSize == 16 && !config.tileBinning)
  require(engines >= 1 && engines <= 4)
  require(storeCredits >= 1 && storeCredits <= 16)
  private val paged=pagedCapacity>0
  private val maximumTiles=if (paged) pagedCapacity else tileSlots
  private val replayTransactions = writeCredits+2*engines+(if (paged) 1 else 0)
  private val attachmentTransactions = 2*(storeCredits+1)
  require(gpuConfig.l2StoreEntries >= 2*engines,
    "each render engine needs a reserved export credit and equal L2 headroom for shader progress")
  private val writesPerEngine = gpuConfig.l2StoreEntries/(2*engines)
  private val attachmentWriteCredits = writesPerEngine*engines
  private val transactions = replayTransactions+engines*(attachmentTransactions+1)
  private val idWidth = log2Ceil(transactions).max(1)
  private val slotBits = log2Ceil(tileSlots).max(1)
  private val kernelWords = SharedCuShaderConfig.kernelWordTransactions(sharedShader)
  val io = IO(new TileBinRenderPipelineIO(gpuConfig,engines,transactions,kernelWords,sharedShader))
  private val replay = Module(new ResolvedTileBinReplayPass(gpuConfig,engines,pageBytes,
    writeCredits,tileSlots,cacheEntries,primitiveLines=TileBinDrawLayout.primitiveLines,pagedCapacity=pagedCapacity,
    useDirectorySramBlackBoxes=useTileSramBlackBoxes))
  private val admission = Module(new TileBinRenderPassAdmission(config,tileSlots,fragCore,engines,pagedCapacity,pageBytes))
  private val pipes = Seq.fill(engines)(Module(new RenderPipeline(config,gpuConfig,
    fragCore=fragCore,sharedShader=sharedShader,persistentAttachments=true,
    tileStoreCredits=storeCredits,useTileSramBlackBoxes=useTileSramBlackBoxes,
    normalizedTextureModulation=true,doubleBufferedAttachments=true,coalesceAttachments=coalesceAttachments)))
  private val sharedRouter = sharedShader.map(pool => Module(new SharedShaderTaskRouter(
    gpuConfig,pool.numComputeUnits,engines,pool.taskIdWidth,
    tasksPerClient=if (pool.parallelFragments) pool.fragmentWorkers+1 else 1)))
  sharedRouter.foreach(router => router.io.pool <> io.shared.get)
  private val decoders = Seq.fill(engines)(Module(new TileBinDrawDecoder(config)))
  private val results = Seq.fill(engines)(Module(new Queue(new TileBinRenderTileCompletion,tileSlots)))
  private val pending = Seq.fill(engines)(Module(new Queue(new TileBinRenderPending,2)))
  private val texturePorts = Seq.fill(engines)(Module(new OmWordToLinePort(
    gpuConfig,64,1,uncached=true)))
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(gpuConfig,64,transactions),1+3*engines))
  private val requests = Module(new Queue(new ComputeMemoryRequest(gpuConfig,64,transactions),2,pipe=true))
  // Keep overlapping bulk exports out of the L2 controller while a previous
  // write owns their bytes (reads conflict with the full line). Leave at least
  // half of each slice's store entries for
  // shader staging/compute. Track ownership from arbitration through response
  // consumption, including requests buffered before the external handshake.
  private val writeLive = RegInit(0.U(attachmentWriteCredits.W))
  private val writeIds = Reg(Vec(attachmentWriteCredits,UInt(idWidth.W)))
  private val writeLines = Reg(Vec(attachmentWriteCredits,UInt(26.W)))
  private val writeMasks = Reg(Vec(attachmentWriteCredits,UInt(64.W)))
  private val writeOwners = Reg(Vec(attachmentWriteCredits,UInt(log2Ceil(engines).max(1).W)))
  private val writeFree = ~writeLive
  private val Seq(idle,admitting,launch,building,sealing,ending,draining,complete) = Enum(8)
  private val phase = RegInit(idle)
  private val snapshot = Reg(new TileBinRenderPassDescriptor)
  private val finalBatch = RegInit(false.B)
  private val tiles = if (!paged) Some(Reg(Vec(tileSlots,new TileRenderPassDescriptor))) else None
  private val gridLookups = if (paged) Seq.fill(engines)(Module(new TileGridDescriptorLookup)) else Seq.empty
  private val aborted = RegInit(false.B)
  private val status = RegInit(0.U(TileBinRenderStatus.width.W))
  private val replayIssued = RegInit(false.B)
  private val replayDone = RegInit(false.B)
  private val replayResult = Reg(new ResolvedTileBinPassCompletion)
  private val completion = Reg(new TileBinRenderPassCompletion)
  private val sealedTiles = if (!paged) Some(RegInit(0.U(tileSlots.W))) else None
  private val sealIndex = Reg(UInt(log2Ceil(maximumTiles+1).max(1).W))
  private val engineStatus = Wire(Vec(engines,UInt(TileBinRenderStatus.width.W)))
  private val severe = Wire(Vec(engines,Bool()))
  private val stopped = aborted || io.cancel
  private val active = phase =/= idle && phase =/= complete
  private val Seq(engineIdle,engineBegin,engineRun) = Enum(3)
  private val enginePhase = RegInit(VecInit(Seq.fill(engines)(engineIdle)))
  private val tileId = Reg(Vec(engines,UInt(32.W)))
  private val tileStatus = RegInit(VecInit(Seq.fill(engines)(0.U(TileBinRenderStatus.width.W))))
  private val inputDone = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val inputResult = Reg(Vec(engines,new ResolvedTileBinCompletion))
  private val ownerIssued = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val drawCounts = RegInit(VecInit(Seq.fill(engines)(0.U(32.W))))
  private val totalTiles = RegInit(VecInit(Seq.fill(engines)(0.U(32.W))))
  private val totalDraws = RegInit(VecInit(Seq.fill(engines)(0.U(32.W))))
  private val totalLoads = Seq.fill(engines)(Module(new CarrySaveAccumulator(64)))
  private val totalStores = Seq.fill(engines)(Module(new CarrySaveAccumulator(64)))
  private val totalResolves = Seq.fill(engines)(Module(new CarrySaveAccumulator(64)))

  io.begin.ready := phase === idle && admission.io.in.ready && !io.cancel
  admission.io.in.valid := io.begin.valid && phase === idle && !io.cancel
  admission.io.in.bits := io.begin.bits
  admission.io.cancel := stopped && active
  admission.io.out.ready := phase === admitting
  io.busy := phase =/= idle
  io.completion.valid := phase === complete
  io.completion.bits := completion
  when(io.begin.fire) {
    assert(!writeLive.orR,"attachment write credits must drain before pass rebinding")
    snapshot := io.begin.bits
    finalBatch := false.B
    status := 0.U; aborted := false.B
    replayIssued := false.B; replayDone := false.B
    sealedTiles.foreach(_ := 0.U); sealIndex := 0.U
    phase := admitting
    for (e <- 0 until engines) { totalTiles(e) := 0.U; totalDraws(e) := 0.U }
  }
  when(admission.io.out.fire) {
    tiles.foreach(_ := admission.io.out.bits.tiles)
    when(admission.io.out.bits.status =/= 0.U || stopped) {
      completion := 0.U.asTypeOf(completion)
      completion.identity := snapshot.attachments.identity
      completion.status := TileBinRenderStatus.merge(admission.io.out.bits.status,
        Mux(stopped,PrimitiveBinStatus.cancelled.U,0.U))
      phase := complete
    }.otherwise { phase := launch }
  }
  replay.io.begin.valid := phase === launch && !stopped
  replay.io.begin.bits := snapshot.replay
  when(replay.io.begin.fire) { replayIssued := true.B; phase := building }
  when(phase === launch && stopped) {
    completion := 0.U.asTypeOf(completion)
    completion.identity := snapshot.attachments.identity
    completion.status := PrimitiveBinStatus.cancelled.U
    phase := complete
  }
  replay.io.cancel := stopped && replayIssued
  replay.io.append.valid := io.append.valid && phase === building && !stopped
  replay.io.append.bits := io.append.bits
  io.append.ready := replay.io.append.ready && phase === building && !stopped
  io.appended <> replay.io.appended
  private val sealInRange = io.seal.bits < snapshot.replay.bins.tileCount
  private val invalidSeal = io.seal.valid && phase === building && !stopped && !sealInRange
  replay.io.seal.valid := io.seal.valid && phase === building && !stopped && sealInRange
  replay.io.seal.bits := io.seal.bits
  io.seal.ready := (replay.io.seal.ready || !sealInRange) && phase === building && !stopped
  when(replay.io.seal.fire && replay.io.seal.bits < snapshot.replay.bins.tileCount) {
    sealedTiles.foreach(bits=>bits := bits | UIntToOH(replay.io.seal.bits,tileSlots))
  }
  io.end.ready := phase === building && !io.append.valid && !io.seal.valid && !stopped
  when(io.end.fire) { finalBatch := io.end.bits; sealIndex := 0.U; phase := sealing }
  when(phase === sealing && !stopped) {
    val alreadySealed = if (paged) false.B else if (tileSlots == 1) sealedTiles.get(0) else sealedTiles.get(sealIndex(slotBits-1,0))
    when(sealIndex === snapshot.replay.bins.tileCount) { phase := ending }
      .elsewhen(alreadySealed) { sealIndex := sealIndex+1.U }
      .otherwise {
        replay.io.seal.valid := true.B
        replay.io.seal.bits := sealIndex
        when(replay.io.seal.fire) { sealIndex := sealIndex+1.U }
      }
  }
  replay.io.end.valid := phase === ending && !stopped
  replay.io.end.bits := true.B
  when(replay.io.end.fire) { phase := draining }
  replay.io.completion.ready := replayIssued && !replayDone
  when(replay.io.completion.fire) {
    replayDone := true.B; replayResult := replay.io.completion.bits
    phase := draining
  }

  for (e <- 0 until engines) {
    io.descriptorLookupBusy(e):=(if (paged) gridLookups(e).io.busy else false.B)
    val rp = pipes(e); val pass = rp.io.tilePass.get
    val decoder = decoders(e); val in = replay.io.reference(e)
    val raw = replay.io.tileDone(e); val out = results(e)
    val running = enginePhase(e) =/= engineIdle
    val sameRaw = running && raw.bits.replay.tile === tileId(e)
    val sameDraw = running && decoder.io.out.bits.source.tile === tileId(e)
    val rawFailed = raw.bits.status =/= 0.U && raw.bits.status =/= PrimitiveBinStatus.exhausted.U
    val discardRaw = raw.valid && !sameRaw && rawFailed && raw.bits.resolved === 0.U
    val skipRaw = enginePhase(e) === engineIdle && raw.valid && rawFailed
    // A resolved draw for the following tile can already be queued while an
    // earlier empty tile's result waits. Retire that empty owner first.
    val emptyHead = raw.valid && !rawFailed && raw.bits.resolved === 0.U
    val canStart=if (paged) gridLookups(e).io.in.ready else true.B
    val startDraw = canStart && enginePhase(e) === engineIdle && decoder.io.out.valid && !emptyHead && !stopped
    val startEmpty = canStart && enginePhase(e) === engineIdle && emptyHead && !stopped
    val starting = startDraw || startEmpty
    val newTile = Mux(startDraw,decoder.io.out.bits.source.tile,raw.bits.replay.tile)
    when(starting) {
      assert(newTile < snapshot.replay.bins.tileCount)
      tileId(e) := newTile; tileStatus(e) := 0.U
      inputDone(e) := false.B; ownerIssued(e) := false.B
      drawCounts(e) := 0.U
      enginePhase(e) := engineBegin
    }
    val retiring = pending(e).io
    io.engineBusy(e) := running || decoder.io.busy || retiring.deq.valid
    decoder.io.cancel := stopped
    decoder.io.in.valid := in.valid && !stopped
    decoder.io.in.bits := in.bits
    in.ready := Mux(stopped,true.B,decoder.io.in.ready)
    val draw = decoder.io.out.bits.draw
    val stagingValid = if (!fragCore) true.B else {
      val bankBytes = 36*gpuConfig.lanes*gpuConfig.warps
      val banks = 2*sharedShader.map(_.fragmentWorkers).getOrElse(1)
      val span = draw.kernargBankStride.pad(35)*(banks-1).U+bankBytes.U
      draw.shaderKernarg(5,0) === 0.U && draw.kernargBankStride(5,0) === 0.U &&
        draw.kernargBankStride >= (((bankBytes+63)/64)*64).U &&
        draw.shaderKernarg.pad(35)+span <= snapshot.shaderEngineStride
    }
    val validDraw = decoder.io.out.bits.validEncoding && stagingValid
    val offerDraw = enginePhase(e) === engineRun && sameDraw && !stopped && validDraw
    rp.io.draw.valid := decoder.io.out.valid && offerDraw
    rp.io.draw.bits := decoder.io.out.bits.draw
    if (fragCore) {
      rp.io.draw.bits.asInstanceOf[SceneTriangle].shaderKernarg :=
        (snapshot.shaderBase.pad(35)+snapshot.shaderEngineStride.pad(35)*e.U+
          draw.shaderKernarg.pad(35))(31,0)
    }
    decoder.io.out.ready := stopped || (enginePhase(e) === engineRun && sameDraw &&
      (!validDraw || rp.io.draw.ready))
    val invalidDraw = decoder.io.out.fire && !validDraw && !stopped
    when(running) {
      tileStatus(e) := TileBinRenderStatus.merge(tileStatus(e),
        Mux(invalidDraw,TileBinRenderStatus.invalidEncoding.U,0.U))
    }
    when(rp.io.draw.fire) {
      drawCounts(e) := drawCounts(e)+1.U
      totalDraws(e) := totalDraws(e)+1.U
    }
    val lookupFault=WireDefault(false.B)
    if (paged) {
      val lookup=gridLookups(e).io
      lookup.grid:=snapshot; lookup.in.valid:=starting; lookup.in.bits:=newTile
      lookup.cancel:=stopped && active
      lookup.out.ready:=stopped || (enginePhase(e)===engineBegin && (pass.begin.ready || lookup.out.bits.status=/=0.U))
      pass.begin.valid:=enginePhase(e)===engineBegin && lookup.out.valid && lookup.out.bits.status===0.U && !stopped
      pass.begin.bits:=lookup.out.bits.descriptor
      lookupFault:=lookup.out.fire && lookup.out.bits.status=/=0.U && !stopped
      when(lookupFault) { enginePhase(e):=engineRun }
    } else {
      pass.begin.valid := enginePhase(e) === engineBegin && !stopped
      pass.begin.bits := (if (tileSlots == 1) tiles.get(0) else tiles.get(tileId(e)(slotBits-1,0)))
    }
    if (deferResolveUntilEnd) {
      pass.begin.bits.resolve := snapshot.attachments.resolve && finalBatch
      assert(!io.seal.valid,"streaming continuation owns tile sealing")
    }
    when(pass.begin.fire) { ownerIssued(e) := true.B; enginePhase(e) := engineRun }
    when(enginePhase(e) === engineBegin && stopped) {
      enginePhase(e) := engineRun
    }
    val frontendDone = enginePhase(e) === engineRun && inputDone(e) &&
      !(decoder.io.out.valid && sameDraw) && rp.io.done
    val earlyOwnerFailure = ownerIssued(e) && !retiring.deq.valid &&
      pass.completion.valid && pass.completion.bits.status =/= TilePassStatus.success.U
    pass.end.valid := frontendDone && ownerIssued(e) && retiring.enq.ready && !stopped && !earlyOwnerFailure
    pass.end.bits := true.B
    retiring.enq.valid := frontendDone &&
      (stopped || earlyOwnerFailure || (ownerIssued(e) && pass.end.ready))
    retiring.enq.bits.tile := tileId(e)
    retiring.enq.bits.status := TileBinRenderStatus.merge(tileStatus(e),
      Mux(stopped,PrimitiveBinStatus.cancelled.U,0.U))
    retiring.enq.bits.input := inputResult(e)
    retiring.enq.bits.draws := drawCounts(e)
    retiring.enq.bits.owner := ownerIssued(e)
    when(retiring.enq.fire) {
      assert(stopped || earlyOwnerFailure || pass.end.fire)
      enginePhase(e) := engineIdle
    }
    pass.clear.valid := false.B; pass.clear.bits := 0.U.asTypeOf(pass.clear.bits)
    pass.clearDone.ready := true.B
    // A former tile can still own an export slot after the producer moves on.
    pass.cancel := stopped && active
    pass.completion.ready := false.B
    raw.ready := false.B
    when(raw.valid && sameRaw && !inputDone(e)) {
      raw.ready := true.B
      inputResult(e) := raw.bits
      inputDone(e) := true.B
      tileStatus(e) := TileBinRenderStatus.merge(
        TileBinRenderStatus.merge(tileStatus(e),raw.bits.status),
        Mux(invalidDraw,TileBinRenderStatus.invalidEncoding.U,0.U))
    }
    // Failed zero-reference results may be assigned to an engine which still
    // owns another tile. Retire those without touching the live ownership.
    out.io.enq.valid := false.B
    out.io.enq.bits := 0.U.asTypeOf(out.io.enq.bits)
    when((discardRaw || skipRaw || (stopped && enginePhase(e) === engineIdle && raw.valid))) {
      out.io.enq.valid := raw.valid
      out.io.enq.bits.identity := raw.bits.replay.identity
      out.io.enq.bits.tile := raw.bits.replay.tile
      out.io.enq.bits.engine := e.U
      out.io.enq.bits.status := TileBinRenderStatus.merge(raw.bits.status,
        Mux(stopped,PrimitiveBinStatus.cancelled.U,0.U))
      out.io.enq.bits.references := raw.bits.replay.emitted
      raw.ready := out.io.enq.ready
    }
    val events = rp.io.tileEvents.get
    io.attachmentEvents(e):=events
    for ((counter,event) <- Seq((totalLoads(e),events.loadBytes),(totalStores(e),events.storeBytes),
      (totalResolves(e),events.resolveBytes))) {
      counter.io.clear := io.begin.fire
      counter.io.addend := event
    }
    val meta = retiring.deq.bits
    val ownerResult = Mux(meta.owner,pass.completion.bits.status,TilePassStatus.cancelled.U)
    val finishTile = retiring.deq.valid && (!meta.owner || pass.completion.valid)
    val skipResult = discardRaw || skipRaw || (stopped && enginePhase(e) === engineIdle && raw.valid)
    retiring.deq.ready := finishTile && !skipResult && out.io.enq.ready
    pass.completion.ready := retiring.deq.ready && meta.owner
    when(finishTile && !skipResult) {
      out.io.enq.valid := true.B
      out.io.enq.bits.identity := snapshot.attachments.identity
      out.io.enq.bits.tile := meta.tile; out.io.enq.bits.engine := e.U
      out.io.enq.bits.status := TileBinRenderStatus.merge(
        TileBinRenderStatus.merge(meta.status,TileBinRenderStatus.attachment(ownerResult)),
        Mux(stopped,PrimitiveBinStatus.cancelled.U,0.U))
      val usable = !stopped && meta.input.replay.prefixUsable &&
        (out.io.enq.bits.status === 0.U ||
          out.io.enq.bits.status === PrimitiveBinStatus.exhausted.U) &&
        snapshot.attachments.color.store && snapshot.attachments.depth.store &&
        ownerResult === TilePassStatus.success.U &&
        meta.draws === meta.input.replay.emitted
      out.io.enq.bits.prefixUsable := usable
      out.io.enq.bits.restartValid := usable && meta.input.replay.restartValid &&
        out.io.enq.bits.status === PrimitiveBinStatus.exhausted.U
      out.io.enq.bits.restart := meta.input.replay.restart
      out.io.enq.bits.references := meta.input.replay.emitted
      out.io.enq.bits.draws := meta.draws
      out.io.enq.bits.loadBytes := Mux(meta.owner,rp.io.tileCompletionBytes.get.loadBytes,0.U)
      out.io.enq.bits.storeBytes := Mux(meta.owner,rp.io.tileCompletionBytes.get.storeBytes,0.U)
      out.io.enq.bits.resolveBytes := Mux(meta.owner,rp.io.tileCompletionBytes.get.resolveBytes,0.U)
    }
    when(out.io.enq.fire) { totalTiles(e) := totalTiles(e)+1.U }
    io.tileDone(e) <> out.io.deq

    rp.io.colorFormat := snapshot.attachments.colorFormat
    rp.io.colorBase := snapshot.attachments.color.base; rp.io.depthBase := snapshot.attachments.depth.base
    rp.io.stride := snapshot.attachments.color.rowPitch; rp.io.sampleMode := snapshot.attachments.sampleMode
    rp.io.screenW := snapshot.screenW; rp.io.screenH := snapshot.screenH
    rp.io.depthTestEnable := false.B; rp.io.depthFunc := 0.U; rp.io.depthWriteEnable := false.B
    rp.io.blendCfgEnable := false.B; rp.io.blendSrcFactor := 0.U; rp.io.blendDstFactor := 0.U
    rp.io.blendEquation := 0.U; rp.io.stencilTestEnable := false.B; rp.io.stencilFunc := 0.U
    rp.io.stencilRef := 0.U; rp.io.stencilReadMask := 0.U; rp.io.stencilWriteMask := 0.U
    rp.io.stencilFailOp := 0.U; rp.io.stencilZFailOp := 0.U; rp.io.stencilZPassOp := 0.U
    rp.io.cullMode := 0.U; rp.io.texEnable := false.B
    rp.io.texFormat := decoder.io.out.bits.texture.format
    rp.io.texBase := decoder.io.out.bits.texture.base
    rp.io.texWidth := decoder.io.out.bits.texture.texWidth
    rp.io.texHeight := decoder.io.out.bits.texture.texHeight
    rp.io.texWrapClamp := false.B; rp.io.texMaxLevel := 0.U
    io.kernelMemReq(e) <> rp.io.kernelMemReq; rp.io.kernelMemResp <> io.kernelMemResp(e)
    io.kernelWordMemReq(e) <> rp.io.kernelWordMemReq; rp.io.kernelWordMemResp <> io.kernelWordMemResp(e)
    rp.io.kernelL1Invalidate <> io.kernelL1Invalidate(e)
    io.kernelL1InvalidateDone(e) <> rp.io.kernelL1InvalidateDone
    io.kernelGlobalAtomicRequest(e) <> rp.io.kernelGlobalAtomicRequest
    rp.io.kernelGlobalAtomicResponse <> io.kernelGlobalAtomicResponse(e)
    rp.io.instructionSatp := io.instructionSatp; rp.io.vectorSatp := io.vectorSatp
    rp.io.instructionTlbFlush := io.instructionTlbFlush; rp.io.vectorTlbFlush := io.vectorTlbFlush
    sharedRouter.foreach { router =>
      rp.io.shared.get <> router.io.clients(e)
      io.sharedOwners.get(e) := router.io.clients(e).owned
    }

    val attachmentRequests = rp.io.tileMemoryRequest.get
    val attachmentResponses = rp.io.tileMemoryResponse.get
    val texture = texturePorts(e)
    rp.io.mem.req.ready := false.B
    rp.io.mem.resp.valid := false.B; rp.io.mem.resp.bits := 0.U.asTypeOf(rp.io.mem.resp.bits)
    texture.io.in <> rp.io.texMem.req; rp.io.texMem.resp <> texture.io.out
    val attachmentFault = attachmentResponses.map(r=>r.fire && r.bits.fault).reduce(_ || _)
    val textureFault = texture.io.memoryResponse.fire && texture.io.memoryResponse.bits.fault
    pass.memoryFault := false.B // response tags identify the attachment slot internally
    val rawFault = raw.fire && rawFailed && raw.bits.status =/= PrimitiveBinStatus.cancelled.U
    when(lookupFault) { tileStatus(e):=TileBinRenderStatus.merge(tileStatus(e),TileBinRenderStatus.invalidEncoding.U) }
    severe(e) := attachmentFault || textureFault || rp.io.shaderFault || invalidDraw || rawFault || earlyOwnerFailure || lookupFault
    engineStatus(e) := Seq(
      Mux(lookupFault,TileBinRenderStatus.invalidEncoding.U,0.U),
      Mux(attachmentFault,TileBinRenderStatus.attachmentFault.U,0.U),
      Mux(textureFault,TileBinRenderStatus.textureFault.U,0.U),
      Mux(rp.io.shaderFault,TileBinRenderStatus.shaderFault.U,0.U),
      Mux(invalidDraw,TileBinRenderStatus.invalidEncoding.U,0.U),
      Mux(raw.fire,raw.bits.status,0.U),
      Mux(earlyOwnerFailure,TileBinRenderStatus.attachment(pass.completion.bits.status),0.U),
      Mux(out.io.enq.fire,out.io.enq.bits.status,0.U)).reduce(TileBinRenderStatus.merge)

    val base = replayTransactions+e*(attachmentTransactions+1)
    for ((request,response,offset,n,p,bulk) <- Seq(
      (attachmentRequests(0),attachmentResponses(0),base,storeCredits+1,1+3*e,true),
      (attachmentRequests(1),attachmentResponses(1),base+storeCredits+1,storeCredits+1,2+3*e,true),
      (texture.io.memoryRequest,texture.io.memoryResponse,base+attachmentTransactions,1,3+3*e,false))) {
      val conflict = (0 until attachmentWriteCredits).map(i=>writeLive(i) &&
        writeLines(i) === request.bits.address(31,6) &&
        (!request.bits.isWrite || (writeMasks(i) & request.bits.byteMask).orR)).reduce(_ || _)
      val ownerCredits = PopCount(VecInit((0 until attachmentWriteCredits).map(i=>
        writeLive(i) && writeOwners(i) === e.U))) < writesPerEngine.U
      val eligible = if (bulk) !conflict && (!request.bits.isWrite || (writeFree.orR && ownerCredits)) else true.B
      arbiter.io.in(p).valid := request.valid && eligible
      arbiter.io.in(p).bits := request.bits
      arbiter.io.in(p).bits.transactionId := request.bits.transactionId.pad(idWidth)+offset.U(idWidth.W)
      request.ready := arbiter.io.in(p).ready && eligible
      if (bulk) io.attachmentLoadAccepted(e)(p-(1+3*e)) := request.fire && !request.bits.isWrite
      val selected = io.memoryResponse.bits.transactionId >= offset.U &&
        io.memoryResponse.bits.transactionId < (offset+n).U
      response.valid := io.memoryResponse.valid && selected
      response.bits := io.memoryResponse.bits
      response.bits.transactionId := io.memoryResponse.bits.transactionId-offset.U
    }
  }
  private val attachmentInputs = (0 until engines).flatMap(e=>Seq(arbiter.io.in(1+3*e),arbiter.io.in(2+3*e)))
  private val writeDispatch = attachmentInputs.map(r=>r.fire && r.bits.isWrite)
  private val newWrite = writeDispatch.reduce(_ || _)
  private val allocatedWrite = if (attachmentWriteCredits == 1) 0.U else PriorityEncoder(writeFree)
  private val allocatedMask = Mux(newWrite,UIntToOH(allocatedWrite,attachmentWriteCredits),0.U)
  private val retiredMask = VecInit((0 until attachmentWriteCredits).map(i=>writeLive(i) &&
    io.memoryResponse.fire && io.memoryResponse.bits.transactionId === writeIds(i))).asUInt
  when(newWrite) {
    assert(writeFree.orR)
    writeIds(allocatedWrite) := Mux1H(writeDispatch,attachmentInputs.map(_.bits.transactionId))
    writeLines(allocatedWrite) := Mux1H(writeDispatch,attachmentInputs.map(_.bits.address(31,6)))
    writeMasks(allocatedWrite) := Mux1H(writeDispatch,attachmentInputs.map(_.bits.byteMask))
    writeOwners(allocatedWrite) := Mux1H(writeDispatch,
      (0 until engines).flatMap(e=>Seq.fill(2)(e.U(log2Ceil(engines).max(1).W))))
  }
  writeLive := (writeLive & ~retiredMask) | allocatedMask
  private val statusNow = (Seq(status,Mux(io.cancel,PrimitiveBinStatus.cancelled.U,0.U),
    Mux(invalidSeal,PrimitiveBinStatus.invalidDescriptor.U,0.U),
    Mux(io.appended.fire,io.appended.bits.status,0.U),
    Mux(replay.io.completion.fire,replay.io.completion.bits.status,0.U)) ++ engineStatus.toSeq)
    .reduce(TileBinRenderStatus.merge)
  when(active) {
    status := statusNow
    when(io.cancel || invalidSeal || severe.asUInt.orR) { aborted := true.B }
    when(replayIssued && stopped) { phase := draining }
  }
  private val enginesDrained = (0 until engines).map(e => enginePhase(e) === engineIdle &&
    !decoders(e).io.busy && !pending(e).io.deq.valid && !results(e).io.deq.valid).reduce(_ && _)
  when(phase === draining && replayDone && gridLookups.map(l=> !l.io.busy).foldLeft(true.B)(_ && _) && enginesDrained && !requests.io.deq.valid && !writeLive.orR) {
    completion.identity := snapshot.attachments.identity
    completion.status := statusNow
    completion.replay := replayResult
    completion.tiles := totalTiles.reduce(_ + _); completion.draws := totalDraws.reduce(_ + _)
    completion.loadBytes := totalLoads.map(_.io.value).reduce(_ + _)
    completion.storeBytes := totalStores.map(_.io.value).reduce(_ + _)
    completion.resolveBytes := totalResolves.map(_.io.value).reduce(_ + _)
    phase := complete
  }
  when(io.completion.fire) { phase := idle; aborted := false.B }
  arbiter.io.in(0).valid := replay.io.memoryRequest.valid
  arbiter.io.in(0).bits := replay.io.memoryRequest.bits
  replay.io.memoryRequest.ready := arbiter.io.in(0).ready
  replay.io.memoryResponse.valid := io.memoryResponse.valid &&
    io.memoryResponse.bits.transactionId < replayTransactions.U
  replay.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := Mux(io.memoryResponse.bits.transactionId < replayTransactions.U,
    replay.io.memoryResponse.ready,false.B)
  for (e <- 0 until engines) {
    val base = replayTransactions+e*(attachmentTransactions+1)
    when(io.memoryResponse.bits.transactionId >= base.U &&
        io.memoryResponse.bits.transactionId < (base+storeCredits+1).U) {
      io.memoryResponse.ready := pipes(e).io.tileMemoryResponse.get(0).ready
    }
    when(io.memoryResponse.bits.transactionId >= (base+storeCredits+1).U &&
        io.memoryResponse.bits.transactionId < (base+attachmentTransactions).U) {
      io.memoryResponse.ready := pipes(e).io.tileMemoryResponse.get(1).ready
    }
    when(io.memoryResponse.bits.transactionId === (base+attachmentTransactions).U) {
      io.memoryResponse.ready := texturePorts(e).io.memoryResponse.ready
    }
  }
  requests.io.enq <> arbiter.io.out
  io.memoryRequest <> requests.io.deq
}
