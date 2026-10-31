package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class LayeredReplayTileCompletion extends Bundle {
  val status=UInt(3.W)
  /** True only when the image engine accepted the bound view. */
  val imageStarted=Bool()
  val layer=UInt(16.W)
  val tile=UInt(32.W)
  val image=new TypedReplayTileCompletion
}

/** Bind a flattened layered-bin tile to its attachment views before replay.
  * The caller owns bin dispatch credits and all allocation alias checks, and
  * must retain them until this component's held completion is consumed.
  */
class LayeredReplayTileImagePipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    f: FragmentShaderConfig=FragmentShaderConfig(),shared: SharedCuShaderConfig=SharedCuShaderConfig(4),
    g: GraphicsConfig=GraphicsConfig(),storage: TypedTileColorConfig=TypedTileColorConfig(),engine: Int=0,
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,
    groupedTexture: Boolean=false) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredTileImageBindingRequest))
    val in=Flipped(Decoupled(new WindowPrimitiveReplay(p)))
    val primitiveDone=Decoupled(new TypedReplayPrimitiveCompletion);val end=Flipped(Decoupled(Bool()))
    val out=Decoupled(new TypedTilePixelQuadCompletion);val completion=Decoupled(new LayeredReplayTileCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,1))else None
    val payload=payloadConfig.map(q=>Vec(2,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,32))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,32)))
    val fragmentEvents=Output(new FragmentPerformanceEvents)
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val admission=Module(new LayeredReplayTileAdmission)
  private val image=Module(new TypedReplayTileImagePipeline(config,p,f,shared,g,storage,engine,
    useSramBlackBoxes,payload,payloadOwner,groupedTexture,geometryInput=true))
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  admission.io.cancel:=externalCancel;admission.io.begin<>io.begin;io.completion<>admission.io.completion
  image.io.begin.valid:=admission.io.imageBegin.valid;image.io.begin.bits:=admission.io.imageBegin.bits.descriptor
  image.io.selectedLayer.get:=admission.io.imageBegin.bits.layer;admission.io.imageBegin.ready:=image.io.begin.ready
  admission.io.imageCompletion<>image.io.completion;image.io.cancel:=admission.io.imageCancel
  private val stop=admission.io.imageCancel || externalCancel
  image.io.in.valid:=admission.io.running && !stop && io.in.valid;image.io.in.bits:=io.in.bits
  io.in.ready:=admission.io.running && !stop && image.io.in.ready
  image.io.end.valid:=admission.io.running && !stop && io.end.valid;image.io.end.bits:=io.end.bits
  io.end.ready:=admission.io.running && !stop && image.io.end.ready
  io.busy:=admission.io.busy;io.cancelSource:=admission.io.cancelSource || image.io.cancelSource
  io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out
  io.shared<>image.io.shared;io.memoryRequest<>image.io.memoryRequest;image.io.memoryResponse<>io.memoryResponse
  io.fragmentEvents:=image.io.fragmentEvents
  if(groupedTexture)io.grouped.get<>image.io.grouped.get
  payload.foreach{_=>
    for(i<-0 until 2){io.payload.get(i)<>image.io.payload.get(i)
      image.io.payload.get(i).cancel:=io.payload.get(i).cancel && admission.io.running}
    io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get
  }
}
