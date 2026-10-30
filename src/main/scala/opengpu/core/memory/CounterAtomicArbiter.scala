package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Serializes the existing scalar atomic path with a task-owned counter path.
  * Translation belongs downstream. Once accepted, request, SATP, response and
  * owner remain immutable until the selected client consumes the result.
  */
class CounterAtomicArbiter(config:GpuConfig=GpuConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Vec(2,Decoupled(new SharedAtomicRequest(config))))
    val satp=Input(Vec(2,UInt(32.W)))
    val out=Vec(2,Decoupled(new SharedAtomicResponse(config)))
    val request=Decoupled(new SharedAtomicRequest(config));val requestSatp=Output(UInt(32.W))
    val response=Flipped(Decoupled(new SharedAtomicResponse(config)))
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offer,waitReply,reply=Value}
  private val state=RegInit(State.idle)
  private val arbiter=Module(new RRArbiter(new SharedAtomicRequest(config),2))
  private val request=Reg(new SharedAtomicRequest(config));private val satp=Reg(UInt(32.W))
  private val owner=Reg(UInt(1.W));private val result=Reg(new SharedAtomicResponse(config))
  for(i<-0 until 2){arbiter.io.in(i).valid:=io.in(i).valid && state===State.idle
    arbiter.io.in(i).bits:=io.in(i).bits;io.in(i).ready:=arbiter.io.in(i).ready && state===State.idle}
  arbiter.io.out.ready:=state===State.idle
  when(arbiter.io.out.fire){request:=arbiter.io.out.bits;owner:=arbiter.io.chosen;satp:=io.satp(arbiter.io.chosen);state:=State.offer}
  io.request.valid:=state===State.offer;io.request.bits:=request;io.requestSatp:=satp
  when(io.request.fire){state:=State.waitReply}
  io.response.ready:=state===State.waitReply
  when(io.response.fire){assert(io.response.bits.warpId===request.warpId,"atomic response lost its owner")
    result:=io.response.bits;state:=State.reply}
  for(i<-0 until 2){io.out(i).valid:=state===State.reply && owner===i.U;io.out(i).bits:=result}
  when(state===State.reply && io.out(owner).fire){state:=State.idle}
  io.busy:=state=/=State.idle
}
