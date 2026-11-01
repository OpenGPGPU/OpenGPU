package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.graphics._
import opengpu.testutil.{GraphicsTestResolution,GpuSim}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationFeedbackBinHarness(config:GpuConfig) extends Module {
  val p=VertexShaderStageConfig();val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,tessellationAvailable=true,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  val client=Module(new TessellationSystemIsolineBinPass(config,p,4,1,g,engines=2,pageBytes=128,writeCredits=2,tileSlots=2,transformFeedback=true))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io);val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new TessellationSystemIsolineBinTask(config,p,4)))
    val taskCompletion=Decoupled(new TessellationSystemIsolineBinTaskCompletion);val end=Flipped(Decoupled(Bool()))
    val out=Vec(2,Decoupled(new WindowPrimitiveReplay(p)));val tileDone=Decoupled(new WindowPrimitiveTileCompletion)
    val completion=Decoupled(new TessellationSystemIsolineBinCompletion(p));val opened=Decoupled(UInt(2.W));val seal=Flipped(Decoupled(Bool()))
    val trap=Decoupled(new TaggedGraphicsTrap(config,4,1));val feedback=new TransformFeedbackPipelineIO(config)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,7));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,7)))
    val cancel=Input(Bool())
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(client.io);io.system<>gpu.io;client.io.shared<>gpu.io.graphicsKernels.get
  client.io.begin<>io.begin;client.io.task<>io.task;io.taskCompletion<>client.io.taskCompletion;client.io.end<>io.end
  io.out<>client.io.out;io.tileDone<>client.io.tileDone;io.completion<>client.io.completion
  io.opened<>client.io.opened;client.io.seal<>io.seal;io.trap<>client.io.trap
  io.memoryRequest<>client.io.memoryRequest;client.io.memoryResponse<>io.memoryResponse;client.io.cancel:=io.cancel
  io.feedback<>client.io.feedback.get
}

class TessellationSystemIsolineFeedbackBinSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  private def vector(fn:Int,form:Int,rd:Int,vs1:Int):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs1)<<15)|(BigInt(form)<<12)|(BigInt(rd)<<7)|0x57
  private def patch(fn:Int,vs1:Int=0,vs2:Int=0):BigInt=(BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(vs2)<<20)|(BigInt(vs1)<<15)|0x2b
  private def geo(fn:Int,rd:Int=0,rs1:Int=0,rs2:Int=0):BigInt=(BigInt(fn)<<25)|(BigInt(rs2)<<20)|(BigInt(rs1)<<15)|(BigInt(rd)<<7)|0x0b
  for(testMode<-Seq("capture","discard","fault","cancel","cancel_fault","reset","reset_fault")) it should s"retain feedback scope across tessellation binning mode=$testMode" in {
    val injectFault=testMode=="fault" || testMode.endsWith("_fault");val discard=testMode=="discard";val reset=testMode.startsWith("reset")
    val interrupt=reset || testMode.startsWith("cancel");val passStatus=if(injectFault)5 else if(interrupt)4 else 0
    val cfg=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new TessellationFeedbackBinHarness(cfg)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;val fb=d.io.feedback;sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B);d.io.trap.ready.poke(true.B)
      val control=Vector(BigInt("0104f057",16),BigInt("3f800537",16),vector(23,4,3,10),vector(23,3,2,0),vector(23,3,4,7),
        patch(20,vs1=3),patch(22,vs1=2,vs2=4),vector(23,3,4,15),patch(22,vs1=2,vs2=4),vector(23,3,4,3),patch(22,vs1=2,vs2=4),BigInt("0000000b",16),BigInt("30500073",16))
      val eval=Vector(BigInt("00000093",16),BigInt("00000113",16),geo(32,3,1,2),BigInt("00000213",16),geo(33,0,4,3),
        BigInt("00300113",16),geo(32,3,1,2),BigInt("00100213",16),geo(33,0,4,3),BigInt("3f0001b7",16),
        BigInt("00300213",16),geo(33,0,4,3),geo(35),geo(36),BigInt("30500073",16))
      def code(p:Vector[BigInt]):BigInt=p.zipWithIndex.map{case(w,i)=>w<<(32*i)}.sum
      case class Req(tag:BigInt,address:BigInt,write:Boolean,data:BigInt,mask:BigInt,due:Int)
      val gpu=mutable.Queue.empty[(BigInt,BigInt,Int)];val bin=mutable.Queue.empty[Req];val feedback=mutable.Queue.empty[Req]
      val binBytes=mutable.Map.empty[BigInt,Int];val feedbackBytes=mutable.Map.empty[BigInt,Int]
      var cycle=0;var records=0;var replay=0;var outputReady=false;var pendingEnd=false;var delayed=0;var recordWrites=0;var resetIssued=false
      var faultEnabled=injectFault;var recovering=false
      def line(m:mutable.Map[BigInt,Int],a:BigInt):BigInt=(0 until 64).foldLeft(BigInt(0))((v,i)=>v|(BigInt(m.getOrElse(a+i,0))<<(8*i)))
      def tick():Unit={
        sys.memoryRequest.ready.poke((cycle%3!=0).B);val gr=gpu.headOption.filter(_._3<=cycle);sys.memoryResponse.valid.poke(gr.nonEmpty.B)
        gr.foreach(r=>{sys.memoryResponse.bits.transactionId.poke(r._1.U);sys.memoryResponse.bits.readData.poke(r._2.U)})
        if(gr.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)gpu.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){val a=sys.memoryRequest.bits.address.peek().litValue
          assert(a==0x1000 || a==0x2000,s"fetch $a");gpu.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,code(if(a==0x1000)control else eval),cycle+3))}
        d.io.memoryRequest.ready.poke((cycle%4!=0).B);val br=bin.headOption.filter(_.due<=cycle);d.io.memoryResponse.valid.poke(br.nonEmpty.B)
        br.foreach(r=>{d.io.memoryResponse.bits.transactionId.poke(r.tag.U);d.io.memoryResponse.bits.readData.poke(line(binBytes,r.address).U)})
        if(br.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean){val r=bin.dequeue();if(r.write)for(i<-0 until 64 if r.mask.testBit(i))binBytes(r.address+i)=((r.data>>(8*i))&255).toInt}
        if(d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean){val q=d.io.memoryRequest.bits
          if(q.isWrite.peek().litToBoolean && q.address.peek().litValue>=0x10000 && q.address.peek().litValue<0x20000)recordWrites+=1
          bin.enqueue(Req(q.transactionId.peek().litValue,q.address.peek().litValue,q.isWrite.peek().litToBoolean,q.writeData.peek().litValue,q.byteMask.peek().litValue,cycle+3))}
        fb.memoryRequest.ready.poke((cycle%4!=0).B);val fr=feedback.headOption.filter(_.due<=cycle);fb.memoryResponse.valid.poke(fr.nonEmpty.B)
        fr.foreach(r=>{fb.memoryResponse.bits.transactionId.poke(r.tag.U);fb.memoryResponse.bits.fault.poke(faultEnabled.B)})
        if(fr.nonEmpty && fb.memoryResponse.ready.peek().litToBoolean){val r=feedback.dequeue();if(!faultEnabled)for(i<-0 until 64 if r.mask.testBit(i))feedbackBytes(r.address+i)=((r.data>>(8*i))&255).toInt}
        if(fb.memoryRequest.valid.peek().litToBoolean && fb.memoryRequest.ready.peek().litToBoolean){val q=fb.memoryRequest.bits
          assert(q.isWrite.peek().litToBoolean);fb.memorySatp.expect(0.U)
          feedback.enqueue(Req(q.transactionId.peek().litValue,q.address.peek().litValue,true,q.writeData.peek().litValue,q.byteMask.peek().litValue,cycle+24))
          if(interrupt && !resetIssued){resetIssued=true;if(reset)fb.cancel.poke(true.B)else d.io.cancel.poke(true.B)}}
        if(feedback.nonEmpty && feedback.head.due>cycle){delayed+=1;assert(!d.io.taskCompletion.valid.peek().litToBoolean)}
        if(fb.recorded.valid.peek().litToBoolean){fb.recorded.bits.bytes.expect(16.U);records+=1}
        for(e<-0 until 2){val o=d.io.out(e);o.ready.poke(outputReady.B);if(o.valid.peek().litToBoolean && outputReady){
          o.bits.record.primitive.clipped.primitive.vertices(0).varyings(0)(0).expect("h3f800000".U)
          o.bits.record.primitive.clipped.primitive.vertices(1).varyings(0)(0).expect("h3f800000".U);replay+=1}}
        d.io.tileDone.ready.poke(true.B);if(pendingEnd)fb.command.ready.expect(false.B)
        if(d.io.trap.valid.peek().litToBoolean)
          assert(!recovering && (injectFault || interrupt) && d.io.trap.bits.event.cause.peek().litValue==5,"unexpected shader trap")
        d.clock.step();fb.cancel.poke(false.B);d.io.cancel.poke(false.B);cycle+=1
      }
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<150000){tick();n+=1};assert(p,s"$label timeout at $cycle")}
      def setupCommand(action:Int):Unit={zero(fb.command.bits);fb.command.bits.action.poke(action.U);val q=fb.command.bits.state.descriptor
        q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(1.U);q.interleaved.poke(true.B);q.buffers.poke(1.U);q.components(0).poke(2.U)
          q.selectors(0).poke(0.U);q.selectors(1).poke(4.U);q.views(0).base.poke(0x8000.U);q.views(0).limit.poke(0x8100.U)}}
      def takeReply():Unit={until("feedback reply")(fb.commandReply.valid.peek().litToBoolean);fb.commandReply.bits.status.expect(0.U)
        fb.commandReply.ready.poke(true.B);tick();fb.commandReply.ready.poke(false.B)}
      setupCommand(TransformFeedbackAction.begin);fb.command.valid.poke(true.B);until("feedback begin")(fb.command.ready.peek().litToBoolean)
      tick();fb.command.valid.poke(false.B);until("held feedback reply")(fb.commandReply.valid.peek().litToBoolean)
      d.io.begin.valid.poke(true.B);for(_<-0 until 3){d.io.begin.ready.expect(false.B);tick()};d.io.begin.valid.poke(false.B)
      takeReply()
      val b=d.io.begin.bits;b.producer.version.poke(1.U);b.producer.arena.version.poke(1.U);b.producer.arena.base.poke(0x10000.U);b.producer.arena.limit.poke(0x20000.U)
      b.producer.screenWidth.poke(4.U);b.producer.screenHeight.poke(4.U);b.producer.tileLog2.poke(3.U)
      b.bins.version.poke(1.U);b.bins.tileCount.poke(1.U);b.bins.pool.version.poke(1.U);b.bins.pool.base.poke(0x40000.U);b.bins.pool.limit.poke(0x44000.U);b.bins.pool.pageSizeLog2.poke(7.U)
      d.io.begin.valid.poke(true.B);until("bin begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
      setupCommand(TransformFeedbackAction.end);fb.command.valid.poke(true.B);pendingEnd=true
      val s=fb.scope;s.context.poke(7.U);s.root.poke(0.U);s.program.poke(9.U);s.mode.poke(1.U);s.cookie.poke(1.U);s.rasterDiscard.poke(discard.B)
      val task=d.io.task.bits;val view=task.viewport;val t=view.system.patch;val ctl=t.control
      view.viewport.version.poke(1.U);view.viewport.extentX.poke(4.U);view.viewport.extentY.poke(4.U);view.viewport.farDepth.poke("h3f800000".U)
      t.commandId.poke(3.U);t.evaluationKernel.kernelPc.poke(0x2000.U);t.outputComponents.poke(4.U);t.patchInputCount.poke(1.U);t.patchInputs(0).perPatch.poke(true.B)
      for(i<-Seq(0,2,3)){view.componentSources(i).valid.poke(true.B);view.componentSources(i).bits.poke((if(i==3)3 else 0).U)}
      view.varyingSlots.poke(1.U);view.flatMask.poke(1.U);view.componentSources(4).valid.poke(true.B);view.componentSources(4).bits.poke(0.U)
      ctl.patch.context.layout.inputVertices.poke(1.U);ctl.patch.context.layout.outputVertices.poke(4.U);ctl.patch.context.layout.patchComponents.poke(1.U)
      ctl.kernel.kernelPc.poke(0x1000.U);for(i<-0 until 3){ctl.kernel.gridSize(i).poke(1.U);ctl.kernel.localSize(i).poke((if(i==0)4 else 1).U)}
      ctl.bases(0).poke(0.U);ctl.bases(1).poke(4.U);task.raster.version.poke(1.U);task.raster.pointSize.poke("h3f800000".U)
      def runTask(id:Int,status:Int,feedbackStatus:Int,admitted:Int):Unit={
        s.context.poke(7.U);s.program.poke(9.U);ctl.patch.context.taskId.poke(id.U)
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
        s.context.poke(99.U);s.program.poke(99.U)
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean);tick();d.io.opened.ready.poke(false.B)
        d.io.seal.valid.poke(true.B);until("seal")(d.io.seal.ready.peek().litToBoolean);tick();d.io.seal.valid.poke(false.B)
        until("task completion")(d.io.taskCompletion.valid.peek().litToBoolean);d.io.taskCompletion.bits.status.expect(status.U)
        d.io.taskCompletion.bits.source.feedbackStatus.expect(feedbackStatus.U);d.io.taskCompletion.bits.admitted.expect(admitted.U)
        d.io.taskCompletion.ready.poke(true.B);tick();d.io.taskCompletion.ready.poke(false.B)
      }
      for(id<- (if(passStatus!=0)Seq(19)else Seq(19,20)))
        runTask(id,passStatus,if(injectFault)3 else if(interrupt)4 else 0,if(passStatus!=0 || discard)0 else 1)
      if(passStatus==0){d.io.end.valid.poke(true.B);d.io.end.bits.poke(true.B);until("bin end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)}
      outputReady=true;until("pass completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(passStatus.U)
      d.io.completion.bits.nextSequence.expect((if(passStatus!=0 || discard)0 else 2).U)
      assert(records==(if(passStatus!=0)0 else 2) && replay==(if(passStatus!=0 || discard)0 else 2) && delayed>0 && gpu.isEmpty && bin.isEmpty && feedback.isEmpty)
      assert(if(passStatus!=0 || discard)recordWrites==0 else recordWrites>0)
      if(interrupt)assert(resetIssued,"cancellation did not cut an accepted feedback store")
      def word(a:Int):BigInt=(0 until 4).foldLeft(BigInt(0))((v,i)=>v|(BigInt(feedbackBytes.getOrElse(BigInt(a+i),0))<<(8*i)))
      val expected=Seq[BigInt](0,0,BigInt("3f800000",16),BigInt("3f800000",16))
      if(injectFault)assert(feedbackBytes.isEmpty)
      else if(!interrupt)assert((0 until 8).map(i=>word(0x8000+4*i))==expected++expected)
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);pendingEnd=false
      until("feedback end")(fb.command.ready.peek().litToBoolean);tick();fb.command.valid.poke(false.B)
      until("feedback end reply")(fb.commandReply.valid.peek().litToBoolean);fb.commandReply.bits.status.expect((if(reset)5 else if(injectFault)3 else if(interrupt)4 else 0).U)
      fb.commandReply.bits.state.primitives.expect((if(passStatus!=0)0 else 2).U);fb.commandReply.bits.state.vertices.expect((if(passStatus!=0)0 else 4).U)
      fb.commandReply.bits.state.offsets(0).expect((if(passStatus!=0)0 else 32).U)
      fb.commandReply.ready.poke(true.B);tick();fb.commandReply.ready.poke(false.B)
      if(passStatus!=0){
        faultEnabled=false;recovering=true;feedbackBytes.clear()
        setupCommand(TransformFeedbackAction.begin);fb.command.valid.poke(true.B);until("retry feedback begin")(fb.command.ready.peek().litToBoolean)
        tick();fb.command.valid.poke(false.B);takeReply()
        d.io.begin.valid.poke(true.B);until("retry bin begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
        setupCommand(TransformFeedbackAction.end);fb.command.valid.poke(true.B);pendingEnd=true
        runTask(21,0,0,1)
        d.io.end.bits.poke(true.B);d.io.end.valid.poke(true.B);until("retry bin end")(d.io.end.ready.peek().litToBoolean);tick();d.io.end.valid.poke(false.B)
        until("retry pass completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(0.U)
        assert(records==1 && replay==1 && gpu.isEmpty && bin.isEmpty && feedback.isEmpty)
        assert((0 until 4).map(i=>word(0x8000+4*i))==expected)
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);pendingEnd=false
        until("retry feedback end")(fb.command.ready.peek().litToBoolean);tick();fb.command.valid.poke(false.B)
        until("retry feedback end reply")(fb.commandReply.valid.peek().litToBoolean);fb.commandReply.bits.status.expect(0.U)
        fb.commandReply.bits.state.primitives.expect(1.U);fb.commandReply.bits.state.vertices.expect(2.U);fb.commandReply.bits.state.offsets(0).expect(16.U)
        fb.commandReply.ready.poke(true.B);tick()
      }
    }
  }
}
