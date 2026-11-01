package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationWarpPatchRequest(warps: Int, lanes: Int) extends Bundle {
  val warp = UInt(log2Ceil(warps).W)
  /** Lane i belongs to invocation base+i. */
  val base = UInt(5.W); val mask = UInt(lanes.W)
  val access = Vec(lanes, new TessellationPatchAccess)
}
class TessellationWarpPatchResult(warps: Int, lanes: Int) extends Bundle {
  val warp = UInt(log2Ceil(warps).W)
  /** Lanes whose replies were accepted, including a failed lane. */
  val retired = UInt(lanes.W)
  val data = Vec(lanes, UInt(32.W))
  /** 0 success, 1 invalid mask or access/reply fault, 2 cancellation. */
  val status = UInt(2.W)
}

/** Serialize one warp instruction against an owned patch context. Lanes
  * execute in increasing order; earlier writes remain visible after a later
  * failure or cancellation. An offered access and its reply must drain.
  */
class TessellationWarpPatchAccess(warps: Int, lanes: Int) extends Module {
  require(warps >= 2 && isPow2(warps) && lanes >= 1 && lanes <= 32)
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new TessellationWarpPatchRequest(warps, lanes)))
    val access = Decoupled(new TessellationInvocationAccess)
    val reply = Flipped(Decoupled(new TessellationContextReply))
    val result = Decoupled(new TessellationWarpPatchResult(warps, lanes))
    val cancel = Input(Bool())
    /** The patch context has accepted a terminal result and cannot accept offers. */
    val terminal = Input(Bool()); val busy = Output(Bool())
  })
  private val idle :: offer :: waitReply :: complete :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val request = Reg(new TessellationWarpPatchRequest(warps, lanes))
  private val result = Reg(new TessellationWarpPatchResult(warps, lanes))
  private val pending = Reg(UInt(lanes.W))
  private val lane = Reg(UInt(math.max(1, log2Ceil(lanes)).W))
  private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel || io.terminal
  private val shifted = (io.request.bits.mask.pad(64) << io.request.bits.base)(63, 0)
  private val legal = io.request.bits.mask.orR && !shifted(63, 32).orR

  io.busy := state =/= idle
  io.request.ready := state === idle && !io.cancel && !io.terminal
  io.access.valid := state === offer
  io.access.bits.invocation := request.base + lane
  io.access.bits.access := request.access(lane)
  io.reply.ready := state === waitReply
  io.result.valid := state === complete; io.result.bits := result

  when(io.request.fire) {
    request := io.request.bits; result := 0.U.asTypeOf(result); result.warp := io.request.bits.warp
    pending := io.request.bits.mask; lane := PriorityEncoder(io.request.bits.mask); cancelled := false.B
    when(!legal) { result.status := 1.U; state := complete }.otherwise { state := offer }
  }
  when(state =/= idle && state =/= complete && (io.cancel || io.terminal)) { cancelled := true.B }
  // Cancellation cannot withdraw an already offered Decoupled request.
  when(io.access.fire) { state := waitReply }
  // Terminal closes the receiving context, so an unaccepted offer can no
  // longer transfer. An accepted request still owns its reply.
  when(state === offer && io.terminal && !io.access.fire) { result.status := 2.U; state := complete }
  when(io.reply.fire) {
    val bit = UIntToOH(lane, lanes)
    val next = pending & ~bit
    val malformed = io.reply.bits.source =/= 1.U || io.reply.bits.invocation =/= io.access.bits.invocation
    result.retired := result.retired | bit; result.data(lane) := io.reply.bits.result.data
    when(stop) { result.status := 2.U; state := complete }
      .elsewhen(malformed || io.reply.bits.result.status =/= 0.U) { result.status := 1.U; state := complete }
      .elsewhen(!next.orR) { state := complete }
      .otherwise { pending := next; lane := PriorityEncoder(next); state := offer }
  }
  when(io.result.fire) { state := idle }
}
