package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics.VertexShaderStageConfig

/** Exact GNT8 capture; old GNT2/5 reader contracts are unchanged. */
class TessellationNativeTriangleVertexSequenceReader(config: GpuConfig, idWidth: Int)
    extends TessellationVertexSequenceCaptureReader[TessellationTrianglePatchSequenceDescriptor](
      config,
      idWidth,
      1,
      new TessellationTrianglePatchSequenceDescriptor(GpuConfig(), VertexShaderStageConfig(), 12)
    )
