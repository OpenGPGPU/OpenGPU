package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}

object TessellationNativeJobMemoryFabric {
  def transactions(imageIds:Int):Int=1<<log2Ceil(imageIds+5)
}

/** Merge already-translated image traffic with four translated job descriptor reads.
  * The image namespace is unchanged; job descriptor data and its page walker use
  * five new IDs. The owner retains satp and all allocations until both clients
  * and this fabric have drained.
  */
class TessellationNativeJobMemoryFabric(config:GpuConfig,imageIds:Int) extends Module {
  require(imageIds>0 && isPow2(imageIds))
  private val slots=TessellationNativeJobMemoryFabric.transactions(imageIds)
  private val idWidth=log2Ceil(slots).max(1)
  val io=IO(new Bundle {
    val imageRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,imageIds)))
    val imageResponse=Decoupled(new ComputeMemoryResponse(64,imageIds))
    val jobRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,4)))
    val jobResponse=Decoupled(new ComputeMemoryResponse(64,4))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Bool());val translationMiss=Output(Bool());val walkActive=Output(Bool());val drained=Output(Bool())
  })
  private val job=Module(new TranslatedLineClient(config,4,preserveCachePolicy=true))
  job.io.satp:=io.satp;job.io.flush:=io.flush
  private val jobLive=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val requestTag=io.jobRequest.bits.transactionId
  job.io.in.valid:=io.jobRequest.valid && !jobLive(requestTag);job.io.in.bits:=io.jobRequest.bits
  io.jobRequest.ready:=job.io.in.ready && !jobLive(requestTag)
  when(io.jobRequest.valid){assert(!io.jobRequest.bits.isWrite,"native job access must be a read")}
  io.jobResponse.valid:=job.io.out.valid;io.jobResponse.bits:=job.io.out.bits
  job.io.out.ready:=io.jobResponse.ready
  when(io.jobResponse.valid){assert(jobLive(io.jobResponse.bits.transactionId),"native job response lacks an owner")}
  for(i<-0 until 4){
    val acquire=io.jobRequest.fire && requestTag===i.U
    val release=io.jobResponse.fire && io.jobResponse.bits.transactionId===i.U
    when(acquire){assert(!jobLive(i) || release,"job memory ID reused before response")}
    when(acquire){jobLive(i):=true.B}.elsewhen(release){jobLive(i):=false.B}
  }
  private val imageLive=RegInit(VecInit(Seq.fill(imageIds)(false.B)))
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,slots),3))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,slots),2,pipe=true))
  arbiter.io.in(0).valid:=io.imageRequest.valid;arbiter.io.in(0).bits:=io.imageRequest.bits
  io.imageRequest.ready:=arbiter.io.in(0).ready
  for((source,i)<-Seq(job.io.memReq,job.io.pageWalk).zipWithIndex){
    arbiter.io.in(i+1).valid:=source.valid;arbiter.io.in(i+1).bits:=source.bits
    arbiter.io.in(i+1).bits.transactionId:=(if(i==0) source.bits.transactionId.pad(idWidth)+imageIds.U else (imageIds+4).U(idWidth.W))
    source.ready:=arbiter.io.in(i+1).ready
    if(i==1)when(source.valid){assert(source.bits.transactionId===0.U)}
  }
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val tag=io.memoryResponse.bits.transactionId
  private val isImage=tag<imageIds.U;private val isRecord=tag>=imageIds.U && tag<(imageIds+4).U;private val isWalk=tag===(imageIds+4).U
  io.imageResponse.valid:=io.memoryResponse.valid && isImage;io.imageResponse.bits:=io.memoryResponse.bits
  job.io.memResp.valid:=io.memoryResponse.valid && isRecord;job.io.memResp.bits:=io.memoryResponse.bits
  job.io.memResp.bits.transactionId:=tag-imageIds.U
  job.io.pageWalkResp.valid:=io.memoryResponse.valid && isWalk;job.io.pageWalkResp.bits:=io.memoryResponse.bits
  job.io.pageWalkResp.bits.transactionId:=0.U
  io.memoryResponse.ready:=Mux(isImage,io.imageResponse.ready,Mux(isRecord,job.io.memResp.ready,Mux(isWalk,job.io.pageWalkResp.ready,false.B)))
  when(io.memoryResponse.valid){
    assert(isImage || isRecord || isWalk,"unexpected native tessellation memory ID")
    when(isRecord){assert(jobLive((tag-imageIds.U)(1,0)),"native job data response lacks an owner")}
    when(isWalk){assert(jobLive.asUInt.orR,"native job walker response lacks an owner")}
  }
  for(i<-0 until imageIds){
    val acquire=io.imageRequest.fire && io.imageRequest.bits.transactionId===i.U
    val release=io.imageResponse.fire && tag===i.U
    when(acquire){assert(!imageLive(i) || release,"image memory ID reused before response")}
    when(io.memoryResponse.valid && tag===i.U){assert(imageLive(i),"image memory response lacks an owner")}
    when(acquire){imageLive(i):=true.B}.elsewhen(release){imageLive(i):=false.B}
  }
  io.translationStall:=job.io.translationStall;io.translationMiss:=job.io.translationMiss;io.walkActive:=job.io.walkActive
  io.drained:= !imageLive.asUInt.orR && !jobLive.asUInt.orR && !queue.io.deq.valid && !io.imageRequest.valid &&
    !io.jobRequest.valid && !io.memoryResponse.valid
}
