package opengpu.system
import opengpu.graphics.tessellation._

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
class TessellationCuIsolineSharedEvaluator(config: GpuConfig, idWidth: Int, numComputeUnits: Int,evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false) extends Module {
  // Preserve the previous full JVM constructor with the language service disabled.
  def this(config: GpuConfig,idWidth: Int,numComputeUnits: Int,evaluationPatchAvailable:Boolean) =
    this(config,idWidth,numComputeUnits,evaluationPatchAvailable,false)

  val io = IO(new Bundle {
    val inputPatchBinding=if(tessellationInfoAvailable)Some(Input(Valid(new TessellationInputPatchBinding)))else None
    val tessellationTask=if(tessellationInfoAvailable)Some(Output(Valid(new TessellationShaderTaskBinding)))else None
    val evaluationPatch=if(evaluationPatchAvailable)Some(Flipped(Vec(numComputeUnits,new SharedCuEvaluationPatchPort(config))))else None
    val evaluationPatchBinding=if(evaluationPatchAvailable)Some(Output(Valid(new TessellationEvaluationPatchBinding)))else None
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
  private val patch = Module(new TessellationCuIsolinePatch(config,zeroOnBegin=evaluationPatchAvailable))
  private val reader = Module(new IsolinePatchInputReader(config, idWidth))
  private val executor = Module(new IsolineGeometryCuExecutor(config, idWidth, numComputeUnits,evaluationPatchAvailable,tessellationInfoAvailable))
  private val active = RegInit(false.B); private val cancelled = RegInit(false.B)
  private val saved = Reg(new TessellationCuIsolineSharedTask(config, idWidth))
  private val bindingLegal=if(tessellationInfoAvailable)
    TessellationInputPatchBinding.legal(io.inputPatchBinding.get,io.task.bits.control.patch.context.taskId)else true.B
  private val rejected=RegInit(false.B)
  private val rejectedResult=Reg(chiselTypeOf(io.completion.bits))
  private val inputBinding=if(tessellationInfoAvailable)Some(Reg(new TessellationInputPatchBinding))else None
  if(tessellationInfoAvailable)when(io.task.fire){inputBinding.get:=io.inputPatchBinding.get.bits}
  patch.io.task.valid := !active && !rejected && io.task.valid && !io.cancel && bindingLegal
  patch.io.task.bits := io.task.bits.control
  io.task.ready := !active && !rejected && !io.cancel && Mux(bindingLegal,patch.io.task.ready,true.B)
  when(io.task.fire) { active := bindingLegal; cancelled := false.B; saved := io.task.bits
    when(!bindingLegal){rejected:=true.B;rejectedResult:=0.U.asTypeOf(rejectedResult)
      rejectedResult.taskId:=io.task.bits.control.patch.context.taskId;rejectedResult.status:=1.U}
  }
  when(active && io.cancel && !patch.io.completion.valid) { cancelled := true.B }
  private val evaluationFault=WireDefault(false.B);private val evaluationBusy=WireDefault(false.B)
  private val evaluationFailed=RegInit(false.B)
  when(io.task.fire){evaluationFailed:=false.B}
  when(evaluationFault){evaluationFailed:=true.B}
  private val stop = active && (io.cancel || cancelled || evaluationFault || evaluationFailed)
  patch.io.cancel := stop
  io.opened <> patch.io.opened; patch.io.load <> io.load; patch.io.seal <> io.seal
  io.controlKernel <> patch.io.kernel; io.bases := patch.io.bases; patch.io.kernelCompletion <> io.controlCompletion
  io.controlCancel := patch.io.cancelCu; io.controlTerminal := patch.io.terminalCu
  patch.io.access <> io.access; io.accessResult <> patch.io.accessResult
  patch.io.park <> io.park; io.resume <> patch.io.resume; patch.io.finish <> io.finish

  if(evaluationPatchAvailable){
    val provider=Module(new TessellationEvaluationPatchProvider(config,idWidth,numComputeUnits))
    val arb=Module(new TessellationInspectionArbiter)
    provider.io.ports<>io.evaluationPatch.get
    provider.io.owned:=io.owner;provider.io.taskIds:=io.taskIds;provider.io.commandId:=saved.commandId
    provider.io.active:=active;provider.io.cancel:=stop
    executor.io.patchLayout.get:=saved.control.patch.context.layout
    provider.io.scope:=executor.io.evaluationPatchBinding.get
    io.evaluationPatchBinding.get:=executor.io.evaluationPatchBinding.get
    arb.io.in(0)<>reader.io.inspect;reader.io.reply<>arb.io.replies(0)
    arb.io.in(1)<>provider.io.inspect;provider.io.reply<>arb.io.replies(1)
    arb.io.in(2)<>io.inspect;patch.io.inspect<>arb.io.out
    // Input publication has no inspection credit. Route its real source-zero
    // acknowledgement directly to the producer, including during cancellation.
    // Only source-two replies may consume the inspection arbiter's credit.
    val loading=patch.io.reply.bits.source===0.U
    val inspecting=patch.io.reply.bits.source===2.U
    arb.io.reply.valid:=patch.io.reply.valid && inspecting;arb.io.reply.bits:=patch.io.reply.bits
    io.reply.valid:=(patch.io.reply.valid && loading) || arb.io.replies(2).valid
    io.reply.bits:=Mux(loading,patch.io.reply.bits,arb.io.replies(2).bits)
    arb.io.replies(2).ready:= !loading && io.reply.ready
    patch.io.reply.ready:=Mux(loading,io.reply.ready,inspecting && arb.io.reply.ready)
    when(patch.io.reply.valid){assert(loading || inspecting,"shared evaluator received an unrouted patch reply source")}
    evaluationFault:=provider.io.fault;evaluationBusy:=provider.io.busy || arb.io.busy
  }else{
  // Lock stalled offers and route the one outstanding response to its issuer.
  val locked = RegInit(false.B); val lockedInternal = Reg(Bool())
  val preferInternal = RegInit(true.B); val internalReply = RegInit(false.B)
  val chooseInternal = Mux(locked, lockedInternal, reader.io.inspect.valid && (!io.inspect.valid || preferInternal))
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
  }
  reader.io.request <> patch.io.request
  reader.io.count := saved.patchInputCount; reader.io.selections := saved.patchInputs
  reader.io.commandId := saved.commandId; reader.io.kernel := saved.evaluationKernel
  reader.io.outputComponents := saved.outputComponents; reader.io.cancel := stop
  executor.io.task <> reader.io.task; reader.io.result <> executor.io.response
  patch.io.response <> reader.io.response
  executor.io.cancel := stop && executor.io.busy
  io.out <> patch.io.out; io.completion.valid:=rejected || (patch.io.completion.valid && !evaluationBusy)
  io.completion.bits:=Mux(rejected,rejectedResult,patch.io.completion.bits)
  if(evaluationPatchAvailable)when(!rejected && evaluationFailed && (patch.io.completion.bits.status===0.U || patch.io.completion.bits.status===4.U)){io.completion.bits.status:=3.U}
  patch.io.completion.ready:=io.completion.ready && !evaluationBusy && !rejected
  io.busy := active || rejected || evaluationBusy
  when(io.completion.fire) { active := false.B;rejected:=false.B }
  io.launch <> executor.io.launch; io.geometryTask := executor.io.geometryTask
  executor.io.kernelCompletion <> io.kernelCompletion; executor.io.event <> io.event
  executor.io.owner := io.owner; executor.io.taskIds := io.taskIds; io.cancelCu := executor.io.cancelCu

  if(tessellationInfoAvailable){
    executor.io.inputPatchBinding.get.valid:=active
    executor.io.inputPatchBinding.get.bits:=inputBinding.get
    io.tessellationTask.get:=executor.io.tessellationTask.get
  }
}
