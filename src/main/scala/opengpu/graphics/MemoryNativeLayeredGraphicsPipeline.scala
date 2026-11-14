package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.system.{SharedCuShaderConfig,NativeLayeredVertexBinImagePipeline,NativeLayeredVertexBinImageCompletion}

/** Real GNJ2/GNF3/GND1+2 entry selected by the host's explicit nativeGeometry
  * configuration. A complete all-reference preflight precedes the first VS.
  * The same actual mixed source/bin/image pass produces every layer, followed
  * by an acknowledged GNY2 publication before the held coarse result retires.
  */
class MemoryNativeLayeredGraphicsPipeline(config:GpuConfig,native:NativeBinnedGraphicsConfig,
    shared:SharedCuShaderConfig,g:GraphicsConfig,commandIdWidth:Int,viewport:Fp32ViewportConfig,
    useSramBlackBoxes:Boolean,fragmentImageAvailable:Boolean) extends Module {
  require(native.nativeGeometry && native.translateImageViews && native.pagedCapacity==65536)
  private val p=native.vertex;private val f=native.fragment;private val engines=native.engines
  private val renderGraphics=native.renderGraphics(g)
  val memoryTransactions=MemoryNativeBinnedGraphicsPipeline.transactions(native)
  private val renderBase=MemoryNativeBinnedGraphicsPipeline.renderBase
  private val renderSlots=MemoryNativeBinnedGraphicsPipeline.renderSlots(native)
  val io=IO(new NativeBinnedGraphicsPipelineIO(config,native,shared,commandIdWidth,fragmentImageAvailable))
  private val reader=Module(new NativeBinnedGraphicsJobReader(config,commandIdWidth,p,engines,
    virtualImageViews=true,allowWideTileCount=true,preflightDescriptors=true,f=f,g=renderGraphics,
    nativeAdvanced=native.storage.nativeAdvancedDraws,imageAvailable=fragmentImageAvailable,nativeGeometry=true,nativeLayered=true,
    preserveChildFaultAfterCancel=native.transformFeedback))
  private val renderer=Module(new NativeLayeredVertexBinImagePipeline(config,p,f,shared,renderGraphics,viewport,
    engines,native.pageBytes,native.writeCredits,native.tileSlots,native.pagedCapacity,native.storage,useSramBlackBoxes,
    native.payload,native.groupedTexture,fragmentImageAvailable,transformFeedback=native.transformFeedback))
  if(native.transformFeedback){
    val scope=RegInit(0.U.asTypeOf(new TransformFeedbackScope))
    when(io.submit.fire){scope:=io.feedback.get.scope}
    TransformFeedbackPipelineIO.forward(io.feedback.get,renderer.io.feedback.get,
      Mux(io.submit.fire,io.feedback.get.scope,scope), !io.busy)
  }
  private val writer=Module(new NativeLayeredCompletionWriter(config,p,commandIdWidth))
  private val fullResult=Reg(new NativeLayeredVertexBinImageCompletion(p))
  private val command=Reg(UInt(commandIdWidth.W));private val drawIndex=RegInit(0.U(32.W))
  private val active=RegInit(false.B)
  private val admit= !active && !io.feedback.map(f=>f.busy || f.command.valid).getOrElse(false.B)
  reader.io.submit.valid:=io.submit.valid && admit;reader.io.submit.bits:=io.submit.bits
  io.submit.ready:=reader.io.submit.ready && admit
  when(io.submit.fire){active:=true.B;command:=io.submit.bits.commandId;drawIndex:=0.U;fullResult:=0.U.asTypeOf(fullResult)}
  renderer.io.begin.valid:=reader.io.begin.valid;renderer.io.begin.bits:=reader.io.layeredBegin.get
  reader.io.begin.ready:=renderer.io.begin.ready
  renderer.io.draw.valid:=reader.io.draw.valid;renderer.io.draw.bits.draw:=reader.io.draw.bits
  renderer.io.draw.bits.geometry:=reader.io.geometry.get;renderer.io.draw.bits.commandId:=command;renderer.io.draw.bits.taskId:=drawIndex
  reader.io.draw.ready:=renderer.io.draw.ready;when(reader.io.draw.fire){drawIndex:=drawIndex+1.U}
  renderer.io.end<>reader.io.end
  // Only identity/status/address are consumed by the reader's coarse command
  // state machine. No expanded count is narrowed into its ordinary structures;
  // the complete real result is independently captured and encoded in GNY2.
  reader.io.drawCompletion.valid:=renderer.io.drawCompletion.valid
  reader.io.drawCompletion.bits:=0.U.asTypeOf(reader.io.drawCompletion.bits)
  reader.io.drawCompletion.bits.identity:=renderer.io.drawCompletion.bits.identity
  reader.io.drawCompletion.bits.ordinal:=renderer.io.drawCompletion.bits.ordinal
  reader.io.drawCompletion.bits.status:=renderer.io.drawCompletion.bits.status
  reader.io.drawCompletion.bits.faultStage:=renderer.io.drawCompletion.bits.faultStage
  reader.io.drawCompletion.bits.source.source.source.source.faultAddress:=renderer.io.drawCompletion.bits.source.source.source.source.faultAddress
  renderer.io.drawCompletion.ready:=reader.io.drawCompletion.ready
  reader.io.passCompletion.valid:=renderer.io.completion.valid
  reader.io.passCompletion.bits:=0.U.asTypeOf(reader.io.passCompletion.bits)
  reader.io.passCompletion.bits.identity:=renderer.io.completion.bits.identity
  reader.io.passCompletion.bits.status:=renderer.io.completion.bits.status
  renderer.io.completion.ready:=reader.io.passCompletion.ready
  when(renderer.io.completion.fire){fullResult:=renderer.io.completion.bits}
  renderer.io.cancel:=reader.io.cancelCore
  reader.io.cancel:=io.cancel || io.payload.map(v=>VecInit(v.map(_.cancel)).asUInt.orR).getOrElse(false.B)
  writer.io.in<>reader.io.completion;writer.io.frame:=fullResult
  writer.io.view:=reader.io.completionView.get;writer.io.viewValid:=reader.io.completionViewValid.get
  io.completion<>writer.io.out;when(io.completion.fire){active:=false.B}
  io.shared<>renderer.io.shared;io.fragmentEvents:=renderer.io.fragmentEvents
  if(native.groupedTexture)io.grouped.get<>renderer.io.grouped.get
  native.payload.foreach{_=>io.payload.get<>renderer.io.payload.get;io.poolPayload.get:=renderer.io.poolPayload.get
    io.payloadPins.get:=renderer.io.payloadPins.get}
  for(e<-0 until engines){
    io.primitiveDone(e)<>renderer.io.primitiveDone(e)
    io.out(e).valid:=renderer.io.out(e).valid;io.out(e).bits:=renderer.io.out(e).bits.pixels
    renderer.io.out(e).ready:=io.out(e).ready;renderer.io.layerDone(e).ready:=true.B
    val selected=renderer.io.tileDone.bits.engine===e.U;val r=renderer.io.tileDone.bits.image
    io.tileDone(e).valid:=renderer.io.tileDone.valid && selected
    io.tileDone(e).bits:=0.U.asTypeOf(io.tileDone(e).bits)
    io.tileDone(e).bits.engine:=e.U;io.tileDone(e).bits.status:=r.status
    io.tileDone(e).bits.renderStarted:=renderer.io.tileDone.bits.renderStarted
    io.tileDone(e).bits.source.bins.identity:=r.identity;io.tileDone(e).bits.source.bins.tile:=r.tile
    io.tileDone(e).bits.source.bins.engine:=e.U;io.tileDone(e).bits.source.bins.status:=r.replay.chain.status
    io.tileDone(e).bits.source.bins.prefixUsable:=r.replay.chain.prefixUsable
    io.tileDone(e).bits.source.bins.restartValid:=r.replay.chain.restartValid;io.tileDone(e).bits.source.bins.restart:=r.replay.chain.restart
    io.tileDone(e).bits.image.identity:=r.identity;io.tileDone(e).bits.image.tile:=r.tile;io.tileDone(e).bits.image.status:=r.status
    io.tileDone(e).bits.image.accepted:=r.accepted;io.tileDone(e).bits.image.issued:=r.issued;io.tileDone(e).bits.image.completed:=r.completed
    io.tileDone(e).bits.image.image.transfers.loadBytes:=r.loadBytes
    io.tileDone(e).bits.image.image.transfers.storedBytes:=r.storedBytes
    io.tileDone(e).bits.image.image.transfers.resolvedBytes:=r.resolvedBytes
  }
  renderer.io.tileDone.ready:=VecInit((0 until engines).map(e=>renderer.io.tileDone.bits.engine===e.U && io.tileDone(e).ready)).asUInt.orR
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,memoryTransactions),3))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,memoryTransactions),2,pipe=true))
  private val tag=io.memoryResponse.bits.transactionId
  private val sources=Seq(reader.io.memoryRequest,writer.io.memoryRequest,renderer.io.memoryRequest)
  private val replies=Seq(reader.io.memoryResponse,writer.io.memoryResponse,renderer.io.memoryResponse)
  private val bases=Seq(0,8,renderBase);private val counts=Seq(reader.memoryTransactions,1,renderSlots)
  private val selected=bases.zip(counts).map{case(b,n)=>tag>=b.U && tag<(b+n).U}
  for(i<-0 until 3){val port=arbiter.io.in(i);port.valid:=sources(i).valid;port.bits:=sources(i).bits
    port.bits.transactionId:=sources(i).bits.transactionId+&bases(i).U;sources(i).ready:=port.ready
    replies(i).valid:=io.memoryResponse.valid && selected(i);replies(i).bits:=io.memoryResponse.bits
    replies(i).bits.transactionId:=tag-bases(i).U}
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  io.memoryResponse.ready:=selected.zip(replies).map{case(sel,r)=>sel && r.ready}.reduce(_ || _)
  when(io.memoryResponse.valid){assert(selected.reduce(_ || _),"GNJ2 reply escaped descriptor/writer/renderer namespaces")}
  private val inFlight=RegInit(0.U(log2Ceil(memoryTransactions+3).W))
  inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned GNJ2 reply")}
  io.busy:=active || reader.io.busy || writer.io.busy || renderer.io.busy || inFlight.orR || queue.io.count.orR
  when(io.completion.valid){assert(!renderer.io.busy && !reader.io.busy && inFlight===0.U && queue.io.count===0.U,
    "GNJ2 coarse completion preceded GNY2 store acknowledgements or owner drain")}
}
