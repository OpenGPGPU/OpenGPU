package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics._

class TessellationJointGeometryRangeCaptureRequest(idWidth: Int) extends Bundle {
  val job = new TessellationJointGeometryJobCapture(idWidth)
  val directory = new TessellationJointDirectoryCapture(idWidth)
  val cursorInstance = UInt(33.W); val cursorPatch = UInt(33.W)
}
class TessellationJointGeometryRangeCaptureCompletion[D <: Data](
    sequenceType: D,
    p: VertexShaderStageConfig,
    idWidth: Int
) extends Bundle {
  val base = new TessellationJointRangeCaptureCompletion(sequenceType, p, idWidth)
  val attachment = new TessellationJointGeometryAttachmentCompletion(p, idWidth)
}

class TessellationJointGeometryRangeCaptureController[D <: Data](
    config: GpuConfig,
    p: VertexShaderStageConfig,
    idWidth: Int,
    domain: Int,
    sequenceType: D,
    enabled: Boolean = false
) extends Module {
  require(
    config.xLen == 32 && config.lanes == 16 && config.warps == 8 && idWidth > 0 && idWidth <= 12 && domain >= 0 && domain < 3
  )
  require(p.input.attributes == 16 && p.input.bindings == 16 && p.varyings == 16)
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationJointGeometryRangeCaptureRequest(idWidth)))
    val completion = Decoupled(new TessellationJointGeometryRangeCaptureCompletion(sequenceType, p, idWidth))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private val source = Module(
    new TessellationJointRangeCaptureController(config, p, idWidth, domain, sequenceType, enabled)
  )
  private val geometry = Module(new TessellationJointGeometryAttachmentReader(config, p, idWidth, enabled))
  private object State extends ChiselEnum {
    val idle, sourceStart, sourceOffer, sourceRun, sourceDrain, geometryStart, geometryOffer, geometryRun, finish,
        complete = Value
  }
  private object Owner extends ChiselEnum { val none, source, geometry = Value }
  private val state = RegInit(State.idle); private val owner = RegInit(Owner.none)
  private val q = Reg(new TessellationJointGeometryRangeCaptureRequest(idWidth))
  private val result = Reg(new TessellationJointGeometryRangeCaptureCompletion(sequenceType, p, idWidth))
  private val cancelled = RegInit(false.B); private val pending = RegInit(false.B)
  private val stop = cancelled || io.cancel
  private def field(d: Data, names: String*): Data = names.foldLeft(d) { case (r, n) =>
    r.asInstanceOf[Record].elements(n)
  }
  source.io.submit.valid := state === State.sourceOffer
  NativeTypedGraphicsLayout.adapt(q.job.request, source.io.submit.bits.job.request)
  NativeTypedGraphicsLayout.adapt(q.job.job, source.io.submit.bits.job.job)
  source.io.submit.bits.directory := q.directory
  source.io.submit.bits.cursorInstance := q.cursorInstance; source.io.submit.bits.cursorPatch := q.cursorPatch
  source.io.completion.ready := state === State.sourceRun; source.io.cancel := owner === Owner.source && stop
  geometry.io.submit.valid := state === State.geometryOffer
  geometry.io.submit.bits.job := q.job; geometry.io.submit.bits.directory := q.directory
  private val seq = result.base.gnt.sequence
  geometry.io.submit.bits.stateHandle := field(seq, "template", "source", "state").asUInt
  geometry.io.submit.bits.tesOutputComponents := field(
    seq,
    "template",
    "source",
    "viewport",
    "system",
    "patch",
    "outputComponents"
  ).asUInt
  private val point =
    field(seq, "template", "source", "viewport", "system", "patch", "control", "patch", "pointMode").asUInt.asBool
  geometry.io.submit.bits.inputTopology := Mux(point, 0.U, (if (domain == 0) 1 else 4).U)
  geometry.io.submit.bits.fragmentVaryingSlots := field(seq, "template", "source", "viewport", "varyingSlots").asUInt
  geometry.io.completion.ready := state === State.geometryRun; geometry.io.cancel := owner === Owner.geometry && stop
  io.memoryRequest.valid := false.B; io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryResponse.ready := false.B
  source.io.memoryRequest.ready := false.B; geometry.io.memoryRequest.ready := false.B
  source.io.memoryResponse.valid := false.B; geometry.io.memoryResponse.valid := false.B
  source.io.memoryResponse.bits := io.memoryResponse.bits; geometry.io.memoryResponse.bits := io.memoryResponse.bits
  when(owner === Owner.source) {
    io.memoryRequest <> source.io.memoryRequest; source.io.memoryResponse <> io.memoryResponse
  }
  when(owner === Owner.geometry) {
    io.memoryRequest <> geometry.io.memoryRequest; geometry.io.memoryResponse <> io.memoryResponse
  }
  when(io.memoryRequest.fire) { assert(owner =/= Owner.none && !pending); pending := true.B }
  when(io.memoryResponse.fire) { assert(owner =/= Owner.none && pending); pending := false.B }
  when(io.memoryRequest.valid) { assert(io.memoryRequest.bits.transactionId === 0.U && !io.memoryRequest.bits.isWrite) }
  private val drained = !source.io.busy && !geometry.io.busy && !pending &&
    !source.io.memoryRequest.valid && !geometry.io.memoryRequest.valid
  io.submit.ready := state === State.idle && drained && owner === Owner.none && !io.cancel
  io.completion.valid := state === State.complete; io.completion.bits := result
  io.busy := state =/= State.idle; io.drained := drained && owner === Owner.none
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(io.submit.fire) {
    q := io.submit.bits; result := 0.U.asTypeOf(result); result.base.commandId := io.submit.bits.job.request.commandId
    result.attachment.commandId := io.submit.bits.job.request.commandId; cancelled := false.B
    if (!enabled) { result.base.status := 6.U; state := State.finish }
    else {
      when(
        io.submit.bits.job.request.domain =/= domain.U ||
          !TessellationJointJobLayout.requestLegal(io.submit.bits.job.request) ||
          !TessellationJointGeometryJobLayout.legal(io.submit.bits.job.job, io.submit.bits.job.request)
      ) {
        result.base.status := 1.U; result.base.faultStage := 4.U
        result.base.faultAddress := io.submit.bits.job.request.view.base; state := State.finish
      }.otherwise { state := State.sourceStart }
    }
  }
  when(state === State.sourceStart) {
    when(stop) { result.base.status := 4.U; state := State.finish }.otherwise { state := State.sourceOffer }
  }
  when(source.io.submit.fire) {
    assert(drained && owner === Owner.none); owner := Owner.source; state := State.sourceRun
  }
  when(source.io.completion.fire) { result.base := source.io.completion.bits; state := State.sourceDrain }
  when(state === State.sourceDrain && drained) {
    owner := Owner.none
    when(result.base.status =/= 0.U || stop) {
      when(result.base.status === 0.U) { result.base.status := 4.U }; state := State.finish
    }
      .otherwise { state := State.geometryStart }
  }
  when(state === State.geometryStart) {
    when(stop) { result.base.status := 4.U; state := State.finish }.otherwise { state := State.geometryOffer }
  }
  when(geometry.io.submit.fire) {
    assert(drained && owner === Owner.none); owner := Owner.geometry; state := State.geometryRun
  }
  when(geometry.io.completion.fire) {
    result.attachment := geometry.io.completion.bits
    val status = geometry.io.completion.bits.status
    when(status =/= 0.U) {
      result.base.status := status; result.base.faultStage := 5.U
      result.base.faultAddress := geometry.io.completion.bits.faultAddress
    }
    state := State.finish
  }
  when(state === State.finish && drained) {
    owner := Owner.none
    val total =
      result.base.sequenceReadBytes.pad(65) +& result.base.sourceReadBytes.pad(65) +& result.attachment.readBytes.pad(
        65
      )
    assert(total < 16384.U, "fixed metadata read inventory overflow")
    result.base.totalReadBytes := total
    when(result.base.status === 0.U && stop) { result.base.status := 4.U }
    state := State.complete
  }
  when(state === State.complete) { assert(drained && owner === Owner.none) }
  when(io.completion.fire) { state := State.idle }
}
