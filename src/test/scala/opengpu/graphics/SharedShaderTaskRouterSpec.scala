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
      port.nativeResources.poke(false.B);port.uniformResources.poke(false.B)
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

  it should "retain provider permission through queued launches and task-slot reuse" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes=4,warps=2),2,2,tasksPerClient=2)){d=>
      initialize(d);val pool=d.io.pool;pool.launch.ready.poke(false.B)
      def offer(client: Int,native: Boolean,uniform: Boolean): Unit={val p=d.io.clients(client)
        p.nativeResources.poke(native.B);p.uniformResources.poke(uniform.B);p.launch.bits.commandId.poke(0x55.U);p.launch.valid.poke(true.B)
        p.launch.ready.expect(true.B);d.clock.step();p.launch.valid.poke(false.B);p.nativeResources.poke((!native).B);p.uniformResources.poke((!uniform).B)
      }
      offer(0,true,false);offer(1,false,true)
      for(_<-0 until 9){pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(0.U);pool.nativeResources.expect(true.B);pool.uniformResources.expect(false.B);d.clock.step()}
      pool.launch.ready.poke(true.B);d.clock.step()
      pool.launch.bits.commandId.expect(2.U);pool.nativeResources.expect(false.B);pool.uniformResources.expect(true.B);d.clock.step()
      for(tag<-Seq(2,0)){pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(tag.U)
        d.io.clients(if(tag==2)1 else 0).completion.bits.commandId.expect(0x55.U);d.clock.step()}
      pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
      pool.launch.ready.poke(false.B);offer(0,false,false);pool.launch.valid.expect(true.B);pool.nativeResources.expect(false.B)
      for(_<-0 until 5){d.io.clients(0).nativeResources.poke(true.B);d.io.clients(0).uniformResources.poke(true.B);d.clock.step();pool.nativeResources.expect(false.B);pool.uniformResources.expect(false.B)}
      pool.launch.ready.poke(true.B);d.clock.step();pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(0.U)
      d.clock.step();pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
    }
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
          dut.clock.step(); port.launch.valid.poke(false.B)
          pool.launch.valid.expect(true.B); pool.launch.bits.commandId.expect(client.U)
          dut.clock.step()
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
      pool.invalidate.valid.expect(true.B); dut.clock.step()
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
      dut.io.clients(0).invalidate.ready.expect(true.B)
      dut.clock.step(); dut.io.clients(0).invalidate.valid.poke(false.B)
      pool.invalidate.valid.expect(true.B)
      pool.invalidate.bits.lineAddress.expect(0x9000.U)
      dut.clock.step()
      pool.invalidateDone.bits.lineAddress.poke(0x9000.U)
      pool.invalidateDone.valid.poke(true.B)
      dut.io.clients(0).invalidateDone.valid.expect(true.B)
      dut.clock.step(); pool.invalidateDone.valid.poke(false.B)
      dut.io.drained.expect(true.B)
    }
  }

  it should "retain multiple live local tags per tile engine and restore reordered faults and completions" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes=4,warps=2),4,2,tasksPerClient=3)) { d =>
      initialize(d)
      def launch(client: Int,local: Int,slot: Int): Unit = {
        val c=d.io.clients(client)
        c.launch.bits.commandId.poke(local.U); c.launch.valid.poke(true.B)
        c.launch.ready.expect(true.B)
        d.clock.step(); c.launch.valid.poke(false.B)
        d.io.pool.launch.valid.expect(true.B); d.io.pool.launch.bits.commandId.expect(slot.U)
        d.clock.step()
      }
      def complete(slot: Int,client: Int,local: Int,success: Boolean=true): Unit = {
        d.io.pool.completion.valid.poke(true.B)
        d.io.pool.completion.bits.commandId.poke(slot.U)
        d.io.pool.completion.bits.success.poke(success.B)
        d.io.clients(client).completion.valid.expect(true.B)
        d.io.clients(client).completion.bits.commandId.expect(local.U)
        d.io.clients(client).completion.bits.success.expect(success.B)
        d.clock.step(); d.io.pool.completion.valid.poke(false.B)
      }
      launch(0,0x55,0); launch(0,0x66,1); launch(1,0x55,3)
      d.io.pool.owned.poke(7.U)
      d.io.pool.taskIds(0).poke(1.U); d.io.pool.taskIds(1).poke(3.U); d.io.pool.taskIds(2).poke(0.U)
      d.io.clients(0).owned.expect(5.U); d.io.clients(1).owned.expect(2.U)
      d.io.clients(0).taskIds(0).expect(0x66.U); d.io.clients(0).taskIds(2).expect(0x55.U)
      d.io.clients(1).taskIds(1).expect(0x55.U)
      d.io.clients(0).launch.bits.commandId.poke(0x55.U); d.io.clients(0).launch.valid.poke(true.B)
      d.io.clients(0).launch.ready.expect(false.B)
      d.clock.step(); d.io.clients(0).launch.valid.poke(false.B)
      d.io.pool.owned.poke(0.U)
      d.io.clients(0).completion.ready.poke(false.B)
      d.io.pool.completion.valid.poke(true.B); d.io.pool.completion.bits.commandId.poke(1.U)
      for (_ <- 0 until 4) {
        d.io.clients(0).completion.bits.commandId.expect(0x66.U)
        d.io.pool.completion.ready.expect(false.B); d.clock.step()
      }
      d.io.clients(0).completion.ready.poke(true.B); d.clock.step()
      d.io.pool.completion.valid.poke(false.B)
      launch(0,0x77,1); launch(0,0x88,2)
      d.io.clients(0).launch.bits.commandId.poke(0x99.U); d.io.clients(0).launch.valid.poke(true.B)
      d.io.clients(0).launch.ready.expect(false.B); d.clock.step(); d.io.clients(0).launch.valid.poke(false.B)
      d.io.pool.trap.valid.poke(true.B); d.io.pool.trap.bits.taskId.poke(2.U)
      d.io.clients(0).trap.valid.expect(true.B); d.io.clients(0).trap.bits.taskId.expect(0x88.U)
      d.io.clients(1).trap.valid.expect(false.B)
      d.clock.step(); d.io.pool.trap.valid.poke(false.B)
      d.io.drained.expect(false.B)
      complete(2,0,0x88,success=false); complete(0,0,0x55)
      complete(3,1,0x55); complete(1,0,0x77)
      d.io.drained.expect(true.B)
    }
  }

  it should "freeze an offered invalidation and its owner when another client arrives" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes=4,warps=2),2,3)) { d =>
      initialize(d)
      val pool=d.io.pool
      // Establish a known arbitration rotation before stalling the next
      // offer, independent of uninitialized round-robin pointer values.
      val seed=d.io.clients(0)
      seed.invalidate.valid.poke(true.B); seed.invalidate.bits.lineAddress.poke(0x7000.U)
      val issued=pool.invalidate.valid.peek().litToBoolean
      seed.invalidate.ready.expect(true.B); d.clock.step(); seed.invalidate.valid.poke(false.B)
      if (!issued) { pool.invalidate.valid.expect(true.B); d.clock.step() }
      pool.invalidateDone.valid.poke(true.B); pool.invalidateDone.bits.lineAddress.poke(0x7000.U)
      d.clock.step(); pool.invalidateDone.valid.poke(false.B)
      pool.invalidate.ready.poke(false.B)
      val first=d.io.clients(2); val second=d.io.clients(1)
      first.invalidate.valid.poke(true.B); first.invalidate.bits.lineAddress.poke(0x8000.U)
      val accepted=first.invalidate.ready.peek().litToBoolean
      d.clock.step()
      if (accepted) first.invalidate.valid.poke(false.B)
      pool.invalidate.valid.expect(true.B); pool.invalidate.bits.lineAddress.expect(0x8000.U)
      second.invalidate.valid.poke(true.B); second.invalidate.bits.lineAddress.poke(0x9000.U)
      for (_ <- 0 until 5) {
        pool.invalidate.valid.expect(true.B); pool.invalidate.bits.lineAddress.expect(0x8000.U)
        second.invalidate.ready.expect(false.B); d.io.drained.expect(false.B); d.clock.step()
      }
      pool.invalidate.ready.poke(true.B); d.clock.step(); first.invalidate.valid.poke(false.B)
      pool.invalidateDone.valid.poke(true.B); pool.invalidateDone.bits.lineAddress.poke(0x8000.U)
      first.invalidateDone.valid.expect(true.B); second.invalidateDone.valid.expect(false.B)
      d.clock.step(); pool.invalidateDone.valid.poke(false.B)
      second.invalidate.ready.expect(true.B)
      if (!pool.invalidate.valid.peek().litToBoolean) d.clock.step()
      pool.invalidate.valid.expect(true.B); pool.invalidate.bits.lineAddress.expect(0x9000.U)
      d.clock.step(); second.invalidate.valid.poke(false.B)
      pool.invalidateDone.valid.poke(true.B); pool.invalidateDone.bits.lineAddress.poke(0x9000.U)
      second.invalidateDone.valid.expect(true.B); first.invalidateDone.valid.expect(false.B)
      d.clock.step(); pool.invalidateDone.valid.poke(false.B); d.io.drained.expect(true.B)
    }
  }

  it should "hold an offered pool launch when a completion frees an earlier task slot" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes=4,warps=2),2,1,tasksPerClient=3)) { d =>
      initialize(d)
      val c=d.io.clients(0)
      c.launch.bits.commandId.poke(0x55.U); c.launch.valid.poke(true.B)
      d.clock.step(); c.launch.valid.poke(false.B)
      d.io.pool.launch.bits.commandId.expect(0.U); d.clock.step()
      d.io.pool.launch.ready.poke(false.B)
      c.launch.bits.commandId.poke(0x66.U); c.launch.valid.poke(true.B)
      d.clock.step(); c.launch.valid.poke(false.B)
      d.io.pool.launch.valid.expect(true.B); d.io.pool.launch.bits.commandId.expect(1.U)
      val held=d.io.pool.launch.bits.peek().litValue
      d.io.pool.completion.valid.poke(true.B); d.io.pool.completion.bits.commandId.poke(0.U)
      d.clock.step(); d.io.pool.completion.valid.poke(false.B)
      c.launch.bits.commandId.poke(0x77.U); c.launch.valid.poke(true.B)
      d.clock.step(); c.launch.valid.poke(false.B)
      for (_ <- 0 until 5) {
        d.io.pool.launch.valid.expect(true.B); assert(d.io.pool.launch.bits.peek().litValue==held)
        d.clock.step()
      }
      d.io.pool.launch.ready.poke(true.B); d.clock.step()
      d.io.pool.launch.valid.expect(true.B); d.io.pool.launch.bits.commandId.expect(0.U)
      d.clock.step(); d.io.pool.launch.valid.expect(false.B)
      for ((slot,local) <- Seq((1,0x66),(0,0x77))) {
        d.io.pool.completion.valid.poke(true.B); d.io.pool.completion.bits.commandId.poke(slot.U)
        c.completion.bits.commandId.expect(local.U); d.clock.step()
        d.io.pool.completion.valid.poke(false.B)
      }
      d.io.drained.expect(true.B)
    }
  }
}
