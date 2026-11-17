package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Serialize original VS input(0), scratch(1), texture(2/3) tags onto the
  * existing single patch-input tag. This is routing, not memory authority:
  * the enclosing translated fabric still checks each real Sv32 permission.
  * Cancellation never flushes this credit. The VS retains every offered
  * request and accepts every reply before releasing its original resources.
  */
class TessellationVertexMemoryAdapter(config:GpuConfig) extends Module {
  val io=IO(new Bundle {
    val sourceRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,4)))
    val sourceResponse=Decoupled(new ComputeMemoryResponse(64,4))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val drained=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,offer,waitReply,deliver=Value}
  private val state=RegInit(State.idle)
  private val request=Reg(new ComputeMemoryRequest(config,64,4))
  private val response=Reg(new ComputeMemoryResponse(64,4))
  io.sourceRequest.ready:=state===State.idle
  io.memoryRequest.valid:=state===State.offer
  io.memoryRequest.bits:=request;io.memoryRequest.bits.transactionId:=0.U
  io.memoryResponse.ready:=state===State.waitReply && io.memoryResponse.bits.transactionId===0.U
  io.sourceResponse.valid:=state===State.deliver;io.sourceResponse.bits:=response
  io.drained:=state===State.idle && !io.sourceRequest.valid && !io.memoryResponse.valid
  when(io.sourceRequest.fire){request:=io.sourceRequest.bits;state:=State.offer}
  when(io.sourceRequest.valid && io.sourceRequest.bits.isWrite){
    assert(io.sourceRequest.bits.transactionId===1.U,"only the original VS scratch tag can write")
  }
  when(io.memoryRequest.fire){state:=State.waitReply}
  when(io.memoryResponse.valid){
    assert(io.memoryResponse.bits.transactionId===0.U,"serialized VS response must use patch-input tag zero")
    assert(state===State.waitReply || (state===State.offer && io.memoryRequest.ready),
      "serialized VS reply has no accepted or simultaneously accepted request")
  }
  when(io.memoryResponse.fire){
    response:=io.memoryResponse.bits;response.transactionId:=request.transactionId
    state:=State.deliver
  }
  when(io.sourceResponse.fire){state:=State.idle}
}
