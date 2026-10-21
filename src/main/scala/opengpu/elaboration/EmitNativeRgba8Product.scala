package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.MobileGpuConfig
import opengpu.graphics._
import opengpu.system.GpuHostSystemAxi

/** Full-capacity structural gate, separate from reduced simulation profiles.
  * This emits hardware, not a rendered 720p frame or a timing qualification.
  */
object EmitNativeRgba8Product {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "EmitNativeRgba8Product <target-dir>")
    val mobile=MobileGpuConfig()
    val payload=ShaderPayloadConfig(capacityBytes=mobile.payloadBytes,
      maxPayloadBytes=16*1024,owners=mobile.tileEngines+1,ports=8)
    val native=NativeBinnedGraphicsConfig(
      storage=TypedTileColorConfig(mobile.tileBytesPerEngine),engines=mobile.tileEngines,
      pageBytes=mobile.binPageBytes,writeCredits=mobile.binWriteCredits,
      tileSlots=mobile.binTileSlots,pagedCapacity=mobile.binDirectoryTiles,
      payload=Some(payload),groupedTexture=true)
    ChiselStage.emitSystemVerilogFile(new GpuHostSystemAxi(
      GraphicsConfig(screenWidth=1280,screenHeight=720),mobile.execution,
      numComputeUnits=mobile.computeUnits,fragCore=true,
      sharedGraphics=true,parallelGraphics=true,useBlackBoxes=true,
      memoryAxiDataBytes=mobile.axiBytes,nativeBinnedGraphics=Some(native)),
      Array("--target-dir",args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
