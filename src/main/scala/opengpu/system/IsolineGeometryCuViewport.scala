package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{TaggedKernelCompletion,TaggedKernelLaunch}
import opengpu.graphics._

class IsolineGeometryCuViewportTask(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int) extends Bundle {
  val source=new IsolineGeometryCuRendererTask(config,p,commandIdWidth)
  val viewport=new Fp32ViewportState
}
class IsolineGeometryCuViewportCompletion extends Bundle {
  val source=new IsolineGeometryCuClipCompletion
  val status=UInt(3.W);val inputs=UInt(16.W);val outputs=UInt(16.W);val culled=UInt(16.W);val guardBand=UInt(16.W)
}

/** Retain tessellated geometry through CU evaluation, clip and viewport drain.
  * Exported window primitives are provisional until patch completion succeeds.
  */
class IsolineGeometryCuViewport(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,
    viewport:Fp32ViewportConfig=Fp32ViewportConfig(),useSramBlackBoxes:Boolean=false) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new IsolineGeometryCuViewportTask(config,p,commandIdWidth)))
    val out=Decoupled(new WindowPrimitive(p));val completion=Decoupled(new IsolineGeometryCuViewportCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
    val launch=Decoupled(new TaggedKernelLaunch(config,commandIdWidth))
    val geometryTask=Output(Valid(new GeometryInvocationLaunch))
    val kernelCompletion=Flipped(Decoupled(new TaggedKernelCompletion(commandIdWidth)))
    val event=Flipped(Vec(numComputeUnits,Decoupled(new GeometryOutputCommand)))
    val owner=Input(UInt(numComputeUnits.W));val taskIds=Input(Vec(numComputeUnits,UInt(commandIdWidth.W)))
    val cancelCu=Output(Vec(numComputeUnits,Bool()))
  })
  private val source=Module(new IsolineGeometryCuClip(config,p,commandIdWidth,numComputeUnits,useSramBlackBoxes))
  private val prep=Module(new Fp32ViewportStage(p,viewport))
  private val active=RegInit(false.B);private val sourceDone=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val descriptor=Reg(new Fp32ViewportState)
  private val summary=Reg(new IsolineGeometryCuClipCompletion);private val result=Reg(new IsolineGeometryCuViewportCompletion)
  private val inputs=RegInit(0.U(16.W));private val outputs=RegInit(0.U(16.W));private val culled=RegInit(0.U(16.W));private val guard=RegInit(0.U(16.W))
  private val legal=Fp32ViewportState.legal(io.task.bits.viewport)
  private val prepFault=prep.io.completion.valid && prep.io.completion.bits.status=/=0.U
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || prepFault || sourceFault
  source.io.task.valid:=io.task.valid && !active && !io.cancel && legal;source.io.task.bits:=io.task.bits.source
  io.task.ready:= !active && !io.cancel && Mux(legal,source.io.task.ready,true.B);io.busy:=active
  when(io.task.fire){active:=true.B;sourceDone:= !legal;done:=false.B;status:=Mux(legal,0.U,1.U)
    descriptor:=io.task.bits.viewport;inputs:=0.U;outputs:=0.U;culled:=0.U;guard:=0.U
    when(!legal){summary:=0.U.asTypeOf(summary);summary.status:=1.U
      summary.source.taskId:=io.task.bits.source.renderer.tessellation.taskId}}
  source.io.cancel:=active && stop;prep.io.cancel:=active && stop
  prep.io.in.valid:=active && !stop && source.io.out.valid
  prep.io.in.bits.clipped:=source.io.out.bits;prep.io.in.bits.viewport:=descriptor
  source.io.out.ready:=active && (stop || prep.io.in.ready)
  when(prep.io.in.fire){inputs:=inputs+1.U}
  io.out<>prep.io.out
  when(io.out.fire){outputs:=outputs+1.U}
  prep.io.completion.ready:=active
  when(prep.io.completion.fire){val r=prep.io.completion.bits
    when(r.culled){culled:=culled+1.U};when(r.output && r.guardBandRequired){guard:=guard+1.U}
    when(r.status=/=0.U && status===0.U && !io.cancel){status:=r.status}}
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire){summary:=source.io.completion.bits;sourceDone:=true.B
    when(source.io.completion.bits.status=/=0.U && status===0.U && !io.cancel && !prepFault){status:=source.io.completion.bits.status}}
  when(active && io.cancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !prep.io.busy && !done){
    result.source:=summary;result.status:=Mux(io.cancel && status===0.U,4.U,status)
    result.inputs:=inputs;result.outputs:=outputs;result.culled:=culled;result.guardBand:=guard;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.launch<>source.io.launch;io.geometryTask:=source.io.geometryTask
  source.io.kernelCompletion<>io.kernelCompletion;source.io.event<>io.event
  source.io.owner:=io.owner;source.io.taskIds:=io.taskIds;io.cancelCu:=source.io.cancelCu
}
