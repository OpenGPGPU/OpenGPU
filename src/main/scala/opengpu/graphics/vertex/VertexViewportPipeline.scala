package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class VertexViewportDraw(p: VertexShaderStageConfig) extends Bundle {
  val geometry=new VertexGeometryDraw(p);val viewport=new Fp32ViewportState
}
class VertexViewportCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val source=new VertexClipCompletion
  val inputParts=UInt(67.W);val preparedParts=UInt(67.W);val culledParts=UInt(67.W);val guardBandParts=UInt(67.W)
  val viewportArithmeticOperations=UInt(80.W);val viewportArithmeticStatus=UInt(5.W);val viewportPackingStatus=UInt(5.W)
  val viewportFaultValid=Bool();val faultSequence=UInt(64.W);val faultPrimitiveId=UInt(32.W);val faultInstanceId=UInt(32.W)
  val faultPartIndex=UInt(3.W);val faultVertex=UInt(2.W)
}

/** Generic vertex input through clipping and perspective/viewport preparation.
  * Viewport state is immutable for the complete draw. Retain context through
  * the final window-space offer and held completion; source payload release
  * alone does not retire this owner. Guard-band flags are explicit outputs for
  * a later raster/guard-clip consumer, not a license to use invalid fixed XY.
  */
class VertexViewportPipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),viewport: Fp32ViewportConfig=Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,groupedTexture: Boolean=false,transformFeedback: Boolean=false) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val draw=Flipped(Decoupled(new VertexViewportDraw(p)));val out=Decoupled(new WindowPrimitive(p))
    val completion=Decoupled(new VertexViewportCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new VertexClipPipeline(config,p,shared,useSramBlackBoxes,payload,payloadOwner,groupedTexture=groupedTexture,transformFeedback=transformFeedback))
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.draw.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,source.io.feedback.get,Mux(io.draw.fire,io.feedback.get.scope,scope), !io.busy)
  }
  if(groupedTexture)io.grouped.get <> source.io.grouped.get
  private val preparation=Module(new Fp32ViewportStage(p,viewport))
  private val descriptor=Reg(new VertexViewportDraw(p));private val summary=Reg(new VertexClipCompletion)
  private val result=Reg(new VertexViewportCompletion)
  private val active=RegInit(false.B);private val offered=RegInit(false.B);private val started=RegInit(false.B)
  private val sourceDone=RegInit(false.B);private val done=RegInit(false.B);private val status=RegInit(0.U(3.W))
  private val received=RegInit(0.U(67.W));private val prepared=RegInit(0.U(67.W))
  private val culled=RegInit(0.U(67.W));private val guarded=RegInit(0.U(67.W));private val operations=RegInit(0.U(80.W))
  private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W))
  private val faultValid=RegInit(false.B);private val faultSequence=Reg(UInt(64.W));private val faultPrimitive=Reg(UInt(32.W))
  private val faultInstance=Reg(UInt(32.W));private val faultPart=Reg(UInt(3.W));private val faultVertex=Reg(UInt(2.W))
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val preparationFault=preparation.io.completion.valid && preparation.io.completion.bits.status=/=0.U
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || externalCancel || preparationFault || sourceFault
  io.draw.ready:= !active && !externalCancel;io.busy:=active || io.feedback.map(_.busy).getOrElse(false.B)
  when(io.draw.fire) {
    descriptor:=io.draw.bits;active:=true.B;offered:=true.B;started:=false.B;sourceDone:=false.B;done:=false.B
    status:=Mux(Fp32ViewportState.legal(io.draw.bits.viewport),0.U,1.U)
    received:=0.U;prepared:=0.U;culled:=0.U;guarded:=0.U;operations:=0.U;arithmeticFlags:=0.U;packingFlags:=0.U
    faultValid:=false.B;faultSequence:=0.U;faultPrimitive:=0.U;faultInstance:=0.U;faultPart:=0.U;faultVertex:=0.U
  }
  source.io.draw.valid:=offered;source.io.draw.bits:=descriptor.geometry
  when(source.io.draw.fire){offered:=false.B;started:=true.B}
  source.io.cancel:=active && started && stop;preparation.io.cancel:=active && stop
  preparation.io.in.valid:=active && !stop && source.io.out.valid && preparation.io.in.ready
  preparation.io.in.bits.clipped:=source.io.out.bits;preparation.io.in.bits.viewport:=descriptor.viewport
  source.io.out.ready:=active && (stop || preparation.io.in.ready)
  when(source.io.out.fire){received:=received+1.U}
  io.out<>preparation.io.out
  when(io.out.fire){prepared:=prepared+1.U}
  preparation.io.completion.ready:=active
  when(preparation.io.completion.fire) {
    val r=preparation.io.completion.bits
    operations:=operations+r.arithmeticOperations;arithmeticFlags:=arithmeticFlags|r.arithmeticStatus;packingFlags:=packingFlags|r.packingStatus
    when(r.culled){culled:=culled+1.U}
    when(r.output && r.guardBandRequired){guarded:=guarded+1.U}
    when(r.status=/=0.U && status===0.U && !externalCancel) {
      status:=r.status;faultValid:=true.B;faultSequence:=r.sequence;faultPrimitive:=r.primitiveId
      faultInstance:=r.instanceId;faultPart:=r.partIndex;faultVertex:=r.faultVertex
    }
  }
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire) {
    summary:=source.io.completion.bits;sourceDone:=true.B
    assert(source.io.completion.bits.outputPrimitives===received,"viewport source lost a clipped part")
    when(source.io.completion.bits.status=/=0.U && status===0.U && !externalCancel && !preparationFault){status:=source.io.completion.bits.status}
  }
  when(active && externalCancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !offered && !source.io.busy && !preparation.io.busy && !done) {
    result.identity:=descriptor.geometry.vertex.input.identity;result.status:=Mux(externalCancel && status===0.U,4.U,status)
    result.source:=summary;result.inputParts:=received;result.preparedParts:=prepared;result.culledParts:=culled;result.guardBandParts:=guarded
    result.viewportArithmeticOperations:=operations;result.viewportArithmeticStatus:=arithmeticFlags;result.viewportPackingStatus:=packingFlags
    result.viewportFaultValid:=faultValid;result.faultSequence:=faultSequence;result.faultPrimitiveId:=faultPrimitive
    result.faultInstanceId:=faultInstance;result.faultPartIndex:=faultPart;result.faultVertex:=faultVertex;done:=true.B
  }
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.shared<>source.io.shared;io.memoryRequest<>source.io.memoryRequest;source.io.memoryResponse<>io.memoryResponse
  payload.foreach { _=>io.payload.get<>source.io.payload.get;source.io.payload.get.cancel:=started && io.payload.get.cancel }
}
