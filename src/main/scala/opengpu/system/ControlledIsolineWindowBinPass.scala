package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.dispatch.{TaggedKernelCompletion,TaggedKernelLaunch}
import opengpu.graphics._

class ControlledIsolineWindowBinTask(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int) extends Bundle {
  val viewport=new ControlledIsolineSharedCuViewportTask(config,p,commandIdWidth)
  val raster=new Fp32RasterState
  val state=UInt(32.W);val token=UInt(32.W)
}
class ControlledIsolineWindowBinTaskCompletion extends Bundle {
  val source=new ControlledIsolineSharedCuViewportCompletion
  val status=UInt(3.W);val received=UInt(64.W);val admitted=UInt(64.W)
}
class ControlledIsolineWindowBinCompletion(p:VertexShaderStageConfig) extends Bundle {
  val status=UInt(3.W);val tasks=UInt(64.W);val nextSequence=UInt(33.W)
  val bins=new WindowPrimitiveBinReplayCompletion(p)
}

/** Controlled-isoline viewport output through acknowledged bin storage and replay.
  * Patch and CU ports stay owned by the enclosing harness until completion.
  * A task report is held until consumed; pass completion waits for the source
  * and bin engines to drain. Geometry invocation/layer metadata is not claimed.
  */
class ControlledIsolineWindowBinPass(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,
    g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),engines:Int=2,
    pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,useSramBlackBoxes:Boolean=false,
    rendererCredits:Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits)
  private val transactions=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val task=Flipped(Decoupled(new ControlledIsolineWindowBinTask(config,p,commandIdWidth)))
    val taskCompletion=Decoupled(new ControlledIsolineWindowBinTaskCompletion)
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new ControlledIsolineWindowBinCompletion(p))
    val opened=Decoupled(UInt(2.W));val load=Flipped(Decoupled(new TessellationPatchAccess))
    val sealInput=Flipped(Decoupled(Bool()));val access=Flipped(Decoupled(new TessellationInvocationAccess))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival));val finish=Flipped(Decoupled(UInt(5.W)))
    val releaseBarrier=Decoupled(new TessellationBarrierRelease);val control=Decoupled(new TessellationBarrierResult)
    val inspect=Flipped(Decoupled(new TessellationPatchAccess));val reply=Decoupled(new TessellationContextReply)
    val launch=Decoupled(new TaggedKernelLaunch(config,commandIdWidth))
    val geometryTask=Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion=Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event=Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner=Input(UInt(numComputeUnits.W));val taskIds=Input(Vec(numComputeUnits,UInt(commandIdWidth.W)))
    val cancelCu=Output(Vec(numComputeUnits,Bool()))
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new ControlledIsolineSharedCuViewport(config,p,commandIdWidth,numComputeUnits,viewport,useSramBlackBoxes))
  private val bins=Module(new WindowPrimitiveBinReplayPass(config,p,g,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits))
  val recordBytes=bins.recordBytes
  if(rendererCredits)bins.io.rendererPermit.get:=io.rendererPermit.get
  private val active=RegInit(false.B);private val ending=RegInit(false.B);private val binDone=RegInit(false.B)
  private val taskOwned=RegInit(false.B);private val report=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val identity=Reg(new GraphicsWorkIdentity)
  private val task=Reg(new ControlledIsolineWindowBinTask(config,p,commandIdWidth))
  private val taskResult=Reg(new ControlledIsolineWindowBinTaskCompletion);private val binResult=Reg(new WindowPrimitiveBinReplayCompletion(p))
  private val result=Reg(new ControlledIsolineWindowBinCompletion(p));private val tasks=RegInit(0.U(64.W))
  private val sequence=RegInit(0.U(33.W));private val received=RegInit(0.U(64.W));private val admitted=RegInit(0.U(64.W))
  private val stop=status=/=0.U || io.cancel || bins.io.cancelSource
  private val admission=Module(new GeometryWindowBinAdmission)
  admission.io.pass:=identity;admission.io.primitive:=io.task.bits.viewport.source.renderer.identity
  admission.io.sequence:=sequence
  private val legal=admission.io.sameIdentity
  bins.io.begin.valid:=io.begin.valid && !active && !io.cancel && bins.io.begin.ready;bins.io.begin.bits:=io.begin.bits
  io.begin.ready:= !active && !io.cancel && bins.io.begin.ready
  when(io.begin.fire){active:=true.B;ending:=false.B;binDone:=false.B;taskOwned:=false.B;report:=false.B;done:=false.B
    status:=0.U;identity:=io.begin.bits.producer.arena.identity;tasks:=0.U;sequence:=0.U}
  val taskAllowed=active && !ending && !binDone && !taskOwned && !report && !stop && bins.io.in.ready
  source.io.task.valid:=io.task.valid && taskAllowed && legal && source.io.task.ready
  source.io.task.bits:=io.task.bits.viewport
  io.task.ready:=taskAllowed && Mux(legal,source.io.task.ready,true.B)
  when(io.task.fire){task:=io.task.bits;tasks:=tasks+1.U;received:=0.U;admitted:=0.U
    when(legal){taskOwned:=true.B}.otherwise{
      status:=1.U;taskResult:=0.U.asTypeOf(taskResult);taskResult.status:=1.U;report:=true.B}}
  source.io.cancel:=taskOwned && stop
  bins.io.in.valid:=taskOwned && !stop && !admission.io.exhausted && source.io.out.valid && bins.io.in.ready
  bins.io.in.bits.primitive:=source.io.out.bits;bins.io.in.bits.raster:=task.raster
  bins.io.in.bits.state:=task.state;bins.io.in.bits.token:=task.token;bins.io.in.bits.sequence:=sequence(31,0)
  bins.io.in.bits.geometry.valid:=false.B
  bins.io.in.bits.geometry.bits:=0.U.asTypeOf(bins.io.in.bits.geometry.bits)
  source.io.out.ready:=taskOwned && (stop || (!admission.io.exhausted && bins.io.in.ready))
  when(taskOwned && source.io.out.valid && admission.io.exhausted && !stop){status:=6.U}
  when(source.io.out.fire){received:=received+1.U
    when(bins.io.in.fire){admitted:=admitted+1.U;sequence:=sequence+1.U}}
  source.io.completion.ready:=taskOwned && !report
  when(source.io.completion.fire){taskOwned:=false.B;report:=true.B;taskResult.source:=source.io.completion.bits
    taskResult.status:=Mux(status=/=0.U,status,Mux(io.cancel,4.U,source.io.completion.bits.status))
    taskResult.received:=received;taskResult.admitted:=admitted
    when(source.io.completion.bits.status=/=0.U && status===0.U && !io.cancel &&
        !(source.io.completion.bits.status===4.U && bins.io.cancelSource)){status:=source.io.completion.bits.status}}
  io.taskCompletion.valid:=report;io.taskCompletion.bits:=taskResult
  when(io.taskCompletion.fire){report:=false.B}
  bins.io.end.valid:=io.end.valid && active && !ending && !taskOwned && !report && !stop && !io.task.valid && bins.io.end.ready
  bins.io.end.bits:=io.end.bits;io.end.ready:=active && !ending && !taskOwned && !report && !stop && !io.task.valid && bins.io.end.ready
  when(io.end.fire){ending:=true.B}
  bins.io.cancel:=active && (status=/=0.U || io.cancel)
  bins.io.completion.ready:=active && !binDone
  when(bins.io.completion.fire){binResult:=bins.io.completion.bits;binDone:=true.B
    when(bins.io.completion.bits.status=/=0.U && status===0.U && !io.cancel){status:=bins.io.completion.bits.status}}
  when(active && io.cancel && status===0.U && !done){status:=4.U}
  when(active && binDone && !taskOwned && !report && !done){result.status:=Mux(io.cancel && status===0.U,4.U,status);result.tasks:=tasks
    result.nextSequence:=sequence;result.bins:=binResult;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){active:=false.B;done:=false.B}
  io.busy:=active
  io.out<>bins.io.out;io.tileDone<>bins.io.tileDone
  io.memoryRequest<>bins.io.memoryRequest;bins.io.memoryResponse<>io.memoryResponse
  io.opened<>source.io.opened;source.io.load<>io.load;source.io.sealInput<>io.sealInput
  source.io.access<>io.access;source.io.arrive<>io.arrive;source.io.finish<>io.finish
  io.releaseBarrier<>source.io.releaseBarrier;io.control<>source.io.control
  source.io.inspect<>io.inspect;io.reply<>source.io.reply
  io.launch<>source.io.launch;io.geometryTask:=source.io.geometryTask
  source.io.kernelCompletion<>io.kernelCompletion;source.io.event<>io.event
  source.io.owner:=io.owner;source.io.taskIds:=io.taskIds;io.cancelCu:=source.io.cancelCu
}
