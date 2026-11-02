package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.TileJobDescriptorRequest

/** Retain a classified native job while feedback objects move to its domain.
  * Child ready and switch ready must be independent of valid. A switch or job
  * offer is presented only when it can be accepted, so cancellation cannot
  * strand an unaccepted offer. Accepted switches drain before cancellation.
  */
class TessellationDomainFeedbackAdmission(idWidth:Int) extends Module {
  require(idWidth>0 && idWidth<=12)
  val io=IO(new Bundle {
    val submit=Flipped(Vec(2,Decoupled(new TileJobDescriptorRequest(idWidth))))
    val render=Vec(2,Decoupled(new TileJobDescriptorRequest(idWidth)))
    val failed=Decoupled(new Bundle {val commandId=UInt(idWidth.W);val status=UInt(3.W);val triangle=Bool()})
    val switch=Decoupled(Bool());val switchReply=Flipped(Decoupled(UInt(3.W)))
    val selected=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object Phase extends ChiselEnum {val idle,select,switching,offer,failed=Value}
  private val phase=RegInit(Phase.idle);private val target=Reg(Bool())
  private val job=Reg(new TileJobDescriptorRequest(idWidth));private val canceled=RegInit(false.B);private val status=Reg(UInt(3.W))
  io.busy:=phase=/=Phase.idle
  for(i<-0 until 2){
    io.submit(i).ready:=phase===Phase.idle && !io.cancel && (if(i==0) !io.submit(1).valid else true.B)
    io.render(i).valid:=phase===Phase.offer && target===(i==1).B && !canceled && !io.cancel && io.render(i).ready
    io.render(i).bits:=job
  }
  io.failed.valid:=phase===Phase.failed;io.failed.bits.commandId:=job.commandId;io.failed.bits.status:=status;io.failed.bits.triangle:=target
  io.switch.valid:=phase===Phase.select && !canceled && !io.cancel && io.selected=/=target && io.switch.ready
  io.switch.bits:=target;io.switchReply.ready:=phase===Phase.switching
  when(io.submit.map(_.fire).reduce(_ || _)){
    assert(!(io.submit(0).fire && io.submit(1).fire),"only one tessellation domain may be admitted")
    target:=io.submit(1).fire;job:=Mux(io.submit(1).fire,io.submit(1).bits,io.submit(0).bits);canceled:=false.B;phase:=Phase.select
  }
  when(phase=/=Phase.idle && io.cancel){canceled:=true.B}
  when(phase===Phase.select){
    when(canceled || io.cancel){status:=4.U;phase:=Phase.failed}
      .elsewhen(io.selected===target){phase:=Phase.offer}
      .elsewhen(io.switch.fire){phase:=Phase.switching}
  }
  when(io.switchReply.fire){
    when(canceled || io.cancel || io.switchReply.bits===4.U){status:=4.U;phase:=Phase.failed}
      .elsewhen(io.switchReply.bits=/=0.U){status:=7.U;phase:=Phase.failed}
      .otherwise{phase:=Phase.offer}
  }
  when(phase===Phase.offer){when(canceled || io.cancel){status:=4.U;phase:=Phase.failed}
    .elsewhen(io.render(target).fire){phase:=Phase.idle}}
  when(io.failed.fire){phase:=Phase.idle}
}
