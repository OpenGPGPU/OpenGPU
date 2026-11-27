package opengpu.system

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointThreeDomainElaborationSpec extends AnyFlatSpec {
  it should "connect the three owned vertex paths to one physical image bank" in {
    val config = GpuConfig(lanes = 16, warps = 8)
    val graphics = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val payload = ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 2, ports = 4)
    val circuit = ChiselStage.elaborate(
      new TessellationJointThreeDomainJobImage(
        config,
        VertexShaderStageConfig(),
        FragmentShaderConfig(),
        SharedCuShaderConfig(2),
        graphics,
        engines = 1,
        pageBytes = 128,
        writeCredits = 2,
        tileSlots = 1,
        pagedCapacity = 8,
        useSramBlackBoxes = true,
        payload = Some(payload),
        transformFeedback = true,
        fragmentImageAvailable = true,
        sampleShading = true,
        tessellationUniforms = true,
        evaluationPatchAvailable = true,
        tessellationInfoAvailable = true,
        primitiveIdInput = true,
        enabled = true
      )
    )
    val modules = circuit.lazilySerialize.iterator
      .flatMap(_.linesIterator)
      .map(_.trim)
      .filter(_.startsWith("inst "))
      .map(_.split("\\s+")(3).replaceFirst("_[0-9]+$", ""))
      .toVector
    for (
      name <- Seq(
        "TessellationSystemIsolineJointVertexImage",
        "TessellationSystemTriangleJointVertexImage",
        "TessellationSystemQuadJointVertexImage",
        "TypedReplayTileImagePipeline",
        "TessellationImageBankOwner",
        "TessellationImageBankMux",
        "TessellationThreeDomainMemoryFabric",
        "TessellationThreeDomainPayloadOwner",
        "TessellationThreeDomainFeedbackBridge"
      )
    )
      assert(modules.count(_ == name) == 1, s"expected exactly one $name in the three-domain owner")
  }
}
