package opengpu.graphics
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.trap.TrapCause
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationCuBarrierSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "retain per-warp paths across park, resume, faults and terminal drain" in {
    simulate(new TessellationCuBarrier(GpuConfig(lanes=4,warps=2))){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: =>Boolean):Unit={var n=0;while(!p && n<50){d.clock.step();n+=1};assert(p)}
      def arrive(w:Int,pc:Int):Unit={d.io.in.bits.warpId.poke(w.U);d.io.in.bits.pc.poke(pc.U)
        d.io.in.bits.activeMask.poke(15.U);d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B)
        d.clock.step();d.io.in.valid.poke(false.B)}
      def park(w:Int,pc:Int):Unit={until(d.io.park.valid.peek().litToBoolean);d.io.park.bits.warp.expect(w.U)
        d.io.park.bits.base.expect((w*4).U);d.io.park.bits.barrierId.expect(pc.U);d.io.park.bits.lanes.expect(15.U)
        val held=d.io.park.bits.peek().litValue;d.clock.step(3);assert(d.io.park.bits.peek().litValue==held)
        d.io.park.ready.poke(true.B);d.clock.step();d.io.park.ready.poke(false.B)}
      def reply(w:Int,pc:Int,status:Int=0):Unit={d.io.resume.bits.warp.poke(w.U);d.io.resume.bits.barrierId.poke(pc.U)
        d.io.resume.bits.status.poke(status.U);d.io.resume.valid.poke(true.B);d.io.resume.ready.expect(true.B)
        d.clock.step();d.io.resume.valid.poke(false.B)}
      def take(w:Int,pc:Int):Unit={until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.warpId.expect(w.U)
        d.io.out.bits.pc.expect(pc.U);d.io.out.bits.activeMask.expect(15.U);val held=d.io.out.bits.peek().litValue
        d.clock.step(3);assert(d.io.out.bits.peek().litValue==held);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)}
      def trap(w:Int,pc:Int,cause:Int):Unit={until(d.io.trap.valid.peek().litToBoolean);d.io.trap.bits.warpId.expect(w.U)
        d.io.trap.bits.pc.expect((pc-4).U);d.io.trap.bits.cause.expect(cause.U);d.io.trap.bits.laneFaultMask.expect(15.U)
        d.io.trap.bits.tval.expect((if(cause==TrapCause.illegalInstruction) BigInt(11) else BigInt(pc-4)).U)
        val held=d.io.trap.bits.peek().litValue;d.clock.step(3);assert(d.io.trap.bits.peek().litValue==held)
        d.io.trap.ready.poke(true.B);d.clock.step();d.io.trap.ready.poke(false.B)}
      d.io.bases(0).poke(0.U);d.io.bases(1).poke(4.U);arrive(0,0x104);arrive(1,0x104)
      d.clock.step(3);d.io.park.valid.expect(false.B);d.io.memoryIdle.poke(true.B)
      park(0,0x104);park(1,0x104);reply(1,0x104);reply(0,0x104);take(1,0x104);take(0,0x104)
      until(!d.io.busy.peek().litToBoolean)
      arrive(0,0x204);park(0,0x204);reply(0,0x208);trap(0,0x204,TrapCause.illegalInstruction)
      arrive(0,0x304);park(0,0x304);arrive(1,0x304);until(d.io.park.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);d.io.park.valid.expect(true.B)
      d.io.terminal.poke(true.B);d.clock.step();d.io.terminal.poke(false.B);reply(0,0x304,2)
      trap(1,0x304,TrapCause.shaderInterrupted);trap(0,0x304,TrapCause.shaderInterrupted)
      until(!d.io.busy.peek().litToBoolean);d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
      arrive(0,0x384);until(d.io.park.valid.peek().litToBoolean);d.io.park.ready.poke(true.B);d.io.terminal.poke(true.B)
      d.clock.step();d.io.park.ready.poke(false.B);d.io.terminal.poke(false.B);d.io.trap.valid.expect(false.B)
      reply(0,0x384);trap(0,0x384,TrapCause.shaderInterrupted)
      until(!d.io.busy.peek().litToBoolean);d.io.kernelStart.poke(true.B);d.clock.step();d.io.kernelStart.poke(false.B)
      arrive(1,0x404);park(1,0x404);reply(1,0x404);take(1,0x404)
    }
  }
}
