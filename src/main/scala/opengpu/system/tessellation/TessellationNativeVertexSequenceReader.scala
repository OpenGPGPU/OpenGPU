package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics.VertexShaderStageConfig

/** Exact GNT7 capture; old GNT1/4 reader contracts are unchanged. */
class TessellationNativeVertexSequenceReader(config: GpuConfig, idWidth: Int)
    extends TessellationVertexSequenceCaptureReader[TessellationPatchSequenceDescriptor](
      config,
      idWidth,
      0,
      new TessellationPatchSequenceDescriptor(GpuConfig(), VertexShaderStageConfig(), 12)
    )
