package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedShaderTaskRouterSmallSpec extends AnyFlatSpec {
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
      port.imageTask.foreach(p => p.poke(0.U.asTypeOf(p)))
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

  it should "retain image publication and stage through queued launches and slot reuse" in {
    simulate(new SharedShaderTaskRouter(GpuConfig(lanes=4,warps=2),2,2,tasksPerClient=2,imageAvailable=true)){d=>
      initialize(d);val pool=d.io.pool;pool.launch.ready.poke(false.B)
      def offer(client:Int,stage:Int,base:Int,enabled:Boolean):Unit={
        val p=d.io.clients(client);val image=p.imageTask.get
        image.enabled.poke(enabled.B);image.stage.poke(stage.U);image.table.version.poke(1.U)
        image.table.base.poke(base.U);image.table.limit.poke((base+256).U)
        image.table.identity.context.poke((base+1).U);image.table.identity.pass.poke((base+2).U)
        image.table.identity.generation.poke((base+3).U)
        p.launch.bits.commandId.poke(0x55.U);p.launch.valid.poke(true.B)
        p.launch.ready.expect(true.B);d.clock.step();p.launch.valid.poke(false.B)
        image.poke(0.U.asTypeOf(image))
      }
      def expectTask(stage:Int,base:Int,enabled:Boolean):Unit={val image=pool.imageTask.get
        image.enabled.expect(enabled.B);image.stage.expect(stage.U);image.table.version.expect(1.U)
        image.table.base.expect(base.U);image.table.limit.expect((base+256).U)
        image.table.identity.context.expect((base+1).U);image.table.identity.pass.expect((base+2).U)
        image.table.identity.generation.expect((base+3).U)
      }
      offer(0,GraphicsStage.vertex,0x1000,true);offer(1,GraphicsStage.fragment,0x2000,true)
      for(_<-0 until 9){pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(0.U);expectTask(GraphicsStage.vertex,0x1000,true);d.clock.step()}
      pool.launch.ready.poke(true.B);d.clock.step()
      pool.launch.bits.commandId.expect(2.U);expectTask(GraphicsStage.fragment,0x2000,true);d.clock.step()
      for(tag<-Seq(2,0)){pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(tag.U);d.clock.step()}
      pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
      pool.launch.ready.poke(false.B);offer(0,GraphicsStage.geometry,0x3000,false)
      for(_<-0 until 5){expectTask(GraphicsStage.geometry,0x3000,false);d.clock.step()}
      pool.launch.ready.poke(true.B);d.clock.step();pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(0.U)
      d.clock.step();pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
      pool.imageTask.get.enabled.expect(false.B)
    }
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

  for (count <- Seq(2)) {
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

}
