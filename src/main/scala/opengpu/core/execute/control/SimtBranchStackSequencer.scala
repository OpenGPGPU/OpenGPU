package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.simt.SimtStackEntry

/** Reserve every required stack entry before any write. Insufficient capacity
  * produces one owned overflow instead of waiting for a path that cannot run.
  * Clear cancels only the named warp's pending control-flow work.
  */
class SimtBranchStackSequencer(config: GpuConfig = GpuConfig()) extends Module {
  private val freeWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SimtBranchResult(config)))
    val freeEntries = Input(UInt(freeWidth.W))
    val stackPush = Decoupled(new SimtStackEntry(config))
    val current = Decoupled(new SimtPath(config))
    val overflow = Decoupled(new SimtPath(config))
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
  })
  private val idle :: pushReconvergence :: pushAlternate :: emitCurrent :: emitOverflow :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val saved = Reg(new SimtBranchResult(config))
  private val clearSaved = io.clear.valid && io.clear.bits === saved.warpId
  private val clearInput = io.clear.valid && io.clear.bits === io.in.bits.warpId
  private val requiredEntries = io.in.bits.hasReconvergence.asUInt +& io.in.bits.hasAlternate.asUInt
  io.in.ready := state === idle && !clearInput
  io.stackPush.valid := (state === pushReconvergence || state === pushAlternate) && !clearSaved
  io.stackPush.bits := Mux(state === pushReconvergence, saved.reconvergence, saved.alternate)
  io.current.valid := state === emitCurrent && !clearSaved
  io.current.bits.warpId := saved.warpId
  io.current.bits.pc := saved.currentPc
  io.current.bits.activeMask := saved.currentMask
  io.overflow.valid := state === emitOverflow && !clearSaved
  io.overflow.bits.warpId := saved.warpId
  io.overflow.bits.pc := saved.pc
  io.overflow.bits.activeMask := saved.activeMask
  when(io.in.fire) {
    saved := io.in.bits
    when(io.freeEntries < requiredEntries) { state := emitOverflow }
      .elsewhen(io.in.bits.hasReconvergence) { state := pushReconvergence }
      .elsewhen(io.in.bits.hasAlternate) { state := pushAlternate }
      .otherwise { state := emitCurrent }
  }
  when(state === pushReconvergence && io.stackPush.fire) {
    state := Mux(saved.hasAlternate, pushAlternate, emitCurrent)
  }
  when(state === pushAlternate && io.stackPush.fire) { state := emitCurrent }
  when(io.current.fire || io.overflow.fire) { state := idle }
  when(state =/= idle && clearSaved) { state := idle }
}
