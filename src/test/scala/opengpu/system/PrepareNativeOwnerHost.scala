package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

/** Emit the same SRAM models and complete target used by product recipes. */
private[system] class NativeOwnerProductRecipeHost extends NativeAlignmentHostSystemAxi(
    NativeOwnerHostProfile.target, useBlackBoxes=true, exposePerformance=true) {
  val macroModelsPresent=IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk,!io.s_axi_aresetn) {
    macroModelsPresent:=Module(new NativeFourEngineSramModels).io.present
  }
}

object PrepareNativeOwnerHost {
  def manifest(rtlEmitted: Boolean): String = {
    val p=NativeOwnerHostProfile.target
    val render=p.native.renderGraphics(p.graphics)
    val words=p.requiredDiscovery.map { case (address,value) =>
      s"""{"address":$address,"value":$value}"""
    }.mkString(",")
    s"""{"version":1,"profile":"${NativeOwnerHostProfile.id}","computeUnits":${p.computeUnits},"frameWidth":${p.graphics.screenWidth},"frameHeight":${p.graphics.screenHeight},"lanes":${p.execution.lanes},"warps":${p.execution.warps},"transactionsPerCu":${p.transactionsPerCu},"axiDataBytes":${p.axiDataBytes},"engines":${p.native.engines},"nativeMaxWidth":${render.screenWidth},"nativeMaxHeight":${render.screenHeight},"pagedCapacity":${p.native.pagedCapacity},"payloadBytes":${p.native.payload.get.capacityBytes},"resourceDiscoveryWords":${p.requiredDiscovery.size},"computeFeatureFlags":${p.requiredDiscovery(2)._2},"targetFps":60,"rtlEmitted":$rtlEmitted,"sourceQualificationRequired":true,"gpuExecution":false,"clockQualified":false,"fpsQualified":false,"requiredDiscovery":[$words]}""" + "\n"
  }
  def main(args: Array[String]): Unit = {
    if (args.sameElements(Array("--describe"))) { print(manifest(rtlEmitted=false)); return }
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "complete owner host emission requires explicit full-host qualification")
    require(args.length==2 && args(0)=="--output", "usage: PrepareNativeOwnerHost --output NEW_DIRECTORY")
    val output=Paths.get(args(1)).toAbsolutePath.normalize
    require(!Files.exists(output), "owner host output must be a new directory")
    Files.createDirectories(output)
    val rtl=output.resolve("rtl")
    ChiselStage.emitSystemVerilogFile(new NativeOwnerProductRecipeHost,
      Array("--target-dir",rtl.toString),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
    Files.writeString(rtl.resolve("NativeFourEngineSramModels.sv"),NativeFourEngineSramModels.source)
    Files.writeString(output.resolve("owner-manifest.json"),manifest(rtlEmitted=true))
    println(s"NATIVE_OWNER_HOST_PREPARED profile=${NativeOwnerHostProfile.id} words=96 frame=640x480 clockQualified=false fpsQualified=false")
  }
}
