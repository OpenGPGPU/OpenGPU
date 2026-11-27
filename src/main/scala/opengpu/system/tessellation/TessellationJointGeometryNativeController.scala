package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics._

class TessellationJointGeometryRangeExecution[D <: Data](sequenceType: D, p: VertexShaderStageConfig, idWidth: Int)
    extends Bundle {
  val captured = new TessellationJointGeometryRangeCaptureCompletion(sequenceType, p, idWidth)
  val directory = new TessellationJointDirectoryCapture(idWidth)
  val cursor = new TessellationJointGeometryCursor
}
class TessellationJointGeometryRangeExecutionRetirement extends Bundle {
  val result = new TessellationVertexTypedRangeResult
  val geometry = new TessellationJointGeometryRangeSummary
}
class TessellationJointGeometryFrameExecutionRetirement extends Bundle {
  val base = new TessellationJointFrameExecutionRetirement
  val geometryReportValid = Bool()
  val actualGsPublished = UInt(158.W); val received = UInt(161.W); val dropped = UInt(161.W)
  val admitted = UInt(97.W); val tasks = UInt(64.W)
  val nextGsSequenceExclusive = UInt(65.W); val nextBinSequence = UInt(33.W)
}

class TessellationJointGeometryNativeController[D <: Data](
    config: GpuConfig,
    p: VertexShaderStageConfig,
    idWidth: Int,
    domain: Int,
    sequenceType: D,
    engines: Int = 4,
    pagedCapacity: Int = 65536,
    enabled: Boolean = false
) extends Module {
  require(config.lanes == 16 && config.warps == 8 && config.xLen == 32 && domain >= 0 && domain < 3)
  require(p.input.attributes == 16 && p.input.bindings == 16 && p.varyings == 16)
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationJointGeometryJobRequest(idWidth)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val frame = Decoupled(new TessellationJointFrameExecution)
    val end = Decoupled(Bool());
    val frameRetired = Flipped(Decoupled(new TessellationJointGeometryFrameExecutionRetirement))
    val frameBusy = Input(Bool()); val frameDrained = Input(Bool()); val cancelFrame = Output(Bool())
    val range = Decoupled(new TessellationJointGeometryRangeExecution(sequenceType, p, idWidth))
    val rangeRetired = Flipped(Decoupled(new TessellationJointGeometryRangeExecutionRetirement))
    val rangeBusy = Input(Bool()); val rangeDrained = Input(Bool()); val cancelRange = Output(Bool())
    val completion = Decoupled(new TessellationJointGeometryNativeCompletion(idWidth))
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val job = Module(new TessellationJointGeometryJobCaptureReader(config, idWidth, enabled))
  private val frame = Module(new TessellationJointFrameCaptureReader(config, idWidth, engines, pagedCapacity, enabled))
  private val directory = Module(new TessellationJointDirectoryCaptureReader(config, idWidth, enabled))
  private val capture = Module(
    new TessellationJointGeometryRangeCaptureController(config, p, idWidth, domain, sequenceType, enabled)
  )
  private object State extends ChiselEnum {
    val idle, jobOffer, jobRun, jobDrain, frameOffer, frameRun, frameDrain, imageStart, imageOffer, directoryOffer,
        directoryRun, rangeCaptureStart, rangeCaptureOffer, rangeCaptureRun, rangeCaptureDrain, rangeStart, rangeOffer,
        rangeRun, rangeDrain, releaseEntry, directoryDrain, endOffer, frameDrainAll, finish, report = Value
  }
  private object MemoryOwner extends ChiselEnum { val none, job, frame, directory, range = Value }
  private val state = RegInit(State.idle); private val owner = RegInit(MemoryOwner.none)
  private val request = Reg(new TessellationJointGeometryJobRequest(idWidth))
  private val accepted = Reg(new TessellationJointGeometryJobCapture(idWidth))
  private val capturedFrame = Reg(new TessellationJointFrameCaptureCompletion(idWidth))
  private val entry = Reg(new TessellationJointDirectoryCapture(idWidth))
  private val capturedRange = Reg(new TessellationJointGeometryRangeCaptureCompletion(sequenceType, p, idWidth))
  private val rangeOffer = Reg(new TessellationJointGeometryRangeExecution(sequenceType, p, idWidth))
  private val result = Reg(new TessellationJointGeometryNativeCompletion(idWidth))
  private val pending = RegInit(false.B); private val cancelled = RegInit(false.B)
  private val frameOwned = RegInit(false.B); private val frameDone = RegInit(false.B);
  private val ended = RegInit(false.B)
  private val directoryOwned = RegInit(false.B); private val directoryDone = RegInit(false.B)
  private val captureOwned = RegInit(false.B); private val rangeOwned = RegInit(false.B)
  private val rangeDone = RegInit(false.B); private val rangeSuccess = RegInit(false.B)
  private val candidatePublished = Reg(UInt(64.W)); private val candidatePatches = Reg(UInt(32.W))
  private val cursor = Reg(new TessellationJointGeometryCursor)
  private val candidateCursor = Reg(new TessellationJointGeometryCursor)
  private val seedTask = Reg(UInt(32.W)); private val seedPrimitive = Reg(UInt(32.W))
  private val seedInput = Reg(UInt(64.W)); private val seedOutput = Reg(UInt(64.W))
  private val invocationSeen = RegInit(false.B)
  private val observed = Reg(new TessellationJointGeometryRangeSummary)
  private val cursorInstance = RegInit(0.U(33.W)); private val cursorPatch = RegInit(0.U(33.W))
  private val stop = cancelled || io.cancel || result.base.status =/= 0.U
  private val active = state =/= State.idle && state =/= State.report
  when(active && io.cancel) { cancelled := true.B }

  private class Fault extends Bundle { val status = UInt(3.W); val stage = UInt(3.W); val address = UInt(32.W) }
  private val faults = Wire(Vec(8, Valid(new Fault))); faults := 0.U.asTypeOf(faults)
  private def fault(index: Int, valid: Bool, status: UInt, stage: Int, address: UInt = 0.U): Unit = {
    faults(index).valid := valid && status =/= 0.U; faults(index).bits.status := status
    faults(index).bits.stage := stage.U; faults(index).bits.address := address
  }
  private val serious = VecInit(faults.map(f => f.valid && f.bits.status =/= 4.U))
  when(active && (result.base.status === 0.U || result.base.status === 4.U)) {
    when(serious.asUInt.orR) {
      val f = PriorityMux(serious, faults.map(_.bits))
      result.base.status := f.status; result.base.faultStage := f.stage; result.base.faultAddress := f.address
    }.elsewhen(
      result.base.status === 0.U && (io.cancel || faults.map(f => f.valid && f.bits.status === 4.U).reduce(_ || _))
    ) {
      result.base.status := 4.U; result.base.faultStage := 0.U; result.base.faultAddress := 0.U
    }
  }

  job.io.submit.valid := state === State.jobOffer; job.io.submit.bits := request
  job.io.cancel := owner === MemoryOwner.job && stop
  job.io.completion.ready := state === State.jobRun
  frame.io.submit.valid := state === State.frameOffer
  NativeTypedGraphicsLayout.adapt(accepted.request, frame.io.submit.bits.request)
  NativeTypedGraphicsLayout.adapt(accepted.job, frame.io.submit.bits.job)
  frame.io.cancel := owner === MemoryOwner.frame && stop
  frame.io.completion.ready := state === State.frameRun
  directory.io.submit.valid := state === State.directoryOffer
  NativeTypedGraphicsLayout.adapt(accepted.request, directory.io.submit.bits.request)
  NativeTypedGraphicsLayout.adapt(accepted.job, directory.io.submit.bits.job)
  directory.io.cancel := directoryOwned && stop
  directory.io.completion.ready := directoryOwned && !directoryDone
  directory.io.entry.ready := state === State.releaseEntry && !capture.io.busy && !rangeOwned &&
    !io.rangeBusy && io.rangeDrained && !pending
  capture.io.submit.valid := state === State.rangeCaptureOffer
  capture.io.submit.bits.job := accepted; capture.io.submit.bits.directory := entry
  capture.io.submit.bits.cursorInstance := cursorInstance; capture.io.submit.bits.cursorPatch := cursorPatch
  capture.io.cancel := captureOwned && stop
  capture.io.completion.ready := state === State.rangeCaptureRun

  io.frame.valid := state === State.imageOffer
  io.frame.bits.descriptor := capturedFrame.frame; io.frame.bits.clearOnly := capturedFrame.clearOnly
  io.cancelFrame := frameOwned && stop
  io.range.valid := state === State.rangeOffer; io.range.bits := rangeOffer
  io.cancelRange := rangeOwned && stop
  io.rangeRetired.ready := rangeOwned && !rangeDone
  io.end.valid := state === State.endOffer; io.end.bits := true.B
  io.frameRetired.ready := frameOwned && !frameDone

  io.memoryRequest.valid := false.B; io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryResponse.ready := false.B
  job.io.memoryRequest.ready := false.B; frame.io.memoryRequest.ready := false.B
  directory.io.memoryRequest.ready := false.B; capture.io.memoryRequest.ready := false.B
  job.io.memoryResponse.valid := false.B; frame.io.memoryResponse.valid := false.B
  directory.io.memoryResponse.valid := false.B; capture.io.memoryResponse.valid := false.B
  job.io.memoryResponse.bits := io.memoryResponse.bits; frame.io.memoryResponse.bits := io.memoryResponse.bits
  directory.io.memoryResponse.bits := io.memoryResponse.bits; capture.io.memoryResponse.bits := io.memoryResponse.bits
  when(owner === MemoryOwner.job) {
    io.memoryRequest <> job.io.memoryRequest; job.io.memoryResponse <> io.memoryResponse
  }
  when(owner === MemoryOwner.frame) {
    io.memoryRequest <> frame.io.memoryRequest; frame.io.memoryResponse <> io.memoryResponse
  }
  when(owner === MemoryOwner.directory) {
    io.memoryRequest <> directory.io.memoryRequest; directory.io.memoryResponse <> io.memoryResponse
  }
  when(owner === MemoryOwner.range) {
    io.memoryRequest <> capture.io.memoryRequest; capture.io.memoryResponse <> io.memoryResponse
  }
  when(io.memoryRequest.fire) {
    assert(!pending && owner =/= MemoryOwner.none, "joint metadata tag zero was reused"); pending := true.B
  }
  when(io.memoryResponse.fire) {
    assert(pending && owner =/= MemoryOwner.none, "joint metadata reply has no owner"); pending := false.B
  }
  when(io.memoryRequest.valid) { assert(io.memoryRequest.bits.transactionId === 0.U && !io.memoryRequest.bits.isWrite) }
  private val readersIdle = !job.io.busy && !frame.io.busy && !directory.io.busy && !capture.io.busy && !pending &&
    !job.io.memoryRequest.valid && !frame.io.memoryRequest.valid && !directory.io.memoryRequest.valid && !capture.io.memoryRequest.valid
  private val executionIdle = !io.rangeBusy && io.rangeDrained && !io.frameBusy && io.frameDrained
  private val fullyDrained = readersIdle && executionIdle
  io.submit.ready := state === State.idle && owner === MemoryOwner.none && fullyDrained && !io.cancel
  io.completion.valid := state === State.report; io.completion.bits := result
  io.busy := state =/= State.idle; io.drained := (state === State.idle || state === State.report) && fullyDrained

  fault(0, job.io.completion.fire, job.io.completion.bits.status, 1, job.io.completion.bits.faultAddress)
  fault(1, frame.io.completion.fire, frame.io.completion.bits.status, 2, frame.io.completion.bits.faultAddress)
  fault(
    2,
    directory.io.completion.fire,
    directory.io.completion.bits.status,
    3,
    directory.io.completion.bits.faultAddress
  )
  fault(
    3,
    capture.io.completion.fire,
    capture.io.completion.bits.base.status,
    4,
    capture.io.completion.bits.base.faultAddress
  )
  private val retired = io.rangeRetired.bits
  private val nextSequence = rangeOffer.cursor.tes.sequence +& retired.result.published
  private val max64 = (BigInt(1) << 64).U; private val max32 = (BigInt(1) << 32).U
  private def cursorLegal(c: TessellationJointGeometryCursor): Bool =
    c.tes.sequence <= max64 && c.tes.primitiveId <= max32 && c.gsTaskId <= max32 &&
      c.gsInputPrimitiveId <= max32 && c.inputSequence <= max64 && c.outputSequence <= max64 && c.binSequence <= max32
  private val next = retired.geometry.nextCursor
  private val start = rangeOffer.cursor
  private val geometryLegal = cursorLegal(next) && next.gsTaskId === (start.gsTaskId +& retired.result.published) &&
    next.inputSequence === (start.inputSequence +& retired.result.published) &&
    next.outputSequence >= start.outputSequence &&
    (next.outputSequence - start.outputSequence).pad(159) === retired.geometry.actualGsPublished.pad(159) &&
    next.binSequence >= start.binSequence &&
    (next.binSequence - start.binSequence).pad(98) === retired.geometry.admitted.pad(98) &&
    retired.geometry.received.pad(162) === (retired.geometry.admitted.pad(162) +& retired.geometry.dropped.pad(162)) &&
    retired.geometry.tasks === retired.result.range.completed
  private val retirementLegal = !retired.result.admissionRejected &&
    retired.result.range.completed === entry.entry.patchCount &&
    nextSequence <= max64 && next.tes.sequence === nextSequence && geometryLegal
  private val rangeStatus =
    Mux(retired.result.range.status =/= 0.U, retired.result.range.status, Mux(retirementLegal, 0.U, 3.U))
  fault(4, io.rangeRetired.fire, rangeStatus, 5)
  private val frameIdentity = io.frameRetired.bits.base.identity.asUInt === request.identity.asUInt
  private val fr = io.frameRetired.bits
  private val frameGeometryLegal = fr.geometryReportValid &&
    fr.actualGsPublished === observed.actualGsPublished && fr.received === observed.received &&
    fr.dropped === observed.dropped && fr.admitted === observed.admitted && fr.tasks === observed.tasks &&
    fr.nextGsSequenceExclusive === observed.nextCursor.outputSequence && fr.nextBinSequence === observed.nextCursor.binSequence
  private val frameStatus = Mux(
    !frameIdentity,
    3.U,
    Mux(
      fr.base.status === 0.U &&
        ((!ended && !io.end.fire) || (!stop && !frameGeometryLegal)),
      3.U,
      fr.base.status
    )
  )
  fault(5, io.frameRetired.fire, frameStatus, 6)
  private val attachment = capturedRange.attachment.captured
  private val first = entry.entry.flags(0)
  private val staticSeedsLegal = Mux(
    first,
    attachment.firstInputPrimitiveId === 0.U,
    invocationSeen && attachment.gsTaskIdBase === seedTask && attachment.firstInputPrimitiveId === seedPrimitive &&
      attachment.inputSequence === seedInput && attachment.firstOutputSequence === seedOutput
  )
  private val capturedCursorDomain = cursorLegal(candidateCursor) && staticSeedsLegal
  fault(6, state === State.rangeStart && !stop && !capturedCursorDomain, 3.U, 7)
  private val progressPublished = result.base.published +& candidatePublished
  private val progressLegal = !progressPublished(64)
  fault(
    7,
    state === State.rangeDrain && rangeDone && !io.rangeBusy && io.rangeDrained && rangeSuccess && !stop && !progressLegal,
    3.U,
    7
  )

  when(io.submit.fire) {
    request := io.submit.bits; accepted := 0.U.asTypeOf(accepted); capturedFrame := 0.U.asTypeOf(capturedFrame)
    entry := 0.U.asTypeOf(entry); capturedRange := 0.U.asTypeOf(capturedRange); rangeOffer := 0.U.asTypeOf(rangeOffer)
    result := 0.U.asTypeOf(result); result.base.commandId := io.submit.bits.commandId;
    result.base.identity := io.submit.bits.identity
    cancelled := false.B; frameOwned := false.B; frameDone := false.B; ended := false.B
    directoryOwned := false.B; directoryDone := false.B; captureOwned := false.B; rangeOwned := false.B;
    rangeDone := false.B
    rangeSuccess := false.B; cursor := 0.U.asTypeOf(cursor); candidateCursor := 0.U.asTypeOf(candidateCursor)
    observed := 0.U.asTypeOf(observed); invocationSeen := false.B
    seedTask := 0.U; seedPrimitive := 0.U; seedInput := 0.U; seedOutput := 0.U
    cursorInstance := 0.U; cursorPatch := 0.U; candidatePublished := 0.U; candidatePatches := 0.U;
    state := State.jobOffer
    if (enabled) {
      when(io.submit.bits.domain =/= domain.U) {
        result.base.status := 1.U; result.base.faultStage := 1.U; state := State.finish
      }
    }
  }
  when(job.io.submit.fire) { owner := MemoryOwner.job; state := State.jobRun }
  when(job.io.completion.fire) {
    accepted := job.io.completion.bits.captured; result.base.readBytes := job.io.completion.bits.readBytes;
    state := State.jobDrain
  }
  when(state === State.jobDrain && !job.io.busy && !pending) {
    owner := MemoryOwner.none
    when(stop) { state := State.finish }.otherwise { state := State.frameOffer }
  }
  when(frame.io.submit.fire) {
    assert(owner === MemoryOwner.none && !pending); owner := MemoryOwner.frame; state := State.frameRun
  }
  when(frame.io.completion.fire) {
    capturedFrame := frame.io.completion.bits
    result.base.readBytes := result.base.readBytes + frame.io.completion.bits.readBytes;
    result.base.clearOnly := frame.io.completion.bits.clearOnly; state := State.frameDrain
  }
  when(state === State.frameDrain && !frame.io.busy && !pending) {
    owner := MemoryOwner.none
    when(stop) { state := State.finish }.otherwise { state := State.imageStart }
  }
  when(state === State.imageStart) { when(stop) { state := State.finish }.otherwise { state := State.imageOffer } }
  when(io.frame.fire) { frameOwned := true.B; state := State.directoryOffer }
  when(directory.io.submit.fire) {
    assert(owner === MemoryOwner.none && !pending); directoryOwned := true.B; owner := MemoryOwner.directory;
    state := State.directoryRun
  }
  when(directory.io.completion.fire) {
    directoryDone := true.B
    result.base.readBytes := result.base.readBytes + directory.io.completion.bits.readBytes
    result.base.capturedEntries := directory.io.completion.bits.capturedEntries
  }
  when(state === State.directoryRun) {
    when(directory.io.entry.valid && !directory.io.memoryRequest.valid && !pending) {
      entry := directory.io.entry.bits; owner := MemoryOwner.none; state := State.rangeCaptureStart
    }.elsewhen(directoryDone && !directory.io.busy && !pending) {
      owner := MemoryOwner.none; state := State.directoryDrain
    }
  }
  when(state === State.rangeCaptureStart) {
    when(stop) { state := State.releaseEntry }.otherwise { state := State.rangeCaptureOffer }
  }
  when(capture.io.submit.fire) {
    assert(owner === MemoryOwner.none && !pending)
    captureOwned := true.B; owner := MemoryOwner.range; state := State.rangeCaptureRun
  }
  when(capture.io.completion.fire) {
    capturedRange := capture.io.completion.bits
    result.base.readBytes := result.base.readBytes + capture.io.completion.bits.base.totalReadBytes;
    state := State.rangeCaptureDrain
  }
  private val viewport = capturedRange.base.gnt.sequence
    .asInstanceOf[Record]
    .elements("template")
    .asInstanceOf[Record]
    .elements("source")
    .asInstanceOf[Record]
    .elements("viewport")
    .asInstanceOf[Record]
  when(state === State.rangeCaptureDrain && !capture.io.busy && !pending) {
    owner := MemoryOwner.none; captureOwned := false.B
    candidateCursor := cursor
    candidateCursor.tes.sequence := Mux(
      entry.entry.flags(0),
      viewport.elements("firstSequence").asUInt,
      cursor.tes.sequence
    )
    candidateCursor.tes.primitiveId := Mux(
      entry.entry.flags(0),
      viewport.elements("firstPrimitiveId").asUInt,
      cursor.tes.primitiveId
    )
    when(first) {
      candidateCursor.gsTaskId := attachment.gsTaskIdBase
      candidateCursor.gsInputPrimitiveId := attachment.firstInputPrimitiveId
      candidateCursor.inputSequence := attachment.inputSequence
      candidateCursor.outputSequence := attachment.firstOutputSequence
    }
    candidateCursor.binSequence := cursor.binSequence
    when(stop) { state := State.releaseEntry }.otherwise { state := State.rangeStart }
  }
  when(state === State.rangeStart) {
    when(stop || !capturedCursorDomain) { state := State.releaseEntry }.otherwise {
      rangeOffer.captured := capturedRange; rangeOffer.directory := entry; rangeOffer.cursor := candidateCursor
      when(first) {
        invocationSeen := true.B; seedTask := attachment.gsTaskIdBase
        seedPrimitive := attachment.firstInputPrimitiveId; seedInput := attachment.inputSequence
        seedOutput := attachment.firstOutputSequence
      }
      state := State.rangeOffer
    }
  }
  when(io.range.fire) { rangeOwned := true.B; rangeDone := false.B; rangeSuccess := false.B; state := State.rangeRun }
  when(io.rangeRetired.fire) {
    rangeDone := true.B; rangeSuccess := rangeStatus === 0.U
    candidateCursor := next
    observed.actualGsPublished := observed.actualGsPublished + retired.geometry.actualGsPublished
    observed.received := observed.received + retired.geometry.received
    observed.dropped := observed.dropped + retired.geometry.dropped
    observed.admitted := observed.admitted + retired.geometry.admitted
    observed.tasks := observed.tasks + retired.geometry.tasks
    when(retired.geometry.tasks =/= 0.U) { observed.nextCursor := next }
    candidatePublished := retired.result.published; candidatePatches := retired.result.range.completed
    state := State.rangeDrain
  }
  when(state === State.rangeDrain && rangeDone && !io.rangeBusy && io.rangeDrained) {
    rangeOwned := false.B
    when(rangeSuccess && !stop && progressLegal && !faults.map(_.valid).reduce(_ || _)) {
      cursor := candidateCursor; cursorInstance := capturedRange.base.nextInstance;
      cursorPatch := capturedRange.base.nextPatch
      result.base.retiredRanges := result.base.retiredRanges + 1.U;
      result.base.retiredPatches := result.base.retiredPatches + candidatePatches
      result.base.published := progressPublished(63, 0)
    }
    state := State.releaseEntry
  }
  when(directory.io.entry.fire) {
    assert(!capture.io.busy && !rangeOwned && !io.rangeBusy && io.rangeDrained && !pending)
    owner := MemoryOwner.directory; state := State.directoryRun
  }
  when(state === State.directoryDrain && !directory.io.busy && !pending) {
    directoryOwned := false.B; owner := MemoryOwner.none; state := Mux(frameOwned, State.endOffer, State.finish)
  }
  when(io.end.fire) { ended := true.B; state := State.frameDrainAll }
  when(io.frameRetired.fire) {
    frameDone := true.B
    result.base.storedBytes := fr.base.storedBytes; result.base.resolvedBytes := fr.base.resolvedBytes
    result.geometryReportValid := frameIdentity && fr.geometryReportValid
    result.actualGsPublished := fr.actualGsPublished; result.received := fr.received; result.dropped := fr.dropped
    result.admitted := fr.admitted; result.geometryTasks := fr.tasks
    result.nextGsSequenceExclusive := fr.nextGsSequenceExclusive
    result.nextGsSequence := fr.nextGsSequenceExclusive(63, 0); result.nextBinSequence := fr.nextBinSequence
  }
  when(
    state === State.frameDrainAll && frameDone && !io.frameBusy && io.frameDrained && !io.rangeBusy && io.rangeDrained
  ) {
    frameOwned := false.B; state := State.finish
  }
  when(state === State.finish && fullyDrained) { assert(owner === MemoryOwner.none); state := State.report }
  when(io.completion.fire) { state := State.idle }
}
