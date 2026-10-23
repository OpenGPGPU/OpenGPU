package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

private class ClipFloatDivideToken(tagWidth: Int) extends Bundle {
  val response = new ClipFloatResponse(tagWidth)
  val normal = Bool()
  val less = Bool()
  val negative = Bool()
  val exponent = SInt(14.W)
  val divisor = UInt(24.W)
  val remainder = UInt(24.W)
  val fraction = UInt(24.W)
  val rounded = UInt(25.W)
}

object ClipFloatStreamingDivider {
  /** Number of advancing clock edges from input acceptance to output offer. */
  val latency = 29
}

/** Extended-exponent RNE division with one request per cycle, including special
  * and malformed inputs. Twenty-four spatial radix-2 steps produce the 23
  * retained fraction bits, guard bit and exact remainder for sticky rounding.
  * This is a pixel arithmetic building block; it does not replace the scalar
  * ClipFloatArithmetic interface or establish an integrated renderer rate.
  *
  * Backpressure freezes the entire pipeline. Cancellation blocks admission and
  * marks accepted internal tokens, which still drain in order with their
  * computed values. An already offered response is immutable even on cancel.
  */
class ClipFloatStreamingDivider(tagWidth: Int = 8) extends Module {
  require(tagWidth > 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ClipFloatRequest(tagWidth)))
    val out = Decoupled(new ClipFloatResponse(tagWidth))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })

  private val count = ClipFloatStreamingDivider.latency
  private val valid = RegInit(VecInit(Seq.fill(count)(false.B)))
  private val tokens = Reg(Vec(count, new ClipFloatDivideToken(tagWidth)))
  private val advance = !valid.last || io.out.ready
  io.in.ready := advance && !io.cancel
  io.out.valid := valid.last
  io.out.bits := tokens.last.response
  io.busy := valid.asUInt.orR

  private val setup = WireDefault(0.U.asTypeOf(new ClipFloatDivideToken(tagWidth)))
  private val a = io.in.bits.a
  private val b = io.in.bits.b
  private val aZero = a.special === 0.U && !a.significand.orR
  private val bZero = b.special === 0.U && !b.significand.orR
  private val aInf = a.special === 1.U
  private val bInf = b.special === 1.U
  private val invalid = a.special === 2.U || b.special === 2.U ||
    (aInf && bInf) || (aZero && bZero)
  private val malformed = !ClipFloat.legal(a) || !ClipFloat.legal(b) ||
    io.in.bits.operation =/= ClipFloatOperation.divide.U
  setup.response.tag := io.in.bits.tag
  setup.response.value.negative := a.negative ^ b.negative
  setup.negative := a.negative ^ b.negative
  setup.normal := !malformed && !invalid && !aInf && !bInf && !aZero && !bZero
  setup.less := a.significand < b.significand
  setup.exponent := a.exponent.pad(14) - b.exponent.pad(14)
  // Bypass tokens use a harmless 1/1 recurrence, keeping its invariant valid.
  setup.remainder := Mux(setup.normal, a.significand, "h800000".U)
  setup.divisor := Mux(setup.normal, b.significand, "h800000".U)
  when(!setup.normal) { setup.less := false.B }
  when(invalid) {
    setup.response.value.negative := false.B
    setup.response.value.special := 2.U
    setup.response.value.significand := "h400000".U
    setup.response.arithmeticStatus := 16.U
  }.elsewhen(aInf || bZero) {
    setup.response.value.special := 1.U
    setup.response.arithmeticStatus := Mux(!aInf && bZero, 8.U, 0.U)
  }
  when(malformed) {
    setup.response.value := ClipFloat.zero
    setup.response.arithmeticStatus := 0.U
    setup.response.error := 1.U
  }

  private val next = Wire(Vec(count, new ClipFloatDivideToken(tagWidth)))
  next(0) := setup
  for (i <- 1 until count) {
    next(i) := tokens(i - 1)
    next(i).response.cancelled := tokens(i - 1).response.cancelled || io.cancel
  }

  private val normalize = Module(new BlockPrefixAdder(25))
  normalize.io.a := Mux(tokens(0).less, Cat(tokens(0).remainder, 0.U(1.W)), tokens(0).remainder.pad(25))
  normalize.io.b := ~tokens(0).divisor.pad(25)
  normalize.io.carryIn := true.B
  next(1).remainder := normalize.io.sum(23, 0)
  next(1).exponent := tokens(0).exponent - tokens(0).less.asUInt.zext

  for (i <- 2 to 25) {
    val previous = tokens(i - 1)
    val shifted = Cat(previous.remainder, 0.U(1.W))
    val subtract = Module(new BlockPrefixAdder(25))
    subtract.io.a := shifted
    subtract.io.b := ~previous.divisor.pad(25)
    subtract.io.carryIn := true.B
    val bit = subtract.io.sum(25)
    next(i).remainder := Mux(bit, subtract.io.sum(23, 0), shifted(23, 0))
    next(i).fraction := Cat(previous.fraction(22, 0), bit)
  }

  private val candidate = Cat(1.U(1.W), tokens(25).fraction(23, 1))
  private val guard = tokens(25).fraction(0)
  private val sticky = tokens(25).remainder.orR
  next(26).rounded := candidate +& (guard && (sticky || candidate(0)))
  when(tokens(25).normal) {
    next(26).response.arithmeticStatus := (guard || sticky).asUInt
  }

  private val rounded = tokens(26).rounded
  private val exponent = tokens(26).exponent + rounded(24).asUInt.zext
  when(tokens(26).normal) {
    next(27).response.value.negative := tokens(26).negative
    next(27).response.value.special := 0.U
    next(27).response.value.exponent := exponent
    next(27).response.value.significand := Mux(rounded(24), rounded(24, 1), rounded(23, 0))
    when(exponent =/= exponent(9, 0).asSInt) {
      next(27).response.value := ClipFloat.zero
      next(27).response.error := 2.U
    }
  }
  private val packed = ClipFloat.toFp32(tokens(27).response.value)
  next(28).response.fp32 := packed.bits
  next(28).response.packingStatus := packed.status

  when(advance) {
    valid(0) := io.in.fire
    when(io.in.fire) { tokens(0) := next(0) }
    for (i <- 1 until count) {
      valid(i) := valid(i - 1)
      when(valid(i - 1)) { tokens(i) := next(i) }
    }
  }.elsewhen(io.cancel) {
    // The last slot has already been offered and must remain irrevocable.
    for (i <- 0 until count - 1) {
      when(valid(i)) { tokens(i).response.cancelled := true.B }
    }
  }
}
