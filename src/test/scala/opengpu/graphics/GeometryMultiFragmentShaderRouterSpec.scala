package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system.GeometryMultiFragmentShaderRouter
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryMultiFragmentShaderRouterSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  it should "retain separate geometry and fragment ownership through reordered completions" in {
    val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=512,owners=3,ports=3)
    simulate(new GeometryMultiFragmentShaderRouter(GpuConfig(lanes=4,warps=2),3,4,2,Some(payload))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val g=d.io.geometry;val pool=d.io.pool;g.completion.ready.poke(true.B)
      for(i<-0 until 3){d.io.clientPayload.get(i).valid.poke(true.B);d.io.clientPayload.get(i).bits.base.poke((0x1000+i*0x1000).U)}
      for(e<-0 until 2)d.io.fragment(e).completion.ready.poke(true.B)
      g.geometryTask.get.valid.poke(true.B);g.geometryTask.get.bits.taskId.poke(19.U)
      g.launch.bits.commandId.poke(7.U);g.launch.valid.poke(true.B);g.launch.ready.expect(true.B)
      d.clock.step();g.launch.valid.poke(false.B);g.geometryTask.get.valid.poke(false.B)
      for(e<-0 until 2){if(e==1){pool.launch.ready.poke(true.B);pool.launch.valid.expect(true.B)
          pool.launch.bits.commandId.expect(0.U);pool.geometryTask.get.valid.expect(true.B)
          d.io.poolPayload.get.bits.base.expect(0x1000.U)}
        val f=d.io.fragment(e);f.launch.bits.commandId.poke((8+e).U);f.launch.valid.poke(true.B)
        f.launch.ready.expect(true.B);d.clock.step();f.launch.valid.poke(false.B)}
      pool.launch.ready.poke(true.B)
      for(tag<-1 until 3){pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(tag.U)
        pool.geometryTask.get.valid.expect((tag==0).B);d.io.poolPayload.get.bits.base.expect((0x1000+tag*0x1000).U);d.clock.step()}
      pool.owned.poke(7.U);for(e<-0 until 3)pool.taskIds(e).poke(e.U)
      g.owned.expect(1.U);d.io.fragment(0).owned.expect(2.U);d.io.fragment(1).owned.expect(4.U)
      pool.geometryEvent.get(0).valid.poke(true.B);g.geometryEvent.get(0).ready.poke(true.B)
      g.geometryEvent.get(0).valid.expect(true.B);pool.geometryEvent.get(0).ready.expect(true.B)
      pool.geometryEvent.get(0).valid.poke(false.B);pool.owned.poke(0.U)
      for(tag<-Seq(2,0,1)){pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(tag.U)
        if(tag==0){g.completion.valid.expect(true.B);g.completion.bits.commandId.expect(7.U)}
        else {val f=d.io.fragment(tag-1);f.completion.valid.expect(true.B);f.completion.bits.commandId.expect((7+tag).U)}
        d.clock.step()}
      pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }
}
