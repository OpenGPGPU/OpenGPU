package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

/** Actual raw TES -> original GNG mapper/GS scheduler/GUR2/output composition.
  * No CU, router, protected owner or product capability is created here.
  * The caller retains original program/resources and the command namespace
  * through the report and supplies only this owner's extra transport drain.
  * shared must be this GS client's exclusive logical route, for example the
  * filtered client port of SharedShaderTaskRouter, never a raw system pool.
  * This component does not filter other commands' untagged texture/events.
  */
class TessellationGeometryPrimitivePipeline(config:GpuConfig,p:VertexShaderStageConfig,
    commandIdWidth:Int,numComputeUnits:Int,domain:Int,exclusiveGsSequence:Boolean=false) extends Module {
  def this(config:GpuConfig,p:VertexShaderStageConfig,commandIdWidth:Int,numComputeUnits:Int,domain:Int)=
    this(config,p,commandIdWidth,numComputeUnits,domain,false)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationGeometryPipelineTask(p,commandIdWidth)))
    val primitive=Decoupled(new GeometryMappedPrimitive(p))
    val completion=Decoupled(new TessellationGeometryPipelineResult(commandIdWidth))
    val nextSequenceExclusive=if(exclusiveGsSequence)Some(Output(UInt(65.W)))else None
    val shared=Flipped(new SharedCuGraphicsPort(config,commandIdWidth,numComputeUnits,geometryAvailable=true))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val ownerTransportDrained=Input(Bool());val cancel=Input(Bool())
    val busy=Output(Bool());val drained=Output(Bool())
  })
  private val control=Module(new TessellationGeometryPipelineControl(p,commandIdWidth,exclusiveGsSequence))
  private val raw=Module(new TessellationGeometryRawBridge(p,domain))
  private val geometry=Module(new NativeGeometryPrimitivePipeline(config,p,commandIdWidth,numComputeUnits,rawOutputs=true,exclusiveGsSequence=exclusiveGsSequence))
  control.io.task<>io.task;io.completion<>control.io.completion
  raw.io.in<>control.io.rawIn;control.io.rawOut<>raw.io.out;control.io.rawFault<>raw.io.fault
  raw.io.cancel:=control.io.rawCancel;control.io.rawBusy:=raw.io.busy
  geometry.io.task<>control.io.geometryTask;control.io.geometryCompletion<>geometry.io.completion
  if(exclusiveGsSequence){control.io.geometryNextSequenceExclusive.get:=geometry.io.nextSequenceExclusive.get
    io.nextSequenceExclusive.get:=control.io.nextSequenceExclusive.get}
  geometry.io.cancel:=control.io.geometryCancel;control.io.geometryBusy:=geometry.io.busy
  io.primitive<>geometry.io.primitive;io.shared<>geometry.io.shared
  io.memoryRequest<>geometry.io.memoryRequest;geometry.io.memoryResponse<>io.memoryResponse
  geometry.io.invalidate<>io.invalidate;io.invalidateDone<>geometry.io.invalidateDone
  // These are this client's visible offers, not another pool owner's traffic.
  // Accepted translator/router/invalidate credits are the required external
  // ownerTransportDrained input; neither predicate authorizes program access.
  private val writebacks=geometry.io.shared.texture.map(t=>t.scalarWriteback.valid || t.vectorWriteback.valid).reduce(_ || _)
  private val requests=geometry.io.shared.texture.map(t=>t.scalar.valid || t.vector.valid).reduce(_ || _)
  private val events=geometry.io.shared.geometryEvent.get.map(_.valid).reduce(_ || _)
  control.io.geometryOffersDrained:= !geometry.io.shared.launch.valid && !geometry.io.primitive.valid &&
    !geometry.io.memoryRequest.valid && !io.memoryResponse.valid && !io.invalidate.valid &&
    !io.invalidateDone.valid && !geometry.io.shared.completion.valid && !geometry.io.shared.trap.valid &&
    !requests && !writebacks && !events
  control.io.ownerTransportDrained:=io.ownerTransportDrained;control.io.cancel:=io.cancel
  io.busy:=control.io.busy;io.drained:=control.io.drained
}

class TessellationIsolineGeometryPipelineTask(p:VertexShaderStageConfig,w:Int) extends Bundle {
  val raw=new TessellationIsolineGeometryRawRequest(p);val commandId=UInt(w.W)
}
class TessellationTriangleGeometryPipelineTask(p:VertexShaderStageConfig,w:Int) extends Bundle {
  val raw=new TessellationTriangleGeometryRawRequest(p);val commandId=UInt(w.W)
}
class TessellationQuadGeometryPipelineTask(p:VertexShaderStageConfig,w:Int) extends Bundle {
  val raw=new TessellationQuadGeometryRawRequest(p);val commandId=UInt(w.W)
}

/** Domain-specific ports retain their real evaluation bundle widths. */
class TessellationIsolineGeometryPrimitivePipeline(config:GpuConfig,p:VertexShaderStageConfig,w:Int,cus:Int) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationIsolineGeometryPipelineTask(p,w)))
    val primitive=Decoupled(new GeometryMappedPrimitive(p));val completion=Decoupled(new TessellationGeometryPipelineResult(w))
    val shared=Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val ownerTransportDrained=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val child=Module(new TessellationGeometryPrimitivePipeline(config,p,w,cus,0))
  child.io.task.valid:=io.task.valid;io.task.ready:=child.io.task.ready
  child.io.task.bits:=0.U.asTypeOf(child.io.task.bits);child.io.task.bits.commandId:=io.task.bits.commandId
  child.io.task.bits.raw.link:=io.task.bits.raw.link;child.io.task.bits.raw.tesTaskId:=io.task.bits.raw.evaluated.taskId
  child.io.task.bits.raw.tesSequence:=io.task.bits.raw.evaluated.sequence;child.io.task.bits.raw.point:=io.task.bits.raw.evaluated.point
  for(v<-0 until 2)child.io.task.bits.raw.vertices(v):=io.task.bits.raw.evaluated.vertices(v)
  io.primitive<>child.io.primitive;io.completion<>child.io.completion;io.shared<>child.io.shared
  io.memoryRequest<>child.io.memoryRequest;child.io.memoryResponse<>io.memoryResponse
  child.io.invalidate<>io.invalidate;io.invalidateDone<>child.io.invalidateDone
  child.io.ownerTransportDrained:=io.ownerTransportDrained;child.io.cancel:=io.cancel;io.busy:=child.io.busy;io.drained:=child.io.drained
}
class TessellationTriangleGeometryPrimitivePipeline(config:GpuConfig,p:VertexShaderStageConfig,w:Int,cus:Int) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationTriangleGeometryPipelineTask(p,w)))
    val primitive=Decoupled(new GeometryMappedPrimitive(p));val completion=Decoupled(new TessellationGeometryPipelineResult(w))
    val shared=Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val ownerTransportDrained=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val child=Module(new TessellationGeometryPrimitivePipeline(config,p,w,cus,1))
  child.io.task.valid:=io.task.valid;io.task.ready:=child.io.task.ready
  child.io.task.bits:=0.U.asTypeOf(child.io.task.bits);child.io.task.bits.commandId:=io.task.bits.commandId
  child.io.task.bits.raw.link:=io.task.bits.raw.link;child.io.task.bits.raw.tesTaskId:=io.task.bits.raw.evaluated.taskId
  child.io.task.bits.raw.tesSequence:=io.task.bits.raw.evaluated.sequence;child.io.task.bits.raw.point:=io.task.bits.raw.evaluated.point
  child.io.task.bits.raw.vertices:=io.task.bits.raw.evaluated.vertices
  io.primitive<>child.io.primitive;io.completion<>child.io.completion;io.shared<>child.io.shared
  io.memoryRequest<>child.io.memoryRequest;child.io.memoryResponse<>io.memoryResponse
  child.io.invalidate<>io.invalidate;io.invalidateDone<>child.io.invalidateDone
  child.io.ownerTransportDrained:=io.ownerTransportDrained;child.io.cancel:=io.cancel;io.busy:=child.io.busy;io.drained:=child.io.drained
}
class TessellationQuadGeometryPrimitivePipeline(config:GpuConfig,p:VertexShaderStageConfig,w:Int,cus:Int) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationQuadGeometryPipelineTask(p,w)))
    val primitive=Decoupled(new GeometryMappedPrimitive(p));val completion=Decoupled(new TessellationGeometryPipelineResult(w))
    val shared=Flipped(new SharedCuGraphicsPort(config,w,cus,geometryAvailable=true))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val invalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val invalidateDone=Decoupled(new CacheLineInvalidate(config))
    val ownerTransportDrained=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val child=Module(new TessellationGeometryPrimitivePipeline(config,p,w,cus,2))
  child.io.task.valid:=io.task.valid;io.task.ready:=child.io.task.ready
  child.io.task.bits:=0.U.asTypeOf(child.io.task.bits);child.io.task.bits.commandId:=io.task.bits.commandId
  child.io.task.bits.raw.link:=io.task.bits.raw.link;child.io.task.bits.raw.tesTaskId:=io.task.bits.raw.evaluated.taskId
  child.io.task.bits.raw.tesSequence:=io.task.bits.raw.evaluated.sequence;child.io.task.bits.raw.point:=io.task.bits.raw.evaluated.point
  child.io.task.bits.raw.vertices:=io.task.bits.raw.evaluated.vertices
  io.primitive<>child.io.primitive;io.completion<>child.io.completion;io.shared<>child.io.shared
  io.memoryRequest<>child.io.memoryRequest;child.io.memoryResponse<>io.memoryResponse
  child.io.invalidate<>io.invalidate;io.invalidateDone<>child.io.invalidateDone
  child.io.ownerTransportDrained:=io.ownerTransportDrained;child.io.cancel:=io.cancel;io.busy:=child.io.busy;io.drained:=child.io.drained
}
