package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}
import opengpu.graphics.{MemoryNativeBinnedGraphicsPipeline,NativeBinnedGraphicsConfig}

object NativeBinnedGraphicsMemoryFabric {
  def transactions(native: NativeBinnedGraphicsConfig): Int=1<<log2Ceil(MemoryNativeBinnedGraphicsPipeline.transactions(native)+6)
}

/** Native binned descriptor/geometry/bin/state/staging requests use Sv32.
  * Per-engine trusted physical image transfers bypass translation. Legacy DMA
  * and both page walkers have separate IDs above the full native namespace.
  * Buffered replies retain immutable offers and VM ownership through stalls.
  */
class NativeBinnedGraphicsMemoryFabric(config: GpuConfig=GpuConfig(),native: NativeBinnedGraphicsConfig=NativeBinnedGraphicsConfig(),kernelWordIds: Int=16,dmaIdBase: Int=8) extends Module {
  require(dmaIdBase>=0 && dmaIdBase+4<=kernelWordIds)
  private val nativeIds=MemoryNativeBinnedGraphicsPipeline.transactions(native)
  private val transactions=NativeBinnedGraphicsMemoryFabric.transactions(native)
  private val idBits=log2Ceil(transactions)
  val io=IO(new Bundle {
    val renderRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,nativeIds)))
    val renderResponse=Decoupled(new ComputeMemoryResponse(64,nativeIds))
    val dmaRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,kernelWordIds)))
    val dmaResponse=Decoupled(new ComputeMemoryResponse(64,kernelWordIds))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)));val walkActive=Output(Vec(4,UInt(3.W)))
    val drained=Output(Bool())
  })
  private val render=Module(new TranslatedLineClient(config,nativeIds,preserveCachePolicy=true))
  private val dma=Module(new TranslatedLineClient(config,4,preserveCachePolicy=true))
  for(c<-Seq(render,dma)){c.io.satp:=io.satp;c.io.flush:=io.flush}
  private val physical=MemoryNativeBinnedGraphicsPipeline.physical(io.renderRequest.bits.transactionId,native)
  render.io.in.valid:=io.renderRequest.valid && !physical;render.io.in.bits:=io.renderRequest.bits
  dma.io.in.valid:=io.dmaRequest.valid;dma.io.in.bits:=io.dmaRequest.bits
  dma.io.in.bits.transactionId:=io.dmaRequest.bits.transactionId-dmaIdBase.U
  io.dmaRequest.ready:=dma.io.in.ready
  when(io.dmaRequest.valid){assert(io.dmaRequest.bits.transactionId>=dmaIdBase.U && io.dmaRequest.bits.transactionId<(dmaIdBase+4).U)}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,transactions),5))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,transactions),2,pipe=true))
  private val sources=Seq(render.io.memReq,dma.io.memReq,render.io.pageWalk,dma.io.pageWalk)
  private val slots=Seq(0,2,3,4)
  for((source,i)<-sources.zipWithIndex){val out=arbiter.io.in(slots(i));out.valid:=source.valid;out.bits:=source.bits
    out.bits.transactionId:=(if(i==0)source.bits.transactionId.pad(idBits) else if(i==1)source.bits.transactionId.pad(idBits)+nativeIds.U else (nativeIds+2+i).U)
    source.ready:=out.ready}
  arbiter.io.in(1).valid:=io.renderRequest.valid && physical;arbiter.io.in(1).bits:=io.renderRequest.bits
  io.renderRequest.ready:=Mux(physical,arbiter.io.in(1).ready,render.io.in.ready)
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val id=io.memoryResponse.bits.transactionId
  private val fromPhysical=MemoryNativeBinnedGraphicsPipeline.physical(id,native)
  private val fromVirtual=id<nativeIds.U && !fromPhysical
  private val fromDma=id>=nativeIds.U && id<(nativeIds+4).U;private val fromRenderWalk=id===(nativeIds+4).U;private val fromDmaWalk=id===(nativeIds+5).U
  private val responses=Seq(render.io.memResp,dma.io.memResp,render.io.pageWalkResp,dma.io.pageWalkResp)
  private val selects=Seq(fromVirtual,fromDma,fromRenderWalk,fromDmaWalk)
  for((response,i)<-responses.zipWithIndex){response.valid:=io.memoryResponse.valid && selects(i);response.bits:=io.memoryResponse.bits
    response.bits.transactionId:=(if(i==0)id else if(i==1)id-nativeIds.U else 0.U)}
  private val renderReplies=Module(new RRArbiter(new ComputeMemoryResponse(64,nativeIds),2))
  renderReplies.io.in(0)<>render.io.out
  renderReplies.io.in(1).valid:=io.memoryResponse.valid && fromPhysical;renderReplies.io.in(1).bits:=io.memoryResponse.bits
  private val renderQueue=Module(new Queue(new ComputeMemoryResponse(64,nativeIds),2))
  renderQueue.io.enq<>renderReplies.io.out;io.renderResponse<>renderQueue.io.deq
  private val dmaQueue=Module(new Queue(new ComputeMemoryResponse(64,kernelWordIds),2))
  dmaQueue.io.enq.valid:=dma.io.out.valid;dmaQueue.io.enq.bits:=dma.io.out.bits
  dmaQueue.io.enq.bits.transactionId:=dma.io.out.bits.transactionId+dmaIdBase.U;dma.io.out.ready:=dmaQueue.io.enq.ready
  io.dmaResponse<>dmaQueue.io.deq
  io.memoryResponse.ready:=MuxCase(false.B,Seq(fromPhysical->renderReplies.io.in(1).ready)++selects.zip(responses.map(_.ready)))
  when(io.memoryResponse.valid){assert(fromPhysical || fromVirtual || fromDma || fromRenderWalk || fromDmaWalk,"native host response outside reserved namespaces")}
  private val live=RegInit(0.U(log2Ceil(nativeIds+5).W))
  private val accepted=io.renderRequest.fire.asUInt+&io.dmaRequest.fire.asUInt
  private val retired=io.renderResponse.fire.asUInt+&io.dmaResponse.fire.asUInt
  private val next=(live+&accepted)-retired
  assert(retired<=live+&accepted && next<=(nativeIds+4).U,"native host translation lifetime/credits must remain owned")
  live:=next
  io.drained:=live===0.U && !queue.io.deq.valid && !io.renderRequest.valid && !io.dmaRequest.valid && !io.memoryResponse.valid && !render.io.walkActive && !dma.io.walkActive
  io.translationStall:=VecInit(Seq(render.io.translationStall.asUInt,dma.io.translationStall.asUInt,0.U(3.W),0.U(3.W)))
  io.translationMiss:=VecInit(Seq(render.io.translationMiss.asUInt,dma.io.translationMiss.asUInt,0.U(3.W),0.U(3.W)))
  io.walkActive:=VecInit(Seq(render.io.walkActive.asUInt,dma.io.walkActive.asUInt,0.U(3.W),0.U(3.W)))
}
