package opengpu.core.frontend.simt

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SimtControlFlowFaultSpec extends AnyFlatSpec {
  for(depth <- Seq(1,3,8))
  it should s"fault atomically at exact capacity and preserve peer stack ownership depth=$depth" in {
    val config=if(depth==8)GpuConfig()else GpuConfig(lanes=4,warps=2,simtStackDepth=depth)
    simulate(new SimtControlFlow(config)) { d =>
      d.io.branch.valid.poke(false.B);d.io.branch.bits.poke(0.U.asTypeOf(d.io.branch.bits))
      d.io.current.ready.poke(false.B);d.io.overflow.ready.poke(false.B)
      d.io.restore.ready.poke(false.B);d.io.restoreWarpId.poke(0.U)
      d.io.clear.valid.poke(false.B);d.io.clear.bits.poke(0.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def until(p: => Boolean): Unit={var n=0;while(!p && n<40){d.clock.step();n+=1};assert(p)}
      def branch(warp: Int,pc: Int,active: BigInt,taken: BigInt): Unit={
        d.io.branch.bits.warpId.poke(warp.U);d.io.branch.bits.pc.poke(pc.U)
        d.io.branch.bits.targetPc.poke((pc+0x40).U);d.io.branch.bits.fallthroughPc.poke((pc+4).U)
        d.io.branch.bits.reconvergePc.poke((pc+0x80).U)
        d.io.branch.bits.activeMask.poke(active.U);d.io.branch.bits.takenMask.poke(taken.U)
        d.io.branch.valid.poke(true.B);until(d.io.branch.ready.peek().litToBoolean)
        d.clock.step();d.io.branch.valid.poke(false.B)
        d.io.branch.bits.poke(0.U.asTypeOf(d.io.branch.bits))
      }
      def current(warp: Int,pc: Int,mask: BigInt): Unit={
        until(d.io.current.valid.peek().litToBoolean)
        d.io.current.bits.warpId.expect(warp.U);d.io.current.bits.pc.expect(pc.U)
        d.io.current.bits.activeMask.expect(mask.U)
        val held=d.io.current.bits.peek().litValue
        for(_<-0 until 3){d.io.current.valid.expect(true.B);assert(d.io.current.bits.peek().litValue==held);d.clock.step()}
        d.io.current.ready.poke(true.B);d.clock.step();d.io.current.ready.poke(false.B)
      }
      val all=(BigInt(1)<<config.lanes)-1
      if(depth>=2){branch(1,0x400,all,all>>1);current(1,0x440,all>>1)}
      var active=all
      for(level<-0 until depth/2){
        val pc=0x100+level*0x100;branch(0,pc,active,active>>1)
        active=active>>1;current(0,pc+0x40,active)
        d.io.stackCounts(0).expect(((level+1)*2).U)
      }
      val occupied=(depth/2)*2
      // Uniform branches require zero slots even when the stack is full.
      branch(0,0x600,active,active);current(0,0x640,active)
      d.io.stackCounts(0).expect(occupied.U)
      branch(0,0x700,active,active>>1)
      until(d.io.overflow.valid.peek().litToBoolean)
      d.io.overflow.bits.warpId.expect(0.U);d.io.overflow.bits.pc.expect(0x700.U)
      d.io.overflow.bits.activeMask.expect(active.U)
      val held=d.io.overflow.bits.peek().litValue
      for(_<-0 until 11){
        d.io.overflow.valid.expect(true.B);assert(d.io.overflow.bits.peek().litValue==held)
        d.io.current.valid.expect(false.B);d.io.stackCounts(0).expect(occupied.U)
        d.io.stackCounts(1).expect((if(depth>=2)2 else 0).U);d.clock.step()
      }
      d.io.overflow.ready.poke(true.B);d.clock.step();d.io.overflow.ready.poke(false.B)
      d.io.stackCounts(0).expect(occupied.U)
      d.io.clear.valid.poke(true.B);d.io.clear.bits.poke(0.U);d.clock.step();d.io.clear.valid.poke(false.B)
      d.io.stackCounts(0).expect(0.U)
      if(depth>=2){
        d.io.restoreWarpId.poke(1.U);d.io.restore.ready.poke(true.B)
        d.io.restore.valid.expect(true.B);d.io.restore.bits.pc.expect(0x404.U)
        d.io.restore.bits.activeMask.expect((all ^ (all>>1)).U);d.clock.step()
        d.io.restore.valid.expect(true.B);d.io.restore.bits.pc.expect(0x480.U)
        d.io.restore.bits.activeMask.expect(all.U);d.clock.step()
        d.io.restore.valid.expect(false.B);d.io.restore.ready.poke(false.B)
      }
      branch(0,0x900,all,all);current(0,0x940,all)
      d.io.stackCounts(0).expect(0.U);d.io.overflow.valid.expect(false.B)
      info(s"SIMT_CAPACITY depth=$depth lanes=${config.lanes} warps=${config.warps} exactTwoSlotAdmission=true oddOrOneCapacity=true uniformAtCapacity=true originalPCMaskHeld=true noPartialPush=true peerLIFOIntact=true clearAndReuse=true")
    }
  }

  for(stage<-0 until 3)
  it should s"discard only a cleared warp's pending resolver and partial stack sequence stage=$stage" in {
    simulate(new SimtControlFlow(GpuConfig(lanes=4,warps=2,simtStackDepth=4))) { d =>
      d.io.branch.valid.poke(false.B);d.io.branch.bits.poke(0.U.asTypeOf(d.io.branch.bits))
      d.io.current.ready.poke(false.B);d.io.overflow.ready.poke(false.B)
      d.io.restore.ready.poke(false.B);d.io.restoreWarpId.poke(0.U)
      d.io.clear.valid.poke(false.B);d.io.clear.bits.poke(0.U)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def send(pc: Int): Unit={
        d.io.branch.bits.warpId.poke(0.U);d.io.branch.bits.pc.poke(pc.U)
        d.io.branch.bits.targetPc.poke((pc+0x40).U);d.io.branch.bits.fallthroughPc.poke((pc+4).U)
        d.io.branch.bits.reconvergePc.poke((pc+0x80).U)
        d.io.branch.bits.activeMask.poke(15.U);d.io.branch.bits.takenMask.poke(3.U)
        d.io.branch.valid.poke(true.B);d.io.branch.ready.expect(true.B)
        d.clock.step();d.io.branch.valid.poke(false.B)
      }
      send(0x100);if(stage>0)d.clock.step(stage)
      d.io.clear.valid.poke(true.B);d.clock.step();d.io.clear.valid.poke(false.B)
      for(_<-0 until 7){d.io.stackCounts(0).expect(0.U);d.io.current.valid.expect(false.B);d.io.overflow.valid.expect(false.B);d.clock.step()}
      send(0x800)
      var n=0;while(!d.io.current.valid.peek().litToBoolean && n<12){d.clock.step();n+=1}
      d.io.current.valid.expect(true.B);d.io.current.bits.pc.expect(0x840.U)
      d.io.stackCounts(0).expect(2.U);d.io.stackCounts(1).expect(0.U)
      d.io.current.ready.poke(true.B);d.clock.step();d.io.current.ready.poke(false.B)
      d.io.restore.ready.poke(true.B);d.io.restore.bits.pc.expect(0x804.U);d.clock.step()
      d.io.restore.bits.pc.expect(0x880.U);d.clock.step();d.io.restore.valid.expect(false.B)
      info(s"SIMT_CLEAR stage=$stage resolverOrSequencerCancelled=true noGhostPush=true noStaleResume=true healthyLIFO=true")
    }
  }
}
