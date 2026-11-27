package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointThreeDomainGeometrySpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._

  private val config = GpuConfig(lanes = 16, warps = 8)
  private val vertex = VertexShaderStageConfig()
  private val fragment = FragmentShaderConfig()
  private val shared = SharedCuShaderConfig(2)
  private val graphics = GraphicsConfig(
    screenWidth = math.min(4, GraphicsTestResolution.width),
    screenHeight = math.min(4, GraphicsTestResolution.height)
  )

  it should "size every domain from native geometry capacity before composing the physical fabric" in {
    val engines = 1
    val writeCredits = 26
    val pagedCapacity = 8
    val ids = (0 until 3).map(
      TessellationJointThreeDomainGeometryJobImage.domainTransactions(_, engines, writeCredits, pagedCapacity, true)
    )
    assert(ids.distinct.size == 1)
    val ordinaryIds = Seq(
      TessellationSystemIsolineImagePipeline.memoryTransactions(engines, writeCredits, pagedCapacity, true),
      TessellationSystemTriangleImagePipeline.memoryTransactions(engines, writeCredits, pagedCapacity, true),
      TessellationSystemQuadImagePipeline.memoryTransactions(engines, writeCredits, pagedCapacity, true)
    ).map(TessellationInputMemoryFabric.transactions)
    assert(ids.zip(ordinaryIds).forall { case (nativeIds, ordinary) => nativeIds > ordinary })
    assert(
      TessellationJointThreeDomainGeometryJobImage.transactions(engines, writeCredits, pagedCapacity, true) ==
        TessellationThreeDomainMemoryFabric.transactions(ids(0), ids(1), ids(2))
    )
    val pins = (0 until 3).map(TessellationJointThreeDomainGeometryJobImage.domainPayloadPins(_, engines))
    assert(pins.distinct.size == 1)
    assert(
      TessellationJointThreeDomainGeometryJobImage.payloadPins(engines) ==
        pins.sum + 3 * (math.max(2, engines + 1) + 1)
    )
  }

  it should "elaborate three geometry parents over one geometry image bank without transform feedback" in {
    val payload = ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 2, ports = 4)
    val circuit = ChiselStage.elaborate(
      new TessellationJointThreeDomainGeometryJobImage(
        config,
        vertex,
        fragment,
        shared,
        graphics,
        engines = 1,
        pageBytes = 128,
        writeCredits = 2,
        tileSlots = 1,
        pagedCapacity = 8,
        useSramBlackBoxes = true,
        payload = Some(payload),
        fragmentImageAvailable = true,
        sampleShading = true,
        tessellationUniforms = true,
        primitiveIdInput = true,
        enabled = true
      )
    )
    val chirrtl = circuit.lazilySerialize.iterator.flatMap(_.linesIterator).mkString("\n")
    val modules = chirrtl.linesIterator
      .map(_.trim)
      .filter(_.startsWith("inst "))
      .map(_.split("\\s+")(3).replaceFirst("_[0-9]+$", ""))
      .toVector
    val topStart = chirrtl.indexOf("module TessellationJointThreeDomainGeometryJobImage :")
    val topEnd = chirrtl.indexOf("\nmodule ", topStart + 1)
    val topBody = if (topEnd < 0) chirrtl.substring(topStart) else chirrtl.substring(topStart, topEnd)
    val topModules = topBody.linesIterator
      .map(_.trim)
      .filter(_.startsWith("inst "))
      .map(_.split("\\s+")(3).replaceFirst("_[0-9]+$", ""))
      .toVector
    assert(topStart >= 0 && topModules.nonEmpty)
    for (index <- 0 until 3) assert(topBody.contains(s"shaders.io.clients[$index]"))
    for (
      name <- Seq(
        "TessellationSystemIsolineJointGeometryVertexImage",
        "TessellationSystemTriangleJointGeometryVertexImage",
        "TessellationSystemQuadJointGeometryVertexImage",
        "TypedReplayTileImagePipeline",
        "TessellationImageBankOwner",
        "TessellationImageBankMux",
        "TessellationThreeDomainMemoryFabric",
        "TessellationThreeDomainPayloadOwner",
        "TessellationThreeDomainSampleMux",
        "SharedShaderTaskRouter"
      )
    )
      assert(topModules.count(_ == name) == 1, s"expected exactly one top-level $name in the geometry shared owner")
    assert(modules.count(_ == "TessellationJointGeometryNativeController") == 3)
    for (
      name <- Seq(
        "TessellationSystemIsolineJointVertexImage",
        "TessellationSystemTriangleJointVertexImage",
        "TessellationSystemQuadJointVertexImage",
        "TessellationThreeDomainFeedbackBridge",
        "GeometryTransformFeedbackStage",
        "TransformFeedbackExport"
      )
    )
      assert(!modules.contains(name), s"unexpected ordinary or feedback module $name")
    assert("""nextGsSequenceExclusive\s*:\s*UInt<65>""".r.findFirstIn(chirrtl).isDefined)
    assert(chirrtl.contains("connect image.io.geometry.valid, current.record.geometry.valid"))
    assert(chirrtl.contains("connect image.io.geometry.bits, current.record.geometry.bits.layer"))
    assert(chirrtl.contains("FragmentSamplePlaneSequencer"))
    val topPort =
      chirrtl.linesIterator.find(line => line.contains("output io :") && line.contains("geometryInvalidate")).get
    assert(topPort.contains("flip geometryInvalidate :"))
    assert(topPort.contains("geometryInvalidateDone :"))
    assert(!topPort.contains("feedback"))
    assert(!chirrtl.contains("TransformFeedbackPipelineIO"))
  }

  it should "keep all three domains default closed with stable held reports and reset-free reuse" in {
    simulate(
      new TessellationJointThreeDomainGeometryJobImage(
        config,
        vertex,
        fragment,
        shared,
        graphics,
        engines = 1,
        pageBytes = 128,
        writeCredits = 2,
        tileSlots = 1,
        pagedCapacity = 8,
        tessellationUniforms = true,
        primitiveIdInput = true,
        enabled = false
      )
    ) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.out.foreach(_.ready.poke(true.B)); d.io.geometryInvalidateDone.ready.poke(true.B)
      val memory = new Memory(d.clock, d.io.memoryRequest, d.io.memoryResponse)
      def quiet(): Unit = {
        d.io.memoryRequest.valid.expect(false.B); d.io.shared.launch.valid.expect(false.B)
        d.io.shared.completion.valid.expect(false.B); d.io.shared.trap.valid.expect(false.B)
        d.io.shared.invalidate.valid.expect(false.B); d.io.shared.invalidateDone.valid.expect(false.B)
        d.io.shared.geometryEvent.foreach(_.foreach(_.valid.expect(false.B)))
        d.io.geometryInvalidateDone.valid.expect(false.B); d.io.out.foreach(_.valid.expect(false.B))
      }
      for (domain <- 0 until 3) {
        request(d.io.submit.bits, domain); d.io.submit.valid.poke(true.B)
        memory.until(s"default-closed geometry domain $domain submit")(d.io.submit.ready.peek().litToBoolean)
        memory.tick(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
        memory.until(s"default-closed geometry domain $domain report")(d.io.completion.valid.peek().litToBoolean)
        val report = d.io.completion.bits
        report.base.status.expect(6.U); report.base.faultStage.expect(1.U); report.base.readBytes.expect(0.U)
        report.base.commandId.expect(5.U); report.geometryReportValid.expect(false.B)
        report.nextGsSequenceExclusive.expect(0.U); report.nextBinSequence.expect(0.U)
        val held = report.peek().litValue
        for (_ <- 0 until 3) {
          memory.tick(); quiet(); d.io.completion.valid.expect(true.B)
          d.io.submit.ready.expect(false.B); assert(report.peek().litValue == held)
        }
        assert(memory.reads.isEmpty && memory.empty)
        d.io.completion.ready.poke(true.B); memory.tick(); d.io.completion.ready.poke(false.B)
        d.io.busy.expect(false.B); d.io.drained.expect(true.B); d.io.submit.ready.expect(true.B)
      }
    }
  }
}
