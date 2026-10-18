package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}

object TileBinHostMemoryFabric {
  def transactions(renderIds: Int,engines: Int,kernelWordIds: Int,programmable: Boolean): Int = {
    val data=4+renderIds+(if (programmable) engines*kernelWordIds else 0)
    val walkers=2+(if (programmable) engines else 0)
    1<<log2Ceil(data+walkers)
  }
}

/** One coherent host line client for the bin engine, per-engine shader staging
  * and serialized legacy fill/copy engines. Each source has its own translator
  * and response range, with independent walker IDs. VM ownership includes local
  * faults, stalled translated replies and all queued external requests.
  */
class TileBinHostMemoryFabric(config: GpuConfig,renderIds: Int,engines: Int,
  kernelWordIds: Int,dmaIdBase: Int,programmable: Boolean) extends Module {
  private val sizes=Seq(4,renderIds) ++ (if (programmable) Seq.fill(engines)(kernelWordIds) else Seq.empty)
  private val count=sizes.size
  private val bases=sizes.scanLeft(0)(_+_).dropRight(1)
  private val dataIds=sizes.sum
  private val transactions=TileBinHostMemoryFabric.transactions(renderIds,engines,kernelWordIds,programmable)
  private val idBits=log2Ceil(transactions).max(1)
  require(dmaIdBase>=0 && dmaIdBase+4<=kernelWordIds)
  val io=IO(new Bundle {
    val renderRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,renderIds)))
    val renderResponse=Decoupled(new ComputeMemoryResponse(64,renderIds))
    val stagingRequest=Vec(engines,Flipped(Decoupled(new ComputeMemoryRequest(config,64,kernelWordIds))))
    val stagingResponse=Vec(engines,Decoupled(new ComputeMemoryResponse(64,kernelWordIds)))
    val dmaRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,kernelWordIds)))
    val dmaResponse=Decoupled(new ComputeMemoryResponse(64,kernelWordIds))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val satp=Input(UInt(32.W))
    val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val drained=Output(Bool())
    // DMA, bin/descriptor, stage 0, remaining stages (sum of worker cycles).
    val translationStall=Output(Vec(4,UInt(3.W)))
    val translationMiss=Output(Vec(4,UInt(3.W)))
    val walkActive=Output(Vec(4,UInt(3.W)))
  })
  private val clients=sizes.map(n=>Module(new TranslatedLineClient(config,n,preserveCachePolicy=true)))
  private val dmaValid=io.dmaRequest.bits.transactionId>=dmaIdBase.U && io.dmaRequest.bits.transactionId<(dmaIdBase+4).U
  clients(0).io.in.valid:=io.dmaRequest.valid && dmaValid
  clients(0).io.in.bits:=io.dmaRequest.bits
  clients(0).io.in.bits.transactionId:=io.dmaRequest.bits.transactionId-dmaIdBase.U
  io.dmaRequest.ready:=clients(0).io.in.ready && dmaValid
  io.dmaResponse.valid:=clients(0).io.out.valid
  io.dmaResponse.bits:=clients(0).io.out.bits
  io.dmaResponse.bits.transactionId:=clients(0).io.out.bits.transactionId.pad(log2Ceil(kernelWordIds))+dmaIdBase.U
  clients(0).io.out.ready:=io.dmaResponse.ready
  when(io.dmaRequest.valid) { assert(dmaValid,"legacy DMA IDs must remain inside their four reserved slots") }
  clients(1).io.in <> io.renderRequest; io.renderResponse <> clients(1).io.out
  for (e <- 0 until engines) {
    if (programmable) {
      clients(e+2).io.in <> io.stagingRequest(e)
      io.stagingResponse(e) <> clients(e+2).io.out
    } else {
      io.stagingRequest(e).ready:=false.B
      io.stagingResponse(e).valid:=false.B; io.stagingResponse(e).bits:=0.U.asTypeOf(io.stagingResponse(e).bits)
      assert(!io.stagingRequest(e).valid,"fixed-function bin engines have no shader staging traffic")
    }
  }
  for (c <- clients) { c.io.satp:=io.satp; c.io.flush:=io.flush }
  private val requests=clients.map(_.io.memReq)++clients.map(_.io.pageWalk)
  private val responses=clients.map(_.io.memResp)++clients.map(_.io.pageWalkResp)
  private val ranges=bases++clients.indices.map(dataIds+_)
  private val rangeSizes=sizes++Seq.fill(count)(1)
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,transactions),2*count))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,transactions),2,pipe=true))
  private val selected=Wire(Vec(2*count,Bool()))
  for (i <- requests.indices) {
    arbiter.io.in(i).valid:=requests(i).valid; arbiter.io.in(i).bits:=requests(i).bits
    arbiter.io.in(i).bits.transactionId:=requests(i).bits.transactionId.pad(idBits)+ranges(i).U(idBits.W)
    requests(i).ready:=arbiter.io.in(i).ready
    selected(i):=io.memoryResponse.bits.transactionId>=ranges(i).U &&
      io.memoryResponse.bits.transactionId<(ranges(i)+rangeSizes(i)).U
    responses(i).valid:=io.memoryResponse.valid && selected(i)
    responses(i).bits:=io.memoryResponse.bits
    responses(i).bits.transactionId:=io.memoryResponse.bits.transactionId-ranges(i).U
  }
  queue.io.enq <> arbiter.io.out; io.memoryRequest <> queue.io.deq
  io.memoryResponse.ready:=responses.indices.map(i=>selected(i) && responses(i).ready).reduce(_ || _)
  when(io.memoryResponse.valid) { assert(PopCount(selected)===1.U,"bin host response must select exactly one memory client") }
  when(io.memoryResponse.fire) { assert(PopCount(VecInit(responses.map(_.fire)))===1.U) }
  private val live=RegInit(0.U(log2Ceil(dataIds+1).W))
  private val added=PopCount(VecInit(clients.map(_.io.in.fire)))
  private val retired=PopCount(VecInit(clients.map(_.io.out.fire)))
  private val next=(live +& added)-retired
  assert(retired<=live+&added,"bin host cannot retire an unowned translated input")
  assert(next<=dataIds.U,"bin host translation credits must remain bounded")
  live:=next
  io.drained:=live===0.U && !queue.io.deq.valid && !io.renderRequest.valid && !io.dmaRequest.valid &&
    !io.stagingRequest.map(_.valid).reduce(_ || _) && !io.memoryResponse.valid
  for (slot <- 0 until 4) {
    val members=clients.indices.filter(i=>math.min(i,3)==slot)
    def sum(f: Int=>Bool): UInt=if (members.isEmpty) 0.U else PopCount(VecInit(members.map(f)))
    io.translationStall(slot):=sum(i=>clients(i).io.translationStall)
    io.translationMiss(slot):=sum(i=>clients(i).io.translationMiss)
    io.walkActive(slot):=sum(i=>clients(i).io.walkActive)
  }
}
