package opengpu.core.frontend.simt

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control._

/** Branch resolution and independent per-warp SIMT stack state.
  *
  * A divergent result reserves space in the addressed warp's stack, writes
  * reconvergence and alternate entries in LIFO order, then emits the path to
  * execute. `restore` pops exactly one saved path for a join/completed path.
  */
class SimtControlFlow(config: GpuConfig = GpuConfig(), instructionSimt: Boolean = false) extends Module {
  private val countWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))

  val io = IO(new Bundle {
    val branch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val instructionBranch = if(instructionSimt) Some(Flipped(Decoupled(new SimtBranchRequest(config)))) else None
    val sync = if(instructionSimt) Some(Flipped(Decoupled(new SimtSyncRequest(config)))) else None
    val fault = if(instructionSimt) Some(Decoupled(new opengpu.core.trap.CoreTrapEvent(config))) else None
    val current = Decoupled(new SimtPath(config))
    val overflow = Decoupled(new SimtPath(config))
    val restoreWarpId = Input(UInt(config.warpIdWidth.W))
    val restore = Decoupled(new SimtPath(config))
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val stackCounts = Output(Vec(config.warps, UInt(countWidth.W)))
  })

  private val resolver = Module(new SimtBranchResolver(config))
  private val sequencer = Module(new SimtBranchStackSequencer(config))
  private val stacks = Module(new SimtStackBank(config))

  sequencer.io.in <> resolver.io.out
  io.overflow <> sequencer.io.overflow
  resolver.io.clear <> io.clear
  sequencer.io.clear <> io.clear

  private val sequencerWarpId = resolver.io.out.bits.warpId
  private val validSequencerWarp = sequencerWarpId < config.warps.U
  sequencer.io.freeEntries := 0.U
  when(validSequencerWarp) {
    sequencer.io.freeEntries := stacks.io.freeEntries(sequencerWarpId)
  }

  io.restore.bits.warpId := stacks.io.pop.bits.warpId
  io.restore.bits.pc := stacks.io.pop.bits.entry.pc
  io.restore.bits.activeMask := stacks.io.pop.bits.entry.activeMask

  if(instructionSimt) {
    val structured = Module(new StructuredSimtControl(config))
    val legacyBusy = RegInit(false.B)
    val legacyOwner = Reg(UInt(config.warpIdWidth.W))
    val clearingLegacy = io.clear.valid && io.clear.bits === legacyOwner
    val allowStructured = !legacyBusy
    structured.io.sync.valid := io.sync.get.valid && allowStructured
    structured.io.sync.bits := io.sync.get.bits
    io.sync.get.ready := structured.io.sync.ready && allowStructured
    structured.io.branch.valid := io.instructionBranch.get.valid && allowStructured
    structured.io.branch.bits := io.instructionBranch.get.bits
    io.instructionBranch.get.ready := structured.io.branch.ready && allowStructured
    structured.io.clear := io.clear
    io.fault.get <> structured.io.fault
    val acceptingStructured = structured.io.sync.fire || structured.io.branch.fire
    val allowLegacy = !legacyBusy && !structured.io.busy &&
      !io.sync.get.valid && !io.instructionBranch.get.valid
    resolver.io.in.valid := io.branch.valid && allowLegacy
    resolver.io.in.bits := io.branch.bits
    io.branch.ready := resolver.io.in.ready && allowLegacy
    when(io.branch.fire) { legacyBusy := true.B; legacyOwner := io.branch.bits.warpId }
    when(sequencer.io.current.fire || sequencer.io.overflow.fire || (legacyBusy && clearingLegacy)) {
      legacyBusy := false.B
    }
    val paths = Module(new RRArbiter(new SimtPath(config),2))
    paths.io.in(0) <> sequencer.io.current
    paths.io.in(1) <> structured.io.current
    io.current <> paths.io.out
    stacks.io.push.valid := Mux(structured.io.busy,structured.io.push.valid,sequencer.io.stackPush.valid)
    stacks.io.push.bits := structured.io.push.bits
    when(!structured.io.busy) {
      stacks.io.push.bits.warpId := sequencer.io.current.bits.warpId
      stacks.io.push.bits.entry := sequencer.io.stackPush.bits
    }
    structured.io.push.ready := stacks.io.push.ready && structured.io.busy
    sequencer.io.stackPush.ready := stacks.io.push.ready && !structured.io.busy
    stacks.io.popWarpId := Mux(structured.io.busy,structured.io.selectWarpId,io.restoreWarpId)
    structured.io.top.valid := stacks.io.pop.valid
    structured.io.top.bits := stacks.io.pop.bits
    structured.io.freeEntries := stacks.io.freeEntries(structured.io.selectWarpId)
    val allowRestore = !legacyBusy && !structured.io.busy && !io.branch.fire && !acceptingStructured
    io.restore.valid := stacks.io.pop.valid && allowRestore
    stacks.io.pop.ready := io.restore.ready && allowRestore
  } else {
    resolver.io.in <> io.branch
    io.current <> sequencer.io.current
    stacks.io.push.valid := sequencer.io.stackPush.valid
    stacks.io.push.bits.warpId := sequencer.io.current.bits.warpId
    stacks.io.push.bits.entry := sequencer.io.stackPush.bits
    sequencer.io.stackPush.ready := stacks.io.push.ready
    stacks.io.popWarpId := io.restoreWarpId
    io.restore.valid := stacks.io.pop.valid
    stacks.io.pop.ready := io.restore.ready
  }

  stacks.io.clear <> io.clear
  io.stackCounts := stacks.io.counts
}
