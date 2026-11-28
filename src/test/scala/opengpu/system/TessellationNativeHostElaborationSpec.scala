package opengpu.system

import java.nio.file.{Files, Paths}
import chisel3.util.log2Ceil
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GraphicsTestResolution
import org.scalatest.flatspec.AnyFlatSpec

object PrepareProtectedJointOwnerHost {
  private[system] def profile: NativeAlignmentHostConfig = {
    val target = NativeAlignmentHostProfile.protectedTessellationTarget
    val gpu = GpuConfig(lanes = 16, warps = 8, l2Sets = 8, l2Ways = 2)
    val graphics = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val native = NativeBinnedGraphicsConfig(
      engines = 1,
      pageBytes = target.native.pageBytes,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      preflightDescriptors = true,
      transformFeedback = true,
      payload = Some(ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 2, ports = 4))
    )
    target.copy(graphics = graphics, execution = gpu, native = native)
  }

  private[system] def geometryProfile: NativeAlignmentHostConfig = {
    val target = NativeAlignmentHostProfile.protectedJointGeometryTarget
    val graphics = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    target.copy(graphics = graphics, execution = target.execution.copy(l2Sets = 8, l2Ways = 2))
  }

  def main(args: Array[String]): Unit = {
    val fullFactory = args.length == 2 && args(1) == "--full-factory"
    val jointGeometry = args.length == 2 && args(1) == "--joint-geometry"
    require(
      args.length == 1 || fullFactory || jointGeometry,
      "pass a new protected joint FIRRTL path and optional --full-factory or --joint-geometry"
    )
    val output = Paths.get(args(0))
    require(!Files.exists(output), "protected joint FIRRTL output already exists")
    val config =
      if (jointGeometry) NativeAlignmentHostProfile.protectedJointGeometryTarget
      else if (fullFactory) NativeAlignmentHostProfile.protectedTessellationTarget
      else profile
    PrepareOwnerResourceImageFloatHost.writeFirrtl(
      new NativeAlignmentHostSystemAxi(
        config,
        useBlackBoxes = fullFactory || jointGeometry,
        exposePerformance = fullFactory || jointGeometry
      ),
      output
    )
  }
}

class TessellationNativeHostElaborationSpec extends AnyFlatSpec {
  it should "connect protected joint tessellation to two physical CUs and the shared host fabrics" in {
    val profile = PrepareProtectedJointOwnerHost.profile
    assert(profile.native.pageBytes == 4096)
    val registers = profile.requiredDiscovery.toMap
    val flags = registers(ComputeResourceCapabilities.base + 8)
    assert((flags & (1 << 22)) != 0 && (flags & ((1 << 11) | (1 << 12))) == 0)
    assert(
      (0 until 4).map(i => registers(0x550 + 4 * i)) ==
        Seq[BigInt](0x474a5001, 0x4ff, 0x08100207, 1)
    )
    intercept[IllegalArgumentException] { profile.copy(tessellationUniforms = false) }
    val circuit = circt.stage.ChiselStage.elaborate(new NativeAlignmentHostSystemAxi(profile))
    val chirrtl = circuit.lazilySerialize.iterator.flatMap(_.linesIterator).mkString("\n")
    val modules = chirrtl.linesIterator
      .map(_.trim)
      .filter(_.startsWith("inst "))
      .map(_.split("\\s+")(3).replaceFirst("_[0-9]+$", ""))
      .toVector
    assert(!chirrtl.contains("jointTessellationGeometryInvalidate"))
    for (
      name <- Seq(
        "TessellationJointThreeDomainJobImage",
        "TessellationHostShaderRouter",
        "TessellationFeedbackAdmission",
        "TessellationQueryAdmissionFence"
      )
    ) {
      assert(modules.count(_ == name) == 1, s"expected one ${name} in the protected system")
    }
    assert(modules.count(_ == "GpuComputeUnit") == 2)
    assert(modules.count(_ == "TessellationEvaluationVectorAccess") == 2)
    assert(modules.count(_ == "TessellationEvaluationPatchProvider") == 3)
    assert(!modules.contains("TessellationNativeThreeDomainJobImage"))
  }

  it should "connect checked uniform services for all three domains on the physical host" in {
    val gpu = GpuConfig(l2Sets = 8, l2Ways = 2)
    val graphics = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val vertex = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 2)
    val fragment = FragmentShaderConfig(2, 4)
    val config = NativeBinnedGraphicsConfig(
      vertex,
      fragment,
      engines = 2,
      pageBytes = 128,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      transformFeedback = true,
      payload = Some(ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 3, ports = 6))
    )
    val error = intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.elaborate(new GpuHostSystemAxi(tessellationUniforms = true))
    }
    assert(error.getMessage.contains("require the native three-domain owner"))

    val circuit = circt.stage.ChiselStage.elaborate(
      new GpuHostSystemAxi(
        graphics,
        gpu,
        numComputeUnits = 2,
        fragCore = true,
        sharedGraphics = true,
        parallelGraphics = true,
        memoryAxiDataBytes = 32,
        instructionCacheSets = 4,
        instructionCacheWays = 1,
        vectorCacheSets = 4,
        vectorCacheWays = 1,
        nativeBinnedGraphics = Some(config),
        tessellationNative = true,
        tessellationUniforms = true
      )
    )
    val instances = circuit.lazilySerialize.iterator
      .flatMap(_.linesIterator)
      .map(_.trim)
      .filter(_.startsWith("inst "))
      .toVector
    assert(instances.count(_.matches("inst service of TessellationUniformCuService(_[0-9]+)?( .*|$)")) == 3)
    assert(instances.count(_.matches("inst computeUnits_[01] of GpuComputeUnit(_[0-9]+)?( .*|$)")) == 2)
  }

  it should "reject primitive queries without feedback ownership" in {
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    val g = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 2); val f = FragmentShaderConfig(2, 4)
    val n = NativeBinnedGraphicsConfig(
      p,
      f,
      engines = 2,
      pageBytes = 128,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      transformFeedback = false,
      payload = Some(ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 3, ports = 6))
    )
    val error = intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.emitCHIRRTL(
        new GpuHostSystemAxi(
          g,
          gpu,
          numComputeUnits = 2,
          fragCore = true,
          sharedGraphics = true,
          parallelGraphics = true,
          memoryAxiDataBytes = 32,
          instructionCacheSets = 4,
          instructionCacheWays = 1,
          vectorCacheSets = 4,
          vectorCacheWays = 1,
          nativeBinnedGraphics = Some(n),
          visibilityQueries = true,
          primitiveQueries = true,
          tessellationNative = true
        )
      )
    }
    assert(error.getMessage.contains("primitive queries require native transform feedback"))
  }
  it should "elaborate shared feedback with visibility and primitive queries" in {
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    val g = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 2); val f = FragmentShaderConfig(2, 4)
    val n = NativeBinnedGraphicsConfig(
      p,
      f,
      engines = 2,
      pageBytes = 128,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      transformFeedback = true,
      payload = Some(ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 3, ports = 6))
    )
    val circuit = circt.stage.ChiselStage.elaborate(
      new GpuHostSystemAxi(
        g,
        gpu,
        numComputeUnits = 2,
        fragCore = true,
        sharedGraphics = true,
        parallelGraphics = true,
        memoryAxiDataBytes = 32,
        instructionCacheSets = 4,
        instructionCacheWays = 1,
        vectorCacheSets = 4,
        vectorCacheWays = 1,
        nativeBinnedGraphics = Some(n),
        tessellationNative = true,
        visibilityQueries = true,
        primitiveQueries = true
      )
    )
    val found = circuit.lazilySerialize.iterator.foldLeft((false, false, false, false, false)) {
      case ((owner, admission, domains, visibility, primitive), part) =>
        (
          owner || part.contains("TransformFeedbackOwnerTransfer"),
          admission || part.contains("TessellationFeedbackAdmission"),
          domains || part.contains("TessellationThreeDomainFeedbackBridge"),
          visibility || part.contains("NativeVisibilityQueryEngine"),
          primitive || part.contains("NativePrimitiveQueryEngine")
        )
    }
    assert(found._1 && found._2 && found._3 && found._4 && found._5)
  }
  it should "elaborate feedback ownership shared by both native renderers" in {
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    val g = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 2); val f = FragmentShaderConfig(2, 4)
    val n = NativeBinnedGraphicsConfig(
      p,
      f,
      engines = 2,
      pageBytes = 128,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      transformFeedback = true,
      payload = Some(ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 3, ports = 6))
    )
    val circuit = circt.stage.ChiselStage.elaborate(
      new GpuHostSystemAxi(
        g,
        gpu,
        numComputeUnits = 2,
        fragCore = true,
        sharedGraphics = true,
        parallelGraphics = true,
        memoryAxiDataBytes = 32,
        instructionCacheSets = 4,
        instructionCacheWays = 1,
        vectorCacheSets = 4,
        vectorCacheWays = 1,
        nativeBinnedGraphics = Some(n),
        tessellationNative = true,
        primitiveQueries = true
      )
    )
    val found = circuit.lazilySerialize.iterator.foldLeft((false, false, false, false, false)) {
      case ((owner, admission, domains, primitive, visibility), part) =>
        (
          owner || part.contains("TransformFeedbackOwnerTransfer"),
          admission || part.contains("TessellationFeedbackAdmission"),
          domains || part.contains("TessellationThreeDomainFeedbackBridge"),
          primitive || part.contains("NativePrimitiveQueryEngine"),
          visibility || part.contains("NativeVisibilityQueryEngine")
        )
    }
    assert(found._1 && found._2 && found._3 && found._4 && !found._5)
  }
  it should "elaborate the opt-in tessellation host with the real payload pool" in {
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    val g = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 2); val f = FragmentShaderConfig(2, 4)
    val n = NativeBinnedGraphicsConfig(
      p,
      f,
      engines = 2,
      pageBytes = 128,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      payload = Some(ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 3, ports = 6))
    )
    val circuit = circt.stage.ChiselStage.elaborate(
      new GpuHostSystemAxi(
        g,
        gpu,
        numComputeUnits = 2,
        fragCore = true,
        sharedGraphics = true,
        parallelGraphics = true,
        memoryAxiDataBytes = 32,
        instructionCacheSets = 4,
        instructionCacheWays = 1,
        vectorCacheSets = 4,
        vectorCacheWays = 1,
        nativeBinnedGraphics = Some(n),
        tessellationNative = true
      )
    )
    val found = circuit.lazilySerialize.iterator.foldLeft((false, false)) { case ((job, owner), part) =>
      (
        job || part.contains("TessellationNativeQuadJobImage"),
        owner || part.contains("TessellationThreeDomainPayloadOwner")
      )
    }
    assert(found._1 && found._2)
  }
  it should "gate and elaborate the opt-in native fragment image host" in {
    val gpu = GpuConfig(lanes = 4, warps = 2, l2Sets = 8, l2Ways = 2)
    val g = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val p = VertexShaderStageConfig(VertexInputFetchConfig(2, 2), 1); val f = FragmentShaderConfig(1, 4)
    val n = NativeBinnedGraphicsConfig(
      p,
      f,
      engines = 1,
      pageBytes = 128,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      payload = None
    )
    val missingImages = intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.elaborate(
        new GpuHostSystemAxi(
          g,
          gpu,
          numComputeUnits = 1,
          transactionsPerCu = 8,
          fragCore = true,
          sharedGraphics = true,
          parallelGraphics = true,
          nativeBinnedGraphics = Some(n),
          nativeFragmentImages = true
        )
      )
    }
    assert(missingImages.getMessage.contains("native fragment images require"))
    for (tessellation <- Seq(false, true)) {
      val circuit = circt.stage.ChiselStage.elaborate(
        new GpuHostSystemAxi(
          g,
          gpu,
          numComputeUnits = 1,
          transactionsPerCu = 8,
          fragCore = true,
          sharedGraphics = true,
          parallelGraphics = true,
          nativeBinnedGraphics = Some(n),
          imageAvailable = true,
          nativeFragmentImages = true,
          tessellationNative = tessellation,
          instructionCacheSets = 4,
          instructionCacheWays = 1,
          vectorCacheSets = 4,
          vectorCacheWays = 1
        )
      )
      val found = circuit.lazilySerialize.iterator.foldLeft((false, false, false)) {
        case ((task, reader, router), part) =>
          (
            task || part.contains("imageTask"),
            reader || part.contains("TypedFragmentImageStateReader"),
            router || part.contains("TessellationHostShaderRouter")
          )
      }
      assert(found._1 && found._2 && (!tessellation || found._3))
    }
  }
  it should "elaborate the exact geometry profile with one shared owner and no legacy joint or feedback parent" in {
    val profile = PrepareProtectedJointOwnerHost.geometryProfile
    val registers = profile.requiredDiscovery.toMap
    assert(profile.requiredDiscovery.size == 100 && registers(0x368) == 0x8b67ff)
    assert(registers(0x160) == 0x474e4305 && registers(0x168) == 0x474e4a02)
    assert(
      (0 until 4).map(i => registers(0x600 + 4 * i)) ==
        Seq[BigInt](0x474a5002, 0x8038ff, 0x08100207, BigInt("8ce58b2c", 16))
    )
    val circuit = circt.stage.ChiselStage.elaborate(
      new NativeAlignmentHostSystemAxi(profile, useBlackBoxes = true)
    )
    val moduleCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var geometryBody = ""
    var topPort = ""
    var invalidateConnected = false
    var doneBitsConnected = false
    var doneValidConnected = false
    var doneReadyConnected = false
    def moduleBody(part: String, name: String): String = {
      val start = part.indexOf(s"module $name :")
      if (start < 0) ""
      else {
        val end = part.indexOf("\nmodule ", start + 1)
        if (end < 0) part.substring(start) else part.substring(start, end)
      }
    }
    circuit.lazilySerialize.iterator.foreach { part =>
      part.linesIterator.foreach { raw =>
        val line = raw.trim
        if (line.startsWith("inst ")) {
          val name = line.split("\\s+")(3).replaceFirst("_[0-9]+$", "")
          moduleCounts(name) = moduleCounts(name) + 1
        }
        if (line.contains("output io :") && line.contains("jointTessellationGeometryInvalidate")) topPort = line
        if (
          line.contains(
            "connect jointGeometryTessellation.io.geometryInvalidate, io.jointTessellationGeometryInvalidate"
          )
        )
          invalidateConnected = true
        if (
          line.contains(
            "connect io.jointTessellationGeometryInvalidateDone.bits, jointGeometryTessellation.io.geometryInvalidateDone.bits"
          )
        )
          doneBitsConnected = true
        if (
          line.contains(
            "connect io.jointTessellationGeometryInvalidateDone.valid, jointGeometryTessellation.io.geometryInvalidateDone.valid"
          )
        )
          doneValidConnected = true
        if (
          line.contains(
            "connect jointGeometryTessellation.io.geometryInvalidateDone.ready, io.jointTessellationGeometryInvalidateDone.ready"
          )
        )
          doneReadyConnected = true
      }
      if (part.contains("module TessellationJointThreeDomainGeometryJobImage :"))
        geometryBody = moduleBody(part, "TessellationJointThreeDomainGeometryJobImage")
    }
    assert(moduleCounts("TessellationJointThreeDomainGeometryJobImage") == 1)
    assert(moduleCounts("TessellationImageBankOwner") == 1)
    assert(moduleCounts("TessellationImageBankMux") == 1)
    assert(moduleCounts("TessellationJointGeometryRangeInvalidationGate") == 3)
    assert(moduleCounts("TessellationHostShaderRouter") == 1)
    assert(moduleCounts("GpuComputeUnit") == 2)
    for (
      name <- Seq(
        "TessellationJointThreeDomainJobImage",
        "TessellationNativeThreeDomainJobImage",
        "TessellationFeedbackAdmission",
        "TessellationThreeDomainFeedbackBridge",
        "TransformFeedbackOwnerTransfer"
      )
    ) assert(!moduleCounts.contains(name), s"unexpected legacy joint or feedback module $name")
    val geometryTransactions = TessellationJointThreeDomainGeometryJobImage.transactions(4, 8, 65536, true)
    val geometryPins = TessellationJointThreeDomainGeometryJobImage.payloadPins(4)
    assert(geometryBody.nonEmpty && MemoryNativeBinnedGraphicsPipeline.transactions(profile.native) == 2048)
    assert(geometryBody.contains(s"}}[$geometryPins], memoryRequest"))
    assert(geometryBody.contains(s"transactionId : UInt<${log2Ceil(geometryTransactions)}>"))
    assert(topPort.contains("flip jointTessellationGeometryInvalidate :"))
    assert(topPort.contains("jointTessellationGeometryInvalidateDone :"))
    assert(invalidateConnected)
    assert(doneBitsConnected)
    assert(doneValidConnected)
    assert(doneReadyConnected)
  }

  it should "elaborate a geometry-only protected top with one shared owner and per-range invalidation gates" in {
    val gpu = GpuConfig(lanes = 16, warps = 8, l2Sets = 8, l2Ways = 2)
    val graphics = GraphicsConfig(
      screenWidth = math.min(4, GraphicsTestResolution.width),
      screenHeight = math.min(4, GraphicsTestResolution.height)
    )
    val payload = ShaderPayloadConfig(capacityBytes = 4096, maxPayloadBytes = 1024, owners = 2, ports = 4)
    val native = NativeBinnedGraphicsConfig(
      engines = 1,
      pageBytes = 128,
      writeCredits = 2,
      tileSlots = 1,
      pagedCapacity = 8,
      preflightDescriptors = true,
      transformFeedback = true,
      payload = Some(payload)
    )
    val both = intercept[IllegalArgumentException] {
      circt.stage.ChiselStage.emitCHIRRTL(
        new GpuHostSystemAxi(
          graphics,
          gpu,
          numComputeUnits = 2,
          fragCore = true,
          sharedGraphics = true,
          parallelGraphics = true,
          memoryAxiDataBytes = 32,
          instructionCacheSets = 4,
          instructionCacheWays = 1,
          vectorCacheSets = 4,
          vectorCacheWays = 1,
          nativeBinnedGraphics = Some(native),
          tessellationNative = true,
          tessellationUniforms = true,
          nativeProtectedSources = true,
          jointTessellationNative = true,
          jointTessellationGeometryNative = true
        )
      )
    }
    assert(both.getMessage.contains("mutually exclusive"))
    val circuit = circt.stage.ChiselStage.elaborate(
      new GpuHostSystemAxi(
        graphics,
        gpu,
        numComputeUnits = 2,
        transactionsPerCu = 16,
        fragCore = true,
        sharedGraphics = true,
        parallelGraphics = true,
        memoryAxiDataBytes = 32,
        instructionCacheSets = 4,
        instructionCacheWays = 1,
        vectorCacheSets = 4,
        vectorCacheWays = 1,
        nativeBinnedGraphics = Some(native),
        tessellationNative = true,
        tessellationUniforms = true,
        nativeProtectedSources = true,
        jointTessellationGeometryNative = true
      )
    )
    val chirrtl = circuit.lazilySerialize.iterator.flatMap(_.linesIterator).mkString("\n")
    val modules = chirrtl.linesIterator
      .map(_.trim)
      .filter(_.startsWith("inst "))
      .map(_.split("\\s+")(3).replaceFirst("_[0-9]+$", ""))
      .toVector
    assert(modules.count(_ == "TessellationJointThreeDomainGeometryJobImage") == 1)
    assert(modules.count(_ == "TessellationJointGeometryRangeInvalidationGate") == 3)
    assert(modules.count(_ == "TessellationImageBankOwner") == 1)
    assert(modules.count(_ == "TessellationImageBankMux") == 1)
    assert(modules.count(_ == "TessellationHostShaderRouter") == 1)
    assert(modules.count(_ == "NativeTransformFeedbackControl") == 1)
    assert(modules.contains("TransformFeedbackExport"))
    for (
      name <- Seq(
        "TessellationJointThreeDomainJobImage",
        "TessellationNativeThreeDomainJobImage",
        "TessellationFeedbackAdmission",
        "TessellationThreeDomainFeedbackBridge",
        "TransformFeedbackOwnerTransfer"
      )
    ) assert(!modules.contains(name), s"unexpected ordinary or feedback module $name")
    val topStart = chirrtl.indexOf("module GpuHostSystemAxi :")
    val topEnd = chirrtl.indexOf("\nmodule ", topStart + 1)
    val topBody = if (topEnd < 0) chirrtl.substring(topStart) else chirrtl.substring(topStart, topEnd)
    val topPort = topBody.linesIterator
      .find(line => line.contains("output io :") && line.contains("jointTessellationGeometryInvalidate"))
      .get
    assert(topPort.contains("flip jointTessellationGeometryInvalidate :"))
    assert(topPort.contains("jointTessellationGeometryInvalidateDone :"))
    assert(
      topBody.contains(
        "connect jointGeometryTessellation.io.geometryInvalidate, io.jointTessellationGeometryInvalidate"
      )
    )
    assert(
      topBody.contains(
        "connect io.jointTessellationGeometryInvalidateDone.bits, jointGeometryTessellation.io.geometryInvalidateDone.bits"
      )
    )
    assert(
      topBody.contains(
        "connect io.jointTessellationGeometryInvalidateDone.valid, jointGeometryTessellation.io.geometryInvalidateDone.valid"
      )
    )
    assert(
      topBody.contains(
        "connect jointGeometryTessellation.io.geometryInvalidateDone.ready, io.jointTessellationGeometryInvalidateDone.ready"
      )
    )
    assert(!topBody.contains("connect jointGeometryTessellation.io.geometryInvalidate.valid, UInt<1>(0h0)"))
    assert(!topBody.contains("connect io.jointTessellationGeometryInvalidateDone.ready, UInt<1>(0h1)"))
    val geometryTransactions = TessellationJointThreeDomainGeometryJobImage.transactions(1, 2, 8, true)
    val geometryPins = TessellationJointThreeDomainGeometryJobImage.payloadPins(1)
    val geometryStart = chirrtl.indexOf("module TessellationJointThreeDomainGeometryJobImage :")
    val geometryEnd = chirrtl.indexOf("\nmodule ", geometryStart + 1)
    val geometryBody =
      if (geometryEnd < 0) chirrtl.substring(geometryStart) else chirrtl.substring(geometryStart, geometryEnd)
    assert(geometryStart >= 0)
    assert(geometryBody.contains(s"}}[$geometryPins], memoryRequest"))
    assert(geometryBody.contains(s"transactionId : UInt<${log2Ceil(geometryTransactions)}>"))
    assert(
      (ComputeResourceCapabilities.words(
        opengpu.config.ComputeResourceProfile(gpu, 2, 32),
        nativeProtectedSourcesAvailable = true,
        nativeDescriptorPreflightAvailable = true,
        jointTessellationGeometryAvailable = true
      )(2) & ComputeResourceCapabilities.jointTessellationGeometry) != 0
    )
  }
}
