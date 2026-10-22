package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Exercises the existing external SIMT branch-result port. This is not an
  * instruction-driven vector-branch execution or native-host qualification.
  */
class GpuCoreSimtOverflowSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit=data match {
    case r: Record=>r.elements.values.foreach(zero)
    case v: Vec[_]=>v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "route external SIMT overflow faults without replacing a held peer event and clear only the retired stack" in {
    simulate(new GpuCore(GpuConfig(lanes=4,warps=2,simtStackDepth=2),vectorCacheSets=4,vectorCacheWays=1)) { d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean): Unit={var n=0;while(!p && n<60){d.clock.step();n+=1};assert(p)}
      def branch(warp: Int,pc: Int,active: Int,taken: Int): Unit={
        val b=d.io.simtBranch.bits;b.warpId.poke(warp.U);b.pc.poke(pc.U)
        b.targetPc.poke((pc+0x40).U);b.fallthroughPc.poke((pc+4).U);b.reconvergePc.poke((pc+0x80).U)
        b.activeMask.poke(active.U);b.takenMask.poke(taken.U)
        d.io.simtBranch.valid.poke(true.B);until(d.io.simtBranch.ready.peek().litToBoolean)
        d.clock.step();d.io.simtBranch.valid.poke(false.B);d.clock.step(6)
      }
      branch(0,0x100,15,7);branch(0,0x180,7,3)
      until(d.io.trap.valid.peek().litToBoolean)
      d.io.trap.bits.warpId.expect(0.U);d.io.trap.bits.pc.expect(0x180.U)
      d.io.trap.bits.cause.expect(26.U);d.io.trap.bits.tval.expect(0x180.U)
      d.io.trap.bits.activeMask.expect(7.U);d.io.trap.bits.laneFaultMask.expect(7.U)
      val held=d.io.trap.bits.peek().litValue
      branch(1,0x200,15,7);branch(1,0x280,7,3)
      for(_<-0 until 9){d.io.trap.valid.expect(true.B);assert(d.io.trap.bits.peek().litValue==held);d.clock.step()}
      d.io.trap.ready.poke(true.B);d.clock.step();d.io.trap.ready.poke(false.B)
      d.io.finish.valid.poke(true.B);d.io.finish.bits.poke(0.U);d.clock.step();d.io.finish.valid.poke(false.B)
      d.io.restore.bits.poke(0.U);d.io.restoreEmpty.expect(true.B)
      until(d.io.trap.valid.peek().litToBoolean)
      d.io.trap.bits.warpId.expect(1.U);d.io.trap.bits.pc.expect(0x280.U)
      d.io.trap.bits.cause.expect(26.U);d.io.trap.bits.activeMask.expect(7.U)
      d.io.restore.bits.poke(1.U);d.io.restoreEmpty.expect(false.B)
      d.io.trap.ready.poke(true.B);d.clock.step();d.io.trap.ready.poke(false.B)
      d.io.finish.valid.poke(true.B);d.io.finish.bits.poke(1.U);d.clock.step();d.io.finish.valid.poke(false.B)
      branch(0,0x900,15,7)
      d.io.restore.bits.poke(0.U);d.io.restoreEmpty.expect(false.B)
      for(_<-0 until 12){d.io.trap.valid.expect(false.B);d.clock.step()}
      info("SIMT_CORE_FAULT externalBranchPort=true originalPCMaskCause26=true peerFaultCannotReplaceHeldEvent=true perWarpFinishClear=true noDuplicateTrap=true healthyCapacityReuse=true instructionDrivenVectorBranch=false")
    }
  }
}
