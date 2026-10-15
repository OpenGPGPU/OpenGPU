package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** A non-allocating read may wait for memory without holding the L2 lookup
  * controller. Keep its line reserved through upstream reply consumption so
  * younger same-line stores and fences cannot overtake it.
  */
class L2UncachedRead(config: GpuConfig = GpuConfig(),
    maxOutstanding: Int = 8, lowerId: Int = 7) extends Module {
  require(lowerId >= 0 && lowerId < maxOutstanding)
  val io = IO(new Bundle {
    val allocate = Flipped(Decoupled(new ComputeMemoryRequest(config, 64, maxOutstanding)))
    val lowerRequest = Decoupled(new ComputeMemoryRequest(config, 64, maxOutstanding))
    val lowerResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, maxOutstanding)))
    val response = Decoupled(new ComputeMemoryResponse(64, maxOutstanding))
    val queryAddress = Input(UInt(config.xLen.W))
    val queryConflict = Output(Bool())
    val active = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, issue, waitData, respond = Value }
  private val state = RegInit(State.idle)
  private val request = Reg(new ComputeMemoryRequest(config, 64, maxOutstanding))
  private val response = Reg(new ComputeMemoryResponse(64, maxOutstanding))
  io.allocate.ready := state === State.idle
  io.lowerRequest.valid := state === State.issue
  io.lowerRequest.bits := request
  io.lowerRequest.bits.transactionId := lowerId.U
  io.lowerResponse.ready := state === State.waitData
  io.response.valid := state === State.respond
  io.response.bits := response
  io.active := state =/= State.idle
  io.queryConflict := io.active && request.address(config.xLen - 1, 6) ===
    io.queryAddress(config.xLen - 1, 6)
  when(io.allocate.fire) {
    assert(!io.allocate.bits.isWrite, "uncached read slot accepts only reads")
    request := io.allocate.bits
    state := State.issue
  }
  when(io.lowerRequest.fire) { state := State.waitData }
  when(io.lowerResponse.valid) {
    assert(state === State.waitData && io.lowerResponse.bits.transactionId === lowerId.U,
      "uncached read reply must identify the issued request")
  }
  when(io.lowerResponse.fire) {
    response := io.lowerResponse.bits
    response.transactionId := request.transactionId
    state := State.respond
  }
  when(io.response.fire) { state := State.idle }
}
