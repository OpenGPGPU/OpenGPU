package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{KernelLaunch,TaggedKernelCompletion,TaggedKernelLaunch}
import opengpu.graphics._

class IsolinePatchSharedCuTask(config:GpuConfig,idWidth:Int) extends Bundle {
  val patch=new TessellationIsolinePatchTask
  val commandId=UInt(idWidth.W);val kernel=new KernelLaunch(config);val outputComponents=UInt(7.W)
}

/** Run isoline coordinates from one owned control patch on the shared geometry
  * CU service. The enclosing owner pins shader resources and reserves commandId
  * until completion; accepted work must drain during cancellation.
  */
class IsolinePatchSharedCu(config:GpuConfig,idWidth:Int,numComputeUnits:Int) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new IsolinePatchSharedCuTask(config,idWidth)))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val sealInput=Flipped(Decoupled(Bool()));val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val finish=Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier=Decoupled(new TessellationBarrierRelease);val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val out=Decoupled(new EvaluatedIsolinePrimitive);val completion=Decoupled(new TessellationIsolinePatchEvaluationCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
    val launch=Decoupled(new TaggedKernelLaunch(config,idWidth));val geometryTask=Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion=Flipped(Decoupled(new TaggedKernelCompletion(idWidth)))
    val event=Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner=Input(UInt(numComputeUnits.W));val taskIds=Input(Vec(numComputeUnits,UInt(idWidth.W)))
    val cancelCu=Output(Vec(numComputeUnits,Bool()))
  })
  private val patch=Module(new TessellationIsolinePatchEvaluation)
  private val executor=Module(new IsolineGeometryCuExecutor(config,idWidth,numComputeUnits))
  private val active=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val commandId=Reg(UInt(idWidth.W));private val kernel=Reg(new KernelLaunch(config));private val components=Reg(UInt(7.W))
  patch.io.task.valid:= !active && io.task.valid && !io.cancel;patch.io.task.bits:=io.task.bits.patch
  io.task.ready:= !active && !io.cancel && patch.io.task.ready
  when(io.task.fire){active:=true.B;cancelled:=false.B;commandId:=io.task.bits.commandId
    kernel:=io.task.bits.kernel;components:=io.task.bits.outputComponents}
  when(active && io.cancel && !patch.io.completion.valid){cancelled:=true.B}
  patch.io.cancel:=active && (io.cancel || cancelled)
  io.opened<>patch.io.opened;patch.io.load<>io.load;patch.io.sealInput<>io.sealInput;patch.io.access<>io.access
  patch.io.arrive<>io.arrive;patch.io.finish<>io.finish;io.releaseBarrier<>patch.io.releaseBarrier;io.control<>patch.io.control
  patch.io.inspect<>io.inspect;io.reply<>patch.io.reply
  executor.io.task.valid:=patch.io.request.valid;patch.io.request.ready:=executor.io.task.ready
  executor.io.task.bits.evaluation:=patch.io.request.bits;executor.io.task.bits.commandId:=commandId
  executor.io.task.bits.kernel:=kernel;executor.io.task.bits.outputComponents:=components
  patch.io.response<>executor.io.response
  // An already offered evaluator request must be accepted before cancellation
  // reaches the CU worker, then its terminal response must drain.
  executor.io.cancel:=active && (io.cancel || cancelled) && executor.io.busy
  io.out<>patch.io.out;io.completion<>patch.io.completion;io.busy:=active
  when(io.completion.fire){active:=false.B}
  io.launch<>executor.io.launch;io.geometryTask:=executor.io.geometryTask
  executor.io.kernelCompletion<>io.kernelCompletion;executor.io.event<>io.event
  executor.io.owner:=io.owner;executor.io.taskIds:=io.taskIds;io.cancelCu:=executor.io.cancelCu
}
