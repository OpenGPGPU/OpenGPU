package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

/** Complete geometry/compute/feedback/sample source composition; requires fresh qualification. */
private[system] class OwnerResourceSampleProductRecipeHost extends NativeAlignmentHostSystemAxi(
    OwnerResourceProfile.sampleStage, useBlackBoxes=true, exposePerformance=true) {
  val macroModelsPresent=IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk,!io.s_axi_aresetn) {
    macroModelsPresent:=Module(new NativeFourEngineSramModels).io.present
  }
}

object PrepareOwnerResourceSampleHost {
  def manifest(rtlEmitted: Boolean): String = {
    val p=OwnerResourceProfile.sampleStage
    val render=p.native.renderGraphics(p.graphics)
    val words=p.requiredDiscovery.map { case (address,value) =>
      s"""{"address":$address,"value":$value}"""
    }.mkString(",")
    s"""{"version":2,"ownerResourceVersion":2,"ownerResourceStage":"geometry-compute-sample","profile":"${OwnerResourceProfile.sampleStageId}","computeUnits":${p.computeUnits},"frameWidth":${p.graphics.screenWidth},"frameHeight":${p.graphics.screenHeight},"lanes":${p.execution.lanes},"warps":${p.execution.warps},"transactionsPerCu":${p.transactionsPerCu},"axiDataBytes":${p.axiDataBytes},"engines":${p.native.engines},"nativeMaxWidth":${render.screenWidth},"nativeMaxHeight":${render.screenHeight},"pagedCapacity":${p.native.pagedCapacity},"payloadBytes":${p.native.payload.get.capacityBytes},"resourceDiscoveryWords":${p.requiredDiscovery.size},"computeFeatureFlags":${p.requiredDiscovery(2)._2},"targetFps":60,"nativeGeometryDraw":${p.native.nativeGeometry},"nativeGeometryTransport":${p.nativeGeometryTransport},"transformFeedback":${p.native.transformFeedback},"nativeSampleShading":${p.native.sampleShading},"nativeSampleTransport":${p.nativeSampleTransport},"nativeFragmentVersions":[6,7],"tessellationFragmentImages":${p.tessellationFragmentImages},"tessellationSampleShading":${p.tessellationSampleShading},"fullResourceQualification":false,"rtlEmitted":$rtlEmitted,"sourceQualificationRequired":true,"gpuExecution":false,"clockQualified":false,"fpsQualified":false,"requiredDiscovery":[$words]}""" + "\n"
  }
  def main(args: Array[String]): Unit = {
    if (args.sameElements(Array("--describe"))) { print(manifest(rtlEmitted=false)); return }
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "complete owner host emission requires explicit full-host qualification")
    require(args.length==2 && args(0)=="--output", "usage: PrepareOwnerResourceSampleHost --output NEW_DIRECTORY")
    val output=Paths.get(args(1)).toAbsolutePath.normalize
    require(!Files.exists(output), "owner host output must be a new directory")
    Files.createDirectories(output)
    val rtl=output.resolve("rtl")
    ChiselStage.emitSystemVerilogFile(new OwnerResourceSampleProductRecipeHost,
      Array("--target-dir",rtl.toString),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
    Files.writeString(rtl.resolve("NativeFourEngineSramModels.sv"),NativeFourEngineSramModels.source)
    Files.writeString(output.resolve("owner-manifest.json"),manifest(rtlEmitted=true))
    val p=OwnerResourceProfile.sampleStage
    println(s"OWNER_RESOURCE_HOST_PREPARED profile=${OwnerResourceProfile.sampleStageId} words=${p.requiredDiscovery.size} frame=640x480 clockQualified=false fpsQualified=false")
  }
}
