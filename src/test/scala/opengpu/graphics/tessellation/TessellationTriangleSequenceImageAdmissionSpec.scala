package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system.{SharedCuShaderConfig,TessellationTriangleSequenceImage}
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationTriangleSequenceImageAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "reject invalid frame descriptors before shader or memory traffic and allow reuse" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    simulate(new TessellationTriangleSequenceImage(cfg,p,f,SharedCuShaderConfig(2,taskIdWidth=4),
      g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height)),engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def tick():Unit={d.io.shared.launch.valid.expect(false.B);d.io.memoryRequest.valid.expect(false.B)
        d.io.inputMemoryRequest.valid.expect(false.B);d.clock.step()}
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<1000){tick();n+=1};assert(condition,s"$label timeout")}
      for(_<-0 until 2){
        d.io.begin.valid.poke(true.B);until("begin")(d.io.begin.ready.peek().litToBoolean);tick();d.io.begin.valid.poke(false.B)
        until("rejected completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.status.expect(1.U);d.io.completion.bits.img.faultStage.expect(1.U)
        d.io.completion.bits.inputFaultValid.expect(false.B)
        val held=d.io.completion.bits.peek().litValue
        for(_<-0 until 4){tick();d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==held)}
        d.io.begin.ready.expect(false.B);d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B)
      }
      d.io.busy.expect(false.B)
    }
  }
}
