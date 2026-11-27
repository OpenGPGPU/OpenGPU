package opengpu.graphics.tessellation

import opengpu.graphics._
import opengpu.graphics.tessellation._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class TessellationPatchStoreSpec extends AnyFlatSpec {
  it should "bound packed patch storage and retain replies under backpressure" in {
    simulate(new TessellationPatchStore) { d =>
      d.io.begin.valid.poke(false.B); d.io.beginResult.ready.poke(false.B); d.io.access.valid.poke(false.B)
      d.io.reply.ready.poke(false.B); d.io.end.valid.poke(false.B); d.reset.poke(true.B); d.clock.step();
      d.reset.poke(false.B)
      def layout(iv: Int, ic: Int, ov: Int, oc: Int, pc: Int): Unit = {
        val x = d.io.begin.bits; x.inputVertices.poke(iv.U); x.inputComponents.poke(ic.U); x.outputVertices.poke(ov.U)
        x.outputComponents.poke(oc.U); x.patchComponents.poke(pc.U)
      }
      def begin(status: Int): Unit = {
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        d.io.beginResult.valid.expect(true.B); d.io.beginResult.bits.expect(status.U); d.io.begin.ready.expect(false.B)
        for (_ <- 0 until 3) { d.io.beginResult.valid.expect(true.B); d.clock.step() }
        d.io.beginResult.ready.poke(true.B); d.clock.step(); d.io.beginResult.ready.poke(false.B)
      }
      for (
        (iv, ic, ov, oc, pc) <- Seq(
          (0, 64, 1, 1, 0),
          (33, 1, 1, 1, 0),
          (1, 65, 1, 1, 0),
          (1, 1, 0, 1, 0),
          (1, 1, 1, 65, 0),
          (1, 1, 1, 1, 121),
          (1, 1, 31, 64, 65)
        )
      ) { layout(iv, ic, ov, oc, pc); begin(1); d.io.busy.expect(false.B) }
      layout(32, 64, 31, 64, 64); begin(0); d.io.busy.expect(true.B)
      def access(
          out: Boolean,
          patch: Boolean,
          write: Boolean,
          v: Int,
          c: Int,
          data: BigInt,
          status: Int,
          expect: BigInt = 0,
          level: Boolean = false
      ): Unit = {
        val q = d.io.access.bits; q.output.poke(out.B); q.perPatch.poke(patch.B); q.tessLevel.poke(level.B);
        q.write.poke(write.B)
        q.vertex.poke(v.U); q.component.poke(c.U); q.data.poke(data.U)
        d.io.access.valid.poke(true.B); d.io.access.ready.expect(true.B); d.clock.step();
        d.io.access.valid.poke(false.B)
        var n = 0; while (!d.io.reply.valid.peek().litToBoolean && n < 4) { d.clock.step(); n += 1 };
        d.io.reply.valid.expect(true.B)
        d.io.reply.bits.status.expect(status.U); d.io.reply.bits.data.expect(expect.U);
        val held = d.io.reply.bits.peek().litValue
        d.io.end.valid.poke(true.B);
        for (_ <- 0 until 2) {
          d.io.end.ready.expect(false.B); assert(d.io.reply.bits.peek().litValue == held); d.clock.step()
        }
        d.io.end.valid.poke(false.B); d.io.reply.ready.poke(true.B); d.clock.step(); d.io.reply.ready.poke(false.B)
      }
      val input = mutable.Map.empty[(Int, Int), BigInt]; val output = mutable.Map.empty[(Int, Int), BigInt];
      val patch = mutable.Map.empty[Int, BigInt]
      val rng = new Random(0x32e5)
      for (i <- 0 until 80) {
        val v = if (i == 0) 31 else rng.nextInt(32); val c = if (i == 0) 63 else rng.nextInt(64);
        val x = BigInt(32, rng)
        input((v, c)) = x; access(false, false, true, v, c, x, 0)
      }
      for (i <- 0 until 80) {
        val v = if (i == 0) 30 else rng.nextInt(31); val c = if (i == 0) 63 else rng.nextInt(64);
        val x = BigInt(32, rng)
        output((v, c)) = x; access(true, false, true, v, c, x, 0)
      }
      for (i <- 0 until 32) {
        val c = if (i == 0) 63 else rng.nextInt(64); val x = BigInt(32, rng); patch(c) = x;
        access(true, true, true, 0, c, x, 0)
      }
      for (((v, c), x) <- input) access(false, false, false, v, c, 0, 0, x)
      for (((v, c), x) <- output) access(true, false, false, v, c, 0, 0, x)
      for ((c, x) <- patch) access(true, true, false, 0, c, 0, 0, x)
      access(false, false, false, 32, 0, 0, 1); access(true, false, false, 31, 0, 0, 1)
      access(true, false, false, 0, 64, 0, 1); access(true, true, false, 0, 64, 0, 1)
      access(false, true, false, 0, 0, 0, 1); access(true, true, false, 1, 0, 0, 1)
      d.io.end.valid.poke(true.B); d.io.end.ready.expect(true.B); d.clock.step(); d.io.end.valid.poke(false.B)
      d.io.busy.expect(false.B); layout(1, 1, 1, 1, 0); begin(0); access(false, false, true, 0, 0, 123, 0);
      access(false, false, false, 0, 0, 0, 0, 123)
      d.io.end.valid.poke(true.B); d.clock.step(); d.io.end.valid.poke(false.B); d.io.busy.expect(false.B)
      // Tessellation levels remain available even when all 2,048 output words are allocated.
      layout(32, 64, 32, 64, 0); begin(0)
      access(true, false, true, 31, 63, 0xdeadbeefL, 0); access(true, false, false, 31, 63, 0, 0, 0xdeadbeefL)
      for (i <- 0 until 6) access(true, true, true, 0, i, 100 + i, 0, level = true)
      for (i <- 0 until 6) access(true, true, false, 0, i, 0, 0, 100 + i, level = true)
      access(true, true, false, 0, 6, 0, 1, level = true); access(false, true, false, 0, 0, 0, 1, level = true)
      access(true, false, false, 0, 0, 0, 1, level = true); access(true, true, false, 1, 0, 0, 1, level = true)
      d.io.end.valid.poke(true.B); d.clock.step(); d.io.end.valid.poke(false.B); d.io.busy.expect(false.B)
    }
  }
}

/** Cross-owner initialization, independent of shader-provided output values. */
class TessellationOwnedPatchStoreSpec extends AnyFlatSpec {
  import opengpu.graphics.WindowRecordTest._
  it should "initialize every newly reachable word and level before publishing an owned patch" in {
    simulate(new TessellationPatchStore(zeroOnBegin = true)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def begin(iv: Int, ic: Int, ov: Int, oc: Int, pc: Int, status: Int = 0): Unit = {
        val l = d.io.begin.bits; l.inputVertices.poke(iv.U); l.inputComponents.poke(ic.U)
        l.outputVertices.poke(ov.U); l.outputComponents.poke(oc.U); l.patchComponents.poke(pc.U)
        d.io.begin.valid.poke(true.B); d.io.begin.ready.expect(true.B); d.clock.step(); d.io.begin.valid.poke(false.B)
        // Later caller mutation cannot shorten initialization of the saved layout.
        zero(l)
        val words = if (status == 0) math.max(iv * ic, ov * oc + pc) else 0
        for (_ <- 0 until words) {
          d.io.busy.expect(true.B); d.io.beginResult.valid.expect(false.B)
          d.io.begin.ready.expect(false.B); d.io.access.ready.expect(false.B); d.io.end.ready.expect(false.B);
          d.clock.step()
        }
        d.io.beginResult.valid.expect(true.B); d.io.beginResult.bits.expect(status.U)
        for (_ <- 0 until 3) {
          d.clock.step(); d.io.beginResult.valid.expect(true.B)
          d.io.beginResult.bits.expect(status.U); d.io.access.ready.expect(false.B); d.io.end.ready.expect(false.B)
        }
        d.io.beginResult.ready.poke(true.B); d.clock.step(); d.io.beginResult.ready.poke(false.B)
      }
      def access(
          out: Boolean,
          patch: Boolean,
          v: Int,
          c: Int,
          write: Boolean = false,
          data: BigInt = 0,
          expected: BigInt = 0,
          level: Boolean = false
      ): Unit = {
        val a = d.io.access.bits; zero(a); a.output.poke(out.B); a.perPatch.poke(patch.B); a.tessLevel.poke(level.B)
        a.vertex.poke(v.U); a.component.poke(c.U); a.write.poke(write.B); a.data.poke(data.U)
        d.io.access.valid.poke(true.B); d.io.access.ready.expect(true.B); d.clock.step();
        d.io.access.valid.poke(false.B)
        var n = 0; while (!d.io.reply.valid.peek().litToBoolean && n < 4) { d.clock.step(); n += 1 }
        d.io.reply.valid.expect(true.B); d.io.reply.bits.status.expect(0.U); d.io.reply.bits.data.expect(expected.U)
        d.io.end.valid.poke(true.B); d.clock.step(); d.io.end.ready.expect(false.B)
        d.io.reply.bits.data.expect(expected.U); d.io.end.valid.poke(false.B)
        d.io.reply.ready.poke(true.B); d.clock.step(); d.io.reply.ready.poke(false.B)
      }
      def end(): Unit = {
        d.io.end.valid.poke(true.B); d.io.end.ready.expect(true.B)
        d.clock.step(); d.io.end.valid.poke(false.B); d.io.busy.expect(false.B)
      }
      begin(32, 64, 32, 64, 0)
      for (v <- 0 until 32; c <- 0 until 64) {
        access(false, false, v, c, write = true, data = 0x10000000L + v * 64 + c)
        access(true, false, v, c, write = true, data = 0x20000000L + v * 64 + c)
      }
      for (c <- 0 until 6) access(true, true, 0, c, write = true, data = 0x30000000L + c, level = true)
      // Establish real nonzero prior contents independently of write ACKs.
      // A broken initialized-mode path that drops ordinary writes must fail.
      for (v <- 0 until 32; c <- 0 until 64) {
        access(false, false, v, c, expected = 0x10000000L + v * 64 + c)
        access(true, false, v, c, expected = 0x20000000L + v * 64 + c)
      }
      for (c <- 0 until 6) access(true, true, 0, c, expected = 0x30000000L + c, level = true)
      end()
      // Output-dominated initialization must cover its whole range even when
      // only one input word exists, and when output consists only of perPatch.
      begin(1, 1, 32, 64, 0)
      access(false, false, 0, 0); access(true, false, 31, 63)
      for (i <- 0 until 120) {
        access(true, false, i / 64, i % 64, write = true, data = 0x60000000L + i)
        access(true, false, i / 64, i % 64, expected = 0x60000000L + i)
      }
      end()
      begin(1, 0, 1, 0, 120)
      for (c <- 0 until 120) access(true, true, 0, c)
      access(true, true, 0, 119, write = true, data = 0x70000000L)
      access(true, true, 0, 119, expected = 0x70000000L)
      end()
      // Full input and coupled output/patch storage, across a layout change.
      begin(32, 64, 32, 60, 120)
      for (v <- 0 until 32; c <- 0 until 64) access(false, false, v, c)
      for (v <- 0 until 32; c <- 0 until 60) access(true, false, v, c)
      for (c <- 0 until 120) access(true, true, 0, c)
      for (c <- 0 until 6) access(true, true, 0, c, level = true)
      for (c <- 0 until 120) access(true, true, 0, c, write = true, data = 0x50000000L + c)
      for (c <- 0 until 120) access(true, true, 0, c, expected = 0x50000000L + c)
      end()
      // Growing the output exposes eight old addresses that were unreachable
      // under the previous layout; they must initialize again too.
      begin(32, 64, 32, 64, 0)
      for (v <- 30 until 32; c <- 0 until 64) access(true, false, v, c)
      for (c <- 0 until 6) access(true, true, 0, c, level = true)
      for (c <- 0 until 6) access(true, true, 0, c, write = true, data = 0x40000000L + c, level = true)
      end()
      begin(32, 64, 32, 64, 1, status = 1); d.io.busy.expect(false.B)
      begin(1, 0, 1, 0, 0)
      for (c <- 0 until 6) access(true, true, 0, c, level = true)
      end()
    }
  }
}
