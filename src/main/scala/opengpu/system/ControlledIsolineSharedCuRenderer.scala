package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

class ControlledIsolineSharedCuRendererTask(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int) extends Bundle {
  val renderer = new ControlledIsolineRendererTask(p)
  val commandId = UInt(idWidth.W); val kernel = new KernelLaunch(config)
}

/** Evaluate controlled isolines on the shared CU and map their output into
  * assembled primitives. The owner reserves the command ID and pins shader
  * resources until completion. Primitives are provisional until success.
  */
class ControlledIsolineSharedCuRenderer(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int, numComputeUnits: Int) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new ControlledIsolineSharedCuRendererTask(config,p,idWidth)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val sealInput = Flipped(Decoupled(Bool())); val access = Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive = Flipped(Decoupled(new TessellationBarrierArrival)); val finish = Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier = Decoupled(new TessellationBarrierRelease); val control = Decoupled(new TessellationBarrierResult)
    val inspect = Flipped(Decoupled(new TessellationPatchAccess)); val reply = Decoupled(new TessellationContextReply)
    val out = Decoupled(new AssembledPrimitive(p)); val completion = Decoupled(new ControlledIsolineRendererCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
    val launch = Decoupled(new TaggedKernelLaunch(config,idWidth)); val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(idWidth)))
    val event = Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W)); val taskIds = Input(Vec(numComputeUnits,UInt(idWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits,Bool()))
  })
  private val renderer = Module(new TessellationControlledIsolineRenderer(p))
  private val executor = Module(new IsolineGeometryCuExecutor(config,idWidth,numComputeUnits))
  private val active = RegInit(false.B); private val cancelled = RegInit(false.B)
  private val commandId = Reg(UInt(idWidth.W)); private val kernel = Reg(new KernelLaunch(config)); private val components = Reg(UInt(7.W))
  renderer.io.task.valid := !active && io.task.valid && !io.cancel
  renderer.io.task.bits := io.task.bits.renderer
  io.task.ready := !active && !io.cancel && renderer.io.task.ready
  when(io.task.fire) { active := true.B; cancelled := false.B; commandId := io.task.bits.commandId
    kernel := io.task.bits.kernel; components := io.task.bits.renderer.outputComponents }
  when(active && io.cancel && !renderer.io.completion.valid) { cancelled := true.B }
  renderer.io.cancel := active && (io.cancel || cancelled)
  io.opened <> renderer.io.opened; renderer.io.load <> io.load; renderer.io.sealInput <> io.sealInput
  renderer.io.access <> io.access; renderer.io.arrive <> io.arrive; renderer.io.finish <> io.finish
  io.releaseBarrier <> renderer.io.releaseBarrier; io.control <> renderer.io.control
  renderer.io.inspect <> io.inspect; io.reply <> renderer.io.reply
  executor.io.task.valid := renderer.io.request.valid; renderer.io.request.ready := executor.io.task.ready
  executor.io.task.bits.evaluation := renderer.io.request.bits; executor.io.task.bits.commandId := commandId
  executor.io.task.bits.kernel := kernel; executor.io.task.bits.outputComponents := components
  renderer.io.response <> executor.io.response
  // Keep an accepted request serviceable through cancellation and drain its response.
  executor.io.cancel := active && (io.cancel || cancelled) && executor.io.busy
  io.out <> renderer.io.out; io.completion <> renderer.io.completion; io.busy := active
  when(io.completion.fire) { active := false.B }
  io.launch <> executor.io.launch; io.geometryTask := executor.io.geometryTask
  executor.io.kernelCompletion <> io.kernelCompletion; executor.io.event <> io.event
  executor.io.owner := io.owner; executor.io.taskIds := io.taskIds; io.cancelCu := executor.io.cancelCu
}
