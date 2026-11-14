package opengpu.graphics

import chisel3._
import chisel3.experimental.hierarchy.{instantiable,public}

/** Port-only boundary containing the raster owner's verification layers.
  * Hierarchical simulation can reuse this engine without exposing the owner's
  * internal assertion captures across the compiled-library boundary.
  * Definition caching reuses elaboration, retaining every instance's state.
  */
@instantiable
class Fp32PrimitiveRasterEngine(p: VertexShaderStageConfig=VertexShaderStageConfig(),
    g: GraphicsConfig=GraphicsConfig(),trianglesOnly: Boolean=false,
    interpolationContexts: Int=8,sampleShading:Boolean=false) extends Module {
  private val core=Module(new Fp32PrimitiveRasterStage(p,g,trianglesOnly,interpolationContexts,sampleShading))
  @public val io=IO(chiselTypeOf(core.io))
  io<>core.io
}
