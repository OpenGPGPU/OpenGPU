package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.{NativeBinnedCompletionInfo,TileBinJobCompletion,TileJobDescriptorRequest}

/** Convert the host's held reset into one request after the control owner has
  * released any irrevocable command offer. The owner drains that request. */
class TessellationFeedbackResetGate extends Module {
  val io=IO(new Bundle {val resetActive=Input(Bool());val coreRequest=Input(Bool());val pulse=Output(Bool())})
  private val sent=RegInit(false.B)
  io.pulse:=io.coreRequest && !sent
  when(!io.resetActive){sent:=false.B}
  when(io.pulse){sent:=true.B}
}

/** Own one accepted native job while feedback object checkpoints move between
  * drained renderer owners. A failed transfer produces an ordered completion;
  * no renderer sees the job. The enclosing renderer's ready must be
  * independent of valid; an offer is presented only when it can be accepted.
  */
class TessellationFeedbackAdmission(idWidth:Int) extends Module {
  require(idWidth>0 && idWidth<=12)
  val io=IO(new Bundle {
    val submit=Flipped(Vec(2,Decoupled(new TileJobDescriptorRequest(idWidth))))
    val render=Vec(2,Decoupled(new TileJobDescriptorRequest(idWidth)))
    val failure=Vec(2,Decoupled(new TileBinJobCompletion(idWidth)))
    val failureDetails=Output(Vec(2,Vec(NativeBinnedCompletionInfo.wordCount,UInt(32.W))))
    val switch=Decoupled(Bool());val switchReply=Flipped(Decoupled(UInt(3.W)))
    val selected=Input(Bool());val poisoned=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum {val idle,select,switching,offer,failed=Value}
  private val phase=RegInit(Phase.idle);private val target=Reg(Bool())
  private val request=Reg(new TileJobDescriptorRequest(idWidth));private val cancelled=RegInit(false.B)
  private val code=Reg(UInt(4.W))
  io.busy:=phase=/=Phase.idle
  for(i<-0 until 2){
    io.submit(i).ready:=phase===Phase.idle && !io.cancel && (if(i==0) !io.submit(1).valid else true.B)
    io.render(i).valid:=phase===Phase.offer && target===i.U && !cancelled && !io.cancel && !io.poisoned && io.render(i).ready;io.render(i).bits:=request
    io.failure(i).valid:=phase===Phase.failed && target===i.U
    io.failure(i).bits:=0.U.asTypeOf(io.failure(i).bits);io.failure(i).bits.commandId:=request.commandId
    io.failure(i).bits.status:=code;io.failure(i).bits.frontendStatus:=code
    io.failureDetails(i):=VecInit(Seq((if(i==0)NativeBinnedCompletionInfo.magic else TessellationNativeCompletionInfo.magic).U(32.W),
      Cat(0.U(27.W),Mux(code===4.U,0.U(2.W),3.U(2.W)),code(2,0)))++Seq.fill(NativeBinnedCompletionInfo.wordCount-2)(0.U(32.W)))
  }
  when(io.submit.map(_.fire).reduce(_ || _)){
    assert(!(io.submit(0).fire && io.submit(1).fire),"one host command must select one renderer")
    target:=io.submit(1).fire;request:=Mux(io.submit(1).fire,io.submit(1).bits,io.submit(0).bits)
    cancelled:=false.B;phase:=Phase.select
  }
  when(phase=/=Phase.idle && io.cancel){cancelled:=true.B}
  // The owner coordinator's ready is independent of valid. Only present a
  // switch when it can fire, so reset never strands an unaccepted switch.
  io.switch.valid:=phase===Phase.select && !cancelled && !io.cancel && !io.poisoned && io.selected=/=target && io.switch.ready
  io.switch.bits:=target;io.switchReply.ready:=phase===Phase.switching
  when(phase===Phase.select){
    when(cancelled || io.cancel){code:=4.U;phase:=Phase.failed}
      .elsewhen(io.poisoned){code:=7.U;phase:=Phase.failed}
      .elsewhen(io.selected===target){phase:=Phase.offer}
      .elsewhen(io.switch.fire){phase:=Phase.switching}
  }
  when(io.switchReply.fire){
    when(cancelled || io.cancel || io.switchReply.bits===4.U){code:=4.U;phase:=Phase.failed}
      .elsewhen(io.switchReply.bits=/=0.U){code:=7.U;phase:=Phase.failed}
      .otherwise{phase:=Phase.offer}
  }
  when(phase===Phase.offer){when(cancelled || io.cancel){code:=4.U;phase:=Phase.failed}
    .elsewhen(io.poisoned){code:=7.U;phase:=Phase.failed}
    .elsewhen(io.render(target).fire){phase:=Phase.idle}}
  when(phase===Phase.failed && io.failure(target).fire){phase:=Phase.idle}
}
