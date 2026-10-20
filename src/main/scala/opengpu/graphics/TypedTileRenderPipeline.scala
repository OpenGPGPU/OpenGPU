package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class TypedTileRenderDraw(p: VertexShaderStageConfig,f: FragmentShaderConfig) extends Bundle {
  val version=UInt(8.W);val shaders=new VertexFragmentDraw(p,f);val tests=new TypedLateTestState;val blend=new TypedBlendState
}
class TypedTileRenderDrawCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val ordinal=UInt(64.W);val status=UInt(3.W)
  /** 0 cancellation/success,1 admission,2 shader/geometry,3 pixel. */
  val faultStage=UInt(2.W);val source=new VertexFragmentCompletion
  val admittedQuads=UInt(99.W);val retiredQuads=UInt(99.W);val droppedQuads=UInt(99.W)
}
class TypedTileRenderCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val faultStage=UInt(2.W)
  val faultAddress=UInt(32.W);val faultOffset=UInt(17.W);val draws=UInt(64.W)
  val pixels=new TypedTilePixelCompletion
}

/** One resident tile pass, multiple ordered shared-CU vertex/fragment draws.
  * Child completion is local retirement, not backing-image publication. Native
  * bin/host admission and backing attachment transfers remain external.
  */
class TypedTileRenderPipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    f: FragmentShaderConfig=FragmentShaderConfig(),shared: SharedCuShaderConfig=SharedCuShaderConfig(4),
    g: GraphicsConfig=GraphicsConfig(),viewport: Fp32ViewportConfig=Fp32ViewportConfig(),
    storage: TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,preparedGeometry: Boolean=false) extends Module {
  require(f.colors==4 && g.maxSampleCount==4,"typed pixel interface requires four color locations and four sample-mask bits")
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TypedTilePixelDescriptor));val end=Flipped(Decoupled(Bool()))
    val draw=Flipped(Decoupled(new TypedTileRenderDraw(p,f)));val drawCompletion=Decoupled(new TypedTileRenderDrawCompletion)
    val primitive=if(preparedGeometry)Some(Input(new WindowPrimitive(p)))else None
    val out=Decoupled(new TypedTilePixelQuadCompletion);val completion=Decoupled(new TypedTileRenderCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>Vec(2,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val maintenance=Input(Bool());val memory=Flipped(Decoupled(new TypedTileColorMemoryRequest(storage)));val reply=Decoupled(new TypedTileColorMemoryReply)
    val refill=Decoupled(new TypedTileColorRefill(storage));val refillReply=Flipped(Decoupled(new TypedTileColorRefillReply))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new VertexFragmentPipeline(config,p,f,shared,g,viewport,useSramBlackBoxes,payload,payloadOwner,preparedGeometry,depthFormatInput=true))
  private val primitive=if(preparedGeometry)Some(Reg(new WindowPrimitive(p)))else None
  primitive.foreach(p=>source.io.primitive.get:=p)
  private val pixels=Module(new TypedTilePixelBackend(storage,useSramBlackBoxes))
  private object State extends ChiselEnum {val idle,validatePass,bind,active,validateDraw,drawing,emitDraw,externalReply,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTilePixelDescriptor);private val draw=Reg(new TypedTileRenderDraw(p,f))
  private val result=Reg(new TypedTileRenderCompletion);private val drawResult=Reg(new TypedTileRenderDrawCompletion)
  private val pixelResult=Reg(new TypedTilePixelCompletion);private val sourceResult=Reg(new VertexFragmentCompletion)
  private val pixelOffered=RegInit(false.B);private val pixelStarted=RegInit(false.B);private val pixelDone=RegInit(false.B)
  private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val drawOwned=RegInit(false.B);private val queued=RegInit(false.B);private val packet=Reg(new TypedTilePixelQuad)
  private val admitted=RegInit(0.U(99.W));private val retired=RegInit(0.U(99.W));private val dropped=RegInit(0.U(99.W));private val draws=RegInit(0.U(64.W))
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W));private val faultAddress=RegInit(0.U(32.W));private val faultOffset=RegInit(0.U(17.W))
  private val contexts=Reg(Vec(2,UInt(32.W)))
  private val active=phase=/=State.idle && phase=/=State.complete
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  private val sourceFailure=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || externalCancel || pixels.io.cancelSource || sourceFailure
  private def fail(code: UInt,stage: UInt,address: UInt=0.U,offset: UInt=0.U): Unit={
    when(status===0.U && !externalCancel){status:=code;faultStage:=stage;faultAddress:=address;faultOffset:=offset}
  }
  io.shared<>source.io.shared;io.memoryRequest<>source.io.memoryRequest;source.io.memoryResponse<>io.memoryResponse
  payload.foreach{_=>
    for(i<-0 until 2){io.payload.get(i)<>source.io.payload.get(i)
      source.io.payload.get(i).context:=contexts(i);source.io.payload.get(i).cancel:=io.payload.get(i).cancel && sourceStarted}
    io.poolPayload.get:=source.io.poolPayload.get;io.payloadPins.get:=source.io.payloadPins.get
  }
  pixels.io.begin.valid:=pixelOffered;pixels.io.begin.bits:=descriptor
  when(pixels.io.begin.fire){pixelOffered:=false.B;pixelStarted:=true.B}
  source.io.draw.valid:=sourceOffered;source.io.draw.bits:=draw.shaders
  source.io.depthFormat.get:=descriptor.depth.format
  when(source.io.draw.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  source.io.cancel:=sourceStarted && stop
  // A quad is captured only while the idle pixel owner can accept it next cycle.
  // Once offered, delay child cancellation until that irrevocable offer drains.
  source.io.out.ready:=sourceStarted && (stop || (!queued && pixels.io.in.ready))
  when(source.io.out.fire){when(stop){dropped:=dropped+1.U}.otherwise{
    packet.color.quad:=source.io.out.bits;packet.state:=draw.tests;packet.color.blend:=draw.blend
    for(i<-0 until 4)packet.color.samples(i):=source.io.out.bits.lanes(i).coverageMask
    queued:=true.B
  }}
  pixels.io.in.valid:=queued;pixels.io.in.bits:=packet
  when(pixels.io.in.fire){queued:=false.B;admitted:=admitted+1.U}
  pixels.io.cancel:=pixelStarted && (status=/=0.U || externalCancel || sourceFailure) && !queued
  io.out<>pixels.io.out
  when(io.out.fire){retired:=retired+1.U}
  when(pixels.io.out.valid && pixels.io.out.bits.status=/=0.U){fail(pixels.io.out.bits.status,3.U,offset=pixels.io.out.bits.faultOffset)}
  source.io.completion.ready:=sourceStarted && !sourceDone
  when(source.io.completion.fire){sourceResult:=source.io.completion.bits;sourceDone:=true.B
    assert(source.io.completion.bits.outputQuads===admitted+dropped+queued.asUInt,"tile owner lost a shader export")
    when(source.io.completion.bits.status=/=0.U && !(source.io.completion.bits.status===4.U && pixels.io.cancelSource)){
      fail(source.io.completion.bits.status,2.U,address=source.io.completion.bits.faultAddress)}
  }
  pixels.io.completion.ready:=pixelStarted && !pixelDone
  when(pixels.io.completion.fire){pixelResult:=pixels.io.completion.bits;pixelDone:=true.B
    when(pixels.io.completion.bits.status=/=0.U){fail(pixels.io.completion.bits.status,Mux(phase===State.bind,1.U,3.U),offset=pixels.io.completion.bits.faultOffset)}}
  private val maintenance=phase===State.active && io.maintenance && !stop
  pixels.io.maintenance:=maintenance || phase===State.externalReply
  pixels.io.memory.valid:=maintenance && io.memory.valid;pixels.io.memory.bits:=io.memory.bits
  io.memory.ready:=maintenance && pixels.io.memory.ready
  io.reply.valid:=phase===State.externalReply && pixels.io.reply.valid;io.reply.bits:=pixels.io.reply.bits
  pixels.io.reply.ready:=phase===State.externalReply && io.reply.ready
  io.refill<>pixels.io.refill;pixels.io.refillReply<>io.refillReply
  private val producer=Mux(io.maintenance,io.memory.valid,io.draw.valid)
  pixels.io.end.valid:=phase===State.active && !producer && io.end.valid && !stop;pixels.io.end.bits:=io.end.bits
  io.end.ready:=phase===State.active && !producer && pixels.io.end.ready && !stop
  io.begin.ready:=phase===State.idle && !externalCancel;io.draw.ready:=phase===State.active && !io.maintenance && !stop
  io.drawCompletion.valid:=phase===State.emitDraw;io.drawCompletion.bits:=drawResult
  io.completion.valid:=phase===State.complete;io.completion.bits:=result;io.busy:=phase=/=State.idle
  when(active && externalCancel && status===0.U){status:=4.U;faultStage:=0.U}
  private val drawDrained= !sourceOffered && (!sourceStarted || (sourceDone && !source.io.busy)) && !queued && admitted===retired
  private def formDrawResult(): Unit={
    drawResult:=0.U.asTypeOf(drawResult);drawResult.identity:=descriptor.colors.identity;drawResult.ordinal:=draws-1.U
    drawResult.status:=Mux(externalCancel && status===0.U,4.U,status);drawResult.faultStage:=faultStage;drawResult.source:=sourceResult
    drawResult.admittedQuads:=admitted;drawResult.retiredQuads:=retired;drawResult.droppedQuads:=dropped;phase:=State.emitDraw
  }
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;faultStage:=0.U;faultAddress:=0.U;faultOffset:=0.U
      pixelStarted:=false.B;pixelDone:=false.B;sourceStarted:=false.B;sourceDone:=false.B;drawOwned:=false.B
      draws:=0.U;admitted:=0.U;retired:=0.U;dropped:=0.U;pixelResult:=0.U.asTypeOf(pixelResult)
      for(i<-0 until 2)contexts(i):=io.payload.map(_(i).context).getOrElse(0.U)
      phase:=State.validatePass}}
    is(State.validatePass){when(!preparedGeometry.B && contexts(0)=/=contexts(1)){fail(1.U,1.U);phase:=State.closing}
      .elsewhen(stop){phase:=State.closing}.otherwise{pixelOffered:=true.B;phase:=State.bind}}
    is(State.bind){when(stop || pixelDone){phase:=State.closing}.elsewhen(pixelStarted && pixels.io.in.ready){phase:=State.active}}
    is(State.active){when(stop){phase:=State.closing}.otherwise{
      when(io.draw.fire){draw:=io.draw.bits;drawOwned:=true.B;draws:=draws+1.U;sourceStarted:=false.B;sourceDone:=false.B
        primitive.foreach(_:=io.primitive.get)
        admitted:=0.U;retired:=0.U;dropped:=0.U;sourceResult:=0.U.asTypeOf(sourceResult)
        sourceResult.preparedGeometry:=preparedGeometry.B;phase:=State.validateDraw}
      when(io.memory.fire){phase:=State.externalReply}
      when(io.end.fire){when(!io.end.bits){fail(5.U,1.U)};phase:=State.closing}
    }}
    is(State.validateDraw){
      val d=descriptor.colors;val f=draw.shaders.fragment;val r=draw.shaders.vertex.raster
      val screenW=Mux(r.screenW===0.U || r.screenW>g.screenWidth.U,g.screenWidth.U,r.screenW)
      val screenH=Mux(r.screenH===0.U || r.screenH>g.screenHeight.U,g.screenHeight.U,r.screenH)
      val loX=Mux(r.scissorEnable,r.scissorMinX,0.U);val loY=Mux(r.scissorEnable,r.scissorMinY,0.U)
      val hiX=Mux(r.scissorEnable,r.scissorMaxX.min(screenW),screenW);val hiY=Mux(r.scissorEnable,r.scissorMaxY.min(screenH),screenH)
      val empty=loX>=hiX || loY>=hiY
      val within=empty || (loX.zext>=d.originX.pad(33) && loY.zext>=d.originY.pad(33) &&
        hiX.zext<=d.originX.pad(33)+d.tileWidth.zext && hiY.zext<=d.originY.pad(33)+d.tileHeight.zext)
      val matching=f.identity.asUInt===d.identity.asUInt && f.sampleMode===d.sampleMode && r.sampleMode===d.sampleMode
      val types=d.colors.zipWithIndex.map{case(v,i)=> !v.enabled || i.U>=f.colorSlots || f.colorTypes(i)===TypedColorFormat.kind(v.format)}.reduce(_ && _)
      when(stop){phase:=State.closing}.elsewhen(draw.version=/=1.U || draw.tests.version=/=1.U || TypedBlendState.invalid(draw.blend) || !within || !matching || !types){fail(1.U,1.U);phase:=State.closing}
        .otherwise{sourceOffered:=true.B;phase:=State.drawing}
    }
    is(State.drawing){when(drawDrained){formDrawResult()}}
    is(State.emitDraw){when(io.drawCompletion.fire){drawOwned:=false.B;phase:=Mux(stop,State.closing,State.active)}}
    is(State.externalReply){when(io.reply.fire){phase:=Mux(stop,State.closing,State.active)}}
    is(State.closing){when(drawDrained){when(drawOwned){formDrawResult()}
      .elsewhen(!pixelOffered && (!pixelStarted || (pixelDone && !pixels.io.busy))){phase:=State.finish}}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.colors.identity;result.status:=Mux(externalCancel && status===0.U,4.U,status)
      result.faultStage:=faultStage;result.faultAddress:=faultAddress;result.faultOffset:=faultOffset;result.draws:=draws;result.pixels:=pixelResult;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!source.io.busy && !pixels.io.busy && !queued && !sourceOffered && !pixelOffered,"tile pass completion escaped child drain")}
}
