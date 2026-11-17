package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{TransformFeedbackThreeDomainTransfer,TransformFeedbackPipelineIO}

/** Move feedback objects between three drained tessellation owners. The caller
  * blocks job admission during each switch and retains memory ownership until
  * both the transfer and all owners drain. */
class TessellationThreeDomainFeedbackBridge(config:GpuConfig,contexts:Int=8) extends Module {
  val io=IO(new Bundle {
    val feedback=new TransformFeedbackPipelineIO(config)
    val owners=Vec(3,Flipped(new TransformFeedbackPipelineIO(config)))
    val switch=Flipped(Decoupled(UInt(2.W)));val switchReply=Decoupled(UInt(3.W))
    val ownerIdle=Input(Vec(3,Bool()));val memoryIdle=Input(Bool())
    val selected=Output(UInt(2.W));val quiesce=Output(Bool());val poisoned=Output(Bool());val drained=Output(Bool())
  })
  private val transfer=Module(new TransformFeedbackThreeDomainTransfer(contexts))
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
  io.drained:=transfer.io.drained && feedbackIdle
  assert(transfer.io.selected<3.U,"invalid feedback owner")
  for(i<-0 until 3){
    val owner=io.owners(i);val selected=transfer.io.selected===i.U
    owner.command<>transfer.io.ownerCommand(i);transfer.io.ownerReply(i)<>owner.commandReply
    owner.scope:=io.feedback.scope;owner.cancel:=transfer.io.ownerCancel(i)
    owner.memoryRequest.ready:=io.feedback.memoryRequest.ready && selected
    owner.memoryResponse.valid:=io.feedback.memoryResponse.valid && selected
    owner.memoryResponse.bits:=io.feedback.memoryResponse.bits
    transfer.io.ownerIdle(i):=Mux(resetting,feedbackIdle,io.ownerIdle(i) && memoryDrained)
    when(!selected){assert(!owner.memoryRequest.valid,"inactive tessellation domain issued feedback memory")}
  }
  private val selected=transfer.io.selected
  io.feedback.memoryRequest.valid:=io.owners(selected).memoryRequest.valid
  io.feedback.memoryRequest.bits:=io.owners(selected).memoryRequest.bits
  io.feedback.memoryResponse.ready:=io.owners(selected).memoryResponse.ready
  io.feedback.memorySatp:=io.owners(selected).memorySatp
  io.feedback.recorded:=io.owners(selected).recorded
  io.feedback.busy:=transfer.io.busy || io.owners.map(_.busy).reduce(_ || _) || !memoryDrained
}
