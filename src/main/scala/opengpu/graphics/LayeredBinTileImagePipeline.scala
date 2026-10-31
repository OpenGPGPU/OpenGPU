package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

/** One layered bin replay engine connected to its image publication owner. */
class LayeredBinTileImagePipeline(config:GpuConfig=GpuConfig(),p:VertexShaderStageConfig=VertexShaderStageConfig(),
    f:FragmentShaderConfig=FragmentShaderConfig(),shared:SharedCuShaderConfig=SharedCuShaderConfig(4),
    g:GraphicsConfig=GraphicsConfig(),storage:TypedTileColorConfig=TypedTileColorConfig(),engine:Int=0,
    useSramBlackBoxes:Boolean=false,payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,
    groupedTexture:Boolean=false) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val enable=Input(Bool());val template=Input(new LayeredTileImageBindingRequest)
    val source=Flipped(Decoupled(new WindowPrimitiveReplay(p)))
    val sourceDone=Flipped(Decoupled(new WindowPrimitiveTileCompletion));val rendererPermit=Output(Bool())
    val primitiveDone=Decoupled(new TypedReplayPrimitiveCompletion);val out=Decoupled(new TypedTilePixelQuadCompletion)
    val completion=Decoupled(new LayeredBinTileCompletion)
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
  private val owner=Module(new LayeredBinTileOwner(p))
  private val image=Module(new LayeredReplayTileImagePipeline(config,p,f,shared,g,storage,engine,
    useSramBlackBoxes,payload,payloadOwner,groupedTexture))
  owner.io.enable:=io.enable;owner.io.template:=io.template;owner.io.source<>io.source;owner.io.sourceDone<>io.sourceDone
  io.rendererPermit:=owner.io.rendererPermit;owner.io.cancel:=io.cancel
  image.io.begin<>owner.io.imageBegin;image.io.in<>owner.io.imageIn;image.io.end<>owner.io.imageEnd
  owner.io.imageCompletion<>image.io.completion;image.io.cancel:=owner.io.imageCancel
  io.completion<>owner.io.completion;io.primitiveDone<>image.io.primitiveDone;io.out<>image.io.out
  io.shared<>image.io.shared;io.memoryRequest<>image.io.memoryRequest;image.io.memoryResponse<>io.memoryResponse
  io.fragmentEvents:=image.io.fragmentEvents
  io.cancelSource:=owner.io.cancelSource || image.io.cancelSource;io.busy:=owner.io.busy || image.io.busy
  if(groupedTexture)io.grouped.get<>image.io.grouped.get
  payload.foreach{_=>io.payload.get<>image.io.payload.get;io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
}
