package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system.GeometryFragmentShaderRouter
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryFragmentShaderRouterSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  it should "route geometry and fragment tasks with independent ownership and payloads" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    val payload=ShaderPayloadConfig(capacityBytes=4096,maxPayloadBytes=512,owners=2,ports=2)
    simulate(new GeometryFragmentShaderRouter(cfg,2,4,Some(payload),imageAvailable=true)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val g=d.io.geometry;val f=d.io.fragment;val pool=d.io.pool
      g.completion.ready.poke(true.B);f.completion.ready.poke(true.B)
      g.imageTask.get.enabled.poke(true.B);g.imageTask.get.stage.poke(GraphicsStage.geometry.U);g.imageTask.get.table.base.poke(0x5000.U)
      f.imageTask.get.enabled.poke(true.B);f.imageTask.get.stage.poke(GraphicsStage.fragment.U);f.imageTask.get.table.base.poke(0x6000.U)
      g.geometryTask.get.valid.poke(true.B);g.geometryTask.get.bits.taskId.poke(19.U)
      g.launch.bits.commandId.poke(7.U);g.launch.valid.poke(true.B)
      d.io.clientPayload.get(0).valid.poke(true.B);d.io.clientPayload.get(0).bits.base.poke(0x1000.U)
      g.launch.ready.expect(true.B);d.clock.step();g.launch.valid.poke(false.B);g.geometryTask.get.valid.poke(false.B)
      f.launch.bits.commandId.poke(7.U);f.launch.valid.poke(true.B)
      d.io.clientPayload.get(1).valid.poke(true.B);d.io.clientPayload.get(1).bits.base.poke(0x2000.U)
      f.launch.ready.expect(true.B);d.clock.step();f.launch.valid.poke(false.B)
      g.imageTask.get.table.base.poke(0x7000.U);f.imageTask.get.table.base.poke(0x8000.U)
      d.io.clientPayload.get(0).bits.base.poke(0x3000.U);d.io.clientPayload.get(1).bits.base.poke(0x4000.U)
      d.io.payloadPins.get(0).valid.expect(true.B);d.io.payloadPins.get(1).valid.expect(true.B)
      for(_<-0 until 3){pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(0.U)
        pool.geometryTask.get.valid.expect(true.B);pool.geometryTask.get.bits.taskId.expect(19.U)
        pool.imageTask.get.enabled.expect(true.B);pool.imageTask.get.stage.expect(GraphicsStage.geometry.U);pool.imageTask.get.table.base.expect(0x5000.U)
        d.io.poolPayload.get.valid.expect(true.B);d.io.poolPayload.get.bits.base.expect(0x1000.U);d.clock.step()}
      pool.launch.ready.poke(true.B);d.clock.step();pool.launch.bits.commandId.expect(1.U)
      pool.imageTask.get.enabled.expect(true.B);pool.imageTask.get.stage.expect(GraphicsStage.fragment.U);pool.imageTask.get.table.base.expect(0x6000.U)
      pool.geometryTask.get.valid.expect(false.B);d.io.poolPayload.get.bits.base.expect(0x2000.U);d.clock.step()
      pool.owned.poke(3.U);pool.taskIds(0).poke(0.U);pool.taskIds(1).poke(1.U)
      g.owned.expect(1.U);f.owned.expect(2.U);g.taskIds(0).expect(7.U);f.taskIds(1).expect(7.U)
      pool.geometryEvent.get(0).valid.poke(true.B);pool.geometryEvent.get(0).bits.operation.poke(1.U)
      g.geometryEvent.get(0).ready.poke(true.B);g.geometryCancel.get(0).poke(true.B)
      g.geometryEvent.get(0).valid.expect(true.B);pool.geometryEvent.get(0).ready.expect(true.B)
      pool.geometryCancel.get(0).expect(true.B);pool.geometryCancel.get(1).expect(false.B)
      pool.geometryEvent.get(0).valid.poke(false.B);pool.owned.poke(0.U)
      for(tag<-Seq(1,0)){pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(tag.U)
        if(tag==0)g.completion.bits.commandId.expect(7.U)else f.completion.bits.commandId.expect(7.U)
        d.clock.step()}
      pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
      d.io.payloadPins.get(0).valid.expect(false.B);d.io.payloadPins.get(1).valid.expect(false.B)
    }
  }
}
