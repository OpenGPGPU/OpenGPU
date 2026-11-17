package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{TransformFeedbackOwnerTransfer,TransformFeedbackPipelineIO}

/** Move feedback objects between drained isoline and triangle owners. The
  * caller must block job admission throughout a switch and retain feedback
  * memory translation and ownership until this bridge and both owners drain. */
class TessellationDomainFeedbackBridge(config:GpuConfig,contexts:Int=8) extends Module {
  val io=IO(new Bundle {
    val feedback=new TransformFeedbackPipelineIO(config)
    val owners=Vec(2,Flipped(new TransformFeedbackPipelineIO(config)))
    val switch=Flipped(Decoupled(Bool()));val switchReply=Decoupled(UInt(3.W))
    val ownerIdle=Input(Vec(2,Bool()));val memoryIdle=Input(Bool())
    val selected=Output(Bool());val quiesce=Output(Bool());val poisoned=Output(Bool())
  })
  private val transfer=Module(new TransformFeedbackOwnerTransfer(contexts))
  transfer.io.command<>io.feedback.command;io.feedback.commandReply<>transfer.io.commandReply
  transfer.io.switch<>io.switch;io.switchReply<>transfer.io.switchReply
  io.selected:=transfer.io.selected;io.quiesce:=transfer.io.quiesce;io.poisoned:=transfer.io.poisoned
  private val resetSent=RegInit(false.B)
  transfer.io.cancel:=io.feedback.cancel && !resetSent
  when(!io.feedback.cancel){resetSent:=false.B}.elsewhen(transfer.io.cancel){resetSent:=true.B}
  private val resetting=transfer.io.ownerCancel.asUInt.orR
  private val memoryLive=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val requestTag=io.feedback.memoryRequest.bits.transactionId
  private val responseTag=io.feedback.memoryResponse.bits.transactionId
  for(i<-0 until 2){
    val acquire=io.feedback.memoryRequest.fire && requestTag===i.U
    val release=io.feedback.memoryResponse.fire && responseTag===i.U
    when(acquire){assert(!memoryLive(i) || release,"feedback memory ID reused before reply")}
    when(release){assert(memoryLive(i) || acquire,"feedback memory reply lacks an owner")}
    when(acquire=/=release){memoryLive(i):=acquire}
  }
  private val memoryDrained=io.memoryIdle && !memoryLive.asUInt.orR && !io.feedback.memoryRequest.valid && !io.feedback.memoryResponse.valid
  private val feedbackIdle= !io.owners.map(_.busy).reduce(_ || _) && memoryDrained
  for(i<-0 until 2){
    val owner=io.owners(i)
    owner.command<>transfer.io.ownerCommand(i);transfer.io.ownerReply(i)<>owner.commandReply
    owner.scope:=io.feedback.scope;owner.cancel:=transfer.io.ownerCancel(i)
    owner.memoryRequest.ready:=io.feedback.memoryRequest.ready && transfer.io.selected===(i==1).B
    owner.memoryResponse.valid:=io.feedback.memoryResponse.valid && transfer.io.selected===(i==1).B
    owner.memoryResponse.bits:=io.feedback.memoryResponse.bits
    transfer.io.ownerIdle(i):=Mux(resetting,feedbackIdle,io.ownerIdle(i) && memoryDrained)
    when(transfer.io.selected=/=(i==1).B){assert(!owner.memoryRequest.valid,"inactive tessellation domain issued feedback memory")}
  }
  io.feedback.memoryRequest.valid:=Mux(transfer.io.selected,io.owners(1).memoryRequest.valid,io.owners(0).memoryRequest.valid)
  io.feedback.memoryRequest.bits:=Mux(transfer.io.selected,io.owners(1).memoryRequest.bits,io.owners(0).memoryRequest.bits)
  io.feedback.memoryResponse.ready:=Mux(transfer.io.selected,io.owners(1).memoryResponse.ready,io.owners(0).memoryResponse.ready)
  io.feedback.memorySatp:=Mux(transfer.io.selected,io.owners(1).memorySatp,io.owners(0).memorySatp)
  io.feedback.recorded:=Mux(transfer.io.selected,io.owners(1).recorded,io.owners(0).recorded)
  io.feedback.busy:=transfer.io.busy || io.owners.map(_.busy).reduce(_ || _) || !memoryDrained
}
