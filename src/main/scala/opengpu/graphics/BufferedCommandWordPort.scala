package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}

/** One read line retained only within an explicitly bounded command job.
  * Miss acceptance and lower-request acceptance coincide, so the surrounding
  * translator captures the request's current address space on the same edge.
  * Invalidation discards reuse, including fills already in flight; accepted
  * requests and stalled word replies still complete normally.
  */
class BufferedCommandWordPort(config: GpuConfig = GpuConfig(),
    maxOutstanding: Int = 4) extends Module {
  require(maxOutstanding > 0)
  val io = IO(new Bundle {
    val port = new OmWordToLineIO(config, 64, maxOutstanding)
    val invalidate = Input(Bool())
    /** Fault status of the currently offered word, stable until out.fire. */
    val responseFault = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, waitData, respond = Value }
  private val state = RegInit(State.idle)
  private val cached = RegInit(false.B)
  private val cachedAddress = Reg(UInt(26.W))
  private val cachedData = Reg(UInt(512.W))
  private val pendingAddress = Reg(UInt(32.W))
  private val pendingWrite = Reg(Bool())
  private val fillEligible = RegInit(false.B)
  private val response = Reg(new OmMemoryResponse)
  private val responseFault = RegInit(false.B)
  private val p = io.port
  private val hit = cached && !p.in.bits.write &&
    cachedAddress === p.in.bits.addr(31, 6)
  p.memoryRequest.valid := state === State.idle && !io.invalidate &&
    p.in.valid && !hit
  p.memoryRequest.bits := 0.U.asTypeOf(p.memoryRequest.bits)
  p.memoryRequest.bits.address := Cat(p.in.bits.addr(31, 6), 0.U(6.W))
  p.memoryRequest.bits.writeData := p.in.bits.data.pad(512) << (p.in.bits.addr(5, 2) << 5)
  p.memoryRequest.bits.byteMask := 15.U(64.W) << (p.in.bits.addr(5, 2) << 2)
  p.memoryRequest.bits.isWrite := p.in.bits.write
  p.memoryRequest.bits.sizeLog2 := 6.U
  p.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  p.in.ready := state === State.idle && !io.invalidate && (hit || p.memoryRequest.ready)
  p.memoryResponse.ready := state === State.waitData
  p.out.valid := state === State.respond
  p.out.bits := response
  io.responseFault := p.out.valid && responseFault

  when(p.in.fire) {
    pendingAddress := p.in.bits.addr
    pendingWrite := p.in.bits.write
    fillEligible := !p.in.bits.write
    when(hit) {
      responseFault := false.B
      response.addr := p.in.bits.addr
      response.write := false.B
      response.data := (cachedData >> (p.in.bits.addr(5, 2) << 5))(31, 0)
      state := State.respond
    }.otherwise {
      assert(p.memoryRequest.fire, "command miss must enter translation with word acceptance")
      cached := false.B
      state := State.waitData
    }
  }
  when(p.memoryResponse.valid) {
    assert(state === State.waitData && p.memoryResponse.bits.transactionId === 0.U,
      "buffered command response must identify the outstanding line")
  }
  when(p.memoryResponse.fire) {
    responseFault := p.memoryResponse.bits.fault
    response.addr := pendingAddress
    response.write := pendingWrite
    response.data := Mux(pendingWrite, 0.U,
      (p.memoryResponse.bits.readData >> (pendingAddress(5, 2) << 5))(31, 0))
    cachedAddress := pendingAddress(31, 6)
    cachedData := p.memoryResponse.bits.readData
    cached := fillEligible && !io.invalidate && !p.memoryResponse.bits.fault
    state := State.respond
  }
  when(p.out.fire) { state := State.idle }
  when(io.invalidate) {
    cached := false.B
    fillEligible := false.B
  }
}
