package opengpu.system

import opengpu.config.GpuConfig
import opengpu.graphics.VertexShaderStageConfig

/** Exact GNT9 capture; old GNT3/6 reader contracts are unchanged. */
class TessellationNativeQuadVertexSequenceReader(config:GpuConfig,idWidth:Int)
  extends TessellationVertexSequenceCaptureReader[TessellationQuadPatchSequenceDescriptor](config,idWidth,2,
    new TessellationQuadPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
