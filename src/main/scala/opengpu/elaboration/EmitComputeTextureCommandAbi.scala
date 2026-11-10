package opengpu.elaboration

import opengpu.graphics.ComputeTextureCommandMmioRegs

object EmitComputeTextureCommandAbi {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty || args.toSeq == Seq("--check"))
    val r = ComputeTextureCommandMmioRegs
    require(r.controlMask == BigInt("301", 16))
    val fields = Seq(
      "CONTROL",
      "ROOT",
      "CONTEXT",
      "PASS",
      "GENERATION",
      "MAGIC"
    )
    val header = EmitCommandAbi.render(
      generator = "EmitComputeTextureCommandAbi",
      comment = "GTM1 command transport.",
      guard = "OPENGPU_COMPUTE_TEXTURE_COMMAND_ABI_H",
      prefix = "OPENGPU_COMPUTE_TEXTURE_COMMAND",
      base = r.base,
      end = r.end,
      magic = r.magic,
      fields = fields
    )
    EmitCommandAbi.emit(args, "driver/opengpu_compute_texture_command_abi.h", header)
  }
}
