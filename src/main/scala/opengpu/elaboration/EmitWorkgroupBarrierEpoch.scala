package opengpu.elaboration

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
  EmitRtl.emit(
    new SingleCuKernelController(GpuConfig(), gridSlices = sliced),
    args(1),
    Array("-disable-all-randomization", "-strip-debug-info",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"))
}
