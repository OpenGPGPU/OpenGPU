package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{TaggedKernelCompletion, TaggedKernelLaunch}
import opengpu.graphics._

class ControlledIsolineSharedCuClipCompletion extends Bundle {
  val source = new ControlledIsolineRendererCompletion
  /** Source status, or 7 for a clip failure. */
  val status = UInt(3.W); val inputs = UInt(16.W); val outputs = UInt(16.W); val culled = UInt(16.W)
}

/** Retain the task through shared-CU evaluation and clip drain. Patch storage
  * can be released after evaluation. Clipped output is provisional until the
  * final completion succeeds.
  */
class ControlledIsolineSharedCuClip(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,
    useSramBlackBoxes:Boolean=false) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new ControlledIsolineSharedCuRendererTask(config,p,commandIdWidth)))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val sealInput=Flipped(Decoupled(Bool()));val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val finish=Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier=Decoupled(new TessellationBarrierRelease);val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val out=Decoupled(new ClippedPrimitive(p));val completion=Decoupled(new ControlledIsolineSharedCuClipCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
    val launch=Decoupled(new TaggedKernelLaunch(config,commandIdWidth))
    val geometryTask=Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion=Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event=Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner=Input(UInt(numComputeUnits.W));val taskIds=Input(Vec(numComputeUnits,UInt(commandIdWidth.W)))
    val cancelCu=Output(Vec(numComputeUnits,Bool()))
  })
  private val source=Module(new ControlledIsolineSharedCuRenderer(config,p,commandIdWidth,numComputeUnits))
  private val clip=Module(new PrimitiveClipStage(p,useSramBlackBoxes))
  private val active=RegInit(false.B);private val sourceDone=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val summary=Reg(new ControlledIsolineRendererCompletion)
  private val result=Reg(new ControlledIsolineSharedCuClipCompletion)
  private val inputs=RegInit(0.U(16.W));private val outputs=RegInit(0.U(16.W));private val culled=RegInit(0.U(16.W))
  private val clipFault=clip.io.completion.valid && clip.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || clipFault
  source.io.task.valid:=io.task.valid && !active && !io.cancel;source.io.task.bits:=io.task.bits
  io.task.ready:= !active && !io.cancel && source.io.task.ready;io.busy:=active
  when(io.task.fire){active:=true.B;sourceDone:=false.B;done:=false.B;status:=0.U
    inputs:=0.U;outputs:=0.U;culled:=0.U}
  source.io.cancel:=active && stop;clip.io.cancel:=active && stop
  clip.io.in.valid:=active && !stop && source.io.out.valid
  clip.io.in.bits:=source.io.out.bits
  source.io.out.ready:=active && (stop || clip.io.in.ready)
  when(clip.io.in.fire){inputs:=inputs+1.U}
  io.out<>clip.io.out
  when(io.out.fire){outputs:=outputs+1.U}
  clip.io.completion.ready:=active
  when(clip.io.completion.fire){
    when(clip.io.completion.bits.culled){culled:=culled+1.U}
    when(clip.io.completion.bits.status=/=0.U && status===0.U && !io.cancel){status:=7.U}}
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire){summary:=source.io.completion.bits;sourceDone:=true.B
    when(source.io.completion.bits.status=/=0.U && status===0.U && !io.cancel && !clipFault){status:=source.io.completion.bits.status}}
  when(active && io.cancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !clip.io.busy && !done){
    result.source:=summary;result.status:=Mux(io.cancel && status===0.U,4.U,status)
    result.inputs:=inputs;result.outputs:=outputs;result.culled:=culled;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.opened<>source.io.opened;source.io.load<>io.load;source.io.sealInput<>io.sealInput
  source.io.access<>io.access;source.io.arrive<>io.arrive;source.io.finish<>io.finish
  io.releaseBarrier<>source.io.releaseBarrier;io.control<>source.io.control
  source.io.inspect<>io.inspect;io.reply<>source.io.reply
  io.launch<>source.io.launch;io.geometryTask:=source.io.geometryTask
  source.io.kernelCompletion<>io.kernelCompletion;source.io.event<>io.event
  source.io.owner:=io.owner;source.io.taskIds:=io.taskIds;io.cancelCu:=source.io.cancelCu
}
