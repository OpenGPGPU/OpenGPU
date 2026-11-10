package opengpu.elaboration

import opengpu.graphics.ComputeUniformCommandMmioRegs

object EmitComputeUniformCommandAbi {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty || args.toSeq == Seq("--check"))
    val r = ComputeUniformCommandMmioRegs
    require(r.controlMask == BigInt("301", 16))
    val fields = Seq(
      "CONTROL",
      "ROOT",
      "CONTEXT",
      "PASS",
      "GENERATION",
      "PRIVATE_BASE",
      "PRIVATE_LIMIT_LO",
      "PRIVATE_LIMIT_HI",
      "MAGIC"
    )
    val header = EmitCommandAbi.render(
      generator = "EmitComputeUniformCommandAbi",
      comment = "GUM1 command transport.",
      guard = "OPENGPU_COMPUTE_UNIFORM_COMMAND_ABI_H",
      prefix = "OPENGPU_COMPUTE_UNIFORM_COMMAND",
      base = r.base,
      end = r.end,
      magic = r.magic,
      fields = fields
    )
    EmitCommandAbi.emit(args, "driver/opengpu_compute_uniform_command_abi.h", header)
  }
}
