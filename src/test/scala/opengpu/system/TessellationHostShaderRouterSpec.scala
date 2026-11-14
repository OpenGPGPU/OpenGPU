package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.{GraphicsStage, ShaderPayloadConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationHostShaderRouterSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "isolate legacy and tessellation tasks, sidebands and payload leases through shared CUs" in {
    val cfg=GpuConfig(lanes=4,warps=2);val payload=ShaderPayloadConfig(owners=2,ports=4)
    simulate(new TessellationHostShaderRouter(cfg,2,8,1,Some(payload))) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val a=d.io.legacy;val b=d.io.tessellation;val pool=d.io.pool
      a.completion.ready.poke(true.B);b.completion.ready.poke(true.B)
      for(i<-0 until 2){
        val binding=d.io.clientPayload.get(i);binding.valid.poke(true.B);binding.bits.handle.poke(i.U)
        binding.bits.owner.poke(i.U);binding.bits.generation.poke((10+i).U);binding.bits.context.poke((30+i).U)
        binding.bits.base.poke((0x1000+i*0x1000).U);binding.bits.bytes.poke(288.U)
      }
      a.launch.bits.commandId.poke(23.U);a.launch.valid.poke(true.B);a.launch.ready.expect(true.B)
      d.clock.step();a.launch.valid.poke(false.B);d.io.clientPayload.get(0).valid.poke(false.B)
      b.tessellationBases.get.valid.poke(true.B);b.tessellationBases.get.bits(0).poke(3.U);b.tessellationBases.get.bits(1).poke(7.U)
      b.launch.bits.commandId.poke(23.U);b.launch.valid.poke(true.B);b.launch.ready.expect(true.B)
      d.clock.step();b.launch.valid.poke(false.B);b.tessellationBases.get.valid.poke(false.B);d.io.clientPayload.get(1).valid.poke(false.B)
      for(_<-0 until 3){
        pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(0.U)
        pool.tessellationBases.get.valid.expect(false.B);pool.geometryTask.get.valid.expect(false.B)
        d.io.poolPayload.get.valid.expect(true.B);d.io.poolPayload.get.bits.generation.expect(10.U);d.clock.step()
      }
      pool.launch.ready.poke(true.B);d.clock.step()
      pool.launch.bits.commandId.expect(1.U);pool.tessellationBases.get.valid.expect(true.B)
      pool.tessellationBases.get.bits(1).expect(7.U);d.io.poolPayload.get.bits.generation.expect(11.U)
      d.clock.step();pool.launch.ready.poke(false.B)
      assert(d.io.payloadPins.get.count(_.valid.peek().litToBoolean)==2)
      pool.owned.poke(3.U);pool.taskIds(0).poke(0.U);pool.taskIds(1).poke(1.U)
      a.owned.expect(1.U);b.owned.expect(2.U);a.taskIds(0).expect(23.U);b.taskIds(1).expect(23.U)
      val patch=pool.tessellation.get(1);val target=b.tessellation.get(1)
      patch.request.valid.poke(true.B);patch.request.bits.base.poke(7.U);target.request.ready.poke(true.B)
      target.request.valid.expect(true.B);target.request.bits.base.expect(7.U);patch.request.ready.expect(true.B)
      d.clock.step();patch.request.valid.poke(false.B)
      target.result.valid.poke(true.B);target.result.bits.data(2).poke(0x1234.U);patch.result.ready.poke(true.B)
      patch.result.valid.expect(true.B);patch.result.bits.data(2).expect(0x1234.U);d.clock.step();target.result.valid.poke(false.B)
      pool.owned.poke(0.U);pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(1.U)
      b.completion.valid.expect(true.B);b.completion.bits.commandId.expect(23.U);a.completion.valid.expect(false.B);d.clock.step()
      pool.completion.bits.commandId.poke(0.U);a.completion.valid.expect(true.B);a.completion.bits.commandId.expect(23.U)
      b.completion.valid.expect(false.B);d.clock.step();pool.completion.valid.poke(false.B)
      d.io.drained.expect(true.B);assert(!d.io.payloadPins.get.exists(_.valid.peek().litToBoolean))
      a.invalidate.valid.poke(true.B);a.invalidate.bits.lineAddress.poke(0x8000.U);a.invalidate.ready.expect(true.B)
      d.clock.step();a.invalidate.valid.poke(false.B)
      pool.invalidate.valid.expect(true.B);pool.invalidate.bits.lineAddress.expect(0x8000.U)
      pool.invalidate.ready.poke(true.B);d.clock.step();pool.invalidate.ready.poke(false.B)
      pool.invalidateDone.valid.poke(true.B);pool.invalidateDone.bits.lineAddress.poke(0x8000.U)
      a.invalidateDone.valid.expect(true.B);b.invalidateDone.valid.expect(false.B);pool.invalidateDone.ready.expect(false.B)
      d.io.drained.expect(false.B);d.clock.step(3);a.invalidateDone.ready.poke(true.B);d.clock.step()
      pool.invalidateDone.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }

  it should "retain legacy image ownership and disable it for tessellation tasks" in {
    val cfg=GpuConfig(lanes=4,warps=2)
    simulate(new TessellationHostShaderRouter(cfg,2,8,1,imageAvailable=true)) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val legacy=d.io.legacy;val tess=d.io.tessellation;val pool=d.io.pool
      legacy.completion.ready.poke(true.B);tess.completion.ready.poke(true.B)
      val image=legacy.imageTask.get
      image.enabled.poke(true.B);image.stage.poke(GraphicsStage.fragment.U)
      image.table.version.poke(1.U);image.table.identity.context.poke(7.U)
      image.table.identity.pass.poke(8.U);image.table.identity.generation.poke(9.U)
      image.table.base.poke(0x1000.U);image.table.limit.poke(0x2000.U)
      legacy.launch.bits.commandId.poke(23.U);legacy.launch.valid.poke(true.B)
      legacy.launch.ready.expect(true.B);d.clock.step();legacy.launch.valid.poke(false.B)
      image.enabled.poke(false.B);image.table.base.poke(0x5000.U)
      tess.tessellationBases.get.valid.poke(true.B);tess.tessellationBases.get.bits(0).poke(3.U)
      tess.launch.bits.commandId.poke(23.U);tess.launch.valid.poke(true.B)
      tess.launch.ready.expect(true.B);d.clock.step();tess.launch.valid.poke(false.B)
      for(_<-0 until 3){
        pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(0.U)
        pool.imageTask.get.enabled.expect(true.B);pool.imageTask.get.stage.expect(GraphicsStage.fragment.U)
        pool.imageTask.get.table.identity.context.expect(7.U);pool.imageTask.get.table.identity.pass.expect(8.U)
        pool.imageTask.get.table.identity.generation.expect(9.U);pool.imageTask.get.table.base.expect(0x1000.U)
        pool.imageTask.get.table.limit.expect(0x2000.U);d.clock.step()
      }
      pool.launch.ready.poke(true.B);d.clock.step()
      pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(1.U)
      pool.tessellationBases.get.valid.expect(true.B);pool.tessellationBases.get.bits(0).expect(3.U)
      pool.imageTask.get.enabled.expect(false.B);pool.imageTask.get.table.base.expect(0.U)
      pool.imageTask.get.table.identity.context.expect(0.U);d.clock.step();pool.launch.ready.poke(false.B)
      pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(1.U)
      tess.completion.valid.expect(true.B);legacy.completion.valid.expect(false.B);d.clock.step()
      pool.completion.bits.commandId.poke(0.U);legacy.completion.valid.expect(true.B)
      tess.completion.valid.expect(false.B);d.clock.step();pool.completion.valid.poke(false.B)
      d.io.drained.expect(true.B)
      image.enabled.poke(true.B);image.table.identity.generation.poke(10.U)
      legacy.launch.valid.poke(true.B);legacy.launch.ready.expect(true.B);d.clock.step()
      legacy.launch.valid.poke(false.B);pool.launch.valid.expect(true.B)
      pool.imageTask.get.table.base.expect(0x5000.U);pool.imageTask.get.table.identity.generation.expect(10.U)
    }
  }
  it should "snapshot tessellation fragment images through stalls cancellation drain and task reuse without legacy bleed" in {
    val cfg=GpuConfig() // Complete 16-lane / 8-warp / two-CU task interfaces.
    simulate(new TessellationHostShaderRouter(cfg,2,8,1,imageAvailable=true,tessellationImageAvailable=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val a=d.io.legacy;val b=d.io.tessellation;val pool=d.io.pool
      a.completion.ready.poke(true.B)
      def offer(client:SharedCuGraphicsPort,base:Int,generation:Int):Unit={
        val image=client.imageTask.get
        image.enabled.poke(true.B);image.stage.poke(GraphicsStage.fragment.U)
        image.table.version.poke(1.U);image.table.identity.context.poke(7.U)
        image.table.identity.pass.poke(8.U);image.table.identity.generation.poke(generation.U)
        image.table.base.poke(base.U);image.table.limit.poke((base+1024).U)
        client.launch.bits.commandId.poke(23.U);client.launch.valid.poke(true.B)
        client.launch.ready.expect(true.B);d.clock.step();client.launch.valid.poke(false.B)
        // Future publication mutations must not alter the pending launch.
        image.table.base.poke(0xdead0000L.U);image.enabled.poke(false.B)
      }
      offer(b,0x8000,9);offer(a,0xa000,10)
      for(_<-0 until 4){pool.launch.valid.expect(true.B)
        pool.launch.bits.commandId.expect(1.U);pool.imageTask.get.enabled.expect(true.B)
        pool.imageTask.get.table.base.expect(0x8000.U);pool.imageTask.get.table.identity.generation.expect(9.U)
        d.io.drained.expect(false.B);d.clock.step()}
      pool.launch.ready.poke(true.B);d.clock.step()
      pool.imageTask.get.table.base.expect(0xa000.U);pool.launch.bits.commandId.expect(0.U)
      d.clock.step();pool.launch.ready.poke(false.B)
      // Cancel remains routed to the assigned tessellation CU, while its
      // completion is backpressured. No task can reuse its slot before retire.
      pool.owned.poke(3.U);pool.taskIds(0).poke(1.U);pool.taskIds(1).poke(0.U)
      b.geometryCancel.get(0).poke(true.B);pool.geometryCancel.get(0).expect(true.B)
      pool.geometryCancel.get(1).expect(false.B);b.tessellation.get(0).cancel.poke(true.B)
      pool.tessellation.get(0).cancel.expect(true.B);pool.tessellation.get(1).cancel.expect(false.B)
      pool.owned.poke(0.U);pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(1.U)
      for(_<-0 until 3){b.completion.valid.expect(true.B);b.completion.bits.commandId.expect(23.U)
        pool.completion.ready.expect(false.B);b.launch.ready.expect(false.B);d.io.drained.expect(false.B);d.clock.step()}
      b.completion.ready.poke(true.B);d.clock.step();b.geometryCancel.get(0).poke(false.B)
      b.tessellation.get(0).cancel.poke(false.B)
      pool.completion.bits.commandId.poke(0.U);a.completion.valid.expect(true.B);d.clock.step()
      pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
      offer(b,0xc000,11);pool.launch.valid.expect(true.B)
      pool.imageTask.get.enabled.expect(true.B);pool.imageTask.get.table.base.expect(0xc000.U)
      pool.imageTask.get.table.identity.generation.expect(11.U)
      pool.launch.ready.poke(true.B);d.clock.step();pool.launch.ready.poke(false.B)
      pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(1.U);d.clock.step()
      pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
      // Patch/evaluation launches explicitly carry an empty publication after
      // a sample fragment task has retired; neither owner's table is inherited.
      zero(b.imageTask.get)
      b.tessellationBases.get.valid.poke(true.B);b.tessellationBases.get.bits(7).poke(9.U)
      b.launch.valid.poke(true.B);d.clock.step();b.launch.valid.poke(false.B)
      pool.imageTask.get.enabled.expect(false.B);pool.imageTask.get.table.base.expect(0.U)
      pool.tessellationBases.get.valid.expect(true.B);pool.tessellationBases.get.bits(7).expect(9.U)
    }
  }

}
