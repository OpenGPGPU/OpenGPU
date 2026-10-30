package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.graphics._

class GeometryViewportTask(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int) extends Bundle {
  val geometry = new GeometrySharedShaderTask(config,p,commandIdWidth)
  val viewport = new Fp32ViewportState
}
class GeometryWindowPrimitive(p:VertexShaderStageConfig) extends Bundle {
  val window = new WindowPrimitive(p)
  val inputPrimitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val layer = UInt(32.W)
}
class GeometryPrimitiveViewportResult extends Bundle {
  val source = new GeometryPrimitiveClipResult
  val status = UInt(3.W)
  val viewportInputs = UInt(64.W)
  val viewportOutputs = UInt(64.W)
  val culled = UInt(64.W)
  val guardBand = UInt(64.W)
}

/** Retain a geometry primitive and its immutable viewport through preparation. */
class GeometryPrimitiveViewportClient(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,
    viewport:Fp32ViewportConfig=Fp32ViewportConfig(),useSramBlackBoxes:Boolean=false) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new GeometryViewportTask(config,p,commandIdWidth)))
    val out=Decoupled(new GeometryWindowPrimitive(p))
    val completion=Decoupled(new GeometryPrimitiveViewportResult)
    val cancel=Input(Bool())
    val shared=Flipped(new SharedCuGraphicsPort(config,commandIdWidth,numComputeUnits,geometryAvailable=true))
    val texture=Vec(numComputeUnits,new SharedCuTexturePort(config))
    val trap=Decoupled(new TaggedGraphicsTrap(config,commandIdWidth,numComputeUnits))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
  })
  private val source=Module(new GeometryPrimitiveClipClient(config,p,commandIdWidth,numComputeUnits,useSramBlackBoxes))
  private val prep=Module(new Fp32ViewportStage(p,viewport))
  private val active=RegInit(false.B);private val sourceDone=RegInit(false.B);private val done=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val descriptor=Reg(new Fp32ViewportState)
  private val summary=Reg(new GeometryPrimitiveClipResult);private val result=Reg(new GeometryPrimitiveViewportResult)
  private val inputs=RegInit(0.U(64.W));private val outputs=RegInit(0.U(64.W));private val culled=RegInit(0.U(64.W))
  private val guardBand=RegInit(0.U(64.W));private val inputId=Reg(UInt(32.W));private val invocation=Reg(UInt(6.W));private val layer=Reg(UInt(32.W))
  private val legal=Fp32ViewportState.legal(io.task.bits.viewport)
  private val prepFault=prep.io.completion.valid && prep.io.completion.bits.status=/=0.U
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || prepFault || sourceFault
  source.io.task.valid:=io.task.valid && !active && !io.cancel && legal;source.io.task.bits:=io.task.bits.geometry
  io.task.ready:= !active && !io.cancel && Mux(legal,source.io.task.ready,true.B)
  when(io.task.fire){active:=true.B;done:=false.B;sourceDone:= !legal;status:=Mux(legal,0.U,1.U)
    descriptor:=io.task.bits.viewport;inputs:=0.U;outputs:=0.U;culled:=0.U;guardBand:=0.U
    when(!legal){summary:=0.U.asTypeOf(summary);summary.status:=1.U
      summary.geometry.taskId:=io.task.bits.geometry.primitive.input.taskId
      summary.geometry.inputPrimitiveId:=io.task.bits.geometry.primitive.input.primitive.primitiveId}}
  source.io.cancel:=active && stop;prep.io.cancel:=active && stop
  prep.io.in.valid:=active && !stop && source.io.out.valid && prep.io.in.ready
  prep.io.in.bits.clipped:=source.io.out.bits.clipped;prep.io.in.bits.viewport:=descriptor
  source.io.out.ready:=active && (stop || prep.io.in.ready)
  when(prep.io.in.fire){inputs:=inputs+1.U;inputId:=source.io.out.bits.inputPrimitiveId
    invocation:=source.io.out.bits.invocationId;layer:=source.io.out.bits.layer}
  io.out.valid:=prep.io.out.valid;io.out.bits.window:=prep.io.out.bits
  io.out.bits.inputPrimitiveId:=inputId;io.out.bits.invocationId:=invocation;io.out.bits.layer:=layer
  prep.io.out.ready:=io.out.ready
  when(io.out.fire){outputs:=outputs+1.U}
  prep.io.completion.ready:=active
  when(prep.io.completion.fire){val r=prep.io.completion.bits
    when(r.culled){culled:=culled+1.U};when(r.output && r.guardBandRequired){guardBand:=guardBand+1.U}
    when(r.status=/=0.U && status===0.U && !io.cancel){status:=r.status}}
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire){summary:=source.io.completion.bits;sourceDone:=true.B
    when(source.io.completion.bits.status=/=0.U && status===0.U && !io.cancel && !prepFault){status:=source.io.completion.bits.status}}
  when(active && io.cancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !prep.io.busy && !done){
    result.source:=summary;result.status:=Mux(io.cancel && status===0.U,4.U,status)
    result.viewportInputs:=inputs;result.viewportOutputs:=outputs;result.culled:=culled;result.guardBand:=guardBand;done:=true.B}
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.shared<>source.io.shared;io.texture<>source.io.texture;io.trap<>source.io.trap
  source.io.invalidate<>io.invalidate;io.invalidateDone<>source.io.invalidateDone
}
