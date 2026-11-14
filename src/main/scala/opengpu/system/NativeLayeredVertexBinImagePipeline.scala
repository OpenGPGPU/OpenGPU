package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

class NativeLayeredVertexDraw(p:VertexShaderStageConfig,idWidth:Int) extends Bundle {
  val draw=new VertexBinDraw(p);val geometry=Valid(new NativeGeometryProgram)
  val commandId=UInt(idWidth.W);val taskId=UInt(32.W)
}
class NativeLayeredVertexBinImageCompletion(p:VertexShaderStageConfig) extends Bundle {
  val version=UInt(8.W);val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val faultStage=UInt(2.W)
  val draws=UInt(64.W);val admittedParts=UInt(33.W)
  val receivedParts=UInt(145.W);val droppedParts=UInt(145.W)
  val submittedGeometryPrimitives=UInt(128.W);val completedGeometryInvocations=UInt(134.W)
  val emittedGeometryPrimitives=UInt(142.W)
  val sourceFaultValid=Bool();val sourceFault=new NativeGeometryVertexViewportCompletion
  val faultDrawValid=Bool();val faultDraw=UInt(64.W)
  val images=new LayeredWindowPrimitiveBinImageCompletion(p)
}
object NativeLayeredVertexBinImagePipeline {
  def sourceBase(engines:Int,writeCredits:Int,pagedCapacity:Int):Int=
    LayeredWindowPrimitiveBinImagePipeline.transactions(engines,writeCredits,pagedCapacity)
  def transactions(engines:Int,writeCredits:Int,pagedCapacity:Int):Int=
    1<<log2Ceil(sourceBase(engines,writeCredits,pagedCapacity)+8)
}

/** Actual mixed native source -> ONE durable producer/directory -> original
  * chain dispatch -> real layered image engines. Ordinary draws bypass GS on
  * the same VS/assembly/clip/viewport source and publish GWP1/layer zero. GS
  * draws publish GWP2 with their actual output layer. A single pass sequence,
  * record arena and directory preserve inter-draw ordering across both kinds.
  * All source and image clients share the same physical CU ownership router.
  */
class NativeLayeredVertexBinImagePipeline(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),
    engines:Int=4,pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=65536,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,groupedTexture:Boolean=false,fragmentImageAvailable:Boolean=false,
    transformFeedback:Boolean=false,sampleShading:Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits)
  private val sharedConfig=shared;private val payloadConfig=payload
  private val sourceBase=NativeLayeredVertexBinImagePipeline.sourceBase(engines,writeCredits,pagedCapacity)
  val memoryTransactions=NativeLayeredVertexBinImagePipeline.transactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredWindowPrimitiveBinImageDescriptor))
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val draw=Flipped(Decoupled(new NativeLayeredVertexDraw(p,sharedConfig.taskIdWidth)))
    val drawCompletion=Decoupled(new NativeGeometryVertexBinDrawCompletion);val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new LayeredTypedPixelQuadCompletion))
    val layerDone=Vec(engines,Decoupled(new LayeredTypedImageLayerCompletion))
    val tileDone=Decoupled(new LayeredTileChainCompletion)
    val completion=Decoupled(new NativeLayeredVertexBinImageCompletion(p))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,geometryAvailable=true,imageAvailable=fragmentImageAvailable))
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,engines+1))else None
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4+6*engines+4,Valid(new ShaderPayloadPin(q)))))
    val fragmentEvents=Output(new FragmentPerformanceEvents)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new NativeGeometryVertexViewportPipeline(config,p,shared,viewport,useSramBlackBoxes,
    payload,0,groupedTexture,allowOrdinary=true,transformFeedback=transformFeedback))
  private val images=Module(new LayeredWindowPrimitiveBinImagePipeline(config,p,f,shared,g,engines,pageBytes,
    writeCredits,tileSlots,pagedCapacity,storage,useSramBlackBoxes,payload,0,groupedTexture,fragmentImageAvailable,sampleShading=sampleShading))
  val recordBytes=images.recordBytes
  private val router=Module(new GeometryFragmentShaderRouter(config,shared.numComputeUnits,shared.taskIdWidth,
    payload,imageAvailable=fragmentImageAvailable))
  if(fragmentImageAvailable){
    val a=router.io.geometry;val b=source.io.shared
    a.nativeResources:=b.nativeResources;a.uniformResources:=b.uniformResources;a.imageTask.get:=0.U.asTypeOf(new ShaderImageTask)
    a.launch<>b.launch;b.completion<>a.completion;b.trap<>a.trap;b.texture<>a.texture;b.owned:=a.owned;b.taskIds:=a.taskIds
    a.invalidate<>b.invalidate;b.invalidateDone<>a.invalidateDone
    a.geometryTask.get:=b.geometryTask.get;a.geometryCancel.get:=b.geometryCancel.get;b.geometryEvent.get<>a.geometryEvent.get
  }else{router.io.geometry<>source.io.shared}
  router.io.fragment<>images.io.shared;io.shared<>router.io.pool
  io.fragmentEvents:=images.io.fragmentEvents
  if(groupedTexture){io.grouped.get.client(0)<>source.io.grouped.get;io.grouped.get.cuMask(0):=source.io.shared.owned
    for(e<-0 until engines){io.grouped.get.client(e+1)<>images.io.grouped.get.client(e);io.grouped.get.cuMask(e+1):=images.io.grouped.get.cuMask(e)}}
  payload.foreach{_=>
    io.payload.get(0)<>source.io.payload.get
    for(e<-0 until engines)io.payload.get(e+1)<>images.io.payload.get(e)
    router.io.clientPayload.get(0):=source.io.payload.get.task;router.io.clientPayload.get(1):=images.io.poolPayload.get
    io.poolPayload.get:=router.io.poolPayload.get
    for(i<-0 until 4)io.payloadPins.get(i):=source.io.payloadPins.get(i)
    for(i<-0 until 6*engines)io.payloadPins.get(4+i):=images.io.payloadPins.get(i)
    for(i<-0 until 4)io.payloadPins.get(4+6*engines+i):=router.io.payloadPins.get(i)
  }
  io.primitiveDone<>images.io.primitiveDone;io.out<>images.io.out;io.layerDone<>images.io.layerDone;io.tileDone<>images.io.tileDone
  private object State extends ChiselEnum {val idle,start,ready,validate,drawing,emitDraw,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new LayeredWindowPrimitiveBinImageDescriptor)
  private val draw=Reg(new NativeLayeredVertexDraw(p,shared.taskIdWidth))
  private val feedbackScope=if(transformFeedback)Some(Reg(new TransformFeedbackScope))else None
  if(transformFeedback){
    when(io.begin.fire){feedbackScope.get:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,source.io.feedback.get,
      Mux(io.begin.fire,io.feedback.get.scope,feedbackScope.get),phase===State.idle)
  }
  private val result=Reg(new NativeLayeredVertexBinImageCompletion(p));private val drawResult=Reg(new NativeGeometryVertexBinDrawCompletion)
  private val sourceResult=Reg(new NativeGeometryVertexViewportCompletion);private val imageResult=Reg(new LayeredWindowPrimitiveBinImageCompletion(p))
  private val imageOffered=RegInit(false.B);private val imageStarted=RegInit(false.B);private val imageDone=RegInit(false.B)
  private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val drawOwned=RegInit(false.B);private val draws=RegInit(0.U(64.W));private val nextSequence=RegInit(0.U(33.W));private val firstSequence=Reg(UInt(33.W))
  private val received=RegInit(0.U(81.W));private val admitted=RegInit(0.U(81.W));private val dropped=RegInit(0.U(81.W))
  private val totalReceived=RegInit(0.U(145.W));private val totalDropped=RegInit(0.U(145.W))
  private val submittedGeometry=RegInit(0.U(128.W));private val geometryInvocations=RegInit(0.U(134.W))
  private val emittedGeometry=RegInit(0.U(142.W))
  private val status=RegInit(0.U(3.W));private val stage=RegInit(0.U(2.W))
  private val cancelled=RegInit(false.B);private val faultDrawValid=RegInit(false.B);private val faultDraw=Reg(UInt(64.W))
  private val sourceFaultValid=RegInit(false.B);private val sourceFault=Reg(new NativeGeometryVertexViewportCompletion)
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  private val sourceFailure=source.io.completion.valid && source.io.completion.bits.status=/=0.U && source.io.completion.bits.status=/=4.U
  private val stop=status=/=0.U || cancelled || externalCancel || sourceFailure || images.io.cancelSource
  private def fail(code:UInt,where:Int):Unit=when(status===0.U && code=/=0.U && code=/=4.U){
    status:=code;stage:=where.U;faultDrawValid:=drawOwned;faultDraw:=draws-1.U}
  io.begin.ready:=phase===State.idle && !externalCancel && !io.feedback.map(f=>f.busy || f.command.valid).getOrElse(false.B)
  io.busy:=phase=/=State.idle || io.feedback.map(_.busy).getOrElse(false.B)
  io.draw.ready:=phase===State.ready && !stop;io.end.ready:=phase===State.ready && !io.draw.valid && !stop && images.io.end.ready
  io.drawCompletion.valid:=phase===State.emitDraw;io.drawCompletion.bits:=drawResult
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  images.io.begin.valid:=imageOffered;images.io.begin.bits:=descriptor
  when(images.io.begin.fire){imageOffered:=false.B;imageStarted:=true.B}
  images.io.cancel:=imageStarted && (status=/=0.U || cancelled || externalCancel || sourceFailure)
  images.io.completion.ready:=imageStarted && !imageDone
  when(images.io.completion.fire){imageResult:=images.io.completion.bits;imageDone:=true.B;fail(images.io.completion.bits.status,3)
    when(images.io.completion.bits.status===4.U){cancelled:=true.B}}
  images.io.end.valid:=io.end.valid && io.end.ready;images.io.end.bits:=io.end.bits
  source.io.task.valid:=sourceOffered;source.io.task.bits:=0.U.asTypeOf(source.io.task.bits)
  source.io.task.bits.source.draw:=draw.draw.vertex.geometry;source.io.task.bits.source.program:=draw.geometry.bits
  source.io.task.bits.source.commandId:=draw.commandId;source.io.task.bits.source.taskId:=draw.taskId
  source.io.task.bits.viewport:=draw.draw.vertex.viewport;source.io.ordinary.get:= !draw.geometry.valid
  when(source.io.task.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  source.io.cancel:=sourceStarted && stop;source.io.completion.ready:=sourceStarted && !sourceDone
  when(source.io.completion.fire){sourceDone:=true.B;sourceResult:=source.io.completion.bits
    assert(source.io.completion.bits.preparedParts===received,"mixed native source lost a window part")
    when(draw.geometry.valid){submittedGeometry:=submittedGeometry+source.io.completion.bits.source.submittedPrimitives
      geometryInvocations:=geometryInvocations+source.io.completion.bits.source.completedInvocations
      emittedGeometry:=emittedGeometry+source.io.completion.bits.source.emittedPrimitives}
    when(source.io.completion.bits.status===4.U){cancelled:=true.B}
    when(sourceFailure){fail(source.io.completion.bits.status,2)
      when(status===0.U){sourceFaultValid:=true.B;sourceFault:=source.io.completion.bits}}}
  images.io.in.valid:=sourceStarted && !stop && !nextSequence(32) && source.io.out.valid && images.io.in.ready
  images.io.in.bits.primitive:=source.io.out.bits.window;images.io.in.bits.raster:=draw.draw.raster
  images.io.in.bits.geometry:=0.U.asTypeOf(images.io.in.bits.geometry)
  when(draw.geometry.valid){images.io.in.bits.geometry.valid:=true.B
    images.io.in.bits.geometry.bits.inputPrimitiveId:=source.io.out.bits.inputPrimitiveId
    images.io.in.bits.geometry.bits.invocationId:=source.io.out.bits.invocationId;images.io.in.bits.geometry.bits.layer:=source.io.out.bits.layer}
  images.io.in.bits.state:=draw.draw.state;images.io.in.bits.token:=draw.draw.token;images.io.in.bits.sequence:=nextSequence(31,0)
  source.io.out.ready:=sourceStarted && (stop || (!nextSequence(32) && images.io.in.ready))
  when(source.io.out.valid && nextSequence(32) && !stop){fail(6.U,3)}
  when(source.io.out.fire){received:=received+1.U;totalReceived:=totalReceived+1.U
    when(images.io.in.fire){admitted:=admitted+1.U;nextSequence:=nextSequence+1.U}
      .otherwise{dropped:=dropped+1.U;totalDropped:=totalDropped+1.U}}
  private val sourceDrained= !sourceOffered && (!sourceStarted || (sourceDone && !source.io.busy))
  private def finishDraw():Unit={drawResult:=0.U.asTypeOf(drawResult);drawResult.identity:=descriptor.states.identity;drawResult.ordinal:=draws-1.U
    drawResult.status:=Mux(status=/=0.U,status,Mux(cancelled || externalCancel,4.U,0.U));drawResult.faultStage:=stage
    drawResult.sourceStarted:=sourceStarted;drawResult.source:=sourceResult
    drawResult.receivedParts:=received;drawResult.admittedParts:=admitted;drawResult.droppedParts:=dropped
    drawResult.firstSequence:=firstSequence;drawResult.nextSequence:=nextSequence;phase:=State.emitDraw}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  private val tag=io.memoryResponse.bits.transactionId;private val sourceResponse=tag>=sourceBase.U && tag<(sourceBase+8).U
  for((request,i)<-Seq(images.io.memoryRequest,source.io.memoryRequest).zipWithIndex){
    val port=arbiter.io.in(i);port.valid:=request.valid;port.bits:=request.bits
    port.bits.transactionId:=request.bits.transactionId+&(if(i==0)0 else sourceBase).U;request.ready:=port.ready}
  images.io.memoryResponse.valid:=io.memoryResponse.valid && tag<sourceBase.U;images.io.memoryResponse.bits:=io.memoryResponse.bits
  source.io.memoryResponse.valid:=io.memoryResponse.valid && sourceResponse;source.io.memoryResponse.bits:=io.memoryResponse.bits
  source.io.memoryResponse.bits.transactionId:=tag-sourceBase.U
  io.memoryResponse.ready:=Mux(sourceResponse,source.io.memoryResponse.ready,tag<sourceBase.U && images.io.memoryResponse.ready)
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val inFlight=RegInit(0.U(log2Ceil(memoryTransactions+3).W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned mixed native response")}
  private val drained=sourceDrained && !imageOffered && (!imageStarted || (imageDone && !images.io.busy)) &&
    router.io.drained && queue.io.count===0.U && inFlight===0.U
  when(phase=/=State.idle && phase=/=State.complete && externalCancel){cancelled:=true.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;imageOffered:=true.B;imageStarted:=false.B;imageDone:=false.B
      sourceOffered:=false.B;sourceStarted:=false.B;sourceDone:=false.B;drawOwned:=false.B
      status:=0.U;stage:=0.U;cancelled:=false.B;faultDrawValid:=false.B;faultDraw:=0.U
      draws:=0.U;nextSequence:=0.U;totalReceived:=0.U;totalDropped:=0.U;sourceFaultValid:=false.B
      submittedGeometry:=0.U;geometryInvocations:=0.U;emittedGeometry:=0.U
      sourceFault:=0.U.asTypeOf(sourceFault);sourceResult:=0.U.asTypeOf(sourceResult);imageResult:=0.U.asTypeOf(imageResult);phase:=State.start}}
    is(State.start){when(stop || imageDone){phase:=State.closing}.elsewhen(imageStarted && images.io.in.ready){phase:=State.ready}}
    is(State.ready){when(stop || imageDone){phase:=State.closing}.elsewhen(io.draw.fire){draw:=io.draw.bits;draws:=draws+1.U;drawOwned:=true.B
      sourceStarted:=false.B;sourceDone:=false.B;sourceResult:=0.U.asTypeOf(sourceResult)
      received:=0.U;admitted:=0.U;dropped:=0.U;firstSequence:=nextSequence;phase:=State.validate}
      .elsewhen(io.end.fire){when(!io.end.bits){fail(1.U,1)};phase:=State.closing}}
    is(State.validate){
      val gs=Wire(new NativeGeometryVertexBinDraw(p,shared.taskIdWidth));gs:=0.U.asTypeOf(gs)
      gs.version:=draw.draw.version;gs.vertex:=source.io.task.bits;gs.raster:=draw.draw.raster;gs.state:=draw.draw.state;gs.token:=draw.draw.token
      val sourceLegal=Mux(draw.geometry.valid,NativeGeometryVertexBinDescriptor.legal(gs,descriptor.bins,g,p),
        VertexBinDescriptor.legal(draw.draw,descriptor.bins,g))
      val stateLegal=descriptor.states.base.pad(64)+(draw.draw.state.pad(64)<<9)+512.U<=descriptor.states.limit &&
        draw.draw.raster.sampleMode===descriptor.image.image.pixels.colors.sampleMode
      def overlap(a:UInt,b:UInt,c:UInt,d:UInt):Bool=a<b && c<d && a<d && c<b
      val enabled=TypedTileImageRules.enabled(descriptor.image.image.pixels)
      val loads=TypedTileImageRules.loads(descriptor.image.image.pixels)
      val protectedSpans=Seq(descriptor.states.base->descriptor.states.limit)++descriptor.scratch.take(engines).map(s=>s.base->s.limit)++
        descriptor.image.image.views.zipWithIndex.map{case(v,i)=>val used=enabled(i) && (v.store || loads(i)===AttachmentLoadOp.load.U)
          Mux(used,v.allocationBase,0.U)->Mux(used,v.allocationLimit,0.U)}++
        descriptor.image.image.resolves.map(r=>Mux(r.enable,r.destination.allocationBase,0.U)->Mux(r.enable,r.destination.allocationLimit,0.U))
      val v=draw.draw.vertex.geometry.vertex;val indirect=draw.draw.vertex.geometry.indirect
      val work=indirect.mode=/=0.U || (v.input.count=/=0.U && v.input.instances=/=0.U)
      val scratchSafe= !work || !protectedSpans.map{case(a,b)=>overlap(v.scratch.base,v.scratch.limit,a,b)}.reduce(_ || _)
      val controlSafe=indirect.mode===0.U || !protectedSpans.drop(1).map{case(a,b)=>overlap(indirect.view.base,indirect.view.limit,a,b)}.reduce(_ || _)
      val gsSafe= !draw.geometry.valid || !protectedSpans.drop(1).map{case(a,b)=>
        overlap(draw.geometry.bits.code.base,draw.geometry.bits.code.limit,a,b) ||
          (draw.geometry.bits.uniforms.version=/=0.U && overlap(draw.geometry.bits.uniforms.address,draw.geometry.bits.uniforms.address.pad(33)+64.U,a,b))}.reduce(_ || _)
      when(stop){phase:=State.closing}.elsewhen(!sourceLegal || !stateLegal || !scratchSafe || !controlSafe || !gsSafe){fail(1.U,1);phase:=State.closing}
        .otherwise{sourceOffered:=true.B;phase:=State.drawing}
    }
    is(State.drawing){when(sourceDrained && ((stop && (status=/=0.U || cancelled || externalCancel || imageDone)) || (!stop && images.io.in.ready))){finishDraw()}}
    is(State.emitDraw){when(io.drawCompletion.fire){drawOwned:=false.B;phase:=Mux(stop || imageDone,State.closing,State.ready)}}
    is(State.closing){when(sourceDrained && !imageOffered){when(drawOwned && (status=/=0.U || cancelled || externalCancel || imageDone)){finishDraw()}
      .elsewhen(!drawOwned && drained){phase:=State.finish}}}
    is(State.finish){result.version:=1.U;result.identity:=descriptor.states.identity
      result.status:=Mux(status=/=0.U,status,Mux(cancelled || externalCancel,4.U,0.U));result.faultStage:=stage
      result.draws:=draws;result.admittedParts:=nextSequence;result.receivedParts:=totalReceived;result.droppedParts:=totalDropped
      result.submittedGeometryPrimitives:=submittedGeometry;result.completedGeometryInvocations:=geometryInvocations
      result.emittedGeometryPrimitives:=emittedGeometry
      result.sourceFaultValid:=sourceFaultValid;result.sourceFault:=sourceFault
      result.faultDrawValid:=faultDrawValid;result.faultDraw:=faultDraw;result.images:=imageResult;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(drained,"mixed native completion preceded source/image/memory drain")}
}
