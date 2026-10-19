package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}
import opengpu.graphics.MemoryNativeTypedGraphicsPipeline

object NativeTypedGraphicsMemoryFabric {val transactions=64}

/** GTJ1 descriptors and shader/geometry staging use VECTOR_SATP. Trusted
  * physical image views use a disjoint subset of the same32-ID render range;
  * translating these physical backing addresses a second time is incorrect.
  * Legacy host DMA retains four translated IDs32..35. The two page walkers
  * own36/37. Both local faults and accepted lower-memory requests retain the
  * parent VM lifetime until their original producer consumes the reply.
  */
class NativeTypedGraphicsMemoryFabric(config: GpuConfig=GpuConfig(),kernelWordIds: Int=16,dmaIdBase: Int=8) extends Module {
  require(dmaIdBase>=0 && dmaIdBase+4<=kernelWordIds)
  private val transactions=NativeTypedGraphicsMemoryFabric.transactions
  val io=IO(new Bundle {
    val renderRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,32)))
    val renderResponse=Decoupled(new ComputeMemoryResponse(64,32))
    val dmaRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,kernelWordIds)))
    val dmaResponse=Decoupled(new ComputeMemoryResponse(64,kernelWordIds))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Vec(4,UInt(3.W)));val translationMiss=Output(Vec(4,UInt(3.W)));val walkActive=Output(Vec(4,UInt(3.W)))
    val drained=Output(Bool())
  })
  private val render=Module(new TranslatedLineClient(config,32,preserveCachePolicy=true))
  private val dma=Module(new TranslatedLineClient(config,4,preserveCachePolicy=true))
  for(c<-Seq(render,dma)){c.io.satp:=io.satp;c.io.flush:=io.flush}
  private val physical=MemoryNativeTypedGraphicsPipeline.physical(io.renderRequest.bits.transactionId)
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
    out.bits.transactionId:=(if(i==0)source.bits.transactionId.pad(6) else if(i==1)source.bits.transactionId.pad(6)+32.U else (34+i).U)
    source.ready:=out.ready}
  arbiter.io.in(1).valid:=io.renderRequest.valid && physical;arbiter.io.in(1).bits:=io.renderRequest.bits
  io.renderRequest.ready:=Mux(physical,arbiter.io.in(1).ready,render.io.in.ready)
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val id=io.memoryResponse.bits.transactionId
  private val fromPhysical=MemoryNativeTypedGraphicsPipeline.physical(id)
  private val fromVirtual=id<32.U && !fromPhysical
  private val fromDma=id>=32.U && id<36.U;private val fromRenderWalk=id===36.U;private val fromDmaWalk=id===37.U
  private val responses=Seq(render.io.memResp,dma.io.memResp,render.io.pageWalkResp,dma.io.pageWalkResp)
  private val selects=Seq(fromVirtual,fromDma,fromRenderWalk,fromDmaWalk)
  for((response,i)<-responses.zipWithIndex){response.valid:=io.memoryResponse.valid && selects(i);response.bits:=io.memoryResponse.bits
    response.bits.transactionId:=(if(i==0)id else if(i==1)id-32.U else 0.U)}
  private val renderReplies=Module(new RRArbiter(new ComputeMemoryResponse(64,32),2))
  renderReplies.io.in(0)<>render.io.out
  renderReplies.io.in(1).valid:=io.memoryResponse.valid && fromPhysical;renderReplies.io.in(1).bits:=io.memoryResponse.bits
  io.renderResponse<>renderReplies.io.out
  io.dmaResponse.valid:=dma.io.out.valid;io.dmaResponse.bits:=dma.io.out.bits
  io.dmaResponse.bits.transactionId:=dma.io.out.bits.transactionId+dmaIdBase.U;dma.io.out.ready:=io.dmaResponse.ready
  io.memoryResponse.ready:=MuxCase(false.B,Seq(fromPhysical->renderReplies.io.in(1).ready)++selects.zip(responses.map(_.ready)))
  when(io.memoryResponse.valid){assert(fromPhysical || fromVirtual || fromDma || fromRenderWalk || fromDmaWalk,"native host response outside reserved namespaces")}
  private val live=RegInit(0.U(7.W))
  private val accepted=io.renderRequest.fire.asUInt+&io.dmaRequest.fire.asUInt
  private val retired=io.renderResponse.fire.asUInt+&io.dmaResponse.fire.asUInt
  private val next=(live+&accepted)-retired
  assert(retired<=live+&accepted && next<=36.U,"native host translation lifetime/credits must remain owned")
  live:=next
  io.drained:=live===0.U && !queue.io.deq.valid && !io.renderRequest.valid && !io.dmaRequest.valid && !io.memoryResponse.valid
  io.translationStall:=VecInit(Seq(render.io.translationStall.asUInt,dma.io.translationStall.asUInt,0.U(3.W),0.U(3.W)))
  io.translationMiss:=VecInit(Seq(render.io.translationMiss.asUInt,dma.io.translationMiss.asUInt,0.U(3.W),0.U(3.W)))
  io.walkActive:=VecInit(Seq(render.io.walkActive.asUInt,dma.io.walkActive.asUInt,0.U(3.W),0.U(3.W)))
}
