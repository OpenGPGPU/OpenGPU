package opengpu.elaboration

import opengpu.graphics.{ShaderImageCommandMmioRegs, ShaderImageDescriptorLayout}

object EmitShaderImageCommandAbi {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty || args.toSeq == Seq("--check"))
    val r = ShaderImageCommandMmioRegs
    require(r.controlMask == BigInt("ff01", 16))
    val fields = Seq(
      "CONTROL",
      "TABLE_BASE",
      "TABLE_LIMIT_LO",
      "TABLE_LIMIT_HI",
      "CONTEXT",
      "PASS",
      "GENERATION",
      "MAGIC"
    )
    val header = EmitCommandAbi.render(
      generator = "EmitShaderImageCommandAbi",
      comment = "GIM1 command transport.",
      guard = "OPENGPU_SHADER_IMAGE_COMMAND_ABI_H",
      prefix = "OPENGPU_IMAGE_COMMAND",
      base = r.base,
      end = r.end,
      magic = r.magic,
      fields = fields,
      descriptorBytes = Some(ShaderImageDescriptorLayout.bytes)
    )
    EmitCommandAbi.emit(args, "driver/opengpu_shader_image_command_abi.h", header)
  }
}
