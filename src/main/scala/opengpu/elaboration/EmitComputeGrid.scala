package opengpu.elaboration

import opengpu.config.GpuConfig
import opengpu.dispatch.{ComputeGridDistributor, SharedCuGridDispatcher, JobDispatcher}
import opengpu.graphics.ShaderPayloadConfig

object EmitComputeGrid extends App {
  require(args.length == 2, "profile output-directory")
  val profile = args(0)
  val out = args(1)
  val firtool = Array("-disable-all-randomization", "-strip-debug-info", "--lowering-options=disallowLocalVariables,disallowPackedArrays")
  profile match {
    case "distributor" => EmitRtl.emit(new ComputeGridDistributor(GpuConfig(), 4, 8), out, firtool)
    case "shared" => EmitRtl.emit(new SharedCuGridDispatcher(GpuConfig(), 4, 8, Some(ShaderPayloadConfig())), out, firtool)
    case "slice" => EmitRtl.emit(new JobDispatcher(GpuConfig(), gridSlices = true), out, firtool)
    case _ => throw new IllegalArgumentException(profile)
  }
}
