package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Restricted instruction interpreter for generated shader ABI checks. This
  * checks uploaded machine-code dataflow, not CU/native-endpoint execution.
  */
class NativeProductShaderProgramsSpec extends AnyFlatSpec {
  private def bits(x: Float): Int = java.lang.Float.floatToRawIntBits(x)
  private def value(x: Int): Float = java.lang.Float.intBitsToFloat(x)
  private class Machine(code: Vector[BigInt], memory: mutable.LongMap[Int],
    base: Long, warp: Int, count: Int) {
    val x = Array.fill(32)(0); val v = Array.fill(32, 16)(0)
    private val params = Array.fill(16, 16)(0)
    private var vl = count; var captures = 0; var samples = 0
    x(1) = base.toInt; x(8) = warp; x(9) = count
    private def addr(raw: Int): Long = raw.toLong & 0xffffffffL
    private def read(a: Long): Int = memory.getOrElse(a, throw new AssertionError(f"uninitialized read0x$a%x"))
    private def texel(u: Float, t: Float): Int = {
      val a = Math.floorMod(math.floor(u.toDouble * 8).toInt, 8)
      val b = Math.floorMod(math.floor(t.toDouble * 8).toInt, 8)
      NativeProductWorkloads.textureRgba8(b * 8 + a)
    }
    def run(): Unit = {
      var ended = false
      for (raw <- code if !ended) {
        val w = raw.toInt; val op = w & 127; val rd = (w >>> 7) & 31
        val f = (w >>> 12) & 7; val a = (w >>> 15) & 31; val b = (w >>> 20) & 31
        val f6 = w >>> 26
        op match {
          case 0x37 => x(rd) = w & 0xfffff000
          case 0x13 if f == 0 => x(rd) = x(a) + (w >> 20)
          case 0x13 if f == 1 => x(rd) = x(a) << b
          case 0x33 if f == 0 => x(rd) = x(a) + x(b)
          case 3 if f == 2 => x(rd) = read(addr(x(a) + (w >> 20)))
          case 7 if f == 6 =>
            for (l <- 0 until vl) v(rd)(l) = read(addr(x(a) + l * 4))
          case 0x27 if f == 6 =>
            for (l <- 0 until vl) memory(addr(x(a) + l * 4)) = v(rd)(l)
          case 0x57 if f == 7 =>
            assert((w >>> 20) == 16); vl = x(a).min(16)
          case 0x57 if f == 4 && f6 == 0x17 =>
            for (l <- 0 until vl) v(rd)(l) = x(a)
          case 0x57 if f == 1 && f6 == 0x24 =>
            for (l <- 0 until vl) v(rd)(l) = bits((value(v(a)(l)) * value(v(b)(l))).toFloat)
          case 0x2b if f6 == 3 =>
            for (l <- 0 until vl) { params(l)(f * 2) = v(a)(l); params(l)(f * 2 + 1) = v(b)(l); captures += 1 }
          case 0x2b if f6 == 4 =>
            for (l <- 0 until vl) {
              assert(v(a)(l) == 0 && v(b)(l) == 1 && params(l).drop(2).forall(_ == 0))
              val color = texel(value(params(l)(0)), value(params(l)(1)))
              v(rd)(l) = bits(((color >>> (f * 8)) & 255) / 255f); samples += 1
            }
          case 0x73 if raw == NativeProductShaderPrograms.cease => ended = true
          case _ => fail(f"unsupported shader instruction0x$w%08x")
        }
        x(0) = 0
      }
      assert(ended && x(1) == base.toInt && x(8) == warp && x(9) == count && x(30) == 0 && x(31) == 0)
    }
  }
  behavior of "native product shader machine code"
  it should "copy both attributes across full warps and a partial warp without touching inactive output lanes" in {
    val n = 128; val s = n * 4; val base = 0x600000L; val guard = 0xdeadbeef
    val ram = mutable.LongMap.empty[Int]
    for (a <- 0 until 19; l <- 0 until n) ram(base + a * s + l * 4) = guard
    def input(a: Int, l: Int): Int = (a << 24) | (l * 7919 + 17)
    for (a <- 0 until 8; l <- 0 until n) ram(base + a * s + l * 4) = input(a, l)
    for (warp <- 0 until 37 by 16) new Machine(NativeProductShaderPrograms.vertex(n), ram, base, warp, (37 - warp).min(16)).run()
    for (a <- 0 until 8; l <- 0 until n)
      assert(ram(base + (11 + a) * s + l * 4) == (if (l < 37) input(a, l) else guard))
    assert(NativeProductShaderPrograms.vertexPrefixBytes(n) == VertexShaderStageConfig().prefixBytes(n, 2, 1))
  }
  it should "capture interpolated UVs and emit modulated RGBA with correct controls across full and reduced capacities" in {
    for (n <- Seq(8, 128); textured <- Seq(false, true)) {
      val s = n * 4; val base = 0x600000L; val active = n - 3; val guard = 0xdeadbeef
      val ram = mutable.LongMap.empty[Int]
      for (a <- 0 until 16; l <- 0 until n) ram(base + a * s + l * 4) = guard
      val modulation = Vector(0.25f, 0.5f, 0.75f, 1f)
      def u(l: Int): Float = (l % 9 - 1) / 8f
      def t(l: Int): Float = (l % 7) / 8f
      for (l <- 0 until n) { ram(base + 6 * s + l * 4) = bits(u(l)); ram(base + 7 * s + l * 4) = bits(t(l)) }
      modulation.zipWithIndex.foreach { case (v, c) => ram(base + 16 * s + c * 4) = bits(v) }
      var samples = 0; var captures = 0
      for (warp <- 0 until active by 16) {
        val m = new Machine(NativeProductShaderPrograms.fragment(n, textured), ram, base, warp, (active - warp).min(16))
        m.run(); samples += m.samples; captures += m.captures
      }
      assert(samples == (if (textured) 4 * active else 0))
      assert(captures == (if (textured) 8 * active else 0))
      for (l <- 0 until n; c <- 0 until 4) {
        val x = Math.floorMod(math.floor(u(l).toDouble * 8).toInt, 8)
        val y = Math.floorMod(math.floor(t(l).toDouble * 8).toInt, 8)
        val raw = NativeProductWorkloads.textureRgba8(y * 8 + x)
        val tex = if (textured) ((raw >>> (c * 8)) & 255) / 255f else 1f
        assert(ram(base + (10 + c) * s + l * 4) == (if (l < active) bits(tex * modulation(c)) else guard))
      }
      for (l <- 0 until n) {
        assert(ram(base + 14 * s + l * 4) == (if (l < active) 0 else guard))
        assert(ram(base + 15 * s + l * 4) == (if (l < active) 5 else guard))
      }
      assert(NativeProductShaderPrograms.fragmentPrefixBytes(n) == FragmentShaderConfig().prefixBytes(n, 1, 1))
    }
  }
}
