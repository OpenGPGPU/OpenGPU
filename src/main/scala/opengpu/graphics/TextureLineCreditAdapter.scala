package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Preserve sixteen logical IDs over two external transaction slots. A lease
  * is reserved at input acceptance, issued with an immutable buffered offer,
  * and released only when the mapped reply is accepted by its logical owner.
  */
class TextureLineCreditAdapter(config: GpuConfig=GpuConfig()) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ComputeMemoryRequest(config,64,16)))
    val out=Decoupled(new ComputeMemoryResponse(64,16))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val busy=Output(Bool())
  })
  private val live=RegInit(VecInit(Seq.fill(2)(false.B)));private val issued=RegInit(VecInit(Seq.fill(2)(false.B)))
  private val logical=Reg(Vec(2,UInt(4.W)));private val offered=RegInit(false.B);private val request=Reg(new ComputeMemoryRequest(config,64,2))
  private val free= ~live.asUInt;private val slot=PriorityEncoder(free)
  io.in.ready:=free.orR && !offered;io.memoryRequest.valid:=offered;io.memoryRequest.bits:=request
  private val replySlot=io.memoryResponse.bits.transactionId
  io.out.valid:=io.memoryResponse.valid && live(replySlot) && issued(replySlot)
  io.out.bits:=io.memoryResponse.bits;io.out.bits.transactionId:=logical(replySlot)
  io.memoryResponse.ready:=io.out.ready && live(replySlot) && issued(replySlot)
  io.busy:=offered || live.asUInt.orR
  when(io.in.fire){
    assert(!(0 until 2).map(i=>live(i) && logical(i)===io.in.bits.transactionId).reduce(_ || _),"duplicate logical texture lease")
    live(slot):=true.B;issued(slot):=false.B;logical(slot):=io.in.bits.transactionId
    request:=io.in.bits;request.transactionId:=slot;offered:=true.B
  }
  when(io.memoryRequest.fire){offered:=false.B;issued(request.transactionId):=true.B}
  when(io.out.fire){live(replySlot):=false.B;issued(replySlot):=false.B}
}
