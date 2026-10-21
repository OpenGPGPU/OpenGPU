package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class VertexClipCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  val source=new PrimitiveAssemblyCompletion
  val inputPrimitives=UInt(64.W);val outputPrimitives=UInt(67.W)
  val intersections=UInt(72.W);val culledPrimitives=UInt(64.W);val arithmeticOperations=UInt(80.W)
  val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W);val zeroW=Bool()
  val clipFaultValid=Bool();val faultSequence=UInt(64.W);val faultPrimitiveId=UInt(32.W);val faultInstanceId=UInt(32.W)
}

/** GPU input, shared-CU vertex shading, assembly and bounded clipping. The
  * source may retire its last payload after clipping captures a primitive;
  * this owner remains busy through generated fan parts and held completion.
  * Source offers awaiting a clipping slot remain owned by the source. Cancel
  * drains those offers while retaining any already published clipped part.
  */
class VertexClipPipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,groupedTexture: Boolean=false) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val draw=Flipped(Decoupled(new VertexGeometryDraw(p)))
    val out=Decoupled(new ClippedPrimitive(p));val completion=Decoupled(new VertexClipCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new VertexGeometryStage(config,p,shared,useSramBlackBoxes,payload,payloadOwner,groupedTexture=groupedTexture))
  if(groupedTexture)io.grouped.get <> source.io.grouped.get
  private val clipper=Module(new PrimitiveClipStage(p,useSramBlackBoxes))
  private val active=RegInit(false.B);private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B)
  private val descriptor=Reg(new VertexGeometryDraw(p));private val sourceDone=RegInit(false.B)
  private val summary=Reg(new PrimitiveAssemblyCompletion);private val result=Reg(new VertexClipCompletion)
  private val done=RegInit(false.B);private val status=RegInit(0.U(3.W))
  private val received=RegInit(0.U(64.W));private val exported=RegInit(0.U(67.W))
  private val intersections=RegInit(0.U(72.W));private val culled=RegInit(0.U(64.W));private val operations=RegInit(0.U(80.W))
  private val arithmeticFlags=RegInit(0.U(5.W));private val packingFlags=RegInit(0.U(5.W));private val zeroW=RegInit(false.B)
  private val clipFaultValid=RegInit(false.B);private val faultSequence=Reg(UInt(64.W))
  private val faultPrimitiveId=Reg(UInt(32.W));private val faultInstanceId=Reg(UInt(32.W))
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val clipFault=clipper.io.completion.valid && clipper.io.completion.bits.status=/=0.U
  private val sourceFault=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || externalCancel || clipFault || sourceFault
  io.draw.ready:= !active && !externalCancel;io.busy:=active
  when(io.draw.fire) {
    descriptor:=io.draw.bits;active:=true.B;done:=false.B;sourceOffered:=true.B;sourceStarted:=false.B;sourceDone:=false.B
    status:=0.U;received:=0.U;exported:=0.U;intersections:=0.U;culled:=0.U;operations:=0.U
    arithmeticFlags:=0.U;packingFlags:=0.U;zeroW:=false.B
    clipFaultValid:=false.B;faultSequence:=0.U;faultPrimitiveId:=0.U;faultInstanceId:=0.U
  }
  source.io.draw.valid:=sourceOffered;source.io.draw.bits:=descriptor
  when(source.io.draw.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  source.io.cancel:=active && sourceStarted && stop;clipper.io.cancel:=active && stop
  // The clipper's ready is independent of valid. Admit only into its private
  // idle slot, so an unaccepted child offer is never withdrawn by cancellation.
  clipper.io.in.valid:=active && !stop && source.io.out.valid && clipper.io.in.ready
  clipper.io.in.bits:=source.io.out.bits
  source.io.out.ready:=active && (stop || clipper.io.in.ready)
  when(source.io.out.fire){received:=received+1.U}
  io.out<>clipper.io.out
  when(io.out.fire){exported:=exported+1.U}
  clipper.io.completion.ready:=active
  when(clipper.io.completion.fire) {
    val r=clipper.io.completion.bits
    intersections:=intersections+r.intersections;operations:=operations+r.arithmeticOperations
    when(r.culled){culled:=culled+1.U}
    arithmeticFlags:=arithmeticFlags|r.arithmeticStatus;packingFlags:=packingFlags|r.packingStatus;zeroW:=zeroW || r.zeroW
    when(r.status=/=0.U && status===0.U && !externalCancel){
      status:=r.status;clipFaultValid:=true.B;faultSequence:=r.sequence;faultPrimitiveId:=r.primitiveId;faultInstanceId:=r.instanceId
    }
  }
  source.io.completion.ready:=active && !sourceDone
  when(source.io.completion.fire) {
    summary:=source.io.completion.bits;sourceDone:=true.B
    assert(source.io.completion.bits.primitives===received,"clip source primitive accounting lost an offer")
    when(source.io.completion.bits.status=/=0.U && status===0.U && !externalCancel && !clipFault){status:=source.io.completion.bits.status}
  }
  when(active && externalCancel && status===0.U && !done){status:=4.U}
  when(active && sourceDone && !sourceOffered && !source.io.busy && !clipper.io.busy && !done) {
    result.identity:=descriptor.vertex.input.identity;result.status:=Mux(externalCancel && status===0.U,4.U,status)
    result.source:=summary;result.inputPrimitives:=received;result.outputPrimitives:=exported
    result.intersections:=intersections;result.culledPrimitives:=culled;result.arithmeticOperations:=operations
    result.arithmeticStatus:=arithmeticFlags;result.packingStatus:=packingFlags;result.zeroW:=zeroW;done:=true.B
    result.clipFaultValid:=clipFaultValid;result.faultSequence:=faultSequence
    result.faultPrimitiveId:=faultPrimitiveId;result.faultInstanceId:=faultInstanceId
  }
  io.completion.valid:=done;io.completion.bits:=result
  when(io.completion.fire){done:=false.B;active:=false.B}
  io.shared<>source.io.shared;io.memoryRequest<>source.io.memoryRequest;source.io.memoryResponse<>io.memoryResponse
  payload.foreach { _=>io.payload.get<>source.io.payload.get;source.io.payload.get.cancel:=sourceStarted && io.payload.get.cancel }
}
