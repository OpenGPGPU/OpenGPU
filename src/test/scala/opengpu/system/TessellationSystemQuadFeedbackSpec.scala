package opengpu.system

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationQuadFeedbackSystemHarness(config:GpuConfig) extends Module {
  val gpu=Module(new GpuSystem(config,numComputeUnits=1,commandIdWidth=4,transactionsPerCu=4,
    enableGraphicsKernels=true,geometryAvailable=true,tessellationAvailable=true,
    instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  val client=Module(new TessellationSystemQuadViewport(config,VertexShaderStageConfig(),4,1,transformFeedback=true))
  val io=IO(new Bundle {
    val system=chiselTypeOf(gpu.io);val task=Flipped(Decoupled(new TessellationSystemQuadViewportTask(config,VertexShaderStageConfig(),4)))
    val opened=Decoupled(UInt(2.W));val seal=Flipped(Decoupled(Bool()))
    val out=Decoupled(new WindowPrimitive(VertexShaderStageConfig()));val completion=Decoupled(new TessellationSystemQuadViewportCompletion)
    val feedback=new TransformFeedbackPipelineIO(config);val cancel=Input(Bool());val trap=Decoupled(new TaggedGraphicsTrap(config,4,1))
  })
  private def tie(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case _=>()
  }
  tie(client.io);io.system<>gpu.io;client.io.shared<>gpu.io.graphicsKernels.get
  client.io.task<>io.task;io.opened<>client.io.opened;client.io.seal<>io.seal
  io.out<>client.io.out;io.completion<>client.io.completion;client.io.cancel:=io.cancel
  io.trap<>client.io.trap;io.feedback<>client.io.feedback.get
}

class TessellationSystemQuadFeedbackSpec extends AnyFlatSpec {
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
  for(testMode<-Seq("capture","discard","fault","cancel","cancel_fault","reset","reset_fault")) it should s"retain tessellation feedback ownership through $testMode" in {
    val config=GpuConfig(lanes=4,warps=2,l2Sets=8,l2Ways=2)
    simulate(new TessellationQuadFeedbackSystemHarness(config)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      val sys=d.io.system;val fb=d.io.feedback
      sys.graphicsDrained.poke(true.B);sys.graphicsShaderL1Invalidate.ready.poke(true.B);d.io.trap.ready.poke(true.B)
      val control=Vector(BigInt("0104f057",16),BigInt("3f800537",16),vector(23,4,3,10),
        vector(23,3,2,0),patch(20,vs1=3)) ++ Vector(7,15,23,31,39,47,3).flatMap { address => Vector(
          BigInt((address << 20) | (5 << 7) | 0x13),vector(23,4,4,5),patch(22,vs1=2,vs2=4)) } ++
        Vector(BigInt("0000000b",16),BigInt("30500073",16))
      val eval=Vector(BigInt("00000093",16),BigInt("00000113",16),geo(32,3,1,2),BigInt("00000213",16),geo(33,0,4,3),
        BigInt("00100113",16),geo(32,3,1,2),BigInt("00100213",16),geo(33,0,4,3),
        BigInt("00300113",16),geo(32,3,1,2),BigInt("00200213",16),geo(33,0,4,3),
        BigInt("3f8001b7",16),BigInt("00300213",16),geo(33,0,4,3),geo(35),geo(36),BigInt("30500073",16))
      def line(p:Vector[BigInt]):BigInt=p.zipWithIndex.map{case(w,i)=>w<<(32*i)}.sum
      val reads=mutable.Queue.empty[(BigInt,BigInt,Int)]
      val stores=mutable.Queue.empty[(BigInt,BigInt,BigInt,BigInt,Int)]
      val bytes=mutable.Map.empty[BigInt,Int];var cycle=0;var held=0;var outputs=0;var recorded=0;var cancelled=false
      val fault=testMode=="fault" || testMode.endsWith("_fault");var faultEnabled=fault;var faultResponseSeen=false
      val reset=testMode.startsWith("reset")
      var expectedEndStatus=if(reset)5 else if(fault)3 else if(testMode=="cancel")4 else 0
      def tick():Unit={
        sys.memoryRequest.ready.poke((cycle%3!=0).B)
        val read=reads.headOption.filter(_._3<=cycle);sys.memoryResponse.valid.poke(read.nonEmpty.B)
        read.foreach{case(id,data,_)=>sys.memoryResponse.bits.transactionId.poke(id.U);sys.memoryResponse.bits.readData.poke(data.U)}
        if(read.nonEmpty && sys.memoryResponse.ready.peek().litToBoolean)reads.dequeue()
        if(sys.memoryRequest.valid.peek().litToBoolean && sys.memoryRequest.ready.peek().litToBoolean){
          val a=sys.memoryRequest.bits.address.peek().litValue;assert(a==0x1000 || a==0x1040 || a==0x2000 || a==0x2040,s"instruction fetch $a")
          reads.enqueue((sys.memoryRequest.bits.transactionId.peek().litValue,line(if(a==0x1000)control.take(16) else if(a==0x1040)control.drop(16) else if(a==0x2000)eval.take(16) else eval.drop(16)),cycle+3))
        }
        fb.memoryRequest.ready.poke((cycle%4!=0).B)
        val reply=stores.headOption.filter(_._5<=cycle);fb.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach{case(id,_,_,_,_)=>fb.memoryResponse.bits.transactionId.poke(id.U);fb.memoryResponse.bits.fault.poke(faultEnabled.B)}
        if(reply.nonEmpty && fb.memoryResponse.ready.peek().litToBoolean){
          val (_,a,data,mask,_)=stores.dequeue();if(faultEnabled)faultResponseSeen=true
          if(!faultEnabled)for(i<-0 until 64 if mask.testBit(i))bytes(a+i)=((data>>(8*i))&255).toInt
        }
        if(fb.memoryRequest.valid.peek().litToBoolean && fb.memoryRequest.ready.peek().litToBoolean){
          val r=fb.memoryRequest.bits;assert(r.isWrite.peek().litToBoolean);fb.memorySatp.expect(0.U)
          stores.enqueue((r.transactionId.peek().litValue,r.address.peek().litValue,r.writeData.peek().litValue,r.byteMask.peek().litValue,cycle+24))
          if((testMode.startsWith("cancel") || reset) && !cancelled){
            cancelled=true;if(reset)fb.cancel.poke(true.B)else d.io.cancel.poke(true.B)
          }
        }
        if(stores.nonEmpty && stores.head._5>cycle){held+=1;d.io.completion.valid.expect(false.B)}
        d.io.out.ready.poke(true.B)
        if(d.io.out.valid.peek().litToBoolean){
          val o=d.io.out.bits;o.clipped.clipped.expect(false.B)
          val flat = if (outputs % 2 == 0) BigInt("3f800000",16) else BigInt(0)
          for (i <- 0 until 3) o.clipped.primitive.vertices(i).varyings(0)(0).expect(flat.U)
          outputs+=1
        }
        if(fb.recorded.valid.peek().litToBoolean){fb.recorded.bits.bytes.expect(24.U);recorded+=1}
        if(d.io.trap.valid.peek().litToBoolean){
          assert(cancelled || faultResponseSeen, s"unexpected trap at $cycle")
          d.io.trap.bits.event.cause.expect(5.U)
        }
        d.clock.step();d.io.cancel.poke(false.B);fb.cancel.poke(false.B);cycle+=1
      }
      def until(label:String)(p: =>Boolean):Unit={var n=0;while(!p && n<100000){tick();n+=1};assert(p,s"$label timeout at $cycle")}
      def command(action:Int):Unit={
        zero(fb.command.bits);fb.command.bits.action.poke(action.U)
        val s=fb.command.bits.state;val q=s.descriptor;s.root.poke(0.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(2.U);q.interleaved.poke(true.B);q.buffers.poke(1.U)
          q.components(0).poke(2.U);q.selectors(0).poke(0.U);q.selectors(1).poke(4.U);q.views(0).base.poke(0x4000.U);q.views(0).limit.poke(0x4100.U)}
        fb.command.valid.poke(true.B);until("feedback command")(fb.command.ready.peek().litToBoolean);tick();fb.command.valid.poke(false.B)
        until("feedback reply")(fb.commandReply.valid.peek().litToBoolean)
        val finalStatus=expectedEndStatus
        fb.commandReply.bits.status.expect((if(action==TransformFeedbackAction.end)finalStatus else 0).U)
        if(action==TransformFeedbackAction.end){val ok=finalStatus==0;fb.commandReply.bits.state.primitives.expect((if(ok)2 else 0).U)
          fb.commandReply.bits.state.vertices.expect((if(ok)6 else 0).U);fb.commandReply.bits.state.offsets(0).expect((if(ok)48 else 0).U)}
        fb.commandReply.ready.poke(true.B);tick();fb.commandReply.ready.poke(false.B)
      }
      command(TransformFeedbackAction.begin)
      val s=fb.scope;s.context.poke(7.U);s.root.poke(0.U);s.program.poke(9.U);s.mode.poke(1.U);s.cookie.poke(1.U)
      s.rasterDiscard.poke((testMode=="discard").B)
      val render=d.io.task.bits;val t=render.system.patch;val ctl=t.control;ctl.patch.context.taskId.poke(31.U)
      ctl.patch.context.layout.inputVertices.poke(1.U);ctl.patch.context.layout.outputVertices.poke(4.U);ctl.patch.context.layout.patchComponents.poke(1.U)
      ctl.kernel.kernelPc.poke(0x1000.U);for(i<-0 until 3){ctl.kernel.gridSize(i).poke(1.U);ctl.kernel.localSize(i).poke((if(i==0)4 else 1).U)}
      ctl.bases(0).poke(0.U);ctl.bases(1).poke(4.U);t.commandId.poke(3.U);t.evaluationKernel.kernelPc.poke(0x2000.U);t.outputComponents.poke(4.U)
      t.patchInputCount.poke(1.U);t.patchInputs(0).perPatch.poke(true.B)
      for(i<-Seq(0,1,3)){render.componentSources(i).valid.poke(true.B);render.componentSources(i).bits.poke(i.U)}
      render.varyingSlots.poke(1.U);render.flatMask.poke(1.U);render.componentSources(4).valid.poke(true.B);render.componentSources(4).bits.poke(0.U)
      render.viewport.version.poke(1.U);render.viewport.extentX.poke(4.U);render.viewport.extentY.poke(4.U);render.viewport.farDepth.poke("h3f800000".U)
      def runTask():Unit={
        s.context.poke(7.U);s.program.poke(9.U)
        d.io.task.valid.poke(true.B);until("task")(d.io.task.ready.peek().litToBoolean);tick();d.io.task.valid.poke(false.B)
        s.context.poke(99.U);s.program.poke(99.U)
        d.io.opened.ready.poke(true.B);until("open")(d.io.opened.valid.peek().litToBoolean);tick();d.io.opened.ready.poke(false.B)
        d.io.seal.valid.poke(true.B);until("seal")(d.io.seal.ready.peek().litToBoolean);tick();d.io.seal.valid.poke(false.B)
      }
      runTask()
      until("completion")(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect((if(fault)5 else if(cancelled)4 else 0).U)
      d.io.completion.bits.feedbackStatus.expect((if(fault)3 else if(cancelled)4 else 0).U)
      val captured=testMode=="capture" || testMode=="discard"
      assert(outputs==(if(testMode=="capture")2 else 0) && recorded==(if(captured)2 else 0) && held>0 && stores.isEmpty && reads.isEmpty)
      if(testMode.startsWith("cancel") || reset)assert(cancelled,"cancellation did not cut an accepted feedback store")
      def word(a:Int):BigInt=(0 until 4).foldLeft(BigInt(0))((v,i)=>v|(BigInt(bytes.getOrElse(BigInt(a+i),0))<<(8*i)))
      if(fault)assert(bytes.isEmpty)
      else if(captured)assert((0 until 12).map(i=>word(0x4000+4*i))==Seq[BigInt](0,0,BigInt("3f800000",16),BigInt("3f800000",16),BigInt("3f800000",16),BigInt("3f800000",16),0,0,BigInt("3f800000",16),BigInt("3f800000",16),0,0))
      d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);command(TransformFeedbackAction.end)
      if(fault || cancelled){
        faultEnabled=false;faultResponseSeen=false;expectedEndStatus=0;s.rasterDiscard.poke(false.B);bytes.clear()
        command(TransformFeedbackAction.begin);runTask()
        until("healthy retry")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(0.U);d.io.completion.bits.feedbackStatus.expect(0.U)
        assert(outputs==2 && recorded==2 && stores.isEmpty && reads.isEmpty)
        assert((0 until 12).map(i=>word(0x4000+4*i))==Seq[BigInt](0,0,BigInt("3f800000",16),BigInt("3f800000",16),BigInt("3f800000",16),BigInt("3f800000",16),0,0,BigInt("3f800000",16),BigInt("3f800000",16),0,0))
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);command(TransformFeedbackAction.end)
      }
    }
  }
}
