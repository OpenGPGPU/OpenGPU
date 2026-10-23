package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

class TexSampleUnitSpec extends AnyFlatSpec {
  behavior of "TexSampleUnit"

  private def texel(r: Int, g: Int, b: Int): BigInt =
    (BigInt(r) << 24) | (BigInt(g) << 16) | (BigInt(b) << 8) | 0xff

  it should "serialize active vector lanes and preserve masked destination lanes" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new TexSampleUnit(config)) { dut =>
      val words = Map[Long, BigInt](
        0x2000L -> texel(0, 0, 0),
        0x2004L -> texel(255, 0, 0),
        0x2008L -> texel(0, 255, 0),
        0x200cL -> texel(255, 255, 0)
      )
      val pending = mutable.Queue.empty[Long]

      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(2.U)
      dut.io.texHeight.poke(2.U)
      dut.io.wrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.lodBias.poke(0.S)
      dut.io.minLevel.poke(0.U)
      dut.io.in.valid.poke(false.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.valid.poke(true.B)
      dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
      dut.io.vectorIn.bits.issued.decode.warpId.poke(1.U)
      dut.io.vectorIn.bits.issued.decode.pc.poke(0x300.U)
      dut.io.vectorIn.bits.issued.decode.activeMask.poke(0xf.U)
      dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
      dut.io.vectorIn.bits.executionMask.poke("b1011".U)
      val coords = Seq(0x4000, 0xc000, 0x4000, 0xc000)
      val vCoords = Seq(0x4000, 0x4000, 0xc000, 0xc000)
      val old = Seq(0x11, 0x22, 0xdeadbeefL, 0x44)
      for (lane <- 0 until config.lanes) {
        dut.io.vectorIn.bits.issued.vs1Data(lane).poke(coords(lane).U)
        dut.io.vectorIn.bits.issued.vs2Data(lane).poke(vCoords(lane).U)
        dut.io.vectorIn.bits.issued.oldVdData(lane).poke(old(lane).U)
      }
      dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))

      dut.io.vectorIn.ready.expect(true.B)
      dut.clock.step()
      dut.io.vectorIn.valid.poke(false.B)

      var guard = 0
      var requests = 0
      var responses = 0
      while (!dut.io.vectorCommit.valid.peek().litToBoolean && guard < 300) {
        if (pending.nonEmpty) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(words(pending.front).U)
          dut.io.mem.resp.bits.write.poke(false.B)
          dut.io.mem.resp.bits.addr.poke(pending.front.U)
          if (dut.io.mem.resp.ready.peek().litToBoolean) {
            pending.dequeue()
            responses += 1
          }
        } else dut.io.mem.resp.valid.poke(false.B)

        if (dut.io.mem.req.valid.peek().litToBoolean &&
          dut.io.mem.req.ready.peek().litToBoolean) {
          pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
          requests += 1
        }
        dut.clock.step()
        guard += 1
      }

      assert(dut.io.vectorCommit.valid.peek().litToBoolean,
        s"vector sample did not complete (requests=$requests, responses=$responses, pending=$pending)")
      dut.io.vectorCommit.bits.writeback.warpId.expect(1.U)
      dut.io.vectorCommit.bits.writeback.vd.expect(3.U)
      dut.io.vectorCommit.bits.writeback.data(0).expect(texel(0, 0, 0).U)
      dut.io.vectorCommit.bits.writeback.data(1).expect(texel(255, 0, 0).U)
      dut.io.vectorCommit.bits.writeback.data(2).expect("hdeadbeef".U)
      dut.io.vectorCommit.bits.writeback.data(3).expect(texel(255, 255, 0).U)
      dut.io.vectorCommit.bits.pc.expect(0x300.U)
      dut.clock.step(2)
      dut.io.vectorCommit.valid.expect(true.B)
      dut.io.vectorCommit.ready.poke(true.B)
      dut.clock.step()
      dut.io.vectorCommit.valid.expect(false.B)
    }
  }

  it should "apply bias and clamps to gradient-selected mip levels" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new TexSampleUnit(config)) { dut =>
      val pending = mutable.Queue.empty[Long]
      val green = texel(0, 255, 0)
      val red = texel(255, 0, 0)
      val blue = texel(0, 0, 255)

      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(4.U)
      dut.io.texHeight.poke(4.U)
      dut.io.wrapClamp.poke(true.B)
      dut.io.texMaxLevel.poke(2.U)
      dut.io.lodBias.poke(1.S)
      dut.io.minLevel.poke(0.U)
      dut.io.in.valid.poke(false.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.valid.poke(true.B)
      dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
      dut.io.vectorIn.bits.issued.decode.activeMask.poke(0xf.U)
      dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
      dut.io.vectorIn.bits.executionMask.poke(0xf.U)
      // Half a UV unit per pixel * four base texels = two texels/pixel:
      // floor(log2(rho)) = 1. Lane order is TL,TR,BL,BR.
      val u = Seq(0, 0x8000, 0, 0x8000)
      val v = Seq(0, 0, 0x8000, 0x8000)
      for (lane <- 0 until 4) {
        dut.io.vectorIn.bits.issued.vs1Data(lane).poke(u(lane).U)
        dut.io.vectorIn.bits.issued.vs2Data(lane).poke(v(lane).U)
      }
      dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)

      var guard = 0
      while (!dut.io.vectorCommit.valid.peek().litToBoolean && guard < 400) {
        if (pending.nonEmpty) {
          val addr = pending.front
          // Packed 4x4 base [0x2000,0x2040), 2x2 mip1
          // [0x2040,0x2050), 1x1 mip2 [0x2050,0x2054).
          val word = if (addr < 0x2040L) green
            else if (addr < 0x2050L) red else blue
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(word.U)
          dut.io.mem.resp.bits.write.poke(false.B)
          dut.io.mem.resp.bits.addr.poke(addr.U)
          if (dut.io.mem.resp.ready.peek().litToBoolean) pending.dequeue()
        } else dut.io.mem.resp.valid.poke(false.B)
        if (dut.io.mem.req.valid.peek().litToBoolean &&
          dut.io.mem.req.ready.peek().litToBoolean)
          pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
        dut.clock.step(); guard += 1
      }
      assert(dut.io.vectorCommit.valid.peek().litToBoolean,
        "gradient-derived mip sample did not complete")
      // Gradient chooses mip1; +1 bias selects mip2 without exceeding max=2.
      for (lane <- 0 until 4)
        dut.io.vectorCommit.bits.writeback.data(lane).expect(blue.U)
    }
  }

  it should "derive a log2 fractional LOD and trilinearly mix adjacent mips" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new TexSampleUnit(config)) { dut =>
      val pending = mutable.Queue.empty[Long]
      val green = texel(0, 255, 0)
      val red = texel(255, 0, 0)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(4.U); dut.io.texHeight.poke(4.U)
      dut.io.wrapClamp.poke(true.B); dut.io.texMaxLevel.poke(2.U)
      dut.io.lodBias.poke(0.S); dut.io.minLevel.poke(0.U)
      dut.io.in.valid.poke(false.B); dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.valid.poke(true.B)
      dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
      dut.io.vectorIn.bits.issued.decode.activeMask.poke(0xf.U)
      dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
      dut.io.vectorIn.bits.executionMask.poke(0xf.U)
      // 0.375 UV/pixel * 4 texels = rho 1.5.  Thus integer LOD is zero
      // and the Q0.8 weight is floor(256 * log2(1.5)) = 149.
      val u = Seq(0, 0x6000, 0, 0x6000)
      val v = Seq(0, 0, 0, 0)
      for (lane <- 0 until 4) {
        dut.io.vectorIn.bits.issued.vs1Data(lane).poke(u(lane).U)
        dut.io.vectorIn.bits.issued.vs2Data(lane).poke(v(lane).U)
      }
      dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)

      var guard = 0
      while (!dut.io.vectorCommit.valid.peek().litToBoolean && guard < 700) {
        if (pending.nonEmpty) {
          val addr = pending.front
          dut.io.mem.resp.valid.poke(true.B)
          // Level 0 is 4x4 at [0x2000,0x2040); level 1 begins at 0x2040.
          dut.io.mem.resp.bits.data.poke((if (addr < 0x2040L) green else red).U)
          dut.io.mem.resp.bits.write.poke(false.B)
          dut.io.mem.resp.bits.addr.poke(addr.U)
          if (dut.io.mem.resp.ready.peek().litToBoolean) pending.dequeue()
        } else dut.io.mem.resp.valid.poke(false.B)
        if (dut.io.mem.req.valid.peek().litToBoolean &&
          dut.io.mem.req.ready.peek().litToBoolean)
          pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
        dut.clock.step(); guard += 1
      }
      assert(dut.io.vectorCommit.valid.peek().litToBoolean,
        "fractional-LOD vector sample did not complete")
      // Channel values use the sampler's truncating Q0.8 multiply:
      // red=floor(255*149/256)=148, green=floor(255*107/256)=106.
      val expected = texel(148, 106, 0)
      for (lane <- 0 until 4)
        dut.io.vectorCommit.bits.writeback.data(lane).expect(expected.U)
    }
  }

  it should "match full-width gradient LOD across axes, extents and consecutive requests" in {
    simulate(new TexSampleUnit(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.valid.poke(false.B)
      dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      val random = new scala.util.Random(20260911L)
      val fixed = Seq(
        (4, 8, Seq(0L, 0x6000L, 0L, 0L), Seq.fill(4)(0L)),
        (8, 4, Seq.fill(4)(0L), Seq(0L, 0L, 0x6000L, 0L)),
        // Bias +1 on the largest automatic level drives the sum to 15 + 1 = 16.
        // A five-bit sum wrapped to -16 and selected the lower clamp; keeping
        // six signed bits selects the upper clamp (level 4, not level 0).
        (16, 1, Seq(0L, 0x08000000L, 0L, 0L), Seq.fill(4)(0L)),
        (1, 16383, Seq(0xffffffffL, 0L, 0xffffL, 0x10000L), Seq.fill(4)(0L)),
        (16383, 1, Seq.fill(4)(0L), Seq(0L, 0xffffffffL, 0L, 0x10001L)),
        (16383, 16382, Seq(0xffffffffL, 0L, 0x10001L, 0L),
          Seq(0L, 0xffffffffL, 0L, 0x10001L)),
        (3, 7, Seq.fill(4)(0L), Seq.fill(4)(0L)))
      val cases = fixed ++ Seq.tabulate(27) { i =>
        val mask = if (i % 3 == 0) 0xffffffffL else 0x1ffffL
        (1 + random.nextInt(16), 1 + random.nextInt(16),
          Seq.fill(4)(random.nextLong() & mask), Seq.fill(4)(random.nextLong() & mask))
      }
      for (((width, height, u, v), trial) <- cases.zipWithIndex) {
        val maxLevel = 4
        val bias = Seq(0, -2, 1)(trial % 3)
        val minLevel = trial % 2
        val edges = Seq((1, 0), (3, 2), (2, 0), (3, 1))
        // Reference evaluates all eight full products before taking the max.
        val rho = (edges.map { case (a, b) => BigInt((u(a) - u(b)).abs) * width } ++
          edges.map { case (a, b) => BigInt((v(a) - v(b)).abs) * height }).max
        val automatic = (1 until 16).filter(l => rho >= (BigInt(1) << (16 + l))).lastOption.getOrElse(0)
        // automatic (0..15) plus the signed bias (-16..15) spans -16..30 and
        // is kept in six signed bits, so the sum never wraps.
        val biased = automatic + bias
        val level = math.max(minLevel, math.min(maxLevel, biased))
        val normal = rho >> automatic
        val fraction = if (biased < minLevel || biased > maxLevel || normal <= 65536 || level == maxLevel) 0
          else math.floor(math.log(1.0 + ((normal >> 8) & 255).toDouble / 256.0) / math.log(2.0) * 256).toInt
        val colors = Seq.tabulate(5)(l => 20 + l * 40)
        val expected = (colors(level) * (256 - fraction) + colors(math.min(level + 1, maxLevel)) * fraction) >> 8
        val bases = mutable.ArrayBuffer(0x2000L)
        var w = width; var h = height
        for (_ <- 0 to maxLevel) {
          bases += bases.last + w.toLong * h * 4
          w = math.max(1, w / 2); h = math.max(1, h / 2)
        }
        dut.io.texBase.poke(0x2000.U)
        dut.io.texWidth.poke(width.U); dut.io.texHeight.poke(height.U)
        dut.io.wrapClamp.poke(true.B); dut.io.texMaxLevel.poke(maxLevel.U)
        dut.io.lodBias.poke(bias.S); dut.io.minLevel.poke(minLevel.U)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        dut.io.vectorIn.bits.executionMask.poke(1.U)
        dut.io.vectorIn.bits.issued.decode.activeMask.poke(15.U)
        for (lane <- 0 until 4) {
          dut.io.vectorIn.bits.issued.vs1Data(lane).poke(u(lane).U)
          dut.io.vectorIn.bits.issued.vs2Data(lane).poke(v(lane).U)
          dut.io.vectorIn.bits.issued.oldVdData(lane).poke((0x100 + lane).U)
        }
        dut.io.vectorIn.valid.poke(true.B)
        dut.io.vectorIn.ready.expect(true.B)
        dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)
        // Upstream operands need not remain stable after acceptance.
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        val pending = mutable.Queue.empty[Long]
        var cycle = 0
        while (!dut.io.vectorCommit.valid.peek().litToBoolean && cycle < 800) {
          dut.io.mem.req.ready.poke((cycle % 3 != 0).B)
          dut.io.mem.resp.valid.poke((pending.nonEmpty && cycle % 4 != 0).B)
          if (pending.nonEmpty) {
            val addr = pending.front
            val mip = (0 to maxLevel).find(l => addr >= bases(l) && addr < bases(l + 1)).get
            assert(mip == level || (fraction != 0 && mip == level + 1), s"trial $trial fetched mip $mip")
            dut.io.mem.resp.bits.addr.poke(addr.U)
            dut.io.mem.resp.bits.data.poke(texel(colors(mip), 0, 0).U)
            dut.io.mem.resp.bits.write.poke(false.B)
          }
          if (dut.io.mem.resp.valid.peek().litToBoolean && dut.io.mem.resp.ready.peek().litToBoolean)
            pending.dequeue()
          if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean)
            pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
          dut.clock.step(); cycle += 1
        }
        assert(cycle < 800, s"trial $trial timed out")
        dut.io.mem.resp.valid.poke(false.B)
        dut.io.vectorCommit.bits.writeback.data(0).expect(texel(expected, 0, 0).U)
        for (lane <- 1 until 4)
          dut.io.vectorCommit.bits.writeback.data(lane).expect((0x100 + lane).U)
        dut.clock.step(2)
        dut.io.vectorCommit.valid.expect(true.B)
        dut.io.vectorCommit.bits.writeback.data(0).expect(texel(expected, 0, 0).U)
        dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
        dut.io.vectorCommit.ready.poke(false.B)
      }
    }
  }

  it should "derive independent LODs for every quad in a 16-lane vector" in {
    simulate(new TexSampleUnit(GpuConfig(lanes = 16, warps = 2))) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.valid.poke(false.B)
      dut.io.vectorCommit.ready.poke(false.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(8.U); dut.io.texHeight.poke(8.U)
      dut.io.wrapClamp.poke(true.B); dut.io.texMaxLevel.poke(3.U)
      dut.io.lodBias.poke(0.S); dut.io.minLevel.poke(0.U)
      val bases = Seq(0x2000L, 0x2100L, 0x2140L, 0x2150L, 0x2154L)
      val colors = Seq(20, 80, 140, 200)
      // One active lane per quad still uses the three masked helper UVs.
      // Follow it with a full vector and an inactive middle quad to exercise
      // boundary recalculation, skipping, consecutive requests and read stalls.
      for ((mask, trial) <- Seq(0x1111, 0xffff, 0xf00f).zipWithIndex) {
        val levels = if (trial == 1) Seq(3, 2, 1, 0) else Seq(0, 1, 2, 3)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        dut.io.vectorIn.bits.executionMask.poke(mask.U)
        dut.io.vectorIn.bits.issued.decode.activeMask.poke(0xffff.U)
        dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
        for (lane <- 0 until 16) {
          val step = 8192L << levels(lane / 4) // extent 8 => rho 1/2/4/8
          dut.io.vectorIn.bits.issued.vs1Data(lane).poke((if (lane % 2 == 0) 0L else step).U)
          dut.io.vectorIn.bits.issued.vs2Data(lane).poke(0.U)
          dut.io.vectorIn.bits.issued.oldVdData(lane).poke((0x100 + lane).U)
        }
        dut.io.vectorIn.valid.poke(true.B)
        dut.io.vectorIn.ready.expect(true.B)
        dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        val pending = mutable.Queue.empty[Long]
        val mipsRead = mutable.Set.empty[Int]
        var cycle = 0
        while (!dut.io.vectorCommit.valid.peek().litToBoolean && cycle < 3000) {
          dut.io.mem.req.ready.poke((cycle % 3 != 0).B)
          dut.io.mem.resp.valid.poke((pending.nonEmpty && cycle % 4 != 0).B)
          if (pending.nonEmpty) {
            val addr = pending.front
            val mip = (0 until 4).find(l => addr >= bases(l) && addr < bases(l + 1)).get
            mipsRead += mip
            dut.io.mem.resp.bits.addr.poke(addr.U)
            dut.io.mem.resp.bits.data.poke(texel(colors(mip), 0, 0).U)
            dut.io.mem.resp.bits.write.poke(false.B)
          }
          if (dut.io.mem.resp.valid.peek().litToBoolean && dut.io.mem.resp.ready.peek().litToBoolean)
            pending.dequeue()
          if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean)
            pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
          dut.clock.step(); cycle += 1
        }
        assert(cycle < 3000, s"wide vector trial $trial timed out")
        dut.io.mem.resp.valid.poke(false.B)
        val expectedMips = (0 until 16).filter(l => (mask & (1 << l)) != 0).map(l => levels(l / 4)).toSet
        assert(mipsRead == expectedMips)
        for (_ <- 0 until 3) {
          dut.io.vectorCommit.valid.expect(true.B)
          for (lane <- 0 until 16) {
            val expected = if ((mask & (1 << lane)) != 0) texel(colors(levels(lane / 4)), 0, 0)
              else BigInt(0x100 + lane)
            dut.io.vectorCommit.bits.writeback.data(lane).expect(expected.U)
          }
          dut.clock.step()
        }
        dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
        dut.io.vectorCommit.ready.poke(false.B)
      }
    }
  }

  it should "bypass derivative latency for fixed mip ranges across masked quads" in {
    val config = GpuConfig(lanes = 16, warps = 2)
    simulate(new TexSampleUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B); dut.io.commit.ready.poke(true.B)
      dut.io.vectorIn.valid.poke(false.B); dut.io.vectorCommit.ready.poke(false.B)
      dut.io.texFormat.poke(PixelFormat.native.U)
      dut.io.texBase.poke(0x2000.U)
      dut.io.texWidth.poke(8.U); dut.io.texHeight.poke(8.U)
      dut.io.wrapClamp.poke(true.B)
      dut.io.mem.req.ready.poke(true.B); dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
      val bases = Seq(0x2000L, 0x2100L, 0x2140L, 0x2150L, 0x2154L)
      val colors = Seq(21, 75, 133, 201)
      // Alternate fixed/nonfixed ranges, including bias endpoints, empty
      // quads and an empty instruction. Helper coordinates have independent
      // gradients even when their destination lanes are masked off.
      val trials = Seq((0, 0, 15, 0xffff), (0, 3, 0, 0xffff),
        (2, 2, -16, 0x1111), (3, 3, 15, 0xf00f),
        (1, 1, 0, 0x00f0), (0, 0, -16, 0), (1, 3, 1, 0xf00f))
      for (((minimum, maximum, bias, mask), trial) <- trials.zipWithIndex) {
        dut.io.minLevel.poke(minimum.U); dut.io.texMaxLevel.poke(maximum.U)
        dut.io.lodBias.poke(bias.S)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        dut.io.vectorIn.bits.executionMask.poke(mask.U)
        dut.io.vectorIn.bits.issued.decode.activeMask.poke(0xffff.U)
        dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
        for (lane <- 0 until 16) {
          val step = 8192L << (lane / 4)
          dut.io.vectorIn.bits.issued.vs1Data(lane).poke((if (lane % 2 == 0) 0L else step).U)
          dut.io.vectorIn.bits.issued.vs2Data(lane).poke(0.U)
          dut.io.vectorIn.bits.issued.oldVdData(lane).poke((0x1200 + lane).U)
        }
        dut.io.vectorIn.valid.poke(true.B); dut.io.vectorIn.ready.expect(true.B)
        dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)
        dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
        // LOD controls must be held by the accepted instruction, not taken
        // from a later caller while the current vector traverses its quads.
        dut.io.minLevel.poke(((minimum + 1) % 4).U)
        dut.io.lodBias.poke((if (bias < 0) 15 else -16).S)
        val pending = mutable.Queue.empty[Long]
        val mipsRead = mutable.Set.empty[Int]
        var firstRequest = -1
        var cycle = 0
        var requests = 0
        while (!dut.io.vectorCommit.valid.peek().litToBoolean && cycle < 3000) {
          dut.io.mem.req.ready.poke((cycle % 3 != 1).B)
          dut.io.mem.resp.valid.poke((pending.nonEmpty && cycle % 4 != 2).B)
          if (pending.nonEmpty) {
            val address = pending.front
            val mip = (0 until 4).find(l => address >= bases(l) && address < bases(l + 1)).get
            mipsRead += mip
            dut.io.mem.resp.bits.addr.poke(address.U)
            dut.io.mem.resp.bits.data.poke(texel(colors(mip), 0, 0).U)
            dut.io.mem.resp.bits.write.poke(false.B)
          }
          if (dut.io.mem.resp.valid.peek().litToBoolean && dut.io.mem.resp.ready.peek().litToBoolean)
            pending.dequeue()
          if (dut.io.mem.req.valid.peek().litToBoolean) {
            if (firstRequest < 0) firstRequest = cycle
            if (dut.io.mem.req.ready.peek().litToBoolean) {
              pending.enqueue(dut.io.mem.req.bits.addr.peek().litValue.toLong)
              requests += 1
            }
          }
          dut.clock.step(); cycle += 1
        }
        assert(cycle < 3000, s"fixed LOD trial $trial timed out")
        dut.io.mem.resp.valid.poke(false.B)
        val levels = (0 until 4).map(q => math.max(minimum, math.min(maximum, q + bias)))
        val expectedMips = (0 until 16).filter(l => (mask & (1 << l)) != 0).map(l => levels(l / 4)).toSet
        assert(mipsRead == expectedMips, s"trial $trial read $mipsRead expected $expectedMips")
        for (_ <- 0 until 4) {
          dut.io.vectorCommit.valid.expect(true.B)
          for (lane <- 0 until 16) {
            val expected = if ((mask & (1 << lane)) != 0) texel(colors(levels(lane / 4)), 0, 0)
              else BigInt(0x1200 + lane)
            dut.io.vectorCommit.bits.writeback.data(lane).expect(expected.U)
          }
          dut.clock.step()
        }
        println(s"FIXED_LOD trial=$trial min=$minimum max=$maximum bias=$bias mask=$mask first_request=$firstRequest cycles=$cycle requests=$requests")
        if (minimum == maximum && mask != 0) {
          val firstLane = (0 until 16).find(l => (mask & (1 << l)) != 0).get
          // Each nonzero mip also requires its packed-base address walk;
          // this is sampler addressing latency, independent of derivatives.
          val budget = firstLane + 5 + minimum
          assert(firstRequest <= budget,
            s"fixed mip incurred derivative startup latency: first request $firstRequest, budget $budget")
        }
        if (mask == 0) assert(requests == 0 && cycle <= 16)
        dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
        dut.io.vectorCommit.ready.poke(false.B)
      }
    }
  }

  for (lanes <- Seq(4, 16)) {
    it should s"reuse vector texture words across $lanes lanes while preserving masks, mip LOD and rebinding" in {
      val config = GpuConfig(lanes = lanes, warps = 2)
      simulate(new TexSampleUnit(config)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.in.valid.poke(false.B); dut.io.commit.ready.poke(false.B)
        dut.io.vectorIn.valid.poke(false.B); dut.io.vectorCommit.ready.poke(false.B)
        dut.io.texFormat.poke(PixelFormat.native.U)
        dut.io.texBase.poke(0x4000.U); dut.io.wrapClamp.poke(true.B)
        dut.io.mem.req.ready.poke(false.B); dut.io.mem.resp.valid.poke(false.B)
        dut.io.mem.resp.bits.poke(0.U.asTypeOf(dut.io.mem.resp.bits))
        val all = (BigInt(1) << lanes) - 1
        val sparse = if (lanes == 4) BigInt(10) else BigInt(0x8420)
        val colors = (0 to 5).map(l => texel(20 + l * 40, 7 + l * 20, 9 + l * 13))
        var memory = Map.empty[Long, BigInt]
        def serve(done: () => Boolean): (Int, Int) = {
          val pending = mutable.ArrayBuffer.empty[(Long, Int)]
          var cycle = 0
          var requests = 0
          var first = -1
          var held = Option.empty[BigInt]
          while (!done() && cycle < 4000) {
            dut.io.mem.req.ready.poke((cycle % 5 != 2).B)
            val responseIndex = pending.lastIndexWhere(_._2 <= cycle)
            val response = if (responseIndex < 0) None else Some(pending(responseIndex))
            dut.io.mem.resp.valid.poke(response.nonEmpty.B)
            response.foreach { case (address, _) =>
              dut.io.mem.resp.bits.addr.poke(address.U)
              dut.io.mem.resp.bits.data.poke(memory(address).U)
              dut.io.mem.resp.bits.write.poke(false.B)
            }
            if (response.nonEmpty && dut.io.mem.resp.ready.peek().litToBoolean) pending.remove(responseIndex)
            held.foreach { address =>
              dut.io.mem.req.valid.expect(true.B)
              dut.io.mem.req.bits.addr.expect(address.U)
            }
            held = None
            if (dut.io.mem.req.valid.peek().litToBoolean) {
              if (first < 0) first = cycle
              if (dut.io.mem.req.ready.peek().litToBoolean) {
                val address = dut.io.mem.req.bits.addr.peek().litValue.toLong
                assert(memory.contains(address), f"unexpected sample address0x$address%x")
                pending.append((address, cycle + 7 + (if (requests % 4 == 0) 5 else 0)))
                requests += 1
              } else { held = Some(dut.io.mem.req.bits.addr.peek().litValue) }
            }
            dut.clock.step(); cycle += 1
          }
          assert(cycle < 4000 && pending.isEmpty)
          dut.io.mem.resp.valid.poke(false.B)
          (requests, first)
        }
        def spatialColor(pixel: Int, trial: Int): BigInt = {
          val offset = if (trial >= 6 && trial <= 8) 17 * (trial - 5) else 0
          texel(pixel * 11 + offset, pixel * 7 + offset, pixel * 3 + offset)
        }
        val trials = Seq((1, 1, 0, 0, all), (8, 4, 3, 3, sparse),
          (1, 1, 0, 2, all), (4, 4, 0, 0, all),
          (1, 1, 0, 0, BigInt(0)), (1, 1, 0, 0, all),
          (4, 4, 0, 0, all), (4, 4, 0, 0, all),
          (4, 4, 0, 0, BigInt(0)),
          (4, 4, 0, 0, (BigInt(3) << (lanes - 2))), (1, 1, 0, 0, all))
        for (((width, height, minimum, maximum, mask), trial) <- trials.zipWithIndex) {
          // Unaccepted masks may change freely, including an empty mask and
          // the final lane. None may issue work or affect the next snapshot.
          for (idleMask <- Seq(all, BigInt(0), BigInt(1) << (lanes - 1))) {
            dut.io.vectorIn.bits.executionMask.poke(idleMask.U)
            dut.io.mem.req.valid.expect(false.B)
            dut.io.vectorCommit.valid.expect(false.B)
            dut.clock.step()
          }
          val ordinary = trial == 3 || (trial >= 6 && trial <= 9)
          dut.io.texFormat.poke((if (trial == 7) PixelFormat.bgra else PixelFormat.native).U)
          var base = 0x4000L
          memory = Map.empty
          for (level <- 0 to maximum) {
            val w = math.max(1, width >> level)
            val h = math.max(1, height >> level)
            for (pixel <- 0 until w * h) {
              val color = if (ordinary) spatialColor(pixel, trial) else colors(level)
              val encoded = if (trial == 7) (BigInt(255) << 24) | (color >> 8) else color
              memory += (base + pixel * 4) -> encoded
            }
            base += w * h * 4
          }
          dut.io.texWidth.poke(width.U); dut.io.texHeight.poke(height.U)
          dut.io.minLevel.poke(minimum.U); dut.io.texMaxLevel.poke(maximum.U)
          dut.io.lodBias.poke(0.S)
          dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
          dut.io.vectorIn.bits.executionMask.poke(mask.U)
          dut.io.vectorIn.bits.issued.decode.activeMask.poke(all.U)
          dut.io.vectorIn.bits.issued.decode.warpId.poke(1.U)
          dut.io.vectorIn.bits.issued.decode.pc.poke((0x500 + trial * 4).U)
          dut.io.vectorIn.bits.issued.decode.instruction.poke("h062081ab".U)
          for (lane <- 0 until lanes) {
            val step = if (lane / 4 == 1) 2L << 16 else if (lane / 4 == 2) 4L << 16 else 0L
            val u = if (ordinary) (2L * (lane % 4) + 1) * 8192 else if (lane % 2 == 1) step else 0L
            val v = if (ordinary) (2L * (lane / 4) + 1) * 8192 else 0L
            dut.io.vectorIn.bits.issued.vs1Data(lane).poke(u.U)
            dut.io.vectorIn.bits.issued.vs2Data(lane).poke(v.U)
            dut.io.vectorIn.bits.issued.oldVdData(lane).poke((0x120000 + lane).U)
          }
          dut.io.vectorIn.valid.poke(true.B); dut.io.vectorIn.ready.expect(true.B)
          dut.clock.step(); dut.io.vectorIn.valid.poke(false.B)
          dut.io.vectorIn.bits.poke(0.U.asTypeOf(dut.io.vectorIn.bits))
          val (requests, first) = serve(() => dut.io.vectorCommit.valid.peek().litToBoolean)
          for (_ <- 0 until 3) {
            dut.io.vectorCommit.bits.writeback.warpId.expect(1.U)
            dut.io.vectorCommit.bits.pc.expect((0x500 + trial * 4).U)
            for (lane <- 0 until lanes) {
              val level = if (trial == 2) Seq(0, 1, 2, 0)(lane / 4) else minimum
              val sampled = if (ordinary) spatialColor(lane, trial) else colors(level)
              val expected = if (mask.testBit(lane)) sampled else BigInt(0x120000 + lane)
              dut.io.vectorCommit.bits.writeback.data(lane).expect(expected.U)
            }
            dut.io.vectorCommit.valid.expect(true.B)
            dut.io.vectorIn.ready.expect(false.B)
            dut.clock.step()
          }
          if (trial == 0 || trial == 1 || trial == 5 || trial == 10) {
            assert(requests == 1, s"single-texel vector fetched $requests words")
            assert(first <= 5 + minimum, s"leading masked lanes delayed the sample: $first")
          }
          if (ordinary && mask == all) {
            val expected = if (lanes == 4) 8 else 16
            assert(requests == expected, s"spatial cache requested $requests words, expected $expected")
          }
          if (mask == 0) assert(requests == 0)
          println(s"SINGLE_TEXEL_VECTOR lanes=$lanes trial=$trial requests=$requests first=$first")
          dut.io.vectorCommit.ready.poke(true.B); dut.clock.step()
          dut.io.vectorCommit.ready.poke(false.B)
        }
        // A scalar instruction immediately after the broadcast still uses
        // the scalar commit channel and releases its ordinary rd reservation.
        dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
        dut.io.in.bits.decode.warpId.poke(1.U)
        dut.io.in.bits.decode.pc.poke(0x700.U)
        dut.io.in.bits.decode.activeMask.poke(all.U)
        dut.io.in.bits.decode.instruction.poke((3 << 7).U)
        dut.io.in.valid.poke(true.B); dut.io.in.ready.expect(true.B)
        dut.clock.step(); dut.io.in.valid.poke(false.B)
        val (requests, _) = serve(() => dut.io.commit.valid.peek().litToBoolean)
        assert(requests == 1)
        dut.io.commit.bits.warpId.expect(1.U)
        dut.io.commit.bits.rd.expect(3.U)
        dut.io.commit.bits.nextPc.expect(0x704.U)
        dut.io.commit.bits.data.expect(colors(0).U)
        dut.io.vectorCommit.valid.expect(false.B)
        dut.io.commit.ready.poke(true.B); dut.clock.step()
      }
    }
  }

}
