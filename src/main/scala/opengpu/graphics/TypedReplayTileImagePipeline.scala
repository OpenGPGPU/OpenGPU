package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class TypedReplayMemorySpan extends Bundle {val base=UInt(32.W);val limit=UInt(33.W)}
class TypedReplayTileDescriptor extends Bundle {
  val image=new TypedTileImageDescriptor;val tile=UInt(32.W);val states=new PrimitiveStateDescriptor
  val engines=UInt(3.W);val scratchStride=UInt(32.W);val scratch=Vec(4,new TypedReplayMemorySpan)
  /** Record, page and directory allocations protected from every FS prefix.
    * Empty spans are allowed; the enclosing owner supplies the actual arenas.
    */
  val protectedMemory=Vec(3,new TypedReplayMemorySpan)
}
class TypedReplayPrimitiveCompletion extends Bundle {
  val source=new PrimitiveBinReference;val status=UInt(3.W)
  /** 0 success/cancel,1 record/state admission/fetch,2 image/render. */
  val faultStage=UInt(2.W);val renderStarted=Bool();val stateReadLines=UInt(4.W);val stateHit=Bool()
  val render=new TypedTileRenderDrawCompletion
}
class TypedReplayTileCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val tile=UInt(32.W);val status=UInt(3.W);val faultStage=UInt(2.W);val faultAddress=UInt(32.W)
  val faultRecordValid=Bool();val faultRecord=new PrimitiveBinReference
  val accepted=UInt(64.W);val issued=UInt(64.W);val completed=UInt(64.W);val stateReadLines=UInt(64.W);val stateHits=UInt(64.W)
  val image=new TypedTileImageCompletion
}

/** One typed tile-image engine starting at resolved post-transform records.
  * Fetch immutable GFS1 state, select a private per-engine/per-state scratch
  * slot, intersect raster scissor with this tile, then rasterize/shade through
  * the existing typed image owner. No vertex task or clip/viewport pipeline is
  * instantiated. Every image store/resolve acknowledgement precedes completion.
  */
class TypedReplayTileImagePipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    f: FragmentShaderConfig=FragmentShaderConfig(),shared: SharedCuShaderConfig=SharedCuShaderConfig(4),
    g: GraphicsConfig=GraphicsConfig(),storage: TypedTileColorConfig=TypedTileColorConfig(),engine: Int=0,
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,groupedTexture: Boolean=false) extends Module {
  require(engine>=0 && engine<4 && p.varyings==f.varyings && f.colors==4)
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,1))else None
    val begin=Flipped(Decoupled(new TypedReplayTileDescriptor));val in=Flipped(Decoupled(new WindowPrimitiveReplay(p)))
    val primitiveDone=Decoupled(new TypedReplayPrimitiveCompletion);val end=Flipped(Decoupled(Bool()))
    val out=Decoupled(new TypedTilePixelQuadCompletion);val completion=Decoupled(new TypedReplayTileCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>Vec(2,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,32));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,32)))
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val image=Module(new TypedTileImagePipeline(config,p,f,shared,g,storage=storage,useSramBlackBoxes=useSramBlackBoxes,
    payload=payload,payloadOwner=payloadOwner,preparedGeometry=true,groupedTexture=groupedTexture))
  if(groupedTexture)io.grouped.get <> image.io.grouped.get
  private val reader=Module(new TypedFragmentStateReader(config,f,g))
  private object State extends ChiselEnum {val idle,validate,bind,ready,checkRecord,fetch,checkState,draw,waitDraw,emit,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedReplayTileDescriptor)
  private val current=Reg(new WindowPrimitiveReplay(p));private val shader=Reg(new TypedFragmentState(f))
  private val recordResult=Reg(new TypedReplayPrimitiveCompletion);private val renderResult=Reg(new TypedTileRenderDrawCompletion)
  private val imageResult=Reg(new TypedTileImageCompletion);private val result=Reg(new TypedReplayTileCompletion)
  private val imageOffered=RegInit(false.B);private val imageStarted=RegInit(false.B);private val imageDone=RegInit(false.B)
  private val stateOffered=RegInit(false.B);private val stateStarted=RegInit(false.B);private val drawOffered=RegInit(false.B)
  private val drawStarted=RegInit(false.B);private val drawDone=RegInit(false.B);private val owned=RegInit(false.B)
  private val accepted=RegInit(0.U(64.W));private val issued=RegInit(0.U(64.W));private val completed=RegInit(0.U(64.W))
  private val readLines=RegInit(0.U(64.W));private val hits=RegInit(0.U(64.W));private val currentLines=Reg(UInt(4.W));private val currentHit=Reg(Bool())
  private val haveSequence=RegInit(false.B);private val sequence=Reg(UInt(32.W));private val contexts=Reg(Vec(2,UInt(32.W)))
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W));private val faultAddress=RegInit(0.U(32.W))
  private val faultRecordValid=RegInit(false.B);private val faultRecord=Reg(new PrimitiveBinReference)
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  private val stop=status=/=0.U || externalCancel
  private val active=phase=/=State.idle && phase=/=State.complete
  private def fail(code: UInt,stage: Int,address: UInt=0.U): Unit=when(status===0.U && !externalCancel){
    status:=code;faultStage:=stage.U;faultAddress:=address;faultRecordValid:=owned;faultRecord:=current.source}
  io.begin.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle;io.cancelSource:=active && stop
  io.in.ready:=phase===State.ready && !stop && image.io.draw.ready
  io.primitiveDone.valid:=phase===State.emit;io.primitiveDone.bits:=recordResult
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  image.io.begin.valid:=imageOffered;image.io.begin.bits:=descriptor.image
  when(image.io.begin.fire){imageOffered:=false.B;imageStarted:=true.B}
  image.io.cancel:=imageStarted && stop && !drawOffered
  io.out<>image.io.out;io.shared<>image.io.shared
  payload.foreach{_=>for(i<-0 until 2){io.payload.get(i)<>image.io.payload.get(i)
    image.io.payload.get(i).context:=contexts(i);image.io.payload.get(i).cancel:=io.payload.get(i).cancel && imageStarted && !drawOffered}
    io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
  image.io.end.valid:=phase===State.ready && !io.in.valid && io.end.valid && image.io.end.ready && !stop
  image.io.end.bits:=io.end.bits;io.end.ready:=phase===State.ready && !io.in.valid && image.io.end.ready && !stop
  image.io.draw.valid:=drawOffered;image.io.draw.bits:=0.U.asTypeOf(image.io.draw.bits)
  image.io.primitive.get:=current.record.primitive
  private val selected=TypedFragmentStateLayout.scratch(shader,engine)
  image.io.draw.bits.version:=shader.version;image.io.draw.bits.shaders.version:=1.U
  image.io.draw.bits.shaders.fragment:=shader.fragment;image.io.draw.bits.shaders.fragment.scratch:=selected
  image.io.draw.bits.tests:=shader.tests;image.io.draw.bits.blend:=shader.blend
  private val raster=WireDefault(current.record.raster);private val colors=descriptor.image.pixels.colors
  private val width=ScreenLimit(raster.screenW,g.screenWidth);private val height=ScreenLimit(raster.screenH,g.screenHeight)
  private def clip(value: SInt,limit: UInt): UInt=value.max(0.S(34.W)).min(limit.zext.pad(34)).asUInt
  private val loX=Mux(raster.scissorEnable,raster.scissorMinX,0.U);private val loY=Mux(raster.scissorEnable,raster.scissorMinY,0.U)
  private val hiX=Mux(raster.scissorEnable,raster.scissorMaxX.min(width),width);private val hiY=Mux(raster.scissorEnable,raster.scissorMaxY.min(height),height)
  private val scissor=WireDefault(raster);scissor.scissorEnable:=true.B
  scissor.scissorMinX:=clip(loX.zext.pad(34).max(colors.originX.pad(34)),width)
  scissor.scissorMinY:=clip(loY.zext.pad(34).max(colors.originY.pad(34)),height)
  scissor.scissorMaxX:=clip(hiX.zext.pad(34).min(colors.originX.pad(34)+colors.tileWidth.zext.pad(34)),width)
  scissor.scissorMaxY:=clip(hiY.zext.pad(34).min(colors.originY.pad(34)+colors.tileHeight.zext.pad(34)),height)
  image.io.draw.bits.shaders.vertex.raster:=scissor
  when(image.io.draw.fire){drawOffered:=false.B;drawStarted:=true.B;issued:=issued+1.U}
  image.io.drawCompletion.ready:=drawStarted && !drawDone
  when(image.io.drawCompletion.fire){renderResult:=image.io.drawCompletion.bits;drawDone:=true.B
    assert(image.io.drawCompletion.bits.source.preparedGeometry,"replay dispatched a vertex source")
    when(image.io.drawCompletion.bits.status===0.U){completed:=completed+1.U}
      .otherwise{fail(image.io.drawCompletion.bits.status,2,image.io.drawCompletion.bits.source.faultAddress)}}
  image.io.completion.ready:=imageStarted && !imageDone
  when(image.io.completion.fire){imageResult:=image.io.completion.bits;imageDone:=true.B
    // A terminal pass result closes child controls that could no longer be
    // accepted after a spontaneous attachment/render failure.
    drawOffered:=false.B
    when(image.io.completion.bits.status=/=0.U){fail(image.io.completion.bits.status,2,image.io.completion.bits.faultAddress)}}
  reader.io.lookup.valid:=stateOffered;reader.io.lookup.bits.arena:=descriptor.states;reader.io.lookup.bits.handle:=current.record.state
  reader.io.cancel:=stop && !stateOffered;reader.io.invalidate:=io.begin.fire
  when(reader.io.lookup.fire){stateOffered:=false.B;stateStarted:=true.B}
  reader.io.result.ready:=stateStarted
  when(reader.io.result.fire){stateStarted:=false.B;currentLines:=reader.io.result.bits.readLines;currentHit:=reader.io.result.bits.hit
    readLines:=readLines+reader.io.result.bits.readLines;hits:=hits+reader.io.result.bits.hit.asUInt
    when(reader.io.result.bits.status=/=0.U){fail(reader.io.result.bits.status,1,descriptor.states.base);phase:=State.closing}
      .otherwise{shader:=reader.io.result.bits.state;phase:=State.checkState}}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,32),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,32),2,pipe=true))
  arbiter.io.in(0).valid:=image.io.memoryRequest.valid;arbiter.io.in(0).bits:=image.io.memoryRequest.bits;image.io.memoryRequest.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=reader.io.memoryRequest.valid;arbiter.io.in(1).bits:=reader.io.memoryRequest.bits
  arbiter.io.in(1).bits.transactionId:=reader.io.memoryRequest.bits.transactionId+&16.U;reader.io.memoryRequest.ready:=arbiter.io.in(1).ready
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val imageResponse=io.memoryResponse.bits.transactionId<16.U
  private val stateResponse=io.memoryResponse.bits.transactionId>=16.U && io.memoryResponse.bits.transactionId<20.U
  image.io.memoryResponse.valid:=io.memoryResponse.valid && imageResponse;image.io.memoryResponse.bits:=io.memoryResponse.bits
  reader.io.memoryResponse.valid:=io.memoryResponse.valid && stateResponse;reader.io.memoryResponse.bits:=io.memoryResponse.bits
  reader.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId-16.U
  io.memoryResponse.ready:=Mux(imageResponse,image.io.memoryResponse.ready,stateResponse && reader.io.memoryResponse.ready)
  private val inFlight=RegInit(0.U(6.W));inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned replay/image memory response")}
  private val memoryDrained=inFlight===0.U && queue.io.count===0.U
  private def finishPrimitive(): Unit={
    recordResult:=0.U.asTypeOf(recordResult);recordResult.source:=current.source
    recordResult.status:=Mux(externalCancel && status===0.U,4.U,status);recordResult.faultStage:=faultStage
    recordResult.renderStarted:=drawStarted;recordResult.stateReadLines:=currentLines;recordResult.stateHit:=currentHit;recordResult.render:=renderResult;phase:=State.emit
  }
  // An image load fault cancels the active draw. Capture its authoritative
  // transfer cause before a canceled draw completion can become the first
  // replay failure. Keep this after child-result handlers for same-cycle ties.
  when(active && imageStarted && image.io.transferFailure.valid){
    fail(image.io.transferFailure.bits.status,2,image.io.transferFailure.bits.address)
  }
  when(active && externalCancel && status===0.U){status:=4.U;faultStage:=0.U;faultRecordValid:=false.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;faultStage:=0.U;faultAddress:=0.U;faultRecordValid:=false.B
      imageStarted:=false.B;imageDone:=false.B;stateOffered:=false.B;stateStarted:=false.B;drawOffered:=false.B;drawStarted:=false.B;drawDone:=false.B;owned:=false.B
      accepted:=0.U;issued:=0.U;completed:=0.U;readLines:=0.U;hits:=0.U;haveSequence:=false.B;imageResult:=0.U.asTypeOf(imageResult)
      for(i<-0 until 2)contexts(i):=io.payload.map(_(i).context).getOrElse(0.U);phase:=State.validate}}
    is(State.validate){
      val d=descriptor;val a=d.states;val count=(a.limit-a.base)>>9
      def legalSpan(s: TypedReplayMemorySpan): Bool=s.base(5,0)===0.U && s.limit(5,0)===0.U && s.limit>=s.base && s.limit<=(BigInt(1)<<32).U
      def overlap(x: TypedReplayMemorySpan,y: TypedReplayMemorySpan): Bool=x.base<x.limit && y.base<y.limit && x.base<y.limit && x.limit>y.base
      val state=Wire(new TypedReplayMemorySpan);state.base:=a.base;state.limit:=a.limit
      val valid=a.version===1.U && a.base(5,0)===0.U && a.limit>a.base && a.limit<=(BigInt(1)<<32).U && ((a.limit-a.base)&511.U)===0.U &&
        a.identity.asUInt===d.image.pixels.colors.identity.asUInt && d.engines>=1.U && d.engines<=4.U && engine.U<d.engines &&
        d.scratchStride>=64.U && d.scratchStride(5,0)===0.U &&
        d.protectedMemory.map(s=>legalSpan(s) && !overlap(s,state)).reduce(_ && _) &&
        d.scratch.zipWithIndex.map{case(s,e)=>Mux(e.U<d.engines,legalSpan(s) && s.base.pad(64)+count.pad(64)*d.scratchStride.pad(64)<=s.limit &&
          !overlap(s,state) && !d.protectedMemory.map(overlap(s,_)).reduce(_ || _),!s.asUInt.orR)}.reduce(_ && _) &&
        (for(x<-0 until 4;y<-x+1 until 4)yield y.U>=d.engines || !overlap(d.scratch(x),d.scratch(y))).reduce(_ && _)
      when(stop){phase:=State.closing}.elsewhen(!valid){fail(1.U,1);phase:=State.closing}.otherwise{imageOffered:=true.B;phase:=State.bind}
    }
    is(State.bind){when(stop || imageDone){phase:=State.closing}.elsewhen(imageStarted && image.io.draw.ready){phase:=State.ready}}
    is(State.ready){when(stop || imageDone){phase:=State.closing}.elsewhen(io.in.fire){
      current:=io.in.bits;owned:=true.B;accepted:=accepted+1.U;drawStarted:=false.B;drawDone:=false.B;currentLines:=0.U;currentHit:=false.B
      renderResult:=0.U.asTypeOf(renderResult);phase:=State.checkRecord
    }.elsewhen(io.end.fire){phase:=State.closing}}
    is(State.checkRecord){
      val s=current.source;val r=current.record
      val valid=s.identity.asUInt===descriptor.states.identity.asUInt && r.primitive.clipped.primitive.identity.asUInt===s.identity.asUInt && s.tile===descriptor.tile &&
        s.ordinal===accepted-1.U && (!haveSequence || r.sequence>sequence) && s.reference.state===r.state && s.reference.sequence===r.sequence && s.reference.token===r.token
      when(stop || imageDone){phase:=State.closing}.elsewhen(!valid){fail(1.U,1);phase:=State.closing}
        .otherwise{sequence:=r.sequence;haveSequence:=true.B;stateOffered:=true.B;phase:=State.fetch}
    }
    is(State.fetch){when((stop || imageDone) && !stateOffered && !stateStarted && !reader.io.busy){phase:=State.closing}}
    is(State.checkState){
      val slots=(0 until 4).map{e=>val v=TypedFragmentStateLayout.scratch(shader,e)
        val start=descriptor.scratch(e).base.pad(64)+current.record.state.pad(64)*descriptor.scratchStride.pad(64)
        e.U>=shader.engineCount || (v.base>=start && v.limit<=start+descriptor.scratchStride.pad(64))}.reduce(_ && _)
      val valid=shader.engineCount===descriptor.engines && shader.fragment.varyingSlots===current.record.primitive.clipped.primitive.varyingSlots &&
        shader.fragment.sampleMode===current.record.raster.sampleMode && slots
      when(stop || imageDone){phase:=State.closing}.elsewhen(!valid){fail(1.U,1);phase:=State.closing}
        .otherwise{drawOffered:=true.B;phase:=State.draw}
    }
    is(State.draw){when(image.io.draw.fire){phase:=State.waitDraw}.elsewhen(imageDone){phase:=State.closing}}
    is(State.waitDraw){when(drawDone){finishPrimitive()}.elsewhen(imageDone){phase:=State.closing}}
    is(State.emit){when(io.primitiveDone.fire){owned:=false.B;phase:=Mux(stop || imageDone,State.closing,State.ready)}}
    is(State.closing){when(!stateOffered && !stateStarted && !reader.io.busy && !drawOffered){
      when(owned && (!drawStarted || drawDone || imageDone)){finishPrimitive()}
        .elsewhen(!owned && !imageOffered && (!imageStarted || (imageDone && !image.io.busy)) && memoryDrained){phase:=State.finish}}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.states.identity;result.tile:=descriptor.tile
      result.status:=Mux(externalCancel && status===0.U,4.U,status);result.faultStage:=faultStage;result.faultAddress:=faultAddress
      result.faultRecordValid:=faultRecordValid;result.faultRecord:=faultRecord;result.accepted:=accepted;result.issued:=issued;result.completed:=completed
      result.stateReadLines:=readLines;result.stateHits:=hits;result.image:=imageResult;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!image.io.busy && !reader.io.busy && memoryDrained && !imageOffered && !drawOffered && !stateOffered,
    "typed replay completion escaped state/shader/pixel/publication drain")}
}
