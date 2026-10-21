package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

private class Rgba8FloatMetadata extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val hit = Bool(); val fault = Bool(); val missingTaps = UInt(4.W)
  val faultAddress = UInt(32.W)
}
class BankedRgba8FloatResult(rawTapOutput: Boolean = false) extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tag = UInt(32.W)
  val values = Vec(4, UInt(32.W))
  val hit = Bool(); val fault = Bool(); val cancelled = Bool(); val missingTaps = UInt(4.W)
  val faultAddress = UInt(32.W)
  val rawTaps = if(rawTapOutput) Some(Vec(4, UInt(32.W))) else None
}

/** Complete physical RGBA8 group service with exact normalized FP32 output.
  * Q16 weights retain all weighted byte bits until one final FP32 rounding.
  * Resource/coordinate/translation admission and the native task interface are
  * upstream;this block is not a general-format or full native sampler claim.
  */
class BankedRgba8FloatTextureService(dataBytes: Int = 8192, entries: Int = 16,
    useBlackBoxes: Boolean = false, cachePolicyInput: Boolean = false,
    rawTapOutput: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new BankedRgba8Sample(cachePolicyInput)))
    val out = Decoupled(new BankedRgba8FloatResult(rawTapOutput))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(GpuConfig(), 64, 16))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 16)))
    val cancel = Input(Bool()); val invalidate = Input(Bool()); val busy = Output(Bool())
    val tapIssues = Output(UInt(3.W)); val bankConflicts = Output(UInt(3.W))
    val occupiedFills = Output(UInt(5.W)); val joined = Output(Bool())
  })
  private val groups = Module(new BankedRgba8TextureHits(dataBytes, entries, useBlackBoxes,
    readThrough = true, weightedOutput = !rawTapOutput, cachePolicyInput = cachePolicyInput,
    rawTapOutput = rawTapOutput))
  if(!rawTapOutput) {
    val metadata = Wire(new Rgba8FloatMetadata)
    val normalize = Module(new Rgba8WeightedToFp32(metadata.getWidth))
    io.in <> groups.io.in
    io.memoryRequest <> groups.io.memoryRequest.get
    groups.io.memoryResponse.get <> io.memoryResponse
    groups.io.cancel := io.cancel; groups.io.invalidate := io.invalidate
    groups.io.refill.valid := false.B; groups.io.refill.bits := 0.U.asTypeOf(groups.io.refill.bits)
    groups.io.refillDone.ready := true.B
    io.tapIssues := groups.io.tapIssues; io.bankConflicts := groups.io.bankConflicts
    io.occupiedFills := groups.io.occupiedFills.get; io.joined := groups.io.joined.get
    metadata.identity := groups.io.out.bits.identity
    metadata.tag := groups.io.out.bits.tag
    metadata.hit := groups.io.out.bits.hit
    metadata.fault := groups.io.out.bits.fault.get
    metadata.missingTaps := groups.io.out.bits.missingTaps
    metadata.faultAddress := groups.io.out.bits.faultAddress.get
    normalize.io.in.valid := groups.io.out.valid
    groups.io.out.ready := normalize.io.in.ready
    normalize.io.in.bits.tag := metadata.asUInt
    normalize.io.in.bits.cancelled := groups.io.out.bits.cancelled
    for(c <- 0 until 4) normalize.io.in.bits.numerators(c) :=
      Mux(groups.io.out.bits.hit, groups.io.out.bits.weightedSums.get(c), 0.U)
    normalize.io.cancel := io.cancel || io.invalidate
    val result = normalize.io.out.bits.tag.asTypeOf(new Rgba8FloatMetadata)
    io.out.valid := normalize.io.out.valid; normalize.io.out.ready := io.out.ready
    io.out.bits.identity := result.identity; io.out.bits.tag := result.tag
    io.out.bits.values := normalize.io.out.bits.values
    io.out.bits.hit := result.hit && !normalize.io.out.bits.cancelled
    io.out.bits.fault := result.fault
    io.out.bits.cancelled := normalize.io.out.bits.cancelled
    io.out.bits.missingTaps := Mux(normalize.io.out.bits.cancelled, 0.U, result.missingTaps)
    io.out.bits.faultAddress := result.faultAddress
    io.busy := groups.io.busy || normalize.io.busy
  } else {
    // Raw mode has no Q16 numeric output. Only rawTaps is a numeric payload;
    // the retained values field is explicitly zero for the shared result ABI.
    io.in <> groups.io.in
    io.memoryRequest <> groups.io.memoryRequest.get
    groups.io.memoryResponse.get <> io.memoryResponse
    groups.io.cancel := io.cancel; groups.io.invalidate := io.invalidate
    groups.io.refill.valid := false.B; groups.io.refill.bits := 0.U.asTypeOf(groups.io.refill.bits)
    groups.io.refillDone.ready := true.B
    io.tapIssues := groups.io.tapIssues; io.bankConflicts := groups.io.bankConflicts
    io.occupiedFills := groups.io.occupiedFills.get; io.joined := groups.io.joined.get
    io.out.valid := groups.io.out.valid; groups.io.out.ready := io.out.ready
    io.out.bits := 0.U.asTypeOf(io.out.bits)
    io.out.bits.identity := groups.io.out.bits.identity; io.out.bits.tag := groups.io.out.bits.tag
    io.out.bits.hit := groups.io.out.bits.hit; io.out.bits.cancelled := groups.io.out.bits.cancelled
    io.out.bits.fault := groups.io.out.bits.fault.get
    io.out.bits.faultAddress := groups.io.out.bits.faultAddress.get
    io.out.bits.missingTaps := groups.io.out.bits.missingTaps
    io.out.bits.rawTaps.get := groups.io.out.bits.rawTaps.get
    io.busy := groups.io.busy
  }
}
