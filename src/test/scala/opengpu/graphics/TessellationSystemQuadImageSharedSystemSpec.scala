package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationSystemQuadImageSharedHarness(transformFeedback:Boolean=false) extends Module {
  val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
  val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
  val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=1024,owners=3,ports=6)
  val hostSlots=TessellationSystemQuadImagePipeline.memoryTransactions(2,2,8)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,hostSlots)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemQuadBinTask(cfg,p,4)))
    val taskCompletion=Decoupled(new TessellationSystemQuadBinTaskCompletion)
    val end=Flipped(Decoupled(Bool()))
    val opened=Decoupled(UInt(2.W));val seal=Flipped(Decoupled(Bool()))
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val primitiveDone=Vec(2,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(2,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(2,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TessellationSystemQuadImageCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val context=Input(UInt(32.W))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(cfg))else None
    val idle=Output(Bool());val reserved=Output(UInt(32.W));val poolDrained=Output(Bool())
  })
  private val stage=Module(new TessellationSystemQuadImagePipeline(cfg,p,f,SharedCuShaderConfig(2,taskIdWidth=4),
    g=g,engines=2,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,payload=Some(payload),transformFeedback=transformFeedback))
  val recordBytes=stage.recordBytes
  private val system=Module(new GpuSystem(cfg,numComputeUnits=2,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,tessellationAvailable=true,graphicsHostTransactions=hostSlots,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1,
    shaderPayload=Some(payload),producerPayloadPins=TessellationSystemQuadImagePipeline.payloadPins(2)))
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io);tie(stage.io)
  stage.io.begin<>io.begin;stage.io.task<>io.task;io.taskCompletion<>stage.io.taskCompletion
  stage.io.end<>io.end;io.opened<>stage.io.opened;stage.io.seal<>io.seal
  stage.io.inspect<>io.inspect;io.reply<>stage.io.reply
  io.primitiveDone<>stage.io.primitiveDone;io.out<>stage.io.out
  io.tileDone<>stage.io.tileDone;io.completion<>stage.io.completion;stage.io.cancel:=io.cancel
  if(transformFeedback)io.feedback.get<>stage.io.feedback.get
  system.io.graphicsKernels.get<>stage.io.shared
  private val hostQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,hostSlots),2,pipe=true))
  hostQueue.io.enq<>stage.io.memoryRequest;system.io.graphicsHostRequest<>hostQueue.io.deq;stage.io.memoryResponse<>system.io.graphicsHostResponse
  private val lowerQueue=Module(new Queue(new ComputeMemoryRequest(cfg,64,slots),2,pipe=true))
  lowerQueue.io.enq<>system.io.memoryRequest;io.memoryRequest<>lowerQueue.io.deq;system.io.memoryResponse<>io.memoryResponse
  system.io.graphicsDrained:= !stage.io.busy && hostQueue.io.count===0.U
  system.io.graphicsShaderL1Invalidate.ready:=true.B
  val management=system.io.payloadManagement.get
  for(i<-0 until 3){val port=stage.io.payload.get(i)
    management.control(i)<>port.control;port.controlResponse<>management.controlResponse(i)
    management.request(i)<>port.request;port.response<>management.response(i)
    port.context:=io.context;port.cancel:=false.B}
  system.io.graphicsPayload.get:=stage.io.poolPayload.get;system.io.payloadProducerPins.get:=stage.io.payloadPins.get
  io.reserved:=management.reservedBytes;io.poolDrained:=management.drained;io.idle:=system.io.vmContextIdle && !stage.io.busy
}

class TessellationSystemQuadImageSharedSystemSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero,Memory}
  import TypedColorReference.fp
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,vs1:Int=0,vs2:Int=0):BigInt=
    (BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|0x2b
  private def geometry(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=
    (BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  private def addi(rd:Int,rs:Int,n:Int):BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  private def vm(base:Int,v:Int,write:Boolean):BigInt=(BigInt(1)<<25)|(BigInt(base)<<15)|(BigInt(6)<<12)|(BigInt(v)<<7)|(if(write)0x27 else 7)
  private val controlProgram=Vector(BigInt("0104f057",16),BigInt("3f800537",16),vector(23,4,3,10),
    vector(23,3,2,0),patch(20,vs1=3)) ++ Vector(7,15,23,31,39,47,3).flatMap { address => Vector(
      BigInt((address << 20) | (5 << 7) | 0x13), vector(23,4,4,5), patch(22,vs1=2,vs2=4)) } ++
    Vector(BigInt("0000000b",16),BigInt("30500073",16))
  private val evaluationProgram=Vector(BigInt("00000093",16),BigInt("00000113",16),geometry(32,3,1,2),
    BigInt("00000213",16),geometry(33,0,4,3),BigInt("00100113",16),geometry(32,3,1,2),
    BigInt("00100213",16),geometry(33,0,4,3),BigInt("3f8002b7",16),BigInt("00300213",16),
    geometry(33,0,4,5),BigInt("00300113",16),geometry(32,3,1,2),BigInt("00400213",16),
    geometry(33,0,4,3),geometry(35),geometry(36),BigInt("30500073",16))
  private def add(rd:Int,a:Int,b:Int):BigInt=(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(rd)<<7)|0x33
  private val fragmentProgram={
    val stride=32;val inputs=10
    val code=mutable.ArrayBuffer[BigInt]((BigInt(2)<<20)|(BigInt(8)<<15)|(BigInt(1)<<12)|(BigInt(7)<<7)|0x13,
      add(7,1,7),(BigInt(3)<<30)|(BigInt(0x10)<<20)|(BigInt(4)<<15)|(BigInt(7)<<12)|0x57)
    code+=addi(5,7,6*stride);code+=vm(5,1,false);code+=addi(6,7,inputs*stride);code+=vm(6,1,true)
    code+=BigInt("30500073",16);code.toVector
  }
  for(testMode<-Seq("baseline", "capture", "discard", "fault", "reset", "reset_fault"))
  it should s"shade controlled quad patches into typed images with feedback mode=$testMode" in {
    val enabled=testMode!="baseline";val fault=testMode=="fault" || testMode=="reset_fault"
    val reset=testMode.startsWith("reset");val discard=testMode=="discard"
    val frameStatus=if(fault)5 else if(reset)4 else 0
    simulate(new TessellationSystemQuadImageSharedHarness(transformFeedback=enabled)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,d.io.memoryRequest.ready,
        d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      val feedbackMemory=d.io.feedback.map(f=>new Memory(f.memoryRequest.bits,f.memoryResponse.bits,f.memoryRequest.valid,
        f.memoryRequest.ready,f.memoryResponse.valid,f.memoryResponse.ready))
      feedbackMemory.foreach{m=>m.delay=24;if(fault)m.faultAt=Set(BigInt(0x700000))}
      var recorded=0;var resetIssued=false;var pendingEnd=false
      controlProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x1000+i*4,w)}
      evaluationProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x3000+i*4,w)}
      fragmentProgram.zipWithIndex.foreach{case(w,i)=>memory.putWord(0x2000+i*4,w)}
      val fields=TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig(2,4)))
      val clear=Vector(0.1,0.2,0.3,0.4).map(fp)
      def identity(i:GraphicsWorkIdentity):Unit={i.context.poke(7.U);i.pass.poke(8.U);i.generation.poke(9.U)}
      def tick():Unit={
        memory.drive();feedbackMemory.foreach(_.drive())
        for(e<-0 until 2){d.io.out(e).ready.poke(true.B);d.io.primitiveDone(e).ready.poke(true.B);d.io.tileDone(e).ready.poke(true.B)}
        d.io.feedback.foreach{f=>
          if(pendingEnd)f.command.ready.expect(false.B)
          if(f.recorded.valid.peek().litToBoolean)recorded+=1
        }
        memory.capture();feedbackMemory.foreach{m=>
          val before=m.seen.size;m.capture()
          if(reset && !resetIssued && m.seen.size>before){resetIssued=true;d.io.feedback.get.cancel.poke(true.B)}
        }
        d.clock.step();d.io.feedback.foreach(_.cancel.poke(false.B))
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<200000){tick();n+=1}
        assert(condition,s"$label stalled at ${memory.cycle} pending=${memory.pending.size}")}
      def feedbackCommand(action:Int):Unit={
        val f=d.io.feedback.get;zero(f.command.bits);f.command.bits.action.poke(action.U)
        val q=f.command.bits.state.descriptor;q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(2.U);q.interleaved.poke(true.B);q.buffers.poke(1.U)
          q.components(0).poke(1.U);q.selectors(0).poke(0.U);q.views(0).base.poke(0x700000.U);q.views(0).limit.poke(0x700100.U)}
        f.command.valid.poke(true.B)
      }
      if(enabled){
        val f=d.io.feedback.get;val scope=f.scope;scope.context.poke(7.U);scope.root.poke(0.U);scope.program.poke(9.U)
        scope.mode.poke(1.U);scope.cookie.poke(1.U);scope.rasterDiscard.poke(discard.B)
        feedbackCommand(TransformFeedbackAction.begin);until("feedback begin")(f.command.ready.peek().litToBoolean)
        tick();f.command.valid.poke(false.B);until("feedback begin reply")(f.commandReply.valid.peek().litToBoolean)
        f.commandReply.bits.status.expect(0.U);f.commandReply.ready.poke(true.B);tick();f.commandReply.ready.poke(false.B)
      }
      val stateBase=0x8000;for(w<-0 until 128)memory.putWord(stateBase+w*4,0)
      for((v,i)<-Vector[BigInt](TypedFragmentStateLayout.magic,512,1,4,7,8,9,0).zipWithIndex)memory.putWord(stateBase+i*4,v)
      val scratch0=0x100000;val scratch1=0x110000
      val prefix=FragmentShaderConfig(2,4).prefixBytes(8,1,1,FragmentInputAbi.center)
      val values=Map[String,BigInt]("version"->1,"engineCount"->2,"fragment.version"->FragmentInputAbi.center,"fragment.identity.context"->7,"fragment.identity.pass"->8,"fragment.identity.generation"->9,
        "fragment.shaderPc"->0x2000,"fragment.varyingSlots"->1,"fragment.colorSlots"->1,"fragment.colorMask"->1,"fragment.sampleMode"->0,
        "fragment.sampler.texWidth"->1,"fragment.sampler.texHeight"->1,
        "fragment.scratch.base"->scratch0,"fragment.scratch.limit"->(scratch0+prefix),"fragment.scratch.allocationBase"->scratch0,"fragment.scratch.allocationLimit"->(scratch0+prefix),
        "scratch[0].base"->scratch1,"scratch[0].limit"->(scratch1+prefix),"scratch[0].allocationBase"->scratch1,"scratch[0].allocationLimit"->(scratch1+prefix),
        "tests.version"->1,"tests.depthEnable"->0,"tests.front.function"->6,"tests.back.function"->6,"blend.version"->1)
      for(field<-fields){val raw=values.getOrElse(field.name,BigInt(0))&((BigInt(1)<<(field.words*32))-1)
        for(w<-0 until field.words)memory.putWord(stateBase+(field.word+w)*4,(raw>>(w*32))&0xffffffffL)}

      val dsc=d.io.begin.bits;zero(dsc);dsc.version.poke(1.U)
      val a=dsc.bins.producer;a.version.poke(1.U);a.arena.version.poke(1.U);identity(a.arena.identity)
      a.arena.base.poke(0x200000.U);a.arena.limit.poke((0x200000+32*d.recordBytes).U)
      a.screenWidth.poke(4.U);a.screenHeight.poke(4.U);a.tileLog2.poke(3.U)
      val b=dsc.bins.bins;b.version.poke(3.U);b.tileCount.poke(1.U);b.directoryBase.poke(0x500000.U);b.directoryLimit.poke(0x500080.U)
      b.pool.version.poke(1.U);identity(b.pool.identity);b.pool.base.poke(0x400000.U);b.pool.limit.poke(0x410000.U);b.pool.pageSizeLog2.poke(7.U)
      dsc.states.version.poke(1.U);identity(dsc.states.identity);dsc.states.base.poke(0x8000.U);dsc.states.limit.poke(0x8400.U);dsc.scratchStride.poke(4096.U)
      for(e<-0 until 2){dsc.scratch(e).base.poke((0x100000+e*0x10000).U);dsc.scratch(e).limit.poke((0x102000+e*0x10000).U)}
      val im=dsc.image;im.version.poke(1.U);im.pixels.version.poke(1.U);val c=im.pixels.colors;c.version.poke(1.U);identity(c.identity)
      c.tileLog2.poke(3.U);c.tileWidth.poke(4.U);c.tileHeight.poke(4.U);c.reservedBytes.poke(4096.U)
      val color=c.colors(0);color.enabled.poke(true.B);color.format.poke(TypedColorFormat.id("RGBA8").U);color.offset.poke(4096.U)
      color.channelMask.poke(15.U);color.loadOp.poke(AttachmentLoadOp.clear.U);color.clear.zip(clear).foreach{case(x,y)=>x.poke(y.U)}
      val view=im.views(0);view.base.poke(0x600000.U);view.limit.poke(0x600040.U)
      view.allocationBase.poke(0x600000.U);view.allocationLimit.poke(0x600040.U)
      view.imageWidth.poke(4.U);view.imageHeight.poke(4.U);view.rowPitch.poke(16.U)
      view.readable.poke(true.B);view.writable.poke(true.B);view.store.poke(true.B)
      for(a<-0x600000 until 0x600040)memory.bytes(a)=0
      d.io.context.poke(101.U);d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      if(enabled){feedbackCommand(TransformFeedbackAction.end);pendingEnd=true}

      def drivePatch(taskId:Int, expectedStatus:Int=frameStatus,
        expectedFeedbackStatus:Int=if(fault)3 else if(reset)4 else 0):Unit={
        val t=d.io.task.bits;val view=t.viewport;val src=view.system.patch;val ctl=src.control;zero(t)
        identity(view.identity)
        view.viewport.version.poke(1.U);view.viewport.extentX.poke(4.U);view.viewport.extentY.poke(4.U);view.viewport.farDepth.poke("h3f800000".U)
        src.commandId.poke(3.U);src.evaluationKernel.kernelPc.poke(0x3000.U);src.outputComponents.poke(5.U)
        src.patchInputCount.poke(1.U);src.patchInputs(0).perPatch.poke(true.B)
        for(i<-Seq(0,1,3)){view.componentSources(i).valid.poke(true.B);view.componentSources(i).bits.poke(i.U)}
        view.varyingSlots.poke(1.U);view.componentSources(4).valid.poke(true.B);view.componentSources(4).bits.poke(4.U)
        ctl.patch.context.taskId.poke(taskId.U);ctl.patch.context.layout.inputVertices.poke(1.U)
        ctl.patch.context.layout.outputVertices.poke(4.U);ctl.patch.context.layout.patchComponents.poke(1.U)
        ctl.kernel.kernelPc.poke(0x1000.U);for(i<-0 until 3){ctl.kernel.gridSize(i).poke(1.U);ctl.kernel.localSize(i).poke((if(i==0)4 else 1).U)}
        ctl.bases(0).poke(0.U);ctl.bases(1).poke(4.U)
        t.raster.version.poke(1.U);t.raster.pointSize.poke("h3f800000".U);t.raster.screenW.poke(4.U);t.raster.screenH.poke(4.U)
        t.state.poke(0.U);t.token.poke((200+taskId).U)
        d.io.feedback.foreach{f=>f.scope.context.poke(7.U);f.scope.program.poke(9.U)}
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
        d.io.feedback.foreach{f=>f.scope.context.poke(99.U);f.scope.program.poke(99.U)}
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean);d.io.opened.bits.expect(0.U)
        tick();d.io.opened.ready.poke(false.B);d.io.seal.valid.poke(true.B)
        until("seal")(d.io.seal.ready.peek().litToBoolean);tick();d.io.seal.valid.poke(false.B)
        until("task completion")(d.io.taskCompletion.valid.peek().litToBoolean)
        d.io.taskCompletion.bits.status.expect(expectedStatus.U)
        if(enabled)d.io.taskCompletion.bits.source.feedbackStatus.expect(expectedFeedbackStatus.U)
        if(expectedStatus==0 && !discard)d.io.taskCompletion.bits.admitted.expect(2.U)
        else d.io.taskCompletion.bits.admitted.expect(0.U)
        d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      }
      drivePatch(19);if(frameStatus==0)drivePatch(20)
      if(frameStatus==0){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until("end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      until("completion")(d.io.completion.valid.peek().litToBoolean)
      val done=d.io.completion.bits
      assert(done.status.peek().litValue==frameStatus,
        s"frame status=${done.status.peek().litValue} faultStage=${done.faultStage.peek().litValue} "+
          s"faultTile=${done.faultTileValid.peek().litValue}/${done.faultTile.peek().litValue} "+
          s"source.status=${done.source.status.peek().litValue} tasks=${done.source.tasks.peek().litValue} "+
          s"tiles=${done.tiles.peek().litValue} primitives=${done.primitives.peek().litValue} "+
          s"load=${done.loadBytes.peek().litValue} store=${done.storedBytes.peek().litValue} "+
          s"cycles=${memory.cycle} pending=${memory.pending.size} seen=${memory.seen.takeRight(8).map(r=>(r.address.toString(16),r.write))}")
      done.source.tasks.expect((if(frameStatus==0)2 else 1).U)
      if(frameStatus==0 && !discard){done.primitives.expect(4.U);done.tiles.expect(1.U)}
      else assert(done.primitives.peek().litValue==0)
      val held=done.peek().litValue;val primitives=done.primitives.peek().litValue
      for(_<-0 until 4){tick();assert(done.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      if(enabled){
        val f=d.io.feedback.get;pendingEnd=false
        until("feedback end")(f.command.ready.peek().litToBoolean);tick();f.command.valid.poke(false.B)
        until("feedback end reply")(f.commandReply.valid.peek().litToBoolean)
        f.commandReply.bits.status.expect((if(reset)5 else if(fault)3 else 0).U)
        f.commandReply.bits.state.primitives.expect((if(frameStatus==0)4 else 0).U)
        f.commandReply.bits.state.vertices.expect((if(frameStatus==0)12 else 0).U)
        f.commandReply.bits.state.offsets(0).expect((if(frameStatus==0)48 else 0).U)
        f.commandReply.ready.poke(true.B);tick();f.commandReply.ready.poke(false.B)
        assert(recorded==(if(frameStatus==0)4 else 0));if(reset)assert(resetIssued)
        if(frameStatus==0){val fm=feedbackMemory.get
          val one=BigInt("3f800000",16)
          assert((0 until 12).map(i=>fm.word(0x700000+i*4))==Seq[BigInt](0,one,one,0,one,0,0,one,one,0,one,0))}
      }
      until("idle")(d.io.idle.peek().litToBoolean);d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B)
      assert(memory.pending.isEmpty && memory.reply.isEmpty)
      val reds=(0 until 16).map(i=>memory.bytes.getOrElse(0x600000+i*4,0))
      if(frameStatus==0 && !discard){
        val expected=(0 until 16).map(i=>if(i%4>=2 && i/4>=2)255 else 26)
        assert(reds==expected,s"unexpected 4x4 red coverage: $reds")
      }
      if(discard)assert(reds.forall(_==26),s"discarded tessellation changed the clear image: $reds")
      if(fault || reset){
        val f=d.io.feedback.get;val fm=feedbackMemory.get
        fm.faultAt=Set.empty;f.scope.context.poke(7.U);f.scope.program.poke(9.U)
        feedbackCommand(TransformFeedbackAction.begin);until("recovery feedback begin")(f.command.ready.peek().litToBoolean)
        tick();f.command.valid.poke(false.B);until("recovery feedback begin reply")(f.commandReply.valid.peek().litToBoolean)
        f.commandReply.bits.status.expect(0.U);f.commandReply.ready.poke(true.B);tick();f.commandReply.ready.poke(false.B)
        d.io.begin.valid.poke(true.B);until("recovery begin")(d.io.begin.ready.peek().litToBoolean)
        tick();d.io.begin.valid.poke(false.B);feedbackCommand(TransformFeedbackAction.end);pendingEnd=true
        val previousRecorded=recorded;drivePatch(21,0,0)
        d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until("recovery end")(d.io.end.ready.peek().litToBoolean)
        tick();d.io.end.valid.poke(false.B);until("recovery completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(0.U);d.io.completion.bits.primitives.expect(2.U)
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
        pendingEnd=false;until("recovery feedback end")(f.command.ready.peek().litToBoolean);tick();f.command.valid.poke(false.B)
        until("recovery feedback end reply")(f.commandReply.valid.peek().litToBoolean)
        f.commandReply.bits.status.expect(0.U);f.commandReply.bits.state.primitives.expect(2.U)
        f.commandReply.bits.state.vertices.expect(6.U);f.commandReply.bits.state.offsets(0).expect(24.U)
        f.commandReply.ready.poke(true.B);tick();f.commandReply.ready.poke(false.B)
        assert(recorded-previousRecorded==2)
        val one=BigInt("3f800000",16)
        assert((0 until 6).map(i=>fm.word(0x700000+i*4))==Seq[BigInt](0,one,one,0,one,0))
        until("recovery idle")(d.io.idle.peek().litToBoolean);d.io.reserved.expect(0.U);d.io.poolDrained.expect(true.B)
        assert(memory.pending.isEmpty && memory.reply.isEmpty)
        val recoveredReds=(0 until 16).map(i=>memory.bytes.getOrElse(0x600000+i*4,0))
        val expected=(0 until 16).map(i=>if(i%4>=2 && i/4>=2)255 else 26)
        assert(recoveredReds==expected,s"unexpected recovered red coverage: $recoveredReds")
      }
      info(s"CONTROLLED_QUAD_WINDOW_BIN_TYPED_IMAGE_SHARED patches=2 primitives=$primitives tiles=1 cycles=${memory.cycle}")
    }
  }
}
