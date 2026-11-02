package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationNativeDomainJobImageAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "reject malformed job envelopes and unknown domain records with exact read accounting" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2);val f=FragmentShaderConfig(2,4)
    val g=GraphicsConfig(screenWidth=math.min(4,GraphicsTestResolution.width),screenHeight=math.min(4,GraphicsTestResolution.height))
    simulate(new TessellationNativeDomainJobImage(cfg,p,f,SharedCuShaderConfig(2,taskIdWidth=4),g,
      engines=1,pageBytes=128,writeCredits=2,tileSlots=1,pagedCapacity=8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<1000){d.clock.step();n+=1};assert(condition,s"$label timeout")}
      def submit(valid:Boolean):Unit={d.io.submit.bits.commandId.poke(3.U);d.io.submit.bits.base.poke("h1000".U)
        d.io.submit.bits.limit.poke("h1200".U);d.io.submit.bits.envelopeValid.poke(valid.B);d.io.submit.valid.poke(true.B)
        until("submit")(d.io.submit.ready.peek().litToBoolean);d.clock.step();d.io.submit.valid.poke(false.B)}
      def finish(bytes:Int):Unit={until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.completion.bits.commandId.expect(3.U);d.io.completion.bits.status.expect(1.U)
        d.io.completion.bits.descriptorReadBytes.expect(bytes.U);d.io.completion.bits.details(0).expect("h474e5a01".U)
        val held=d.io.completion.bits.peek().litValue;d.clock.step(3);assert(d.io.completion.bits.peek().litValue==held)
        d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
        until("drain")(!d.io.busy.peek().litToBoolean)}
      submit(false);d.io.memoryRequest.valid.expect(false.B);finish(0)
      submit(true);until("probe request")(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.bits.address.expect("h1000".U);val id=d.io.memoryRequest.bits.transactionId.peek().litValue
      d.io.memoryRequest.ready.poke(true.B);d.clock.step();d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.readData.poke(0.U)
      d.io.memoryResponse.valid.poke(true.B);until("probe response")(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step();d.io.memoryResponse.valid.poke(false.B);finish(64)
      submit(false);finish(0)
    }
  }
}
