package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Shares storage's descriptor/data/walk IDs with a counter descriptor/walk.
  * A slot is owned from queue admission through its acknowledged response;
  * the same ID is never offered to a second client while occupied.
  */
class SharedStorageCounterPort(config:GpuConfig=GpuConfig()) extends Module {
  val io=IO(new Bundle {
    val storageRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,4)))
    val storageResponse=Decoupled(new ComputeMemoryResponse(64,4))
    val counterRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,2)))
    val counterResponse=Decoupled(new ComputeMemoryResponse(64,2))
    val request=Decoupled(new ComputeMemoryRequest(config,64,4))
    val response=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val busy=Output(Bool())
  })
  private val busy=RegInit(VecInit(Seq.fill(3)(false.B)))
  private val counterOwner=Reg(Vec(3,Bool()))
  private val storageId=io.storageRequest.bits.transactionId
  private val counterSlot=Mux(io.counterRequest.bits.transactionId===0.U,0.U,2.U)
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,4),2))
  private val offers=Module(new Queue(new ComputeMemoryRequest(config,64,4),2,pipe=false,flow=false))
  when(io.storageRequest.valid){assert(storageId<3.U,"storage request ID outside shared namespace")}
  arbiter.io.in(0).valid:=io.storageRequest.valid && storageId<3.U && !busy(storageId)
  arbiter.io.in(0).bits:=io.storageRequest.bits
  io.storageRequest.ready:=arbiter.io.in(0).ready && storageId<3.U && !busy(storageId)
  arbiter.io.in(1).valid:=io.counterRequest.valid && !busy(counterSlot)
  arbiter.io.in(1).bits:=io.counterRequest.bits;arbiter.io.in(1).bits.transactionId:=counterSlot
  io.counterRequest.ready:=arbiter.io.in(1).ready && !busy(counterSlot)
  offers.io.enq<>arbiter.io.out
  when(offers.io.enq.fire){val slot=offers.io.enq.bits.transactionId
    assert(slot<3.U && !busy(slot));busy(slot):=true.B;counterOwner(slot):=arbiter.io.chosen===1.U}
  io.request<>offers.io.deq
  private val responseId=io.response.bits.transactionId
  private val inRange=responseId<3.U
  private val occupied=inRange && busy(responseId)
  private val selectedCounter=occupied && counterOwner(responseId)
  when(io.response.valid){assert(occupied,"response to an unowned shared slot")}
  io.storageResponse.valid:=io.response.valid && occupied && !selectedCounter
  io.storageResponse.bits:=io.response.bits
  io.counterResponse.valid:=io.response.valid && selectedCounter
  io.counterResponse.bits:=io.response.bits
  io.counterResponse.bits.transactionId:=Mux(responseId===2.U,1.U,0.U)
  io.response.ready:=occupied && Mux(selectedCounter,io.counterResponse.ready,io.storageResponse.ready)
  when(io.response.fire){busy(responseId):=false.B}
  io.busy:=busy.asUInt.orR
}
