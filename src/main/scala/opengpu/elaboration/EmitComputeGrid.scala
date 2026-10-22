package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.dispatch.{ComputeGridDistributor, SharedCuGridDispatcher, JobDispatcher}
import opengpu.graphics.ShaderPayloadConfig

object EmitComputeGrid extends App {
  require(args.length == 2, "profile output-directory")
  val profile = args(0)
  val out = Array("--target-dir",args(1))
  val firtool = Array("-disable-all-randomization", "-strip-debug-info", "--lowering-options=disallowLocalVariables,disallowPackedArrays")
  profile match {
    case "distributor" => ChiselStage.emitSystemVerilogFile(new ComputeGridDistributor(GpuConfig(),4,8),out,firtool)
    case "shared" => ChiselStage.emitSystemVerilogFile(new SharedCuGridDispatcher(GpuConfig(),4,8,Some(ShaderPayloadConfig())),out,firtool)
    case "slice" => ChiselStage.emitSystemVerilogFile(new JobDispatcher(GpuConfig(),gridSlices=true),out,firtool)
    case _ => throw new IllegalArgumentException(profile)
  }
}
