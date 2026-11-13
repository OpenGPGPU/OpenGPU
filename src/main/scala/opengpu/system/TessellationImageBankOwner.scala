package opengpu.system

import chisel3._
import chisel3.util._

/** Lock the common engines before a classified job reaches a domain child.
  * Classification offers are independent of child ready, including when the
  * feedback admission only asserts render.valid after render.ready.
  */
class TessellationImageBankOwner extends Module {
  val io=IO(new Bundle {
    val offer=Input(Vec(3,Bool()))
    val retired=Input(Bool())
    val drained=Input(Bool())
    val active=Output(Bool())
    val domain=Output(UInt(2.W))
    val grant=Output(Vec(3,Bool()))
  })
  private val active=RegInit(false.B)
  private val domain=RegInit(0.U(2.W))
  io.active:=active;io.domain:=domain
  for(i<-0 until 3)io.grant(i):=active && domain===i.U
  assert(PopCount(io.offer)<=1.U,"shared tessellation image bank has multiple classified jobs")
  when(!active && io.offer.asUInt.orR && io.drained){
    domain:=PriorityEncoder(io.offer);active:=true.B
  }
  when(io.retired){
    assert(active,"shared tessellation image bank retired without an owner")
    assert(io.drained,"shared tessellation image bank changed owner before drain")
    active:=false.B
  }
  when(!active){assert(io.drained,"shared tessellation image bank is busy without an owner")}
}
