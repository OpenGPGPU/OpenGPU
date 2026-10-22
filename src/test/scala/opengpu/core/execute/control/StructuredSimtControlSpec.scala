package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.simt.SimtStackBank
import opengpu.core.trap.CoreTrapEvent
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class StructuredSimtHarness(config: GpuConfig) extends Module {
  private val countWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))
  val io = IO(new Bundle {
    val sync = Flipped(Decoupled(new SimtSyncRequest(config)))
    val branch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val restore = Flipped(Decoupled(UInt(config.warpIdWidth.W)))
    val current = Decoupled(new SimtPath(config))
    val fault = Decoupled(new CoreTrapEvent(config))
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val counts = Output(Vec(config.warps, UInt(countWidth.W)))
    val legacyEntry = Flipped(Decoupled(new opengpu.core.frontend.simt.SimtStackWrite(config)))
  })
  val control = Module(new StructuredSimtControl(config))
  val bank = Module(new SimtStackBank(config))
  control.io.sync <> io.sync; control.io.branch <> io.branch
  control.io.clear := io.clear; bank.io.clear := io.clear
  bank.io.popWarpId := Mux(control.io.busy, control.io.selectWarpId, io.restore.bits)
  control.io.top.valid := bank.io.pop.valid
  control.io.top.bits := bank.io.pop.bits
  control.io.freeEntries := bank.io.freeEntries(control.io.selectWarpId)
  val push = Module(new RRArbiter(new opengpu.core.frontend.simt.SimtStackWrite(config), 2))
  push.io.in(0) <> control.io.push
  push.io.in(1) <> io.legacyEntry
  bank.io.push <> push.io.out
  val restored = Wire(Decoupled(new SimtPath(config)))
  restored.valid := io.restore.valid && bank.io.pop.valid && !control.io.busy
  restored.bits.warpId := bank.io.pop.bits.warpId
  restored.bits.pc := bank.io.pop.bits.entry.pc
  restored.bits.activeMask := bank.io.pop.bits.entry.activeMask
  val paths = Module(new RRArbiter(new SimtPath(config), 2))
  paths.io.in(0) <> control.io.current; paths.io.in(1) <> restored
  io.current <> paths.io.out
  bank.io.pop.ready := restored.ready && io.restore.valid && !control.io.busy
  io.restore.ready := restored.ready && bank.io.pop.valid && !control.io.busy
  io.fault <> control.io.fault; io.counts := bank.io.counts
}

class StructuredSimtControlSpec extends AnyFlatSpec {
  private class Driver(val d: StructuredSimtHarness, val lanes: Int) {
    val all = (BigInt(1) << lanes) - 1
    d.io.sync.valid.poke(false.B); d.io.sync.bits.poke(0.U.asTypeOf(d.io.sync.bits))
    d.io.branch.valid.poke(false.B); d.io.branch.bits.poke(0.U.asTypeOf(d.io.branch.bits))
    d.io.restore.valid.poke(false.B); d.io.restore.bits.poke(0.U)
    d.io.current.ready.poke(false.B); d.io.fault.ready.poke(false.B)
    d.io.clear.valid.poke(false.B); d.io.clear.bits.poke(0.U)
    d.io.legacyEntry.valid.poke(false.B); d.io.legacyEntry.bits.poke(0.U.asTypeOf(d.io.legacyEntry.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(c: => Boolean): Unit = {
      var n = 0; while (!c && n < 30) { d.clock.step(); n += 1 }; assert(c)
    }
    def sync(pc: Int, join: Int, mask: BigInt = all, warp: Int = 0): Unit = {
      d.io.sync.bits.pc.poke(pc.U); d.io.sync.bits.joinPc.poke(join.U)
      d.io.sync.bits.activeMask.poke(mask.U); d.io.sync.bits.warpId.poke(warp.U)
      d.io.sync.valid.poke(true.B); until(d.io.sync.ready.peek().litToBoolean)
      d.clock.step(); d.io.sync.valid.poke(false.B)
    }
    def branch(pc: Int, target: Int, fall: Int, active: BigInt, taken: BigInt, warp: Int = 0): Unit = {
      d.io.branch.bits.pc.poke(pc.U); d.io.branch.bits.targetPc.poke(target.U)
      d.io.branch.bits.fallthroughPc.poke(fall.U); d.io.branch.bits.warpId.poke(warp.U)
      d.io.branch.bits.activeMask.poke(active.U); d.io.branch.bits.takenMask.poke(taken.U)
      d.io.branch.valid.poke(true.B); until(d.io.branch.ready.peek().litToBoolean)
      d.clock.step(); d.io.branch.valid.poke(false.B)
    }
    def path(pc: Int, mask: BigInt, warp: Int = 0): Unit = {
      until(d.io.current.valid.peek().litToBoolean)
      for (_ <- 0 until 4) {
        d.io.current.bits.pc.expect(pc.U); d.io.current.bits.activeMask.expect(mask.U)
        d.io.current.bits.warpId.expect(warp.U); d.clock.step()
      }
      d.io.current.ready.poke(true.B); d.clock.step(); d.io.current.ready.poke(false.B)
    }
    def join(pc: Int, mask: BigInt, warp: Int = 0): Unit = {
      d.io.restore.bits.poke(warp.U); d.io.restore.valid.poke(true.B)
      path(pc, mask, warp); d.io.restore.valid.poke(false.B)
    }
    def fault(cause: Int, pc: Int, mask: BigInt, tval: Int, warp: Int = 0): Unit = {
      until(d.io.fault.valid.peek().litToBoolean)
      for (_ <- 0 until 4) {
        d.io.fault.bits.cause.expect(cause.U); d.io.fault.bits.pc.expect(pc.U)
        d.io.fault.bits.activeMask.expect(mask.U); d.io.fault.bits.laneFaultMask.expect(mask.U)
        d.io.fault.bits.tval.expect(tval.U); d.io.fault.bits.warpId.expect(warp.U); d.clock.step()
      }
      d.io.fault.ready.poke(true.B); d.clock.step(); d.io.fault.ready.poke(false.B)
    }
  }
  for (lanes <- Seq(4, 16)) {
    it should s"restore both paths and nested scopes through one physical stack lanes=$lanes" in {
      simulate(new StructuredSimtHarness(GpuConfig(lanes = lanes, warps = 2))) { d =>
        val x = new Driver(d, lanes); val left = x.all >> 1; val right = x.all ^ left
        x.sync(0x1000, 0x1100); x.path(0x1004, x.all)
        x.branch(0x1004, 0x1080, 0x1008, x.all, left); x.path(0x1080, left)
        d.io.counts(0).expect(2.U)
        x.sync(0x1080, 0x10c0, left); x.path(0x1084, left)
        x.branch(0x1084, 0x10a0, 0x1088, left, 1); x.path(0x10a0, 1)
        d.io.counts(0).expect(4.U)
        x.join(0x1088, left ^ 1); x.join(0x10c4, left)
        d.io.counts(0).expect(2.U)
        x.join(0x1008, right); x.join(0x1104, x.all)
        d.io.counts(0).expect(0.U); d.io.counts(1).expect(0.U)
      }
    }
    it should s"park divergent loop exits for 256 iterations without stack growth lanes=$lanes" in {
      simulate(new StructuredSimtHarness(GpuConfig(lanes = lanes, warps = 2))) { d =>
        val x = new Driver(d, lanes)
        x.sync(0x2000, 0x2100); x.path(0x2004, x.all)
        var active = x.all
        for (i <- 0 until 256) {
          // Uniform iterations greatly exceed the physical depth. Then lanes
          // retire one at a time; only one original-mask marker remains.
          if (i >= 256 - lanes) active = active.clearBit(i - (256 - lanes))
          val before = if (i >= 256 - lanes) active.setBit(i - (256 - lanes)) else active
          x.branch(0x2080, 0x2040, 0x2100, before, active)
          x.path(if (active == 0) 0x2100 else 0x2040, if (active == 0) before else active)
          d.io.counts(0).expect(1.U)
        }
        x.join(0x2104, x.all); d.io.counts(0).expect(0.U)
        x.sync(0x2200, 0x2300); x.path(0x2204, x.all)
        val continuing = x.all >> 1
        x.branch(0x2204, 0x2300, 0x2208, x.all, x.all ^ continuing)
        x.path(0x2208, continuing); d.io.counts(0).expect(1.U)
        x.branch(0x2204, 0x2300, 0x2208, continuing, continuing)
        x.path(0x2300, continuing); x.join(0x2304, x.all)
      }
    }
  }
  it should "attribute capacity, missing-scope and alignment faults without partial writes and clear only the owner" in {
    simulate(new StructuredSimtHarness(GpuConfig(lanes = 4, warps = 2, simtStackDepth = 2))) { d =>
      val x = new Driver(d, 4)
      x.branch(0x3000, 0x3040, 0x3004, 15, 3); x.fault(27, 0x3000, 15, 0x3000)
      d.io.counts(0).expect(0.U)
      x.sync(0x3000, 0x3102); x.fault(0, 0x3000, 15, 0x3102)
      d.io.counts(0).expect(0.U)
      x.sync(0x3000, 0x3100); x.path(0x3004, 15)
      x.sync(0x3004, 0x3080); x.path(0x3008, 15)
      d.io.counts(0).expect(2.U)
      x.sync(0x3008, 0x3060); x.fault(26, 0x3008, 15, 0x3008)
      d.io.counts(0).expect(2.U)
      x.branch(0x3008, 0x3040, 0x300c, 15, 3); x.fault(26, 0x3008, 15, 0x3008)
      d.io.counts(0).expect(2.U)
      x.branch(0x3008, 0x3042, 0x300c, 15, 3); x.fault(0, 0x3008, 15, 0x3042)
      x.sync(0x4000, 0x4100, warp = 1); x.path(0x4004, 15, warp = 1)
      // Clear an owned operation before it can push; the peer scope survives.
      d.io.clear.valid.poke(true.B); d.io.clear.bits.poke(0.U); d.clock.step()
      d.io.clear.valid.poke(false.B); d.io.counts(0).expect(0.U); d.io.counts(1).expect(1.U)
      x.sync(0x5000, 0x5100)
      d.io.clear.valid.poke(true.B); d.io.clear.bits.poke(0.U); d.clock.step()
      d.io.clear.valid.poke(false.B); d.clock.step(5)
      d.io.current.valid.expect(false.B); d.io.fault.valid.expect(false.B)
      d.io.counts(0).expect(0.U); d.io.counts(1).expect(1.U)
      x.join(0x4104, 15, warp = 1)
      // A legacy externally supplied frame cannot masquerade as an explicit
      // instruction scope even when its numeric PC/mask happen to match.
      d.io.legacyEntry.bits.warpId.poke(0.U)
      d.io.legacyEntry.bits.entry.pc.poke(0x6100.U)
      d.io.legacyEntry.valid.poke(true.B); x.until(d.io.legacyEntry.ready.peek().litToBoolean)
      d.clock.step(); d.io.legacyEntry.valid.poke(false.B)
      x.branch(0x6000, 0x6080, 0x6004, 15, 3); x.fault(27, 0x6000, 15, 0x6000)
      d.io.counts(0).expect(1.U)
    }
  }
}
