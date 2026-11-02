package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

/** Adapt quad evaluation to the existing checked patch reader and scalar
  * geometry-CU executor. The quad vertex index is retained locally because
  * the isoline executor's correlation tag has only one vertex bit. Only one
  * evaluation is outstanding; accepted requests drain across cancellation.
  */
class TessellationQuadCuEvaluator(config: GpuConfig, idWidth: Int, numComputeUnits: Int) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new QuadEvaluationRequest)); val response = Decoupled(new QuadEvaluationResponse)
    val count = Input(UInt(6.W)); val selections = Input(Vec(61, new IsolinePatchInputSelection))
    val commandId = Input(UInt(idWidth.W)); val kernel = Input(new KernelLaunch(config)); val outputComponents = Input(UInt(7.W))
    val inspect = Decoupled(new TessellationPatchAccess); val reply = Flipped(Decoupled(new TessellationContextReply))
    val launch = Decoupled(new TaggedKernelLaunch(config, idWidth)); val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(idWidth)))
    val event = Flipped(Vec(numComputeUnits, Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W)); val taskIds = Input(Vec(numComputeUnits, UInt(idWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits, Bool())); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val reader = Module(new IsolinePatchInputReader(config, idWidth))
  private val executor = Module(new IsolineGeometryCuExecutor(config, idWidth, numComputeUnits))
  private val active = RegInit(false.B); private val vertex = Reg(UInt(2.W))
  private val cancelled = RegInit(false.B)
  private val stop = io.cancel || (active && cancelled)
  reader.io.request.valid := io.request.valid && !active
  reader.io.request.bits.taskId := io.request.bits.taskId
  reader.io.request.bits.sequence := io.request.bits.sequence
  reader.io.request.bits.vertex := 0.U
  reader.io.request.bits.tessCoord := io.request.bits.tessCoord
  io.request.ready := !active && reader.io.request.ready
  when(io.request.fire) { active := true.B; vertex := io.request.bits.vertex; cancelled := io.cancel }
  when(active && io.cancel) { cancelled := true.B }
  reader.io.count := io.count; reader.io.selections := io.selections
  reader.io.commandId := io.commandId; reader.io.kernel := io.kernel; reader.io.outputComponents := io.outputComponents
  reader.io.cancel := stop; io.inspect <> reader.io.inspect; reader.io.reply <> io.reply
  executor.io.task <> reader.io.task; reader.io.result <> executor.io.response
  // An already offered reader task must be accepted before cancellation reaches
  // the executor; otherwise a held task and an idle executor can deadlock.
  executor.io.cancel := stop && executor.io.busy
  io.launch <> executor.io.launch; io.geometryTask := executor.io.geometryTask
  executor.io.kernelCompletion <> io.kernelCompletion; executor.io.event <> io.event
  executor.io.owner := io.owner; executor.io.taskIds := io.taskIds; io.cancelCu := executor.io.cancelCu
  io.response.valid := active && reader.io.response.valid
  io.response.bits.taskId := reader.io.response.bits.taskId
  io.response.bits.sequence := reader.io.response.bits.sequence(13, 0)
  io.response.bits.vertex := vertex
  io.response.bits.failed := reader.io.response.bits.failed || reader.io.response.bits.vertex =/= 0.U ||
    reader.io.response.bits.sequence(15, 14).orR
  io.response.bits.components := reader.io.response.bits.components
  reader.io.response.ready := active && io.response.ready
  when(io.response.fire) { active := false.B }
  io.busy := active || reader.io.busy || executor.io.busy
}
