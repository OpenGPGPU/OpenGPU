package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics._

class TessellationJointGeometryCursor extends Bundle {
  val tes = new TessellationJointPublicationCursor
  val gsTaskId = UInt(33.W); val gsInputPrimitiveId = UInt(33.W)
  val inputSequence = UInt(65.W); val outputSequence = UInt(65.W)
  val binSequence = UInt(33.W)
}
class TessellationJointGeometryRangeSummary extends Bundle {
  val actualGsPublished = UInt(158.W); val received = UInt(161.W); val dropped = UInt(161.W)
  val admitted = UInt(97.W); val tasks = UInt(64.W)
  val nextCursor = new TessellationJointGeometryCursor
}

class TessellationSystemIsolineGeometryVertexTypedRangeBegin(config: GpuConfig, p: VertexShaderStageConfig, w: Int)
    extends Bundle {
  val gnt = new TessellationVertexSequenceCompletion(new TessellationPatchSequenceDescriptor(config, p, w), w)
  val capture = new TessellationVertexCaptureCompletion(p)
  val attachment = new TessellationJointGeometryAttachmentCompletion(p, w)
}
class TessellationSystemIsolineGeometryVertexTypedRangeCompletion(config: GpuConfig, p: VertexShaderStageConfig, w: Int)
    extends Bundle {
  val result = new TessellationVertexTypedRangeResult
  val lastTaskValid = Bool();
  val lastTask = new TessellationSystemIsolineGeometryVertexInputImageTaskCompletion(config, p, w)
  val geometry = new TessellationJointGeometryRangeSummary
}

class TessellationSystemTriangleGeometryVertexTypedRangeBegin(config: GpuConfig, p: VertexShaderStageConfig, w: Int)
    extends Bundle {
  val gnt = new TessellationVertexSequenceCompletion(new TessellationTrianglePatchSequenceDescriptor(config, p, w), w)
  val capture = new TessellationVertexCaptureCompletion(p)
  val attachment = new TessellationJointGeometryAttachmentCompletion(p, w)
}
class TessellationSystemTriangleGeometryVertexTypedRangeCompletion(
    config: GpuConfig,
    p: VertexShaderStageConfig,
    w: Int
) extends Bundle {
  val result = new TessellationVertexTypedRangeResult
  val lastTaskValid = Bool();
  val lastTask = new TessellationSystemTriangleGeometryVertexInputImageTaskCompletion(config, p, w)
  val geometry = new TessellationJointGeometryRangeSummary
}

class TessellationSystemQuadGeometryVertexTypedRangeBegin(config: GpuConfig, p: VertexShaderStageConfig, w: Int)
    extends Bundle {
  val gnt = new TessellationVertexSequenceCompletion(new TessellationQuadPatchSequenceDescriptor(config, p, w), w)
  val capture = new TessellationVertexCaptureCompletion(p)
  val attachment = new TessellationJointGeometryAttachmentCompletion(p, w)
}
class TessellationSystemQuadGeometryVertexTypedRangeCompletion(config: GpuConfig, p: VertexShaderStageConfig, w: Int)
    extends Bundle {
  val result = new TessellationVertexTypedRangeResult
  val lastTaskValid = Bool();
  val lastTask = new TessellationSystemQuadGeometryVertexInputImageTaskCompletion(config, p, w)
  val geometry = new TessellationJointGeometryRangeSummary
}
