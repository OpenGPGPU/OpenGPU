package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationGeometryParentElaborationSpec extends AnyFlatSpec {
  private val config = GpuConfig(lanes = 16, warps = 8)
  private val p = VertexShaderStageConfig(varyings = 4)
  private val f = FragmentShaderConfig(varyings = 4, colors = 4)
  private val shared = SharedCuShaderConfig(2)
  private val g = GraphicsConfig(
    screenWidth = math.min(64, GraphicsTestResolution.width),
    screenHeight = math.min(64, GraphicsTestResolution.height)
  )

  it should "elaborate every native GS image and vertex-input parent without transform feedback" in {
    val designs = Seq[() => RawModule](
      () =>
        new TessellationSystemIsolineImagePipeline(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          ownedVertexSource = true,
          nativeGeometryAvailable = true
        ),
      () =>
        new TessellationSystemTriangleImagePipeline(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          ownedVertexSource = true,
          nativeGeometryAvailable = true
        ),
      () =>
        new TessellationSystemQuadImagePipeline(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          ownedVertexSource = true,
          nativeGeometryAvailable = true
        ),
      () =>
        new TessellationSystemIsolineVertexInputImage(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          nativeGeometryAvailable = true
        ),
      () =>
        new TessellationSystemTriangleVertexInputImage(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          nativeGeometryAvailable = true
        ),
      () =>
        new TessellationSystemQuadVertexInputImage(config, p, f, shared, g, engines = 1, nativeGeometryAvailable = true)
    )
    for (design <- designs) {
      val chirrtl = ChiselStage.emitCHIRRTL(design())
      assert(chirrtl.contains("TessellationGeometryRawBridge"))
      assert(chirrtl.contains("TessellationGeometryPatchDispatch"))
      assert(chirrtl.contains("TessellationGeometryBinPassCore"))
      assert("""nextGsSequenceExclusive\s*:\s*UInt<65>""".r.findFirstIn(chirrtl).isDefined)
      assert("""nextSequenceExclusive\s*:\s*UInt<65>""".r.findFirstIn(chirrtl).isDefined)
      assert(!chirrtl.contains("GeometryTransformFeedbackStage"))
    }
  }

  it should "retain ordinary transform-feedback parent wiring when native GS is disabled" in {
    val designs = Seq[() => RawModule](
      () =>
        new TessellationSystemIsolineImagePipeline(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          transformFeedback = true,
          ownedVertexSource = true
        ),
      () =>
        new TessellationSystemTriangleImagePipeline(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          transformFeedback = true,
          ownedVertexSource = true
        ),
      () =>
        new TessellationSystemQuadImagePipeline(
          config,
          p,
          f,
          shared,
          g,
          engines = 1,
          transformFeedback = true,
          ownedVertexSource = true
        ),
      () =>
        new TessellationSystemIsolineVertexInputImage(config, p, f, shared, g, engines = 1, transformFeedback = true),
      () =>
        new TessellationSystemTriangleVertexInputImage(config, p, f, shared, g, engines = 1, transformFeedback = true),
      () => new TessellationSystemQuadVertexInputImage(config, p, f, shared, g, engines = 1, transformFeedback = true)
    )
    for (design <- designs) {
      val chirrtl = ChiselStage.emitCHIRRTL(design())
      assert(chirrtl.contains("TransformFeedbackExport"))
      assert(!chirrtl.contains("TessellationGeometryRawBridge"))
    }
  }
}
