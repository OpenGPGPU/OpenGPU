package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class VertexBinDraw(p: VertexShaderStageConfig) extends Bundle {
  val version=UInt(8.W);val vertex=new VertexViewportDraw(p);val raster=new Fp32RasterState
  /** Names immutable fragment/test/blend state retained by the pass owner. */
  val state=UInt(32.W);val token=UInt(32.W)
}
class VertexBinDrawCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val ordinal=UInt(64.W);val status=UInt(3.W);val faultStage=UInt(2.W)
  val sourceStarted=Bool();val source=new VertexViewportCompletion
  val receivedParts=UInt(67.W);val admittedParts=UInt(67.W);val droppedParts=UInt(67.W)
  val firstSequence=UInt(33.W);val nextSequence=UInt(33.W)
}
class VertexBinCompletion(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 0 success/cancel, 1 admission, 2 vertex/clip/viewport, 3 bins/order. */
  val faultStage=UInt(2.W);val faultDrawValid=Bool();val faultDraw=UInt(64.W)
  val sourceFaultValid=Bool();val sourceFault=new VertexViewportCompletion
  val draws=UInt(64.W);val receivedParts=UInt(131.W);val admittedParts=UInt(33.W);val droppedParts=UInt(131.W)
  val bins=new WindowPrimitiveBinReplayCompletion(p)
}
object VertexBinPipeline {
  def binTransactions(engines: Int,writeCredits: Int,pagedCapacity: Int): Int=
    writeCredits+2*engines+1+(if(pagedCapacity>0)1 else 0)
  def memoryTransactions(engines: Int,writeCredits: Int,pagedCapacity: Int): Int=
    1<<log2Ceil(binTransactions(engines,writeCredits,pagedCapacity)+4)
}

/** Ordered shared-CU vertex draws through clipping/viewport into general bins.
  * Each window part is accepted once, snapshots draw raster/state/token, and
  * receives a pass-wide sequence independent of its draw-local sequence.
  * A draw result waits for source drain AND its final record/bin append acks.
  * The pass result also waits for all replay records and tile results; it is
  * not downstream raster/FS/pixel retirement or image publication.
  *
  * The enclosing owner retains immutable state/code/texture bindings, VM and
  * all arenas through this held result and later rendering/publication drain.
  * Known vertex input/scratch spans cannot alias the writable bin arenas.
  * General code/texture allocation provenance is still an external contract.
  */
class VertexBinPipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    shared: SharedCuShaderConfig=SharedCuShaderConfig(4),g: GraphicsConfig=GraphicsConfig(),
    viewport: Fp32ViewportConfig=Fp32ViewportConfig(),engines: Int=2,pageBytes: Int=4096,writeCredits: Int=8,
    tileSlots: Int=8,pagedCapacity: Int=0,useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,rendererCredits: Boolean=false,groupedTexture: Boolean=false,transformFeedback: Boolean=false) extends Module {
  require(g.subPixelBits==viewport.subPixelBits,"viewport and bins need the same fixed coordinate scale")
  private val sharedConfig=shared;private val payloadConfig=payload
  private val sourceBase=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val memoryTransactions=VertexBinPipeline.memoryTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureClientIO)else None
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val draw=Flipped(Decoupled(new VertexBinDraw(p)));val drawCompletion=Decoupled(new VertexBinDrawCompletion)
    val end=Flipped(Decoupled(Bool()));val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion);val completion=Decoupled(new VertexBinCompletion(p))
    val rendererPermit=if(rendererCredits)Some(Input(Vec(engines,Bool())))else None
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new VertexViewportPipeline(config,p,shared,viewport,useSramBlackBoxes,payload,payloadOwner,groupedTexture=groupedTexture,transformFeedback=transformFeedback))
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.draw.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,source.io.feedback.get,Mux(io.draw.fire,io.feedback.get.scope,scope), !io.busy)
  }
  if(groupedTexture)io.grouped.get <> source.io.grouped.get
  private val bins=Module(new WindowPrimitiveBinReplayPass(config,p,g,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,rendererCredits))
  if(rendererCredits)bins.io.rendererPermit.get:=io.rendererPermit.get
  val recordBytes=bins.recordBytes
  private object State extends ChiselEnum {val idle,start,ready,validateDraw,drawing,emitDraw,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new WindowPrimitiveBinReplayDescriptor)
  private val draw=Reg(new VertexBinDraw(p));private val drawResult=Reg(new VertexBinDrawCompletion)
  private val sourceResult=Reg(new VertexViewportCompletion);private val binResult=Reg(new WindowPrimitiveBinReplayCompletion(p))
  private val result=Reg(new VertexBinCompletion(p));private val payloadContext=Reg(UInt(32.W))
  private val binOffered=RegInit(false.B);private val binStarted=RegInit(false.B);private val binDone=RegInit(false.B)
  private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val drawOwned=RegInit(false.B);private val draws=RegInit(0.U(64.W))
  private val firstSequence=RegInit(0.U(33.W));private val nextSequence=RegInit(0.U(33.W))
  private val received=RegInit(0.U(67.W));private val admitted=RegInit(0.U(67.W));private val dropped=RegInit(0.U(67.W))
  private val totalReceived=RegInit(0.U(131.W));private val totalDropped=RegInit(0.U(131.W))
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W))
  private val faultDrawValid=RegInit(false.B);private val faultDraw=Reg(UInt(64.W))
  private val sourceFaultValid=RegInit(false.B);private val sourceFault=Reg(new VertexViewportCompletion)
  private val externalCancel=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  private val sourceFailure=source.io.completion.valid && source.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || externalCancel || bins.io.cancelSource || sourceFailure
  private val active=phase=/=State.idle && phase=/=State.complete
  private def fail(code: UInt,stage: Int,drawValid: Bool=drawOwned): Unit={
    when(status===0.U && !externalCancel){status:=code;faultStage:=stage.U;faultDrawValid:=drawValid;faultDraw:=draws-1.U}
  }
  io.begin.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle || io.feedback.map(_.busy).getOrElse(false.B)
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
  source.io.draw.valid:=sourceOffered;source.io.draw.bits:=draw.vertex
  when(source.io.draw.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  source.io.cancel:=sourceStarted && stop
  private val exhausted=nextSequence(32)
  bins.io.in.valid:=sourceStarted && !stop && !exhausted && source.io.out.valid && bins.io.in.ready
  bins.io.in.bits.primitive:=source.io.out.bits;bins.io.in.bits.raster:=draw.raster
  bins.io.in.bits.geometry:=0.U.asTypeOf(bins.io.in.bits.geometry)
  bins.io.in.bits.state:=draw.state;bins.io.in.bits.token:=draw.token;bins.io.in.bits.sequence:=nextSequence(31,0)
  source.io.out.ready:=sourceStarted && (stop || (!exhausted && bins.io.in.ready))
  when(sourceStarted && source.io.out.valid && exhausted && !stop){fail(6.U,3)}
  when(source.io.out.fire){received:=received+1.U;totalReceived:=totalReceived+1.U
    when(bins.io.in.fire){admitted:=admitted+1.U;nextSequence:=nextSequence+1.U}
      .otherwise{dropped:=dropped+1.U;totalDropped:=totalDropped+1.U}}
  source.io.completion.ready:=sourceStarted && !sourceDone
  when(source.io.completion.fire){sourceResult:=source.io.completion.bits;sourceDone:=true.B
    assert(source.io.completion.bits.preparedParts===received,"vertex bin owner lost a window part")
    val code=source.io.completion.bits.status
    when(code=/=0.U && !(code===4.U && bins.io.cancelSource)){
      fail(code,2)
      when(status===0.U && !externalCancel){sourceFaultValid:=true.B;sourceFault:=source.io.completion.bits}
    }}
  io.shared<>source.io.shared
  payload.foreach{_=>io.payload.get<>source.io.payload.get
    source.io.payload.get.context:=payloadContext;source.io.payload.get.cancel:=sourceStarted && io.payload.get.cancel}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  arbiter.io.in(0).valid:=bins.io.memoryRequest.valid;arbiter.io.in(0).bits:=bins.io.memoryRequest.bits
  bins.io.memoryRequest.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=source.io.memoryRequest.valid;arbiter.io.in(1).bits:=source.io.memoryRequest.bits
  arbiter.io.in(1).bits.transactionId:=source.io.memoryRequest.bits.transactionId+&sourceBase.U
  source.io.memoryRequest.ready:=arbiter.io.in(1).ready
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val sourceResponse=io.memoryResponse.bits.transactionId>=sourceBase.U && io.memoryResponse.bits.transactionId<(sourceBase+4).U
  private val binResponse=io.memoryResponse.bits.transactionId<sourceBase.U
  source.io.memoryResponse.valid:=io.memoryResponse.valid && sourceResponse;source.io.memoryResponse.bits:=io.memoryResponse.bits
  source.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId-sourceBase.U
  bins.io.memoryResponse.valid:=io.memoryResponse.valid && binResponse;bins.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(sourceResponse,source.io.memoryResponse.ready,binResponse && bins.io.memoryResponse.ready)
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
      val v=draw.vertex.geometry.vertex;val input=v.input;val d=descriptor.producer.arena;val b=descriptor.bins
      def aliases(base: UInt,limit: UInt): Bool=(base<d.limit && limit>d.base) ||
        (base<b.pool.limit && limit>b.pool.base) || (b.version=/=1.U && base<b.directoryLimit && limit>b.directoryBase)
      val scratchAlias=aliases(v.scratch.base,v.scratch.limit)
      val inputAlias=(input.indexFormat=/=0.U && aliases(input.indices.base,input.indices.limit)) ||
        input.bindings.zipWithIndex.map{case(binding,i)=>
          val used=input.attributes.map(a=>a.enabled && a.bindingIndex===i.U).reduce(_ || _)
          used && aliases(binding.base,binding.limit)}.reduce(_ || _)
      val indirect=draw.vertex.geometry.indirect
      val parameterEnd=indirect.view.base+&Mux(indirect.mode===1.U,16.U,20.U)
      val parameterAlias=indirect.mode=/=0.U && aliases(indirect.view.base,parameterEnd)
      val work=indirect.mode=/=0.U || (input.count=/=0.U && input.instances=/=0.U)
      val legal=IndirectDrawControl.legal(indirect,input.indexFormat) && draw.version===1.U && input.identity.asUInt===d.identity.asUInt && Fp32RasterState.legal(draw.raster,g) &&
        Fp32ViewportState.legal(draw.vertex.viewport) && draw.vertex.geometry.topology<=6.U && !(work && (scratchAlias || inputAlias || parameterAlias))
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
    "vertex bin completion escaped source/bin/offer drain")}
}
