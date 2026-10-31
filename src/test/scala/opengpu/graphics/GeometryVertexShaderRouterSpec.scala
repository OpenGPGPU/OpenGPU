package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.GeometryVertexShaderRouter
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryVertexShaderRouterSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "route simultaneous subtree tasks and vertex work with independent returned tags" in {
    simulate(new GeometryVertexShaderRouter(GpuConfig(lanes=4,warps=2),3,4,2)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val g=d.io.geometry;val v=d.io.vertex;val pool=d.io.pool;g.completion.ready.poke(true.B);v.completion.ready.poke(true.B)
      g.geometryTask.get.valid.poke(true.B);g.geometryTask.get.bits.taskId.poke(19.U)
      g.launch.valid.poke(true.B);g.launch.bits.commandId.poke(3.U);g.launch.ready.expect(true.B);d.clock.step()
      g.geometryTask.get.valid.poke(false.B);g.launch.bits.commandId.poke(4.U);g.launch.ready.expect(true.B);d.clock.step();g.launch.valid.poke(false.B)
      pool.launch.ready.poke(true.B);pool.launch.valid.expect(true.B);pool.launch.bits.commandId.expect(0.U);pool.geometryTask.get.valid.expect(true.B)
      v.launch.valid.poke(true.B);v.launch.bits.commandId.poke(9.U);v.launch.ready.expect(true.B);d.clock.step();v.launch.valid.poke(false.B)
      pool.launch.bits.commandId.expect(1.U);pool.geometryTask.get.valid.expect(false.B);d.clock.step()
      pool.launch.bits.commandId.expect(2.U);pool.geometryTask.get.valid.expect(false.B);d.clock.step()
      pool.owned.poke(7.U);for(i<-0 until 3)pool.taskIds(i).poke(i.U)
      g.owned.expect(3.U);v.owned.expect(4.U);g.taskIds(0).expect(3.U);g.taskIds(1).expect(4.U);v.taskIds(2).expect(9.U)
      pool.owned.poke(0.U)
      for((tag,local)<-Seq((1,4),(2,9),(0,3))){pool.completion.valid.poke(true.B);pool.completion.bits.commandId.poke(tag.U)
        if(tag==2){v.completion.valid.expect(true.B);v.completion.bits.commandId.expect(local.U)}
        else{g.completion.valid.expect(true.B);g.completion.bits.commandId.expect(local.U)}
        d.clock.step()}
      pool.completion.valid.poke(false.B);d.io.drained.expect(true.B)
    }
  }
}
