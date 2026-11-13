package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class LayeredTypedReplayTileDescriptor extends Bundle {
  val version=UInt(8.W);val image=new TypedLayeredImageDescriptor
  val records=new PrimitiveStateDescriptor;val pool=new BinPagePoolDescriptor;val directory=new TypedReplayMemorySpan
  val chain=new TileBinCompletion;val states=new PrimitiveStateDescriptor
  val engines=UInt(3.W);val scratchStride=UInt(32.W);val scratch=Vec(4,new TypedReplayMemorySpan)
}
class LayeredTypedPixelQuadCompletion extends Bundle {
  val layer=UInt(32.W);val pixels=new TypedTilePixelQuadCompletion
}
class LayeredTypedImageLayerCompletion extends Bundle {
  val layer=UInt(32.W);val image=new TypedReplayTileCompletion
}
class LayeredTypedReplayTileCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val tile=UInt(32.W);val status=UInt(3.W);val faultStage=UInt(3.W)
  val replay=new LayeredWindowReplayCompletion
  val accepted=UInt(64.W);val issued=UInt(64.W);val completed=UInt(64.W)
  val loadBytes=UInt(64.W);val storedBytes=UInt(64.W);val resolvedBytes=UInt(64.W)
}

/** One retained tile, one physical typed image owner, all admitted layers.
  * Empty layers bind/clear/load/end/store/resolve just like populated layers.
  * Only matching records launch fragments; source GS/TF work is not repeated.
  * The enclosing dispatcher retains its tile credit, all backing and VM leases
  * through this held final completion. It must drain all offered per-primitive,
  * pixel and layer results during faults/cancellation and discard failed-pass
  * outputs. This explicit internal owner is not enabled by a product profile.
  */
class LayeredTypedReplayTileImagePipeline(config:GpuConfig=GpuConfig(),p:VertexShaderStageConfig=VertexShaderStageConfig(),
    f:FragmentShaderConfig=FragmentShaderConfig(),shared:SharedCuShaderConfig=SharedCuShaderConfig(4),
    g:GraphicsConfig=GraphicsConfig(),storage:TypedTileColorConfig=TypedTileColorConfig(),engine:Int=0,pageBytes:Int=4096,
    useSramBlackBoxes:Boolean=false,payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,
    groupedTexture:Boolean=false,fragmentImageAvailable:Boolean=false) extends Module {
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredTypedReplayTileDescriptor))
    val primitiveDone=Decoupled(new TypedReplayPrimitiveCompletion)
    val out=Decoupled(new LayeredTypedPixelQuadCompletion);val layerDone=Decoupled(new LayeredTypedImageLayerCompletion)
    val completion=Decoupled(new LayeredTypedReplayTileCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits,imageAvailable=fragmentImageAvailable))
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,1))else None
    val payload=payloadConfig.map(q=>Vec(2,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val fragmentEvents=Output(new FragmentPerformanceEvents)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,64))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,64)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val admission=Module(new TypedLayeredImageAdmission(storage))
  private val scanner=Module(new LayeredWindowPrimitiveReplayPipeline(config,p,pageBytes))
  private val image=Module(new TypedReplayTileImagePipeline(config,p,f,shared,g,storage,engine,useSramBlackBoxes,
    payload,payloadOwner,groupedTexture,fragmentImageAvailable,geometryInput=true))
  private object State extends ChiselEnum {val idle,validate,admit,run,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new LayeredTypedReplayTileDescriptor)
  private val status=RegInit(0.U(3.W));private val stage=RegInit(0.U(3.W));private val cancelled=RegInit(false.B)
  private val admissionOffered=RegInit(false.B);private val admissionStarted=RegInit(false.B);private val admissionDone=RegInit(false.B)
  private val scannerOffered=RegInit(false.B);private val scannerStarted=RegInit(false.B);private val scannerDone=RegInit(false.B)
  private val imageBound=RegInit(false.B);private val layer=Reg(UInt(32.W))
  private val original=Reg(new PrimitiveBinReference);private val normalized=Reg(new PrimitiveBinReference)
  private val originalValid=RegInit(false.B)
  private val scanResult=Reg(new LayeredWindowReplayCompletion);private val result=Reg(new LayeredTypedReplayTileCompletion)
  private val accepted=RegInit(0.U(64.W));private val issued=RegInit(0.U(64.W));private val completed=RegInit(0.U(64.W))
  private val loads=RegInit(0.U(64.W));private val stores=RegInit(0.U(64.W));private val resolves=RegInit(0.U(64.W))
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  // An internally cancelled image is secondary to a page/record fault retained
  // by the scanner. Capture that original cause after the shared drain.
  private def imageFault(code:UInt):Bool=code=/=0.U && code=/=4.U
  private val imageFailure=(image.io.primitiveDone.valid && imageFault(image.io.primitiveDone.bits.status)) ||
    (image.io.out.valid && imageFault(image.io.out.bits.status)) || (image.io.completion.valid && imageFault(image.io.completion.bits.status))
  private val scanFailure=scanner.io.completion.valid && scanner.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || cancelled || externalCancel || imageFailure || scanFailure || scanner.io.fault.valid
  private val active=phase=/=State.idle && phase=/=State.complete
  private def fail(code:UInt,where:Int): Unit={
    val realChild=(where==2 || where==3).B && code=/=4.U
    val sourceFirst=if(where==3)!scanner.io.fault.valid else true.B
    when(status===0.U && !cancelled && (!externalCancel || realChild) && sourceFirst){status:=code;stage:=where.U}
  }
  // Lock the first observable real cause before propagating cancellation.
  // Waiting for a child's terminal result could otherwise turn its earlier
  // pixel/primitive memory error into a secondary cancelled completion.
  when(active && scanner.io.fault.valid){fail(scanner.io.fault.bits.status,2)}
    .elsewhen(active && imageFailure){
      val code=Mux(image.io.primitiveDone.valid && imageFault(image.io.primitiveDone.bits.status),image.io.primitiveDone.bits.status,
        Mux(image.io.out.valid && imageFault(image.io.out.bits.status),image.io.out.bits.status,image.io.completion.bits.status))
      fail(code,3)
    }
  io.begin.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  admission.io.in.valid:=admissionOffered;admission.io.in.bits:=descriptor.image
  admission.io.cancel:=admissionStarted && stop;admission.io.out.ready:=admissionStarted && !admissionDone
  when(admission.io.in.fire){admissionOffered:=false.B;admissionStarted:=true.B}
  when(admission.io.out.fire){admissionDone:=true.B
    when(admission.io.out.bits.invalid){fail(1.U,1)}
      .elsewhen(admission.io.out.bits.cancelled){cancelled:=true.B}
      .elsewhen(!stop){scannerOffered:=true.B;phase:=State.run}}
  scanner.io.begin.valid:=scannerOffered;scanner.io.begin.bits.arena:=descriptor.records
  scanner.io.begin.bits.pool:=descriptor.pool;scanner.io.begin.bits.chain:=descriptor.chain
  scanner.io.begin.bits.layers:=descriptor.image.layers
  when(scanner.io.begin.fire){scannerOffered:=false.B;scannerStarted:=true.B}
  scanner.io.cancel:=scannerStarted && stop
  scanner.io.completion.ready:=scannerStarted && !scannerDone
  when(scanner.io.completion.fire){scannerDone:=true.B;scanResult:=scanner.io.completion.bits
    when(scanner.io.completion.bits.status=/=0.U){fail(scanner.io.completion.bits.status,2)}}
  private val (selectedValid,selectedImage)=TypedLayeredImageSelection(descriptor.image,scanner.io.layerBegin.bits)
  image.io.begin.valid:=scanner.io.layerBegin.valid;scanner.io.layerBegin.ready:=image.io.begin.ready
  image.io.begin.bits:=0.U.asTypeOf(image.io.begin.bits)
  image.io.begin.bits.image:=selectedImage;image.io.begin.bits.tile:=descriptor.chain.tile
  image.io.begin.bits.states:=descriptor.states;image.io.begin.bits.engines:=descriptor.engines
  image.io.begin.bits.scratchStride:=descriptor.scratchStride;image.io.begin.bits.scratch:=descriptor.scratch
  image.io.begin.bits.protectedMemory(0).base:=descriptor.records.base;image.io.begin.bits.protectedMemory(0).limit:=descriptor.records.limit
  image.io.begin.bits.protectedMemory(1).base:=descriptor.pool.base;image.io.begin.bits.protectedMemory(1).limit:=descriptor.pool.limit
  image.io.begin.bits.protectedMemory(2):=descriptor.directory
  when(image.io.begin.fire){assert(selectedValid,"layer scanner selected an unadmitted attachment layer")
    imageBound:=true.B;layer:=scanner.io.layerBegin.bits}
  private val bridgeStop=stop || scanner.io.cancelLayer
  image.io.in.valid:=scanner.io.out.valid && imageBound && !bridgeStop && image.io.in.ready
  image.io.in.bits:=scanner.io.out.bits.replay
  scanner.io.out.ready:=bridgeStop || (imageBound && image.io.in.ready)
  when(image.io.in.fire){assert(!originalValid,"layer image replaced an owned primitive reference")
    original:=scanner.io.out.bits.original;normalized:=scanner.io.out.bits.replay.source;originalValid:=true.B}
  io.primitiveDone.valid:=image.io.primitiveDone.valid;io.primitiveDone.bits:=image.io.primitiveDone.bits
  io.primitiveDone.bits.source:=original;image.io.primitiveDone.ready:=io.primitiveDone.ready
  when(io.primitiveDone.valid){assert(originalValid && image.io.primitiveDone.bits.source.asUInt===normalized.asUInt,
    "layer image returned an unowned original reference")}
  when(io.primitiveDone.fire){originalValid:=false.B}
  image.io.end.valid:=scanner.io.layerEnd.valid && !bridgeStop && image.io.end.ready
  image.io.end.bits:=true.B;scanner.io.layerEnd.ready:=bridgeStop || image.io.end.ready
  image.io.cancel:=imageBound && (stop || scanner.io.cancelLayer)
  scanner.io.layerBusy:=image.io.busy
  io.out.valid:=image.io.out.valid;io.out.bits.layer:=layer;io.out.bits.pixels:=image.io.out.bits;image.io.out.ready:=io.out.ready
  // Both consumers acquire the same held image result in one handshake.
  scanner.io.layerCompletion.valid:=image.io.completion.valid && io.layerDone.ready
  scanner.io.layerCompletion.bits:=image.io.completion.bits
  io.layerDone.valid:=image.io.completion.valid && scanner.io.layerCompletion.ready
  io.layerDone.bits.layer:=layer;io.layerDone.bits.image:=image.io.completion.bits
  image.io.completion.ready:=scanner.io.layerCompletion.ready && io.layerDone.ready
  when(image.io.completion.fire){imageBound:=false.B
    val r=image.io.completion.bits;accepted:=accepted+r.accepted;issued:=issued+r.issued;completed:=completed+r.completed
    loads:=loads+r.image.transfers.loadBytes;stores:=stores+r.image.transfers.storedBytes;resolves:=resolves+r.image.transfers.resolvedBytes
    when(r.status=/=0.U && !(r.status===4.U && scanner.io.cancelLayer)){fail(r.status,3)}}
  io.shared<>image.io.shared;io.fragmentEvents:=image.io.fragmentEvents
  if(groupedTexture)io.grouped.get<>image.io.grouped.get
  payload.foreach{_=>for(i<-0 until 2){io.payload.get(i)<>image.io.payload.get(i)
    image.io.payload.get(i).cancel:=io.payload.get(i).cancel && imageBound}
    io.poolPayload.get:=image.io.poolPayload.get;io.payloadPins.get:=image.io.payloadPins.get}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,64),2))
  arbiter.io.in(0).valid:=scanner.io.memoryRequest.valid;arbiter.io.in(0).bits:=scanner.io.memoryRequest.bits
  scanner.io.memoryRequest.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=image.io.memoryRequest.valid;arbiter.io.in(1).bits:=image.io.memoryRequest.bits
  arbiter.io.in(1).bits.transactionId:=image.io.memoryRequest.bits.transactionId+&2.U;image.io.memoryRequest.ready:=arbiter.io.in(1).ready
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,64),2,pipe=true))
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val scanResponse=io.memoryResponse.bits.transactionId<2.U
  private val imageResponse=io.memoryResponse.bits.transactionId>=2.U && io.memoryResponse.bits.transactionId<34.U
  scanner.io.memoryResponse.valid:=io.memoryResponse.valid && scanResponse;scanner.io.memoryResponse.bits:=io.memoryResponse.bits
  image.io.memoryResponse.valid:=io.memoryResponse.valid && imageResponse;image.io.memoryResponse.bits:=io.memoryResponse.bits
  image.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId-2.U
  io.memoryResponse.ready:=Mux(scanResponse,scanner.io.memoryResponse.ready,imageResponse && image.io.memoryResponse.ready)
  when(io.memoryResponse.valid){assert(scanResponse || imageResponse,"unowned layered image response tag")}
  private val inFlight=RegInit(0.U(7.W));inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned layered image memory response")}
  private val memoryDrained=inFlight===0.U && queue.io.count===0.U
  private val drained= !admissionOffered && !scannerOffered && !originalValid && !imageBound && !image.io.busy &&
    (!admissionStarted || (admissionDone && !admission.io.busy)) &&
    (!scannerStarted || (scannerDone && !scanner.io.busy)) && memoryDrained
  when(active && externalCancel){cancelled:=true.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;stage:=0.U;cancelled:=false.B
      admissionOffered:=false.B;admissionStarted:=false.B;admissionDone:=false.B
      scannerOffered:=false.B;scannerStarted:=false.B;scannerDone:=false.B;imageBound:=false.B;originalValid:=false.B
      accepted:=0.U;issued:=0.U;completed:=0.U;loads:=0.U;stores:=0.U;resolves:=0.U
      scanResult:=0.U.asTypeOf(scanResult);phase:=State.validate}}
    is(State.validate){
      val d=descriptor;val a=d.states;val count=(a.limit-a.base)>>9
      def overlap(x:UInt,y:UInt,z:UInt,t:UInt):Bool=x<y && z<t && x<t && z<y
      def legalSpan(x:UInt,y:UInt):Bool=x(5,0)===0.U && y(5,0)===0.U && y>=x && y<=(BigInt(1)<<32).U
      val fixed=Seq((d.records.base,d.records.limit),(d.pool.base,d.pool.limit),(d.directory.base,d.directory.limit))
      val en=TypedTileImageRules.enabled(d.image.image.pixels);val load=TypedTileImageRules.loads(d.image.image.pixels)
      val views=d.image.image.views.zipWithIndex.map{case(v,i)=>val needed=en(i) && (v.store || load(i)===AttachmentLoadOp.load.U)
        (Mux(needed,v.allocationBase,0.U),Mux(needed,v.allocationLimit,0.U))} ++
        d.image.image.resolves.map(r=>(Mux(r.enable,r.destination.allocationBase,0.U),Mux(r.enable,r.destination.allocationLimit,0.U)))
      val stateClear= !(fixed++views).map{case(x,y)=>overlap(a.base,a.limit,x,y)}.reduce(_ || _)
      val scratchClear=d.scratch.zipWithIndex.map{case(s,e)=>Mux(e.U<d.engines,
        legalSpan(s.base,s.limit) && s.base.pad(64)+count.pad(64)*d.scratchStride.pad(64)<=s.limit &&
          !(fixed++views++Seq((a.base,a.limit))).map{case(x,y)=>overlap(s.base,s.limit,x,y)}.reduce(_ || _),!s.asUInt.orR)}.reduce(_ && _)
      val separate=(for(x<-0 until 4;y<-x+1 until 4)yield y.U>=d.engines ||
        !overlap(d.scratch(x).base,d.scratch(x).limit,d.scratch(y).base,d.scratch(y).limit)).reduce(_ && _)
      val fixedClear=(for(x<-fixed.indices;y<-x+1 until fixed.size)yield
        !overlap(fixed(x)._1,fixed(x)._2,fixed(y)._1,fixed(y)._2)).reduce(_ && _)
      val viewsClear= !views.flatMap{case(x,y)=>fixed.map{case(z,t)=>overlap(x,y,z,t)}}.reduce(_ || _)
      val legal=d.version===1.U && d.engines>=1.U && d.engines<=4.U && engine.U<d.engines &&
        fixed.map{case(x,y)=>legalSpan(x,y)}.reduce(_ && _) && fixedClear &&
        a.version===1.U && legalSpan(a.base,a.limit) && a.limit>a.base && ((a.limit-a.base)&511.U)===0.U &&
        a.identity.asUInt===d.image.image.pixels.colors.identity.asUInt &&
        a.identity.asUInt===d.records.identity.asUInt && a.identity.asUInt===d.pool.identity.asUInt &&
        a.identity.asUInt===d.chain.identity.asUInt && d.scratchStride>=64.U && d.scratchStride(5,0)===0.U &&
        stateClear && scratchClear && separate && viewsClear
      when(stop){phase:=State.closing}.elsewhen(!legal){fail(1.U,1);phase:=State.closing}
        .otherwise{admissionOffered:=true.B;phase:=State.admit}
    }
    is(State.admit){when(stop || (admissionDone && !scannerOffered)){phase:=State.closing}}
    is(State.run){when(stop || scannerDone){phase:=State.closing}}
    is(State.closing){when(drained){phase:=State.finish}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.states.identity;result.tile:=descriptor.chain.tile
      result.status:=Mux(status=/=0.U,status,Mux(cancelled || externalCancel,4.U,0.U));result.faultStage:=stage
      result.replay:=scanResult;result.replay.chain:=descriptor.chain
      result.replay.identity:=descriptor.records.identity;result.replay.tile:=descriptor.chain.tile
      result.accepted:=accepted;result.issued:=issued;result.completed:=completed
      result.loadBytes:=loads;result.storedBytes:=stores;result.resolvedBytes:=resolves;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(drained,"layered tile completed before admission/replay/image/publication drain")}
}
