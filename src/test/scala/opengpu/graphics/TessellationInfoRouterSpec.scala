package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationInfoRouterSpec extends AnyFlatSpec {
  it should "capture independent language bindings and route cancellation by actual ownership across two CUs" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes=16,warps=8),2,2,tessellationInfoAvailable=true)) { d =>
      val pool=d.io.pool
      pool.launch.ready.poke(false.B);pool.completion.valid.poke(false.B)
      pool.completion.bits.poke(0.U.asTypeOf(pool.completion.bits))
      pool.trap.valid.poke(false.B);pool.trap.bits.poke(0.U.asTypeOf(pool.trap.bits))
      pool.owned.poke(0.U);pool.taskIds.foreach(_.poke(0.U));pool.invalidate.ready.poke(true.B)
      pool.invalidateDone.valid.poke(false.B);pool.invalidateDone.bits.poke(0.U.asTypeOf(pool.invalidateDone.bits))
      pool.texture.foreach { t=>t.scalar.valid.poke(false.B);t.scalar.bits.poke(0.U.asTypeOf(t.scalar.bits))
        t.vector.valid.poke(false.B);t.vector.bits.poke(0.U.asTypeOf(t.vector.bits))
        t.scalarWriteback.ready.poke(true.B);t.vectorWriteback.ready.poke(true.B) }
      d.io.clients.foreach { p=>
        p.nativeResources.poke(false.B);p.uniformResources.poke(false.B)
        p.tessellationTask.get.valid.poke(false.B);p.tessellationTask.get.bits.poke(0.U.asTypeOf(p.tessellationTask.get.bits))
        p.tessellationInfoCancel.get.foreach(_.poke(false.B))
        p.launch.valid.poke(false.B);p.launch.bits.poke(0.U.asTypeOf(p.launch.bits))
        p.completion.ready.poke(true.B);p.trap.ready.poke(true.B)
        p.invalidate.valid.poke(false.B);p.invalidate.bits.poke(0.U.asTypeOf(p.invalidate.bits));p.invalidateDone.ready.poke(true.B)
        p.texture.foreach { t=>t.scalar.ready.poke(false.B);t.vector.ready.poke(false.B)
          t.scalarWriteback.valid.poke(false.B);t.scalarWriteback.bits.poke(0.U.asTypeOf(t.scalarWriteback.bits))
          t.vectorWriteback.valid.poke(false.B);t.vectorWriteback.bits.poke(0.U.asTypeOf(t.vectorWriteback.bits)) }
      }
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(client<-0 until 2) {
        val p=d.io.clients(client);p.launch.bits.commandId.poke(0x55.U)
        p.tessellationTask.get.valid.poke(true.B);p.tessellationTask.get.bits.version.poke(1.U)
        p.tessellationTask.get.bits.stage.poke((4+client).U)
        p.tessellationTask.get.bits.taskId.poke((91+client).U)
        p.tessellationTask.get.bits.inputPatchPrimitiveId.poke((0x80001000L+client).U)
        p.launch.valid.poke(true.B);p.launch.ready.expect(true.B);d.clock.step();p.launch.valid.poke(false.B)
        p.tessellationTask.get.valid.poke(false.B);p.tessellationTask.get.bits.inputPatchPrimitiveId.poke(0.U)
      }
      for(client<-0 until 2) {
        pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(client.U)
        pool.tessellationTask.get.valid.expect(true.B)
        pool.tessellationTask.get.bits.taskId.expect((91+client).U)
        pool.tessellationTask.get.bits.inputPatchPrimitiveId.expect((0x80001000L+client).U)
        for(_<-0 until 3){d.clock.step();pool.tessellationTask.get.bits.inputPatchPrimitiveId.expect((0x80001000L+client).U)}
        pool.launch.ready.poke(true.B);d.clock.step();pool.launch.ready.poke(false.B)
      }
      // Reverse placement: local client zero owns CU1, client one owns CU0.
      pool.owned.poke(3.U);pool.taskIds(0).poke(1.U);pool.taskIds(1).poke(0.U)
      d.io.clients(0).tessellationInfoCancel.get(0).poke(true.B)
      d.io.clients(0).tessellationInfoCancel.get(1).poke(true.B)
      pool.tessellationInfoCancel.get(0).expect(false.B)
      pool.tessellationInfoCancel.get(1).expect(true.B)
      pool.owned.poke(0.U);pool.tessellationInfoCancel.get.foreach(_.expect(false.B))
      for(slot<-Seq(1,0)) {
        pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(slot.U)
        pool.completion.bits.success.poke(true.B)
        d.io.clients(slot).completion.bits.commandId.expect(0x55.U)
        d.clock.step();pool.completion.valid.poke(false.B)
      }
      d.io.drained.expect(true.B)
    }
  }
}
