package opengpu.graphics

import chisel3._
import chisel3.util._

class Rgba8WeightedInput(tagWidth: Int) extends Bundle {
  /** Exact sum(byte * Q16 weight), with the four weights summing to65536. */
  val numerators = Vec(4, UInt(24.W))
  val tag = UInt(tagWidth.W)
  val cancelled = Bool()
}
class Rgba8NormalizedResult(tagWidth: Int) extends Bundle {
  val values = Vec(4, UInt(32.W))
  val tag = UInt(tagWidth.W)
  val cancelled = Bool()
}

/** Correctly rounded positive FP32 n/(255*65536), for0<=n<=255*65536.
  * Five elastic stages sustain one complete RGBA group per cycle. Normalize
  * the integer first, then divide its32-bit significand by255 exactly using
  * byte folding. The odd divisor has no halfway remainder, so nearest-even
  * rounding reduces to remainder>=128. Zero is handled explicitly.
  * A cancel pulse poisons every unoffered row, including blocked stages; the
  * already offered public result remains immutable. Accepted rows still drain.
  */
class Rgba8WeightedToFp32(tagWidth: Int = 32) extends Module {
  require(tagWidth > 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Rgba8WeightedInput(tagWidth)))
    val out = Decoupled(new Rgba8NormalizedResult(tagWidth))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val stages = 5
  private val valid = RegInit(VecInit(Seq.fill(stages)(false.B)))
  private val ready = Wire(Vec(stages, Bool()))
  private val tags = Reg(Vec(stages, UInt(tagWidth.W)))
  private val cancelled = Reg(Vec(stages, Bool()))
  private val zero = Reg(Vec(stages, Vec(4, Bool())))
  private val exponent = Reg(Vec(stages, Vec(4, UInt(8.W))))
  private val raw = Reg(Vec(4, UInt(24.W)))
  private val leadingZeros = Reg(Vec(4, UInt(5.W)))
  private val normalized = Reg(Vec(4, UInt(32.W)))
  private val digitSum = Reg(Vec(4, UInt(10.W)))
  private val quotientBase = Reg(Vec(4, UInt(25.W)))
  private val quotient = Reg(Vec(4, UInt(24.W)))
  private val remainder = Reg(Vec(4, UInt(8.W)))
  private val result = Reg(Vec(4, UInt(32.W)))
  ready(stages - 1) := !valid(stages - 1) || io.out.ready
  for(i <- (0 until stages - 1).reverse) ready(i) := !valid(i) || ready(i + 1)
  io.in.ready := ready(0)
  io.out.valid := valid(stages - 1)
  io.out.bits.tag := tags(stages - 1)
  io.out.bits.cancelled := cancelled(stages - 1)
  for(c <- 0 until 4) io.out.bits.values(c) := Mux(cancelled(stages - 1), 0.U, result(c))
  io.busy := valid.asUInt.orR

  for(i <- 0 until stages) {
    val supplying = if(i == 0) io.in.valid else valid(i - 1)
    when(ready(i)) {
      valid(i) := supplying
      when(supplying) {
        tags(i) := (if(i == 0) io.in.bits.tag else tags(i - 1))
        cancelled(i) := (if(i == 0) io.in.bits.cancelled else cancelled(i - 1)) || io.cancel
        if(i > 0) zero(i) := zero(i - 1)
        if(i > 1) exponent(i) := exponent(i - 1)
        for(c <- 0 until 4) {
          if(i == 0) {
            val n = io.in.bits.numerators(c)
            assert(n <= (255 * 65536).U, "weighted RGBA8 numerator exceeds its admitted unit sum")
            raw(c) := n
            leadingZeros(c) := PriorityEncoder(Reverse(n))
            zero(0)(c) := n === 0.U
          } else if(i == 1) {
            val n = (raw(c) << leadingZeros(c))(23, 0)
            val adjust = n(23, 16).andR
            normalized(c) := Mux(adjust, Cat(0.U(1.W), n, 0.U(7.W)), Cat(n, 0.U(8.W)))
            exponent(i)(c) := 126.U(8.W) - leadingZeros(c) + adjust.asUInt
          } else if(i == 2) {
            val n = normalized(c)
            val bytes = (0 until 4).map(b => n(8*b+7, 8*b))
            digitSum(c) := (bytes(0) +& bytes(1)) +& (bytes(2) +& bytes(3))
            // n =255*((n>>8)+(n>>16)+(n>>24)) + sum(bytes).
            val a = (n >> 8).pad(25); val b = (n >> 16).pad(25); val d = (n >> 24).pad(25)
            val sum = a ^ b ^ d
            val carry = ((a & b) | (a & d) | (b & d)) << 1
            quotientBase(c) := (sum + carry)(24, 0)
          } else if(i == 3) {
            val correction = PopCount(VecInit(Seq(255, 510, 765, 1020).map(v => digitSum(c) >= v.U)))
            val rest = digitSum(c) - ((correction << 8) - correction)
            quotient(c) := (quotientBase(c) +& correction)(23, 0)
            remainder(c) := rest(7, 0)
          } else {
            val rounded = quotient(c) +& (remainder(c) >= 128.U).asUInt
            val roundedExponent = exponent(i - 1)(c) + rounded(24).asUInt
            result(c) := Mux(zero(i - 1)(c), 0.U, Cat(0.U(1.W), roundedExponent, rounded(22, 0)))
          }
        }
      }
    }
    if(i < stages - 1) {
      when(valid(i) && !ready(i) && io.cancel) { cancelled(i) := true.B }
    }
  }
}
