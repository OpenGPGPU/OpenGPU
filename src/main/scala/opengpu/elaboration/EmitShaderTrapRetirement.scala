package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.core.trap.ShaderTrapRetirement
import opengpu.core.backend.ScalarBackend
import opengpu.config.GpuConfig

object EmitShaderTrapRetirement {
  def main(args: Array[String]): Unit={
    require(args.length<=2,"EmitShaderTrapRetirement [target-dir] [scalar-backend]")
    require(args.length<2 || args(1)=="scalar-backend")
    ChiselStage.emitSystemVerilogFile(if(args.length==2)new ScalarBackend(GpuConfig())else new ShaderTrapRetirement,
      Array("--target-dir",args.headOption.getOrElse("generated/shader-trap-retirement")),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
