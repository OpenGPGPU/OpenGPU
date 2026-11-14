package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

/** Independent internal frame format; never reinterpret a legacy GNF1/2. */
class LayeredWindowPrimitiveBinImageDescriptor extends Bundle {
  val version=UInt(8.W);val bins=new WindowPrimitiveBinReplayDescriptor
  val image=new TypedLayeredImageDescriptor;val states=new PrimitiveStateDescriptor
  val scratchStride=UInt(32.W);val scratch=Vec(4,new TypedReplayMemorySpan)
}
class LayeredWindowPrimitiveBinImageCompletion(p:VertexShaderStageConfig) extends Bundle {
  val version=UInt(8.W);val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  val producer=new WindowPrimitiveBinCompletion(p);val directory=new TileBinDirectoryCompletion
  val images=new LayeredTileChainSummary
}
object LayeredWindowPrimitiveBinImagePipeline {
  def imageBase(writeCredits:Int,pagedCapacity:Int):Int=writeCredits+(if(pagedCapacity>0)1 else 0)+1
  def transactions(engines:Int,writeCredits:Int,pagedCapacity:Int):Int=
    1<<log2Ceil(imageBase(writeCredits,pagedCapacity)+64*engines)
}

/** One physical producer/directory/arena/sequence stream for mixed GWP1/GWP2
  * records. The directory's ORIGINAL closed chain is dispatched straight to
  * the real layered image engines; no ordinary replay reader consumes or
  * narrows it first. The four-engine and 65536-tile bounds remain available.
  *
  * Source completion precedes directory EOF, so GS/TF execute once per source
  * draw. Empty tiles are still reported by directory version 3 and execute all
  * image layers. The caller retains code, state, all layers, VM and backing
  * leases through this held complete pass result, including failure drain.
  */
class LayeredWindowPrimitiveBinImagePipeline(config:GpuConfig,p:VertexShaderStageConfig,
    f:FragmentShaderConfig,shared:SharedCuShaderConfig,g:GraphicsConfig=GraphicsConfig(),
    engines:Int=4,pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=65536,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,groupedTexture:Boolean=false,
    fragmentImageAvailable:Boolean=false) extends Module {
  require(engines>=1 && engines<=4 && p.varyings==f.varyings && f.colors==4)
  private val sharedConfig=shared;private val payloadConfig=payload
  private val metadataId=writeCredits;private val producerId=writeCredits+(if(pagedCapacity>0)1 else 0)
  private val imageBase=LayeredWindowPrimitiveBinImagePipeline.imageBase(writeCredits,pagedCapacity)
  val memoryTransactions=LayeredWindowPrimitiveBinImagePipeline.transactions(engines,writeCredits,pagedCapacity)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredWindowPrimitiveBinImageDescriptor))
    val in=Flipped(Decoupled(new WindowPrimitiveRecord(p)));val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new LayeredTypedPixelQuadCompletion))
    val layerDone=Vec(engines,Decoupled(new LayeredTypedImageLayerCompletion))
    val tileDone=Decoupled(new LayeredTileChainCompletion)
    val completion=Decoupled(new LayeredWindowPrimitiveBinImageCompletion(p))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,imageAvailable=fragmentImageAvailable))
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,engines))else None
    val payload=payloadConfig.map(q=>Vec(engines,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(6*engines,Valid(new ShaderPayloadPin(q)))))
    val fragmentEvents=Output(new FragmentPerformanceEvents)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val producer=Module(new WindowPrimitiveBinProducer(config,p,g))
  val recordBytes=producer.recordBytes
  private val directory=Module(new TileBinDirectory(tileSlots,pageBytes,writeCredits,pagedCapacity,config,useSramBlackBoxes))
  private val words=Module(new OmWordToLinePort(config,64,writeCredits,uncached=true))
  private val scheduler=Module(new LayeredTileChainScheduler(engines))
  private val admission=Module(new TypedLayeredImageAdmission(storage))
  private val images=Seq.tabulate(engines)(e=>Module(new LayeredTypedReplayTileImagePipeline(config,p,f,shared,g,storage,
    e,pageBytes,useSramBlackBoxes,payload,payloadOwner+e,groupedTexture,fragmentImageAvailable)))
  private val router=Module(new SharedShaderTaskRouter(config,shared.numComputeUnits,engines,shared.taskIdWidth,
    shaderPayload=payload,imageAvailable=fragmentImageAvailable))
  private object State extends ChiselEnum {val idle,validate,admit,start,run,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new LayeredWindowPrimitiveBinImageDescriptor)
  private val columns=Reg(UInt(17.W));private val status=RegInit(0.U(3.W));private val cancelled=RegInit(false.B)
  private val offers=RegInit(VecInit(Seq.fill(4)(false.B))) // admission, producer, directory, scheduler
  private val started=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val ended=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val dirEnd=RegInit(false.B);private val dirEndAccepted=RegInit(false.B)
  private val producerResult=Reg(new WindowPrimitiveBinCompletion(p));private val directoryResult=Reg(new TileBinDirectoryCompletion)
  private val imageResult=Reg(new LayeredTileChainSummary);private val result=Reg(new LayeredWindowPrimitiveBinImageCompletion(p))
  private val contexts=Reg(Vec(engines,UInt(32.W)))
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  private val stop=status=/=0.U || cancelled || externalCancel || scheduler.io.cancelSource
  private val active=phase=/=State.idle && phase=/=State.complete
  private def fail(code:UInt):Unit=when(status===0.U && code=/=0.U && code=/=4.U){status:=code}
  private val tileSize=1.U(17.W)<<descriptor.bins.producer.tileLog2
  private def imageFor(tile:UInt):TypedLayeredImageDescriptor={
    val d=WireDefault(descriptor.image);val x=(tile%columns)<<descriptor.bins.producer.tileLog2
    val y=(tile/columns)<<descriptor.bins.producer.tileLog2;val c=d.image.pixels.colors
    c.originX:=x.zext;c.originY:=y.zext
    c.tileWidth:=(descriptor.bins.producer.screenWidth-x).min(tileSize)
    c.tileHeight:=(descriptor.bins.producer.screenHeight-y).min(tileSize);d
  }
  io.begin.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  io.cancelSource:=active && (stop || producer.io.cancelSource)
  admission.io.in.valid:=offers(0);admission.io.in.bits:=imageFor(0.U)
  admission.io.cancel:=started(0) && stop;admission.io.out.ready:=started(0) && !ended(0)
  producer.io.begin.valid:=offers(1);producer.io.begin.bits:=descriptor.bins.producer
  directory.io.begin.valid:=offers(2);directory.io.begin.bits:=descriptor.bins.bins
  scheduler.io.begin.valid:=offers(3);scheduler.io.begin.bits:=descriptor.states.identity
  val fires=Seq(admission.io.in.fire,producer.io.begin.fire,directory.io.begin.fire,scheduler.io.begin.fire)
  for(i<-0 until 4)when(fires(i)){offers(i):=false.B;started(i):=true.B}
  when(admission.io.out.fire){ended(0):=true.B
    when(admission.io.out.bits.invalid){fail(1.U)}
      .elsewhen(admission.io.out.bits.cancelled){cancelled:=true.B}
      .elsewhen(!stop){for(i<-1 until 4)offers(i):=true.B;phase:=State.start}}
  producer.io.in.valid:=phase===State.run && !stop && io.in.valid;producer.io.in.bits:=io.in.bits
  io.in.ready:=phase===State.run && !stop && producer.io.in.ready
  producer.io.end.valid:=phase===State.run && !stop && io.end.valid;producer.io.end.bits:=io.end.bits
  io.end.ready:=phase===State.run && !stop && producer.io.end.ready
  producer.io.cancel:=started(1) && stop
  producer.io.completion.ready:=started(1) && !ended(1)
  when(producer.io.completion.fire){producerResult:=producer.io.completion.bits;ended(1):=true.B;fail(producer.io.completion.bits.status)
    when(producer.io.completion.bits.status===4.U){cancelled:=true.B}}
  // An append already accepted from the producer must receive a reply even if
  // a directory fault has closed its input. Stop producer before directory.
  private object Bridge extends ChiselEnum {val empty,offer,waitReply,reply=Value}
  private val bridge=RegInit(Bridge.empty);private val append=Reg(new TileBinAppend);private val reply=Reg(new TileBinAppendResult)
  producer.io.append.ready:=bridge===Bridge.empty
  when(producer.io.append.fire){append:=producer.io.append.bits;bridge:=Bridge.offer}
  directory.io.append.valid:=bridge===Bridge.offer && !stop && !ended(2) && directory.io.append.ready
  directory.io.append.bits:=append;directory.io.appended.ready:=bridge===Bridge.waitReply
  when(bridge===Bridge.offer){when(stop || ended(2)){
    reply:=0.U.asTypeOf(reply);reply.identity:=descriptor.states.identity;reply.tile:=append.tile
    reply.reference:=append.reference;reply.status:=PrimitiveBinStatus.cancelled.U;bridge:=Bridge.reply
  }.elsewhen(directory.io.append.fire){bridge:=Bridge.waitReply}}
  when(directory.io.appended.fire){reply:=directory.io.appended.bits;bridge:=Bridge.reply}
  producer.io.appended.valid:=bridge===Bridge.reply;producer.io.appended.bits:=reply
  when(producer.io.appended.fire){bridge:=Bridge.empty}
  directory.io.seal.valid:=false.B;directory.io.seal.bits:=0.U
  when(started(2) && ended(1) && !stop && !ended(2) && !dirEnd && !dirEndAccepted){dirEnd:=true.B}
  directory.io.end.valid:=dirEnd;directory.io.end.bits:=true.B
  when(directory.io.end.fire){dirEnd:=false.B;dirEndAccepted:=true.B}
  directory.io.cancel:=started(2) && ended(1) && stop && !dirEnd
  directory.io.completion.ready:=started(2) && !ended(2)
  when(directory.io.completion.fire){directoryResult:=directory.io.completion.bits;ended(2):=true.B
    when(directory.io.completion.bits.status===PrimitiveBinStatus.memoryFault.U){fail(3.U)}
      .elsewhen(directory.io.completion.bits.status=/=PrimitiveBinStatus.success.U &&
        directory.io.completion.bits.status=/=PrimitiveBinStatus.cancelled.U){fail(5.U)}}
  scheduler.io.tile<>directory.io.tileClosed;scheduler.io.producerDone:=ended(2)
  scheduler.io.cancel:=started(3) && (status=/=0.U || cancelled || externalCancel)
  scheduler.io.completion.ready:=started(3) && !ended(3)
  when(scheduler.io.completion.fire){imageResult:=scheduler.io.completion.bits;ended(3):=true.B;fail(scheduler.io.completion.bits.status)
    when(scheduler.io.completion.bits.status===4.U){cancelled:=true.B}}
  io.tileDone<>scheduler.io.tileDone
  private def tieInputs(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(tieInputs);case v:Vec[_]=>v.foreach(tieInputs)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b:=false.B
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s:=0.S
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u:=0.U
    case _=>()
  }
  io.shared<>router.io.pool
  payload.foreach{_=>io.poolPayload.get:=router.io.poolPayload.get
    for(i<-0 until 2*engines)io.payloadPins.get(i):=router.io.payloadPins.get(i)}
  for((image,e)<-images.zipWithIndex){
    scheduler.io.permit(e):=phase===State.run || phase===State.closing
    image.io.begin.valid:=scheduler.io.chain(e).valid; scheduler.io.chain(e).ready:=image.io.begin.ready
    val d=image.io.begin.bits;d:=0.U.asTypeOf(d);d.version:=1.U
    d.image:=imageFor(scheduler.io.chain(e).bits.tile);d.records:=descriptor.bins.producer.arena
    d.pool:=descriptor.bins.bins.pool;d.chain:=scheduler.io.chain(e).bits;d.states:=descriptor.states
    d.directory.base:=(if(pagedCapacity>0)descriptor.bins.bins.directoryBase else 0.U)
    d.directory.limit:=(if(pagedCapacity>0)descriptor.bins.bins.directoryLimit else 0.U)
    d.engines:=engines.U;d.scratchStride:=descriptor.scratchStride;d.scratch:=descriptor.scratch
    image.io.cancel:=scheduler.io.cancelImage(e);scheduler.io.imageBusy(e):=image.io.busy
    scheduler.io.imageCompletion(e)<>image.io.completion
    io.primitiveDone(e)<>image.io.primitiveDone;io.out(e)<>image.io.out;io.layerDone(e)<>image.io.layerDone
    router.io.clients(e)<>image.io.shared
    if(groupedTexture){io.grouped.get.client(e)<>image.io.grouped.get.client(0);io.grouped.get.cuMask(e):=image.io.grouped.get.cuMask(0)}
    payload.foreach{_=>tieInputs(image.io.payload.get(0));io.payload.get(e)<>image.io.payload.get(1)
      image.io.payload.get(1).context:=contexts(e);image.io.payload.get(1).cancel:=scheduler.io.imageOwned(e) && io.payload.get(e).cancel
      router.io.clientPayload.get(e):=image.io.poolPayload.get
      for(i<-0 until 4)io.payloadPins.get(2*engines+4*e+i):=image.io.payloadPins.get(i)}
  }
  for(i<-0 until 6)io.fragmentEvents.phaseWorkers(i):=images.map(_.io.fragmentEvents.phaseWorkers(i)).reduce(_+&_)
  io.fragmentEvents.stagingOutstanding:=images.map(_.io.fragmentEvents.stagingOutstanding).reduce(_+&_)
  io.fragmentEvents.stagingBlocked:=images.map(_.io.fragmentEvents.stagingBlocked).reduce(_ || _)
  io.fragmentEvents.outputBlocked:=images.map(_.io.fragmentEvents.outputBlocked).reduce(_ || _)
  words.io.in<>directory.io.mem.req;directory.io.mem.resp<>words.io.out
  directory.io.mem.responseFault:=words.io.responseFault
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),engines+2+(if(pagedCapacity>0)1 else 0)))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  private val tag=io.memoryResponse.bits.transactionId
  def connect(index:Int,base:Int,count:Int,request:DecoupledIO[ComputeMemoryRequest],response:DecoupledIO[ComputeMemoryResponse]):Bool={
    val port=arbiter.io.in(index);port.valid:=request.valid;port.bits:=request.bits;port.bits.transactionId:=request.bits.transactionId+&base.U
    request.ready:=port.ready;val selected=tag>=base.U && tag<(base+count).U
    response.valid:=io.memoryResponse.valid && selected;response.bits:=io.memoryResponse.bits;response.bits.transactionId:=tag-base.U
    selected && response.ready
  }
  private val ready=Seq(connect(0,0,writeCredits,words.io.memoryRequest,words.io.memoryResponse),
    connect(1,producerId,1,producer.io.memoryRequest,producer.io.memoryResponse))++
    images.zipWithIndex.map{case(image,e)=>connect(e+2,imageBase+64*e,64,image.io.memoryRequest,image.io.memoryResponse)}++
    (if(pagedCapacity>0)Seq(connect(engines+2,metadataId,1,directory.io.directoryMemoryRequest.get,directory.io.directoryMemoryResponse.get))else Seq.empty)
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq;io.memoryResponse.ready:=ready.reduce(_ || _)
  private val inFlight=RegInit(0.U(log2Ceil(memoryTransactions+3).W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned layered pass response")}
  private val drained= !offers.asUInt.orR && (0 until 4).map(i=> !started(i) || ended(i)).reduce(_ && _) &&
    !producer.io.busy && !directory.io.busy && !scheduler.io.busy && !admission.io.busy &&
    !images.map(_.io.busy).reduce(_ || _) && router.io.drained && bridge===Bridge.empty && inFlight===0.U && queue.io.count===0.U
  when(active && externalCancel){cancelled:=true.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;cancelled:=false.B;offers.foreach(_:=false.B)
      started.foreach(_:=false.B);ended.foreach(_:=false.B);dirEnd:=false.B;dirEndAccepted:=false.B
      producerResult:=0.U.asTypeOf(producerResult);directoryResult:=0.U.asTypeOf(directoryResult);imageResult:=0.U.asTypeOf(imageResult)
      for(e<-0 until engines)contexts(e):=io.payload.map(_(e).context).getOrElse(0.U)
      phase:=State.validate}}
    is(State.validate){
      val d=descriptor;val b=d.bins;val a=d.states;val c=d.image.image.pixels.colors
      val cols=(b.producer.screenWidth.pad(17)+tileSize-1.U)>>b.producer.tileLog2
      val rows=(b.producer.screenHeight.pad(17)+tileSize-1.U)>>b.producer.tileLog2
      def overlap(x:UInt,y:UInt,z:UInt,t:UInt):Bool=x<y && z<t && x<t && z<y
      def span(x:UInt,y:UInt):Bool=x(5,0)===0.U && y(5,0)===0.U && y>=x && y<=(BigInt(1)<<32).U
      val fixed=Seq((b.producer.arena.base,b.producer.arena.limit),(b.bins.pool.base,b.bins.pool.limit),
        (if(pagedCapacity>0)b.bins.directoryBase else 0.U,if(pagedCapacity>0)b.bins.directoryLimit else 0.U))
      val enabled=TypedTileImageRules.enabled(d.image.image.pixels);val loads=TypedTileImageRules.loads(d.image.image.pixels)
      val views=d.image.image.views.zipWithIndex.map{case(v,i)=>val used=enabled(i) && (v.store || loads(i)===AttachmentLoadOp.load.U)
        (Mux(used,v.allocationBase,0.U),Mux(used,v.allocationLimit,0.U), !used || (v.imageWidth>=b.producer.screenWidth && v.imageHeight>=b.producer.screenHeight))}++
        d.image.image.resolves.map(r=>(Mux(r.enable,r.destination.allocationBase,0.U),Mux(r.enable,r.destination.allocationLimit,0.U),
          !r.enable || (r.destination.imageWidth>=b.producer.screenWidth && r.destination.imageHeight>=b.producer.screenHeight)))
      val occupied=fixed++views.map{case(x,y,_)=>x->y}
      val count=(a.limit-a.base)>>9
      val stateClear= !occupied.map{case(x,y)=>overlap(a.base,a.limit,x,y)}.reduce(_ || _)
      val scratchClear=d.scratch.zipWithIndex.map{case(s,e)=>if(e<engines)
        span(s.base,s.limit) && s.base.pad(64)+count.pad(64)*d.scratchStride.pad(64)<=s.limit &&
          !(occupied:+(a.base->a.limit)).map{case(x,y)=>overlap(s.base,s.limit,x,y)}.reduce(_ || _)
        else !s.asUInt.orR}.reduce(_ && _)
      val separate=(for(x<-0 until engines;y<-x+1 until engines)yield !overlap(d.scratch(x).base,d.scratch(x).limit,d.scratch(y).base,d.scratch(y).limit)).foldLeft(true.B)(_ && _)
      val fixedClear=(for(x<-fixed.indices;y<-x+1 until fixed.size)yield !overlap(fixed(x)._1,fixed(x)._2,fixed(y)._1,fixed(y)._2)).reduce(_ && _)
      val viewsClear= !views.flatMap{case(x,y,_)=>fixed.map{case(z,t)=>overlap(x,y,z,t)}}.reduce(_ || _)
      val legal=d.version===1.U && b.producer.version===1.U && b.bins.version===3.U && !b.bins.resume &&
        b.producer.screenWidth>0.U && b.producer.screenHeight>0.U && b.producer.screenWidth<=g.screenWidth.U && b.producer.screenHeight<=g.screenHeight.U &&
        (b.producer.tileLog2===3.U || b.producer.tileLog2===4.U) && c.tileLog2===b.producer.tileLog2 && c.originX===0.S && c.originY===0.S &&
        cols*rows===b.bins.tileCount && cols*rows<=(if(pagedCapacity>0)pagedCapacity else tileSlots).U &&
        a.version===1.U && span(a.base,a.limit) && a.limit>a.base && ((a.limit-a.base)&511.U)===0.U &&
        a.identity.asUInt===c.identity.asUInt && a.identity.asUInt===b.producer.arena.identity.asUInt && a.identity.asUInt===b.bins.pool.identity.asUInt &&
        fixed.map{case(x,y)=>span(x,y)}.reduce(_ && _) && fixedClear && views.map(_._3).reduce(_ && _) &&
        d.scratchStride>=64.U && d.scratchStride(5,0)===0.U && stateClear && scratchClear && separate && viewsClear
      columns:=cols
      when(stop){phase:=State.closing}.elsewhen(!legal){fail(1.U);phase:=State.closing}
        .otherwise{offers(0):=true.B;phase:=State.admit}
    }
    is(State.admit){when(stop || (ended(0) && !offers.asUInt.orR)){phase:=State.closing}}
    is(State.start){when(started(1) && started(2) && started(3)){phase:=State.run}}
    is(State.run){when(stop || ended(1)){phase:=State.closing}}
    is(State.closing){when(drained){phase:=State.finish}}
    is(State.finish){result.version:=1.U;result.identity:=descriptor.states.identity
      result.status:=Mux(status=/=0.U,status,Mux(cancelled || externalCancel,4.U,0.U))
      result.producer:=producerResult;result.directory:=directoryResult;result.images:=imageResult;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(drained,"layered pass completed before records/images/leases drained")}
}
