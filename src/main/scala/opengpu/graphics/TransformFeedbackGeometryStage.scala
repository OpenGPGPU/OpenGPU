package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class TransformFeedbackGeometryDraw(p: VertexShaderStageConfig) extends Bundle {
  val geometry=new VertexGeometryDraw(p)
  val context=UInt(32.W);val root=UInt(32.W);val program=UInt(32.W);val rasterDiscard=Bool()
  val mode=UInt(2.W);val cookie=UInt(32.W)
}
class TransformFeedbackGeometryCompletion extends Bundle {
  val source=new PrimitiveAssemblyCompletion;val status=UInt(3.W)
  val capturedPrimitives=UInt(64.W);val capturedVertices=UInt(64.W);val capturedBytes=UInt(64.W)
}

/** Actual shared-CU vertex execution -> raw assembly -> acknowledged feedback
  * -> flat primitive. Native descriptor admission/translation and clipping/bin
  * consumers are enclosing interfaces. Commands may switch paused objects only
  * between fully drained draws; private source records remain owned through
  * feedback/public geometry and held completion. Existing shader ABI is reused.
  */
class TransformFeedbackGeometryStage(config: GpuConfig=GpuConfig(),
    p: VertexShaderStageConfig=VertexShaderStageConfig(),shared: SharedCuShaderConfig=SharedCuShaderConfig(4),
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,
    payloadOwner: Int=0,groupedTexture: Boolean=false,contexts: Int=8,
    scopedCancellation: Boolean=false) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val resetObjects=if(scopedCancellation)Some(Input(Bool()))else None
    val draw=Flipped(Decoupled(new TransformFeedbackGeometryDraw(p)))
    val command=Flipped(Decoupled(new TransformFeedbackCommand))
    val commandReply=Decoupled(new TransformFeedbackCommandReply)
    val out=Decoupled(new AssembledPrimitive(p))
    val completion=Decoupled(new TransformFeedbackGeometryCompletion)
    val recorded=Valid(new TransformFeedbackPrimitiveResult)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val memorySatp=Output(UInt(32.W));val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val geometry=Module(new VertexGeometryStage(config,p,shared,useSramBlackBoxes,payload,
    payloadOwner,groupedTexture=groupedTexture,flatShading=false))
  private val feedback=Module(new TransformFeedbackExport(config,p,contexts))
  private val descriptor=Reg(new TransformFeedbackGeometryDraw(p))
  private val active=RegInit(false.B);private val offered=RegInit(false.B);private val started=RegInit(false.B)
  private val fault=RegInit(0.U(3.W));private val primitives=RegInit(0.U(64.W))
  private val vertices=RegInit(0.U(64.W));private val bytes=RegInit(0.U(64.W))
  private val drawCancelled=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val cancelled=drawCancelled || io.resetObjects.getOrElse(false.B)
  feedback.io.command.valid:=io.command.valid && !active && !cancelled
  feedback.io.command.bits:=io.command.bits;io.command.ready:=feedback.io.command.ready && !active && !cancelled
  io.commandReply<>feedback.io.commandReply
  io.draw.ready:= !active && !feedback.io.busy && !io.command.valid && !cancelled
  when(io.draw.fire){
    descriptor:=io.draw.bits;active:=true.B;offered:=true.B;started:=false.B
    fault:=0.U;primitives:=0.U;vertices:=0.U;bytes:=0.U
  }
  geometry.io.draw.valid:=offered;geometry.io.draw.bits:=descriptor.geometry
  when(geometry.io.draw.fire){offered:=false.B;started:=true.B}
  feedback.io.in.valid:=geometry.io.out.valid;geometry.io.out.ready:=feedback.io.in.ready
  feedback.io.in.bits.primitive:=geometry.io.out.bits
  feedback.io.in.bits.context:=descriptor.context;feedback.io.in.bits.root:=descriptor.root
  feedback.io.in.bits.program:=descriptor.program;feedback.io.in.bits.rasterDiscard:=descriptor.rasterDiscard
  feedback.io.in.bits.mode:=descriptor.mode;feedback.io.in.bits.cookie:=descriptor.cookie
  io.out<>feedback.io.out;feedback.io.result.ready:=true.B
  io.recorded.valid:=feedback.io.result.fire && feedback.io.result.bits.captured
  io.recorded.bits:=feedback.io.result.bits
  private val resultFault=feedback.io.result.fire && feedback.io.result.bits.status=/=0.U
  private val primary=resultFault && feedback.io.result.bits.status=/=4.U
  when(active && cancelled && fault===0.U){fault:=4.U}
  when(resultFault && (fault===0.U || (fault===4.U && primary))){fault:=feedback.io.result.bits.status}
  geometry.io.cancel:=started && (cancelled || fault=/=0.U || resultFault)
  feedback.io.cancel:=(if(scopedCancellation)io.resetObjects.get else cancelled)
  feedback.io.abort:=(if(scopedCancellation)drawCancelled else false.B)
  private def satAdd(a: UInt,b: UInt): UInt={val n=a+&b;Mux(n(64),((BigInt(1)<<64)-1).U,n(63,0))}
  when(io.recorded.valid){
    primitives:=satAdd(primitives,1.U)
    // Successful result bytes / immutable component layout is not used to
    // infer vertex count: the actual assembled primitive supplies it.
    bytes:=satAdd(bytes,feedback.io.result.bits.bytes)
  }
  private val capturedVertices=Reg(UInt(2.W))
  when(feedback.io.in.fire){capturedVertices:=feedback.io.in.bits.primitive.vertexCount}
  when(io.recorded.valid){vertices:=satAdd(vertices,capturedVertices)}

  private val requests=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,8),2,pipe=true))
  requests.io.in(0).valid:=geometry.io.memoryRequest.valid
  requests.io.in(0).bits:=geometry.io.memoryRequest.bits
  geometry.io.memoryRequest.ready:=requests.io.in(0).ready
  requests.io.in(1).valid:=feedback.io.memoryRequest.valid
  requests.io.in(1).bits:=feedback.io.memoryRequest.bits;requests.io.in(1).bits.transactionId:=4.U
  feedback.io.memoryRequest.ready:=requests.io.in(1).ready
  queue.io.enq<>requests.io.out;io.memoryRequest<>queue.io.deq
  private val memoryFeedback=io.memoryResponse.bits.transactionId===4.U
  geometry.io.memoryResponse.valid:=io.memoryResponse.valid && !memoryFeedback
  geometry.io.memoryResponse.bits:=io.memoryResponse.bits
  feedback.io.memoryResponse.valid:=io.memoryResponse.valid && memoryFeedback
  feedback.io.memoryResponse.bits:=io.memoryResponse.bits;feedback.io.memoryResponse.bits.transactionId:=0.U
  io.memoryResponse.ready:=Mux(memoryFeedback,feedback.io.memoryResponse.ready,geometry.io.memoryResponse.ready)
  when(io.memoryResponse.valid){assert(io.memoryResponse.bits.transactionId<=4.U)}
  io.memorySatp:=descriptor.root
  when(feedback.io.memoryRequest.valid){assert(feedback.io.memorySatp===descriptor.root)}

  val source=geometry.io.completion.bits
  io.completion.valid:=active && geometry.io.completion.valid && !feedback.io.busy && !queue.io.deq.valid
  io.completion.bits.source:=source
  io.completion.bits.status:=Mux(fault=/=0.U && fault=/=4.U,fault,
    Mux(source.status=/=0.U,source.status,fault))
  io.completion.bits.capturedPrimitives:=primitives;io.completion.bits.capturedVertices:=vertices
  io.completion.bits.capturedBytes:=bytes
  geometry.io.completion.ready:=io.completion.ready && !feedback.io.busy && !queue.io.deq.valid
  when(io.completion.fire){assert(started && !offered);active:=false.B;started:=false.B}
  io.busy:=active || feedback.io.busy
  io.shared<>geometry.io.shared
  if(groupedTexture)io.grouped.get<>geometry.io.grouped.get
  payload.foreach { _=>io.payload.get<>geometry.io.payload.get
    geometry.io.payload.get.cancel:=started && io.payload.get.cancel}
}
