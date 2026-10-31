package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.system.{SharedCuGraphicsPort,SharedCuShaderConfig}

class TypedTileImageCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 0 cancellation/end,1 renderer,2 physical attachment transfer/admission. */
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W)
  val renderer=new TypedTileRenderCompletion;val transfers=new TypedTileImageTransferCompletion
}
class TypedTileImageTransferFault extends Bundle {val status=UInt(3.W);val address=UInt(32.W)}
/** Typed shared-CU tile draws with physical backing views. Stores precede local
  * end; pass completion additionally owns every physical memory acknowledgement.
  * The external port must preserve the existing coherent L2 store contract.
  * No CPU cache coherence, MMU/resource-table or native host ABI is implied.
  */
class TypedTileImagePipeline(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    f: FragmentShaderConfig=FragmentShaderConfig(),shared: SharedCuShaderConfig=SharedCuShaderConfig(4),
    g: GraphicsConfig=GraphicsConfig(),viewport: Fp32ViewportConfig=Fp32ViewportConfig(),
    storage: TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes: Boolean=false,
    payload: Option[ShaderPayloadConfig]=None,payloadOwner: Int=0,preparedGeometry: Boolean=false,groupedTexture: Boolean=false,transformFeedback: Boolean=false,
    geometryInput: Boolean=false) extends Module {
  require(!geometryInput || preparedGeometry)
  private val sharedConfig=shared;private val payloadConfig=payload
  val io=IO(new Bundle {
    val feedback=if(transformFeedback)Some(new TransformFeedbackPipelineIO(config))else None
    val grouped=if(groupedTexture)Some(new NativeRgba8TextureRendererPorts(sharedConfig.numComputeUnits,if(preparedGeometry)1 else 2))else None
    val begin=Flipped(Decoupled(new TypedTileImageDescriptor));val end=Flipped(Decoupled(Bool()))
    val draw=Flipped(Decoupled(new TypedTileRenderDraw(p,f)));val drawCompletion=Decoupled(new TypedTileRenderDrawCompletion)
    val primitive=if(preparedGeometry)Some(Input(new WindowPrimitive(p)))else None
    val geometry=if(geometryInput)Some(Input(Valid(UInt(32.W))))else None
    val out=Decoupled(new TypedTilePixelQuadCompletion);val completion=Decoupled(new TypedTileImageCompletion)
    /** Latched primary transfer failure, before cancellation of an active draw
      * can retire that draw with status 4. Retained through complete drain.
      */
    val transferFailure=Output(Valid(new TypedTileImageTransferFault))
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>Vec(2,new FragmentPayloadPort(config,q)))
    val poolPayload=payloadConfig.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payloadConfig.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val fragmentEvents=Output(new FragmentPerformanceEvents)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val renderer=Module(new TypedTileRenderPipeline(config,p,f,shared,g,viewport,storage,useSramBlackBoxes,payload,payloadOwner,preparedGeometry,groupedTexture=groupedTexture,transformFeedback=transformFeedback,geometryInput=geometryInput))
  if(transformFeedback){
    val scope=Reg(new TransformFeedbackScope)
    when(io.draw.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,renderer.io.feedback.get,Mux(io.draw.fire,io.feedback.get.scope,scope), !io.busy)
  }
  if(groupedTexture)io.grouped.get <> renderer.io.grouped.get
  if(preparedGeometry)renderer.io.primitive.get:=io.primitive.get
  if(geometryInput)renderer.io.geometry.get:=io.geometry.get
  private val transfers=Module(new TypedTileImageTransfer(config,storage))
  private object State extends ChiselEnum {val idle,bindTransfer,bindRender,run,flush,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTileImageDescriptor);private val result=Reg(new TypedTileImageCompletion)
  private val transferOffered=RegInit(false.B);private val transferStarted=RegInit(false.B);private val transferDone=RegInit(false.B)
  private val renderOffered=RegInit(false.B);private val renderStarted=RegInit(false.B);private val renderDone=RegInit(false.B)
  private val flushOffered=RegInit(false.B);private val flushStarted=RegInit(false.B);private val flushSuccess=Reg(Bool());private val endOffered=RegInit(false.B)
  private val transferResult=Reg(new TypedTileImageTransferCompletion);private val renderResult=Reg(new TypedTileRenderCompletion)
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W));private val faultAddress=RegInit(0.U(32.W))
  private val contexts=Reg(Vec(2,UInt(32.W)))
  private val externalCancel=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  private val renderFault=(renderer.io.drawCompletion.valid && renderer.io.drawCompletion.bits.status=/=0.U) ||
    (renderer.io.out.valid && renderer.io.out.bits.status=/=0.U) || (renderer.io.completion.valid && renderer.io.completion.bits.status=/=0.U)
  private val transferFault=transferStarted && transfers.io.status=/=0.U
  private val stop=status=/=0.U || externalCancel || renderFault || transferFault
  private val active=phase=/=State.idle && phase=/=State.complete
  io.transferFailure.valid:=active && status=/=0.U && status=/=4.U && faultStage===2.U
  io.transferFailure.bits.status:=status;io.transferFailure.bits.address:=faultAddress
  private def fail(code: UInt,stage: UInt,address: UInt=0.U): Unit=when(status===0.U && !externalCancel){status:=code;faultStage:=stage;faultAddress:=address}
  io.shared<>renderer.io.shared
  io.fragmentEvents:=renderer.io.fragmentEvents
  payload.foreach{_=>for(i<-0 until 2){io.payload.get(i)<>renderer.io.payload.get(i);renderer.io.payload.get(i).context:=contexts(i)
    renderer.io.payload.get(i).cancel:=io.payload.get(i).cancel && renderStarted && !endOffered && !transfers.io.memory.valid}
    io.poolPayload.get:=renderer.io.poolPayload.get;io.payloadPins.get:=renderer.io.payloadPins.get}
  transfers.io.begin.valid:=transferOffered;transfers.io.begin.bits:=descriptor
  when(transfers.io.begin.fire){transferOffered:=false.B;transferStarted:=true.B}
  renderer.io.begin.valid:=renderOffered;renderer.io.begin.bits:=descriptor.pixels
  when(renderer.io.begin.fire){renderOffered:=false.B;renderStarted:=true.B}
  transfers.io.cancel:=transferStarted && (status=/=0.U || externalCancel || renderFault) && !transferOffered
  // Private maintenance offers remain irrevocable even if cancellation arrives.
  renderer.io.cancel:=renderStarted && (status=/=0.U || externalCancel || transferFault) && !transfers.io.memory.valid && !endOffered
  renderer.io.maintenance:=transfers.io.memory.valid;renderer.io.memory<>transfers.io.memory;transfers.io.reply<>renderer.io.reply
  transfers.io.refill<>renderer.io.refill;renderer.io.refillReply<>transfers.io.refillReply
  io.out<>renderer.io.out;io.drawCompletion<>renderer.io.drawCompletion
  renderer.io.draw.valid:=phase===State.run && !stop && io.draw.valid && renderer.io.draw.ready;renderer.io.draw.bits:=io.draw.bits
  io.draw.ready:=phase===State.run && !stop && renderer.io.draw.ready
  io.end.ready:=phase===State.run && !stop && !io.draw.valid && renderer.io.draw.ready
  renderer.io.end.valid:=endOffered;renderer.io.end.bits:=true.B
  when(renderer.io.end.fire){endOffered:=false.B}
  transfers.io.flush.valid:=flushOffered;transfers.io.flush.bits:=flushSuccess
  when(transfers.io.flush.fire){flushOffered:=false.B;flushStarted:=true.B}
  renderer.io.completion.ready:=renderStarted && !renderDone
  when(renderer.io.completion.fire){renderDone:=true.B;renderResult:=renderer.io.completion.bits
    when(renderer.io.completion.bits.status=/=0.U){fail(renderer.io.completion.bits.status,1.U,renderer.io.completion.bits.faultAddress)}}
  transfers.io.completion.ready:=transferStarted && !transferDone
  when(transfers.io.completion.fire){transferDone:=true.B;transferResult:=transfers.io.completion.bits
    when(transfers.io.completion.bits.status=/=0.U){fail(transfers.io.completion.bits.status,2.U,transfers.io.completion.bits.faultAddress)}}
  when(active && transferFault){fail(transfers.io.status,2.U,transfers.io.faultAddress)}
  when(active && renderFault && !transferFault){
    val code=Mux(renderer.io.out.valid && renderer.io.out.bits.status=/=0.U,renderer.io.out.bits.status,
      Mux(renderer.io.drawCompletion.valid && renderer.io.drawCompletion.bits.status=/=0.U,renderer.io.drawCompletion.bits.status,renderer.io.completion.bits.status))
    fail(code,1.U)
  }
  when(active && externalCancel && status===0.U){status:=4.U;faultStage:=0.U}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,16),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,16),1))
  private val sources=Seq(renderer.io.memoryRequest,transfers.io.memoryRequest)
  for((source,i)<-sources.zipWithIndex){arbiter.io.in(i).valid:=source.valid;arbiter.io.in(i).bits:=source.bits
    arbiter.io.in(i).bits.transactionId:=Cat(i.U(1.W),source.bits.transactionId.pad(3));source.ready:=arbiter.io.in(i).ready}
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val owner=io.memoryResponse.bits.transactionId(3)
  renderer.io.memoryResponse.valid:=io.memoryResponse.valid && !owner;renderer.io.memoryResponse.bits:=io.memoryResponse.bits
  renderer.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId(2,0)
  transfers.io.memoryResponse.valid:=io.memoryResponse.valid && owner;transfers.io.memoryResponse.bits:=io.memoryResponse.bits
  transfers.io.memoryResponse.bits.transactionId:=0.U
  io.memoryResponse.ready:=Mux(owner,transfers.io.memoryResponse.ready,renderer.io.memoryResponse.ready)
  private val inFlight=RegInit(0.U(5.W));inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned image pipeline memory response")}
  private val memoryDrained=inFlight===0.U && !queue.io.deq.valid
  io.begin.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle || io.feedback.map(_.busy).getOrElse(false.B)
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;transferOffered:=true.B;transferStarted:=false.B;transferDone:=false.B
      renderStarted:=false.B;renderDone:=false.B;flushStarted:=false.B;status:=0.U;faultStage:=0.U;faultAddress:=0.U
      transferResult:=0.U.asTypeOf(transferResult);renderResult:=0.U.asTypeOf(renderResult)
      for(i<-0 until 2)contexts(i):=io.payload.map(_(i).context).getOrElse(0.U)
      phase:=State.bindTransfer}}
    is(State.bindTransfer){when(stop || transferDone){phase:=State.closing}.elsewhen(transferStarted && transfers.io.bound){renderOffered:=true.B;phase:=State.bindRender}}
    is(State.bindRender){when(stop){phase:=State.closing}.elsewhen(renderStarted && renderer.io.draw.ready){phase:=State.run}}
    is(State.run){when(stop){phase:=State.closing}.elsewhen(io.end.fire){
      when(io.end.bits){flushSuccess:=true.B;flushOffered:=true.B;phase:=State.flush}
        .otherwise{fail(5.U,0.U);phase:=State.closing}}}
    is(State.flush){when(transferDone){when(!stop && transferResult.status===0.U){endOffered:=true.B};phase:=State.closing}}
    is(State.closing){
      when(!flushStarted && !flushOffered && !transferDone && transfers.io.bound && (!renderStarted || (renderDone && !renderer.io.busy))){
        flushSuccess:=false.B;flushOffered:=true.B}
      when(!transferOffered && !renderOffered && !flushOffered && !endOffered && (!renderStarted || (renderDone && !renderer.io.busy)) &&
        (!transferStarted || (transferDone && !transfers.io.busy)) && memoryDrained){phase:=State.finish}
    }
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.pixels.colors.identity;result.status:=Mux(externalCancel && status===0.U,4.U,status)
      result.faultStage:=faultStage;result.faultAddress:=Mux(faultStage===1.U,renderResult.faultAddress,faultAddress)
      result.renderer:=renderResult;result.transfers:=transferResult;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!renderer.io.busy && !transfers.io.busy && memoryDrained,"image pass completion escaped publication drain")}
}
