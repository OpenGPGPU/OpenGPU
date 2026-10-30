package opengpu.dispatch

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.command.{GpuCommandOpcode, GpuCommandRouter}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AtomicCounterCommandOwnershipSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  it should "hold the counter task and lease through the kernel command processor" in {
    simulate(new GpuCommandProcessor(GpuConfig(lanes=4,warps=2),commandIdWidth=4,atomicCounterAvailable=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      val cmd=d.io.command.bits;cmd.commandId.poke(3.U);cmd.launch.kernelPc.poke(0x1000.U)
      for(i<-0 until 3){cmd.launch.gridSize(i).poke(1.U);cmd.launch.localSize(i).poke(1.U)}
      val t=cmd.atomicCounter.get;t.enabled.poke(true.B);t.table.version.poke(1.U);t.table.base.poke(0x8000.U);t.table.limit.poke(0x8040.U)
      t.table.identity.context.poke(7.U);t.table.identity.pass.poke(11.U);t.table.identity.generation.poke(13.U)
      d.io.command.valid.poke(true.B);d.io.command.ready.expect(true.B);d.clock.step();d.io.command.valid.poke(false.B);zero(cmd)
      d.io.atomicCounterLease.get.expect(true.B);until(d.io.dispatch.valid.peek().litToBoolean)
      val held=d.io.dispatch.bits.peek().litValue
      for(_<-0 until 5){assert(d.io.dispatch.bits.peek().litValue==held);d.io.dispatch.bits.atomicCounter.get.table.base.expect(0x8000.U);d.clock.step()}
      d.io.dispatch.ready.poke(true.B);d.clock.step();d.io.dispatch.ready.poke(false.B)
      d.io.dispatchCompletion.bits.commandId.poke(3.U);d.io.dispatchCompletion.bits.success.poke(false.B)
      d.io.dispatchCompletion.valid.poke(true.B);d.io.dispatchCompletion.ready.expect(true.B);d.clock.step();d.io.dispatchCompletion.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.success.expect(false.B)
      for(_<-0 until 4){d.io.atomicCounterLease.get.expect(true.B);d.clock.step()}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.atomicCounterLease.get.expect(false.B)
    }
  }
  it should "retain a direct counter command through the unified router and public completion" in {
    simulate(new GpuCommandRouter(GpuConfig(lanes=4,warps=2),commandIdWidth=4,enableIndirectCompute=true,atomicCounterAvailable=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean):Unit={var n=0;while(!p && n<100){d.clock.step();n+=1};assert(p)}
      val cmd=d.io.command.bits;cmd.commandId.poke(5.U);cmd.opcode.poke(GpuCommandOpcode.kernel)
      val t=cmd.atomicCounter.get;t.enabled.poke(true.B);t.table.version.poke(1.U);t.table.base.poke(0x8000.U);t.table.limit.poke(0x8040.U)
      t.table.identity.context.poke(7.U);t.table.identity.pass.poke(11.U);t.table.identity.generation.poke(13.U)
      d.io.command.valid.poke(true.B);d.io.command.ready.expect(true.B);d.clock.step();d.io.command.valid.poke(false.B);zero(cmd)
      d.io.atomicCounterLease.get.expect(true.B);until(d.io.kernel.valid.peek().litToBoolean)
      val held=d.io.kernel.bits.peek().litValue
      for(_<-0 until 5){assert(d.io.kernel.bits.peek().litValue==held);d.io.kernel.bits.atomicCounter.get.table.identity.generation.expect(13.U);d.clock.step()}
      d.io.kernel.ready.poke(true.B);d.clock.step();d.io.kernel.ready.poke(false.B)
      d.io.kernelCompletion.bits.commandId.poke(5.U);d.io.kernelCompletion.bits.success.poke(true.B)
      d.io.kernelCompletion.valid.poke(true.B);d.io.kernelCompletion.ready.expect(true.B);d.clock.step();d.io.kernelCompletion.valid.poke(false.B)
      until(d.io.completion.valid.peek().litToBoolean);d.io.completion.bits.success.expect(true.B)
      for(_<-0 until 4){d.io.atomicCounterLease.get.expect(true.B);d.clock.step()}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.atomicCounterLease.get.expect(false.B)
    }
  }
}
