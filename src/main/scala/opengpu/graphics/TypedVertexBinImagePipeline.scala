package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class TypedVertexBinImageDescriptor extends Bundle {
  val version=UInt(8.W)
  val bins=new WindowPrimitiveBinReplayDescriptor
  /** Whole-frame views; origin is zero and per-tile extents are generated. */
  val image=new TypedTileImageDescriptor
  val states=new PrimitiveStateDescriptor
  val scratchStride=UInt(32.W)
  val scratch=Vec(4,new TypedReplayMemorySpan)
}
class TypedVertexBinImageTileCompletion extends Bundle {
  val engine=UInt(8.W);val status=UInt(3.W)
  val source=new WindowPrimitiveTileCompletion
  val renderStarted=Bool();val image=new TypedReplayTileCompletion
}
class TypedVertexBinImageCompletion(p: VertexShaderStageConfig) extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 0 success/cancel, 1 admission, 2 geometry/bins, 3 image execution/publication. */
  val faultStage=UInt(2.W);val faultTileValid=Bool();val faultTile=UInt(32.W)
  val source=new VertexBinCompletion(p)
  val tiles=UInt(64.W);val primitives=UInt(64.W)
  val loadBytes=UInt(64.W);val storedBytes=UInt(64.W);val resolvedBytes=UInt(64.W)
}
object TypedVertexBinImagePipeline {
  def memoryTransactions(engines: Int,writeCredits: Int,pagedCapacity: Int): Int=
    1<<log2Ceil(VertexBinPipeline.memoryTransactions(engines,writeCredits,pagedCapacity)+32*engines)
  def payloadPins(engines: Int): Int=2*(engines+1)+4*engines
}

/** Shared-CU geometry is prepared and binned once, then independently owned
  * typed image engines consume complete records. Version-3 directories report
  * every tile, including empty tiles. Per-engine image results are queued before
  * returning the dispatch credit; a held result cannot block another engine.
  *
  * This internal physical-address component is not a native descriptor/MMU
  * interface. The enclosing owner pins all allocations/code/uniforms and the
  * VM context through the held completion, and discards all outputs on failure.
  */
class TypedVertexBinImagePipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    f: FragmentShaderConfig=FragmentShaderConfig(),shared: SharedCuShaderConfig=SharedCuShaderConfig(4),
    g: GraphicsConfig=GraphicsConfig(),viewport: Fp32ViewportConfig=Fp32ViewportConfig(),engines: Int=2,
    pageBytes: Int=4096,writeCredits: Int=8,tileSlots: Int=8,pagedCapacity: Int=0,
    storage: TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,groupedTexture: Boolean=false) extends Module {
  require(engines>=1 && engines<=4 && p.varyings==f.varyings && f.colors==4)
  payload.foreach(q=>require(payloadOwner>=0 && payloadOwner+engines<q.owners))
  private val sharedConfig=shared;private val payloadConfig=payload
  private val binSlots=VertexBinPipeline.memoryTransactions(engines,writeCredits,pagedCapacity)
  val memoryTransactions=TypedVertexBinImagePipeline.memoryTransactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,engines+1))else None
    val begin=Flipped(Decoupled(new TypedVertexBinImageDescriptor))
    val draw=Flipped(Decoupled(new VertexBinDraw(p)));val drawCompletion=Decoupled(new VertexBinDrawCompletion)
    val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(engines,Decoupled(new TypedVertexBinImageTileCompletion))
    val completion=Decoupled(new TypedVertexBinImageCompletion(p))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>Vec(engines+1,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(TypedVertexBinImagePipeline.payloadPins(engines),Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new VertexBinPipeline(config,p,shared,g,viewport,engines,pageBytes,writeCredits,
    tileSlots,pagedCapacity,useSramBlackBoxes,payload,payloadOwner,rendererCredits=true,groupedTexture=groupedTexture))
  val recordBytes=source.recordBytes
  private val images=Seq.tabulate(engines)(e=>Module(new TypedReplayTileImagePipeline(config,p,f,shared,g,storage,
    engine=e,useSramBlackBoxes=useSramBlackBoxes,payload=payload,payloadOwner=payloadOwner+e,groupedTexture=groupedTexture)))
  if(groupedTexture){
    val ports=io.grouped.get
    ports.client(0) <> source.io.grouped.get;ports.cuMask(0):=source.io.shared.owned
    for((image,e)<-images.zipWithIndex){
      ports.client(e+1) <> image.io.grouped.get.client(0)
      ports.cuMask(e+1):=image.io.grouped.get.cuMask(0)
    }
  }
  private val router=Module(new SharedShaderTaskRouter(config,shared.numComputeUnits,engines+1,shared.taskIdWidth,shaderPayload=payload))
  private val tileQueues=Seq.fill(engines)(Module(new Queue(new TypedVertexBinImageTileCompletion,1)))
  private object State extends ChiselEnum {val idle,validate,scan,sourceOffer,run,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedVertexBinImageDescriptor)
  private val columns=Reg(UInt(17.W));private val scanX=Reg(UInt(17.W));private val scanY=Reg(UInt(17.W))
  private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val sourceResult=Reg(new VertexBinCompletion(p));private val result=Reg(new TypedVertexBinImageCompletion(p))
  private val draws=RegInit(0.U(64.W));private val localDrawValid=RegInit(false.B);private val localDraw=Reg(new VertexBinDrawCompletion)
  private val owned=RegInit(VecInit(Seq.fill(engines)(false.B)));private val tile=Reg(Vec(engines,UInt(32.W)))
  private val imageBinding=Reg(Vec(engines,new TypedReplayTileDescriptor))
  private val imageOffered=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val imageStarted=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val imageDone=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val endSent=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val haveBin=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val binResult=Reg(Vec(engines,new WindowPrimitiveTileCompletion))
  private val imageResult=Reg(Vec(engines,new TypedReplayTileCompletion))
  private val tileCounts=RegInit(VecInit(Seq.fill(engines)(0.U(64.W))))
  private val primitiveCounts=RegInit(VecInit(Seq.fill(engines)(0.U(64.W))))
  private val loads=RegInit(VecInit(Seq.fill(engines)(0.U(64.W))))
  private val stores=RegInit(VecInit(Seq.fill(engines)(0.U(64.W))))
  private val resolves=RegInit(VecInit(Seq.fill(engines)(0.U(64.W))))
  private val contexts=Reg(Vec(engines+1,UInt(32.W)))
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W))
  private val faultTileValid=RegInit(false.B);private val faultTile=Reg(UInt(32.W))
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  private val sourceFailure=(source.io.completion.valid && source.io.completion.bits.status=/=0.U) ||
    (source.io.drawCompletion.valid && source.io.drawCompletion.bits.status=/=0.U)
  private val imageFailure=images.map(i=>i.io.completion.valid && i.io.completion.bits.status=/=0.U).reduce(_ || _)
  private val stop=status=/=0.U || externalCancel || sourceFailure || imageFailure
  private val running=phase===State.run || phase===State.closing
  private val active=phase=/=State.idle && phase=/=State.complete
  private val tileSize=1.U(17.W)<<descriptor.bins.producer.tileLog2
  private def fail(code: UInt,stage: Int,tileValid: Bool=false.B,which: UInt=0.U): Unit=
    when(status===0.U && !externalCancel){status:=code;faultStage:=stage.U;faultTileValid:=tileValid;faultTile:=which}
  private def overlap(a: UInt,b: UInt,c: UInt,d: UInt): Bool=a<b && c<d && a<d && c<b
  private def legalSpan(a: UInt,b: UInt): Bool=a(5,0)===0.U && b(5,0)===0.U && b>=a && b<=(BigInt(1)<<32).U
  private def imageFor(x: UInt,y: UInt): TypedTileImageDescriptor={
    val d=WireDefault(descriptor.image);val c=d.pixels.colors
    c.originX:=x.zext;c.originY:=y.zext
    c.tileWidth:=(descriptor.bins.producer.screenWidth-x).min(tileSize)
    c.tileHeight:=(descriptor.bins.producer.screenHeight-y).min(tileSize)
    d
  }
  private def bindingFor(id: UInt): TypedReplayTileDescriptor={
    val d=Wire(new TypedReplayTileDescriptor);d:=0.U.asTypeOf(d)
    val x=(id(15,0)%columns)<<descriptor.bins.producer.tileLog2
    val y=(id(15,0)/columns)<<descriptor.bins.producer.tileLog2
    d.image:=imageFor(x,y);d.tile:=id;d.states:=descriptor.states;d.engines:=engines.U
    d.scratchStride:=descriptor.scratchStride;d.scratch:=descriptor.scratch
    d.protectedMemory(0).base:=descriptor.bins.producer.arena.base;d.protectedMemory(0).limit:=descriptor.bins.producer.arena.limit
    d.protectedMemory(1).base:=descriptor.bins.bins.pool.base;d.protectedMemory(1).limit:=descriptor.bins.bins.pool.limit
    d.protectedMemory(2).base:=(if(pagedCapacity>0)descriptor.bins.bins.directoryBase else 0.U)
    d.protectedMemory(2).limit:=(if(pagedCapacity>0)descriptor.bins.bins.directoryLimit else 0.U)
    d
  }
  io.begin.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  source.io.begin.valid:=sourceOffered;source.io.begin.bits:=descriptor.bins
  when(source.io.begin.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  source.io.cancel:=sourceStarted && stop
  source.io.completion.ready:=sourceStarted && !sourceDone
  when(source.io.completion.fire){sourceDone:=true.B;sourceResult:=source.io.completion.bits
    when(source.io.completion.bits.status=/=0.U){fail(source.io.completion.bits.status,2)}}

  // Admission never exposes a private draw offer to a child that can close.
  private val v=io.draw.bits.vertex.geometry.vertex
  private val protectedScratch=Seq((descriptor.states.base,descriptor.states.limit)) ++
    descriptor.scratch.take(engines).map(s=>(s.base,s.limit)) ++
    descriptor.image.views.zipWithIndex.map{case(view,i)=>
      val needed=TypedTileImageRules.enabled(descriptor.image.pixels)(i) &&
        (view.store || TypedTileImageRules.loads(descriptor.image.pixels)(i)===AttachmentLoadOp.load.U)
      (Mux(needed,view.allocationBase,0.U),Mux(needed,view.allocationLimit,0.U))} ++
    descriptor.image.resolves.map(r=>(Mux(r.enable,r.destination.allocationBase,0.U),Mux(r.enable,r.destination.allocationLimit,0.U)))
  private val work=v.input.count=/=0.U && v.input.instances=/=0.U
  private val drawLegal=io.draw.bits.version===1.U &&
    descriptor.states.base.pad(64)+(io.draw.bits.state.pad(64)<<9)+512.U<=descriptor.states.limit &&
    io.draw.bits.raster.sampleMode===descriptor.image.pixels.colors.sampleMode &&
    (!work || !protectedScratch.map{case(a,b)=>overlap(v.scratch.base,v.scratch.limit,a,b)}.reduce(_ || _))
  io.draw.ready:=phase===State.run && !stop && !localDrawValid && source.io.draw.ready
  source.io.draw.valid:=io.draw.valid && io.draw.ready && drawLegal;source.io.draw.bits:=io.draw.bits
  when(io.draw.fire){draws:=draws+1.U;when(!drawLegal){
    localDraw:=0.U.asTypeOf(localDraw);localDraw.identity:=descriptor.states.identity;localDraw.ordinal:=draws
    localDraw.status:=1.U;localDraw.faultStage:=1.U;localDrawValid:=true.B;fail(1.U,1)}}
  io.drawCompletion.valid:=localDrawValid || source.io.drawCompletion.valid
  io.drawCompletion.bits:=Mux(localDrawValid,localDraw,source.io.drawCompletion.bits)
  source.io.drawCompletion.ready:=io.drawCompletion.ready && !localDrawValid
  when(io.drawCompletion.fire && localDrawValid){localDrawValid:=false.B}
  when(source.io.drawCompletion.valid && source.io.drawCompletion.bits.status=/=0.U){fail(source.io.drawCompletion.bits.status,2)}
  io.end.ready:=phase===State.run && !stop && !io.draw.valid && !localDrawValid && source.io.end.ready
  source.io.end.valid:=io.end.valid && io.end.ready;source.io.end.bits:=io.end.bits

  router.io.clients(0)<>source.io.shared;io.shared<>router.io.pool
  private def tieInputs(d: Data): Unit=d match {
    case r: Record=>r.elements.values.foreach(tieInputs)
    case v: Vec[_]=>v.foreach(tieInputs)
    case b: Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  payload.foreach{_=>
    io.payload.get(0)<>source.io.payload.get;source.io.payload.get.context:=contexts(0)
    source.io.payload.get.cancel:=sourceStarted && io.payload.get(0).cancel
    router.io.clientPayload.get(0):=source.io.payload.get.task
    io.poolPayload.get:=router.io.poolPayload.get
    for(i<-0 until 2*(engines+1))io.payloadPins.get(i):=router.io.payloadPins.get(i)
  }
  private val doneEngine=source.io.tileDone.bits.bins.engine
  private val canTakeBin=Wire(Vec(engines,Bool()))
  source.io.tileDone.ready:=VecInit((0 until engines).map(e=>doneEngine===e.U && canTakeBin(e))).asUInt.orR
  for((image,e)<-images.zipWithIndex){
    val queue=tileQueues(e);val incoming=source.io.tileDone.valid && doneEngine===e.U
    val first=source.io.out(e).valid || incoming
    val binCode=Mux(source.io.tileDone.bits.recordStatus=/=0.U,source.io.tileDone.bits.recordStatus,source.io.tileDone.bits.bins.status)
    val candidate=Mux(source.io.out(e).valid,source.io.out(e).bits.source.tile,source.io.tileDone.bits.bins.tile)
    source.io.rendererPermit.get(e):=running && !owned(e) && queue.io.enq.ready
    canTakeBin(e):=running && !haveBin(e) && (owned(e) || queue.io.enq.ready)
    when(running && !owned(e) && first && queue.io.enq.ready){
      owned(e):=true.B;tile(e):=candidate;imageStarted(e):=false.B;imageDone(e):=false.B;endSent(e):=false.B
      imageResult(e):=0.U.asTypeOf(imageResult(e))
      when(!stop && (!incoming || binCode===0.U)){when(candidate<descriptor.bins.bins.tileCount){imageBinding(e):=bindingFor(candidate);imageOffered(e):=true.B}
        .otherwise{fail(7.U,2,true.B,candidate)}}
    }
    when(source.io.tileDone.fire && doneEngine===e.U){
      when(owned(e)){assert(tile(e)===source.io.tileDone.bits.bins.tile,"image owner received another tile's bin result")}
      binResult(e):=source.io.tileDone.bits;haveBin(e):=true.B
      // A sibling's cancelled report is secondary to the source's retained
      // draw/pass failure. It must not replace that primary shader/bin fault.
      when(binCode=/=0.U && binCode=/=4.U){fail(binCode,2,true.B,source.io.tileDone.bits.bins.tile)}
    }
    image.io.begin.valid:=imageOffered(e);image.io.begin.bits:=imageBinding(e)
    when(image.io.begin.fire){imageOffered(e):=false.B;imageStarted(e):=true.B}
    image.io.in.valid:=running && owned(e) && imageStarted(e) && !imageDone(e) && source.io.out(e).valid && !stop
    image.io.in.bits:=source.io.out(e).bits
    source.io.out(e).ready:=running && (stop || (owned(e) && imageStarted(e) && !imageDone(e) && image.io.in.ready))
    when(image.io.in.fire){assert(image.io.in.bits.source.tile===tile(e),"image consumed another tile's record")}
    image.io.end.valid:=owned(e) && haveBin(e) && imageStarted(e) && !endSent(e) && !stop && image.io.end.ready
    image.io.end.bits:=true.B;when(image.io.end.fire){endSent(e):=true.B}
    image.io.cancel:=imageStarted(e) && stop
    io.primitiveDone(e)<>image.io.primitiveDone;io.out(e)<>image.io.out
    image.io.completion.ready:=imageStarted(e) && !imageDone(e)
    when(image.io.completion.fire){imageDone(e):=true.B;imageResult(e):=image.io.completion.bits
      when(image.io.completion.bits.status=/=0.U){fail(image.io.completion.bits.status,3,true.B,tile(e))}}
    router.io.clients(e+1)<>image.io.shared
    payload.foreach{_=>
      tieInputs(image.io.payload.get(0));io.payload.get(e+1)<>image.io.payload.get(1)
      image.io.payload.get(1).context:=contexts(e+1);image.io.payload.get(1).cancel:=imageStarted(e) && io.payload.get(e+1).cancel
      router.io.clientPayload.get(e+1):=image.io.poolPayload.get
      for(i<-0 until 4)io.payloadPins.get(2*(engines+1)+e*4+i):=image.io.payloadPins.get(i)
    }
    queue.io.enq.valid:=owned(e) && haveBin(e) && !imageOffered(e) && (!imageStarted(e) || (imageDone(e) && !image.io.busy))
    queue.io.enq.bits:=0.U.asTypeOf(queue.io.enq.bits);queue.io.enq.bits.engine:=e.U
    queue.io.enq.bits.source:=binResult(e);queue.io.enq.bits.renderStarted:=imageStarted(e);queue.io.enq.bits.image:=imageResult(e)
    queue.io.enq.bits.status:=Mux(binResult(e).recordStatus=/=0.U,binResult(e).recordStatus,
      Mux(binResult(e).bins.status=/=0.U,binResult(e).bins.status,Mux(imageResult(e).status=/=0.U,imageResult(e).status,Mux(stop,4.U,0.U))))
    io.tileDone(e)<>queue.io.deq
    when(queue.io.enq.fire){owned(e):=false.B;haveBin(e):=false.B;imageStarted(e):=false.B;imageDone(e):=false.B
      tileCounts(e):=tileCounts(e)+1.U;primitiveCounts(e):=primitiveCounts(e)+imageResult(e).accepted
      loads(e):=loads(e)+imageResult(e).image.transfers.loadBytes
      stores(e):=stores(e)+imageResult(e).image.transfers.storedBytes
      resolves(e):=resolves(e)+imageResult(e).image.transfers.resolvedBytes}
  }

  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),engines+1))
  private val requests=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  arbiter.io.in(0).valid:=source.io.memoryRequest.valid;arbiter.io.in(0).bits:=source.io.memoryRequest.bits
  source.io.memoryRequest.ready:=arbiter.io.in(0).ready
  source.io.memoryResponse.valid:=io.memoryResponse.valid && io.memoryResponse.bits.transactionId<binSlots.U
  source.io.memoryResponse.bits:=io.memoryResponse.bits
  private val imageReplies=Wire(Vec(engines,Bool()))
  for((image,e)<-images.zipWithIndex){val base=binSlots+32*e;val port=arbiter.io.in(e+1)
    port.valid:=image.io.memoryRequest.valid;port.bits:=image.io.memoryRequest.bits
    port.bits.transactionId:=image.io.memoryRequest.bits.transactionId+&base.U;image.io.memoryRequest.ready:=port.ready
    val selected=io.memoryResponse.bits.transactionId>=base.U && io.memoryResponse.bits.transactionId<(base+32).U
    image.io.memoryResponse.valid:=io.memoryResponse.valid && selected;image.io.memoryResponse.bits:=io.memoryResponse.bits
    image.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId-base.U
    imageReplies(e):=selected && image.io.memoryResponse.ready
  }
  requests.io.enq<>arbiter.io.out;io.memoryRequest<>requests.io.deq
  io.memoryResponse.ready:=Mux(io.memoryResponse.bits.transactionId<binSlots.U,source.io.memoryResponse.ready,imageReplies.asUInt.orR)
  private val inFlight=RegInit(0.U(log2Ceil(memoryTransactions+3).W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned typed bin image response")}
  private val drained= !sourceOffered && (!sourceStarted || (sourceDone && !source.io.busy)) && !owned.asUInt.orR &&
    !imageOffered.asUInt.orR && !images.map(_.io.busy).reduce(_ || _) && tileQueues.map(q=> !q.io.deq.valid).reduce(_ && _) &&
    !localDrawValid && router.io.drained && inFlight===0.U && requests.io.count===0.U
  when(active && externalCancel && status===0.U){status:=4.U;faultStage:=0.U;faultTileValid:=false.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;faultStage:=0.U;faultTileValid:=false.B
      sourceOffered:=false.B;sourceStarted:=false.B;sourceDone:=false.B;sourceResult:=0.U.asTypeOf(sourceResult)
      draws:=0.U;localDrawValid:=false.B;scanX:=0.U;scanY:=0.U
      for(e<-0 until engines){owned(e):=false.B;imageOffered(e):=false.B;imageStarted(e):=false.B;imageDone(e):=false.B;haveBin(e):=false.B
        tileCounts(e):=0.U;primitiveCounts(e):=0.U;loads(e):=0.U;stores(e):=0.U;resolves(e):=0.U}
      for(i<-0 until engines+1)contexts(i):=io.payload.map(_(i).context).getOrElse(0.U)
      phase:=State.validate}}
    is(State.validate){
      val d=descriptor;val b=d.bins;val a=d.states;val c=d.image.pixels.colors
      val cols=(b.producer.screenWidth.pad(17)+tileSize-1.U)>>b.producer.tileLog2
      val rows=(b.producer.screenHeight.pad(17)+tileSize-1.U)>>b.producer.tileLog2
      val count=(a.limit-a.base)>>9
      val fixed=Seq((b.producer.arena.base,b.producer.arena.limit),(b.bins.pool.base,b.bins.pool.limit)) ++
        (if(pagedCapacity>0)Seq((b.bins.directoryBase,b.bins.directoryLimit))else Seq.empty)
      val enabled=TypedTileImageRules.enabled(d.image.pixels);val loadOps=TypedTileImageRules.loads(d.image.pixels)
      val views=d.image.views.zipWithIndex.map{case(v,i)=>val used=enabled(i) && (v.store || loadOps(i)===AttachmentLoadOp.load.U)
        (Mux(used,v.allocationBase,0.U),Mux(used,v.allocationLimit,0.U))} ++
        d.image.resolves.map(r=>(Mux(r.enable,r.destination.allocationBase,0.U),Mux(r.enable,r.destination.allocationLimit,0.U)))
      val stateClear= !(fixed++views).map{case(x,y)=>overlap(a.base,a.limit,x,y)}.reduce(_ || _)
      val scratchClear=d.scratch.zipWithIndex.map{case(s,e)=>if(e<engines)
        legalSpan(s.base,s.limit) && s.base.pad(64)+count.pad(64)*d.scratchStride.pad(64)<=s.limit &&
          !(fixed++views++Seq((a.base,a.limit))).map{case(x,y)=>overlap(s.base,s.limit,x,y)}.reduce(_ || _)
        else !s.asUInt.orR}.reduce(_ && _)
      val separate=(for(x<-0 until engines;y<-x+1 until engines)yield !overlap(d.scratch(x).base,d.scratch(x).limit,d.scratch(y).base,d.scratch(y).limit)).foldLeft(true.B)(_ && _)
      val viewsClear= !views.flatMap{case(x,y)=>fixed.map{case(z,t)=>overlap(x,y,z,t)}}.reduce(_ || _)
      val legal=d.version===1.U && b.bins.version===3.U && !b.bins.resume && b.producer.version===1.U &&
        b.producer.screenWidth>0.U && b.producer.screenHeight>0.U && b.producer.screenWidth<=g.screenWidth.U && b.producer.screenHeight<=g.screenHeight.U &&
        (b.producer.tileLog2===3.U || b.producer.tileLog2===4.U) && c.tileLog2===b.producer.tileLog2 && c.originX===0.S && c.originY===0.S &&
        cols*rows===b.bins.tileCount && cols*rows<=(if(pagedCapacity>0)pagedCapacity else tileSlots).U &&
        a.version===1.U && legalSpan(a.base,a.limit) && a.limit>a.base && ((a.limit-a.base)&511.U)===0.U &&
        a.identity.asUInt===c.identity.asUInt && a.identity.asUInt===b.producer.arena.identity.asUInt && a.identity.asUInt===b.bins.pool.identity.asUInt &&
        d.scratchStride>=64.U && d.scratchStride(5,0)===0.U && stateClear && scratchClear && separate && viewsClear
      columns:=cols
      when(stop){phase:=State.closing}.elsewhen(!legal){fail(1.U,1);phase:=State.closing}.otherwise{phase:=State.scan}
    }
    is(State.scan){
      when(stop){phase:=State.closing}.elsewhen(TypedTileImageRules.invalid(imageFor(scanX,scanY),storage)){fail(1.U,1);phase:=State.closing}
        .elsewhen(scanX+tileSize>=descriptor.bins.producer.screenWidth){
          when(scanY+tileSize>=descriptor.bins.producer.screenHeight){sourceOffered:=true.B;phase:=State.sourceOffer}
            .otherwise{scanX:=0.U;scanY:=scanY+tileSize}}
        .otherwise{scanX:=scanX+tileSize}
    }
    is(State.sourceOffer){when(stop || sourceDone){phase:=State.closing}.elsewhen(sourceStarted && source.io.draw.ready){phase:=State.run}}
    is(State.run){when(stop || sourceDone){phase:=State.closing}.elsewhen(io.end.fire){when(!io.end.bits){fail(1.U,1)};phase:=State.closing}}
    is(State.closing){when(drained){phase:=State.finish}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.states.identity
      result.status:=Mux(externalCancel && status===0.U,4.U,status);result.faultStage:=faultStage;result.faultTileValid:=faultTileValid;result.faultTile:=faultTile
      result.source:=sourceResult;result.tiles:=tileCounts.reduce(_+_);result.primitives:=primitiveCounts.reduce(_+_)
      result.loadBytes:=loads.reduce(_+_);result.storedBytes:=stores.reduce(_+_);result.resolvedBytes:=resolves.reduce(_+_);phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(drained,"typed bin image completion escaped geometry/render/publication drain")}
}
