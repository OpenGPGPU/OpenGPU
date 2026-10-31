package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{CacheLineInvalidate,ComputeMemoryRequest,ComputeMemoryResponse,ShaderPayloadBinding}
import opengpu.graphics._

class IsolineTypedImageTask(config:GpuConfig,p:FragmentShaderConfig,idWidth:Int,payload:Option[ShaderPayloadConfig]) extends Bundle {
  val image=new TypedTileImageDescriptor
  val tile=new IsolineTypedTileTask(config,p,idWidth,payload)
}
class IsolineTypedImageCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 1 admission, 2 tile, 3 image transfer, 0 cancellation. */
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W)
  val tile=new IsolineTypedTileCompletion;val transfer=new TypedTileImageTransferCompletion
}

/** One isoline patch, one resident tile, and its physical attachment views.
  * The enclosing owner keeps shader resources stable and disjoint from image
  * allocations. Stores are acknowledged before completion; failed or cancelled
  * transfers can have already written a subset of the backing image.
  */
class IsolineTypedImagePipeline(config:GpuConfig,p:FragmentShaderConfig,shared:SharedCuShaderConfig,
    g:GraphicsConfig=GraphicsConfig(),viewport:Fp32ViewportConfig=Fp32ViewportConfig(),
    storage:TypedTileColorConfig=TypedTileColorConfig(),useSramBlackBoxes:Boolean=false,
    payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0,interpolationContexts:Int=8) extends Module {
  private val idWidth=shared.taskIdWidth;private val cus=shared.numComputeUnits
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new IsolineTypedImageTask(config,p,idWidth,payload)))
    val out=Decoupled(new TypedTilePixelQuadCompletion);val completion=Decoupled(new IsolineTypedImageCompletion)
    val shared=Flipped(new SharedCuGraphicsPort(config,idWidth,cus,geometryAvailable=true))
    val geometryTexture=Vec(cus,new SharedCuTexturePort(config));val geometryTrap=Decoupled(new TaggedGraphicsTrap(config,idWidth,cus))
    val geometryInvalidate=Flipped(Decoupled(new CacheLineInvalidate(config)));val geometryInvalidateDone=Decoupled(new CacheLineInvalidate(config))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,8));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,8)))
    val payloadPort=payload.map(q=>new FragmentPayloadPort(config,q));val poolPayload=payload.map(q=>Output(Valid(new ShaderPayloadBinding(config,q))))
    val payloadPins=payload.map(q=>Output(Vec(4,Valid(new ShaderPayloadPin(q)))))
    val performance=Output(new FragmentPerformanceEvents);val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val tile=Module(new IsolineTypedTilePipeline(config,p,shared,g,viewport,storage,useSramBlackBoxes,payload,payloadOwner,interpolationContexts))
  private val transfer=Module(new TypedTileImageTransfer(config,storage))
  private val admission=Module(new IsolineTypedTileAdmission(config,p,idWidth,g,storage,payload))
  admission.io.task:=io.task.bits.tile
  private val hazard=Module(new IsolineImageScratchHazard)
  hazard.io.image:=io.task.bits.image;hazard.io.scratch:=io.task.bits.tile.draw.fragment.scratch
  private val legal=admission.io.legal && !hazard.io.hazard && io.task.bits.image.pixels.asUInt===io.task.bits.tile.pixels.asUInt
  private object State extends ChiselEnum {val idle,bindTransfer,bindTile,draw,flush,close,finish,complete=Value}
  private val phase=RegInit(State.idle);private val saved=Reg(new IsolineTypedImageTask(config,p,idWidth,payload))
  private val result=Reg(new IsolineTypedImageCompletion);private val tileResult=Reg(new IsolineTypedTileCompletion)
  private val transferResult=Reg(new TypedTileImageTransferCompletion)
  private val transferOffered=RegInit(false.B);private val transferStarted=RegInit(false.B);private val transferDone=RegInit(false.B)
  private val tileOffered=RegInit(false.B);private val tileStarted=RegInit(false.B);private val tileDone=RegInit(false.B)
  private val flushOffered=RegInit(false.B);private val flushStarted=RegInit(false.B);private val endOffered=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(2.W));private val faultAddress=RegInit(0.U(32.W))
  private val externalCancel=io.cancel || io.payloadPort.map(_.cancel).getOrElse(false.B)
  private val transferFault=transferStarted && transfer.io.status=/=0.U
  private val tileFault=tile.io.completion.valid && tile.io.completion.bits.status=/=0.U
  private val stop=status=/=0.U || externalCancel || transferFault || tileFault
  private val active=phase=/=State.idle && phase=/=State.complete
  private def fail(code:UInt,stage:UInt,address:UInt=0.U):Unit=
    when(status===0.U && !externalCancel){status:=code;faultStage:=stage;faultAddress:=address}
  io.task.ready:=phase===State.idle && !externalCancel;io.busy:=phase=/=State.idle
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  transfer.io.begin.valid:=transferOffered;transfer.io.begin.bits:=saved.image
  when(transfer.io.begin.fire){transferOffered:=false.B;transferStarted:=true.B}
  tile.io.task.valid:=tileOffered;tile.io.task.bits:=saved.tile
  when(tile.io.task.fire){tileOffered:=false.B;tileStarted:=true.B}
  transfer.io.cancel:=transferStarted && (status=/=0.U || externalCancel || tileFault) && !transferOffered
  // An already offered maintenance request must remain serviceable.
  tile.io.cancel:=tileStarted && (status=/=0.U || externalCancel || transferFault) && !transfer.io.memory.valid && !endOffered
  tile.io.memory<>transfer.io.memory;transfer.io.reply<>tile.io.reply
  transfer.io.refill<>tile.io.refill;tile.io.refillReply<>transfer.io.refillReply
  transfer.io.flush.valid:=flushOffered;transfer.io.flush.bits:=phase===State.flush
  when(transfer.io.flush.fire){flushOffered:=false.B;flushStarted:=true.B}
  tile.io.end.valid:=endOffered;tile.io.end.bits:=true.B
  when(tile.io.end.fire){endOffered:=false.B}
  transfer.io.completion.ready:=transferStarted && !transferDone
  when(transfer.io.completion.fire){transferDone:=true.B;transferResult:=transfer.io.completion.bits
    when(transfer.io.completion.bits.status=/=0.U){fail(transfer.io.completion.bits.status,3.U,transfer.io.completion.bits.faultAddress)}}
  tile.io.completion.ready:=tileStarted && !tileDone
  when(tile.io.completion.fire){tileDone:=true.B;tileResult:=tile.io.completion.bits
    when(tile.io.completion.bits.status=/=0.U && !transferFault &&
      !(transfer.io.completion.valid && transfer.io.completion.bits.status=/=0.U)){fail(tile.io.completion.bits.status,2.U)}}
  when(active && transferFault){fail(transfer.io.status,3.U,transfer.io.faultAddress)}
  when(active && tileFault && !transferFault){fail(tile.io.completion.bits.status,2.U)}
  when(active && externalCancel && status===0.U){status:=4.U;faultStage:=0.U}
  io.out<>tile.io.out;io.shared<>tile.io.shared;io.geometryTexture<>tile.io.geometryTexture;io.geometryTrap<>tile.io.geometryTrap
  tile.io.geometryInvalidate<>io.geometryInvalidate;io.geometryInvalidateDone<>tile.io.geometryInvalidateDone
  io.performance:=tile.io.performance
  payload.foreach{_=>io.payloadPort.get<>tile.io.payloadPort.get
    tile.io.payloadPort.get.cancel:=io.payloadPort.get.cancel && tileStarted && !transfer.io.memory.valid && !endOffered
    io.poolPayload.get:=tile.io.poolPayload.get;io.payloadPins.get:=tile.io.payloadPins.get}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,8),2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,8),1))
  private val sources=Seq(tile.io.memoryRequest,transfer.io.memoryRequest)
  for((source,i)<-sources.zipWithIndex){arbiter.io.in(i).valid:=source.valid;arbiter.io.in(i).bits:=source.bits
    arbiter.io.in(i).bits.transactionId:=Cat(i.U(1.W),source.bits.transactionId.pad(2));source.ready:=arbiter.io.in(i).ready}
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val owner=io.memoryResponse.bits.transactionId(2)
  tile.io.memoryResponse.valid:=io.memoryResponse.valid && !owner;tile.io.memoryResponse.bits:=io.memoryResponse.bits
  tile.io.memoryResponse.bits.transactionId:=io.memoryResponse.bits.transactionId(1,0)
  transfer.io.memoryResponse.valid:=io.memoryResponse.valid && owner;transfer.io.memoryResponse.bits:=io.memoryResponse.bits
  transfer.io.memoryResponse.bits.transactionId:=0.U
  io.memoryResponse.ready:=Mux(owner,transfer.io.memoryResponse.ready,tile.io.memoryResponse.ready)
  private val inFlight=RegInit(0.U(4.W));inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned isoline image memory response")}
  private val memoryDrained=inFlight===0.U && !queue.io.deq.valid
  switch(phase){
    is(State.idle){when(io.task.fire){saved:=io.task.bits;status:=0.U;faultStage:=0.U;faultAddress:=0.U
      tileStarted:=false.B;tileDone:=false.B;transferStarted:=false.B;transferDone:=false.B;flushStarted:=false.B
      tileResult:=0.U.asTypeOf(tileResult);transferResult:=0.U.asTypeOf(transferResult)
      when(legal){transferOffered:=true.B;phase:=State.bindTransfer}.otherwise{
        result:=0.U.asTypeOf(result);result.identity:=io.task.bits.image.pixels.colors.identity;result.status:=1.U;result.faultStage:=1.U;phase:=State.complete}}}
    is(State.bindTransfer){when(stop || transferDone){phase:=State.close}.elsewhen(transferStarted && transfer.io.bound){tileOffered:=true.B;phase:=State.bindTile}}
    is(State.bindTile){when(stop){phase:=State.close}.elsewhen(tileStarted){phase:=State.draw}}
    is(State.draw){when(stop){phase:=State.close}.elsewhen(tile.io.readyForStore){flushOffered:=true.B;phase:=State.flush}}
    is(State.flush){when(transferDone){when(!stop && transferResult.status===0.U){endOffered:=true.B};phase:=State.close}}
    is(State.close){
      when(!flushStarted && !flushOffered && !transferDone && transfer.io.bound && (!tileStarted || (tileDone && !tile.io.busy))){flushOffered:=true.B}
      when(!transferOffered && !tileOffered && !flushOffered && !endOffered && (!tileStarted || (tileDone && !tile.io.busy)) &&
        (!transferStarted || (transferDone && !transfer.io.busy)) && memoryDrained){phase:=State.finish}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=saved.image.pixels.colors.identity
      result.status:=Mux(externalCancel && status===0.U,4.U,status);result.faultStage:=faultStage;result.faultAddress:=faultAddress
      result.tile:=tileResult;result.transfer:=transferResult;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!tile.io.busy && !transfer.io.busy && memoryDrained,"isoline image completion escaped drain")}
}
