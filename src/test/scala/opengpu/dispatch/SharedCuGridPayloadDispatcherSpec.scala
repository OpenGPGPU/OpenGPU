package opengpu.dispatch

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics.ShaderPayloadConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedCuGridPayloadDispatcherSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(count <- Seq(1,2,4))
  it should s"retain each payload binding through transport drain and separate producer retirement with $count CUs" in {
    val config=GpuConfig(lanes=4,warps=2)
    val p=ShaderPayloadConfig(owners=2,ports=2+count)
    simulate(new SharedCuGridDispatcher(config,count,8,Some(p))) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(cu <- 0 until count){d.io.cuLaunch(cu).ready.poke(true.B);d.io.cuCompletion(cu).bits.success.poke(true.B)}
      d.io.completion.ready.poke(false.B);d.io.graphicsCompletion.ready.poke(false.B)
      def until(condition: => Boolean): Unit={var n=0;while(!condition && n<100){d.clock.step();n+=1};assert(condition)}
      def launch(graphics: Boolean,id: Int,local: Boolean=true): Int={
        val port=if(graphics)d.io.graphicsLaunch else d.io.launch
        port.bits.commandId.poke(id.U);port.bits.launch.kernelPc.poke((0x1000+id*4).U)
        port.bits.launch.kernargAddress.poke(0x8000.U)
        for(i <- 0 until 3){port.bits.launch.gridSize(i).poke(1.U);port.bits.launch.localSize(i).poke(1.U)}
        val binding=d.io.graphicsPayload.get
        binding.valid.poke(local.B);binding.bits.handle.poke((id%p.slots).U)
        binding.bits.generation.poke((id+10).U);binding.bits.context.poke((id+20).U)
        binding.bits.owner.poke((id%2).U);binding.bits.base.poke(0x8000.U);binding.bits.bytes.poke(288.U)
        port.valid.poke(true.B);until(port.ready.peek().litToBoolean)
        val offered=d.io.payloadPins.get(count)
        offered.valid.expect((graphics && local).B)
        if(graphics && local){offered.bits.handle.expect((id%p.slots).U);offered.bits.generation.expect((id+10).U)}
        if (!graphics) { d.clock.step(); port.valid.poke(false.B); until(d.io.cuLaunch.map(_.valid.peek().litToBoolean).exists(identity)) }
        val cu=(0 until count).find(c=>d.io.cuLaunch(c).valid.peek().litToBoolean).get
        d.io.cuGridSlice(cu).singleGroup.expect((!graphics).B)
        if (!graphics) d.io.cuGridSlice(cu).groupId.foreach(_.expect(0.U))
        d.clock.step();port.valid.poke(false.B)
        d.io.taskIds(cu).expect(id.U)
        cu
      }
      val owners=Vector.tabulate(count)(i=>launch(true,7+i)->(7+i)).toMap
      d.io.graphicsPayload.get.valid.poke(false.B)
      d.io.graphicsPayload.get.bits.poke(0.U.asTypeOf(d.io.graphicsPayload.get.bits))
      def check(cu: Int): Unit={
        val id=owners(cu);val b=d.io.cuPayload.get(cu)
        b.valid.expect(true.B);b.bits.handle.expect((id%p.slots).U)
        b.bits.generation.expect((id+10).U);b.bits.context.expect((id+20).U)
        b.bits.owner.expect((id%2).U);b.bits.base.expect(0x8000.U);b.bits.bytes.expect(288.U)
      }
      def pinnedIds: Set[Int]=(0 until count).filter(i=>d.io.payloadPins.get(i).valid.peek().litToBoolean)
        .map(i=>d.io.payloadPins.get(i).bits.generation.peek().litValue.toInt-10).toSet
      assert(pinnedIds==(7 until 7+count).toSet)
      for(cu <- 0 until count)d.io.cuCompletion(cu).valid.poke(true.B)
      for(_ <- 0 until 10) {
        for(cu <- 0 until count){check(cu);d.io.cuCompletion(cu).ready.expect(false.B)}
        d.io.graphicsCompletion.valid.expect(false.B);d.clock.step()
      }
      d.io.cuPayloadDrained.get(0).poke(true.B);d.io.cuCompletion(0).ready.expect(true.B)
      d.clock.step();d.io.cuCompletion(0).valid.poke(false.B)
      d.io.cuPayload.get(0).valid.expect(false.B)
      val compute=launch(false,7)
      assert(compute==0)
      d.io.cuPayload.get(compute).valid.expect(false.B)
      for(cu <- 1 until count)check(cu)
      d.io.cuPayloadDrained.get(compute).poke(false.B)
      d.io.cuCompletion(compute).valid.poke(true.B)
      for(_ <- 0 until 5){d.io.cuCompletion(compute).ready.expect(false.B);d.io.completion.valid.expect(false.B);d.clock.step()}
      d.io.cuPayloadDrained.get(compute).poke(true.B);d.io.cuCompletion(compute).ready.expect(true.B)
      d.clock.step();d.io.cuCompletion(compute).valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.commandId.expect(7.U)
      val held=d.io.completion.bits.peek().litValue
      for(_ <- 0 until 5){d.clock.step();assert(d.io.completion.bits.peek().litValue==held)}
      d.io.completion.ready.poke(true.B);d.clock.step()
      for(cu <- 1 until count)d.io.cuPayloadDrained.get(cu).poke(true.B)
      var guard=0
      while(d.io.busy.peek().litValue!=0 && guard<100) {
        val accepted=(1 until count).filter(c=>d.io.cuCompletion(c).valid.peek().litToBoolean && d.io.cuCompletion(c).ready.peek().litToBoolean)
        d.clock.step();accepted.foreach(c=>d.io.cuCompletion(c).valid.poke(false.B));guard+=1
      }
      d.io.busy.expect(0.U)
      assert(pinnedIds==(7 until 7+count).toSet,"CU retirement or compute reuse released an unconsumed graphics payload")
      val seen=scala.collection.mutable.ArrayBuffer.empty[Int]
      d.io.graphicsCompletion.ready.poke(true.B)
      while(seen.size<count && guard<200) {
        if(d.io.graphicsCompletion.valid.peek().litToBoolean)seen+=d.io.graphicsCompletion.bits.commandId.peek().litValue.toInt
        d.clock.step();guard+=1
        assert(pinnedIds==(7 until 7+count).toSet--seen.toSet,"payload pins did not follow producer completion consumption")
      }
      assert(seen.sorted.toVector==(7 until 7+count).toVector)
      val fallback=launch(true,99,local=false)
      d.io.cuPayload.get(fallback).valid.expect(false.B)
      d.io.cuPayloadDrained.get(fallback).poke(true.B);d.io.cuCompletion(fallback).valid.poke(true.B)
      until(d.io.cuCompletion(fallback).ready.peek().litToBoolean)
      d.clock.step();d.io.cuCompletion(fallback).valid.poke(false.B)
      until(d.io.graphicsCompletion.valid.peek().litToBoolean)
      d.io.graphicsCompletion.bits.commandId.expect(99.U);d.clock.step()
      info(s"GRID_PAYLOAD_DISPATCH CUs=$count boundTasks=$count computeSameTag=7 drainHeld=true completionPins=true fallback=true")
    }
  }
}
