package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies the M5b texture unit against an integer-exact software reference.
  *
  * The reference uses full-precision coordinates, floor-modulo repeat and
  * saturating clamp, independently of the RTL's bounded coordinate reduction.
  */
class TextureUnitSpec extends AnyFlatSpec {
  behavior of "TextureUnit"

  /** Byte-addressed word memory model with out-of-range tracking. */
  private class TexMem(val base: Long, val width: Int, val height: Int,
                       val levels: Int = 1) {
    val words = mutable.LongMap[Int]()
    var oobReads = 0
    def levelInfo(level: Int): (Long, Int, Int) = {
      var offset = 0L
      var w = width
      var h = height
      for (_ <- 0 until level) {
        offset += w.toLong * h * 4
        w = math.max(1, w / 2)
        h = math.max(1, h / 2)
      }
      (base + offset, w, h)
    }
    def put(x: Int, y: Int, argb: Int): Unit =
      words(base + (y.toLong * width + x) * 4) = argb
    def putLevel(level: Int, x: Int, y: Int, argb: Int): Unit = {
      val (levelBase, w, _) = levelInfo(level)
      words(levelBase + (y.toLong * w + x) * 4) = argb
    }
    def texel(x: Int, y: Int): Int =
      words.getOrElse(base + (y.toLong * width + x) * 4, 0)
    private def inRange(a: Long): Boolean =
      a >= base && a < levelInfo(levels)._1 &&
        ((a - base) % 4) == 0
    def read(a: Long): Int = {
      if (!inRange(a)) oobReads += 1
      words.getOrElse(a, 0)
    }
  }

  /** Q16.16 helper. */
  private def q(v: Double): Long = math.round(v * 65536.0)

  /** Texel word in the pipeline's packed-colour order (r,g,b, opaque alpha). */
  private def texel(r: Int, g: Int, b: Int): Int =
    (r << 24) | (g << 16) | (b << 8) | 0xff

  /** Integer-exact sampling contract without hardware-width truncation. */
  private def refSample(
    mem: TexMem,
    clampMode: Boolean,
    u: Long,
    v: Long
  ): Int = {
    def axis(coord: Long, extent: Int): (Int, Int, Int) = {
      val biased: Long = (coord * extent.toLong >> 8) - 128L
      val periodLast: Long = extent.toLong << 8
      val inRange =
        if (clampMode)
          if (biased < 0L) 0L else math.min(biased, periodLast - 1L)
        else Math.floorMod(biased, periodLast)
      val idx0raw = (inRange >> 8).toInt
      val frac = (inRange & 0xff).toInt
      val idx0 =
        if (clampMode) math.min(idx0raw, extent - 1) else idx0raw
      val idx1 =
        if (clampMode) math.min(idx0 + 1, extent - 1)
        else (idx0 + 1) % extent
      (idx0, idx1, frac)
    }
    val (x0, x1, fx) = axis(u, mem.width)
    val (y0, y1, fy) = axis(v, mem.height)
    val t00 = mem.texel(x0, y0); val t10 = mem.texel(x1, y0)
    val t01 = mem.texel(x0, y1); val t11 = mem.texel(x1, y1)
    val w00 = (256 - fx) * (256 - fy)
    val w10 = fx * (256 - fy)
    val w01 = (256 - fx) * fy
    val w11 = fx * fy
    require(w00 + w10 + w01 + w11 == 65536,
      "reference weights must sum to exactly 65536")
    def chan(sel: Int => Int): Int = {
      val acc = sel(t00) * w00 + sel(t10) * w10 + sel(t01) * w01 +
        sel(t11) * w11
      (acc >> 16) & 0xff
    }
    (chan(w => (w >> 24) & 0xff) << 24) |
      (chan(w => (w >> 16) & 0xff) << 16) |
      (chan(w => (w >> 8) & 0xff) << 8) | 0xff
  }

  /** Attach config pins once; request/response/memory lines are driven by
    * the shared service loop below.
    */
  private def attach(dut: TextureUnit, mem: TexMem, wrapClamp: Boolean,
                     maxLevel: Int = 0): Unit = {
    dut.io.texFormat.poke(0.U)
    dut.io.texBase.poke(mem.base.U)
    dut.io.texWidth.poke(mem.width.U)
    dut.io.texHeight.poke(mem.height.U)
    dut.io.wrapMode.poke(wrapClamp.B)
    dut.io.texMaxLevel.poke(maxLevel.U)
    dut.io.mem.req.ready.poke(true.B)
  }

  /** One sample transaction driven through a single continuous service loop
    * (the pattern proven by GpuHostAxiSpec): accept, capture every fired word
    * request, return address-tagged responses with at least one cycle of
    * latency, and consume the held result.
    *
    * If `stallOnValid` cycles > 0 the consumer deliberately stalls once the
    * result appears; the returned value is what was visible DURING the stall,
    * which checks that the held result neither changes nor leaks.
    */
  private class SampleStats {
    val requestCycles = mutable.ArrayBuffer.empty[Int]
    val requestAddrs = mutable.ArrayBuffer.empty[Long]
    val responseAddrs = mutable.ArrayBuffer.empty[Long]
    val responseCycles = mutable.ArrayBuffer.empty[Int]
    var resultCycle = -1
  }

  private def runSample(
    dut: TextureUnit,
    mem: TexMem,
    u: Long,
    v: Long,
    mipLevel: Int = 0,
    lodFrac: Int = 0,
    stallCycles: Int = 0,
    maxCycles: Int = 400,
    reverseResponses: Boolean = false,
    stats: Option[SampleStats] = None,
    formatAfterAccept: Option[Int] = None,
    requestStallCycles: Int = 0
  ): Int = {
    dut.io.sample.valid.poke(true.B)
    dut.io.sample.bits.u.poke(u.U)
    dut.io.sample.bits.v.poke(v.U)
    dut.io.sample.bits.mipLevel.poke(mipLevel.U)
    dut.io.sample.bits.lodFrac.poke(lodFrac.U)
    val pending = mutable.ArrayBuffer.empty[Long]
    val captured = mutable.ArrayBuffer.empty[Long]
    var releaseReverseBatch = !reverseResponses
    var accepted = false
    var seenValue = false
    var value = 0
    var guard = 0
    var done = false
    var stalled = 0
    while (!done && guard < maxCycles) {
      dut.io.mem.req.ready.poke((guard >= requestStallCycles).B)
      // Request side: drop valid once the handshake has committed.
      if (accepted) {
        dut.io.sample.valid.poke(false.B)
        formatAfterAccept.foreach(f => dut.io.texFormat.poke(f.U))
      }
      else if (dut.io.sample.valid.peek().litToBoolean &&
        dut.io.sample.ready.peek().litToBoolean) accepted = true

      // Requests captured last cycle become response candidates now. In the
      // reverse-order mode, hold a complete four-tap level batch, then return
      // it last-request-first to exercise address-based response routing.
      pending ++= captured
      captured.clear()
      if (reverseResponses && !releaseReverseBatch && pending.size >= 4)
        releaseReverseBatch = true
      val responseIdx =
        if (reverseResponses) pending.size - 1 else 0
      if (pending.nonEmpty && releaseReverseBatch) {
        val responseAddr = pending(responseIdx)
        dut.io.mem.resp.valid.poke(true.B)
        dut.io.mem.resp.bits.data.poke(
          BigInt(mem.read(responseAddr) & 0xffffffffL).U)
        dut.io.mem.resp.bits.write.poke(false.B)
        dut.io.mem.resp.bits.addr.poke(responseAddr.U)
        if (dut.io.mem.resp.valid.peek().litToBoolean &&
          dut.io.mem.resp.ready.peek().litToBoolean) {
          stats.foreach { s =>
            s.responseAddrs += responseAddr
            s.responseCycles += guard
          }
          pending.remove(responseIdx)
          if (reverseResponses && pending.isEmpty) releaseReverseBatch = false
        }
      } else dut.io.mem.resp.valid.poke(false.B)
      if (dut.io.mem.req.valid.peek().litToBoolean &&
        dut.io.mem.req.ready.peek().litToBoolean) {
        val requestAddr = dut.io.mem.req.bits.addr.peek().litValue.toLong
        captured += requestAddr
        stats.foreach { s =>
          s.requestCycles += guard
          s.requestAddrs += requestAddr
        }
      }

      // Result side: optionally stall first, then consume.
      dut.io.result.ready.poke(false.B)
      if (!seenValue && dut.io.result.valid.peek().litToBoolean) {
        value = dut.io.result.bits.peek().litValue.toInt & 0xffffffff
        seenValue = true
        stats.foreach(_.resultCycle = guard)
      }
      if (seenValue) {
        dut.io.result.valid.expect(true.B)
        dut.io.result.bits.expect(BigInt(value & 0xffffffffL).U)
        if (stallCycles > stalled) stalled += 1
        else {
          dut.io.result.ready.poke(true.B)
          if (dut.io.result.ready.peek().litToBoolean) done = true
        }
      }

      dut.clock.step()
      guard += 1
    }
    dut.io.sample.valid.poke(false.B)
    dut.io.result.ready.poke(false.B)
    dut.io.mem.resp.valid.poke(false.B)
    assert(done, s"sample did not complete (guard=$guard)")
    value
  }

  it should "finish single-texel levels without coordinate or bilinear pipeline bubbles" in {
    simulate(new TextureUnit()) { dut =>
      val words = Seq(0x115395d3L, 0xd3955311L, 0xd3115395L, 0x00955311L, 0x00115395L)
      for (format <- words.indices; clamp <- Seq(false, true);
           coordinate <- Seq(0L, q(0.5), q(1.0), 0xffffffffL)) {
        val mem = new TexMem(0x1000, 1, 1)
        mem.put(0, 0, words(format).toInt)
        attach(dut, mem, wrapClamp = clamp)
        dut.io.texFormat.poke(format.U)
        val stats = new SampleStats
        val got = runSample(dut, mem, coordinate, 0xffffffffL - coordinate,
          stallCycles = 3, stats = Some(stats), formatAfterAccept = Some((format + 1) % 5))
        assert((got & 0xffffffffL) == (if (format >= 3) 0x115395ffL else 0x115395d3L))
        assert(stats.requestAddrs.toSeq == Seq(0x1000L) && mem.oobReads == 0)
        assert(stats.requestCycles.head <= 2, "a 1x1 level needs no coordinate pipeline")
        assert(stats.resultCycle <= 4, "a one-cycle memory reply should finish by cycle four")
      }
    }
  }

  it should "mix distinct 1x1 mip levels and enter a 1x1 level from a larger level" in {
    simulate(new TextureUnit()) { dut =>
      // Every level has a different colour and alpha, so skipping a mip walk
      // or mixing against a stale first-level value cannot pass this oracle.
      val colours = Seq(0xa0205040, 0x20609080, 0x0c3cdcc0, 0x6c140420, 0xc86428a0)
      val mem = new TexMem(0x1800, 4, 2, levels = 5)
      for (level <- colours.indices) {
        val (_, w, h) = mem.levelInfo(level)
        for (y <- 0 until h; x <- 0 until w) mem.putLevel(level, x, y, colours(level))
      }
      attach(dut, mem, wrapClamp = true, maxLevel = 4)
      assert((runSample(dut, mem, q(0.31), q(0.79), mipLevel = 1, lodFrac = 64) & 0xffffffffL) == 0x1b57a390L)
      assert((runSample(dut, mem, q(0.8), q(0.2), mipLevel = 2, lodFrac = 128) & 0xffffffffL) == 0x3c287070L)
      assert((runSample(dut, mem, q(1.8), q(1.4), mipLevel = 15, lodFrac = 255) & 0xffffffffL) == 0xc86428a0L)
      assert(mem.oobReads == 0)
      val tiny = new TexMem(0x2000, 1, 1, levels = 3)
      tiny.putLevel(0, 0, 0, 0x10203040)
      tiny.putLevel(1, 0, 0, 0x50607080)
      tiny.putLevel(2, 0, 0, 0x90a0b0c0)
      attach(dut, tiny, wrapClamp = false, maxLevel = 2)
      assert((runSample(dut, tiny, 0xffffffffL, 0, mipLevel = 0, lodFrac = 128,
        stallCycles = 4) & 0xffffffffL) == 0x30405060L)
      assert((runSample(dut, tiny, 0, 0xffffffffL, mipLevel = 1, lodFrac = 64) & 0xffffffffL) == 0x60708090L)
      val stalled = new SampleStats
      assert((runSample(dut, tiny, q(0.5), q(0.5), stallCycles = 4,
        stats = Some(stalled), requestStallCycles = 8) & 0xffffffffL) == 0x10203040L)
      assert(stalled.requestCycles.toSeq == Seq(8) && stalled.requestAddrs.toSeq == Seq(0x2000L))
      assert(tiny.oobReads == 0)
      // Reusing the unit for a larger level must re-enable bilinear filtering.
      attach(dut, mem, wrapClamp = true, maxLevel = 4)
      assert((runSample(dut, mem, q(0.31), q(0.79)) & 0xffffffffL) == 0xa0205040L)
    }
  }

  it should "sample Mesa byte orders with alpha and retain format under backpressure" in {
    simulate(new TextureUnit()) { dut =>
      val memoryWords = Seq(0x115395d3L, 0xd3955311L, 0xd3115395L, 0x00955311L, 0x00115395L)
      for (format <- memoryWords.indices) {
        val mem = new TexMem(0x1000, 2, 2)
        for (y <- 0 until 2; x <- 0 until 2) mem.put(x, y, memoryWords(format).toInt)
        attach(dut, mem, wrapClamp = true)
        dut.io.texFormat.poke(format.U)
        val got = runSample(dut, mem, q(0.5), q(0.5), reverseResponses = true,
          stallCycles = 5, formatAfterAccept = Some((format + 1) % 5))
        val expected = if (format >= 3) 0x115395ffL else 0x115395d3L
        assert((got & 0xffffffffL) == expected, s"format=$format got=0x${got.toHexString}")
        assert(mem.oobReads == 0)
      }
      // Alpha uses the same bilinear weights as RGB, rather than becoming 255.
      val mem = new TexMem(0x1000, 2, 2)
      Seq(0x11955311L, 0x53955311L, 0x95955311L, 0xd3955311L).zipWithIndex.foreach {
        case (word, i) => mem.put(i % 2, i / 2, word.toInt)
      }
      attach(dut, mem, wrapClamp = true)
      dut.io.texFormat.poke(PixelFormat.rgba.U)
      assert(runSample(dut, mem, q(0.5), q(0.5)) == 0x11539573)
      val mip = new TexMem(0x1000, 2, 2, levels = 2)
      for (y <- 0 until 2; x <- 0 until 2) mip.put(x, y, 0x11955311)
      mip.putLevel(1, 0, 0, 0xd3955311L.toInt)
      attach(dut, mip, wrapClamp = true, maxLevel = 1)
      dut.io.texFormat.poke(PixelFormat.rgba.U)
      assert(runSample(dut, mip, q(0.5), q(0.5), lodFrac = 128) == 0x11539572)
    }
  }

  it should "blend a gradient bilinearly at interior points" in {
    val mem = new TexMem(0x4000L, width = 2, height = 2)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      mem.put(0, 0, texel(0, 0, 0)) // black
      mem.put(1, 0, texel(255, 0, 0)) // red
      mem.put(0, 1, texel(0, 255, 0)) // green
      mem.put(1, 1, texel(255, 255, 0)) // yellow

      val got = runSample(dut, mem, q(0.5), q(0.5))
      val r = ((got >> 24) & 0xff); val g = ((got >> 16) & 0xff)
      val b = ((got >> 8) & 0xff)
      assert(r == 127 && g == 127 && b == 0,
        s"centre blend expected ~(127,127,0), got ($r,$g,$b)")
      val expectQuarter =
        refSample(mem, clampMode = false, q(0.25), q(0.25))
      val gotQuarter = runSample(dut, mem, q(0.25), q(0.25))
      assert(gotQuarter == expectQuarter,
        f"quarter-point hw=${gotQuarter.toHexString} ref=${expectQuarter.toHexString}")
    }
  }

  it should "issue four taps consecutively and route reversed responses" in {
    val mem = new TexMem(0x4000L, width = 2, height = 2)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      mem.put(0, 0, texel(0x10, 0x20, 0x30))
      mem.put(1, 0, texel(0x40, 0x50, 0x60))
      mem.put(0, 1, texel(0x70, 0x80, 0x90))
      mem.put(1, 1, texel(0xa0, 0xb0, 0xc0))

      val stats = new SampleStats
      val got = runSample(dut, mem, q(0.5), q(0.5),
        reverseResponses = true, stats = Some(stats))
      val expect = refSample(mem, clampMode = false, q(0.5), q(0.5))
      assert(got == expect,
        f"reversed completion hw=${got.toHexString} ref=${expect.toHexString}")
      assert(stats.requestCycles.size == 4,
        s"expected four tap requests, got ${stats.requestCycles.size}")
      assert(stats.requestCycles.zip(stats.requestCycles.drop(1)).forall {
        case (a, b) => b == a + 1
      }, s"tap requests were not consecutive: ${stats.requestCycles.mkString(",")}")
      assert(stats.responseAddrs == stats.requestAddrs.reverse,
        s"responses were not reversed: req=${stats.requestAddrs.mkString(",")} " +
          s"resp=${stats.responseAddrs.mkString(",")}")
    }
  }

  it should "clamp past-edge samples to edge texels without out-of-bounds reads" in {
    val mem = new TexMem(0x4000L, width = 4, height = 4)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = true)
      for (y <- 0 until 4; x <- 0 until 4)
        mem.put(x, y, texel(x * 40, y * 40, 0))

      val far = runSample(dut, mem, q(1.75), q(1.75))
      val expect = refSample(mem, clampMode = true, q(1.75), q(1.75))
      assert(far == expect, s"clamped far corner hw=${far.toHexString} ref=${expect.toHexString}")
      assert(far == mem.texel(3, 3),
        s"expected solid edge texel, got ${far.toHexString}")
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }

  it should "coalesce duplicate clamped taps into one physical read" in {
    val mem = new TexMem(0x4000L, width = 4, height = 4)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = true)
      for (y <- 0 until 4; x <- 0 until 4)
        mem.put(x, y, texel(x * 40, y * 40, 0))

      // Both axes clamp to their final texel, so all four bilinear inputs are
      // identical. The blend still needs four logical words, but only one
      // memory response should supply them.
      val stats = new SampleStats
      val got = runSample(dut, mem, q(1.75), q(1.75), stats = Some(stats))
      assert(got == mem.texel(3, 3),
        f"coalesced edge sample got ${got.toHexString}")
      assert(stats.requestAddrs == Seq(0x403cL),
        s"expected one edge read, got ${stats.requestAddrs.mkString(",")}")
      assert(stats.responseAddrs == stats.requestAddrs,
        s"response did not match coalesced request: ${stats.responseAddrs.mkString(",")}")

      // Clamping only X still leaves two distinct Y taps. Check that the
      // coalescer neither loses a unique address nor aliases its response.
      val partialStats = new SampleStats
      val partial = runSample(dut, mem, q(1.75), q(0.5),
        stats = Some(partialStats))
      assert(partial == refSample(mem, clampMode = true, q(1.75), q(0.5)),
        f"partially coalesced sample got ${partial.toHexString}")
      assert(partialStats.requestAddrs == Seq(0x401cL, 0x402cL),
        s"expected two unique edge reads, got ${partialStats.requestAddrs.mkString(",")}")
    }
  }

  it should "tile with REPEAT across the seam" in {
    val mem = new TexMem(0x4000L, width = 4, height = 4)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      for (y <- 0 until 4; x <- 0 until 4)
        mem.put(x, y, texel(x * 60, y * 60 + 10, 5))

      val got = runSample(dut, mem, q(1.02), q(0.5))
      val expect = refSample(mem, clampMode = false, q(1.02), q(0.5))
      assert(got == expect, f"seam wrap hw=${got.toHexString} ref=${expect.toHexString}")
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }

  it should "return exact texel values at sample centres (nearest via degenerate bilinear)" in {
    val mem = new TexMem(0x4000L, width = 8, height = 8)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      for (y <- 0 until 8; x <- 0 until 8)
        mem.put(x, y, texel(x * 13, y * 17, (x ^ y) & 0xff))
      for (y <- 0 until 8; x <- 0 until 8) {
        val got = runSample(dut, mem,
          ((2 * x + 1) * 65536L / 16), ((2 * y + 1) * 65536L / 16))
        val want = mem.texel(x, y)
        assert(got == want,
          f"texel centre ($x,$y) got ${got.toHexString} want ${want.toHexString}")
      }
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }

  it should "walk a packed mip chain and clamp requests to max level" in {
    val mem = new TexMem(0x4000L, width = 8, height = 4, levels = 4)
    val colors = Seq(
      texel(0x10, 0, 0), texel(0x20, 0, 0),
      texel(0x30, 0, 0), texel(0x40, 0, 0))
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = true, maxLevel = 3)
      for (level <- colors.indices) {
        val (_, w, h) = mem.levelInfo(level)
        for (y <- 0 until h; x <- 0 until w)
          mem.putLevel(level, x, y, colors(level))
      }
      for (level <- colors.indices)
        assert(runSample(dut, mem, q(0.5), q(0.5), mipLevel = level) ==
          colors(level), s"mip level $level selected the wrong packed image")
      assert(runSample(dut, mem, q(0.5), q(0.5), mipLevel = 7) == colors(3),
        "requested mip must clamp to texMaxLevel")
      assert(mem.oobReads == 0, "mip walk fetched outside the packed chain")
    }
  }

  it should "trilinearly blend adjacent packed mip levels and clamp at the last level" in {
    val mem = new TexMem(0x4000L, width = 4, height = 4, levels = 3)
    val green = texel(0, 255, 0)
    val red = texel(255, 0, 0)
    val blue = texel(0, 0, 255)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = true, maxLevel = 2)
      for ((color, level) <- Seq(green, red, blue).zipWithIndex) {
        val (_, w, h) = mem.levelInfo(level)
        for (y <- 0 until h; x <- 0 until w) mem.putLevel(level, x, y, color)
      }
      // 128 is an exact 50/50 Q0.8 level blend; channels truncate exactly.
      assert(runSample(dut, mem, q(0.5), q(0.5), mipLevel = 0, lodFrac = 128) ==
        texel(127, 127, 0), "level 0/1 trilinear blend was wrong")
      assert(runSample(dut, mem, q(0.5), q(0.5), mipLevel = 1, lodFrac = 64) ==
        texel(191, 0, 63), "level 1/2 trilinear blend was wrong")
      assert(runSample(dut, mem, q(0.5), q(0.5), mipLevel = 2, lodFrac = 128) == blue,
        "last mip must not attempt an out-of-range next-level blend")
      assert(runSample(dut, mem, q(0.5), q(0.5), mipLevel = 15, lodFrac = 255) == blue,
        "a request above the upper clamp must also suppress fractional blending")
      assert(mem.oobReads == 0, "trilinear fetch walked outside the packed chain")
    }
  }

  it should "match the software reference across randomized samples and hold results under backpressure" in {
    val mem = new TexMem(0x4000L, width = 16, height = 16)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      for (y <- 0 until 16; x <- 0 until 16)
        mem.put(x, y, texel((x * 211 + y * 37) & 0xff,
          (x * 7 + y * 149) & 0xff, (x * y) & 0xff))

      var seed = 0xf00dcafeL
      def rnd(n: Int): Int = {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        (((seed >>> 33) & 0x7fffffffL) % n).toInt
      }

      // Bit-exact randomized sweep spanning interior, seam, clamped overshoot.
      for (t <- 0 until 20) {
        val u = rnd((1 << 16) + 512).toLong
        val v = rnd((1 << 16) + 512).toLong
        val expect = refSample(mem, clampMode = false, u, v)
        val got = runSample(dut, mem, u, v)
        assert(got == expect,
          f"sample $t u=$u v=$v hw=${got.toHexString} ref=${expect.toHexString}")
      }
      assert(mem.oobReads == 0, "hardware fetched outside the texture")

      // Backpressure regression: stall once the result is visible, confirm
      // the held value matches the reference, release, then run a follow-up
      // sample to prove no cross-request tap leak (the M4b failure class).
      val ua = q(0.25); val va = q(0.25)
      val ub = q(0.75); val vb = q(0.75)
      val gotA = runSample(dut, mem, ua, va, stallCycles = 12)
      val expectA = refSample(mem, clampMode = false, ua, va)
      assert(gotA == expectA,
        f"stalled result hw=${gotA.toHexString} ref=${expectA.toHexString}")
      val gotB = runSample(dut, mem, ub, vb)
      val expectB = refSample(mem, clampMode = false, ub, vb)
      assert(gotB == expectB,
        f"post-stall sample leaked state: hw=${gotB.toHexString} ref=${expectB.toHexString}")
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }
  for (overlap <- Seq(false, true)) {
    it should s"repeat NPOT mip levels and clamp full unsigned coordinates (overlap=$overlap)" in {
      simulate(new TextureUnit(allowPrepared = overlap, overlapPrepared = overlap)) { dut =>
        dut.io.prepared.foreach { p =>
          p.valid.poke(false.B); p.bits.poke(0.U.asTypeOf(p.bits))
        }
        dut.io.sample.valid.poke(false.B); dut.io.result.ready.poke(false.B)
        dut.io.mem.resp.valid.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val mem = new TexMem(0x4000L, 7, 5, levels = 4)
        for (level <- 0 until 4) {
          val (_, w, h) = mem.levelInfo(level)
          for (y <- 0 until h; x <- 0 until w)
            mem.putLevel(level,x,y,texel((x*53+y*17+level*31)&255,
              (x*7+y*73+level*47)&255,(x*29+y*11+level*61)&255))
        }
        def levelSample(level: Int, clamp: Boolean, u: Long, v: Long): Int = {
          val (base, w, h) = mem.levelInfo(level)
          val view = new TexMem(base,w,h)
          for (y <- 0 until h; x <- 0 until w)
            view.put(x,y,mem.words(base+(y.toLong*w+x)*4))
          refSample(view,clamp,u,v)
        }
        def mix(a: Int, b: Int, f: Int): Int =
          Seq(24,16,8,0).foldLeft(0) { (out, shift) =>
            (out << 8) | ((((a >>> shift)&255)*(256-f)+((b >>> shift)&255)*f) >> 8)
          }
        val coordinates = Seq((0L,0L),(0x8000L,0x8000L),
          (0x10000L,0x10001L),(0xffffffffL,0xfffffffeL),(98303L,98305L))
        var checked = 0
        for (clamp <- Seq(false,true); level <- 0 until 4;
             fraction <- Seq(0,128,255); (u,v) <- coordinates) {
          attach(dut,mem,wrapClamp=clamp,maxLevel=3)
          val f = if (level == 3) 0 else fraction
          val expected = mix(levelSample(level,clamp,u,v),
            levelSample(math.min(level+1,3),clamp,u,v),f)
          val got = runSample(dut,mem,u,v,mipLevel=level,lodFrac=fraction,
            stallCycles=3,requestStallCycles=2)
          assert(got == expected,
            s"overlap=$overlap clamp=$clamp mip=$level fraction=$fraction u=$u v=$v")
          checked += 1
        }
        assert(mem.oobReads == 0)
        info(s"NPOT_MIP_SAMPLES=$checked overlap=$overlap")
      }
    }
  }

  for (entries <- Seq(0, 8)) {
    it should s"finish a non-fractional sample without a mix bubble (cache=$entries)" in {
      simulate(new TextureUnit(wordCacheEntries = entries)) { dut =>
        val mem = new TexMem(0x4000L, 2, 2, levels = 2)
        for (y <- 0 until 2; x <- 0 until 2) mem.put(x, y, texel(32, 64, 96))
        mem.putLevel(1, 0, 0, texel(128, 160, 192))
        attach(dut, mem, wrapClamp = true, maxLevel = 1)
        dut.io.invalidateCache.foreach(_.poke(false.B))
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val stats = new SampleStats
        assert(runSample(dut, mem, q(0.5), q(0.5), stats = Some(stats),
          stallCycles = 4) == texel(32, 64, 96))
        assert(stats.responseCycles.nonEmpty)
        assert(stats.resultCycle - stats.responseCycles.last == 2,
          "non-fractional bilinear sample retained a redundant mix cycle")
        // A fractional request still mixes the two registered levels, and a
        // following zero-fraction request must not return the preceding mix.
        assert(runSample(dut, mem, q(0.5), q(0.5), lodFrac = 128,
          stallCycles = 3) == texel(80, 112, 144))
        assert(runSample(dut, mem, q(0.5), q(0.5), stallCycles = 2) == texel(32, 64, 96))
        assert(mem.oobReads == 0)
      }
    }
  }

}
