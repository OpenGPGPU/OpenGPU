package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileRenderPassAdmissionSpec extends AnyFlatSpec {
  import TilePassTestData._
  behavior of "full-precision tile admission"

  it should "match absolute address intervals across high pitches, upper coordinates and the exclusive 4 GiB boundary" in {
    simulate(new TileRenderPassAdmission(4, 65535, 65535)) { dut =>
      dut.io.in.valid.poke(false.B); dut.io.out.ready.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val rng = new scala.util.Random(0x413dd)
      val limit = 1L << 32
      val directed = Seq(
        Pass(width = 1, height = 1, originX = 0, originY = 65520,
          pitch = 65536, colorBase = 0, depthBase = 4),
        Pass(width = 1, height = 1, originX = 0, originY = 65520,
          pitch = 65552, colorBase = 0, depthBase = 252, resolve = true,
          resolveBase = 128, resolvePitch = 65552),
        Pass(mode = 2, width = 1, height = 1, originX = 0, originY = 65520,
          pitch = 65536, colorBase = 0, depthBase = 16),
        Pass(width = 1, height = 1, originX = 0, originY = 65520,
          pitch = 65552, colorBase = 0, depthBase = 256),
        Pass(mode = 2, width = 1, height = 1, originX = 65520, originY = 0,
          pitch = 0x100000, colorBase = 0x100, depthBase = 0x110),
        Pass(width = 1, height = 1, originX = 0, originY = 256,
          pitch = 65532, colorBase = 0x400, depthBase = 0x404),
        Pass(width = 1, height = 1, originX = 0, originY = 65520,
          pitch = 0xfffffffcL, colorBase = 0, depthBase = 4),
        Pass(width = 0, height = 0, originX = 0, originY = 0))
      val random = (0 until 240).map { i =>
        val log = if (i % 2 == 0) 3 else 4
        val size = 1 << log
        val pitch = if (i % 3 == 0) Seq(512L, 65536L, 65552L, 0x80000004L, 0xfffffffcL)(i % 5)
          else (rng.nextLong() & 0xfffffffcL)
        Pass(mode = i % 3, width = 1 + rng.nextInt(size), height = 1 + rng.nextInt(size),
          tileLog2 = log, originX = rng.nextInt(65536 / size) * size,
          originY = rng.nextInt(65536 / size) * size, pitch = pitch,
          colorBase = if (i % 4 == 0) rng.nextLong() & 0xfffffffcL else 0,
          depthBase = if (i % 4 == 0) rng.nextLong() & 0xfffffffcL else 0x200000,
          resolve = i % 2 == 0, resolveBase = 0x400000,
          resolvePitch = if (i % 5 == 0) pitch else 512)
      }
      var admitted = 0
      for (p <- directed ++ random) {
        pokeDescriptor(dut.io.in.bits, p)
        dut.io.in.bits.color.limit.poke(limit.U)
        dut.io.in.bits.depth.limit.poke(limit.U)
        dut.io.in.bits.resolveLimit.poke(limit.U)
        val bases = Seq(p.colorBase, p.depthBase, p.resolveBase).map(BigInt(_))
        val pitches = Seq(p.pitch, p.pitch, p.resolvePitch).map(BigInt(_))
        val strides = Seq(p.samples * 4, p.samples * 4, 4).map(BigInt(_))
        val start = (0 until 3).map(i => bases(i) + BigInt(p.originY) * pitches(i) + BigInt(p.originX) * strides(i))
        val end = (0 until 3).map(i => bases(i) + BigInt(p.originY + p.height - 1) * pitches(i) +
          BigInt(p.originX + p.width) * strides(i))
        def disjoint(a: Int, b: Int): Boolean = end(a) <= start(b) || end(b) <= start(a)
        val basic = p.width > 0 && p.height > 0 && p.originX + p.width <= 65535 &&
          p.originY + p.height <= 65535 && p.pitch >= (p.originX + p.width) * p.samples * 4L
        val valid = basic && end(0) <= limit && end(1) <= limit && disjoint(0, 1) &&
          (!p.resolve || (p.resolvePitch >= (p.originX + p.width) * 4L &&
            end(2) <= limit && disjoint(2, 0) && disjoint(2, 1)))
        dut.io.in.ready.expect(true.B); dut.io.in.valid.poke(true.B)
        dut.clock.step(); dut.io.in.valid.poke(false.B)
        dut.io.in.bits.poke(0.U.asTypeOf(new TileRenderPassDescriptor))
        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
          dut.io.in.ready.expect(false.B); dut.clock.step(); cycles += 1
        }
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.valid.expect(valid.B)
        for (i <- 0 until 3) dut.io.out.bits.tileBases(i).expect((start(i) & BigInt("ffffffff", 16)).U)
        val frozen = dut.io.out.bits.peek().litValue
        for (_ <- 0 until 3) { dut.clock.step(); assert(dut.io.out.bits.peek().litValue == frozen) }
        dut.io.out.ready.poke(true.B); dut.clock.step(); dut.io.out.ready.poke(false.B)
        if (valid) admitted += 1
      }
      assert(admitted >= 5, "directed high-pitch/carry valid cases were not admitted")
    }
  }
}
