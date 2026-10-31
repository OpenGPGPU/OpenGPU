package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

class IsolineGeometryCuTask(config: GpuConfig, commandIdWidth: Int) extends Bundle {
  val evaluation = new IsolineEvaluationRequest
  val commandId = UInt(commandIdWidth.W); val kernel = new KernelLaunch(config)
  val outputComponents = UInt(7.W)
}

/** Execute one coordinate through the existing scalar geometry instruction ABI.
  * The caller reserves commandId and keeps program resources alive through the
  * response. The shader reads gl_TessCoord through input vertex 0 components
  * 0..2, writes output components and emits exactly one point.
  */
class IsolineGeometryCuExecutor(config: GpuConfig, commandIdWidth: Int, numComputeUnits: Int) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new IsolineGeometryCuTask(config, commandIdWidth)))
    val response = Decoupled(new IsolineEvaluationResponse)
    val cancel = Input(Bool()); val busy = Output(Bool())
    val launch = Decoupled(new TaggedKernelLaunch(config,commandIdWidth))
    val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event = Flipped(Vec(numComputeUnits, Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W)); val taskIds = Input(Vec(numComputeUnits,UInt(commandIdWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits,Bool()))
  })
  private val idle :: offer :: running :: result :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val saved = Reg(new IsolineGeometryCuTask(config,commandIdWidth))
  private val response = Reg(new IsolineEvaluationResponse)
  private val seen = RegInit(false.B); private val duplicate = RegInit(false.B)
  private val cancelled = RegInit(false.B)
  private val scheduler = Module(new GeometrySharedCuScheduler(config,commandIdWidth,numComputeUnits))
  io.task.ready := state === idle && !io.cancel
  io.response.valid := state === result; io.response.bits := response; io.busy := state =/= idle
  scheduler.io.task.valid := state === offer
  scheduler.io.task.bits := 0.U.asTypeOf(scheduler.io.task.bits)
  scheduler.io.task.bits.commandId := saved.commandId; scheduler.io.task.bits.kernel := saved.kernel
  scheduler.io.task.bits.geometry.taskId := saved.evaluation.taskId
  scheduler.io.task.bits.geometry.primitiveId := saved.evaluation.sequence
  scheduler.io.task.bits.geometry.inputVertices := 1.U; scheduler.io.task.bits.geometry.inputComponents := 3.U
  for (i <- 0 until 3) scheduler.io.task.bits.geometry.vertices(0).components(i) := saved.evaluation.tessCoord(i)
  scheduler.io.task.bits.geometry.invocations := 1.U; scheduler.io.task.bits.geometry.topology := GeometryOutputTopology.points
  scheduler.io.task.bits.geometry.maxVertices := 1.U; scheduler.io.task.bits.geometry.outputComponents := saved.outputComponents
  scheduler.io.cancel := state === running && (io.cancel || cancelled)
  io.launch <> scheduler.io.launch; io.geometryTask := scheduler.io.geometryTask
  scheduler.io.kernelCompletion <> io.kernelCompletion; scheduler.io.event <> io.event
  scheduler.io.owner := io.owner; scheduler.io.taskIds := io.taskIds; io.cancelCu := scheduler.io.cancelCu
  scheduler.io.primitive.ready := state === running
  scheduler.io.completion.ready := state === running
  when(io.task.fire) {
    saved := io.task.bits; response := 0.U.asTypeOf(response)
    response.taskId := io.task.bits.evaluation.taskId; response.sequence := io.task.bits.evaluation.sequence
    response.vertex := io.task.bits.evaluation.vertex; seen := false.B; duplicate := false.B; cancelled := false.B
    state := offer
  }
  when(state =/= idle && state =/= result && io.cancel) { cancelled := true.B }
  when(scheduler.io.task.fire) { state := running }
  when(scheduler.io.primitive.fire) {
    when(seen || scheduler.io.primitive.bits.vertexCount =/= 1.U ||
      scheduler.io.primitive.bits.taskId =/= saved.evaluation.taskId ||
      scheduler.io.primitive.bits.primitiveId =/= saved.evaluation.sequence) { duplicate := true.B }
    response.components := scheduler.io.primitive.bits.vertices(0).components; seen := true.B
  }
  when(scheduler.io.completion.fire) {
    val p = scheduler.io.primitive.bits
    val badConcurrent = scheduler.io.primitive.fire && (seen || p.vertexCount =/= 1.U ||
      p.taskId =/= saved.evaluation.taskId || p.primitiveId =/= saved.evaluation.sequence)
    response.failed := scheduler.io.completion.bits.status =/= GeometryInvocationStatus.success ||
      !(seen || scheduler.io.primitive.fire) || duplicate || badConcurrent || cancelled || io.cancel
    state := result
  }
  when(io.response.fire) { state := idle }
}
