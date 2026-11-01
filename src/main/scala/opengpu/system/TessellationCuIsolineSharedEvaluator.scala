package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, KernelCompletion, TaggedKernelLaunch, TaggedKernelCompletion}
import opengpu.graphics._

class TessellationCuIsolineSharedTask(config: GpuConfig, idWidth: Int) extends Bundle {
  val control = new TessellationCuIsolineTask(config)
  val commandId = UInt(idWidth.W); val evaluationKernel = new KernelLaunch(config)
  val outputComponents = UInt(7.W)
  val patchInputCount = UInt(6.W); val patchInputs = Vec(61, new IsolinePatchInputSelection)
}

/** Retain control outputs through checked patch reads and CU evaluation.
  * The caller reserves both CU interfaces and commandId and pins resources
  * through completion; launches and accepted traffic must drain on cancellation.
  * The two interfaces can be multiplexed onto the same CU after control ends.
  */
class TessellationCuIsolineSharedEvaluator(config: GpuConfig, idWidth: Int, numComputeUnits: Int) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationCuIsolineSharedTask(config, idWidth)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val seal = Flipped(Decoupled(Bool())); val inspect = Flipped(Decoupled(new TessellationPatchAccess))
    val reply = Decoupled(new TessellationContextReply)
    val controlKernel = Decoupled(new KernelLaunch(config)); val bases = Output(Vec(config.warps, UInt(5.W)))
    val controlCompletion = Flipped(Decoupled(new KernelCompletion)); val controlCancel = Output(Bool()); val controlTerminal = Output(Bool())
    val access = Flipped(Decoupled(new TessellationWarpPatchRequest(config.warps, config.lanes)))
    val accessResult = Decoupled(new TessellationWarpPatchResult(config.warps, config.lanes))
    val park = Flipped(Decoupled(new TessellationWarpPark(config.warps, config.lanes)))
    val resume = Decoupled(new TessellationWarpResume(config.warps)); val finish = Flipped(Decoupled(UInt(5.W)))
    val launch = Decoupled(new TaggedKernelLaunch(config, idWidth)); val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(idWidth)))
    val event = Flipped(Vec(numComputeUnits, Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W)); val taskIds = Input(Vec(numComputeUnits, UInt(idWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits, Bool()))
    val out = Decoupled(new EvaluatedIsolinePrimitive); val completion = Decoupled(new TessellationCuIsolineCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val patch = Module(new TessellationCuIsolinePatch(config))
  private val reader = Module(new IsolinePatchInputReader(config, idWidth))
  private val executor = Module(new IsolineGeometryCuExecutor(config, idWidth, numComputeUnits))
  private val active = RegInit(false.B); private val cancelled = RegInit(false.B)
  private val saved = Reg(new TessellationCuIsolineSharedTask(config, idWidth))
  patch.io.task.valid := !active && io.task.valid && !io.cancel; patch.io.task.bits := io.task.bits.control
  io.task.ready := !active && !io.cancel && patch.io.task.ready
  when(io.task.fire) { active := true.B; cancelled := false.B; saved := io.task.bits }
  when(active && io.cancel && !patch.io.completion.valid) { cancelled := true.B }
  private val stop = active && (io.cancel || cancelled)
  patch.io.cancel := stop
  io.opened <> patch.io.opened; patch.io.load <> io.load; patch.io.seal <> io.seal
  io.controlKernel <> patch.io.kernel; io.bases := patch.io.bases; patch.io.kernelCompletion <> io.controlCompletion
  io.controlCancel := patch.io.cancelCu; io.controlTerminal := patch.io.terminalCu
  patch.io.access <> io.access; io.accessResult <> patch.io.accessResult
  patch.io.park <> io.park; io.resume <> patch.io.resume; patch.io.finish <> io.finish

  // Lock stalled offers and route the one outstanding response to its issuer.
  private val locked = RegInit(false.B); private val lockedInternal = Reg(Bool())
  private val preferInternal = RegInit(true.B); private val internalReply = RegInit(false.B)
  private val chooseInternal = Mux(locked, lockedInternal, reader.io.inspect.valid && (!io.inspect.valid || preferInternal))
  patch.io.inspect.valid := Mux(chooseInternal, reader.io.inspect.valid, io.inspect.valid)
  patch.io.inspect.bits := Mux(chooseInternal, reader.io.inspect.bits, io.inspect.bits)
  reader.io.inspect.ready := chooseInternal && patch.io.inspect.ready
  io.inspect.ready := !chooseInternal && patch.io.inspect.ready
  when(patch.io.inspect.valid && !patch.io.inspect.ready && !locked) { locked := true.B; lockedInternal := chooseInternal }
  when(patch.io.inspect.fire) { locked := false.B; preferInternal := !chooseInternal
    when(chooseInternal) { internalReply := true.B } }
  reader.io.reply.valid := patch.io.reply.valid && internalReply; reader.io.reply.bits := patch.io.reply.bits
  io.reply.valid := patch.io.reply.valid && !internalReply; io.reply.bits := patch.io.reply.bits
  patch.io.reply.ready := Mux(internalReply, reader.io.reply.ready, io.reply.ready)
  when(reader.io.reply.fire) { internalReply := false.B }
  reader.io.request <> patch.io.request
  reader.io.count := saved.patchInputCount; reader.io.selections := saved.patchInputs
  reader.io.commandId := saved.commandId; reader.io.kernel := saved.evaluationKernel
  reader.io.outputComponents := saved.outputComponents; reader.io.cancel := stop
  executor.io.task <> reader.io.task; reader.io.result <> executor.io.response
  patch.io.response <> reader.io.response
  executor.io.cancel := stop && executor.io.busy
  io.out <> patch.io.out; io.completion <> patch.io.completion; io.busy := active
  when(io.completion.fire) { active := false.B }
  io.launch <> executor.io.launch; io.geometryTask := executor.io.geometryTask
  executor.io.kernelCompletion <> io.kernelCompletion; executor.io.event <> io.event
  executor.io.owner := io.owner; executor.io.taskIds := io.taskIds; io.cancelCu := executor.io.cancelCu
}
