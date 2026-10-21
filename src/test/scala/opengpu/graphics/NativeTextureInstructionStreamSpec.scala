package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeTextureInstructionStreamSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val cfg = GpuConfig(lanes = 4, warps = 3)
  private class Driver(val d: NativeTextureInstructionStream) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val requests = mutable.ArrayBuffer.empty[BigInt]
    val cancels = mutable.Set.empty[BigInt]
    def tick(): Unit = {
      if (d.io.grouped.request.valid.peek().litToBoolean && d.io.grouped.request.ready.peek().litToBoolean)
        requests += d.io.grouped.request.bits.tag.peek().litValue
      if (d.io.grouped.cancelGroup.valid.peek().litToBoolean)
        cancels += d.io.grouped.cancelGroup.bits.tag.peek().litValue
      d.clock.step()
    }
    def until(p: => Boolean): Unit = {
      var n = 0; while (!p && n < 512) { tick(); n += 1 }; assert(p, "instruction contexts stalled")
    }
    def submit(warp: Int): Unit = {
      val q = d.io.in.bits; zero(q); identity(q.template.group.lookup.table.identity)
      q.warp.poke(warp.U); q.pc.poke((0x200 + warp * 4).U); q.vd.poke((10 + warp).U)
      q.active.poke(15.U); q.mask.poke(15.U); q.lane.poke(1.U)
      q.data(0).poke((0x11100000L + warp).U)
      d.io.in.valid.poke(true.B); until(d.io.in.ready.peek().litToBoolean)
      tick(); d.io.in.valid.poke(false.B)
    }
    def reply(tag: BigInt, status: Int = 0): Unit = {
      val r = d.io.grouped.response.bits; zero(r); identity(r.identity)
      r.tag.poke(tag.U); r.status.poke(status.U)
      r.faultAddressValid.poke((status == 3).B); r.faultAddress.poke((if (status == 3) 0xdead1000L else 0).U)
      r.rgba.zipWithIndex.foreach { case (x, c) => x.poke((0x3e800000L + (tag & 0xff).toLong * 16 + c).U) }
      d.io.grouped.response.valid.poke(true.B); tick(); d.io.grouped.response.valid.poke(false.B)
    }
  }

  it should "retain three warp contexts across out-of-order replies and a stalled public retirement" in {
    simulate(new NativeTextureInstructionStream(cfg, 3)) { d =>
      val t = new Driver(d)
      t.submit(0)
      d.io.in.bits.warp.poke(0.U); d.io.in.ready.expect(false.B)
      d.io.in.bits.warp.poke(1.U); d.io.in.ready.expect(true.B)
      t.submit(1); t.submit(2)
      d.io.warpBusy.expect(7.U); d.io.in.bits.warp.poke(1.U); d.io.in.ready.expect(false.B)
      d.io.grouped.request.ready.poke(true.B); t.until(t.requests.size == 9)
      assert(t.requests.distinct.size == 9 && t.requests.forall(_.testBit(31)))
      val byContext = t.requests.groupBy(x => ((x >> 2) & 3).toInt)
      // The first visible result must remain stable when older numbered rows
      // finish later. A fault belongs only to its own warp and vector result.
      byContext(2).reverse.foreach(tag => t.reply(tag, if ((tag & 3) == 3) 3 else 0))
      t.until(d.io.out.valid.peek().litToBoolean)
      val held = d.io.out.bits.peek().litValue
      byContext(0).reverse.foreach(t.reply(_)); byContext(1).reverse.foreach(t.reply(_))
      for (_ <- 0 until 8) { t.tick(); assert(d.io.out.bits.peek().litValue == held) }
      val warps = mutable.Set.empty[Int]
      d.io.out.ready.poke(true.B)
      while (warps.size < 3) {
        t.until(d.io.out.valid.peek().litToBoolean)
        val r = d.io.out.bits; val w = r.instruction.warp.peek().litValue.toInt
        assert(!warps(w)); warps += w; r.completed.expect(true.B)
        r.status.expect((if (w == 2) 3 else 0).U)
        r.instruction.pc.expect((0x200 + w * 4).U); r.instruction.vd.expect((10 + w).U)
        r.instruction.data(0).expect((0x11100000L + w).U)
        if (w != 2) for (l <- 1 until 4)
          r.instruction.data(l).expect((0x3e800000L + (w * 4 + l) * 16).U)
        else { r.faultAddressValid.expect(true.B); r.faultAddress.expect(0xdead1000L.U) }
        t.tick()
      }
      d.io.busy.expect(false.B); d.io.warpBusy.expect(0.U)
    }
  }

  it should "drain accepted offers on invalidation and resume only the untouched lanes" in {
    simulate(new NativeTextureInstructionStream(cfg, 3)) { d =>
      val t = new Driver(d); t.submit(1)
      for (_ <- 0 until 8) t.tick()
      d.io.grouped.request.valid.expect(true.B)
      val held = d.io.grouped.request.bits.peek().litValue
      d.io.invalidate.poke(true.B); t.tick(); d.io.invalidate.poke(false.B)
      for (_ <- 0 until 4) { t.tick(); assert(d.io.grouped.request.bits.peek().litValue == held) }
      d.io.grouped.request.ready.poke(true.B); t.until(t.requests.size == 2)
      t.requests.reverse.toVector.foreach(t.reply(_))
      t.until(d.io.out.valid.peek().litToBoolean)
      d.io.out.bits.completed.expect(false.B); d.io.out.bits.status.expect(0.U)
      d.io.out.bits.instruction.warp.expect(1.U); d.io.out.bits.instruction.lane.expect(3.U)
      d.io.warpBusy.expect(2.U)
      d.io.out.ready.poke(true.B); t.tick(); d.io.warpBusy.expect(0.U)
      t.submit(1); assert(t.requests.size == 2)
      d.io.cancel.poke(true.B)
      t.until(d.io.out.valid.peek().litToBoolean); d.io.out.bits.completed.expect(true.B)
      d.io.out.bits.status.expect(4.U); t.tick(); d.io.busy.expect(false.B)
    }
  }

  it should "cancel every live context while retaining a later primary fault and allowing exact tag reuse" in {
    simulate(new NativeTextureInstructionStream(cfg, 3)) { d =>
      val t = new Driver(d)
      for (w <- 0 until 3) t.submit(w)
      d.io.grouped.request.ready.poke(true.B); t.until(t.requests.size >= 4)
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 24) t.tick()
      val old = t.requests.toVector
      assert(old.nonEmpty && old.forall(t.cancels))
      val primary = old.head
      old.reverse.foreach(tag => t.reply(tag, if (tag == primary) 3 else 4))
      val retired = mutable.Set.empty[Int]; d.io.out.ready.poke(true.B)
      while (retired.size < 3) {
        t.until(d.io.out.valid.peek().litToBoolean)
        val r = d.io.out.bits; val w = r.instruction.warp.peek().litValue.toInt
        r.completed.expect(true.B); r.status.expect((if (w == ((primary >> 2) & 3).toInt) 3 else 4).U)
        assert(!retired(w)); retired += w; t.tick()
      }
      d.io.busy.expect(false.B); d.io.cancel.poke(false.B); d.io.out.ready.poke(false.B)
      t.requests.clear(); t.submit(0); t.until(t.requests.size == 3)
      assert(t.requests.exists(_ == primary))
      t.requests.reverse.toVector.foreach(t.reply(_))
      t.until(d.io.out.valid.peek().litToBoolean); d.io.out.bits.status.expect(0.U)
      d.io.out.ready.poke(true.B); t.tick(); d.io.busy.expect(false.B)
    }
  }
}
