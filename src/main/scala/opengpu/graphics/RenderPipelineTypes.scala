package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** A scene triangle in clip space (Q16.16) with per-vertex colour and depth.
  *
  * `shaderPc`/`shaderKernarg` carry the draw's shader descriptor (Phase D): the
  * fragment/vertex shader entry PC and the kernel-arg buffer address, decoded
  * from the draw-call record.  A core-backed shader reads varyings/uniforms
  * from the kernarg buffer and writes its output through the same memory.
  */
class SceneTriangle(config: GraphicsConfig) extends Bundle with HasDrawState {
  val clip = Vec(3, new ClipVertex)
  val color = Vec(3, new Varyings)
  /** Per-vertex alpha. Fixed-function records have no alpha and stay opaque. */
  val alpha = Vec(3, UInt(8.W))
  val depth = Vec(3, SInt(32.W))
  /** Per-vertex texture coordinates (unsigned Q16.16; see TexUV). */
  val uv = Vec(3, new TexUV)
  /** Half-open scanout scissor. Disabled records cover the whole target. */
  val scissorEnable = Bool()
  val scissorMinX = UInt(16.W)
  val scissorMinY = UInt(16.W)
  val scissorMaxX = UInt(16.W)
  val scissorMaxY = UInt(16.W)
  val shaderPc = UInt(32.W)
  val shaderKernarg = UInt(32.W)
  val kernargBankStride = UInt(32.W)
}

/** Fixed-function and render-target state sampled at a draw boundary. */
private[graphics] class DrawRenderState extends Bundle with HasResolvedDrawState {
  val clampBiasedU = Bool()
  val clampBiasedV = Bool()
  val blendConstant = UInt(32.W)
  val pixelCenter = Bool()
  val colorFormat = UInt(PixelFormat.width.W)
  val texFormat = UInt(PixelFormat.width.W)
  val colorBase = UInt(32.W)
  val depthBase = UInt(32.W)
  val stride = UInt(32.W)
  val sampleMode = UInt(2.W)
  val texBase = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
}

private[graphics] class RenderPipelineKernelPorts(gpuConfig: GpuConfig, kernelWordOutstanding: Int) extends Bundle {
  val mem = new Bundle {
    val req = Decoupled(new OmMemoryRequest)
    val resp = Flipped(Decoupled(new OmMemoryResponse))
  }
  val kernelMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
  val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
  val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig, 64, kernelWordOutstanding))
  val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse(64, kernelWordOutstanding)))
  val kernelL1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(gpuConfig)))
  val kernelL1InvalidateDone = Decoupled(new CacheLineInvalidate(gpuConfig))
  val kernelGlobalAtomicRequest = Decoupled(new SharedAtomicRequest(gpuConfig))
  val kernelGlobalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(gpuConfig)))
  val instructionSatp = Input(UInt(32.W))
  val instructionTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(gpuConfig)))
  val vectorSatp = Input(UInt(32.W))
  val vectorTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(gpuConfig)))
  val shaderFault = Output(Bool())
}
