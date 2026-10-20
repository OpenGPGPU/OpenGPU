package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class VertexFragmentDraw(p: VertexShaderStageConfig,f: FragmentShaderConfig) extends Bundle {
  val version=UInt(8.W);val vertex=new VertexRasterDraw(p);val fragment=new FragmentShaderDraw(f)
}
class VertexFragmentCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** True when vertex/clip/viewport data came from an immutable bin record. */
  val preparedGeometry=Bool()
  /** 0 success/cancellation,1 preflight,2 geometry/raster,3 fragment. */
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W)
  val vertex=new VertexRasterCompletion;val fragment=new FragmentShaderCompletion;val outputQuads=UInt(99.W)
}

/** One draw owns GPU input through ordered raw typed fragment exports. Two
  * clients share the enclosing physical CU pool and payload service. Explicit
  * source EOF, prospective/queued payload pins and accepted traffic survive
  * cancellation/fault drain; completion retains VM ownership until consumed.
  * Native bin/host admission and typed attachment consumption remain external.
  */
class VertexFragmentPipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    f: FragmentShaderConfig=FragmentShaderConfig(),shared: SharedCuShaderConfig=SharedCuShaderConfig(4),
    g: GraphicsConfig=GraphicsConfig(),viewport: Fp32ViewportConfig=Fp32ViewportConfig(),
    useSramBlackBoxes: Boolean=false,payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,
    preparedGeometry: Boolean=false) extends Module {
  require(p.varyings==f.varyings,"vertex and fragment interface capacities must match")
  payload.foreach(q=>require(payloadOwner>=0 && payloadOwner+1<q.owners))
  private val shaderConfig=shared.copy(shaderPayload=None);private val payloadConfig=payload
  val io=IO(new Bundle {
    val draw=Flipped(Decoupled(new VertexFragmentDraw(p,f)));val out=Decoupled(new FragmentShaderQuad(f,g))
    val primitive=if(preparedGeometry)Some(Input(new WindowPrimitive(p)))else None
    val completion=Decoupled(new VertexFragmentCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,shaderConfig.taskIdWidth,shaderConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>Vec(2,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val vertex=if(!preparedGeometry)Some(Module(new VertexRasterPipeline(config,p,shaderConfig,g,viewport,useSramBlackBoxes,payload,payloadOwner,centroidInput=true)))else None
  private val fragment=Module(new FragmentShaderStage(config,f,shaderConfig,g,useSramBlackBoxes,payload,payloadOwner+1))
  private val router=Module(new SharedShaderTaskRouter(config,shared.numComputeUnits,2,shared.taskIdWidth,shaderPayload=payload))
  router.io.clients(1)<>fragment.io.shared;io.shared<>router.io.pool
  private val vertexDraw=Wire(Decoupled(new VertexRasterDraw(p)))
  private val vertexOut=Wire(Decoupled(new Fp32RasterizedQuad(p,g)))
  private val vertexCompletion=Wire(Decoupled(new VertexRasterCompletion))
  private val vertexCancel=Wire(Bool());private val vertexBusy=Wire(Bool())
  private val vertexRequest=Wire(Decoupled(new ComputeMemoryRequest(config,64,4)))
  private val vertexResponse=Wire(Decoupled(new ComputeMemoryResponse(64,4)))
  private val primitive=if(preparedGeometry)Some(Reg(new WindowPrimitive(p)))else None
  private val centroidMask=Wire(UInt((p.varyings*4).W))
  private def tie(d: Data,direction: ActualDirection): Unit=d match {
    case r: Record=>r.elements.values.foreach(tie(_,direction))
    case v: Vec[_]=>v.foreach(tie(_,direction))
    case b: Bool if DataMirror.directionOf(b)==direction=>b:=false.B
    case s: SInt if DataMirror.directionOf(s)==direction=>s:=0.S
    case u: UInt if DataMirror.directionOf(u)==direction=>u:=0.U
    case _=>()
  }
  if(preparedGeometry){
    val raster=Module(new Fp32PrimitiveRasterStage(p,g))
    raster.io.in.valid:=vertexDraw.valid;raster.io.in.bits.primitive:=primitive.get;raster.io.in.bits.state:=vertexDraw.bits.raster
    raster.io.in.bits.centroidMask:=centroidMask
    raster.io.in.bits.depthOffset:=0.U.asTypeOf(raster.io.in.bits.depthOffset)
    vertexDraw.ready:=raster.io.in.ready;vertexOut<>raster.io.out;vertexBusy:=raster.io.busy;raster.io.cancel:=vertexCancel
    vertexCompletion.valid:=raster.io.completion.valid;raster.io.completion.ready:=vertexCompletion.ready
    val r=raster.io.completion.bits;val c=vertexCompletion.bits
    c:=0.U.asTypeOf(c);c.identity:=r.identity;c.status:=r.status;c.inputParts:=1.U;c.rasterParts:=1.U;c.culledParts:=r.culled.asUInt
    c.outputQuads:=r.outputQuads;c.coveredLanes:=r.coveredLanes;c.helperLanes:=r.helperLanes;c.coveredSamples:=r.coveredSamples
    c.rasterQuads:=r.rasterQuads;c.discardedQuads:=r.discardedQuads;c.interpolationOperations:=r.arithmeticOperations
    c.arithmeticStatus:=r.arithmeticStatus;c.packingStatus:=r.packingStatus;c.rasterFaultValid:=r.status=/=0.U
    c.interpolationFault:=r.interpolationFault;c.faultSequence:=r.sequence;c.faultPrimitiveId:=r.primitiveId;c.faultInstanceId:=r.instanceId
    c.faultPartIndex:=r.partIndex;c.faultX:=r.faultX;c.faultY:=r.faultY;c.faultLane:=r.faultLane
    tie(router.io.clients(0),ActualDirection.Input)
    vertexRequest.valid:=false.B;vertexRequest.bits:=0.U.asTypeOf(vertexRequest.bits);vertexResponse.ready:=false.B
    payload.foreach{_=>tie(io.payload.get(0),ActualDirection.Output);router.io.clientPayload.get(0):=0.U.asTypeOf(router.io.clientPayload.get(0))}
  }else{
    val v=vertex.get;v.io.draw<>vertexDraw;vertexOut<>v.io.out;vertexCompletion<>v.io.completion
    v.io.centroidMask.get:=centroidMask
    v.io.cancel:=vertexCancel;vertexBusy:=v.io.busy;vertexRequest<>v.io.memoryRequest;v.io.memoryResponse<>vertexResponse
    router.io.clients(0)<>v.io.shared
  }
  private object State extends ChiselEnum {val idle,validate,run,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new VertexFragmentDraw(p,f));private val result=Reg(new VertexFragmentCompletion)
  centroidMask:=descriptor.fragment.centroidMask
  private val vertexOffered=RegInit(false.B);private val vertexStarted=RegInit(false.B);private val vertexDone=RegInit(false.B)
  private val fragmentOffered=RegInit(false.B);private val fragmentStarted=RegInit(false.B);private val fragmentDone=RegInit(false.B)
  private val vertexResult=Reg(new VertexRasterCompletion);private val fragmentResult=Reg(new FragmentShaderCompletion)
  private val preflightStatus=RegInit(0.U(3.W));private val cancelled=RegInit(false.B);private val exported=RegInit(0.U(99.W))
  private val contexts=Reg(Vec(2,UInt(32.W)))
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  private val cancelDraw=externalCancel || cancelled
  private val active=phase=/=State.idle && phase=/=State.complete
  vertexDraw.valid:=vertexOffered;vertexDraw.bits:=descriptor.vertex
  fragment.io.draw.valid:=fragmentOffered;fragment.io.draw.bits:=descriptor.fragment
  when(vertexDraw.fire){vertexOffered:=false.B;vertexStarted:=true.B}
  when(fragment.io.draw.fire){fragmentOffered:=false.B;fragmentStarted:=true.B}
  vertexCancel:=vertexStarted && (cancelDraw || fragment.io.cancelSource)
  fragment.io.cancel:=fragmentStarted && cancelDraw
  fragment.io.in.valid:=vertexOut.valid && fragmentStarted
  fragment.io.in.bits:=vertexOut.bits;vertexOut.ready:=fragmentStarted && fragment.io.in.ready
  fragment.io.inputEnd.valid:=vertexStarted && !vertexDone && vertexCompletion.valid
  fragment.io.inputEnd.bits.identity:=vertexCompletion.bits.identity
  fragment.io.inputEnd.bits.status:=vertexCompletion.bits.status;fragment.io.inputEnd.bits.quads:=vertexCompletion.bits.outputQuads
  vertexCompletion.ready:=fragmentStarted && !vertexDone && fragment.io.inputEnd.ready
  when(vertexCompletion.fire){vertexResult:=vertexCompletion.bits;vertexDone:=true.B}
  fragment.io.completion.ready:=fragmentStarted && !fragmentDone
  when(fragment.io.completion.fire){
    assert(vertexDone,"fragment completion escaped source EOF")
    assert(fragment.io.completion.bits.inputQuads===vertexResult.outputQuads,"fragment input stream lost a quad")
    assert(fragment.io.completion.bits.outputQuads===exported,"fragment export count disagrees with consumer")
    fragmentResult:=fragment.io.completion.bits;fragmentDone:=true.B
  }
  io.out<>fragment.io.out
  when(io.out.fire){exported:=exported+1.U}
  payload.foreach{_=>
    val clients=Seq((1,fragment.io.payload.get))++vertex.toSeq.map(v=>(0,v.io.payload.get))
    for((i,client)<-clients){
      io.payload.get(i)<>client;client.context:=contexts(i)
      client.cancel:=io.payload.get(i).cancel && (if(i==0)vertexStarted else fragmentStarted)
      router.io.clientPayload.get(i):=client.task
    }
    io.poolPayload.get:=router.io.poolPayload.get;io.payloadPins.get:=router.io.payloadPins.get
  }

  private val memoryArbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  private val memoryQueue=Module(new Queue(new ComputeMemoryRequest(config,64,8),1))
  private val sources=Seq(vertexRequest,fragment.io.memoryRequest)
  for((source,i)<-sources.zipWithIndex){
    memoryArbiter.io.in(i).valid:=source.valid;memoryArbiter.io.in(i).bits:=source.bits
    memoryArbiter.io.in(i).bits.transactionId:=Cat(i.U(1.W),source.bits.transactionId);source.ready:=memoryArbiter.io.in(i).ready
  }
  memoryQueue.io.enq<>memoryArbiter.io.out;io.memoryRequest<>memoryQueue.io.deq
  private val responses=Seq(vertexResponse,fragment.io.memoryResponse)
  private val responseOwner=io.memoryResponse.bits.transactionId(2)
  for((target,i)<-responses.zipWithIndex){
    target.valid:=io.memoryResponse.valid && responseOwner===i.U;target.bits:=io.memoryResponse.bits
    target.bits.transactionId:=io.memoryResponse.bits.transactionId(1,0)
  }
  io.memoryResponse.ready:=Mux(responseOwner,fragment.io.memoryResponse.ready,vertexResponse.ready)
  private val inFlight=RegInit(0.U(4.W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned composed graphics memory reply")}
  private val memoryDrained=inFlight===0.U && !memoryQueue.io.deq.valid
  io.draw.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  when(active && externalCancel){cancelled:=true.B}
  private def overlap(a: VertexInputView,b: VertexInputView): Bool=a.base<b.limit && a.limit>b.base
  switch(phase){
    is(State.idle){when(io.draw.fire){
      descriptor:=io.draw.bits;preflightStatus:=0.U;cancelled:=false.B;exported:=0.U
      primitive.foreach(_:=io.primitive.get)
      vertexStarted:=false.B;fragmentStarted:=false.B;vertexDone:=false.B;fragmentDone:=false.B
      vertexResult:=0.U.asTypeOf(vertexResult);fragmentResult:=0.U.asTypeOf(fragmentResult)
      vertexResult.identity:= (if(preparedGeometry)io.primitive.get.clipped.primitive.identity else io.draw.bits.vertex.vertex.geometry.vertex.input.identity)
      fragmentResult.identity:=io.draw.bits.fragment.identity
      for(i<-0 until 2)contexts(i):=io.payload.map(_(i).context).getOrElse(0.U)
      phase:=State.validate
    }}
    is(State.validate){
      val v=descriptor.vertex.vertex.geometry.vertex;val f=descriptor.fragment
      val (invalid,bounds)=FragmentShaderDescriptor.errors(f,this.f,g,config)
      val identity=primitive.map(_.clipped.primitive.identity).getOrElse(v.input.identity)
      val slots=primitive.map(_.clipped.primitive.varyingSlots).getOrElse(v.varyingSlots)
      val matching=identity.asUInt===f.identity.asUInt && slots===f.varyingSlots && descriptor.vertex.raster.sampleMode===f.sampleMode
      val inputAlias=v.input.bindings.zipWithIndex.map{case(b,i)=>
        v.input.attributes.map(a=>a.enabled && a.bindingIndex===i.U).reduce(_ || _) && overlap(f.scratch,b)
      }.reduce(_ || _) || (v.input.indexFormat=/=0.U && overlap(f.scratch,v.input.indices))
      val aliases=if(preparedGeometry)false.B else overlap(v.scratch,f.scratch) || inputAlias
      when(externalCancel){phase:=State.finish}
        .elsewhen(descriptor.version=/=1.U || invalid || !matching || aliases || (!preparedGeometry.B && contexts(0)=/=contexts(1))){
          preflightStatus:=1.U;phase:=State.finish
        }.elsewhen(bounds){preflightStatus:=2.U;phase:=State.finish}
        .otherwise{vertexOffered:=true.B;fragmentOffered:=true.B;phase:=State.run}
    }
    is(State.run){when(vertexDone && fragmentDone && !vertexBusy && !fragment.io.busy && router.io.drained && memoryDrained){phase:=State.finish}}
    is(State.finish){
      result:=0.U.asTypeOf(result);result.identity:=Mux(preparedGeometry.B,descriptor.fragment.identity,descriptor.vertex.vertex.geometry.vertex.input.identity)
      result.preparedGeometry:=preparedGeometry.B
      result.vertex:=vertexResult;result.fragment:=fragmentResult;result.outputQuads:=exported
      val status=Mux(preflightStatus=/=0.U,preflightStatus,Mux(fragmentResult.status=/=0.U,fragmentResult.status,Mux(cancelled || externalCancel,4.U,0.U)))
      result.status:=status
      result.faultStage:=Mux(preflightStatus=/=0.U,1.U,Mux(fragmentResult.faultSource===7.U,2.U,Mux(fragmentResult.faultSource=/=0.U,3.U,0.U)))
      result.faultAddress:=Mux(preflightStatus=/=0.U,descriptor.fragment.scratch.base,
        Mux(fragmentResult.faultSource===7.U,vertexResult.source.source.source.source.faultAddress,fragmentResult.faultAddress))
      phase:=State.complete
    }
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!vertexBusy && !fragment.io.busy && router.io.drained && memoryDrained,"draw completion escaped shader/memory drain")}
}
