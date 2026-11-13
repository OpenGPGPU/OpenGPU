package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class NativeGeometryVertexBinDraw(p: VertexShaderStageConfig,idWidth: Int) extends Bundle {
  val version=UInt(8.W)
  val vertex=new NativeGeometryVertexViewportTask(p,idWidth)
  val raster=new Fp32RasterState;val state=UInt(32.W);val token=UInt(32.W)
}
class NativeGeometryVertexBinDrawCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val ordinal=UInt(64.W)
  val status=UInt(3.W);val faultStage=UInt(2.W);val sourceStarted=Bool()
  val source=new NativeGeometryVertexViewportCompletion
  val receivedParts=UInt(NativeGeometryVertexViewportPipeline.partCountWidth.W)
  val admittedParts=UInt(NativeGeometryVertexViewportPipeline.partCountWidth.W)
  val droppedParts=UInt(NativeGeometryVertexViewportPipeline.partCountWidth.W)
  val firstSequence=UInt(33.W);val nextSequence=UInt(33.W)
}
class NativeGeometryVertexBinCompletion(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W);val faultStage=UInt(2.W)
  val faultDrawValid=Bool();val faultDraw=UInt(64.W)
  val sourceFaultValid=Bool();val sourceFault=new NativeGeometryVertexViewportCompletion
  val draws=UInt(64.W)
  val receivedParts=UInt((NativeGeometryVertexViewportPipeline.partCountWidth+64).W)
  val admittedParts=UInt(33.W)
  val droppedParts=UInt((NativeGeometryVertexViewportPipeline.partCountWidth+64).W)
  val bins=new WindowPrimitiveBinReplayCompletion(p)
}
object NativeGeometryVertexBinDescriptor {
  def legal(draw: NativeGeometryVertexBinDraw,descriptor: WindowPrimitiveBinReplayDescriptor,
      g: GraphicsConfig,p: VertexShaderStageConfig): Bool={
    val ordinary=Wire(new VertexBinDraw(p))
    ordinary.version:=draw.version;ordinary.vertex.geometry:=draw.vertex.source.draw
    ordinary.vertex.viewport:=draw.vertex.viewport;ordinary.raster:=draw.raster
    ordinary.state:=draw.state;ordinary.token:=draw.token
    val gs=draw.vertex.source.program;val d=descriptor.producer.arena;val b=descriptor.bins
    def aliases(base: UInt,limit: UInt): Bool=(base<d.limit && limit>d.base) ||
      (base<b.pool.limit && limit>b.pool.base) ||
      (b.version=/=1.U && base<b.directoryLimit && limit>b.directoryBase)
    VertexBinDescriptor.legal(ordinary,descriptor,g,Some(true.B)) &&
      NativeGeometryProgramLayout.legal(gs,p) &&
      NativeGeometryProgramLayout.topologyMatches(gs,ordinary.vertex.geometry.topology) &&
      gs.identity.asUInt===ordinary.vertex.geometry.vertex.input.identity.asUInt &&
      gs.vertexVaryingSlots===ordinary.vertex.geometry.vertex.varyingSlots &&
      !aliases(gs.code.base,gs.code.limit) &&
      !(gs.uniforms.version=/=0.U && aliases(gs.uniforms.address,gs.uniforms.address.pad(33)+64.U))
  }
}
object NativeGeometryVertexBinPipeline {
  def memoryTransactions(engines: Int,writeCredits: Int,pagedCapacity: Int): Int=
    1<<log2Ceil(VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)+8)
}

/** Explicit GS draw source into the unchanged general bin/replay owner.
  * Source-tag space and expansion counters are independent of the ordinary
  * four-tag/67-bit interface. Physical arenas, renderer permits and bin limits
  * retain their original contracts. The caller preflights the complete graph
  * before begin and retains code/metadata/indirect resource contents through
  * rendering, publication and ownership drain. This is not product admission.
  */
class NativeGeometryVertexBinPipeline(config: GpuConfig,p: VertexShaderStageConfig,
    shared: SharedCuShaderConfig,g: GraphicsConfig=GraphicsConfig(),
    viewport: Fp32ViewportConfig=Fp32ViewportConfig(),engines: Int=2,pageBytes: Int=4096,writeCredits: Int=8,
    tileSlots: Int=8,pagedCapacity: Int=0,useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,rendererCredits: Boolean=false,groupedTexture: Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits,"viewport and bins need the same fixed coordinate scale")
  private val sharedConfig=shared;private val payloadConfig=payload
  private val sourceBase=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val memoryTransactions=NativeGeometryVertexBinPipeline.memoryTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val draw=Flipped(Decoupled(new NativeGeometryVertexBinDraw(p,sharedConfig.taskIdWidth)));val drawCompletion=Decoupled(new NativeGeometryVertexBinDrawCompletion)
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new NativeGeometryVertexBinCompletion(p))
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,geometryAvailable=true))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new NativeGeometryVertexViewportPipeline(config,p,shared,viewport,useSramBlackBoxes,payload,payloadOwner,groupedTexture=groupedTexture))
  if(groupedTexture)io.grouped.get <> source.io.grouped.get
  private val bins=Module(new WindowPrimitiveBinReplayPass(config,p,g,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits))
  if(rendererCredits)bins.io.rendererPermit.get:=io.rendererPermit.get
  val recordBytes=bins.recordBytes
  private object State extends ChiselEnum {val idle,start,ready,validateDraw,drawing,emitDraw,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new WindowPrimitiveBinReplayDescriptor)
  private val draw=Reg(new NativeGeometryVertexBinDraw(p,sharedConfig.taskIdWidth));private val drawResult=Reg(new NativeGeometryVertexBinDrawCompletion)
  private val sourceResult=Reg(new NativeGeometryVertexViewportCompletion);private val binResult=Reg(new WindowPrimitiveBinReplayCompletion(p))
  private val result=Reg(new NativeGeometryVertexBinCompletion(p));private val payloadContext=Reg(UInt(32.W))
  private val binOffered=RegInit(false.B);private val binStarted=RegInit(false.B);private val binDone=RegInit(false.B)
  private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val drawOwned=RegInit(false.B);private val draws=RegInit(0.U(64.W))
  private val firstSequence=RegInit(0.U(33.W));private val nextSequence=RegInit(0.U(33.W))
  private val received=RegInit(0.U(NativeGeometryVertexViewportPipeline.partCountWidth.W));private val admitted=RegInit(0.U(NativeGeometryVertexViewportPipeline.partCountWidth.W));private val dropped=RegInit(0.U(NativeGeometryVertexViewportPipeline.partCountWidth.W))
  private val totalReceived=RegInit(0.U((NativeGeometryVertexViewportPipeline.partCountWidth+64).W));private val totalDropped=RegInit(0.U((NativeGeometryVertexViewportPipeline.partCountWidth+64).W))
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W))
  private val faultDrawValid=RegInit(false.B);private val faultDraw=Reg(UInt(64.W))
  private val sourceFaultValid=RegInit(false.B);private val sourceFault=Reg(new NativeGeometryVertexViewportCompletion)
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val sourceFailure=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || externalCancel || bins.io.cancelSource || sourceFailure
  private val active=phase=/=State.idle && phase=/=State.complete
  private def fail(code: UInt,stage: Int,drawValid: Bool=drawOwned): Unit={
    when(status===0.U && !externalCancel){status:=code;faultStage:=stage.U;faultDrawValid:=drawValid;faultDraw:=draws-1.U}
  }
  io.begin.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  io.draw.ready:=phase===State.ready && !stop
  io.drawCompletion.valid:=phase===State.emitDraw;io.drawCompletion.bits:=drawResult
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  bins.io.begin.valid:=binOffered;bins.io.begin.bits:=descriptor
  when(bins.io.begin.fire){binOffered:=false.B;binStarted:=true.B}
  bins.io.cancel:=binStarted && (status=/=0.U || externalCancel)
  bins.io.completion.ready:=binStarted && !binDone
  when(bins.io.completion.fire){binResult:=bins.io.completion.bits;binDone:=true.B
    when(bins.io.completion.bits.status=/=0.U){fail(bins.io.completion.bits.status,3)}}
  io.out<>bins.io.out;io.tileDone<>bins.io.tileDone
  // Only present end on a ready cycle; no private end offer can be stranded
  // if the bin owner closes spontaneously between draws or during cancel.
  bins.io.end.valid:=phase===State.ready && !io.draw.valid && io.end.valid && bins.io.end.ready && !stop
  bins.io.end.bits:=io.end.bits
  io.end.ready:=phase===State.ready && !io.draw.valid && bins.io.end.ready && !stop
  source.io.task.valid:=sourceOffered;source.io.task.bits:=draw.vertex
  when(source.io.task.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  source.io.cancel:=sourceStarted && stop
  private val exhausted=nextSequence(32)
  bins.io.in.valid:=sourceStarted && !stop && !exhausted && source.io.out.valid && bins.io.in.ready
  bins.io.in.bits.primitive:=source.io.out.bits.window;bins.io.in.bits.raster:=draw.raster
  bins.io.in.bits.geometry.valid:=true.B
  bins.io.in.bits.geometry.bits.inputPrimitiveId:=source.io.out.bits.inputPrimitiveId
  bins.io.in.bits.geometry.bits.invocationId:=source.io.out.bits.invocationId
  bins.io.in.bits.geometry.bits.layer:=source.io.out.bits.layer
  bins.io.in.bits.state:=draw.state;bins.io.in.bits.token:=draw.token;bins.io.in.bits.sequence:=nextSequence(31,0)
  source.io.out.ready:=sourceStarted && (stop || (!exhausted && bins.io.in.ready))
  when(sourceStarted && source.io.out.valid && exhausted && !stop){fail(6.U,3)}
  when(source.io.out.fire){received:=received+1.U;totalReceived:=totalReceived+1.U
    when(bins.io.in.fire){admitted:=admitted+1.U;nextSequence:=nextSequence+1.U}
      .otherwise{dropped:=dropped+1.U;totalDropped:=totalDropped+1.U}}
  source.io.completion.ready:=sourceStarted && !sourceDone
  when(source.io.completion.fire){sourceResult:=source.io.completion.bits;sourceDone:=true.B
    assert(source.io.completion.bits.preparedParts===received,"native GS bin owner lost a window part")
    val code=source.io.completion.bits.status
    when(code=/=0.U && !(code===4.U && bins.io.cancelSource)){
      fail(code,2)
      when(status===0.U && !externalCancel){sourceFaultValid:=true.B;sourceFault:=source.io.completion.bits}
    }}
  io.shared<>source.io.shared
  payload.foreach{_=>io.payload.get<>source.io.payload.get
    source.io.payload.get.context:=payloadContext;source.io.payload.get.cancel:=sourceStarted && io.payload.get.cancel
    io.payloadPins.get:=source.io.payloadPins.get}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  arbiter.io.in(0).valid:=bins.io.memoryRequest.valid;arbiter.io.in(0).bits:=bins.io.memoryRequest.bits
  bins.io.memoryRequest.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=source.io.memoryRequest.valid;arbiter.io.in(1).bits:=source.io.memoryRequest.bits
  arbiter.io.in(1).bits.transactionId:=source.io.memoryRequest.bits.transactionId+&sourceBase.U
  source.io.memoryRequest.ready:=arbiter.io.in(1).ready
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val sourceResponse=io.memoryResponse.bits.transactionId>=sourceBase.U && io.memoryResponse.bits.transactionId<(sourceBase+8).U
  private val binResponse=io.memoryResponse.bits.transactionId<sourceBase.U
  source.io.memoryResponse.valid:=io.memoryResponse.valid && sourceResponse;source.io.memoryResponse.bits:=io.memoryResponse.bits
  source.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId-sourceBase.U
  bins.io.memoryResponse.valid:=io.memoryResponse.valid && binResponse;bins.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(sourceResponse,source.io.memoryResponse.ready,binResponse && bins.io.memoryResponse.ready)
  when(io.memoryResponse.valid){assert(sourceResponse || binResponse,"native GS bin response lost its owner")}
  private val sourceDrained= !sourceOffered && (!sourceStarted || (sourceDone && !source.io.busy))
  private val stopKnown=status=/=0.U || externalCancel || binDone
  private def finishDraw(): Unit={
    drawResult:=0.U.asTypeOf(drawResult);drawResult.identity:=descriptor.producer.arena.identity;drawResult.ordinal:=draws-1.U
    drawResult.status:=Mux(externalCancel && status===0.U,4.U,status);drawResult.faultStage:=faultStage
    drawResult.sourceStarted:=sourceStarted;drawResult.source:=sourceResult
    drawResult.receivedParts:=received;drawResult.admittedParts:=admitted;drawResult.droppedParts:=dropped
    drawResult.firstSequence:=firstSequence;drawResult.nextSequence:=nextSequence;phase:=State.emitDraw
  }
  when(active && externalCancel && status===0.U){status:=4.U;faultStage:=0.U;faultDrawValid:=false.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;binOffered:=true.B;binStarted:=false.B;binDone:=false.B
      sourceOffered:=false.B;sourceStarted:=false.B;sourceDone:=false.B;drawOwned:=false.B
      draws:=0.U;nextSequence:=0.U;totalReceived:=0.U;totalDropped:=0.U;status:=0.U;faultStage:=0.U;faultDrawValid:=false.B
      sourceFaultValid:=false.B;sourceFault:=0.U.asTypeOf(sourceFault);sourceResult:=0.U.asTypeOf(sourceResult);binResult:=0.U.asTypeOf(binResult)
      payloadContext:=io.payload.map(_.context).getOrElse(0.U);phase:=State.start}}
    is(State.start){when(stop || binDone){phase:=State.closing}.elsewhen(binStarted && bins.io.in.ready){phase:=State.ready}}
    is(State.ready){when(stop || binDone){phase:=State.closing}.elsewhen(io.draw.fire){
      draw:=io.draw.bits;draws:=draws+1.U;drawOwned:=true.B;sourceStarted:=false.B;sourceDone:=false.B
      sourceResult:=0.U.asTypeOf(sourceResult);received:=0.U;admitted:=0.U;dropped:=0.U;firstSequence:=nextSequence;phase:=State.validateDraw
    }.elsewhen(io.end.fire){when(!io.end.bits){fail(1.U,1,false.B)};phase:=State.closing}}
    is(State.validateDraw){
      val legal=NativeGeometryVertexBinDescriptor.legal(draw,descriptor,g,p)
      when(stop){phase:=State.closing}.elsewhen(!legal){fail(1.U,1);phase:=State.closing}
        .otherwise{sourceOffered:=true.B;phase:=State.drawing}
    }
    is(State.drawing){when(sourceDrained && ((stop && stopKnown) || (!stop && bins.io.in.ready))){finishDraw()}}
    is(State.emitDraw){when(io.drawCompletion.fire){drawOwned:=false.B;phase:=Mux(stop || binDone,State.closing,State.ready)}}
    is(State.closing){when(sourceDrained && !binOffered && (!stop || stopKnown)){when(drawOwned){finishDraw()}
      .elsewhen(binDone && !bins.io.busy && queue.io.count===0.U){phase:=State.finish}}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.producer.arena.identity
      result.status:=Mux(externalCancel && status===0.U,4.U,status);result.faultStage:=faultStage;result.faultDrawValid:=faultDrawValid;result.faultDraw:=faultDraw
      result.sourceFaultValid:=sourceFaultValid;result.sourceFault:=sourceFault;result.draws:=draws
      result.receivedParts:=totalReceived;result.admittedParts:=nextSequence;result.droppedParts:=totalDropped;result.bins:=binResult;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!source.io.busy && !bins.io.busy && !binOffered && !sourceOffered && queue.io.count===0.U,
    "native GS bin completion escaped source/bin/offer drain")}
}
