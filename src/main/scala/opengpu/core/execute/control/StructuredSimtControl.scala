package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.simt.{SimtStackRead, SimtStackWrite}
import opengpu.core.trap.{CoreTrapEvent, TrapCause}

/** Instruction control client of the physical per-warp SIMT stack bank.
  * The caller grants exclusive stack access from request acceptance until
  * current/fault acceptance. This block never owns a second hidden stack.
  */
class StructuredSimtControl(config: GpuConfig = GpuConfig()) extends Module {
  private val countWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))
  val io = IO(new Bundle {
    val sync = Flipped(Decoupled(new SimtSyncRequest(config)))
    val branch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val selectWarpId = Output(UInt(config.warpIdWidth.W))
    val top = Flipped(Valid(new SimtStackRead(config)))
    val freeEntries = Input(UInt(countWidth.W))
    val push = Decoupled(new SimtStackWrite(config))
    val current = Decoupled(new SimtPath(config))
    val fault = Decoupled(new CoreTrapEvent(config))
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, inspect, push, current, fault = Value }
  private val state = RegInit(State.idle)
  private val isSync = Reg(Bool())
  private val saved = Reg(new SimtBranchRequest(config))
  private val path = Reg(new SimtPath(config))
  private val entry = Reg(new SimtStackWrite(config))
  private val cause = Reg(UInt(5.W))
  private val tval = Reg(UInt(config.xLen.W))
  private val clearSaved = io.clear.valid && io.clear.bits === saved.warpId
  private def clearing(id: UInt): Bool = io.clear.valid && io.clear.bits === id
  io.sync.ready := state === State.idle && !clearing(io.sync.bits.warpId)
  io.branch.ready := state === State.idle && !io.sync.valid && !clearing(io.branch.bits.warpId)
  io.selectWarpId := saved.warpId
  io.busy := state =/= State.idle
  io.push.valid := state === State.push && !clearSaved
  io.push.bits := entry
  io.current.valid := state === State.current && !clearSaved
  io.current.bits := path
  io.fault.valid := state === State.fault && !clearSaved
  io.fault.bits.warpId := saved.warpId
  io.fault.bits.pc := saved.pc
  io.fault.bits.activeMask := saved.activeMask
  io.fault.bits.cause := cause
  io.fault.bits.tval := tval
  io.fault.bits.laneFaultMask := saved.activeMask
  when(io.sync.fire) {
    saved := 0.U.asTypeOf(saved)
    saved.warpId := io.sync.bits.warpId
    saved.pc := io.sync.bits.pc
    saved.activeMask := io.sync.bits.activeMask
    saved.reconvergePc := io.sync.bits.joinPc
    isSync := true.B; state := State.inspect
  }.elsewhen(io.branch.fire) {
    saved := io.branch.bits
    isSync := false.B; state := State.inspect
  }
  private def fail(value: Int, address: UInt): Unit = {
    cause := value.U; tval := address; state := State.fault
  }
  private val taken = saved.activeMask & saved.takenMask
  private val fall = saved.activeMask & ~saved.takenMask
  private val divergent = taken.orR && fall.orR
  private val joinPc = io.top.bits.entry.joinPc
  private val takenAtJoin = saved.targetPc === joinPc
  private val fallAtJoin = saved.fallthroughPc === joinPc
  // Lanes reaching this scope's join can wait in its original-mask marker.
  // In particular a divergent loop exit allocates no per-iteration entry.
  private val takeCurrent = taken.orR && !(divergent && takenAtJoin)
  private val needAlternate = divergent && !takenAtJoin && !fallAtJoin
  when(state === State.inspect) {
    path.warpId := saved.warpId
    path.pc := Mux(isSync, saved.pc + 4.U,
      Mux(takeCurrent, saved.targetPc, saved.fallthroughPc))
    path.activeMask := Mux(isSync, saved.activeMask, Mux(takeCurrent, taken, fall))
    entry := 0.U.asTypeOf(entry)
    entry.warpId := saved.warpId
    entry.entry.structured := true.B
    when(isSync) {
      entry.entry.pc := saved.reconvergePc + 4.U
      entry.entry.joinPc := saved.reconvergePc
      entry.entry.activeMask := saved.activeMask
      entry.entry.originalMask := saved.activeMask
      when(saved.reconvergePc(1, 0).orR) {
        fail(TrapCause.instructionAddressMisaligned, saved.reconvergePc)
      }.elsewhen(io.freeEntries === 0.U) {
        fail(TrapCause.simtStackOverflow, saved.pc)
      }.otherwise { state := State.push }
    }.otherwise {
      entry.entry.pc := saved.fallthroughPc
      entry.entry.joinPc := joinPc
      entry.entry.activeMask := fall
      entry.entry.originalMask := io.top.bits.entry.originalMask
      entry.entry.divergent := true.B
      when(!io.top.valid || !io.top.bits.entry.structured) {
        fail(TrapCause.simtStackUnderflow, saved.pc)
      }.elsewhen(taken.orR && saved.targetPc(1, 0).orR) {
        fail(TrapCause.instructionAddressMisaligned, saved.targetPc)
      }.elsewhen(needAlternate && io.freeEntries === 0.U) {
        fail(TrapCause.simtStackOverflow, saved.pc)
      }.otherwise { state := Mux(needAlternate, State.push, State.current) }
    }
  }
  when(io.push.fire) { state := State.current }
  when(io.current.fire || io.fault.fire) { state := State.idle }
  when(state =/= State.idle && clearSaved) { state := State.idle }
}
