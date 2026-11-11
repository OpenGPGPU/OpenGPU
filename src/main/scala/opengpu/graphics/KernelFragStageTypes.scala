package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.SimtBranchRequest
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
import opengpu.core.memory.{
  CacheLineInvalidate,
    ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.KernelCompletion

/** Packed producer-side quad record. Keeping the four lanes together reduces
  * the number of independent dynamic array reads in the consumer path. */
private[graphics] class KernelFragQuadRecord(gfxConfig: GraphicsConfig) extends Bundle {
  val lanes = Vec(4, new RasterFragment(gfxConfig))
  val u = Vec(4, UInt(32.W))
  val v = Vec(4, UInt(32.W))
  /** Rasterizer-derived per-sample depths, a register sideband carried in
    * parallel with each lane (outside the SoA kernarg layout). */
  val depths = Vec(4, Vec(gfxConfig.maxSampleCount, UInt(30.W)))
}

/** Consumer-side emit payload of one lane.  The whole 2x2 quad is captured
  * into a register at a quad boundary, so the emit/output muxes are driven by
  * the 2-bit lane selector instead of the full batch index. */
private[graphics] class KernelFragEmitLane(gfxConfig: GraphicsConfig) extends Bundle {
  val x = SInt(gfxConfig.coordWidth.W)
  val y = SInt(gfxConfig.coordWidth.W)
  val e0 = SInt(gfxConfig.edgeWidth.W)
  val e1 = SInt(gfxConfig.edgeWidth.W)
  val e2 = SInt(gfxConfig.edgeWidth.W)
  val covered = Bool()
  val coverageMask = UInt(gfxConfig.maxSampleCount.W)
}

/** Fragment batch producer/consumer contract, independent of its executor. */
class KernelFragFrontendIO(config: GpuConfig, gfxConfig: GraphicsConfig, val quadExports: Boolean = false) extends Bundle {
  val fragIn = Flipped(Decoupled(new FragmentQuad(gfxConfig)))
  /** Per-lane perspective-correct UVs of the presented quad. */
  val fragUv = Input(Vec(4, new TexUV))
  /** Per-lane rasterizer-derived per-sample depths of the presented quad. */
  val fragDepths = Input(Vec(4, Vec(gfxConfig.maxSampleCount, UInt(30.W))))
  val out = Decoupled(new RasterFragment(gfxConfig))
  /** Selected shader depth and effective coverage stay attached to each lane. */
  val quadOut = if (quadExports) Some(Decoupled(Vec(4, new SamplePixel(gfxConfig.maxSampleCount)))) else None
  /** Per-sample depths selected for the presented fragment. */
  val outDepths = Output(Vec(gfxConfig.maxSampleCount, UInt(30.W)))
  /** The shader-depth word overrides the per-sample raster depths. */
  val depthOverride = Output(Bool())
  val shaderPc = Input(UInt(32.W))
  val inputSkip = Input(UInt(6.W))
  val outputSkip = Input(UInt(4.W))
  /** Output-control ABI: 0 = legacy (any nonzero word emits), 1 = the
    * output-valid word splits into bit 0 emit / bit 1 depth override. */
  val abi1 = Input(Bool())
  val kernargBase = Input(UInt(32.W))
  /** Byte stride between two complete, identically laid-out kernarg banks. */
  val kernargBankStride = Input(UInt(32.W))
  /** Texture sampling config for the tex.sample instruction. */
  val texFormat = Input(UInt(PixelFormat.width.W))
  val texBase = Input(UInt(32.W))
  val texWidth = Input(UInt(14.W))
  val texHeight = Input(UInt(14.W))
  val texWrapClamp = Input(Bool())
  val texNearest = Input(Bool())
  val texMaxLevel = Input(UInt(4.W))
  val texLodBias = Input(SInt(5.W))
  val texMinLevel = Input(UInt(4.W))
  val flush = Input(Bool())
  val drained = Output(Bool())
  /** Ordered per-draw completion event (one per rising flush). */
  val drawRetire = Decoupled(Bool())
}

/** Connect a producer frontend to its fragment worker. */
object KernelFragFrontendIO {
  def connect(front: KernelFragFrontendIO, worker: KernelFragFrontendIO): Unit = {
    require(front.quadExports == worker.quadExports)
    worker.fragIn <> front.fragIn
    worker.fragUv := front.fragUv
    worker.fragDepths := front.fragDepths
    front.out <> worker.out
    front.outDepths := worker.outDepths
    front.depthOverride := worker.depthOverride
    front.quadOut.foreach(_ <> worker.quadOut.get)
    worker.shaderPc := front.shaderPc
    worker.inputSkip := front.inputSkip
    worker.outputSkip := front.outputSkip
    worker.abi1 := front.abi1
    worker.kernargBase := front.kernargBase
    worker.kernargBankStride := front.kernargBankStride
    worker.texFormat := front.texFormat
    worker.texBase := front.texBase
    worker.texWidth := front.texWidth
    worker.texHeight := front.texHeight
    worker.texWrapClamp := front.texWrapClamp
    worker.texNearest := front.texNearest
    worker.texMaxLevel := front.texMaxLevel
    worker.texLodBias := front.texLodBias
    worker.texMinLevel := front.texMinLevel
    worker.flush := front.flush
    front.drained := worker.drained
    front.drawRetire <> worker.drawRetire
  }
}

/** Standalone fragment worker ports, retaining the existing shader ABI. */
class KernelFragStageIO(config: GpuConfig, gfxConfig: GraphicsConfig, stagingTransactions: Int = 2,
  quadExports: Boolean = false, shaderPayload: Option[ShaderPayloadConfig] = None) extends KernelFragFrontendIO(config, gfxConfig, quadExports) {
  val payload = shaderPayload.map(p => new FragmentPayloadPort(config, p))
  val performance = Output(new FragmentPerformanceEvents)
  /** One-cycle failure events; ownership lasts until drained/draw retirement. */
  val executionFault = Output(Bool())
  val memReq = Decoupled(new ComputeMemoryRequest(config))
  val memResp = Flipped(Decoupled(new ComputeMemoryResponse()))
  val wordMemReq = Decoupled(new ComputeMemoryRequest(config, 64, stagingTransactions))
  val wordMemResp = Flipped(Decoupled(new ComputeMemoryResponse(64, stagingTransactions)))
  /** Texture-unit word memory.  Routed through the translated texture client
    * (VA under VECTOR_SATP), same translation domain as kernarg staging. */
  val texMem = new Bundle {
    val req = Decoupled(new OmMemoryRequest)
    val resp = Flipped(Decoupled(new OmMemoryResponse))
  }
  val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
  val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
  val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
  val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
  val kernelLaunch = new Bundle {
    val invocations = Output(new opengpu.core.memory.KernelInvocationPolicy(config))
    val valid = Output(Bool())
    val ready = Input(Bool())
    val kernelPc = Output(UInt(config.xLen.W))
    val kernargAddress = Output(UInt(config.xLen.W))
    val gridX = Output(UInt(32.W))
    val gridY = Output(UInt(32.W))
    val gridZ = Output(UInt(32.W))
    val localX = Output(UInt(16.W))
    val localY = Output(UInt(16.W))
    val localZ = Output(UInt(16.W))
  }
  val kernelCompletion = Flipped(Decoupled(new KernelCompletion))
  val kernelTrap = Flipped(Decoupled(new CoreTrapEvent(config)))
  val kernelSimtBranch = Flipped(Decoupled(new SimtBranchRequest(config)))
  val kernelTexSample = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
  val kernelTexWriteback = Decoupled(new ScalarCommitRequest(config))
  val kernelVectorTexSample =
    Flipped(Decoupled(new VectorTextureRequest(config)))
  val kernelVectorTexWriteback =
    Decoupled(new VectorCommitRequest(config))
}
