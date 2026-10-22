package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

/** Native descriptor capacities are discoverable; the GTJ1/GTI1/GTD1 wire
  * records keep their fixed16-slot layout even for a smaller elaboration.
  * This shares the system payload pool and CUs, never a private shader core.
  */
case class NativeTypedGraphicsConfig(vertex: VertexShaderStageConfig=VertexShaderStageConfig(postTransformEntries=PostTransformVertexCache.defaultEntries),
    fragment: FragmentShaderConfig=FragmentShaderConfig(),storage: TypedTileColorConfig=TypedTileColorConfig(),
    payload: Option[ShaderPayloadConfig]=Some(ShaderPayloadConfig(maxPayloadBytes=16*1024,owners=2,ports=6)),groupedTexture: Boolean=false,transformFeedback: Boolean=false) {
  require(vertex.varyings==fragment.varyings && fragment.colors==4)
  payload.foreach(p=>require(p.owners==2,"native vertex and fragment clients own two payload identities"))
}

object MemoryNativeTypedGraphicsPipeline {
  val transactions=32
  /** Descriptor IDs0..3, shader/geometry IDs16..23 are virtual. The physical
    * backing-image transfer owner uses IDs24..31 (currently only24).
    */
  def physical(id: UInt): Bool=id>=24.U && id<32.U
}

class MemoryNativeTypedGraphicsPipeline(config: GpuConfig=GpuConfig(),native: NativeTypedGraphicsConfig=NativeTypedGraphicsConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),g: GraphicsConfig=GraphicsConfig(),commandIdWidth: Int=8,
    viewport: Fp32ViewportConfig=Fp32ViewportConfig(),useSramBlackBoxes: Boolean=false) extends Module {
  private val p=native.vertex;private val f=native.fragment;private val payload=native.payload
  private val sharedConfig=shared
  private val transactions=MemoryNativeTypedGraphicsPipeline.transactions
  val io=IO(new Bundle {
    val feedback=if(native.transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val grouped=if(native.groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,2))else None
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(commandIdWidth)))
    val completion=Decoupled(new NativeTypedGraphicsCompletion(commandIdWidth))
    val out=Decoupled(new TypedTilePixelQuadCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload= native.payload.map(q=>Vec(2,new FragmentPayloadPort(config,q)))
    val poolPayload=native.payload.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=native.payload.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val capabilityWords=Output(Vec(8,UInt(32.W)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val reader=Module(new NativeTypedGraphicsJobReader(config,commandIdWidth,p,f,native.storage))
  private val image=Module(new TypedTileImagePipeline(config,p,f,shared,g,viewport,native.storage,useSramBlackBoxes,payload,groupedTexture=native.groupedTexture,transformFeedback=native.transformFeedback))
  if(native.transformFeedback){
    val scope=RegInit(0.U.asTypeOf(new TransformFeedbackScope))
    when(io.submit.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,Mux(io.submit.fire,io.feedback.get.scope,scope), !io.busy)
  }
  if(native.groupedTexture)io.grouped.get <> image.io.grouped.get
  reader.io.submit<>io.submit;io.completion<>reader.io.completion
  image.io.begin<>reader.io.begin;image.io.draw<>reader.io.draw;image.io.end<>reader.io.end
  reader.io.drawCompletion<>image.io.drawCompletion;reader.io.passCompletion<>image.io.completion
  image.io.cancel:=reader.io.cancelCore
  reader.io.cancel:=io.cancel || io.payload.map(ps=>VecInit(ps.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  io.shared<>image.io.shared;io.out<>image.io.out
  payload.foreach{_=>
    for(i<-0 until 2){io.payload.get(i)<>image.io.payload.get(i)
      image.io.payload.get(i).cancel:=io.payload.get(i).cancel && reader.io.cancelCore}
    io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get
  }
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,transactions),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,transactions),2,pipe=true))
  private val sources=Seq(reader.io.memoryRequest,image.io.memoryRequest)
  private val replies=Seq(reader.io.memoryResponse,image.io.memoryResponse)
  for(i<-0 until 2){
    arbiter.io.in(i).valid:=sources(i).valid;arbiter.io.in(i).bits:=sources(i).bits
    arbiter.io.in(i).bits.transactionId:=sources(i).bits.transactionId.pad(5)+(if(i==0)0 else 16).U(5.W)
    sources(i).ready:=arbiter.io.in(i).ready
    val selected=if(i==0)io.memoryResponse.bits.transactionId<4.U else io.memoryResponse.bits.transactionId>=16.U
    replies(i).valid:=io.memoryResponse.valid && selected;replies(i).bits:=io.memoryResponse.bits
    replies(i).bits.transactionId:=io.memoryResponse.bits.transactionId-(if(i==0)0 else 16).U
  }
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val id=io.memoryResponse.bits.transactionId
  io.memoryResponse.ready:=Mux(id<4.U,reader.io.memoryResponse.ready,Mux(id>=16.U,image.io.memoryResponse.ready,false.B))
  when(io.memoryResponse.valid){assert(id<4.U || id>=16.U,"native memory response outside owned namespaces")}
  private val inFlight=RegInit(0.U(6.W));inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"native memory reply without an accepted request")}
  io.busy:=reader.io.busy || image.io.busy || queue.io.deq.valid || inFlight.orR
  when(io.completion.valid){assert(!image.io.busy && !queue.io.deq.valid && inFlight===0.U,"native result cannot release queued or physical image work")}
  // GTC1: header size, record magic/sizes, implemented capacities and storage.
  private val flags=(if(payload.nonEmpty)1 else 0) | (1<<1) | (1<<2) | (shared.numComputeUnits<<8)
  io.capabilityWords:=VecInit(Seq(BigInt("47544301",16),BigInt(8),NativeTypedGraphicsLayout.jobMagic,
    BigInt(NativeTypedGraphicsLayout.jobBytes)|(BigInt(NativeTypedGraphicsLayout.passBytes)<<16),
    BigInt(NativeTypedGraphicsLayout.drawBytes)|(BigInt(p.input.attributes)<<16)|(BigInt(p.input.bindings)<<24),
    BigInt(p.varyings)|(BigInt(f.colors)<<8)|(BigInt(g.maxSampleCount)<<16),
    BigInt(native.storage.bytes)|(BigInt(g.subPixelBits)<<24),BigInt(flags)).map(_.U(32.W)))
}
