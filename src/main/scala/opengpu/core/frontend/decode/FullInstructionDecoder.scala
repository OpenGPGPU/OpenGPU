package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig

/** Unified RV32I + F/D/Zfh + RVV instruction classifier. */
class FullInstructionDecoder(config: GpuConfig = GpuConfig(), scalarFpuAvailable: Boolean = true,
  textureAvailable: Boolean = true, taskTextureAvailability: Boolean = false,
  nativeResourcesAvailable: Boolean = true, nativeUniformsAvailable: Boolean = false, imageAvailable: Boolean = false, storageAvailable: Boolean = false, computeUniformAvailable: Boolean = false, geometryAvailable: Boolean = false, computeTextureAvailable: Boolean = false, atomicCounterAvailable: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val imageEnabled = if(imageAvailable) Some(Input(Bool())) else None
    val storageEnabled = if(storageAvailable) Some(Input(Bool())) else None
  val computeUniformEnabled = if(computeUniformAvailable) Some(Input(Bool())) else None
    val geometryEnabled = if(geometryAvailable) Some(Input(Bool())) else None
    val computeTextureEnabled = if(computeTextureAvailable) Some(Input(Bool())) else None
    val atomicCounterEnabled = if(atomicCounterAvailable) Some(Input(Bool())) else None
    val textureEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val nativeResourcesEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val uniformResourcesEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
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
  private val textureAllowed = textureAvailable.B && io.textureEnabled.getOrElse(true.B)
  private val nativeAllowed = nativeResourcesAvailable.B && io.nativeResourcesEnabled.getOrElse(true.B)
  private val uniformAllowed = nativeUniformsAvailable.B && io.uniformResourcesEnabled.getOrElse(true.B)
  private val scalarUniform = io.instruction(31,25)===7.U
  private val vectorUniform = io.instruction(31,26)===7.U
  private val scalarNative = io.instruction(31,25)>=2.U
  private val vectorNative = io.instruction(31,26)>=2.U
  private val vectorImage = io.instruction(31,26)>=8.U && io.instruction(31,26)<=11.U
  private val imageAllowed = imageAvailable.B && io.imageEnabled.getOrElse(false.B)
  private val vectorStorage = io.instruction(31,26)>=14.U && io.instruction(31,26)<=16.U
  private val storageAllowed = storageAvailable.B && io.storageEnabled.getOrElse(false.B)
  private val computeUniformAllowed = computeUniformAvailable.B && io.computeUniformEnabled.getOrElse(false.B) && uniformAllowed
  private val computeTextureAllowed = computeTextureAvailable.B && io.computeTextureEnabled.getOrElse(false.B)
  private val counterAllowed = atomicCounterAvailable.B && io.atomicCounterEnabled.getOrElse(false.B)
  private val vectorCounter = opengpu.core.memory.AtomicCounterVectorOpcode.legal(io.instruction)
  private val scalarComputeTexture = opengpu.graphics.ShaderTextureFetchOpcode.scalar(io.instruction) || opengpu.graphics.ShaderTextureSampleOpcode.scalar(io.instruction)
  private val vectorComputeTexture = opengpu.graphics.ShaderTextureFetchOpcode.vector(io.instruction) || opengpu.graphics.ShaderTextureSampleOpcode.vector(io.instruction)
  private val scalarGeometry = opengpu.graphics.GeometryScalarOpcode.legal(io.instruction)
  private val geometryAllowed = geometryAvailable.B && io.geometryEnabled.getOrElse(false.B)

  when(scalarDecoder.io.decoded.legal &&
      (Mux(scalarGeometry, geometryAllowed,
        ((scalarComputeTexture && computeTextureAllowed) || (scalarUniform && computeUniformAllowed) || (textureAllowed && (!scalarNative || nativeAllowed) && (!scalarUniform || uniformAllowed))) || !scalarDecoder.io.decoded.texSample))) {
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
      (scalarFpuAvailable.B || !vectorNeedsScalarFpu) &&
      (Mux(vectorCounter,counterAllowed,Mux(vectorStorage, storageAllowed, Mux(vectorImage, imageAllowed, (vectorComputeTexture && computeTextureAllowed) || (vectorUniform && computeUniformAllowed) || (textureAllowed && (!vectorNative || nativeAllowed) && (!vectorUniform || uniformAllowed))))) || vectorDecoder.io.decoded.unit =/= VectorUnit.texture)) {
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
  // A disabled vector profile must not fall back to comparing scalar RFs.
  when((scalarDecoder.io.decoded.vectorBranch || scalarDecoder.io.decoded.simtSync) && !config.enableVector.B) {
    io.decoded.legal := false.B
    io.decoded.executionType := ExecutionType.illegal
    io.decoded.illegalInstruction := true.B
  }
}
