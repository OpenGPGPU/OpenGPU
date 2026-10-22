package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.memory.ComputeMemoryResponse
import opengpu.util.RegisteredQueue2

/** Register accepted memory replies before vertex-owner routing and fault
  * control. Tags 0/1 belong to input/staging; tags 2/3 are texture slots 0/1.
  * Cancellation must drain this queue through its clients, never flush it.
  */
class VertexMemoryResponseRouter extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val inputResponse=Decoupled(new ComputeMemoryResponse(64,1))
    val stagingResponse=Decoupled(new ComputeMemoryResponse(64,1))
    val textureResponse=Decoupled(new ComputeMemoryResponse(64,2))
    val busy=Output(Bool())
  })
  private val replies=Module(new RegisteredQueue2(new ComputeMemoryResponse(64,4)))
  replies.io.enq<>io.in
  private val head=replies.io.deq;private val tag=head.bits.transactionId
  io.inputResponse.valid:=head.valid && tag===0.U;io.inputResponse.bits:=head.bits;io.inputResponse.bits.transactionId:=0.U
  io.stagingResponse.valid:=head.valid && tag===1.U;io.stagingResponse.bits:=head.bits;io.stagingResponse.bits.transactionId:=0.U
  io.textureResponse.valid:=head.valid && tag>=2.U;io.textureResponse.bits:=head.bits;io.textureResponse.bits.transactionId:=tag-2.U
  head.ready:=Mux(tag===0.U,io.inputResponse.ready,Mux(tag===1.U,io.stagingResponse.ready,io.textureResponse.ready))
  io.busy:=replies.io.count=/=0.U
}
