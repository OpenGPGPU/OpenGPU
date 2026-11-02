package opengpu.graphics

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.system.SharedCuShaderConfig
import org.scalatest.flatspec.AnyFlatSpec

class TypedBinnedFragmentImageElaborationSpec extends AnyFlatSpec {
  it should "elaborate image-enabled binned rendering with one and two tile engines" in {
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1)
    val f = FragmentShaderConfig(1, 4)
    val g = GraphicsConfig(screenWidth = 4, screenHeight = 4)
    for (engines <- Seq(1, 2)) {
      val text = ChiselStage.emitCHIRRTL(new TypedVertexBinImagePipeline(cfg, p, f, SharedCuShaderConfig(1), g,
        engines = engines, fragmentImageAvailable = true))
      assert(text.contains("imageTask"))
    }
  }
  it should "lower one image-enabled binned renderer through FIRRTL" in {
    val rtl = ChiselStage.emitSystemVerilog(new TypedVertexBinImagePipeline(
      GpuConfig(lanes = 4, warps = 2), VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1),
      FragmentShaderConfig(1, 4), SharedCuShaderConfig(1), GraphicsConfig(screenWidth = 4, screenHeight = 4),
      engines = 1, fragmentImageAvailable = true))
    assert(rtl.contains("imageTask"))
  }
}
