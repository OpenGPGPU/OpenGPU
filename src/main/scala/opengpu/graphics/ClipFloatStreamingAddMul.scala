package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32FmaLane, Fp32Operation, Fp32Request}

private class ClipFloatFmaMetadata(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val baseExponent = SInt(14.W)
  val alignmentLost = Bool()
  val towardZero = Bool()
  val malformed = Bool()
  val cancelled = Bool()
}

private class ClipFloatFmaPrepared(tagWidth: Int, slotWidth: Int) extends Bundle {
  val request = new Fp32Request(slotWidth)
  val metadata = new ClipFloatFmaMetadata(tagWidth)
}

private class ClipFloatFmaFormation(tagWidth: Int) extends Bundle {
  val response = new ClipFloatResponse(tagWidth)
  val towardZero = Bool()
}

/** Streaming extended-exponent add/subtract/multiply and internal addTowardZero.
  * The existing pipelined FP32 FMA operates on normalized significands; owned
  * metadata retains the extended exponent, user tag and cancellation state.
  * Sixteen reserved result slots cover the arithmetic and response latency so
  * dense traffic can sustain one operation per cycle. Division is rejected.
  *
  * Accepted work is never flushed on cancellation. It drains with computed
  * results and cancelled=true; an already offered response remains immutable.
  */
class ClipFloatStreamingAddMul(tagWidth: Int = 8) extends Module {
  require(tagWidth > 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ClipFloatRequest(tagWidth)))
    val out = Decoupled(new ClipFloatResponse(tagWidth))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val slots = 16
  private val slotWidth = log2Ceil(slots)
  private val core = Module(new Fp32FmaLane(tagWidth = slotWidth, resultEntries = slots))
  core.io.flush := false.B
  private val prepared = Reg(new ClipFloatFmaPrepared(tagWidth, slotWidth))
  private val preparedValid = RegInit(false.B)
  private val metadata = Reg(Vec(slots, new ClipFloatFmaMetadata(tagWidth)))
  private val metadataLive = RegInit(VecInit(Seq.fill(slots)(false.B)))
  private val writeSlot = RegInit(0.U(slotWidth.W))
  private val formation = Reg(new ClipFloatFmaFormation(tagWidth))
  private val formationValid = RegInit(false.B)
  private val output = Reg(new ClipFloatResponse(tagWidth))
  private val outputValid = RegInit(false.B)
  private val outputReady = !outputValid || io.out.ready
  private val formationReady = !formationValid || outputReady
  private val preparedReady = !preparedValid || core.io.in.ready
  io.in.ready := preparedReady && !io.cancel
  io.out.valid := outputValid
  io.out.bits := output
  io.busy := preparedValid || core.io.busy || formationValid || outputValid
  core.io.in.valid := preparedValid
  core.io.in.bits := prepared.request
  core.io.in.bits.tag := writeSlot
  core.io.out.ready := formationReady

  private val prepare = WireDefault(0.U.asTypeOf(new ClipFloatFmaPrepared(tagWidth, slotWidth)))
  private val request = io.in.bits
  private val a = request.a
  private val b = request.b
  private val aZero = a.special === 0.U && !a.significand.orR
  private val bZero = b.special === 0.U && !b.significand.orR
  private val multiply = request.operation === ClipFloatOperation.multiply.U
  private val towardZero = request.operation === ClipFloatOperation.addTowardZero.U
  private val common = Mux(aZero, b.exponent,
    Mux(bZero, a.exponent, Mux(a.exponent > b.exponent, a.exponent, b.exponent)))
  private def aligned(v: ClipFloat): UInt = {
    val gap = (common - v.exponent).asUInt
    val exponent = 127.U(13.W) - gap
    // Preserve the far operand's sign/sticky direction for directed rounding.
    val tiny = Mux(towardZero, Cat(v.negative, 100.U(8.W), 0.U(23.W)),
      Cat(v.negative, 0.U(31.W)))
    Mux(v.special =/= 0.U, ClipFloat.coreBits(v),
      Mux(!v.significand.orR, Cat(v.negative, 0.U(31.W)),
        Mux(gap > 26.U, tiny, Cat(v.negative, exponent(7, 0), v.significand(22, 0)))))
  }
  prepare.request.operation := Mux(multiply, Fp32Operation.mul, Fp32Operation.add)
  prepare.request.roundingMode := Mux(towardZero, 1.U, 0.U)
  prepare.request.operandA := ClipFloat.coreBits(a)
  prepare.request.operandB := Mux(multiply, ClipFloat.coreBits(b), aligned(a))
  prepare.request.operandC := Mux(multiply, 0.U,
    aligned(b) ^ Mux(request.operation === ClipFloatOperation.subtract.U, "h80000000".U, 0.U))
  prepare.metadata.tag := request.tag
  prepare.metadata.baseExponent := Mux(multiply, a.exponent.pad(14) + b.exponent.pad(14), common.pad(14))
  prepare.metadata.alignmentLost := !multiply && a.special === 0.U && b.special === 0.U &&
    !aZero && !bZero && ((common - a.exponent) > 26.S || (common - b.exponent) > 26.S)
  prepare.metadata.towardZero := towardZero
  prepare.metadata.malformed := !ClipFloat.legal(a) || !ClipFloat.legal(b) ||
    request.operation > ClipFloatOperation.addTowardZero.U ||
    request.operation === ClipFloatOperation.divide.U

  when(preparedReady) {
    preparedValid := io.in.fire
    when(io.in.fire) { prepared := prepare }
  }
  when(preparedValid && io.cancel) { prepared.metadata.cancelled := true.B }
  when(io.cancel) {
    for (i <- 0 until slots) {
      when(metadataLive(i)) { metadata(i).cancelled := true.B }
    }
  }
  private val readSlot = core.io.out.bits.tag
  when(core.io.out.fire) {
    assert(metadataLive(readSlot), "streaming clip FMA lost result ownership")
    metadataLive(readSlot) := false.B
  }
  when(core.io.in.fire) {
    assert(!metadataLive(writeSlot) || (core.io.out.fire && readSlot === writeSlot),
      "streaming clip FMA overwrote live metadata")
    metadata(writeSlot) := prepared.metadata
    metadata(writeSlot).cancelled := prepared.metadata.cancelled || io.cancel
    metadataLive(writeSlot) := true.B
    writeSlot := writeSlot + 1.U
  }

  private val owner = metadata(readSlot)
  private val normalized = ClipFloat.fromFp32(core.io.out.bits.result)
  private val combined = owner.baseExponent + normalized.exponent.pad(14)
  private val finiteNonzero = normalized.special === 0.U && normalized.significand.orR
  private val formed = WireDefault(0.U.asTypeOf(new ClipFloatFmaFormation(tagWidth)))
  formed.towardZero := owner.towardZero
  formed.response.value := normalized
  formed.response.tag := owner.tag
  formed.response.cancelled := owner.cancelled || io.cancel
  formed.response.arithmeticStatus := core.io.out.bits.status | owner.alignmentLost.asUInt
  when(finiteNonzero) {
    formed.response.value.exponent := combined
    when(combined < (-512).S || combined > 511.S) {
      formed.response.value := ClipFloat.zero
      formed.response.error := 2.U
    }
  }
  when(owner.malformed) {
    formed.response.value := ClipFloat.zero
    formed.response.arithmeticStatus := 0.U
    formed.response.error := 1.U
  }
  when(formationReady) {
    formationValid := core.io.out.valid
    when(core.io.out.fire) { formation := formed }
  }.elsewhen(io.cancel && formationValid) {
    formation.response.cancelled := true.B
  }
  private val packed = ClipFloat.toFp32(formation.response.value, formation.towardZero)
  when(outputReady) {
    outputValid := formationValid
    when(formationValid) {
      output := formation.response
      output.fp32 := packed.bits
      output.packingStatus := packed.status
      output.cancelled := formation.response.cancelled || io.cancel
    }
  }
}
