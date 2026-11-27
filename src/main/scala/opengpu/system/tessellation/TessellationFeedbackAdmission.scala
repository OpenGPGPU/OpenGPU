package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.{NativeBinnedCompletionInfo, TileBinJobCompletion, TileJobDescriptorRequest}

/** Convert the host's held reset into one request after the control owner has released any irrevocable command offer.
  * The owner drains that request.
  */
class TessellationFeedbackResetGate extends Module {
  val io = IO(new Bundle {
    val resetActive = Input(Bool()); val coreRequest = Input(Bool()); val pulse = Output(Bool())
  })
  private val sent = RegInit(false.B)
  io.pulse := io.coreRequest && !sent
  when(!io.resetActive) { sent := false.B }
  when(io.pulse) { sent := true.B }
}

/** Preserve a job's query scope from host acceptance through ownership transfer and completion, including failures
  * before either renderer is offered work.
  */
class TessellationQueryAdmissionFence extends Module {
  val io = IO(new Bundle {
    val accepted = Input(Bool()); val context = Input(UInt(32.W)); val root = Input(UInt(32.W))
    val renderIdle = Input(Bool()); val admissionBusy = Input(Bool())
    val legacyFault = Flipped(Valid(UInt(3.W))); val tessFault = Flipped(Valid(UInt(3.W)))
    val queryIdle = Output(Bool()); val scopeContext = Output(UInt(32.W)); val scopeRoot = Output(UInt(32.W))
    val fault = Valid(new opengpu.graphics.VisibilityQueryJobFault)
  })
  private val context = Reg(UInt(32.W)); private val root = Reg(UInt(32.W))
  when(io.accepted) { context := io.context; root := io.root }
  io.scopeContext := context; io.scopeRoot := root
  io.queryIdle := io.renderIdle && !io.admissionBusy && !io.accepted
  assert(!(io.legacyFault.valid && io.tessFault.valid), "one host job may complete at a time")
  io.fault.valid := io.legacyFault.valid || io.tessFault.valid
  io.fault.bits.context := context; io.fault.bits.root := root
  io.fault.bits.status := Mux(io.tessFault.valid, io.tessFault.bits, io.legacyFault.bits)
}

/** Own one accepted native job while feedback object checkpoints move between drained renderer owners. A failed
  * transfer produces an ordered completion; no renderer sees the job. The enclosing renderer's ready must be
  * independent of valid; an offer is presented only when it can be accepted.
  */
class TessellationFeedbackAdmission(idWidth: Int, jointTessellation: Boolean = false) extends Module {
  require(idWidth > 0 && idWidth <= 12)
  val io = IO(new Bundle {
    val submit = Flipped(Vec(2, Decoupled(new TileJobDescriptorRequest(idWidth))))
    val render = Vec(2, Decoupled(new TileJobDescriptorRequest(idWidth)))
    val failure = Vec(2, Decoupled(new TileBinJobCompletion(idWidth)))
    val failureDetails = Output(Vec(2, Vec(NativeBinnedCompletionInfo.wordCount, UInt(32.W))))
    val jointSubmit = Option.when(jointTessellation)(Flipped(Decoupled(new TessellationJointJobRequest(idWidth))))
    val jointRender = Option.when(jointTessellation)(Decoupled(new TessellationJointJobRequest(idWidth)))
    val jointFailure = Option.when(jointTessellation)(Decoupled(new TessellationJointNativeCompletion(idWidth)))
    val switch = Decoupled(Bool())
    val switchReply = Flipped(Decoupled(UInt(3.W)))
    val selected = Input(Bool())
    val poisoned = Input(Bool())
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object Phase extends ChiselEnum { val idle, select, switching, offer, failed = Value }
  private val phase = RegInit(Phase.idle)
  private val target = Reg(Bool())
  private val request = Reg(new TileJobDescriptorRequest(idWidth))
  private val jointRequest = Option.when(jointTessellation)(Reg(new TessellationJointJobRequest(idWidth)))
  private val cancelled = RegInit(false.B)
  private val code = Reg(UInt(4.W))
  private val canAccept = phase === Phase.idle && !io.cancel
  private val canOffer = phase === Phase.offer && !cancelled && !io.cancel && !io.poisoned
  private val tessellationValid = io.jointSubmit.map(_.valid).getOrElse(io.submit(1).valid)
  private val tessellationAccepted = io.jointSubmit.map(_.fire).getOrElse(io.submit(1).fire)
  io.busy := phase =/= Phase.idle
  io.submit(0).ready := canAccept && !tessellationValid
  io.submit(1).ready := canAccept && !jointTessellation.B
  for (index <- 0 until 2) {
    val supported = (index == 0 || !jointTessellation).B
    io.render(index).valid := supported && canOffer && target === index.U && io.render(index).ready
    io.render(index).bits := request
    io.failure(index).valid := supported && phase === Phase.failed && target === index.U
    io.failure(index).bits := 0.U.asTypeOf(io.failure(index).bits)
    io.failure(index).bits.commandId := request.commandId
    io.failure(index).bits.status := code
    io.failure(index).bits.frontendStatus := code
    val magic = if (index == 0) NativeBinnedCompletionInfo.magic else TessellationNativeCompletionInfo.magic
    io.failureDetails(index) := VecInit(
      Seq(magic.U(32.W), Cat(0.U(27.W), Mux(code === 4.U, 0.U(2.W), 3.U(2.W)), code(2, 0))) ++
        Seq.fill(NativeBinnedCompletionInfo.wordCount - 2)(0.U(32.W))
    )
  }
  io.jointSubmit.foreach { submit =>
    submit.ready := canAccept
    when(submit.fire) { jointRequest.get := submit.bits }
    val render = io.jointRender.get
    render.valid := canOffer && target && render.ready
    render.bits := jointRequest.get
    val failure = io.jointFailure.get
    failure.valid := phase === Phase.failed && target
    failure.bits := 0.U.asTypeOf(failure.bits)
    failure.bits.commandId := jointRequest.get.commandId
    failure.bits.identity := jointRequest.get.identity
    failure.bits.status := code(2, 0)
    failure.bits.faultStage := Mux(code === 1.U, 1.U, Mux(code === 4.U, 0.U, 7.U))
    failure.bits.faultAddress := Mux(code === 1.U, jointRequest.get.view.base, 0.U)
  }

  when(io.submit(0).fire || tessellationAccepted) {
    assert(!(io.submit(0).fire && tessellationAccepted), "one host command must select one renderer")
    target := tessellationAccepted
    when(io.submit(0).fire || io.submit(1).fire) {
      request := Mux(io.submit(1).fire, io.submit(1).bits, io.submit(0).bits)
    }
    cancelled := false.B
    val malformedJoint = io.jointSubmit
      .map(submit => submit.fire && !TessellationJointJobLayout.requestLegal(submit.bits))
      .getOrElse(false.B)
    when(malformedJoint) {
      code := 1.U
      phase := Phase.failed
    }.otherwise { phase := Phase.select }
  }
  when(phase =/= Phase.idle && io.cancel) { cancelled := true.B }
  // The coordinator's ready is independent of valid. Offer the switch
  // when it can fire, and drain its reply after any cancellation.
  io.switch.valid := phase === Phase.select && !cancelled && !io.cancel && !io.poisoned &&
    io.selected =/= target && io.switch.ready
  io.switch.bits := target
  io.switchReply.ready := phase === Phase.switching
  when(phase === Phase.select) {
    when(cancelled || io.cancel) { code := 4.U; phase := Phase.failed }
      .elsewhen(io.poisoned) { code := 7.U; phase := Phase.failed }
      .elsewhen(io.selected === target) { phase := Phase.offer }
      .elsewhen(io.switch.fire) { phase := Phase.switching }
  }
  when(io.switchReply.fire) {
    when(cancelled || io.cancel || io.switchReply.bits === 4.U) { code := 4.U; phase := Phase.failed }
      .elsewhen(io.switchReply.bits =/= 0.U) { code := 7.U; phase := Phase.failed }
      .otherwise { phase := Phase.offer }
  }
  when(phase === Phase.offer) {
    when(cancelled || io.cancel) { code := 4.U; phase := Phase.failed }
      .elsewhen(io.poisoned) { code := 7.U; phase := Phase.failed }
      .elsewhen(io.render(0).fire || io.jointRender.map(_.fire).getOrElse(io.render(1).fire)) { phase := Phase.idle }
  }
  when(io.failure(0).fire || io.jointFailure.map(_.fire).getOrElse(io.failure(1).fire)) {
    phase := Phase.idle
  }
}
