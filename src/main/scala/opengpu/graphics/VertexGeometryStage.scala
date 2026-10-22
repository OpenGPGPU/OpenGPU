package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class VertexGeometryDraw(p: VertexShaderStageConfig) extends Bundle {
  val vertex=new VertexShaderDraw(p)
  val topology=UInt(4.W);val flatMask=UInt((4*p.varyings).W)
  val indirect=new IndirectDrawControl
}

/** Generic GPU input/shader/assembly stream. Admission snapshots both stages;
  * an offered child draw is retained until accepted, then cancellation may
  * reach that child. Completion retains host ownership through primitive drain.
  * Rasterization, clipping and native host descriptors are separate interfaces.
  */
private[graphics] class DirectVertexGeometryStage(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,groupedTexture: Boolean=false,
    flatShading: Boolean=true,transformFeedback: Boolean=false) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  require(!transformFeedback || flatShading,"feedback owns pre-flat assembly and downstream flat shading")
  val io=IO(new Bundle {
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val draw=Flipped(Decoupled(new VertexGeometryDraw(p)))
    val out=Decoupled(new AssembledPrimitive(p))
    val completion=Decoupled(new PrimitiveAssemblyCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  if(transformFeedback){
    val stage=Module(new TransformFeedbackGeometryStage(config,p,shared,useSramBlackBoxes,payload,
      payloadOwner,groupedTexture=groupedTexture,scopedCancellation=true))
    val fb=io.feedback.get
    stage.io.draw.valid:=io.draw.valid;io.draw.ready:=stage.io.draw.ready
    stage.io.draw.bits.geometry:=io.draw.bits
    stage.io.draw.bits.context:=fb.scope.context;stage.io.draw.bits.root:=fb.scope.root
    stage.io.draw.bits.program:=fb.scope.program;stage.io.draw.bits.rasterDiscard:=fb.scope.rasterDiscard
    stage.io.draw.bits.mode:=fb.scope.mode;stage.io.draw.bits.cookie:=fb.scope.cookie
    stage.io.command<>fb.command;fb.commandReply<>stage.io.commandReply
    stage.io.cancel:=io.cancel;stage.io.resetObjects.get:=fb.cancel
    io.busy:=stage.io.busy;fb.busy:=stage.io.busy
    fb.recorded:=stage.io.recorded
    val scope=RegInit(0.U.asTypeOf(new TransformFeedbackScope))
    when(io.draw.fire){scope:=fb.scope}
    fb.memorySatp:=scope.root
    io.out<>stage.io.out
    val forwarded=RegInit(0.U(64.W));val kinds=RegInit(VecInit(Seq.fill(3)(0.U(64.W))))
    when(io.draw.fire){forwarded:=0.U;kinds.foreach(_:=0.U)}
    when(io.out.fire){forwarded:=forwarded+1.U
      for(k<-0 until 3)when(io.out.bits.kind===k.U){kinds(k):=kinds(k)+1.U}}
    io.completion.valid:=stage.io.completion.valid;stage.io.completion.ready:=io.completion.ready
    io.completion.bits:=stage.io.completion.bits.source
    io.completion.bits.status:=stage.io.completion.bits.status
    io.completion.bits.primitives:=forwarded;io.completion.bits.points:=kinds(0)
    io.completion.bits.lines:=kinds(1);io.completion.bits.triangles:=kinds(2)
    val exportRequest=stage.io.memoryRequest.bits.transactionId===4.U
    io.memoryRequest.valid:=stage.io.memoryRequest.valid && !exportRequest
    io.memoryRequest.bits:=stage.io.memoryRequest.bits
    fb.memoryRequest.valid:=stage.io.memoryRequest.valid && exportRequest
    fb.memoryRequest.bits:=stage.io.memoryRequest.bits;fb.memoryRequest.bits.transactionId:=0.U
    stage.io.memoryRequest.ready:=Mux(exportRequest,fb.memoryRequest.ready,io.memoryRequest.ready)
    val responses=Module(new RRArbiter(new ComputeMemoryResponse(64,8),2))
    responses.io.in(0).valid:=io.memoryResponse.valid;responses.io.in(0).bits:=io.memoryResponse.bits
    io.memoryResponse.ready:=responses.io.in(0).ready
    responses.io.in(1).valid:=fb.memoryResponse.valid;responses.io.in(1).bits:=fb.memoryResponse.bits
    responses.io.in(1).bits.transactionId:=4.U;fb.memoryResponse.ready:=responses.io.in(1).ready
    stage.io.memoryResponse<>responses.io.out
    io.shared<>stage.io.shared
    if(groupedTexture)io.grouped.get<>stage.io.grouped.get
    payload.foreach(_=>io.payload.get<>stage.io.payload.get)
  }else{
  val vertex=Module(new VertexShaderStage(config,p,shared,useSramBlackBoxes,payload,payloadOwner,groupedTexture=groupedTexture))
  if(groupedTexture)io.grouped.get <> vertex.io.grouped.get
  val assembly=Module(new PrimitiveAssemblyStage(p,flatShading=flatShading))
  val descriptor=Reg(new VertexGeometryDraw(p));val active=RegInit(false.B)
  val vertexOffered=RegInit(false.B);val assemblyOffered=RegInit(false.B)
  val vertexStarted=RegInit(false.B);val assemblyStarted=RegInit(false.B)
  val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  io.draw.ready:= !active && !externalCancel;io.busy:=active
  when(io.draw.fire) {
    descriptor:=io.draw.bits;active:=true.B;vertexOffered:=true.B;assemblyOffered:=true.B
    vertexStarted:=false.B;assemblyStarted:=false.B
  }
  vertex.io.draw.valid:=vertexOffered;vertex.io.draw.bits:=descriptor.vertex
  when(vertex.io.draw.fire){vertexOffered:=false.B;vertexStarted:=true.B}
  assembly.io.draw.valid:=assemblyOffered;assembly.io.draw.bits.version:=1.U
  assembly.io.draw.bits.identity:=descriptor.vertex.input.identity
  assembly.io.draw.bits.count:=descriptor.vertex.input.count;assembly.io.draw.bits.instances:=descriptor.vertex.input.instances
  assembly.io.draw.bits.baseInstance:=descriptor.vertex.input.baseInstance
  assembly.io.draw.bits.varyingSlots:=descriptor.vertex.varyingSlots
  assembly.io.draw.bits.topology:=descriptor.topology;assembly.io.draw.bits.flatMask:=descriptor.flatMask
  when(assembly.io.draw.fire){assemblyOffered:=false.B;assemblyStarted:=true.B}
  vertex.io.cancel:=vertexStarted && (externalCancel || assembly.io.cancelSource)
  assembly.io.cancel:=assemblyStarted && externalCancel
  assembly.io.in<>vertex.io.out;assembly.io.end<>vertex.io.completion
  io.out<>assembly.io.out;io.completion<>assembly.io.completion
  io.shared<>vertex.io.shared;io.memoryRequest<>vertex.io.memoryRequest;vertex.io.memoryResponse<>io.memoryResponse
  payload.foreach { _=>
    io.payload.get<>vertex.io.payload.get
    vertex.io.payload.get.cancel:=vertexStarted && io.payload.get.cancel
  }
  when(io.completion.fire){assert(vertexStarted && assemblyStarted);active:=false.B}
  }
}
