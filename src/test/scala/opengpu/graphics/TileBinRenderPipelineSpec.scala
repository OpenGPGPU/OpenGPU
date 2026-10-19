package opengpu.graphics

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.reflect.DataMirror
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinRenderPipelineSpec extends AnyFlatSpec {
  import TileBinRenderTestData._
  private val banked=sys.props.get("opengpu.bankedBins").contains("true")
  private val screenW=16
  private val screenH=8
  private val engineCount=2
  private val writeCredits=2
  private val storeCredits=2
  private val attachmentTransactions=2*(storeCredits+1)
  private val replayTransactions=writeCredits+2*engineCount
  private val cfg=GraphicsConfig(screenWidth=screenW,screenHeight=screenH,
    tileSize=16,tileAttachments=true,omInflight=2)
  private class MacroBinnedRenderer(height: Int,width: Int,pagedCapacity: Int) extends TileBinRenderPipeline(cfg.copy(screenWidth=width,screenHeight=height),
    GpuConfig(lanes=4,warps=2,l2StoreEntries=4),engines=engineCount,pageBytes=128,
    writeCredits=writeCredits,tileSlots=4,cacheEntries=8,storeCredits=storeCredits,useTileSramBlackBoxes=true,pagedCapacity=pagedCapacity,bankedAttachments=banked) {
    val macroModelPresent=IO(Output(Bool()))
    private val resources=Module(new TileSramSimulationResources)
    macroModelPresent:=resources.io.present
  }
  private def module(height: Int=screenH,blackBoxes: Boolean=false,width: Int=screenW,pagedCapacity: Int=0): TileBinRenderPipeline =
    if (blackBoxes) new MacroBinnedRenderer(height,width,pagedCapacity)
    else new TileBinRenderPipeline(cfg.copy(screenWidth=width,screenHeight=height),
      GpuConfig(lanes=4,warps=2,l2StoreEntries=4),engines=engineCount,pageBytes=128,
      writeCredits=writeCredits,tileSlots=4,cacheEntries=8,storeCredits=storeCredits,pagedCapacity=pagedCapacity,bankedAttachments=banked)
  private class ContinuingHarness(pagedCapacity: Int=0) extends ContinuingTileBinRenderTestAdapter(
    cfg,GpuConfig(lanes=4,warps=2,l2StoreEntries=4),pagedCapacity=pagedCapacity)
  private class MacroContinuingRenderer(width: Int,height: Int) extends ContinuingTileBinRenderTestAdapter(
    cfg.copy(screenWidth=width,screenHeight=height),GpuConfig(lanes=4,warps=2,l2StoreEntries=4),
    pagedCapacity=16384,useTileSramBlackBoxes=true) {
    val macroModelPresent=IO(Output(Bool()))
    private val resources=Module(new TileSramSimulationResources)
    macroModelPresent:=resources.io.present
  }
  private class FullFrameTransferRenderer(width: Int=1280,height: Int=720) extends TileBinRenderPipeline(
    cfg.copy(screenWidth=width,screenHeight=height),GpuConfig(l2Sets=512,l2StoreEntries=4),
    engines=2,pageBytes=4096,writeCredits=8,tileSlots=8,cacheEntries=8,storeCredits=8,
    useTileSramBlackBoxes=true,pagedCapacity=16384,bankedAttachments=banked) {
    val macroModelPresent=IO(Output(Bool()))
    private val resources=Module(new TileSramSimulationResources)
    macroModelPresent:=resources.io.present
    // Test-only observability. No production port, control or memory-model
    // behavior changes. Stop before every externally observable service event.
    val simulationCycle=IO(Output(UInt(32.W)))
    val serviceWake=IO(Output(Bool()))
    private val elapsed=RegInit(0.U(32.W))
    elapsed:=elapsed+1.U;simulationCycle:=elapsed
    private val previousBusy=RegNext(io.engineBusy.asUInt,0.U)
    serviceWake:=io.memoryRequest.valid || io.memoryResponse.valid || io.completion.valid ||
      io.begin.valid || io.append.valid || io.seal.valid || io.end.valid || io.cancel || io.appended.valid ||
      io.engineBusy.asUInt=/=previousBusy || (0 until 2).map(e=>
        io.tileDone(e).valid || io.kernelMemReq(e).valid || io.kernelWordMemReq(e).valid ||
          io.attachmentEvents(e).loadBytes=/=0.U).reduce(_ || _)
  }
  private def advanceQuiet(dut: FullFrameTransferRenderer,limit: Int,expectedCycle: Int): Int = {
    dut.simulationCycle.expect(expectedCycle.U)
    if(dut.serviceWake.peek().litToBoolean)0 else {
      dut.clock.stepUntil(dut.serviceWake,BigInt(1),limit)
      val after=dut.simulationCycle.peek().litValue.toInt
      val elapsed=after-expectedCycle
      assert(elapsed>=0 && elapsed<=limit,s"native step exceeded allowance: $elapsed / $limit")
      elapsed
    }
  }
  case class Pending(address: Long,tid: Int,write: Boolean,data: BigInt,mask: BigInt,due: Int,fault: Boolean)
  case class Restart(primitive: Long,state: Long,sequence: Long,token: Long)
  case class Tile(tile: Int,engine: Int,status: Int,draws: Int,loads: Long,stores: Long,resolves: Long,
    prefixUsable: Boolean,restart: Option[Restart])
  case class Summary(status: Int,tiles: Int,draws: Int,loads: Long,stores: Long,resolves: Long,
    committed: Int,references: Int,resolved: Int,spills: Int)

  private class Driver(val io: TileBinRenderPipelineIO,val clock: Clock,val reset: Reset,
    val mode: Int=0,
    val extentW: Int=screenW,val extentH: Int=screenH,val resolve: Boolean=false,
    val systemMemory: Boolean=false,requestPort: DecoupledIO[ComputeMemoryRequest]=null,
    responsePort: DecoupledIO[ComputeMemoryResponse]=null,
    val width: Int=screenW,val height: Int=screenH,val pagedCapacity: Int=0,
    val colorAddress: Long=TileBinRenderTestData.colorBase,val depthAddress: Long=TileBinRenderTestData.depthBase,
    val resolveAddress: Long=TileBinRenderTestData.resolveBase,val directoryAddress: Long=0x80000L,
    val maxWait: Int=200000,tileBatch: Int=>Int = _=>0,
    binCreditWindow: Int=writeCredits,attachmentCreditWindow: Int=storeCredits,
    memoryLatency: Int=2,memoryWriteLimit: Int=Int.MaxValue) {
    private val colorBase=colorAddress; private val depthBase=depthAddress; private val resolveBase=resolveAddress
    private val replayTransactions=binCreditWindow+2*engineCount+(if (pagedCapacity>0) 1 else 0)
    private val attachmentTransactions=2*(attachmentCreditWindow+1)
    private val metadataTiles=((extentW+7)/8)*((extentH+7)/8)
    private var gridTiles=metadataTiles
    private var binSegments=Vector((binBase,binBase+4096))
    val binRequests=mutable.ArrayBuffer.empty[Long]
    private val memoryRequest=Option(requestPort).getOrElse(io.memoryRequest)
    private val memoryResponse=Option(responsePort).getOrElse(io.memoryResponse)
    var onStep: () => Unit = () => ()
    var collectServiceTrace=false
    val serviceTrace=mutable.ArrayBuffer.empty[(Int,Boolean,BigInt)]
    var quietSkippedCycles=0L
    var physicalOffset=0L
    def physical(address: Long): Long=address+physicalOffset
    val memory=mutable.LongMap.empty[Long]
    val pending=mutable.ArrayBuffer.empty[Pending]
    val live=mutable.Set.empty[Int]
    val tiles=mutable.ArrayBuffer.empty[Tile]
    private val reportedTiles=mutable.Set.empty[(Int,Int)]
    val holdTile=Array(false,false)
    val holdAttachmentResponses=Array(false,false)
    val holdSlotResponses=Array.fill(2,2)(false)
    val attachmentRequests=mutable.ArrayBuffer.empty[(Int,Int,Boolean,Long)]
    var holdTopRowWrites=false
    var holdRequests=false
    var holdResponses=false
    var faultRecord=Set.empty[Long]
    var faultNextAttachmentRead=false
    var faultNextAttachmentWrite=false
    var faultOldExport=false
    var returnedFaults=0
    var cycle=0
    var appendReplies=0
    var expectedAppendStatus=0
    var attachmentReads=0
    var usefulAttachmentLoads=0L
    var attachmentWrites=0
    var textureReads=0
    var requests=0
    var peakActive=0
    private var responding=Option.empty[Pending]
    private var heldRequest=Option.empty[BigInt]
    private val heldTile=Array.fill[Option[BigInt]](2)(None)
    private val sequences=mutable.Map.empty[Int,Int].withDefaultValue(0)
    val samples=1<<mode
    val pitch=width*samples*4
    def address(x: Int,y: Int,s: Int,depth: Boolean): Long =
      (if (depth) depthBase else colorBase)+y*pitch+(x*samples+s)*4L+physicalOffset
    def attachmentEngine(tid: Int): Option[Int] = (0 until 2).find { e =>
      val base=replayTransactions+e*(attachmentTransactions+1)
      tid>=base && tid<base+attachmentTransactions
    }
    def isTexture(tid: Int): Boolean = (0 until 2).exists(e =>
      tid==replayTransactions+e*(attachmentTransactions+1)+attachmentTransactions)
    def attachmentSlot(tid: Int,e: Int): Int =
      (tid-(replayTransactions+e*(attachmentTransactions+1)))/(attachmentCreditWindow+1)
    def topRowWrite(p: Pending): Boolean = {
      val logical=p.address-physicalOffset
      p.write && ((logical>=colorBase && logical<colorBase+8*pitch) ||
        (logical>=depthBase && logical<depthBase+8*pitch))
    }
    for (depth <- Seq(false,true); y <- 0 until height; x <- 0 until width; s <- 0 until samples)
      memory(address(x,y,s,depth))=0xdeadbeefL
    for ((draw,i) <- draws.zipWithIndex; (w,n) <- primitive(draw).zipWithIndex)
      memory(recordBase+i*128L+n*4L)=w
    for ((w,n) <- state.zipWithIndex) memory(recordBase+32*64L+n*4L)=w
    io.begin.valid.poke(false.B); io.begin.bits.poke(0.U.asTypeOf(io.begin.bits))
    io.append.valid.poke(false.B); io.append.bits.poke(0.U.asTypeOf(io.append.bits))
    io.appended.ready.poke(true.B); io.seal.valid.poke(false.B); io.seal.bits.poke(0.U)
    io.end.valid.poke(false.B); io.end.bits.poke(true.B); io.cancel.poke(false.B)
    io.completion.ready.poke(false.B); memoryRequest.ready.poke(false.B)
    memoryResponse.valid.poke(false.B)
    memoryResponse.bits.poke(0.U.asTypeOf(memoryResponse.bits))
    io.instructionSatp.poke(0.U); io.vectorSatp.poke(0.U)
    io.instructionTlbFlush.valid.poke(false.B); io.vectorTlbFlush.valid.poke(false.B)
    io.instructionTlbFlush.bits.poke(0.U.asTypeOf(io.instructionTlbFlush.bits))
    io.vectorTlbFlush.bits.poke(0.U.asTypeOf(io.vectorTlbFlush.bits))
    for (e <- 0 until 2) {
      io.tileDone(e).ready.poke(true.B)
      io.kernelMemReq(e).ready.poke(true.B); io.kernelMemResp(e).valid.poke(false.B)
      io.kernelMemResp(e).bits.poke(0.U.asTypeOf(io.kernelMemResp(e).bits))
      io.kernelWordMemReq(e).ready.poke(true.B); io.kernelWordMemResp(e).valid.poke(false.B)
      io.kernelWordMemResp(e).bits.poke(0.U.asTypeOf(io.kernelWordMemResp(e).bits))
      io.kernelL1Invalidate(e).valid.poke(false.B)
      io.kernelL1Invalidate(e).bits.poke(0.U.asTypeOf(io.kernelL1Invalidate(e).bits))
      io.kernelL1InvalidateDone(e).ready.poke(true.B)
      io.kernelGlobalAtomicRequest(e).ready.poke(true.B)
      io.kernelGlobalAtomicResponse(e).valid.poke(false.B)
      io.kernelGlobalAtomicResponse(e).bits.poke(0.U.asTypeOf(io.kernelGlobalAtomicResponse(e).bits))
    }
    reset.poke(true.B); clock.step(); reset.poke(false.B)
    def step(): Unit = {
      val req=memoryRequest
      val writeFull=memoryWriteLimit<Int.MaxValue &&
        pending.count(_.write)+responding.count(_.write)>=memoryWriteLimit
      val requestReady= !holdRequests && cycle%5!=2 && !(writeFull && req.bits.isWrite.peek().litToBoolean)
      req.ready.poke(requestReady.B)
      heldRequest.foreach { old =>
        req.valid.expect(true.B); assert(req.bits.peek().litValue==old,"offered line request changed")
      }
      heldRequest=if (req.valid.peek().litToBoolean && !requestReady) Some(req.bits.peek().litValue) else None
      if (responding.isEmpty && !holdResponses) {
        val i=pending.lastIndexWhere(p=>p.due<=cycle &&
          !(holdTopRowWrites && topRowWrite(p)) &&
          !attachmentEngine(p.tid).exists(e=>holdAttachmentResponses(e) ||
            (p.write && holdSlotResponses(e)(attachmentSlot(p.tid,e)))))
        if (i>=0) {
          val p=pending.remove(i)
          val lateFault=faultOldExport && p.write && !systemMemory &&
            attachmentEngine(p.tid).exists(e=>attachmentSlot(p.tid,e)==0)
          if (lateFault) faultOldExport=false
          responding=Some(p.copy(fault=p.fault || lateFault))
        }
      }
      memoryResponse.valid.poke(responding.nonEmpty.B)
      responding.foreach { p =>
        val data=(0 until 16).foldLeft(BigInt(0))((acc,n)=>
          acc|(BigInt(memory.getOrElse(p.address+n*4L,0xa5a5a5a5L))<<(32*n)))
        memoryResponse.bits.readData.poke(data.U(512.W))
        memoryResponse.bits.transactionId.poke(p.tid.U)
        memoryResponse.bits.fault.poke(p.fault.B)
      }
      if (responding.nonEmpty && memoryResponse.ready.peek().litToBoolean) {
        if(collectServiceTrace)serviceTrace+=((cycle,true,memoryResponse.bits.peek().litValue))
        val p=responding.get
        if (p.fault) returnedFaults+=1
        else if (p.write) {
          if (!systemMemory) {
            val attachment=attachmentEngine(p.tid).nonEmpty
            assert((attachment && p.mask!=0 && (0 until 16).forall(i=>((p.mask>>(4*i))&15)==0 || ((p.mask>>(4*i))&15)==15)) ||
              (!attachment && (p.mask.bitCount==4 ||
                (pagedCapacity>0 && p.address>=directoryAddress && p.address+64<=directoryAddress+64L*gridTiles && p.mask.bitCount==64))),
              "store must retain complete attachment words or its bin/directory mask")
          }
          for (n <- 0 until 16 if ((p.mask>>(4*n))&15)!=0) {
            val a=p.address+n*4L
            var word=memory.getOrElse(a,0L)
            for (b <- 0 until 4 if p.mask.testBit(4*n+b))
              word=(word & ~(255L<<(8*b))) | (((p.data>>(32*n+8*b)).toLong&255L)<<(8*b))
            memory(a)=word
          }
        }
        assert(live.remove(p.tid)); responding=None
      }
      if (req.valid.peek().litToBoolean && requestReady) {
        if(collectServiceTrace)serviceTrace+=((cycle,false,req.bits.peek().litValue))
        val b=req.bits; val tid=b.transactionId.peek().litValue.toInt
        val addr=b.address.peek().litValue.toLong; val write=b.isWrite.peek().litToBoolean
        assert(live.add(tid),s"live transaction $tid reused")
        val size=b.sizeLog2.peek().litValue.toInt
        if (systemMemory) assert(size==2 || size==6) else b.sizeLog2.expect(6.U)
        assert(addr%(1L<<size)==0)
        val logical=addr-physicalOffset
        if (logical>=binBase && logical<recordBase) {
          assert(binSegments.exists { case (start,end)=>logical>=start && logical+(1L<<size)<=end },
            s"bin request entered an unallocated gap: 0x${logical.toHexString}")
          binRequests+=logical
        }
        val attachment=if (systemMemory) logical>=colorBase && logical<resolveBase+65536 else attachmentEngine(tid).nonEmpty
        if (attachment) {
          assert(logical>=colorBase && logical<resolveBase+65536,"attachment left image allocations")
          if (write) attachmentWrites+=b.byteMask.peek().litValue.bitCount/4 else attachmentReads+=1
          if (!systemMemory) {
            val e=attachmentEngine(tid).get
            attachmentRequests+=((e,attachmentSlot(tid,e),write,addr))
          }
        } else if (!systemMemory && isTexture(tid)) { textureReads+=1 }
        else if (!systemMemory) assert((addr>=binBase && addr<recordBase+4096) ||
          (pagedCapacity>0 && addr>=directoryAddress && addr+64<=directoryAddress+64L*gridTiles),"bin/record/directory request left arena")
        val fault= (!write && faultRecord(logical)) ||
          (attachment && !write && faultNextAttachmentRead) ||
          (attachment && write && faultNextAttachmentWrite)
        if (attachment && !write && faultNextAttachmentRead) faultNextAttachmentRead=false
        if (attachment && write && faultNextAttachmentWrite) faultNextAttachmentWrite=false
        pending+=Pending(addr,tid,write,b.writeData.peek().litValue,b.byteMask.peek().litValue,
          cycle+memoryLatency+tid%7,fault)
        requests+=1
      }
      if (io.appended.valid.peek().litToBoolean && io.appended.ready.peek().litToBoolean) {
        io.appended.bits.status.expect(expectedAppendStatus.U); appendReplies+=1
      }
      for (e <- 0 until 2) {
        val out=io.tileDone(e); out.ready.poke((!holdTile(e)).B)
        heldTile(e).foreach { old =>
          out.valid.expect(true.B); assert(out.bits.peek().litValue==old,"held tile completion changed")
        }
        heldTile(e)=if (out.valid.peek().litToBoolean && holdTile(e)) Some(out.bits.peek().litValue) else None
        if (out.valid.peek().litToBoolean && !holdTile(e)) {
          val b=out.bits; b.identity.context.expect(3.U); b.identity.pass.expect(7.U)
          b.identity.generation.expect(11.U); b.engine.expect(e.U)
          val tile=b.tile.peek().litValue.toInt
          assert(reportedTiles.add((tileBatch(e),tile)),s"duplicate tile $tile in batch ${tileBatch(e)}")
          tiles+=Tile(tile,e,b.status.peek().litValue.toInt,b.draws.peek().litValue.toInt,
            b.loadBytes.peek().litValue.toLong,b.storeBytes.peek().litValue.toLong,
            b.resolveBytes.peek().litValue.toLong,b.prefixUsable.peek().litToBoolean,
            if (b.restartValid.peek().litToBoolean) Some(Restart(
              b.restart.primitive.peek().litValue.toLong,b.restart.state.peek().litValue.toLong,
              b.restart.sequence.peek().litValue.toLong,b.restart.token.peek().litValue.toLong)) else None)
        }
        io.kernelMemReq(e).valid.expect(false.B)
        if (!systemMemory) io.kernelWordMemReq(e).valid.expect(false.B)
      }
      usefulAttachmentLoads+=(0 until 2).map(e=>io.attachmentEvents(e).loadBytes.peek().litValue.toLong).sum
      peakActive=math.max(peakActive,(0 until 2).count(e=>io.engineBusy(e).peek().litToBoolean))
      onStep(); clock.step(); cycle+=1
    }
    def until(condition: => Boolean,max: Int=maxWait): Unit = {
      val start=cycle
      while (!condition && cycle-start<max) step()
      assert(condition,s"stalled at $cycle pending=${pending.size} live=$live tiles=$tiles replies=$appendReplies")
    }
    def begin(mutate: TileBinRenderPassDescriptor=>Unit = _=>()): Unit = {
      assert(live.isEmpty && pending.isEmpty && responding.isEmpty)
      tiles.clear(); reportedTiles.clear(); sequences.clear(); attachmentRequests.clear(); binRequests.clear(); appendReplies=0; requests=0
      attachmentReads=0; usefulAttachmentLoads=0L; attachmentWrites=0; textureReads=0; peakActive=0; returnedFaults=0
      pokeBinding(io.begin.bits,width,height,extentW,extentH,mode,resolve)
      val binding=io.begin.bits
      if (pagedCapacity>0) {
        binding.replay.bins.version.poke(2.U)
        binding.replay.bins.directoryBase.poke(directoryAddress.U)
        binding.replay.bins.directoryLimit.poke((directoryAddress+64L*metadataTiles).U)
      }
      if (colorAddress!=TileBinRenderTestData.colorBase || depthAddress!=TileBinRenderTestData.depthBase) {
        binding.attachments.color.base.poke(colorAddress.U)
        binding.attachments.color.limit.poke((colorAddress+pitch.toLong*height).U)
        binding.attachments.depth.base.poke(depthAddress.U)
        binding.attachments.depth.limit.poke((depthAddress+pitch.toLong*height).U)
        binding.attachments.resolveBase.poke(resolveAddress.U)
        binding.attachments.resolveLimit.poke((resolveAddress+width.toLong*height*4).U)
      }
      mutate(binding)
      gridTiles=binding.replay.bins.tileCount.peek().litValue.toInt
      val p=binding.replay.bins.pool
      def number(u: UInt): Long=u.peek().litValue.toLong
      binSegments=if (number(p.version)==2) {
        Vector((number(p.base),number(p.initialLimit))) ++ (0 until number(p.spillCount).toInt.min(8)).map { i=>
          (number(p.spills(i).base),number(p.spills(i).limit))
        }
      } else Vector((number(p.base),number(p.limit)))
      io.begin.valid.poke(true.B); until(io.begin.ready.peek().litToBoolean)
      step(); io.begin.valid.poke(false.B); io.begin.bits.poke(0.U.asTypeOf(io.begin.bits))
      until(io.append.ready.peek().litToBoolean || io.completion.valid.peek().litToBoolean)
    }
    def append(tile: Int,draw: Int,sequence: Option[Int]=None): Unit = {
      val b=io.append.bits; b.tile.poke(tile.U)
      b.reference.primitive.poke((firstHandle+draw*2).U); b.reference.state.poke((firstHandle+32).U)
      sequences(tile)=sequence.getOrElse(sequences(tile)+1)
      b.reference.sequence.poke(sequences(tile).U); b.reference.token.poke((100+draw).U)
      val before=appendReplies
      io.append.valid.poke(true.B); until(io.append.ready.peek().litToBoolean)
      step(); io.append.valid.poke(false.B); until(appendReplies==before+1)
    }
    def seal(tile: Int): Unit = {
      io.seal.bits.poke(tile.U); io.seal.valid.poke(true.B)
      until(io.seal.ready.peek().litToBoolean); step(); io.seal.valid.poke(false.B)
    }
    def end(): Unit = {
      io.end.valid.poke(true.B); until(io.end.ready.peek().litToBoolean)
      step(); io.end.valid.poke(false.B)
    }
    def cancel(): Unit = { io.cancel.poke(true.B); step(); io.cancel.poke(false.B) }
    def finish(advanceIdle: Option[Int=>Int]=None): Summary = {
      if(advanceIdle.isEmpty)until(io.completion.valid.peek().litToBoolean) else {
        // This path is only for the clear/store fixture after end acceptance.
        // Host responses and every ready-dependent handshake retain the normal
        // driver step. Never skip a due response, held offer or control event.
        assert(!systemMemory && physicalOffset==0 && !holdRequests && !holdResponses &&
          !holdTopRowWrites && !holdTile.contains(true) && !holdAttachmentResponses.contains(true) &&
          !holdSlotResponses.exists(_.contains(true)) && faultRecord.isEmpty &&
          !faultNextAttachmentRead && !faultNextAttachmentWrite && !faultOldExport)
        val start=cycle
        while(!io.completion.valid.peek().litToBoolean && cycle-start<maxWait) {
          step()
          if(responding.isEmpty && heldRequest.isEmpty && heldTile.forall(_.isEmpty)) {
            val due=pending.map(_.due).minOption.getOrElse(cycle+4096)
            val allowance=math.min(math.min(due-cycle,4096),maxWait-(cycle-start))
            if(allowance>0) {
              // A consumed response's valid was held through the last edge;
              // deassert it before any extra native edge can occur.
              memoryResponse.valid.poke(false.B)
              val skipped=advanceIdle.get(allowance)
              assert(skipped>=0 && skipped<=allowance)
              cycle+=skipped;quietSkippedCycles+=skipped
            }
          }
        }
        assert(io.completion.valid.peek().litToBoolean,s"native completion timed out at $cycle")
      }
      assert(live.isEmpty && pending.isEmpty && responding.isEmpty,"completion before memory drain")
      val b=io.completion.bits
      b.identity.context.expect(3.U); b.identity.pass.expect(7.U); b.identity.generation.expect(11.U)
      val s=Summary(b.status.peek().litValue.toInt,b.tiles.peek().litValue.toInt,
        b.draws.peek().litValue.toInt,b.loadBytes.peek().litValue.toLong,b.storeBytes.peek().litValue.toLong,
        b.resolveBytes.peek().litValue.toLong,b.replay.replay.committed.peek().litValue.toInt,
        b.replay.replay.references.peek().litValue.toInt,b.replay.resolved.peek().litValue.toInt,
        b.replay.replay.spillTransitions.peek().litValue.toInt)
      assert(s.tiles==tiles.size && s.draws==tiles.map(_.draws).sum)
      assert(tiles.forall(t=> !t.restart.isDefined ||
        (t.prefixUsable && t.status==PrimitiveBinStatus.exhausted)))
      assert(tiles.filter(t=>t.status!=0 && t.status!=PrimitiveBinStatus.exhausted)
        .forall(t=> !t.prefixUsable && t.restart.isEmpty))
      assert(s.loads==usefulAttachmentLoads && s.stores==attachmentWrites*4L)
      val held=b.peek().litValue
      for (i <- 0 until 5) {
        io.cancel.poke((i==2).B); io.begin.ready.expect(false.B)
        memoryRequest.valid.expect(false.B); step(); io.completion.valid.expect(true.B)
        assert(b.peek().litValue==held,"pass completion changed")
      }
      io.cancel.poke(false.B); io.completion.ready.poke(true.B); step()
      io.completion.ready.poke(false.B); io.busy.expect(false.B)
      s
    }
    def image: Vector[Long] = (for (depth <- Vector(false,true); y <- 0 until height;
      x <- 0 until width; sample <- 0 until samples) yield memory(address(x,y,sample,depth))).toVector
  }

  private def reference(mode: Int,extentW: Int,extentH: Int,sequence: Vector[Draw]=draws,
    width: Int=screenW,height: Int=screenH,
    colorAddress: Long=TileBinRenderTestData.colorBase,depthAddress: Long=TileBinRenderTestData.depthBase): Vector[Long] = {
    val colorBase=colorAddress; val depthBase=depthAddress
    val samples=1<<mode; val pitch=width*samples*4
    val memory=mutable.LongMap.empty[Long]
    def addr(x: Int,y: Int,s: Int,depth: Boolean): Long =
      (if (depth) depthBase else colorBase)+y*pitch+(x*samples+s)*4L
    for (depth <- Seq(false,true); y <- 0 until height; x <- 0 until width; s <- 0 until samples)
      memory(addr(x,y,s,depth))=if (x<extentW && y<extentH) (if (depth) depthClear else colorClear) else 0xdeadbeefL
    simulate(new RenderPipeline(cfg.copy(screenWidth=width,screenHeight=height,tileSize=0,tileAttachments=false),GpuConfig(lanes=4,warps=2))) { d =>
      d.io.draw.valid.poke(false.B); d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
      d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
      d.io.colorFormat.poke(0.U); d.io.colorBase.poke(colorBase.U); d.io.depthBase.poke(depthBase.U)
      d.io.stride.poke(pitch.U); d.io.screenW.poke(width.U); d.io.screenH.poke(height.U)
      d.io.sampleMode.poke(mode.U); d.io.depthTestEnable.poke(false.B); d.io.depthFunc.poke(0.U)
      d.io.depthWriteEnable.poke(false.B); d.io.blendCfgEnable.poke(false.B)
      d.io.blendSrcFactor.poke(0.U); d.io.blendDstFactor.poke(0.U); d.io.blendEquation.poke(0.U)
      d.io.stencilTestEnable.poke(false.B); d.io.stencilFunc.poke(0.U); d.io.stencilRef.poke(0.U)
      d.io.stencilReadMask.poke(0.U); d.io.stencilWriteMask.poke(0.U)
      d.io.stencilFailOp.poke(0.U); d.io.stencilZFailOp.poke(0.U); d.io.stencilZPassOp.poke(0.U)
      d.io.cullMode.poke(0.U); d.io.texEnable.poke(false.B); d.io.texFormat.poke(0.U)
      d.io.texBase.poke(0.U); d.io.texWidth.poke(1.U); d.io.texHeight.poke(1.U)
      d.io.texWrapClamp.poke(false.B); d.io.texMaxLevel.poke(0.U)
      d.io.texMem.req.ready.poke(true.B); d.io.texMem.resp.valid.poke(false.B)
      d.io.texMem.resp.bits.poke(0.U.asTypeOf(d.io.texMem.resp.bits))
      d.io.kernelMemReq.ready.poke(true.B); d.io.kernelMemResp.valid.poke(false.B)
      d.io.kernelMemResp.bits.poke(0.U.asTypeOf(d.io.kernelMemResp.bits))
      d.io.kernelWordMemReq.ready.poke(true.B); d.io.kernelWordMemResp.valid.poke(false.B)
      d.io.kernelWordMemResp.bits.poke(0.U.asTypeOf(d.io.kernelWordMemResp.bits))
      d.io.kernelL1Invalidate.valid.poke(false.B); d.io.kernelL1Invalidate.bits.poke(0.U.asTypeOf(d.io.kernelL1Invalidate.bits))
      d.io.kernelL1InvalidateDone.ready.poke(true.B); d.io.kernelGlobalAtomicRequest.ready.poke(true.B)
      d.io.kernelGlobalAtomicResponse.valid.poke(false.B)
      d.io.kernelGlobalAtomicResponse.bits.poke(0.U.asTypeOf(d.io.kernelGlobalAtomicResponse.bits))
      d.io.instructionSatp.poke(0.U); d.io.vectorSatp.poke(0.U)
      d.io.instructionTlbFlush.valid.poke(false.B); d.io.vectorTlbFlush.valid.poke(false.B)
      d.io.instructionTlbFlush.bits.poke(0.U.asTypeOf(d.io.instructionTlbFlush.bits))
      d.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(d.io.vectorTlbFlush.bits))
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val queue=mutable.ArrayBuffer.empty[(Boolean,Long,Long,Int)]
      var response=Option.empty[(Boolean,Long,Long,Int)]
      var cycle=0
      def tick(): Unit = {
        d.io.mem.req.ready.poke((cycle%5!=2).B)
        if (response.isEmpty) {
          val i=queue.lastIndexWhere(_._4<=cycle)
          if (i>=0) response=Some(queue.remove(i))
        }
        d.io.mem.resp.valid.poke(response.nonEmpty.B)
        response.foreach { r =>
          d.io.mem.resp.bits.write.poke(r._1.B); d.io.mem.resp.bits.addr.poke(r._2.U)
          d.io.mem.resp.bits.data.poke(memory.getOrElse(r._2,0xdeadbeefL).U)
        }
        if (response.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean) {
          val r=response.get; if (r._1) memory(r._2)=r._3
          response=None
        }
        if (d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean) {
          val b=d.io.mem.req.bits
          queue+=((b.write.peek().litToBoolean,b.addr.peek().litValue.toLong,b.data.peek().litValue.toLong,cycle+3))
        }
        d.clock.step(); cycle+=1
      }
      def until(condition: => Boolean): Unit = {
        val start=cycle
        while (!condition && cycle-start<100000) tick()
        assert(condition,s"ordered reference stalled at $cycle")
      }
      for (draw <- sequence) {
        pokeDraw(d.io.draw.bits.asInstanceOf[SceneTriangle],draw,extentW,extentH)
        d.io.draw.valid.poke(true.B); until(d.io.draw.ready.peek().litToBoolean)
        tick(); d.io.draw.valid.poke(false.B); until(d.io.done.peek().litToBoolean)
      }
      assert(queue.isEmpty && response.isEmpty)
    }
    (for (depth <- Vector(false,true); y <- 0 until height; x <- 0 until width;
      s <- 0 until samples) yield memory(addr(x,y,s,depth))).toVector
  }

  it should "render a paged 81-tile image and preserve every ordered depth stencil blend update" in {
    val size=72; val count=81
    val expected=reference(0,size,size,width=size,height=size)
    simulate(module(height=size,width=size,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,width=size,height=size,extentW=size,extentH=size,
        pagedCapacity=16384,maxWait=1000000)
      d.begin(_.replay.bins.pool.limit.poke((binBase+count*128).U))
      for (draw <- 0 until 3; tile <- 0 until count) d.append(tile,draw)
      d.end(); val s=d.finish()
      assert(s.status==0 && s.tiles==count && s.draws==3*count && s.committed==3*count && d.image==expected)
      assert(s.loads==0 && s.stores==size*size*8L)
      assert(d.tiles.map(_.tile).distinct.size==count && d.tiles.map(_.engine).toSet==Set(0,1))
      info(s"PAGED_RENDER tiles=$count draws=${s.draws} stores=${s.stores} cycles=${d.cycle}")
    }
  }

  it should "keep another paged render engine progressing while final empty-tile results are withheld" in {
    simulate(module(height=72,width=72,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,width=72,height=72,extentW=72,extentH=72,pagedCapacity=16384)
      d.holdTile(0)=true
      d.begin { b=>
        b.attachments.color.loadOp.poke(AttachmentLoadOp.discard.U)
        b.attachments.depth.loadOp.poke(AttachmentLoadOp.discard.U)
        b.attachments.color.store.poke(false.B); b.attachments.depth.store.poke(false.B)
      }
      d.end(); d.until(d.tiles.count(_.engine==1)>=20)
      assert(d.tiles.forall(_.engine==1)); dut.io.completion.valid.expect(false.B)
      d.holdTile(0)=false; val s=d.finish()
      assert(s.status==0 && s.tiles==81 && s.draws==0 && s.loads==0 && s.stores==0)
      assert(d.tiles.forall(t=> !t.prefixUsable))
    }
  }

  it should "drain an in-flight grid lookup on cancellation before rebinding the renderer" in {
    simulate(module(height=16,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,height=16,extentH=16,pagedCapacity=16384)
      d.begin(); d.append(0,0); d.seal(0)
      d.until(dut.io.descriptorLookupBusy.exists(_.peek().litToBoolean))
      d.cancel(); val failed=d.finish()
      assert(failed.status==PrimitiveBinStatus.cancelled && failed.draws==0 && d.attachmentRequests.isEmpty)
      d.begin(); d.end(); assert(d.finish().status==0)
    }
  }

  it should "attribute a paged metadata read fault and permit healthy same-backing reuse" in {
    simulate(module(height=72,width=72,pagedCapacity=16384)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,width=72,height=72,extentW=72,extentH=72,pagedCapacity=16384)
      d.begin(); d.faultRecord=Set(d.directoryAddress); d.expectedAppendStatus=PrimitiveBinStatus.memoryFault
      d.append(0,0); val failed=d.finish()
      assert(failed.status==PrimitiveBinStatus.memoryFault && failed.tiles==81 && failed.draws==0)
      assert(d.attachmentRequests.isEmpty && d.tiles.forall(t=> !t.prefixUsable))
      d.expectedAppendStatus=0; d.faultRecord=Set.empty[Long]
      d.begin(); d.end(); val healthy=d.finish()
      assert(healthy.status==0 && healthy.tiles==81 && healthy.stores==72*72*8L)
    }
  }

  it should "preserve every clear-store handshake and cycle with native idle stepping" in {
    val results=mutable.ArrayBuffer.empty[(Int,Summary,Vector[(Int,Boolean,BigInt)],Vector[Tile],Map[Long,Long],Int)]
    for(native <- Seq(false,true))simulate(new FullFrameTransferRenderer(64,32)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,width=64,height=32,extentW=64,extentH=32,
        pagedCapacity=16384,colorAddress=0x1000000L,depthAddress=0x2000000L,resolveAddress=0x3000000L,
        directoryAddress=0x8000000L,maxWait=100000,binCreditWindow=8,attachmentCreditWindow=8,
        memoryLatency=80,memoryWriteLimit=4)
      d.collectServiceTrace=true
      d.begin { b=>
        b.attachments.tileLog2.poke(4.U);b.replay.bins.tileCount.poke(8.U)
        b.replay.bins.pool.pageSizeLog2.poke(12.U);b.replay.bins.directoryLimit.poke((0x8000000L+8*64).U)
      }
      d.end()
      val summary=d.finish(if(native)Some((limit:Int)=>advanceQuiet(dut,limit,d.cycle))else None)
      assert(summary.status==0 && summary.tiles==8 && summary.draws==0 && summary.loads==0 && summary.stores==16384)
      assert(d.tiles.map(_.tile).distinct.size==8 && d.tiles.map(_.engine).toSet==Set(0,1))
      for(z <- Seq(false,true);y <- 0 until 32;x <- 0 until 64)
        assert(d.memory(d.address(x,y,0,z))==(if(z)depthClear else colorClear))
      if(native)assert(d.quietSkippedCycles>0)else assert(d.quietSkippedCycles==0)
      results+=((d.cycle,summary,d.serviceTrace.toVector,d.tiles.toVector,d.memory.toMap,d.peakActive))
      info(s"NATIVE_CLEAR_SERVICE native=$native cycles=${d.cycle} skipped=${d.quietSkippedCycles} handshakes=${d.serviceTrace.size}")
    }
    assert(results(0)==results(1),"native idle stepping changed cycles, requests/responses, tile ownership or final image")
  }

  it should "clear and store a complete 720p frame through real SRAM owners at a bounded memory service rate" in {
    val width=1280;val height=720;val count=3600
    val cb=0x1000000L;val db=0x2000000L;val rb=0x3000000L;val directory=0x8000000L
    simulate(new FullFrameTransferRenderer) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,width=width,height=height,extentW=width,extentH=height,
        pagedCapacity=16384,colorAddress=cb,depthAddress=db,resolveAddress=rb,directoryAddress=directory,
        maxWait=12000000,binCreditWindow=8,attachmentCreditWindow=8,memoryLatency=80,memoryWriteLimit=4)
      d.begin { b=>
        b.attachments.tileLog2.poke(4.U);b.replay.bins.tileCount.poke(count.U)
        b.replay.bins.pool.pageSizeLog2.poke(12.U)
        b.replay.bins.directoryLimit.poke((directory+count*64L).U)
      }
      d.end();val summary=d.finish(Some((limit:Int)=>advanceQuiet(dut,limit,d.cycle)))
      assert(d.quietSkippedCycles>0)
      info(s"BIN_CLEAR_NATIVE_IDLE skippedCycles=${d.quietSkippedCycles}")
      assert(summary.status==0 && summary.tiles==count && summary.draws==0 && summary.committed==0)
      assert(summary.loads==0 && summary.stores==width*height*8L && summary.resolves==0)
      assert(d.tiles.map(_.tile).distinct.size==count && d.tiles.map(_.engine).toSet==Set(0,1))
      assert(d.tiles.forall(t=>t.status==0 && t.loads==0 && t.stores==2048))
      for(z <- Seq(false,true);y <- 0 until height;x <- 0 until width)
        assert(d.memory(d.address(x,y,0,z))==(if(z)depthClear else colorClear),s"full-frame clear mismatch ($x,$y,$z)")
      info(s"BIN_CLEAR_STORE_720P tiles=$count exactWords=${width*height*2} usefulBytes=${summary.stores} writeLines=${d.attachmentRequests.count(_._3)} cycles=${d.cycle} latency=80 writeWindow=4")
    }
  }

  it should "render a complete sparse 720p image through SRAM-backed paged tile ownership" in {
    val width=1280; val height=720; val columns=80; val count=3600
    val cb=0x1000000L; val db=0x2000000L; val rb=0x3000000L; val directory=0x8000000L
    val chosen=Vector(0,22*columns+39,count-1)
    val scene=chosen.flatMap { tile=>
      val x=(tile%columns)*16; val y=(tile/columns)*16
      draws.map(_.copy(clip=Vector((-65536L,-65536L,0L,65536L),
        (196608L,-65536L,0L,65536L),(-65536L,196608L,0L,65536L)),scissor=Some((x,y,x+16,y+16))))
    }
    val expected=reference(0,width,height,scene,width=width,height=height,colorAddress=cb,depthAddress=db)
    for (tile <- chosen) {
      val x=(tile%columns)*16; val y=(tile/columns)*16
      assert((for (dy <- 0 until 16; dx <- 0 until 16) yield expected((y+dy)*width+x+dx)).exists(_!=colorClear),
        s"720p reference did not shade tile $tile")
    }
    simulate(module(height=height,width=width,pagedCapacity=16384,blackBoxes=true)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,width=width,height=height,extentW=width,extentH=height,
        pagedCapacity=16384,colorAddress=cb,depthAddress=db,resolveAddress=rb,directoryAddress=directory,maxWait=4000000)
      for (depth <- Seq(false,true); y <- 0 until height; x <- 0 until width)
        d.memory(d.address(x,y,0,depth))=if (depth) depthClear else colorClear
      for ((draw,i) <- scene.zipWithIndex; (w,n) <- primitive(draw).zipWithIndex)
        d.memory(recordBase+i*128L+n*4L)=w
      d.begin { b=>
        b.attachments.tileLog2.poke(4.U); b.replay.bins.tileCount.poke(count.U)
        b.replay.bins.directoryLimit.poke((directory+count*64L).U)
        b.attachments.color.loadOp.poke(AttachmentLoadOp.load.U)
        b.attachments.depth.loadOp.poke(AttachmentLoadOp.load.U)
      }
      for ((tile,i) <- chosen.zipWithIndex; draw <- 0 until 3) d.append(tile,i*3+draw)
      d.end(); val s=d.finish()
      assert(s.status==0 && s.tiles==count && s.draws==9 && s.committed==9 && d.image==expected)
      assert(d.tiles.map(_.tile).distinct.size==count && d.tiles.map(_.engine).toSet==Set(0,1))
      assert(d.tiles.filter(t=> !chosen.contains(t.tile)).forall(t=>t.draws==0 && t.loads==0 && t.stores==0))
      assert(s.loads==d.tiles.map(_.loads).sum && s.stores==d.tiles.map(_.stores).sum)
      info(s"PAGED_RENDER_720P tiles=$count activeTiles=${chosen.size} draws=${s.draws} loads=${s.loads} stores=${s.stores} cycles=${d.cycle} exactWords=${expected.size}")
    }
  }


  it should "automatically continue a complete sparse 720p image through real SRAM banks" in {
    val width=1280; val height=720; val columns=80; val count=3600
    val cb=0x1000000L; val db=0x2000000L; val rb=0x3000000L; val directory=0x8000000L
    val chosen=Vector(0,22*columns+39,count-1)
    val records=chosen.flatMap { tile=>
      val x=(tile%columns)*16; val y=(tile/columns)*16
      draws.map(_.copy(clip=Vector((-65536L,-65536L,0L,65536L),
        (196608L,-65536L,0L,65536L),(-65536L,196608L,0L,65536L)),scissor=Some((x,y,x+16,y+16))))
    }
    val scene=chosen.indices.flatMap(i=>Vector.tabulate(9)(n=>records(i*3+n%3))).toVector
    val expected=reference(0,width,height,scene,width=width,height=height,colorAddress=cb,depthAddress=db)
    simulate(new MacroContinuingRenderer(width,height)) { h=>
      h.restartLimit.poke(2.U)
      val d=new Driver(h.io,h.clock,h.reset,width=width,height=height,extentW=width,extentH=height,
        pagedCapacity=16384,colorAddress=cb,depthAddress=db,resolveAddress=rb,directoryAddress=directory,
        maxWait=6000000,tileBatch=e=>h.tileBatches(e).peek().litValue.toInt)
      for (depth <- Seq(false,true); y <- 0 until height; x <- 0 until width)
        d.memory(d.address(x,y,0,depth))=if (depth) depthClear else colorClear
      for ((draw,i) <- records.zipWithIndex; (w,n) <- primitive(draw).zipWithIndex)
        d.memory(recordBase+i*128L+n*4L)=w
      d.begin { b=>
        b.attachments.tileLog2.poke(4.U); b.replay.bins.tileCount.poke(count.U)
        b.replay.bins.directoryLimit.poke((directory+count*64L).U)
        b.replay.bins.pool.limit.poke((binBase+3*128).U)
        b.attachments.color.loadOp.poke(AttachmentLoadOp.load.U)
        b.attachments.depth.loadOp.poke(AttachmentLoadOp.load.U)
      }
      for (n <- 0 until 9; (tile,i) <- chosen.zipWithIndex) d.append(tile,i*3+n%3)
      d.end(); d.until(h.io.completion.valid.peek().litToBoolean)
      h.batches.expect(2.U); h.restarts.expect(1.U)
      val s=d.finish()
      assert(s.status==0 && s.tiles==2*count && s.draws==27 && s.committed==27 && s.references==27 && s.resolved==27)
      assert(d.appendReplies==27 && d.image==expected,"720p automatic continuation differs from uninterrupted samples")
      assert(d.tiles.filter(t=> !chosen.contains(t.tile)).forall(t=>t.draws==0 && t.loads==0 && t.stores==0))
      assert(d.tiles.map(_.tile).distinct.size==count && d.tiles.groupBy(_.tile).values.forall(_.size==2))
      info(s"BIN_RENDER_CONTINUE_720P tiles=$count visits=${s.tiles} refs=${s.committed} batches=2 restarts=1 loads=${s.loads} stores=${s.stores} readLines=${d.attachmentReads} writeLines=${d.attachmentRequests.count(_._3)} cycles=${d.cycle}")
    }
  }

  for (blackBoxes <- Seq(false,true)) {
  it should s"render interleaved bins on two persistent engines and match the ordered depth stencil blend image with SRAM macros=$blackBoxes" in {
    val expected=reference(0,screenW,screenH)
    simulate(module(blackBoxes=blackBoxes)) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.begin()
      for (draw <- draws.indices; tile <- 0 until 2) d.append(tile,draw)
      d.end(); val s=d.finish()
      assert(s.status==0 && s.tiles==2 && s.draws==6 && s.references==6 && s.resolved==6)
      assert(s.loads==0 && s.stores==screenW*screenH*8L)
      assert(d.image==expected,"binned final samples differ from ordered reference")
      assert(d.peakActive==2)
      info(s"BIN_RENDER_IMAGE draws=6 samples=128 loadBytes=${s.loads} storeBytes=${s.stores} cycles=${d.cycle}")
    }
  }
  }

  for ((cancel,lateFault) <- Seq((false,false),(true,false),(false,true))) {
  it should s"double-buffer actual tile rendering behind held exports with cancellation=$cancel lateFault=$lateFault" in {
    val wide=draws.map(d=>d.copy(clip=Vector((-65536L,-65536L,0L,65536L),
      (196608L,-65536L,0L,65536L),(-65536L,196608L,0L,65536L))))
    val expected=if (cancel || lateFault) Vector.empty else reference(0,16,16,wide,height=16)
    simulate(module(height=16)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,extentH=16,height=16)
      for ((draw,i) <- wide.zipWithIndex; (w,n) <- primitive(draw).zipWithIndex)
        d.memory(recordBase+i*128L+n*4L)=w
      for (z <- Seq(false,true); y <- 0 until 16; x <- 0 until 16)
        d.memory(d.address(x,y,0,z))=if (z) depthClear else colorClear
      for (e <- 0 until 2) d.holdSlotResponses(e)(0)=true
      d.begin { b=>
        b.attachments.color.loadOp.poke(AttachmentLoadOp.load.U)
        b.attachments.depth.loadOp.poke(AttachmentLoadOp.load.U)
      }
      for (i <- 0 until 3; tile <- 0 until 4) d.append(tile,i)
      d.end()
      d.until((0 until 2).forall(e=>d.attachmentRequests.exists(r=>r._1==e && r._2==1 && !r._3)))
      assert((0 until 2).forall(e=>d.live.exists(t=>d.attachmentEngine(t).contains(e) && d.attachmentSlot(t,e)==0)))
      assert(d.tiles.isEmpty,"exports completed despite withheld first-slot acknowledgements")
      val overlap=d.cycle
      if (cancel) d.cancel()
      d.faultOldExport=lateFault
      for (e <- 0 until 2) d.holdSlotResponses(e)(0)=false
      val s=d.finish()
      assert(s.tiles==4 && s.loads==d.tiles.map(_.loads).sum && s.stores==d.tiles.map(_.stores).sum)
      if (cancel || lateFault) {
        assert(s.status==(if (lateFault) TileBinRenderStatus.attachmentFault else PrimitiveBinStatus.cancelled) &&
          d.tiles.forall(t=> !t.prefixUsable && t.restart.isEmpty))
        d.begin(); d.end(); assert(d.finish().status==0)
      } else {
        assert(s.status==0 && s.draws==12 && d.image==expected)
        assert(d.tiles.forall(t=>t.prefixUsable && t.draws==3))
        info(s"BIN_RENDER_DOUBLE tiles=4 draws=12 overlapAt=$overlap loads=${s.loads} stores=${s.stores} cycles=${d.cycle}")
      }
    }
  }
  }

  it should "keep empty tile ownership ahead of prefetched draws for following tiles" in {
    val expected=reference(0,16,16,draws.map(_.copy(scissor=Some((0,8,16,16)))),height=16)
    simulate(module(height=16)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,extentH=16,height=16)
      d.begin()
      for (i <- 0 until 3; tile <- 2 until 4) d.append(tile,i)
      d.end(); val s=d.finish()
      assert(s.status==0 && s.tiles==4 && s.draws==6 && d.image==expected)
      assert(d.tiles.filter(_.tile<2).forall(_.draws==0))
      assert(d.tiles.filter(_.tile>=2).forall(_.draws==3))
    }
  }

  it should "render partial 4x tiles and resolve exact samples without touching guard pixels" in {
    val width=13; val height=7
    val expected=reference(2,width,height)
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset,mode=2,extentW=width,extentH=height,resolve=true)
      d.begin()
      for (draw <- draws.indices; tile <- 0 until 2) d.append(tile,draw)
      d.end(); val s=d.finish()
      assert(s.status==0 && d.image==expected)
      assert(s.loads==0 && s.resolves==width*height*4L && s.stores==width*height*(4*8L+4))
      for (y <- 0 until height; x <- 0 until width) {
        val samples=(0 until 4).map(i=>d.memory(d.address(x,y,i,depth=false)))
        val resolved=(0 until 4).foldLeft(0L) { (v,b) =>
          val sum=samples.map(w=>(w>>(b*8))&255).sum
          v|(((sum+2)/4)<<(b*8))
        }
        assert(d.memory(resolveBase+y*screenW*4+x*4L)==resolved)
      }
      info(s"BIN_RENDER_MSAA draws=6 samples=${width*height*4} storeBytes=${s.stores} resolveBytes=${s.resolves} cycles=${d.cycle}")
    }
  }

  it should "clear and store every empty tile at pass end" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.begin(); d.end(); val s=d.finish()
      assert(s.status==0 && s.tiles==2 && s.draws==0 && s.references==0)
      assert(s.loads==0 && s.stores==screenW*screenH*8L)
      for (y <- 0 until screenH; x <- 0 until screenW) {
        assert(d.memory(d.address(x,y,0,false))==colorClear)
        assert(d.memory(d.address(x,y,0,true))==depthClear)
      }
    }
  }

  it should "complete another tile while one engine retains attachment acknowledgements and results" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.holdAttachmentResponses(0)=true; d.holdTile(0)=true
      d.begin(); d.append(0,0); d.seal(0)
      d.append(1,1); d.seal(1)
      d.until(d.tiles.exists(_.tile==1))
      assert(!d.tiles.exists(_.tile==0))
      d.holdAttachmentResponses(0)=false
      d.until(dut.io.tileDone(0).valid.peek().litToBoolean)
      for (_ <- 0 until 8) d.step()
      d.holdTile(0)=false
      d.end(); val s=d.finish()
      assert(s.status==0 && s.tiles==2 && s.draws==2)
    }
  }

  it should "reject malformed record encodings, drain both engines and rebind clean caches" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.memory(recordBase+32*64L)=0
      d.begin(); d.append(0,0); d.append(1,0); d.end(); val s=d.finish()
      assert(s.status==TileBinRenderStatus.invalidEncoding && s.draws==0 && s.stores==0)
      d.memory(recordBase+32*64L)=TileBinDrawLayout.stateMagic.toLong
      d.begin(); d.append(0,0); d.end()
      assert(d.finish().status==0)
    }
  }

  it should "retain record read fault attribution through cancellation without exporting incomplete tiles" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.faultRecord=Set(recordBase+64)
      d.begin(); d.append(0,0); d.append(1,0); d.end(); val s=d.finish()
      assert(s.status==PrimitiveBinStatus.memoryFault && s.draws==0 && s.stores==0)
      assert(d.returnedFaults>0)
    }
  }

  it should "fail attachment loads and late stores while draining all line owners" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.faultNextAttachmentRead=true
      d.begin(b=>{
        b.attachments.color.loadOp.poke(AttachmentLoadOp.load.U)
        b.attachments.depth.loadOp.poke(AttachmentLoadOp.load.U)
      })
      d.append(0,0); d.append(1,0); d.end(); val load=d.finish()
      assert(load.status==TileBinRenderStatus.attachmentFault && d.returnedFaults==1)
      d.faultNextAttachmentWrite=true
      d.begin(); d.end(); val store=d.finish()
      assert(store.status==TileBinRenderStatus.attachmentFault && d.returnedFaults==1)
      d.begin(); d.end(); assert(d.finish().status==0)
    }
  }

  it should "cancel with an offered bin request, retain it through drain and allow reuse" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.begin(); d.holdRequests=true
      dut.io.append.bits.tile.poke(0.U)
      dut.io.append.bits.reference.primitive.poke(firstHandle.U)
      dut.io.append.bits.reference.state.poke((firstHandle+32).U)
      dut.io.append.bits.reference.sequence.poke(1.U); dut.io.append.bits.reference.token.poke(1.U)
      dut.io.append.valid.poke(true.B); d.until(dut.io.append.ready.peek().litToBoolean)
      d.step(); dut.io.append.valid.poke(false.B)
      d.until(dut.io.memoryRequest.valid.peek().litToBoolean)
      d.cancel()
      for (_ <- 0 until 8) { d.step(); dut.io.completion.valid.expect(false.B) }
      d.expectedAppendStatus=PrimitiveBinStatus.cancelled
      d.holdRequests=false
      val s=d.finish()
      assert(s.status==PrimitiveBinStatus.cancelled && s.stores==0)
      d.expectedAppendStatus=0
      d.begin(); d.end(); assert(d.finish().status==0)
    }
  }

  it should "reject invalid grid bindings before any memory traffic" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.begin(b=>b.replay.bins.tileCount.poke(1.U))
      val s=d.finish()
      assert(s.status==PrimitiveBinStatus.invalidDescriptor && s.tiles==0 && d.requests==0)
    }
  }

  it should "render more than eight ordered references per tile across page links" in {
    val sequence=Vector.tabulate(9)(i=>draws(i%3))
    val expected=reference(0,screenW,screenH,sequence)
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.begin()
      for (i <- 0 until 9; tile <- 0 until 2) d.append(tile,i%3)
      d.end(); val s=d.finish()
      assert(s.status==0 && s.draws==18 && s.committed==18 && s.references==18 && s.resolved==18)
      assert(s.loads==0 && s.stores==screenW*screenH*8L && d.image==expected)
      info(s"BIN_RENDER_PAGES references=18 draws=18 stores=${s.stores} cycles=${d.cycle}")
    }
  }



  for ((paged,mode,drawCount) <- Seq((false,0,19),(true,0,19),(true,2,9))) {
  it should s"automatically continue a bounded bin stream without producer resubmission paged=$paged MSAA=$mode" in {
    val expected=reference(mode,screenW,screenH,Vector.tabulate(drawCount)(i=>draws(i%3)))
    simulate(new ContinuingHarness(if (paged) 16384 else 0)) { h=>
      h.restartLimit.poke(8.U)
      val d=new Driver(h.io,h.clock,h.reset,mode=mode,resolve=mode>0,pagedCapacity=if (paged) 16384 else 0,tileBatch=e=>h.tileBatches(e).peek().litValue.toInt)
      val eventBatches=mutable.ArrayBuffer.empty[(Int,Long)]
      val replyBatches=mutable.ArrayBuffer.empty[Int]
      d.onStep=()=>{
        for (e <- 0 until 2 if h.io.tileDone(e).valid.peek().litToBoolean && h.io.tileDone(e).ready.peek().litToBoolean)
          eventBatches+=((h.tileBatches(e).peek().litValue.toInt,h.io.tileDone(e).bits.resolveBytes.peek().litValue.toLong))
        if (h.io.appended.valid.peek().litToBoolean && h.io.appended.ready.peek().litToBoolean)
          replyBatches+=h.appendedBatch.peek().litValue.toInt
      }
      d.begin(b=>b.replay.bins.pool.limit.poke((binBase+256).U))
      for (i <- 0 until drawCount; tile <- 0 until 2) d.append(tile,i%3)
      d.end()
      d.until(h.io.completion.valid.peek().litToBoolean)
      val batches=h.batches.peek().litValue.toInt; val restarts=h.restarts.peek().litValue.toInt
      val s=d.finish()
      assert(s.status==0 && s.draws==2*drawCount && s.committed==2*drawCount && s.references==2*drawCount && s.resolved==2*drawCount)
      assert(batches==(2*drawCount+11)/12 && restarts==batches-1 && s.tiles==2*batches)
      assert(d.appendReplies==2*drawCount && replyBatches.size==2*drawCount)
      assert(replyBatches.sliding(2).forall(x=>x.size<2 || x(0)<=x(1)))
      assert(eventBatches.size==2*batches && eventBatches.forall {case (batch,bytes)=>batch==batches-1 || bytes==0})
      assert(d.image==expected,"automatic continuation changed ordered blend/depth/stencil samples")
      if (mode>0) {
        assert(s.resolves==screenW*screenH*4L)
        for (y <- 0 until screenH; x <- 0 until screenW) {
          val samples=(0 until 4).map(i=>d.memory(d.address(x,y,i,depth=false)))
          val resolved=(0 until 4).foldLeft(0L) { (v,b)=>v|(((samples.map(w=>(w>>(b*8))&255).sum+2)/4)<<(b*8)) }
          assert(d.memory(resolveBase+y*screenW*4+x*4L)==resolved)
        }
      }
      info(s"BIN_RENDER_CONTINUE paged=$paged mode=$mode refs=${s.committed} batches=$batches restarts=$restarts loads=${s.loads} stores=${s.stores} resolves=${s.resolves} cycles=${d.cycle}")
    }
  }
  }

  it should "preserve per-tile order when a continued batch does not touch an older tile" in {
    val scene=Vector.tabulate(14)(i=>draws(i%3).copy(scissor=Some((0,0,8,8)))) ++
      Vector.tabulate(13)(i=>draws(i%3).copy(scissor=Some((8,0,16,8))))
    val expected=reference(0,screenW,screenH,scene)
    simulate(new ContinuingHarness(16384)) { h=>
      h.restartLimit.poke(8.U)
      val d=new Driver(h.io,h.clock,h.reset,pagedCapacity=16384,tileBatch=e=>h.tileBatches(e).peek().litValue.toInt)
      d.begin(b=>b.replay.bins.pool.limit.poke((binBase+256).U))
      for (i <- 0 until 13) d.append(0,i%3)
      for (i <- 0 until 13) d.append(1,i%3)
      d.append(0,1); d.end(); d.until(h.io.completion.valid.peek().litToBoolean)
      h.restarts.expect(3.U)
      val s=d.finish()
      assert(s.status==0 && s.committed==27 && d.image==expected)
      // Begin a new stream, then reject a duplicate for tile zero in a batch
      // whose current bin for that tile is empty, after two internal restarts.
      d.begin(b=>b.replay.bins.pool.limit.poke((binBase+256).U))
      for (i <- 0 until 13) d.append(0,i%3)
      for (i <- 0 until 7) d.append(1,i%3)
      d.expectedAppendStatus=PrimitiveBinStatus.invalidOrder
      d.append(0,0,Some(13))
      val failed=d.finish()
      assert(failed.status==PrimitiveBinStatus.invalidOrder && failed.committed==20)
    }
  }

  for ((zeroPages,budget) <- Seq((false,0),(false,1),(true,8))) {
  it should s"terminate an automatic stream on exhausted budget or zero progress zeroPages=$zeroPages budget=$budget" in {
    simulate(new ContinuingHarness()) { h=>
      h.restartLimit.poke(budget.U)
      val d=new Driver(h.io,h.clock,h.reset,tileBatch=e=>h.tileBatches(e).peek().litValue.toInt)
      d.begin(b=>b.replay.bins.pool.limit.poke((binBase+(if (zeroPages) 0 else 256)).U))
      if (!zeroPages) for (i <- 0 until 6*(budget+1); tile <- 0 until 2) d.append(tile,i%3)
      d.expectedAppendStatus=PrimitiveBinStatus.exhausted
      d.append(0,0); d.until(h.io.completion.valid.peek().litToBoolean)
      h.restarts.expect((if (zeroPages) 0 else budget).U); h.batches.expect((if (zeroPages) 1 else budget+1).U)
      val s=d.finish()
      assert(s.status==PrimitiveBinStatus.exhausted && s.committed==(if (zeroPages) 0 else 12*(budget+1)))
    }
  }
  }

  it should "invalidate a continued image on a reload fault without retrying the failed batch" in {
    simulate(new ContinuingHarness(16384)) { h=>
      h.restartLimit.poke(8.U)
      val d=new Driver(h.io,h.clock,h.reset,pagedCapacity=16384,tileBatch=e=>h.tileBatches(e).peek().litValue.toInt)
      d.begin(b=>b.replay.bins.pool.limit.poke((binBase+256).U))
      for (i <- 0 until 9; tile <- 0 until 2) d.append(tile,i%3)
      d.faultNextAttachmentRead=true; d.end(); d.until(h.io.completion.valid.peek().litToBoolean)
      h.restarts.expect(1.U); h.batches.expect(2.U)
      val s=d.finish()
      assert(s.status==TileBinRenderStatus.attachmentFault && d.returnedFaults>0)
    }
  }

  for (paged <- Seq(false,true)) {
  it should s"render ordered blends across discontiguous spill backing without resubmission paged=$paged" in {
    val sequence=Vector.tabulate(13)(i=>draws(i%3))
    val expected=reference(0,screenW,screenH,sequence)
    simulate(module(pagedCapacity=if (paged) 16384 else 0)) { dut=>
      val d=new Driver(dut.io,dut.clock,dut.reset,pagedCapacity=if (paged) 16384 else 0)
      d.begin { b=>
        val p=b.replay.bins.pool
        p.version.poke(2.U); p.initialLimit.poke((binBase+256).U); p.spillCount.poke(2.U)
        p.spills(0).base.poke((binBase+0x2000).U); p.spills(0).limit.poke((binBase+0x2100).U)
        p.spills(1).base.poke((binBase+0x6000).U); p.spills(1).limit.poke((binBase+0x6100).U)
        p.limit.poke((binBase+0x6100).U)
      }
      for (i <- 0 until 13; tile <- 0 until 2) d.append(tile,i%3)
      d.end(); val s=d.finish()
      assert(s.status==0 && s.draws==26 && s.committed==26 && s.references==26 && s.resolved==26 && s.spills==2)
      assert(d.tiles.forall(t=>t.prefixUsable && t.restart.isEmpty && t.draws==13))
      assert(d.image==expected,"segmented bin image differs from uninterrupted draw order")
      val segments=Vector((binBase,binBase+256),(binBase+0x2000,binBase+0x2100),(binBase+0x6000,binBase+0x6100))
      val touched=d.memory.keysIterator.filter(a=>a>=binBase && a<binBase+0x6100).toVector
      assert(touched.forall(a=>segments.exists {case (start,end)=>a>=start && a<end}))
      info(s"BIN_RENDER_SPILL paged=$paged refs=${s.committed} transitions=${s.spills} loads=${s.loads} stores=${s.stores} cycles=${d.cycle}")
    }
  }
  }

  it should "resume exact exported prefixes after arena exhaustion without dropping or repeating draws" in {
    val sequence=Vector.tabulate(9)(i=>draws(i%3))
    val expected=reference(0,screenW,screenH,sequence)
    val touchedSamples=expected.drop(screenW*screenH).count(_!=depthClear)
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.begin { b=>
        val p=b.replay.bins.pool
        p.version.poke(2.U); p.initialLimit.poke((binBase+128).U); p.spillCount.poke(1.U)
        p.spills(0).base.poke((binBase+0x2000).U); p.spills(0).limit.poke((binBase+0x2080).U)
        p.limit.poke((binBase+0x2080).U)
      }
      for (i <- 0 until 6; tile <- 0 until 2) d.append(tile,i%3)
      d.expectedAppendStatus=PrimitiveBinStatus.exhausted
      for (tile <- 0 until 2) d.append(tile,0)
      d.end(); val prefix=d.finish()
      assert(prefix.status==PrimitiveBinStatus.exhausted && prefix.committed==12 &&
        prefix.references==12 && prefix.draws==12 && prefix.spills==1 && prefix.loads==0 && prefix.stores==1024)
      assert(d.tiles.size==2 && d.tiles.forall(t=>t.prefixUsable && t.draws==6 &&
        t.restart.contains(Restart(firstHandle,firstHandle+32,7,100))))
      // The whole binding and its stores have drained before reusing the bin
      // arena. Load both saved tiles; even a completed tile must not be cleared
      // again when another tile resumes its rejected reference.
      d.expectedAppendStatus=0
      d.begin { b=>
        b.attachments.color.loadOp.poke(AttachmentLoadOp.load.U)
        b.attachments.depth.loadOp.poke(AttachmentLoadOp.load.U)
      }
      for (i <- 6 until 9; tile <- 0 until 2) d.append(tile,i%3,Some(i+1))
      d.end(); val resumed=d.finish()
      // All resumed depths fail against the nearer committed depth. The
      // stencil z-fail operation touches only these depth/stencil words;
      // lazy load and dirty export leave the saved color plane untouched.
      assert(d.image==expected,"restarted blend/depth/stencil image differs from uninterrupted ordering")
      info(s"BIN_RENDER_RESTART_CHECK banked=$banked touchedSamples=$touchedSamples status=${resumed.status} draws=${resumed.draws} committed=${resumed.committed} loads=${resumed.loads} stores=${resumed.stores} expectedBytes=${touchedSamples*4L}")
      assert(resumed.status==0 && resumed.draws==6 && resumed.committed==6 &&
        resumed.loads==touchedSamples*4L && resumed.stores==touchedSamples*4L)
      assert(d.tiles.forall(t=>t.prefixUsable && t.restart.isEmpty))
      info(s"BIN_RENDER_RESTART prefixDraws=${prefix.draws} resumedDraws=${resumed.draws} loads=${resumed.loads} stores=${prefix.stores+resumed.stores} cycles=${d.cycle}")
    }
  }

  it should "invalidate restartable attachment prefixes when their exports fault" in {
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      d.begin(b=>b.replay.bins.pool.limit.poke((binBase+128).U))
      for (i <- 0 until 6) d.append(0,i%3)
      d.expectedAppendStatus=PrimitiveBinStatus.exhausted
      d.faultNextAttachmentWrite=true
      d.append(0,0)
      // Publish only the exhausted tile. Keep the empty second tile unsealed
      // so the injected store fault belongs to the exhausted tile.
      d.seal(0)
      d.until(d.returnedFaults>0)
      val s=d.finish()
      assert(s.status==TileBinRenderStatus.attachmentFault && d.returnedFaults==1)
      assert(d.tiles.find(_.tile==0).exists(t=> !t.prefixUsable && t.restart.isEmpty))
      d.expectedAppendStatus=0
      d.begin(); d.end(); assert(d.finish().status==0)
    }
  }

  for (discard <- Seq(false,true)) {
    it should s"report a zero-reference exhausted prefix with attachment discard=$discard" in {
      simulate(module()) { dut =>
        val d=new Driver(dut.io,dut.clock,dut.reset)
        d.begin { b=>
          b.replay.bins.pool.limit.poke(binBase.U)
          b.attachments.color.store.poke((!discard).B)
          b.attachments.depth.store.poke((!discard).B)
        }
        d.expectedAppendStatus=PrimitiveBinStatus.exhausted
        d.append(0,0); d.end(); val s=d.finish()
        assert(s.status==PrimitiveBinStatus.exhausted && s.committed==0 && s.draws==0)
        val t=d.tiles.find(_.tile==0).get
        assert(t.prefixUsable== !discard)
        assert(t.restart== (if (discard) None else Some(Restart(firstHandle,firstHandle+32,1,100))))
        assert(s.stores==(if (discard) 0 else 1024))
      }
    }
  }

  for ((translated,frameHeight,continuing) <- Seq((false,8,false),(true,8,false),(true,16,false),(true,280,false),(true,280,true))) {
  it should s"render both tile engines through actual shared CUs with Sv32=$translated height=$frameHeight and recover faults continuing=$continuing" in {
    val scene=if (frameHeight==8) draws else draws.map(d=>d.copy(clip=Vector((-65536L,-65536L,0L,65536L),
      (196608L,-65536L,0L,65536L),(-65536L,196608L,0L,65536L))))
    val paged=frameHeight>16
    val visits=if (continuing) 9 else 3
    val perTileScene=Vector.tabulate(visits)(i=>scene(i%3))
    val activeTiles=if (paged) Vector(0,2*frameHeight/8-1) else (0 until 2*frameHeight/8).toVector
    val expectedScene=if (!paged) perTileScene else activeTiles.flatMap { tile=>
      val x=(tile%2)*8; val y=(tile/2)*8
      perTileScene.map(_.copy(scissor=Some((x,y,x+8,y+8))))
    }
    val expected=reference(0,screenW,frameHeight,expectedScene,height=frameHeight)
    simulate(new TileBinSharedSystemHarness(frameHeight,if (paged) 16384 else 0,continuing)) { h =>
      def zeroInputs(d: Data): Unit=d match {
        case r: Record=>r.elements.values.foreach(zeroInputs)
        case v: Vec[_]=>v.foreach(zeroInputs)
        case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
        case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
        case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
        case _=>()
      }
      zeroInputs(h.io)
      h.io.restartLimit.poke((if (continuing) 8 else 0).U)
      val d=new Driver(h.io.render,h.clock,h.reset,extentH=frameHeight,systemMemory=true,
        requestPort=h.io.memoryRequest,responsePort=h.io.memoryResponse,height=frameHeight,
        pagedCapacity=if (paged) 16384 else 0,directoryAddress=0x90000L,maxWait=1000000,
        tileBatch=e=>h.io.tileBatches(e).peek().litValue.toInt)
      for ((draw,i) <- scene.zipWithIndex; (word,n) <- primitive(draw).zipWithIndex)
        d.memory(recordBase+i*128L+n*4L)=word
      def addi(rd: Int,rs: Int,imm: Int): Long=((imm&4095).toLong<<20)|(rs<<15)|(rd<<7)|0x13
      def add(rd: Int,a: Int,b: Int): Long=(b<<20)|(a<<15)|(rd<<7)|0x33
      def slli(rd: Int,rs: Int,n: Int): Long=(n<<20)|(rs<<15)|(1<<12)|(rd<<7)|0x13
      def vle(base: Int,dst: Int): Long=(1<<25)|(base<<15)|(6<<12)|(dst<<7)|7
      def vse(base: Int,src: Int): Long=(1<<25)|(base<<15)|(6<<12)|(src<<7)|0x27
      val capacity=8
      def address(offset: Int): Seq[Long]=Seq(addi(7,0,offset/16),slli(7,7,4),add(6,5,7))
      val vset=(3L<<30)|(0x10L<<20)|(4L<<15)|(7<<12)|0x57
      val program=Seq(slli(5,8,2),add(5,1,5),vset)++address(12*capacity)++Seq(vle(6,2))++
        address(24*capacity)++Seq(vse(6,2))++address(8*capacity)++Seq(vle(6,3))++
        address(28*capacity)++Seq(vse(6,3),0x30500073L)
      for ((word,n) <- program.zipWithIndex) d.memory(0x1000+n*4L)=word
      d.memory(0x2000)=0xffffffffL
      var peakOwned=0
      var seenEngines=0
      var secondSlotLoads=0
      var armReloadFault=false
      d.onStep=()=>{
        if (armReloadFault && h.io.render.appended.valid.peek().litToBoolean &&
            h.io.render.appended.ready.peek().litToBoolean && h.io.appendedBatch.peek().litValue==1) {
          d.faultNextAttachmentRead=true; armReloadFault=false
        }
        peakOwned=math.max(peakOwned,h.io.owned.peek().litValue.bitCount)
        for (e <- 0 until 2 if h.io.engineOwned(e).peek().litValue!=0) seenEngines|=1<<e
        for (e <- 0 until 2 if h.io.render.attachmentLoadAccepted(e)(1).peek().litToBoolean) secondSlotLoads|=1<<e
      }
      def bind(b: TileBinRenderPassDescriptor): Unit = {
        b.shaderBase.poke(0x80000.U); b.shaderLimit.poke(0x88000.U)
        b.shaderEngineStride.poke(0x4000.U)
        if (paged) {
          val p=b.replay.bins.pool
          p.version.poke(2.U); p.initialLimit.poke(binBase.U); p.spillCount.poke(2.U)
          for (i <- 0 until 2) {
            p.spills(i).base.poke((binBase+0x1000L*(i+1)).U)
            p.spills(i).limit.poke((binBase+0x1000L*(i+1)+128).U)
          }
          p.limit.poke((binBase+0x2080).U)
        }
        if (frameHeight>8) {
          b.attachments.color.loadOp.poke(AttachmentLoadOp.load.U)
          b.attachments.depth.loadOp.poke(AttachmentLoadOp.load.U)
        }
      }
      val seed=d.memory.toVector
      val scenarios=if (continuing) Seq("healthy","directory-read-fault","reload-fault","healthy-reuse")
        else if (paged) Seq("healthy","directory-read-fault","healthy-reuse")
        else if (frameHeight==16) Seq("healthy","healthy-reuse")
        else if (translated) Seq("healthy","invalid-staging","shader-fault","bin-mapping-fault","healthy-reuse")
        else Seq("healthy","invalid-staging","shader-fault","healthy-reuse")
      for ((scenario,run) <- scenarios.zipWithIndex) {
        secondSlotLoads=0
        armReloadFault=scenario=="reload-fault"
        d.physicalOffset=if (translated) (run+1L)*0x400000L else 0L
        for ((address,value) <- seed) d.memory(d.physical(address))=value
        if (translated) {
          val root=0x300000L+run*4096L
          d.memory(root)=if (scenario=="bin-mapping-fault") 0L else ((d.physicalOffset>>12)<<10)|0xcfL
          val satp=(BigInt(1)<<31)|(BigInt(run+1)<<22)|(root>>12)
          h.io.render.instructionSatp.poke(satp.U); h.io.render.vectorSatp.poke(satp.U)
          h.io.render.instructionTlbFlush.valid.poke(true.B); h.io.render.vectorTlbFlush.valid.poke(true.B)
          h.io.invalidateInstructionCache.poke(true.B); d.step()
          h.io.render.instructionTlbFlush.valid.poke(false.B); h.io.render.vectorTlbFlush.valid.poke(false.B)
          h.io.invalidateInstructionCache.poke(false.B)
        }
        val pc=if (scenario=="shader-fault") 0x2000L else 0x1000L
        d.memory(d.physical(recordBase+32*64L+6*4))=pc
        d.memory(d.physical(recordBase+32*64L+7*4))=if (scenario=="invalid-staging") 0x3fc0 else 0
        d.memory(d.physical(recordBase+32*64L+8*4))=0x400
        for (depth <- Seq(false,true); y <- 0 until frameHeight; x <- 0 until screenW)
          d.memory(d.address(x,y,0,depth))=if (frameHeight==8) 0xdeadbeefL else if (depth) depthClear else colorClear
        d.holdTopRowWrites=frameHeight==16
        d.faultRecord=if (scenario=="directory-read-fault") Set(d.directoryAddress) else Set.empty[Long]
        d.begin(bind)
        if (scenario=="bin-mapping-fault" || scenario=="directory-read-fault") {
          d.expectedAppendStatus=PrimitiveBinStatus.memoryFault
          d.append(0,0)
        } else {
          for (draw <- 0 until visits; tile <- activeTiles) d.append(tile,draw%3)
          d.end()
        }
        if (frameHeight==16) {
          d.until(secondSlotLoads==3)
          assert(d.pending.exists(d.topRowWrite),"shared shaders did not overlap outstanding prior-tile exports")
          d.holdTopRowWrites=false
        }
        d.until(h.io.render.completion.valid.peek().litToBoolean)
        if (continuing && (scenario=="healthy" || scenario=="healthy-reuse" || scenario=="reload-fault")) {
          h.io.restarts.expect(1.U); h.io.batches.expect(2.U)
        }
        val s=d.finish()
        d.expectedAppendStatus=0
        if (scenario=="reload-fault") {
          assert(s.status==TileBinRenderStatus.attachmentFault && d.returnedFaults>0)
        } else if (scenario=="shader-fault") {
          assert(s.status==TileBinRenderStatus.shaderFault && s.stores==0)
        } else if (scenario=="invalid-staging") {
          assert(s.status==TileBinRenderStatus.invalidEncoding && s.stores==0)
        } else if (scenario=="bin-mapping-fault" || scenario=="directory-read-fault") {
          assert(s.status==PrimitiveBinStatus.memoryFault && s.stores==0 && s.draws==0)
        } else {
          assert(!paged || s.spills==(if (continuing) 4 else 2))
          assert(s.status==0 && s.draws==visits*activeTiles.size && d.image==expected,
            s"shared CU image differs in $scenario, status=${s.status}")
        }
        d.until(h.io.idle.peek().litToBoolean && d.live.isEmpty && d.pending.isEmpty)
      }
      assert(peakOwned==2 && seenEngines==3,s"shared occupancy peak=$peakOwned engines=$seenEngines")
      info(s"BIN_RENDER_SHARED Sv32=$translated height=$frameHeight continuing=$continuing cycles=${d.cycle} peakOwned=$peakOwned engineMask=$seenEngines")
    }
  }
  }

  it should "consume native texture bindings, rebind images and attribute texture read faults" in {
    val red=reference(0,screenW,screenH,draws.map(d=>d.copy(color=d.color&0xff0000ffL)))
    val white=reference(0,screenW,screenH)
    val translucent=reference(0,screenW,screenH,draws.map(d=>
      d.copy(color=(d.color&0xffffff00L)|(((d.color&255)*128+127)/255))))
    simulate(module()) { dut =>
      val d=new Driver(dut.io,dut.clock,dut.reset)
      for ((base,texel,expected) <- Seq((0x70000L,0xff0000ffL,red),(0x71000L,0xffffffffL,white),
        (0x72000L,0xffffff80L,translucent))) {
        d.memory(base)=texel; d.memory(base+4)=texel
        d.memory(recordBase+32*64L+4)=flags|(1L<<10)|(1L<<11)
        d.memory(recordBase+32*64L+9*4)=base
        d.memory(recordBase+32*64L+10*4)=2|(1L<<16)
        d.begin()
        for (draw <- draws.indices; tile <- 0 until 2) d.append(tile,draw)
        d.end(); val s=d.finish()
        assert(s.status==0 && d.textureReads>0 && d.image==expected)
      }
      d.faultRecord=Set(0x72000L)
      d.begin(); d.append(0,0); d.append(1,0); d.end(); val failed=d.finish()
      assert(failed.status==TileBinRenderStatus.textureFault && failed.stores==0 && d.returnedFaults>0)
    }
  }
}
