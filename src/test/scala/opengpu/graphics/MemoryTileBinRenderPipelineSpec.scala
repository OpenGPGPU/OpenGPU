package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.DecoupledIO
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class MemoryTileBinRenderPipelineSpec extends AnyFlatSpec {
  import TileBinRenderTestData._
  private val banked=sys.props.get("opengpu.bankedBins").contains("true")
  private val screenW=16; private val screenH=8
  private val cfg=GraphicsConfig(screenWidth=screenW,screenHeight=screenH,
    tileSize=16,tileAttachments=true,omInflight=2)
  private class MacroRenderer extends MemoryTileBinRenderPipeline(cfg,GpuConfig(lanes=4,warps=2,l2StoreEntries=4),
    pageBytes=128,writeCredits=2,tileSlots=4,storeCredits=2,useTileSramBlackBoxes=true,bankedAttachments=banked) {
    val macroPresent=IO(Output(Bool()))
    private val resources=Module(new TileSramSimulationResources)
    macroPresent:=resources.io.present
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

  case class Result(status: Int,frontend: Int,accepted: Int,batches: Int,restarts: Int,visits: Long,
    descriptorBytes: Int,appendBytes: Long,renderValid: Boolean,spills: Long)
  private class Driver(val io: MemoryTileBinRenderPipelineIO,val clock: Clock,val reset: Reset,val mode: Int=0,
    val systemMemory: Boolean=false,requestPort: DecoupledIO[ComputeMemoryRequest]=null,
    responsePort: DecoupledIO[ComputeMemoryResponse]=null) {
    import TileBinJobTestData.{descriptorBase,appendBase}
    case class Request(address: Long,id: Int,write: Boolean,data: BigInt,mask: BigInt,due: Int,fault: Boolean)
    private val memoryRequest=Option(requestPort).getOrElse(io.memoryRequest)
    private val memoryResponse=Option(responsePort).getOrElse(io.memoryResponse)
    var onStep: () => Unit = () => ()
    var physicalOffset=0L
    def physical(address: Long): Long=address+physicalOffset
    val memory=mutable.LongMap.empty[Long]
    val pending=mutable.ArrayBuffer.empty[Request]; val requests=mutable.ArrayBuffer.empty[Request]
    val live=mutable.Set.empty[Int]; val tiles=mutable.Set.empty[(Int,Int)]
    var response=Option.empty[Request]; var cycle=0; var holdRequests=false
    var faultAddress=Option.empty[Long]; var held=Option.empty[BigInt]
    private var appendEnd=appendBase
    private var binSegments=Vector((binBase,binBase+256))
    def tieInputs(d: Data): Unit=d match {
      case r: Record=>r.elements.values.foreach(tieInputs)
      case v: Vec[_]=>v.foreach(tieInputs)
      case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
      case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
      case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
      case _=>()
    }
    tieInputs(io); reset.poke(true.B); clock.step(); reset.poke(false.B)
    def address(x: Int,y: Int,s: Int,depth: Boolean): Long=
      (if (depth) depthBase else colorBase)+(y*screenW*(1<<mode)+x*(1<<mode)+s)*4L+physicalOffset
    def prepare(count: Int,mutate: Vector[Long]=>Vector[Long] = x=>x): Unit = {
      assert(live.isEmpty && pending.isEmpty && response.isEmpty)
      val header=mutate(TileBinJobTestData.descriptor(count,programmable=systemMemory,mode=mode,resolve=mode>0))
      appendEnd=header(6); requests.clear(); tiles.clear()
      binSegments=if ((header(21)&255)==2) Vector((header(16),header(19))) ++
        (0 until ((header(21)>>8)&15).toInt).map(i=>(header(49+3*i),header(50+3*i)))
        else Vector((header(16),header(17)))
      for ((w,i) <- header.zipWithIndex) memory(physical(descriptorBase+4L*i))=w
      for ((ref,i) <- TileBinJobTestData.references(count).zipWithIndex; (w,k) <- ref.words.zipWithIndex)
        memory(physical(appendBase+32L*i+4L*k))=w
      for ((draw,i) <- draws.zipWithIndex; (w,k) <- primitive(draw).zipWithIndex) memory(physical(recordBase+i*128L+k*4))=w
      for ((w,i) <- state.zipWithIndex) memory(physical(recordBase+32*64L+i*4))=w
      for (z <- Seq(false,true); y <- 0 until screenH; x <- 0 until screenW; s <- 0 until (1<<mode))
        memory(address(x,y,s,z))=0xdeadbeefL
    }
    def step(): Unit = {
      memoryRequest.ready.poke((!holdRequests && cycle%5!=2).B)
      held.foreach(x=>{memoryRequest.valid.expect(true.B); assert(memoryRequest.bits.peek().litValue==x)})
      held=if (memoryRequest.valid.peek().litToBoolean && !memoryRequest.ready.peek().litToBoolean) Some(memoryRequest.bits.peek().litValue) else None
      if (response.isEmpty) {
        val i=pending.lastIndexWhere(_.due<=cycle)
        if (i>=0) response=Some(pending.remove(i))
      }
      memoryResponse.valid.poke(response.nonEmpty.B)
      response.foreach { r=>
        memoryResponse.bits.transactionId.poke(r.id.U); memoryResponse.bits.fault.poke(r.fault.B)
        val value=(0 until 16).foldLeft(BigInt(0))((v,i)=>v|(BigInt(memory.getOrElse(r.address+4L*i,0xdeadbeefL))<<(32*i)))
        memoryResponse.bits.readData.poke(value.U)
      }
      if (response.nonEmpty && memoryResponse.ready.peek().litToBoolean) {
        val r=response.get; assert(live.remove(r.id))
        if (r.write && !r.fault) for (i <- 0 until 16 if r.mask.testBit(4*i)) {
          assert(((r.mask>>(4*i))&15)==15)
          memory(r.address+i*4L)=((r.data>>(32*i))&0xffffffffL).toLong
        }
        response=None
      }
      if (memoryRequest.valid.peek().litToBoolean && memoryRequest.ready.peek().litToBoolean) {
        val b=memoryRequest.bits; val id=b.transactionId.peek().litValue.toInt; val addr=b.address.peek().litValue.toLong
        val write=b.isWrite.peek().litToBoolean
        val size=b.sizeLog2.peek().litValue.toInt
        assert(size==6 || (systemMemory && size==2))
        assert(addr%(1L<<size)==0 && live.add(id))
        if (!systemMemory && id<8) assert(!write && addr==descriptorBase+id*64L)
        if (!systemMemory && id==8) assert(!write && addr>=appendBase && addr+64<=appendEnd)
        val virtual=addr-physicalOffset
        if (virtual>=binBase && virtual<recordBase)
          assert(binSegments.exists { case (start,end)=>virtual>=start && virtual+(1L<<size)<=end },"bin request entered an unallocated segment gap")
        if (write) assert(!(virtual>=descriptorBase && virtual<descriptorBase+512) &&
          !(virtual>=appendBase && virtual<appendEnd) && !(virtual>=recordBase && virtual<recordBase+4096))
        val r=Request(addr,id,write,b.writeData.peek().litValue,b.byteMask.peek().litValue,
          cycle+4+id%11,faultAddress.map(physical).contains(addr))
        pending+=r; requests+=r
      }
      for (e <- 0 until 2 if io.tileEvents(e).valid.peek().litToBoolean) {
        val b=io.tileEvents(e).bits
        assert(tiles.add((b.batch.peek().litValue.toInt,b.result.tile.peek().litValue.toInt)))
      }
      for (e <- 0 until 2) { io.kernelMemReq(e).valid.expect(false.B); if (!systemMemory) io.kernelWordMemReq(e).valid.expect(false.B) }
      onStep(); clock.step(); cycle+=1
    }
    def until(p: => Boolean): Unit = { val start=cycle; while (!p && cycle-start<400000) step(); assert(p,s"memory renderer stalled at $cycle live=$live") }
    def submit(): Unit = {
      io.submit.bits.commandId.poke(37.U); io.submit.bits.base.poke(descriptorBase.U)
      io.submit.bits.limit.poke((descriptorBase+512).U); io.submit.bits.envelopeValid.poke(true.B)
      io.submit.valid.poke(true.B); until(io.submit.ready.peek().litToBoolean); step(); io.submit.valid.poke(false.B)
      io.submit.bits.poke(0.U.asTypeOf(io.submit.bits))
    }
    def finish(): Result = {
      until(io.completion.valid.peek().litToBoolean)
      assert(live.isEmpty && pending.isEmpty && response.isEmpty)
      val b=io.completion.bits; b.commandId.expect(37.U)
      val r=Result(b.status.peek().litValue.toInt,b.frontendStatus.peek().litValue.toInt,b.acceptedAppends.peek().litValue.toInt,
        b.render.batches.peek().litValue.toInt,b.render.restarts.peek().litValue.toInt,b.render.totals.tileVisits.peek().litValue.toLong,
        b.descriptorReadBytes.peek().litValue.toInt,b.appendReadBytes.peek().litValue.toLong,b.renderValid.peek().litToBoolean,b.render.totals.spillTransitions.peek().litValue.toLong)
      assert(!r.renderValid || r.visits==tiles.size)
      val frozen=b.peek().litValue
      for (i <- 0 until 5) {
        io.cancel.poke((i==2).B); step(); io.completion.valid.expect(true.B)
        assert(b.peek().litValue==frozen); io.submit.ready.expect(false.B)
      }
      io.cancel.poke(false.B); io.completion.ready.poke(true.B); step(); io.completion.ready.poke(false.B)
      io.busy.expect(false.B); r
    }
    def image: Vector[Long]=(for (z <- Vector(false,true); y <- 0 until screenH; x <- 0 until screenW;
      s <- 0 until (1<<mode)) yield memory(address(x,y,s,z))).toVector
  }
  private def scene(count: Int): Vector[Draw]=TileBinJobTestData.references(count).map { r=>
    draws(((r.primitive-firstHandle)/2).toInt).copy(scissor=Some((r.tile.toInt*8,0,r.tile.toInt*8+8,8)))
  }
  private def renderer(macros: Boolean=false): MemoryTileBinRenderPipeline=
    if (macros) new MacroRenderer else new MemoryTileBinRenderPipeline(cfg,GpuConfig(lanes=4,warps=2,l2StoreEntries=4),
      pageBytes=128,writeCredits=2,tileSlots=4,storeCredits=2,bankedAttachments=banked)

  for (mode <- Seq(0,2)) {
    it should s"render the exact memory-submitted image through automatic restart with MSAA=$mode" in {
      val expected=reference(mode,screenW,screenH,scene(18))
      simulate(renderer(macros=mode==2)) { h=>
        h.io.capabilityWords(0).expect("h47424301".U); h.io.capabilityWords(2).expect("h47424a01".U)
        h.io.capabilityWords(3).expect("h00200200".U); h.io.capabilityWords(4).expect((0x90202 | (if(banked)1<<TileBinCapabilityFlags.BankedSamples else 0)).U)
        h.io.capabilityWords(5).expect("h00044000".U); h.io.capabilityWords(7).expect("h001effff".U)
        val d=new Driver(h.io,h.clock,h.reset,mode)
        d.prepare(18,x=>if (mode==0) x.updated(17,0x12080L).updated(19,0x10000L).updated(21,0x202L)
          .updated(49,0x11000L).updated(50,0x11080L).updated(52,0x12000L).updated(53,0x12080L) else x)
        d.submit(); val r=d.finish()
        assert(r.status==0 && r.frontend==0 && r.accepted==18 && r.batches==2 && r.restarts==1 && r.visits==4)
        assert(r.descriptorBytes==512 && r.appendBytes==9*64 && d.image==expected)
        assert(r.spills==(if (mode==0) 4 else 0))
        assert(d.requests.count(_.id<8)==8 && d.requests.count(_.id==8)==9)
        if (mode>0) for (y <- 0 until screenH; x <- 0 until screenW) {
          val samples=(0 until 4).map(s=>d.memory(d.address(x,y,s,false)))
          val resolved=(0 until 4).foldLeft(0L)((v,b)=>v|(((samples.map(w=>(w>>(8*b))&255).sum+2)/4)<<(8*b)))
          assert(d.memory(resolveBase+(y*screenW+x)*4L)==resolved)
        }
        info(s"MEMORY_BIN_IMAGE mode=$mode refs=${r.accepted} batches=${r.batches} restarts=${r.restarts} visits=${r.visits} spills=${r.spills} descriptorBytes=${r.descriptorBytes} appendBytes=${r.appendBytes} exactWords=${expected.size} cycles=${d.cycle}")
      }
    }
  }
  it should "drain a stream read fault and recover the complete image on the same engine" in {
    val expected=reference(0,screenW,screenH,scene(18))
    simulate(renderer()) { h=>
      val d=new Driver(h.io,h.clock,h.reset); d.prepare(3); d.faultAddress=Some(TileBinJobTestData.appendBase+64)
      d.submit(); val failed=d.finish()
      assert(failed.status==PrimitiveBinStatus.memoryFault && failed.frontend==PrimitiveBinStatus.memoryFault && failed.accepted==2 && failed.renderValid)
      d.prepare(18); d.faultAddress=None; d.submit(); val healthy=d.finish()
      assert(healthy.status==0 && healthy.restarts==1 && d.image==expected)
    }
  }
  it should "retain queued descriptor reads through cancellation and give late faults priority" in {
    simulate(renderer()) { h=>
      val d=new Driver(h.io,h.clock,h.reset); d.prepare(0); d.holdRequests=true; d.faultAddress=Some(TileBinJobTestData.descriptorBase)
      d.submit(); d.until(h.io.memoryRequest.valid.peek().litToBoolean)
      h.io.cancel.poke(true.B); d.step(); h.io.cancel.poke(false.B)
      for (_ <- 0 until 6) d.step()
      d.holdRequests=false; val r=d.finish()
      assert(r.status==PrimitiveBinStatus.memoryFault && !r.renderValid && r.accepted==0 && d.requests.forall(!_.write))
      d.prepare(0); d.faultAddress=None; d.submit(); val empty=d.finish()
      assert(empty.status==0 && empty.accepted==0 && empty.appendBytes==0 && empty.visits==2)
      assert(d.image.take(screenW*screenH).forall(_==colorClear) && d.image.drop(screenW*screenH).forall(_==depthClear))
      d.prepare(0,x=>x.updated(13,4L).updated(14,1L)); d.submit(); val partial16=d.finish()
      assert(partial16.status==0 && partial16.visits==1 && partial16.appendBytes==0)
      assert(d.image.take(screenW*screenH).forall(_==colorClear) && d.image.drop(screenW*screenH).forall(_==depthClear))
    }
  }
  it should "reject a malformed stream record without treating its data as a successful draw" in {
    simulate(renderer()) { h=>
      val d=new Driver(h.io,h.clock,h.reset); d.prepare(3); d.memory(TileBinJobTestData.appendBase+64+20)=1
      d.submit(); val r=d.finish()
      assert(r.status==TileBinRenderStatus.invalidEncoding && r.frontend==TileBinRenderStatus.invalidEncoding && r.accepted==2)
    }
  }

  it should "submit bounded memory jobs through shared CUs and Sv32 with permission faults and healthy reuse" in {
    val expected=reference(0,screenW,screenH,scene(18))
    simulate(new MemoryTileBinSharedSystemHarness) { h=>
      val d=new Driver(h.io.render,h.clock,h.reset,systemMemory=true,
        requestPort=h.io.memoryRequest,responsePort=h.io.memoryResponse)
      h.io.invalidateInstructionCache.poke(false.B)
      def addi(rd: Int,rs: Int,imm: Int): Long=((imm&4095).toLong<<20)|(rs<<15)|(rd<<7)|0x13
      def add(rd: Int,a: Int,b: Int): Long=(b<<20)|(a<<15)|(rd<<7)|0x33
      def slli(rd: Int,rs: Int,n: Int): Long=(n<<20)|(rs<<15)|(1<<12)|(rd<<7)|0x13
      def vle(base: Int,dst: Int): Long=(1<<25)|(base<<15)|(6<<12)|(dst<<7)|7
      def vse(base: Int,src: Int): Long=(1<<25)|(base<<15)|(6<<12)|(src<<7)|0x27
      def offset(n: Int): Seq[Long]=Seq(addi(7,0,n/16),slli(7,7,4),add(6,5,7))
      val vset=(3L<<30)|(0x10L<<20)|(4L<<15)|(7<<12)|0x57
      val program=Seq(slli(5,8,2),add(5,1,5),vset)++offset(12*8)++Seq(vle(6,2))++
        offset(24*8)++Seq(vse(6,2))++offset(8*8)++Seq(vle(6,3))++offset(28*8)++Seq(vse(6,3),0x30500073L)
      var peakOwned=0; var engineMask=0
      // Sample CU ownership on every memory-model tick, including all fault drains.
      var scenarios=0
      for ((scenario,run) <- Seq("healthy","descriptor-page-fault","append-page-fault","shader-fault","healthy-reuse").zipWithIndex) {
        d.physicalOffset=(run+1L)*0x400000
        d.prepare(18)
        for ((word,n) <- program.zipWithIndex) d.memory(d.physical(0x1000+n*4L))=word
        if (scenario=="shader-fault") d.memory(d.physical(0x1000))=0xffffffffL
        d.memory(d.physical(recordBase+32*64L+6*4))=0x1000
        d.memory(d.physical(recordBase+32*64L+8*4))=0x400
        val root=0x300000L+run*4096L; val leaf=0x310000L+run*4096L
        d.memory(root)=((leaf>>12)<<10)|1L
        for (page <- 0 until 192) {
          val flags=if (page==0x90 || page==0xa0 || (page>=0x20 && page<0x21)) 0x43L else 0xcfL
          d.memory(leaf+page*4L)=((d.physical(page*4096L)>>12)<<10)|flags
        }
        if (scenario=="descriptor-page-fault") d.memory(leaf+0x90*4)=0
        if (scenario=="append-page-fault") d.memory(leaf+0xa0*4)=0
        val satp=(BigInt(1)<<31)|(BigInt(run+1)<<22)|(root>>12)
        h.io.render.instructionSatp.poke(satp.U); h.io.render.vectorSatp.poke(satp.U)
        h.io.render.instructionTlbFlush.valid.poke(true.B); h.io.render.vectorTlbFlush.valid.poke(true.B)
        h.io.invalidateInstructionCache.poke(true.B); d.step()
        h.io.render.instructionTlbFlush.valid.poke(false.B); h.io.render.vectorTlbFlush.valid.poke(false.B)
        h.io.invalidateInstructionCache.poke(false.B)
        d.onStep=()=>{
          peakOwned=peakOwned.max(h.io.owned.peek().litValue.bitCount)
          for (e <- 0 until 2 if h.io.engineOwned(e).peek().litValue!=0) engineMask|=1<<e
        }
        d.submit(); val r=d.finish(); d.until(h.io.idle.peek().litToBoolean)
        scenario match {
          case "descriptor-page-fault"=>assert(r.status==PrimitiveBinStatus.memoryFault && !r.renderValid && r.accepted==0)
          case "append-page-fault"=>assert(r.status==PrimitiveBinStatus.memoryFault && r.renderValid && r.accepted==0)
          case "shader-fault"=>assert(r.status==TileBinRenderStatus.shaderFault && r.frontend==0)
          case _=>assert(r.status==0 && r.accepted==18 && r.restarts==1 && d.image==expected,"memory-submitted shader image differs")
        }
        scenarios+=1
      }
      assert(peakOwned==2 && engineMask==3)
      info(s"MEMORY_BIN_SHARED Sv32=true scenarios=$scenarios peakOwned=$peakOwned engineMask=$engineMask cycles=${d.cycle}")
    }
  }
}
