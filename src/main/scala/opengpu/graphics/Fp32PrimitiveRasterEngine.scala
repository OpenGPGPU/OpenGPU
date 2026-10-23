package opengpu.graphics

import chisel3._

/** Port-only boundary containing the raster owner's verification layers.
  * Hierarchical simulation can reuse this engine without exposing the owner's
  * internal assertion captures across the compiled-library boundary.
  */
class Fp32PrimitiveRasterEngine(p: VertexShaderStageConfig=VertexShaderStageConfig(),
    g: GraphicsConfig=GraphicsConfig(),trianglesOnly: Boolean=false,
    interpolationContexts: Int=8) extends Module {
  private val core=Module(new Fp32PrimitiveRasterStage(p,g,trianglesOnly,interpolationContexts))
  val io=IO(chiselTypeOf(core.io))
  io<>core.io
}
