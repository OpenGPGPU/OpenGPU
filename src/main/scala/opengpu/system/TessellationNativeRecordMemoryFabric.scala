package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,TranslatedLineClient,VectorTlbFlush}

object TessellationNativeRecordMemoryFabric {
  def transactions(imageIds:Int):Int=1<<log2Ceil(imageIds+2)
}

/** Merge already-translated image traffic with one translated descriptor read.
  * The image namespace is unchanged; descriptor data and its page walker use
  * two new IDs. The owner retains satp and all allocations until both clients
  * and this fabric have drained.
  */
class TessellationNativeRecordMemoryFabric(config:GpuConfig,imageIds:Int) extends Module {
  require(imageIds>0 && isPow2(imageIds))
  private val slots=TessellationNativeRecordMemoryFabric.transactions(imageIds)
  private val idWidth=log2Ceil(slots).max(1)
  val io=IO(new Bundle {
    val imageRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,imageIds)))
    val imageResponse=Decoupled(new ComputeMemoryResponse(64,imageIds))
    val recordRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,1)))
    val recordResponse=Decoupled(new ComputeMemoryResponse(64,1))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val translationStall=Output(Bool());val translationMiss=Output(Bool());val walkActive=Output(Bool());val drained=Output(Bool())
  })
  private val record=Module(new TranslatedLineClient(config,4,preserveCachePolicy=true))
  record.io.satp:=io.satp;record.io.flush:=io.flush
  private val recordLive=RegInit(false.B)
  record.io.in.valid:=io.recordRequest.valid && !recordLive;record.io.in.bits:=io.recordRequest.bits
  io.recordRequest.ready:=record.io.in.ready && !recordLive
  when(io.recordRequest.valid){assert(io.recordRequest.bits.transactionId===0.U && !io.recordRequest.bits.isWrite,
    "native record access must be a read using local transaction zero")}
  when(io.recordRequest.fire){recordLive:=true.B}
  io.recordResponse.valid:=record.io.out.valid;io.recordResponse.bits:=record.io.out.bits
  record.io.out.ready:=io.recordResponse.ready
  when(io.recordResponse.valid){assert(recordLive && record.io.out.bits.transactionId===0.U)}
  when(io.recordResponse.fire){recordLive:=false.B}
  private val imageLive=RegInit(VecInit(Seq.fill(imageIds)(false.B)))
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,slots),3))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,slots),2,pipe=true))
  arbiter.io.in(0).valid:=io.imageRequest.valid;arbiter.io.in(0).bits:=io.imageRequest.bits
  io.imageRequest.ready:=arbiter.io.in(0).ready
  for((source,i)<-Seq(record.io.memReq,record.io.pageWalk).zipWithIndex){
    arbiter.io.in(i+1).valid:=source.valid;arbiter.io.in(i+1).bits:=source.bits
    arbiter.io.in(i+1).bits.transactionId:=(imageIds+i).U(idWidth.W);source.ready:=arbiter.io.in(i+1).ready
    when(source.valid){assert(source.bits.transactionId===0.U)}
  }
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  private val tag=io.memoryResponse.bits.transactionId
  private val isImage=tag<imageIds.U;private val isRecord=tag===imageIds.U;private val isWalk=tag===(imageIds+1).U
  io.imageResponse.valid:=io.memoryResponse.valid && isImage;io.imageResponse.bits:=io.memoryResponse.bits
  record.io.memResp.valid:=io.memoryResponse.valid && isRecord;record.io.memResp.bits:=io.memoryResponse.bits
  record.io.memResp.bits.transactionId:=0.U
  record.io.pageWalkResp.valid:=io.memoryResponse.valid && isWalk;record.io.pageWalkResp.bits:=io.memoryResponse.bits
  record.io.pageWalkResp.bits.transactionId:=0.U
  io.memoryResponse.ready:=Mux(isImage,io.imageResponse.ready,Mux(isRecord,record.io.memResp.ready,Mux(isWalk,record.io.pageWalkResp.ready,false.B)))
  when(io.memoryResponse.valid){
    assert(isImage || isRecord || isWalk,"unexpected native tessellation memory ID")
    when(isRecord || isWalk){assert(recordLive,"native record response lacks an owner")}
  }
  for(i<-0 until imageIds){
    val acquire=io.imageRequest.fire && io.imageRequest.bits.transactionId===i.U
    val release=io.imageResponse.fire && tag===i.U
    when(acquire){assert(!imageLive(i) || release,"image memory ID reused before response")}
    when(io.memoryResponse.valid && tag===i.U){assert(imageLive(i),"image memory response lacks an owner")}
    when(acquire){imageLive(i):=true.B}.elsewhen(release){imageLive(i):=false.B}
  }
  io.translationStall:=record.io.translationStall;io.translationMiss:=record.io.translationMiss;io.walkActive:=record.io.walkActive
  io.drained:= !imageLive.asUInt.orR && !recordLive && !queue.io.deq.valid && !io.imageRequest.valid &&
    !io.recordRequest.valid && !io.memoryResponse.valid
}
