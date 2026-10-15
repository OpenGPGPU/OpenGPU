package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedShaderTaskRouterSpec extends AnyFlatSpec {
  behavior of "SharedShaderTaskRouter"

  private def initialize(dut: SharedShaderTaskRouter): Unit = {
    val pool = dut.io.pool
    pool.launch.ready.poke(true.B)
    pool.completion.valid.poke(false.B)
    pool.completion.bits.poke(0.U.asTypeOf(pool.completion.bits))
    pool.trap.valid.poke(false.B)
    pool.trap.bits.poke(0.U.asTypeOf(pool.trap.bits))
    pool.owned.poke(0.U)
    pool.taskIds.foreach(_.poke(0.U))
    pool.invalidate.ready.poke(true.B)
    pool.invalidateDone.valid.poke(false.B)
    pool.invalidateDone.bits.poke(0.U.asTypeOf(pool.invalidateDone.bits))
    pool.texture.foreach { tex =>
      tex.scalar.valid.poke(false.B)
      tex.scalar.bits.poke(0.U.asTypeOf(tex.scalar.bits))
      tex.vector.valid.poke(false.B)
      tex.vector.bits.poke(0.U.asTypeOf(tex.vector.bits))
      tex.scalarWriteback.ready.poke(true.B)
      tex.vectorWriteback.ready.poke(true.B)
    }
    dut.io.clients.foreach { port =>
      port.launch.valid.poke(false.B)
      port.launch.bits.poke(0.U.asTypeOf(port.launch.bits))
      port.completion.ready.poke(true.B)
      port.trap.ready.poke(true.B)
      port.invalidate.valid.poke(false.B)
      port.invalidate.bits.poke(0.U.asTypeOf(port.invalidate.bits))
      port.invalidateDone.ready.poke(true.B)
      port.texture.foreach { tex =>
        tex.scalar.ready.poke(false.B)
        tex.vector.ready.poke(false.B)
        tex.scalarWriteback.valid.poke(false.B)
        tex.scalarWriteback.bits.poke(0.U.asTypeOf(tex.scalarWriteback.bits))
        tex.vectorWriteback.valid.poke(false.B)
        tex.vectorWriteback.bits.poke(0.U.asTypeOf(tex.vectorWriteback.bits))
      }
    }
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
  }

  for (count <- Seq(2, 4)) {
    it should s"route textures, faults and out-of-order completions by task across $count CUs" in {
      simulate(new SharedShaderTaskRouter(GpuConfig(lanes = 4, warps = 2), count, 2)) { dut =>
        initialize(dut)
        val pool = dut.io.pool
        def launch(client: Int, localTag: Int): Unit = {
          val port = dut.io.clients(client)
          port.launch.bits.commandId.poke(localTag.U)
          port.launch.bits.launch.kernelPc.poke((0x1000 + client * 64).U)
          port.launch.valid.poke(true.B)
          port.launch.ready.expect(true.B)
          pool.launch.bits.commandId.expect(client.U)
          dut.clock.step(); port.launch.valid.poke(false.B)
        }
        launch(0, 0x55); launch(1, 0x55)
        // Client zero runs on the last CU, client one on CU zero. Neither
        // client number nor local task tag is a physical CU index.
        pool.owned.poke((BigInt(1).setBit(count - 1)).U)
        pool.taskIds(0).poke(1.U)
        pool.taskIds(count - 1).poke(0.U)
        dut.io.clients(0).owned.expect((BigInt(1) << (count - 1)).U)
        dut.io.clients(1).owned.expect(1.U)
        dut.io.clients(0).taskIds(count - 1).expect(0x55.U)
        dut.io.clients(1).taskIds(0).expect(0x55.U)

        pool.texture(0).scalar.valid.poke(true.B)
        dut.io.clients(1).texture(0).scalar.ready.poke(true.B)
        pool.texture(0).scalar.ready.expect(true.B)
        dut.io.clients(1).texture(0).scalar.valid.expect(true.B)
        dut.io.clients(0).texture(0).scalar.valid.expect(false.B)
        dut.io.clients(0).texture(0).scalarWriteback.valid.poke(true.B)
        dut.io.clients(0).texture(0).scalarWriteback.bits.data.poke(0xdead.U)
        dut.io.clients(1).texture(0).scalarWriteback.valid.poke(true.B)
        dut.io.clients(1).texture(0).scalarWriteback.bits.data.poke(0x1234.U)
        pool.texture(0).scalarWriteback.valid.expect(true.B)
        pool.texture(0).scalarWriteback.bits.data.expect(0x1234.U)
        dut.io.clients(0).texture(0).scalarWriteback.ready.expect(false.B)

        pool.texture(count - 1).vector.valid.poke(true.B)
        dut.io.clients(0).texture(count - 1).vector.ready.poke(true.B)
        dut.io.clients(0).texture(count - 1).vectorWriteback.valid.poke(true.B)
        dut.io.clients(0).texture(count - 1).vectorWriteback.bits.writeback.data(2).poke(0xabcd.U)
        pool.texture(count - 1).vector.ready.expect(true.B)
        dut.io.clients(0).texture(count - 1).vector.valid.expect(true.B)
        dut.io.clients(1).texture(count - 1).vector.valid.expect(false.B)
        pool.texture(count - 1).vectorWriteback.valid.expect(true.B)
        pool.texture(count - 1).vectorWriteback.bits.writeback.data(2).expect(0xabcd.U)
        dut.clock.step()
        pool.texture(0).scalar.valid.poke(false.B)
        pool.texture(count - 1).vector.valid.poke(false.B)
        dut.io.clients.foreach(_.texture.foreach { tex =>
          tex.scalarWriteback.valid.poke(false.B)
          tex.vectorWriteback.valid.poke(false.B)
        })

        pool.owned.poke((BigInt(1) << (count - 1)).U)
        pool.completion.valid.poke(true.B)
        pool.completion.bits.commandId.poke(1.U)
        pool.completion.bits.success.poke(false.B)
        dut.io.clients(1).completion.ready.poke(false.B)
        dut.io.clients(1).completion.bits.commandId.expect(0x55.U)
        dut.io.clients(1).completion.bits.success.expect(false.B)
        dut.io.clients(0).completion.valid.expect(false.B)
        pool.completion.ready.expect(false.B)
        dut.io.clients(1).launch.ready.expect(false.B)
        dut.clock.step(2)
        dut.io.clients(1).completion.ready.poke(true.B)
        dut.clock.step(); pool.completion.valid.poke(false.B)
        pool.owned.poke(0.U)
        pool.completion.bits.commandId.poke(0.U)
        pool.completion.bits.success.poke(true.B)
        pool.completion.valid.poke(true.B)
        dut.io.clients(0).completion.bits.commandId.expect(0x55.U)
        dut.clock.step(); pool.completion.valid.poke(false.B)
        dut.io.drained.expect(true.B)

        launch(1, 0x77)
        pool.owned.poke(1.U)
        pool.trap.valid.poke(true.B)
        pool.trap.bits.taskId.poke(1.U)
        pool.trap.bits.cu.poke(0.U)
        pool.trap.bits.event.pc.poke(0x1080.U)
        dut.io.clients(1).trap.bits.taskId.expect(0x77.U)
        dut.io.clients(1).trap.bits.event.pc.expect(0x1080.U)
        dut.io.clients(0).trap.valid.expect(false.B)
        dut.io.clients(1).trap.ready.poke(false.B)
        pool.trap.ready.expect(false.B)
        dut.clock.step()
        dut.io.clients(1).trap.ready.poke(true.B)
        dut.clock.step(); pool.trap.valid.poke(false.B)
        pool.owned.poke(0.U)
        pool.completion.bits.commandId.poke(1.U)
        pool.completion.bits.success.poke(false.B)
        pool.completion.valid.poke(true.B)
        dut.io.clients(1).completion.bits.commandId.expect(0x77.U)
        dut.clock.step(); pool.completion.valid.poke(false.B)
        dut.io.drained.expect(true.B)
      }
    }
  }

  it should "retain the invalidation requester through acknowledgement backpressure" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes = 4, warps = 2), 2, 3)) { dut =>
      initialize(dut)
      val pool = dut.io.pool
      dut.io.clients(2).invalidate.valid.poke(true.B)
      dut.io.clients(2).invalidate.bits.lineAddress.poke(0x8000.U)
      dut.io.clients(2).invalidate.ready.expect(true.B)
      dut.clock.step(); dut.io.clients(2).invalidate.valid.poke(false.B)
      dut.io.clients(0).invalidate.valid.poke(true.B)
      dut.io.clients(0).invalidate.bits.lineAddress.poke(0x9000.U)
      dut.io.clients(2).invalidateDone.ready.poke(false.B)
      pool.invalidateDone.valid.poke(true.B)
      pool.invalidateDone.bits.lineAddress.poke(0x8000.U)
      dut.io.clients(2).invalidateDone.valid.expect(true.B)
      dut.io.clients(2).invalidateDone.bits.lineAddress.expect(0x8000.U)
      dut.io.clients(0).invalidateDone.valid.expect(false.B)
      pool.invalidateDone.ready.expect(false.B)
      pool.invalidate.valid.expect(false.B)
      dut.io.drained.expect(false.B)
      dut.clock.step(3)
      dut.io.clients(2).invalidateDone.ready.poke(true.B)
      dut.clock.step(); pool.invalidateDone.valid.poke(false.B)
      pool.invalidate.valid.expect(true.B)
      pool.invalidate.bits.lineAddress.expect(0x9000.U)
      dut.clock.step(); dut.io.clients(0).invalidate.valid.poke(false.B)
      pool.invalidateDone.bits.lineAddress.poke(0x9000.U)
      pool.invalidateDone.valid.poke(true.B)
      dut.io.clients(0).invalidateDone.valid.expect(true.B)
      dut.clock.step(); pool.invalidateDone.valid.poke(false.B)
      dut.io.drained.expect(true.B)
    }
  }
}
