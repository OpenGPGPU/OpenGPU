package opengpu.dispatch

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AtomicCounterGridDispatchSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  it should "retain counter task identity across held child launches and retirements" in {
    simulate(new ComputeGridDistributor(GpuConfig(lanes=4,warps=2),2,8,atomicCounterAvailable=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      val task=d.io.launch.bits.atomicCounter.get
      task.enabled.poke(true.B);task.table.version.poke(1.U);task.table.base.poke(0x8000.U);task.table.limit.poke(0x8040.U)
      task.table.identity.context.poke(7.U);task.table.identity.pass.poke(11.U);task.table.identity.generation.poke(13.U)
      d.io.launch.bits.commandId.poke(42.U)
      for(i<-0 until 3){d.io.launch.bits.launch.gridSize(i).poke((if(i==0)2 else 1).U);d.io.launch.bits.launch.localSize(i).poke(1.U)}
      d.io.launch.valid.poke(true.B);d.io.launch.ready.expect(true.B);d.clock.step();d.io.launch.valid.poke(false.B)
      zero(task)
      val tags=collection.mutable.ArrayBuffer.empty[BigInt]
      for(group<-0 until 2){
        until(d.io.childLaunch.valid.peek().litToBoolean)
        val held=d.io.childLaunch.bits.peek().litValue
        d.io.childGroup.groupId(0).expect(group.U)
        val child=d.io.childLaunch.bits.atomicCounter.get
        child.enabled.expect(true.B);child.table.base.expect(0x8000.U);child.table.limit.expect(0x8040.U)
        child.table.identity.context.expect(7.U);child.table.identity.pass.expect(11.U);child.table.identity.generation.expect(13.U)
        for(_<-0 until 4){assert(d.io.childLaunch.bits.peek().litValue==held);d.clock.step()}
        tags+=d.io.childLaunch.bits.commandId.peek().litValue
        d.io.childLaunch.ready.poke(true.B);d.clock.step();d.io.childLaunch.ready.poke(false.B)
      }
      assert(tags.distinct.size==2)
      for(tag<-tags.reverse){d.io.childCompletion.bits.commandId.poke(tag.U);d.io.childCompletion.bits.success.poke(true.B)
        d.io.childCompletion.valid.poke(true.B);d.io.childCompletion.ready.expect(true.B);d.clock.step();d.io.childCompletion.valid.poke(false.B)}
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.commandId.expect(42.U);d.io.completion.bits.success.expect(true.B)
    }
  }
}
