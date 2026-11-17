package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch, TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

class TessellationQuadPatchCuTask(config: GpuConfig, idWidth: Int) extends Bundle {
  val patch = new TessellationQuadPatchTask
  val commandId = UInt(idWidth.W); val evaluationKernel = new KernelLaunch(config)
  val outputComponents = UInt(7.W)
  val patchInputCount = UInt(6.W); val patchInputs = Vec(61, new IsolinePatchInputSelection)
}

/** Retain a controlled quad patch while selected outputs are read and its
  * evaluation vertices execute on the reserved shared-CU command identity.
  */
class TessellationQuadPatchCuEvaluation(config: GpuConfig, idWidth: Int, numComputeUnits: Int) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationQuadPatchCuTask(config, idWidth)))
    val opened = Decoupled(UInt(2.W)); val load = Flipped(Decoupled(new TessellationPatchAccess))
    val sealInput = Flipped(Decoupled(Bool())); val access = Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive = Flipped(Decoupled(new TessellationBarrierArrival)); val finish = Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier = Decoupled(new TessellationBarrierRelease); val control = Decoupled(new TessellationBarrierResult)
    val inspect = Flipped(Decoupled(new TessellationPatchAccess)); val reply = Decoupled(new TessellationContextReply)
    val launch = Decoupled(new TaggedKernelLaunch(config, idWidth)); val geometryTask = Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion = Flipped(Decoupled(new TaggedKernelCompletion(idWidth)))
    val event = Flipped(Vec(numComputeUnits, Decoupled(new GeometryOutputCommand)))
    val owner = Input(UInt(numComputeUnits.W)); val taskIds = Input(Vec(numComputeUnits, UInt(idWidth.W)))
    val cancelCu = Output(Vec(numComputeUnits, Bool()))
    val out = Decoupled(new EvaluatedQuadPrimitive); val completion = Decoupled(new TessellationQuadPatchEvaluationCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val patch = Module(new TessellationQuadPatchEvaluation)
  private val evaluator = Module(new TessellationQuadCuEvaluator(config, idWidth, numComputeUnits))
  private val active = RegInit(false.B); private val cancelled = RegInit(false.B)
  private val saved = Reg(new TessellationQuadPatchCuTask(config, idWidth))
  patch.io.task.valid := !active && io.task.valid && !io.cancel; patch.io.task.bits := io.task.bits.patch
  io.task.ready := !active && !io.cancel && patch.io.task.ready
  when(io.task.fire) { active := true.B; cancelled := false.B; saved := io.task.bits }
  when(active && io.cancel && !patch.io.completion.valid) { cancelled := true.B }
  private val stop = active && (io.cancel || cancelled)
  patch.io.cancel := stop
  io.opened <> patch.io.opened; patch.io.load <> io.load; patch.io.sealInput <> io.sealInput
  patch.io.access <> io.access; patch.io.arrive <> io.arrive; patch.io.finish <> io.finish
  io.releaseBarrier <> patch.io.releaseBarrier; io.control <> patch.io.control

  // Lock a stalled inspection offer and route its one reply to its owner.
  private val locked = RegInit(false.B); private val lockedInternal = Reg(Bool())
  private val preferInternal = RegInit(true.B); private val internalReply = RegInit(false.B)
  private val chooseInternal = Mux(locked, lockedInternal, evaluator.io.inspect.valid && (!io.inspect.valid || preferInternal))
  patch.io.inspect.valid := Mux(chooseInternal, evaluator.io.inspect.valid, io.inspect.valid)
  patch.io.inspect.bits := Mux(chooseInternal, evaluator.io.inspect.bits, io.inspect.bits)
  evaluator.io.inspect.ready := chooseInternal && patch.io.inspect.ready
  io.inspect.ready := !chooseInternal && patch.io.inspect.ready
  when(patch.io.inspect.valid && !patch.io.inspect.ready && !locked) { locked := true.B; lockedInternal := chooseInternal }
  when(patch.io.inspect.fire) { locked := false.B; preferInternal := !chooseInternal
    when(chooseInternal) { internalReply := true.B } }
  evaluator.io.reply.valid := patch.io.reply.valid && internalReply; evaluator.io.reply.bits := patch.io.reply.bits
  io.reply.valid := patch.io.reply.valid && !internalReply; io.reply.bits := patch.io.reply.bits
  patch.io.reply.ready := Mux(internalReply, evaluator.io.reply.ready, io.reply.ready)
  when(evaluator.io.reply.fire) { internalReply := false.B }

  evaluator.io.request <> patch.io.request; patch.io.response <> evaluator.io.response
  evaluator.io.count := saved.patchInputCount; evaluator.io.selections := saved.patchInputs
  evaluator.io.commandId := saved.commandId; evaluator.io.kernel := saved.evaluationKernel
  evaluator.io.outputComponents := saved.outputComponents; evaluator.io.cancel := stop
  io.launch <> evaluator.io.launch; io.geometryTask := evaluator.io.geometryTask
  evaluator.io.kernelCompletion <> io.kernelCompletion; evaluator.io.event <> io.event
  evaluator.io.owner := io.owner; evaluator.io.taskIds := io.taskIds; io.cancelCu := evaluator.io.cancelCu
  io.out <> patch.io.out; io.completion <> patch.io.completion; io.busy := active
  when(io.completion.fire) { active := false.B }
}
