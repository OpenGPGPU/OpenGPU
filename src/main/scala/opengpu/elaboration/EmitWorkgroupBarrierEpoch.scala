package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.dispatch.SingleCuKernelController

/** Complete default-width launch/initialization controller timing scope. */
object EmitWorkgroupBarrierEpoch extends App {
  require(args.length == 2, "profile output-directory")
  val sliced = args(0) match {
    case "whole-grid" => false
    case "grid-slice" => true
    case other => throw new IllegalArgumentException(other)
  }
  ChiselStage.emitSystemVerilogFile(
    new SingleCuKernelController(GpuConfig(), gridSlices = sliced),
    Array("--target-dir", args(1)),
    Array("-disable-all-randomization", "-strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"))
}
