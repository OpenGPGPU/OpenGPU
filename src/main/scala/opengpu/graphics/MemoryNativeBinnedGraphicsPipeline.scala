package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

/** Path: native binned external rendering, selected by `GpuHostSystemAxi`'s
  * `nativeBinnedGraphics` parameter; this is the four-engine
  * product-qualification profile. See README.md in this package.
  */
case class NativeBinnedGraphicsConfig(vertex: VertexShaderStageConfig=VertexShaderStageConfig(postTransformEntries=PostTransformVertexCache.defaultEntries),
    fragment: FragmentShaderConfig=FragmentShaderConfig(),storage: TypedTileColorConfig=TypedTileColorConfig(),
    engines: Int=2,pageBytes: Int=4096,writeCredits: Int=8,tileSlots: Int=8,pagedCapacity: Int=16384,
    payload: Option[ShaderPayloadConfig]=Some(ShaderPayloadConfig(maxPayloadBytes=16*1024,owners=3,ports=8)),
    translateImageViews: Boolean=true,groupedTexture: Boolean=false,transformFeedback: Boolean=false,
    renderTargetExtent: Option[(Int,Int)]=None,preflightDescriptors: Boolean=false,nativeGeometry:Boolean=false,sampleShading:Boolean=false) {
  renderTargetExtent.foreach{case(width,height)=>
    require(width>0 && width<65536 && height>0 && height<65536,
      "native render-target extents must fit positive 16-bit dimensions")}
  /** Native raster/bin/image bounds are independent of the display mode.
    * Keep display and legacy framebuffer storage at their original extent.
    */
  def renderGraphics(display: GraphicsConfig): GraphicsConfig=renderTargetExtent match {
    case None=>display
    case Some((width,height))=>
      require(width>=display.screenWidth && height>=display.screenHeight,
        "native render-target maximum must accommodate the display extent")
      display.copy(screenWidth=width,screenHeight=height)
  }
  def descriptorPreflight: Boolean=preflightDescriptors || storage.nativeAdvancedDraws || nativeGeometry
  require(!nativeGeometry || (translateImageViews && pagedCapacity==65536 && vertex.varyings==16 &&
    vertex.input.attributes==16 && vertex.input.bindings==16),"GNJ2/GNF3 retain translated full-capacity native metadata")
  require(!sampleShading || nativeGeometry,"sample shading is opt-in on the complete native layered route")
  require(engines>=1 && engines<=4 && vertex.varyings==fragment.varyings && fragment.colors==4)
  payload.foreach(q=>require(q.owners>=engines+1,"vertex and fragment engines need distinct payload owners"))
}

object NativeBinnedGraphicsConfig {
  /** Four tile engines retain complete native shader reservations. */
  def fourEngine: NativeBinnedGraphicsConfig=NativeBinnedGraphicsConfig(
    storage=TypedTileColorConfig(parallelLanes=true,parallelDepth=true,
      nativeSamples=true,streamingWordPorts=8),engines=4,
    payload=Some(NativeShaderPayloadSizing.pool(owners=5,ports=12)),groupedTexture=true)

  /** Candidate four-engine profile with the complete 2048-axis API grid. */
  def fourEngineApiExtent: NativeBinnedGraphicsConfig=
    fourEngine.copy(renderTargetExtent=Some((2048,2048)),pagedCapacity=65536)
}

object MemoryNativeBinnedGraphicsPipeline {
  val renderBase=16
  def binSlots(n: NativeBinnedGraphicsConfig): Int=VertexBinPipeline.memoryTransactions(n.engines,n.writeCredits,n.pagedCapacity)
  def renderSlots(n: NativeBinnedGraphicsConfig): Int=if(n.nativeGeometry)
    opengpu.system.NativeLayeredVertexBinImagePipeline.transactions(n.engines,n.writeCredits,n.pagedCapacity)
    else TypedVertexBinImagePipeline.memoryTransactions(n.engines,n.writeCredits,n.pagedCapacity)
  def payloadPins(n:NativeBinnedGraphicsConfig):Int=if(n.nativeGeometry)8+6*n.engines else TypedVertexBinImagePipeline.payloadPins(n.engines)
  def transactions(n: NativeBinnedGraphicsConfig): Int=1<<log2Ceil(renderBase+renderSlots(n))
  /** Image transfer IDs are stable in both address-space profiles. */
  def imageTransfer(id: UInt,n: NativeBinnedGraphicsConfig): Bool=(0 until n.engines).map{e=>
    val base=if(n.nativeGeometry)renderBase+LayeredWindowPrimitiveBinImagePipeline.imageBase(n.writeCredits,n.pagedCapacity)+64*e+2
      else renderBase+binSlots(n)+32*e
    id>=(base+8).U && id<(base+16).U
  }.reduce(_ || _)
  /** GNC2 translates every request. GNC1 retains trusted physical image views. */
  def physical(id: UInt,n: NativeBinnedGraphicsConfig): Bool=if(n.translateImageViews)false.B else imageTransfer(id,n)
}

class NativeBinnedGraphicsPipelineIO(config:GpuConfig,native:NativeBinnedGraphicsConfig,sharedConfig:SharedCuShaderConfig,
    commandIdWidth:Int,fragmentImageAvailable:Boolean) extends Bundle {
  private val p=native.vertex;private val engines=native.engines
  private val memoryTransactions=MemoryNativeBinnedGraphicsPipeline.transactions(native)

    val feedback=if(native.transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val grouped=if(native.groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,engines+1))else None
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(commandIdWidth)))
    val completion=Decoupled(new NativeBinnedGraphicsCompletion(commandIdWidth,p))
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,geometryAvailable=native.nativeGeometry,imageAvailable=fragmentImageAvailable))
    val payload=native.payload.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=native.payload.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=native.payload.map(q=>Output(Vec(MemoryNativeBinnedGraphicsPipeline.payloadPins(native),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val fragmentEvents=Output(new FragmentPerformanceEvents)
    val cancel=Input(Bool());val busy=Output(Bool())
  }

/** Native GPU-memory job -> actual shared-CU vertex/bin/image ownership.
  * Descriptor and renderer transactions occupy disjoint tagged ranges. The
  * enclosing host translates GNC2 image views as well as descriptor/resource
  * requests; only GNC1 preserves trusted physical image requests. Allocation,
  * code and VM ownership remain pinned through held completion.
  */
private[graphics] class MemoryLegacyNativeBinnedGraphicsPipeline(config: GpuConfig=GpuConfig(),native: NativeBinnedGraphicsConfig=NativeBinnedGraphicsConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),g: GraphicsConfig=GraphicsConfig(),commandIdWidth: Int=8,
    viewport: Fp32ViewportConfig=Fp32ViewportConfig(),useSramBlackBoxes: Boolean=false,
    fragmentImageAvailable: Boolean=false) extends Module {
  private val p=native.vertex;private val f=native.fragment;private val payload=native.payload
  private val sharedConfig=shared;private val engines=native.engines
  private val renderGraphics=native.renderGraphics(g)
  val memoryTransactions=MemoryNativeBinnedGraphicsPipeline.transactions(native)
  private val idBits=log2Ceil(memoryTransactions)
  private val renderBase=MemoryNativeBinnedGraphicsPipeline.renderBase
  private val renderSlots=MemoryNativeBinnedGraphicsPipeline.renderSlots(native)
  val io=IO(new NativeBinnedGraphicsPipelineIO(config,native,shared,commandIdWidth,fragmentImageAvailable))

  private val reader=Module(new NativeBinnedGraphicsJobReader(config,commandIdWidth,p,engines,native.translateImageViews,
    allowWideTileCount=native.pagedCapacity==65536,preflightDescriptors=native.descriptorPreflight,
    f=f,g=renderGraphics,nativeAdvanced=native.storage.nativeAdvancedDraws,imageAvailable=fragmentImageAvailable))
  private val image=Module(new TypedVertexBinImagePipeline(config,p,f,shared,renderGraphics,viewport,engines,
    native.pageBytes,native.writeCredits,native.tileSlots,native.pagedCapacity,native.storage,useSramBlackBoxes,payload,groupedTexture=native.groupedTexture,transformFeedback=native.transformFeedback,fragmentImageAvailable=fragmentImageAvailable))
  if(native.transformFeedback){
    val scope=RegInit(0.U.asTypeOf(new TransformFeedbackScope))
    when(io.submit.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,image.io.feedback.get,Mux(io.submit.fire,io.feedback.get.scope,scope), !io.busy)
  }
  if(native.groupedTexture)io.grouped.get <> image.io.grouped.get
  io.fragmentEvents:=image.io.fragmentEvents
  val recordBytes=image.recordBytes
  reader.io.submit<>io.submit;io.completion<>reader.io.completion
  image.io.begin<>reader.io.begin;image.io.draw<>reader.io.draw;image.io.end<>reader.io.end
  reader.io.drawCompletion<>image.io.drawCompletion;reader.io.passCompletion<>image.io.completion
  image.io.cancel:=reader.io.cancelCore
  reader.io.cancel:=io.cancel || io.payload.map(ps=>VecInit(ps.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  io.shared<>image.io.shared;io.out<>image.io.out;io.primitiveDone<>image.io.primitiveDone;io.tileDone<>image.io.tileDone
  payload.foreach{_=>
    val contexts=Reg(Vec(engines+1,UInt(32.W)))
    for(i<-0 until engines+1){
      when(io.submit.fire){contexts(i):=io.payload.get(i).context}
      io.payload.get(i)<>image.io.payload.get(i)
      image.io.payload.get(i).context:=contexts(i)
      image.io.payload.get(i).cancel:=io.payload.get(i).cancel && reader.io.cancelCore
    }
    io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get
  }
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  private val sources=Seq(reader.io.memoryRequest,image.io.memoryRequest)
  private val replies=Seq(reader.io.memoryResponse,image.io.memoryResponse)
  private val id=io.memoryResponse.bits.transactionId
  private val descriptorResponse=id<reader.memoryTransactions.U
  private val renderResponse=id>=renderBase.U && id<(renderBase+renderSlots).U
  for(i<-0 until 2){
    arbiter.io.in(i).valid:=sources(i).valid;arbiter.io.in(i).bits:=sources(i).bits
    arbiter.io.in(i).bits.transactionId:=sources(i).bits.transactionId.pad(idBits)+(if(i==0)0 else renderBase).U(idBits.W)
    sources(i).ready:=arbiter.io.in(i).ready
    replies(i).valid:=io.memoryResponse.valid && (if(i==0)descriptorResponse else renderResponse)
    replies(i).bits:=io.memoryResponse.bits
    replies(i).bits.transactionId:=id-(if(i==0)0 else renderBase).U
  }
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  io.memoryResponse.ready:=Mux(descriptorResponse,reader.io.memoryResponse.ready,Mux(renderResponse,image.io.memoryResponse.ready,false.B))
  when(io.memoryResponse.valid){assert(descriptorResponse || renderResponse,"native binned reply outside reserved namespaces")}
  private val inFlight=RegInit(0.U(log2Ceil(memoryTransactions+3).W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"native binned reply without an accepted request")}
  io.busy:=reader.io.busy || image.io.busy || queue.io.count.orR || inFlight.orR
  when(io.completion.valid){assert(!image.io.busy && queue.io.count===0.U && inFlight===0.U,"native binned completion escaped renderer or memory ownership")}
}

/** Public host entry: the checked configuration selects one complete native
  * submission/version contract. The ordinary GNJ1/GNF1/2 implementation is
  * retained unchanged inside its branch; GS uses the actual GNJ2/GNF3/GNY2
  * source/bin/layered image/writer path.
  */
class MemoryNativeBinnedGraphicsPipeline(config:GpuConfig=GpuConfig(),native:NativeBinnedGraphicsConfig=NativeBinnedGraphicsConfig(),
    shared:SharedCuShaderConfig=SharedCuShaderConfig(4),g:GraphicsConfig=GraphicsConfig(),commandIdWidth:Int=8,
    viewport:Fp32ViewportConfig=Fp32ViewportConfig(),useSramBlackBoxes:Boolean=false,fragmentImageAvailable:Boolean=false) extends Module {
  val memoryTransactions=MemoryNativeBinnedGraphicsPipeline.transactions(native)
  val io=IO(new NativeBinnedGraphicsPipelineIO(config,native,shared,commandIdWidth,fragmentImageAvailable))
  val recordBytes=WindowPrimitiveRecordLayout.lineCount(new WindowPrimitiveRecord(native.vertex))*64
  if(native.nativeGeometry){
    val renderer=Module(new MemoryNativeLayeredGraphicsPipeline(config,native,shared,g,commandIdWidth,viewport,useSramBlackBoxes,fragmentImageAvailable))
    io<>renderer.io
  }else{
    val renderer=Module(new MemoryLegacyNativeBinnedGraphicsPipeline(config,native,shared,g,commandIdWidth,viewport,useSramBlackBoxes,fragmentImageAvailable))
    io<>renderer.io
  }
}
