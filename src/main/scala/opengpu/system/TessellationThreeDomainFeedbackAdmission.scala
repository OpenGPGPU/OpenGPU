package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.TileJobDescriptorRequest

/** Retain a classified job while the feedback owner moves to its domain.
  * An accepted switch drains even after cancellation. A child or switch is
  * offered only when ready, so cancellation cannot strand an unaccepted offer.
  */
class TessellationThreeDomainFeedbackAdmission(idWidth:Int) extends Module {
  require(idWidth>0 && idWidth<=12)
  val io=IO(new Bundle {
    val submit=Flipped(Vec(3,Decoupled(new TileJobDescriptorRequest(idWidth))))
    val render=Vec(3,Decoupled(new TileJobDescriptorRequest(idWidth)))
    val failed=Decoupled(new Bundle {val commandId=UInt(idWidth.W);val status=UInt(3.W);val domain=UInt(2.W)})
    val switch=Decoupled(UInt(2.W));val switchReply=Flipped(Decoupled(UInt(3.W)))
    val selected=Input(UInt(2.W));val poisoned=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum {val idle,select,switching,offer,failed=Value}
  private val phase=RegInit(Phase.idle);private val target=Reg(UInt(2.W))
  private val job=Reg(new TileJobDescriptorRequest(idWidth));private val canceled=RegInit(false.B);private val status=Reg(UInt(3.W))
  io.busy:=phase=/=Phase.idle
  for(i<-0 until 3){
    val higher=(i+1 until 3).map(j=>io.submit(j).valid).foldLeft(false.B)(_ || _)
    io.submit(i).ready:=phase===Phase.idle && !io.cancel && !higher
    io.render(i).valid:=phase===Phase.offer && target===i.U && !canceled && !io.cancel && !io.poisoned && io.render(i).ready
    io.render(i).bits:=job
  }
  io.failed.valid:=phase===Phase.failed;io.failed.bits.commandId:=job.commandId;io.failed.bits.status:=status;io.failed.bits.domain:=target
  io.switch.valid:=phase===Phase.select && !canceled && !io.cancel && !io.poisoned && io.selected=/=target && io.switch.ready
  io.switch.bits:=target;io.switchReply.ready:=phase===Phase.switching
  val fired=VecInit(io.submit.map(_.fire))
  when(fired.asUInt.orR){
    assert(PopCount(fired)<=1.U,"only one tessellation domain may be admitted")
    target:=PriorityEncoder(fired);job:=Mux1H(fired,io.submit.map(_.bits));canceled:=false.B;phase:=Phase.select
  }
  when(phase=/=Phase.idle && io.cancel){canceled:=true.B}
  when(phase===Phase.select){
    when(canceled || io.cancel){status:=4.U;phase:=Phase.failed}
      .elsewhen(io.poisoned){status:=7.U;phase:=Phase.failed}
      .elsewhen(io.selected===target){phase:=Phase.offer}
      .elsewhen(io.switch.fire){phase:=Phase.switching}
  }
  when(io.switchReply.fire){
    when(canceled || io.cancel || io.switchReply.bits===4.U){status:=4.U;phase:=Phase.failed}
      .elsewhen(io.switchReply.bits=/=0.U){status:=7.U;phase:=Phase.failed}
      .otherwise{phase:=Phase.offer}
  }
  when(phase===Phase.offer){when(canceled || io.cancel){status:=4.U;phase:=Phase.failed}
    .elsewhen(io.poisoned){status:=7.U;phase:=Phase.failed}
    .elsewhen(io.render(target).fire){phase:=Phase.idle}}
  when(phase===Phase.failed && io.failed.fire){phase:=Phase.idle}
}
