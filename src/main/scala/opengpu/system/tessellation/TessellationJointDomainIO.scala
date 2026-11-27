package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, ShaderPayloadBinding, VectorTlbFlush}
import opengpu.graphics._
import opengpu.graphics.tessellation.{TessellationContextReply, TessellationPatchAccess}

class TessellationJointDomainIO(
    config: GpuConfig,
    vertex: VertexShaderStageConfig,
    sharedConfig: SharedCuShaderConfig,
    engines: Int,
    memoryTransactions: Int,
    payloadConfig: Option[ShaderPayloadConfig],
    payloadPinCount: Int,
    transformFeedback: Boolean,
    externalImages: Boolean,
    fragmentImageAvailable: Boolean,
    evaluationPatchAvailable: Boolean,
    tessellationInfoAvailable: Boolean
) extends Bundle {
  val submit = Flipped(Decoupled(new TessellationJointJobRequest(sharedConfig.taskIdWidth)))
  val completion = Decoupled(new TessellationJointNativeCompletion(sharedConfig.taskIdWidth))
  val images = Option.when(externalImages)(
    Flipped(
      Vec(
        engines,
        new TypedReplayTileImagePipelineIO(
          config,
          vertex,
          sharedConfig,
          payloadConfig,
          fragmentImageAvailable = fragmentImageAvailable
        )
      )
    )
  )
  val primitiveDone = Vec(engines, Decoupled(new TypedReplayPrimitiveCompletion))
  val out = Vec(engines, Decoupled(new TypedTilePixelQuadCompletion))
  val tileDone = Vec(engines, Decoupled(new TypedVertexBinImageTileCompletion))
  val inspect = Flipped(Decoupled(new TessellationPatchAccess))
  val reply = Decoupled(new TessellationContextReply)
  val shared = Flipped(
    new SharedCuGraphicsPort(
      config,
      sharedConfig.taskIdWidth,
      sharedConfig.numComputeUnits,
      geometryAvailable = true,
      tessellationAvailable = true,
      imageAvailable = fragmentImageAvailable,
      evaluationPatchAvailable = evaluationPatchAvailable,
      tessellationInfoAvailable = tessellationInfoAvailable
    )
  )
  val payload = payloadConfig.map(p => Vec(engines + 1, new FragmentPayloadPort(config, p)))
  val poolPayload = payloadConfig.map(p => Output(Valid(new ShaderPayloadBinding(config, p))))
  val payloadPins = payloadConfig.map(p => Output(Vec(payloadPinCount, Valid(new ShaderPayloadPin(p)))))
  val feedback = Option.when(transformFeedback)(new TransformFeedbackPipelineIO(config))
  val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, memoryTransactions))
  val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, memoryTransactions)))
  val satp = Input(UInt(32.W))
  val flush = Flipped(Valid(new VectorTlbFlush(config)))
  val translationStall = Output(Vec(4, UInt(3.W)))
  val translationMiss = Output(Vec(4, UInt(3.W)))
  val walkActive = Output(Vec(4, UInt(3.W)))
  val fragmentEvents = Output(new FragmentPerformanceEvents)
  val cancel = Input(Bool())
  val busy = Output(Bool())
  val drained = Output(Bool())
}
