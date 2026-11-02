package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeDomainFeedbackAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "retain live feedback through rejected jobs and recover after reset" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    simulate(new TessellationNativeDomainJobImage(cfg,p,f,SharedCuShaderConfig(2,taskIdWidth=4),g,
      engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8,transformFeedback=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<3000){d.clock.step();n+=1};assert(condition,s"$label timeout")}
      val fb=d.io.feedback.get;fb.scope.context.poke(7.U);fb.scope.root.poke(0x30.U);fb.scope.program.poke(9.U)
      fb.scope.mode.poke(1.U);fb.scope.cookie.poke(1.U)
      def command(action:Int):Unit={zero(fb.command.bits);val c=fb.command.bits;c.action.poke(action.U);val state=c.state;val q=state.descriptor
        state.root.poke(0x30.U);q.context.poke(7.U);q.cookie.poke(1.U);q.program.poke(9.U)
        if(action==TransformFeedbackAction.begin){q.kind.poke(1.U);q.interleaved.poke(true.B);q.buffers.poke(1.U)
          q.components(0).poke(1.U);q.views(0).base.poke(0x1000.U);q.views(0).limit.poke(0x1100.U)}
        fb.command.valid.poke(true.B);until("feedback command")(fb.command.ready.peek().litToBoolean);d.clock.step();fb.command.valid.poke(false.B)
        until("feedback reply")(fb.commandReply.valid.peek().litToBoolean);fb.commandReply.bits.status.expect(0.U)
        fb.commandReply.bits.state.descriptor.cookie.expect(1.U);fb.commandReply.ready.poke(true.B);d.clock.step();fb.commandReply.ready.poke(false.B)}
      command(TransformFeedbackAction.begin)
      d.io.submit.bits.commandId.poke(3.U);d.io.submit.bits.envelopeValid.poke(false.B);d.io.submit.valid.poke(true.B)
      until("submit")(d.io.submit.ready.peek().litToBoolean);d.clock.step();d.io.submit.valid.poke(false.B)
      until("job completion")(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.status.expect(1.U)
      d.io.completion.bits.descriptorReadBytes.expect(0.U);d.io.memoryRequest.valid.expect(false.B)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);command(TransformFeedbackAction.end)
      command(TransformFeedbackAction.begin);fb.cancel.poke(true.B);d.clock.step()
      until("feedback reset drain")(!fb.busy.peek().litToBoolean);fb.cancel.poke(false.B);d.clock.step()
      command(TransformFeedbackAction.begin);command(TransformFeedbackAction.end)
    }
  }
}
