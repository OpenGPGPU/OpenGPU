package opengpu.system

import chisel3._
import chisel3.stage.{ChiselCircuitAnnotation, ChiselGeneratorAnnotation}
import chisel3.stage.phases.{AddDebugIntrinsics, AddDedupGroupAnnotations, Elaborate}
import circt.stage.ChiselStage
import firrtl.options.Unserializable
import java.nio.file.{Files, Path, Paths}

/** Complete sample owner plus R32F exchange; requires its own emitted model. */
private[system] class OwnerResourceImageFloatProductRecipeHost(profile: NativeAlignmentHostConfig)
    extends NativeAlignmentHostSystemAxi(profile, useBlackBoxes = true, exposePerformance = true) {
  val macroModelsPresent = IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk, !io.s_axi_aresetn) {
    macroModelsPresent := Module(new NativeFourEngineSramModels).io.present
  }
}

object PrepareOwnerResourceImageFloatHost {
  private def factory(tessellationUniforms: Boolean): (NativeAlignmentHostConfig, String, String) = {
    import OwnerResourceProfile._
    if (tessellationUniforms) (tessellationUniformStage, tessellationUniformStageId, tessellationUniformStageName)
    else (imageFloatStage, imageFloatStageId, imageFloatStageName)
  }

  def manifest(
      rtlEmitted: Boolean,
      inlineAssertions: Boolean = false,
      tessellationUniforms: Boolean = false
  ): String = {
    val (p, profile, stage) = factory(tessellationUniforms)
    val render = p.native.renderGraphics(p.graphics)
    val words = p.requiredDiscovery
      .map { case (address, value) =>
        s"""{"address":$address,"value":$value}"""
      }
      .mkString(",")
    val mode = if (inlineAssertions) ",\"assertionMode\":\"inline\"" else ""
    val uniforms = if (p.tessellationUniforms) ",\"tessellationUniforms\":true" else ""
    s"""{"version":2,"ownerResourceVersion":2,"ownerResourceStage":"$stage","profile":"$profile","computeUnits":${p.computeUnits},"frameWidth":${p.graphics.screenWidth},"frameHeight":${p.graphics.screenHeight},"lanes":${p.execution.lanes},"warps":${p.execution.warps},"transactionsPerCu":${p.transactionsPerCu},"axiDataBytes":${p.axiDataBytes},"engines":${p.native.engines},"nativeMaxWidth":${render.screenWidth},"nativeMaxHeight":${render.screenHeight},"pagedCapacity":${p.native.pagedCapacity},"payloadBytes":${p.native.payload.get.capacityBytes},"resourceDiscoveryWords":${p.requiredDiscovery.size},"computeFeatureFlags":${p
        .requiredDiscovery(2)
        ._2},"targetFps":60,"nativeGeometryDraw":${p.native.nativeGeometry},"nativeGeometryTransport":${p.nativeGeometryTransport},"transformFeedback":${p.native.transformFeedback},"nativeSampleShading":${p.native.sampleShading},"nativeSampleTransport":${p.nativeSampleTransport},"nativeFragmentVersions":[6,7],"tessellationFragmentImages":${p.tessellationFragmentImages},"tessellationSampleShading":${p.tessellationSampleShading},"imageFloatExchange":${p.imageFloatExchange},"fullResourceQualification":false,"rtlEmitted":$rtlEmitted,"sourceQualificationRequired":true,"gpuExecution":false,"clockQualified":false,"fpsQualified":false,"requiredDiscovery":[$words]$mode$uniforms}""" + "\n"
  }

  def main(args: Array[String]): Unit = prepare(args, tessellationUniforms = false)

  private[system] def writeFirrtl(gen: => RawModule, path: Path): Unit = {
    val elaborated = new Elaborate().transform(Seq(ChiselGeneratorAnnotation(() => gen)))
    // Both phases use the elaborated Chisel circuit. Serialize it directly
    // so the JVM can exit without building a second whole-circuit IR.
    val annotated = new AddDedupGroupAnnotations().transform(new AddDebugIntrinsics().transform(elaborated))
    val design = annotated.collectFirst { case annotation: ChiselCircuitAnnotation =>
      annotation.elaboratedCircuit
    }.get
    val annotations = (design.annotations ++ annotated).iterator
      .filterNot(_.isInstanceOf[Unserializable])
      .toVector
      .distinct
    val writer = Files.newBufferedWriter(path)
    try design.lazilySerialize(annotations).iterator.foreach(writer.write(_))
    finally writer.close()
  }

  private[system] def prepare(args: Array[String], tessellationUniforms: Boolean): Unit = {
    if (args.sameElements(Array("--describe"))) {
      print(manifest(rtlEmitted = false, tessellationUniforms = tessellationUniforms)); return
    }
    require(
      sys.props.get("opengpu.fullHostQualification").contains("1"),
      "complete owner host emission requires explicit full-host qualification"
    )
    val staged = args.headOption.contains("--firrtl-output")
    require(
      (args.length == 2 || (args.length == 3 && args(2) == "--inline-assertions")) &&
        (args(0) == "--output" || staged),
      "expected a new owner output directory"
    )
    val inlineAssertions = args.length == 3
    val output = Paths.get(args(1)).toAbsolutePath.normalize
    require(!Files.exists(output), "owner host output must be a new directory")
    Files.createDirectories(output)
    val rtl = output.resolve("rtl")
    val (profile, profileId, _) = factory(tessellationUniforms)
    val options = PrepareOwnerResourceSampleHost.firtoolOptions(inlineAssertions)
    val macroDirectory = if (staged) {
      val firrtl = output.resolve("owner.fir")
      writeFirrtl(new OwnerResourceImageFloatProductRecipeHost(profile), firrtl)
      val firtool = firtoolresolver
        .Resolve(BuildInfo.firtoolVersion.get)
        .fold(error => throw new IllegalStateException(error), _.path.getCanonicalPath)
      val command = Seq(
        firtool,
        firrtl.toString,
        "-format=fir",
        "-warn-on-unprocessed-annotations",
        "-output-annotation-file",
        "circt.anno.json"
      ) ++ options ++ Seq("--split-verilog", s"-o=$rtl")
      Files.writeString(output.resolve("firtool-command"), command.mkString("\n") + "\n")
      output
    } else {
      ChiselStage.emitSystemVerilogFile(
        new OwnerResourceImageFloatProductRecipeHost(profile),
        Array("--target-dir", rtl.toString),
        options
      )
      rtl
    }
    Files.writeString(macroDirectory.resolve("NativeFourEngineSramModels.sv"), NativeFourEngineSramModels.source)
    Files.writeString(
      output.resolve("owner-manifest.json"),
      manifest(rtlEmitted = true, inlineAssertions = inlineAssertions, tessellationUniforms = tessellationUniforms)
    )
    if (staged)
      println(s"OWNER_RESOURCE_FIRRTL_WRITTEN profile=$profileId")
    else
      println(
        s"OWNER_RESOURCE_HOST_PREPARED profile=$profileId words=112 frame=640x480 clockQualified=false fpsQualified=false"
      )
  }
}

object PrepareOwnerResourceTessellationUniformHost {
  def main(args: Array[String]): Unit =
    PrepareOwnerResourceImageFloatHost.prepare(args, tessellationUniforms = true)
}
