package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Resolves a lane-wise SIMT branch into the path executed now and stack work.
  *
  * When both directions contain active lanes, the taken path executes first.
  * The fallthrough path and reconvergence state are returned as explicit stack
  * entries; the stack controller pushes reconvergence before alternate so the
  * alternate path is popped first.
  */
class SimtBranchResolver(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SimtBranchRequest(config)))
    val out = Decoupled(new SimtBranchResult(config))
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
  })

  private val outValid = RegInit(false.B)
  private val outBits = Reg(new SimtBranchResult(config))
  private val canLoad = !outValid || io.out.ready
  private val takenActive = io.in.bits.activeMask & io.in.bits.takenMask
  private val fallthroughActive =
    io.in.bits.activeMask & ~io.in.bits.takenMask
  private val hasTaken = takenActive.orR
  private val hasFallthrough = fallthroughActive.orR
  private val divergent = hasTaken && hasFallthrough
  private val currentPc =
    Mux(hasTaken, io.in.bits.targetPc, io.in.bits.fallthroughPc)
  private val currentMask =
    Mux(hasTaken, takenActive, fallthroughActive)

  private val clearInput = io.clear.valid && io.clear.bits === io.in.bits.warpId
  private val clearSaved = io.clear.valid && io.clear.bits === outBits.warpId
  io.in.ready := canLoad && !clearInput
  io.out.valid := outValid && !clearSaved
  io.out.bits := outBits

  when(canLoad) {
    outValid := io.in.valid && !clearInput
    when(io.in.fire) {
      outBits.pc := io.in.bits.pc
      outBits.activeMask := io.in.bits.activeMask
      outBits.warpId := io.in.bits.warpId
      outBits.currentPc := currentPc
      outBits.currentMask := currentMask
      outBits.divergent := divergent
      outBits.hasAlternate := divergent
      outBits.alternate.pc := io.in.bits.fallthroughPc
      outBits.alternate.activeMask := fallthroughActive
      outBits.alternate.originalMask := io.in.bits.activeMask
      outBits.alternate.divergent := true.B
      outBits.alternate.structured := false.B
      outBits.alternate.joinPc := 0.U
      outBits.hasReconvergence := divergent
      outBits.reconvergence.pc := io.in.bits.reconvergePc
      outBits.reconvergence.activeMask := io.in.bits.activeMask
      outBits.reconvergence.originalMask := io.in.bits.activeMask
      outBits.reconvergence.divergent := false.B
      outBits.reconvergence.structured := false.B
      outBits.reconvergence.joinPc := 0.U
    }
  }
  when(clearSaved && !io.in.fire) { outValid := false.B }
}
