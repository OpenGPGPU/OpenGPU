package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class LayeredGeometryBinImageDescriptor extends Bundle {
  val bins=new WindowPrimitiveBinReplayDescriptor
  val image=new LayeredTileImageBindingRequest
}
class LayeredGeometryBinImageCompletion(p:VertexShaderStageConfig) extends Bundle {
  val status=UInt(3.W);val sourceStarted=Bool();val source=new GeometryWindowBinCompletion(p)
}

/** Cross-client checks before a geometry pass can issue work. */
class LayeredGeometryBinImageAdmission(engines:Int=1) extends Module {
  require(engines>=1 && engines<=4)
  val io=IO(new Bundle {val descriptor=Input(new LayeredGeometryBinImageDescriptor);val legal=Output(Bool())})
  private val d=io.descriptor;private val b=d.bins.producer;private val i=d.image
  private val size=1.U(17.W)<<b.tileLog2
  private val cols=(b.screenWidth.pad(17)+size-1.U)>>b.tileLog2
  private val rows=(b.screenHeight.pad(17)+size-1.U)>>b.tileLog2
  private val count=cols*rows*i.layerCount
  private val identity=b.arena.identity.asUInt;private val state=i.template.states
  private def overlap(a:UInt,b:UInt,c:UInt,d:UInt):Bool=a<b && c<d && a<d && c<b
  private def legalSpan(a:UInt,b:UInt):Bool=a(5,0)===0.U && b(5,0)===0.U && b>=a && b<=(BigInt(1)<<32).U
  private val fixed=Seq((b.arena.base,b.arena.limit),(d.bins.bins.pool.base,d.bins.bins.pool.limit),
    (d.bins.bins.directoryBase,d.bins.bins.directoryLimit))
  private val enabled=TypedTileImageRules.enabled(i.template.image.pixels);private val loads=TypedTileImageRules.loads(i.template.image.pixels)
  private val views=i.template.image.views.zipWithIndex.map{case(v,n)=>
    val used=enabled(n) && (v.store || loads(n)===AttachmentLoadOp.load.U)
    (Mux(used,v.allocationBase,0.U),Mux(used,v.allocationLimit,0.U))} ++
    i.template.image.resolves.map(r=>(Mux(r.enable,r.destination.allocationBase,0.U),Mux(r.enable,r.destination.allocationLimit,0.U)))
  private val countStates=(state.limit-state.base)>>9
  private val stateClear= !(fixed++views).map{case(a,z)=>overlap(state.base,state.limit,a,z)}.reduce(_ || _)
  private val scratchClear=i.template.scratch.zipWithIndex.map{case(v,n)=>if(n<engines)
    legalSpan(v.base,v.limit) && v.base.pad(64)+countStates.pad(64)*i.template.scratchStride.pad(64)<=v.limit &&
      !(fixed++views++Seq((state.base,state.limit))).map{case(a,z)=>overlap(v.base,v.limit,a,z)}.reduce(_ || _)
    else !v.asUInt.orR}.reduce(_ && _)
  private val scratchSeparate=(for(a<-0 until engines;b<-a+1 until engines)yield
    !overlap(i.template.scratch(a).base,i.template.scratch(a).limit,i.template.scratch(b).base,i.template.scratch(b).limit)).foldLeft(true.B)(_ && _)
  private val viewsClear= !views.flatMap{case(a,z)=>fixed.map{case(c,d)=>overlap(a,z,c,d)}}.reduce(_ || _)
  io.legal:=(b.tileLog2===3.U || b.tileLog2===4.U) && b.screenWidth=/=0.U && b.screenHeight=/=0.U &&
    i.layerCount=/=0.U && i.screenWidth===b.screenWidth && i.screenHeight===b.screenHeight &&
    i.template.image.pixels.colors.tileLog2===b.tileLog2 && count<=65535.U &&
    d.bins.bins.version===3.U && !d.bins.bins.resume && d.bins.bins.tileCount===count &&
    state.version===1.U && legalSpan(state.base,state.limit) && state.limit>state.base && ((state.limit-state.base)&511.U)===0.U &&
    i.template.scratchStride>=64.U && i.template.scratchStride(5,0)===0.U && stateClear && scratchClear && scratchSeparate && viewsClear &&
    state.identity.asUInt===identity && d.bins.bins.pool.identity.asUInt===identity &&
    i.template.image.pixels.colors.identity.asUInt===identity
}

/** Internal physical-address geometry/bin/image composition. The enclosing
  * owner validates allocation provenance and cross-attachment aliasing, pins
  * all resources through completion, and discards outputs on any pass failure.
  */
class LayeredGeometryBinImagePipeline(config:GpuConfig,p:VertexShaderStageConfig,f:FragmentShaderConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),
    engines:Int=2,pageBytes:Int=4096,writeCredits:Int=8,tileSlots:Int=8,pagedCapacity:Int=0,
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false) extends Module {
  require(engines>=1 && engines<=4 && p.varyings==f.varyings && f.colors==4)
  private val sharedConfig=shared
  private val binSlots=VertexBinPipeline.binTransactions(engines,writeCredits,pagedCapacity)
  val memoryTransactions=1<<log2Ceil(binSlots+32*engines)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredGeometryBinImageDescriptor))
    val task=Flipped(Decoupled(new GeometryWindowBinTask(config,p,sharedConfig.taskIdWidth)))
    val taskCompletion=Decoupled(new GeometryWindowBinTaskCompletion);val end=Flipped(Decoupled(Bool()))
    val primitiveDone=Vec(engines,Decoupled(new TypedReplayPrimitiveCompletion))
    val out=Vec(engines,Decoupled(new TypedTilePixelQuadCompletion))
    val tileDone=Vec(engines,Decoupled(new LayeredBinTileCompletion))
    val completion=Decoupled(new LayeredGeometryBinImageCompletion(p))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,geometryAvailable=true))
    val geometryTexture=Vec(sharedConfig.numComputeUnits,new SharedCuTexturePort(config))
    val geometryTrap=Decoupled(new TaggedGraphicsTrap(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)))
    val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,memoryTransactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,memoryTransactions)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val source=Module(new GeometryWindowBinPass(config,p,shared.taskIdWidth,shared.numComputeUnits,g,viewport,
    engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useSramBlackBoxes,layered=true,rendererCredits=true))
  private val dispatch=Module(new LayeredBinTileDispatch(p,engines))
  private val images=Seq.tabulate(engines)(e=>Module(new LayeredReplayTileImagePipeline(config,p,f,shared,g,storage,e,useSramBlackBoxes)))
  private val router=Module(new GeometryMultiFragmentShaderRouter(config,shared.numComputeUnits,shared.taskIdWidth,engines))
  router.io.geometry<>source.io.shared;io.shared<>router.io.pool
  source.io.texture<>io.geometryTexture;io.geometryTrap<>source.io.trap
  source.io.invalidate<>io.geometryInvalidate;io.geometryInvalidateDone<>source.io.invalidateDone
  private object State extends ChiselEnum {val idle,validate,offer,run,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new LayeredGeometryBinImageDescriptor)
  private val started=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val sourceResult=Reg(new GeometryWindowBinCompletion(p));private val status=RegInit(0.U(3.W))
  private val result=Reg(new LayeredGeometryBinImageCompletion(p))
  private val admission=Module(new LayeredGeometryBinImageAdmission(engines));admission.io.descriptor:=descriptor
  private val sourceFailure=(source.io.taskCompletion.valid && source.io.taskCompletion.bits.status=/=0.U) ||
    (source.io.completion.valid && source.io.completion.bits.status=/=0.U)
  private val tileFailure=dispatch.io.completion.map(x=>x.valid && x.bits.status=/=0.U).reduce(_ || _)
  private val stop=status=/=0.U || io.cancel || sourceFailure || dispatch.io.protocolFault || tileFailure
  private val running=phase===State.run
  io.begin.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  source.io.begin.valid:=phase===State.offer;source.io.begin.bits:=descriptor.bins
  source.io.layerCount.get:=descriptor.image.layerCount
  source.io.cancel:=started && stop
  source.io.task.valid:=running && !stop && io.task.valid;source.io.task.bits:=io.task.bits
  io.task.ready:=running && !stop && source.io.task.ready
  io.taskCompletion<>source.io.taskCompletion
  source.io.end.valid:=running && !stop && io.end.valid;source.io.end.bits:=io.end.bits
  io.end.ready:=running && !stop && source.io.end.ready
  source.io.completion.ready:=started && !sourceDone
  when(source.io.completion.fire){sourceDone:=true.B;sourceResult:=source.io.completion.bits
    when(status===0.U && source.io.completion.bits.status=/=0.U){status:=source.io.completion.bits.status}}
  dispatch.io.enable:=running;dispatch.io.cancel:=stop;dispatch.io.clearFault:=phase===State.idle
  dispatch.io.source<>source.io.out;dispatch.io.sourceDone<>source.io.tileDone
  source.io.rendererPermit.get:=dispatch.io.rendererPermit
  for((image,e)<-images.zipWithIndex){
    val template=WireDefault(descriptor.image);template.template.engines:=engines.U
    template.template.protectedMemory(0).base:=descriptor.bins.producer.arena.base
    template.template.protectedMemory(0).limit:=descriptor.bins.producer.arena.limit
    template.template.protectedMemory(1).base:=descriptor.bins.bins.pool.base
    template.template.protectedMemory(1).limit:=descriptor.bins.bins.pool.limit
    template.template.protectedMemory(2).base:=(if(pagedCapacity>0)descriptor.bins.bins.directoryBase else 0.U)
    template.template.protectedMemory(2).limit:=(if(pagedCapacity>0)descriptor.bins.bins.directoryLimit else 0.U)
    dispatch.io.templates(e):=template
    image.io.begin<>dispatch.io.imageBegin(e);image.io.in<>dispatch.io.imageIn(e);image.io.end<>dispatch.io.imageEnd(e)
    dispatch.io.imageCompletion(e)<>image.io.completion;image.io.cancel:=dispatch.io.imageCancel(e)
    io.primitiveDone(e)<>image.io.primitiveDone;io.out(e)<>image.io.out;io.tileDone(e)<>dispatch.io.completion(e)
    router.io.fragment(e)<>image.io.shared
    when(dispatch.io.completion(e).valid && dispatch.io.completion(e).bits.status=/=0.U && status===0.U){
      status:=dispatch.io.completion(e).bits.status}
  }
  when(dispatch.io.protocolFault && status===0.U){status:=7.U}
  when((phase===State.offer || running) && io.cancel && status===0.U){status:=4.U}
  when(running && source.io.taskCompletion.valid && source.io.taskCompletion.bits.status=/=0.U && status===0.U){
    status:=source.io.taskCompletion.bits.status}
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
    imageReplies(e):=selected && image.io.memoryResponse.ready}
  requests.io.enq<>arbiter.io.out;io.memoryRequest<>requests.io.deq
  io.memoryResponse.ready:=Mux(io.memoryResponse.bits.transactionId<binSlots.U,source.io.memoryResponse.ready,imageReplies.asUInt.orR)
  private val inFlight=RegInit(0.U(log2Ceil(memoryTransactions+3).W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned layered geometry image response")}
  private val drained=sourceDone && !source.io.busy && !dispatch.io.busy && !images.map(_.io.busy).reduce(_ || _) &&
    router.io.drained && inFlight===0.U && requests.io.count===0.U
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;started:=false.B;sourceDone:=false.B
      sourceResult:=0.U.asTypeOf(sourceResult);status:=0.U;result:=0.U.asTypeOf(result);phase:=State.validate}}
    is(State.validate){when(io.cancel){status:=4.U;result.status:=4.U;phase:=State.complete}
      .elsewhen(!admission.io.legal){status:=1.U;result.status:=1.U;phase:=State.complete}.otherwise{phase:=State.offer}}
    is(State.offer){when(source.io.begin.fire){started:=true.B;phase:=State.run}}
    is(State.run){when(drained){result.status:=Mux(status=/=0.U,status,Mux(io.cancel,4.U,0.U))
      result.sourceStarted:=true.B;result.source:=sourceResult;phase:=State.complete}}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
}
