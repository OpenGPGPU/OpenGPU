package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationWarpBarrierHarness extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationPatchContextTask))
    val opened = Decoupled(UInt(2.W)); val seal = Flipped(Decoupled(Bool()))
    val park = Flipped(Decoupled(new TessellationWarpPark(2, 16)))
    val resume = Decoupled(new TessellationWarpResume(2))
    val finish = Flipped(Decoupled(UInt(5.W))); val result = Decoupled(new TessellationBarrierResult)
    val close = Flipped(Decoupled(Bool())); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  val context = Module(new TessellationPatchContext)
  val warps = Module(new TessellationWarpBarrier(2, 16))
  context.io.begin <> io.begin; io.opened <> context.io.opened; context.io.sealInput <> io.seal
  warps.io.park <> io.park; io.resume <> warps.io.resume
  context.io.arrive <> warps.io.arrive; warps.io.release <> context.io.release
  context.io.finish <> io.finish; io.result <> context.io.controlResult; context.io.close <> io.close
  context.io.cancel := io.cancel; warps.io.cancel := io.cancel
  warps.io.terminal := context.io.controlResult.fire && context.io.controlResult.bits.status =/= 0.U
  io.busy := context.io.busy || warps.io.busy
  context.io.load.valid := false.B; context.io.load.bits := 0.U.asTypeOf(context.io.load.bits)
  context.io.access.valid := false.B; context.io.access.bits := 0.U.asTypeOf(context.io.access.bits)
  context.io.inspect.valid := false.B; context.io.inspect.bits := 0.U.asTypeOf(context.io.inspect.bits)
  context.io.reply.ready := true.B
}

class TessellationWarpBarrierSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _ => ()
  }
  it should "reject overlapping masks and hold an offered arrival during cancellation" in {
    simulate(new TessellationWarpBarrier(2, 16)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def park(w: Int, base: Int): Unit = {
        d.io.park.bits.warp.poke(w.U); d.io.park.bits.base.poke(base.U)
        d.io.park.bits.lanes.poke("hffff".U); d.io.park.bits.barrierId.poke(9.U)
        d.io.park.valid.poke(true.B); d.io.park.ready.expect(true.B); d.clock.step(); d.io.park.valid.poke(false.B)
      }
      park(0, 0); park(1, 8)
      var n = 0; while (!d.io.resume.valid.peek().litToBoolean && n < 8) { d.clock.step(); n += 1 }
      d.io.resume.valid.expect(true.B); d.io.resume.bits.warp.expect(1.U); d.io.resume.bits.status.expect(1.U)
      d.io.resume.ready.poke(true.B); d.clock.step(); d.io.resume.ready.poke(false.B)
      d.io.arrive.valid.expect(true.B); val held = d.io.arrive.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      for (_ <- 0 until 3) { d.io.arrive.valid.expect(true.B); assert(d.io.arrive.bits.peek().litValue == held); d.clock.step() }
      d.io.arrive.ready.poke(true.B); d.clock.step(); d.io.arrive.ready.poke(false.B)
      n = 0; while (!d.io.resume.valid.peek().litToBoolean && n < 8) { d.clock.step(); n += 1 }
      d.io.resume.valid.expect(true.B); d.io.resume.bits.warp.expect(0.U); d.io.resume.bits.status.expect(2.U)
      d.io.resume.ready.poke(true.B); d.clock.step(); d.io.resume.ready.poke(false.B); d.clock.step(3); d.io.busy.expect(false.B)
      park(1, 24)
      n = 0; while (!d.io.resume.valid.peek().litToBoolean && n < 8) { d.clock.step(); n += 1 }
      d.io.resume.valid.expect(true.B); d.io.resume.bits.status.expect(1.U)
    }
  }
  it should "park two warps across 32 invocations, repeat barriers, and drain cancellation" in {
    simulate(new TessellationWarpBarrierHarness) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(): Unit = {
        val t = d.io.begin.bits; t.taskId.poke(8.U); t.layout.inputVertices.poke(1.U); t.layout.inputComponents.poke(1.U)
        t.layout.outputVertices.poke(32.U); t.layout.outputComponents.poke(1.U); t.layout.patchComponents.poke(0.U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        d.io.opened.valid.expect(true.B); d.io.opened.bits.expect(0.U); d.io.opened.ready.poke(true.B); d.clock.step()
        d.io.opened.ready.poke(false.B); d.clock.step(); d.io.seal.valid.poke(true.B); d.io.seal.ready.expect(true.B)
        d.clock.step(); d.io.seal.valid.poke(false.B)
      }
      def park(w: Int, base: Int, id: Int): Unit = {
        d.io.park.bits.warp.poke(w.U); d.io.park.bits.base.poke(base.U); d.io.park.bits.lanes.poke("hffff".U)
        d.io.park.bits.barrierId.poke(id.U); d.io.park.valid.poke(true.B); d.io.park.ready.expect(true.B)
        d.clock.step(); d.io.park.valid.poke(false.B)
      }
      def resumes(id: Int, epoch: Int): Unit = {
        var cycles = 0; var seen = Set.empty[Int]
        while (seen.size < 2 && cycles < 200) {
          if (d.io.resume.valid.peek().litToBoolean) {
            val w = d.io.resume.bits.warp.peek().litValue.toInt
            assert(!seen(w)); d.io.resume.bits.status.expect(0.U); d.io.resume.bits.barrierId.expect(id.U)
            d.io.resume.bits.epoch.expect(epoch.U)
            if (seen.isEmpty) for (_ <- 0 until 3) { d.clock.step(); d.io.resume.valid.expect(true.B); d.io.resume.bits.warp.expect(w.U) }
            d.io.resume.ready.poke(true.B); d.clock.step(); d.io.resume.ready.poke(false.B); seen += w
          } else d.clock.step()
          cycles += 1
        }
        assert(seen == Set(0, 1))
      }
      begin()
      for (epoch <- 0 until 2) {
        park(0, 0, 10 + epoch)
        for (_ <- 0 until 40) { d.io.resume.valid.expect(false.B); d.clock.step() }
        park(1, 16, 10 + epoch); resumes(10 + epoch, epoch)
      }
      for (i <- 0 until 32) {
        d.io.finish.bits.poke(i.U); d.io.finish.valid.poke(true.B); d.io.finish.ready.expect(true.B); d.clock.step()
      }
      d.io.finish.valid.poke(false.B); d.io.result.valid.expect(true.B); d.io.result.bits.status.expect(0.U)
      d.io.result.bits.barriers.expect(2.U); d.io.result.ready.poke(true.B); d.clock.step(); d.io.result.ready.poke(false.B)
      d.io.close.valid.poke(true.B); d.io.close.ready.expect(true.B); d.clock.step(); d.io.close.valid.poke(false.B)
      begin(); park(0, 0, 20); d.clock.step(4); d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      var cycles = 0
      while (!d.io.resume.valid.peek().litToBoolean && cycles < 30) { d.clock.step(); cycles += 1 }
      d.io.resume.valid.expect(true.B); d.io.resume.bits.warp.expect(0.U); d.io.resume.bits.status.expect(2.U)
      d.io.resume.ready.poke(true.B); d.clock.step(); d.io.resume.ready.poke(false.B)
      cycles = 0; while (!d.io.result.valid.peek().litToBoolean && cycles < 30) { d.clock.step(); cycles += 1 }
      d.io.result.valid.expect(true.B); d.io.result.bits.status.expect(2.U); d.io.result.ready.poke(true.B); d.clock.step()
      d.io.result.ready.poke(false.B); d.io.close.valid.poke(true.B); d.io.close.ready.expect(true.B); d.clock.step()
      d.io.close.valid.poke(false.B); d.clock.step(3); d.io.busy.expect(false.B)

      // Divergent IDs can finish the context while an arrival remains offered.
      begin(); park(0, 0, 30); d.clock.step(8); park(1, 16, 31)
      cycles = 0; while (!d.io.result.valid.peek().litToBoolean && cycles < 100) { d.clock.step(); cycles += 1 }
      d.io.result.valid.expect(true.B); d.io.result.bits.status.expect(1.U)
      d.clock.step(3); d.io.result.valid.expect(true.B)
      d.io.result.ready.poke(true.B); d.clock.step(); d.io.result.ready.poke(false.B)
      var canceled = Set.empty[Int]; cycles = 0
      while (canceled.size < 2 && cycles < 30) {
        if (d.io.resume.valid.peek().litToBoolean) {
          val w = d.io.resume.bits.warp.peek().litValue.toInt
          assert(!canceled(w)); d.io.resume.bits.status.expect(2.U)
          d.io.resume.ready.poke(true.B); d.clock.step(); d.io.resume.ready.poke(false.B); canceled += w
        } else d.clock.step()
        cycles += 1
      }
      assert(canceled == Set(0, 1)); d.io.close.valid.poke(true.B); d.io.close.ready.expect(true.B)
      d.clock.step(); d.io.close.valid.poke(false.B); d.clock.step(3); d.io.busy.expect(false.B)
    }
  }
}
