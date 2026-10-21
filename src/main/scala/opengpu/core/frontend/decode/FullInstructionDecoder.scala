package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig

/** Unified RV32I + F/D/Zfh + RVV instruction classifier. */
class FullInstructionDecoder(config: GpuConfig = GpuConfig(), scalarFpuAvailable: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val decoded = Output(new FullDecodeSignals)
  })

  val scalarDecoder = Module(new RiscVDecoder)
  val fpuDecoder = Module(new FpuDecoder)
  val vectorDecoder = Module(new VectorDecoder)
  scalarDecoder.io.instruction := io.instruction
  fpuDecoder.io.instruction := io.instruction
  vectorDecoder.io.instruction := io.instruction

  io.decoded := 0.U.asTypeOf(new FullDecodeSignals)
  io.decoded.scalar := scalarDecoder.io.decoded
  io.decoded.executionType := ExecutionType.illegal

  val scalarBranch = scalarDecoder.io.decoded.branchOp =/= BranchOp.none ||
    scalarDecoder.io.decoded.jump
  val scalarMemory = scalarDecoder.io.decoded.memoryRead ||
    scalarDecoder.io.decoded.memoryWrite
  val scalarSystem = scalarDecoder.io.decoded.system

  when(scalarDecoder.io.decoded.legal) {
    io.decoded.legal := true.B
    io.decoded.executionType := Mux(
      scalarBranch,
      ExecutionType.branch,
      Mux(
        scalarMemory,
        ExecutionType.memory,
        Mux(scalarSystem, ExecutionType.system, ExecutionType.integer)
      )
    )
  }

  // Vector-vector FP uses its own RF and execution units. VF operands and
  // vfmv.f.s additionally require the scalar FP register file/backend.
  private val vectorNeedsScalarFpu =
    vectorDecoder.io.decoded.readsFloat || vectorDecoder.io.decoded.writesFloat
  when(vectorDecoder.io.decoded.valid && config.enableVector.B &&
      (scalarFpuAvailable.B || !vectorNeedsScalarFpu)) {
    io.decoded.legal := true.B
    io.decoded.executionType := ExecutionType.vector
    io.decoded.vector := vectorDecoder.io.decoded
  }

  // FPU retains the original final priority, although architectural encodings
  // make the enabled FPU and vector instruction families mutually exclusive.
  when(fpuDecoder.io.decoded.valid && config.enableFpu.B && scalarFpuAvailable.B) {
    io.decoded.legal := true.B
    io.decoded.executionType := ExecutionType.fpu
    io.decoded.fpu := fpuDecoder.io.decoded
  }

  // The implemented ISA allow-lists are authoritative. An unknown encoding,
  // a reserved encoding, or an instruction from a disabled extension traps.
  io.decoded.illegalInstruction := !io.decoded.legal
}
