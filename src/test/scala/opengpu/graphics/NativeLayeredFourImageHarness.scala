package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.system._
import opengpu.testutil.GraphicsTestResolution

/** Actual mixed source, common producer/directory and four real image owners.
  * The submitted frame is 25x1 (four 8-wide tiles); capacity is not reduced to
  * that frame: all 65536 directory entries, two slots per engine, 32KiB typed
  * storage, 64 sample owners, sixteen attributes/varyings and four MRTs remain.
  * This physical-source service harness is separate from protected host/MMU
  * qualification and from the product's 16-lane/eight-warp throughput target.
  */
private[graphics] class NativeLayeredFourImageHarness extends Module {
  val config=GpuConfig(lanes=4,warps=1,l2Sets=8,l2Ways=2)
  val vertices=VertexShaderStageConfig();val fragments=FragmentShaderConfig()
  val native=NativeBinnedGraphicsConfig.fourEngineApiExtent
  val frameWidth=25;val frameHeight=1
  require(GraphicsTestResolution.width>=frameWidth && GraphicsTestResolution.height>=frameHeight)
  private val graphics=native.renderGraphics(GraphicsConfig(
    screenWidth=GraphicsTestResolution.width,screenHeight=GraphicsTestResolution.height))
  private val payload=native.payload.get
  private val pins=4+6*4+4
  private val hostSlots=NativeLayeredVertexBinImagePipeline.transactions(4,native.writeCredits,65536)
  private val slots=GpuSystem.totalMemoryTransactions(2,4,hostSlots)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredWindowPrimitiveBinImageDescriptor))
    val draw=Flipped(Decoupled(new NativeLayeredVertexDraw(vertices,8)))
    val drawCompletion=Decoupled(new NativeGeometryVertexBinDrawCompletion)
    val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(4,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(4,Decoupled(new LayeredTypedPixelQuadCompletion))
    val layerDone=Vec(4,Decoupled(new LayeredTypedImageLayerCompletion))
    val tileDone=Decoupled(new LayeredTileChainCompletion)
    val completion=Decoupled(new NativeLayeredVertexBinImageCompletion(vertices))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val cancel=Input(Bool());val payloadCancel=Input(Vec(5,Bool()))
    // Backpressure the real pool's allocation reply, retaining its actual
    // lease. Never synthesize a staging success or an ownership indication.
    val holdPayloadReply=Input(Vec(5,Bool()))
    val payloadReplyHeld=Output(Vec(5,Bool()))
    val task=Output(Vec(5,Valid(new ShaderPayloadBinding(config,payload))))
    val launch=Output(Valid(Bool()));val launchPayload=Output(Valid(new ShaderPayloadBinding(config,payload)))
    val imageStore=Output(Vec(4,Bool()));val imageAck=Output(Vec(4,Bool()))
    val trap=Output(Valid(new TaggedGraphicsTrap(config,8,2)))
    val reservedBytes=Output(UInt(32.W));val poolDrained=Output(Bool())
    val owners=Output(UInt(2.W));val idle=Output(Bool())
  })
  private val stage=Module(new NativeLayeredVertexBinImagePipeline(config,vertices,fragments,
    SharedCuShaderConfig(2),graphics,engines=4,pageBytes=native.pageBytes,writeCredits=native.writeCredits,
    tileSlots=native.tileSlots,pagedCapacity=65536,storage=native.storage,payload=native.payload))
  val recordBytes=stage.recordBytes
  private val system=Module(new GpuSystem(config,numComputeUnits=2,enableGraphicsKernels=true,
    geometryAvailable=true,graphicsHostTransactions=hostSlots,shaderPayload=native.payload,
    producerPayloadPins=pins,instructionCacheSets=4,instructionCacheWays=1,vectorCacheSets=4,vectorCacheWays=1))
  private def tie(x:Data):Unit=x match {
    case r:Record=>r.elements.values.foreach(tie);case v:Vec[_]=>v.foreach(tie)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  tie(system.io)
  stage.io.begin<>io.begin;stage.io.draw<>io.draw;stage.io.end<>io.end
  io.drawCompletion<>stage.io.drawCompletion;io.completion<>stage.io.completion
  io.primitiveDone<>stage.io.primitiveDone;io.out<>stage.io.out;io.layerDone<>stage.io.layerDone;io.tileDone<>stage.io.tileDone
  stage.io.cancel:=io.cancel
  system.io.graphicsKernels.get<>stage.io.shared
  system.io.graphicsHostRequest<>stage.io.memoryRequest;stage.io.memoryResponse<>system.io.graphicsHostResponse
  system.io.graphicsPayload.get:=stage.io.poolPayload.get;system.io.payloadProducerPins.get:=stage.io.payloadPins.get
  private val management=system.io.payloadManagement.get
  for(i<-0 until 5){val port=stage.io.payload.get(i);val reply=management.controlResponse(i)
    management.control(i)<>port.control
    port.controlResponse.valid:=reply.valid && !io.holdPayloadReply(i);port.controlResponse.bits:=reply.bits
    reply.ready:=port.controlResponse.ready && !io.holdPayloadReply(i)
    management.request(i)<>port.request;port.response<>management.response(i)
    port.context:=7.U;port.cancel:=io.payloadCancel(i)
    io.task(i):=port.task;io.payloadReplyHeld(i):=reply.valid && io.holdPayloadReply(i)
  }
  private val invalidations=Module(new Queue(new CacheLineInvalidate(config),1))
  invalidations.io.enq<>system.io.graphicsShaderL1Invalidate
  system.io.graphicsShaderL1InvalidateDone<>invalidations.io.deq
  private val requests=Module(new Queue(new ComputeMemoryRequest(config,64,slots),2,pipe=true))
  requests.io.enq<>system.io.memoryRequest;io.memoryRequest<>requests.io.deq;system.io.memoryResponse<>io.memoryResponse
  system.io.graphicsDrained:= !stage.io.busy && requests.io.count===0.U
  io.idle:=system.io.vmContextIdle && !stage.io.busy && requests.io.count===0.U
  io.reservedBytes:=management.reservedBytes;io.poolDrained:=management.drained
  io.launch.valid:=stage.io.shared.launch.fire;io.launch.bits:=stage.io.shared.geometryTask.get.valid
  io.launchPayload:=stage.io.poolPayload.get;io.owners:=stage.io.shared.owned
  io.trap.valid:=stage.io.shared.trap.fire;io.trap.bits:=stage.io.shared.trap.bits
  private val imageBase=LayeredWindowPrimitiveBinImagePipeline.imageBase(native.writeCredits,65536)
  for(e<-0 until 4){val request=stage.io.memoryRequest;val response=stage.io.memoryResponse
    io.imageStore(e):=request.fire && request.bits.isWrite &&
      request.bits.transactionId>=(imageBase+64*e).U && request.bits.transactionId<(imageBase+64*(e+1)).U
    io.imageAck(e):=response.fire && response.bits.transactionId>=(imageBase+64*e).U &&
      response.bits.transactionId<(imageBase+64*(e+1)).U
  }
}
