package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

class IsolineGeometryCuRendererTask(config: GpuConfig, p: VertexShaderStageConfig, commandIdWidth: Int) extends Bundle {
  val renderer = new IsolineRendererTask(p)
  val commandId = UInt(commandIdWidth.W); val kernel = new KernelLaunch(config)
}

/** Connect one isoline patch to the scalar geometry CU service. Shader code,
  * command ID and resources are owned until the patch completion is accepted.
  * Accepted primitives are provisional until successful completion.
  */
class IsolineGeometryCuRenderer(config: GpuConfig, p: VertexShaderStageConfig, commandIdWidth: Int, numComputeUnits: Int) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new IsolineGeometryCuRendererTask(config,p,commandIdWidth)))
    val primitive = Decoupled(new AssembledPrimitive(p))
    val completion = Decoupled(new IsolineRendererCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
    val launch = Decoupled(new TaggedKernelLaunch(config,commandIdWidth))
    val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event = Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W)); val taskIds = Input(Vec(numComputeUnits,UInt(commandIdWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits,Bool()))
  })
  private val renderer = Module(new TessellationIsolineRendererHandoff(p))
  private val executor = Module(new IsolineGeometryCuExecutor(config,commandIdWidth,numComputeUnits))
  private val active = RegInit(false.B); private val cancelled = RegInit(false.B)
  private val commandId = Reg(UInt(commandIdWidth.W)); private val kernel = Reg(new KernelLaunch(config))
  private val outputComponents = Reg(UInt(7.W))
  renderer.io.begin.valid := !active && io.task.valid && !io.cancel
  renderer.io.begin.bits := io.task.bits.renderer
  io.task.ready := !active && !io.cancel && renderer.io.begin.ready
  when(io.task.fire) {
    active := true.B; cancelled := false.B; commandId := io.task.bits.commandId
    kernel := io.task.bits.kernel; outputComponents := io.task.bits.renderer.outputComponents
  }
  when(active && io.cancel && !renderer.io.completion.valid) { cancelled := true.B }
  renderer.io.cancel := io.cancel || (active && cancelled)
  executor.io.task.valid := renderer.io.request.valid
  renderer.io.request.ready := executor.io.task.ready
  executor.io.task.bits.evaluation := renderer.io.request.bits
  executor.io.task.bits.commandId := commandId; executor.io.task.bits.kernel := kernel
  executor.io.task.bits.outputComponents := outputComponents
  renderer.io.response <> executor.io.response
  // A request already offered by the renderer must be accepted even during
  // cancellation. Once accepted, cancel the live CU and drain its response.
  executor.io.cancel := active && (io.cancel || cancelled) && executor.io.busy
  io.primitive <> renderer.io.out
  io.completion.valid := renderer.io.completion.valid; renderer.io.completion.ready := io.completion.ready
  io.completion.bits := renderer.io.completion.bits
  when(cancelled && renderer.io.completion.bits.status === 2.U) { io.completion.bits.status := 4.U }
  io.busy := active
  when(io.completion.fire) { active := false.B }
  io.launch <> executor.io.launch; io.geometryTask := executor.io.geometryTask
  executor.io.kernelCompletion <> io.kernelCompletion; executor.io.event <> io.event
  executor.io.owner := io.owner; executor.io.taskIds := io.taskIds; io.cancelCu := executor.io.cancelCu
}
