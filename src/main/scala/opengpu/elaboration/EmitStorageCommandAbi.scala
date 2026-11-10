package opengpu.elaboration

import opengpu.graphics.StorageCommandMmioRegs
import opengpu.core.memory.StorageDescriptorLayout

object EmitStorageCommandAbi {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty || args.toSeq == Seq("--check"))
    val r = StorageCommandMmioRegs
    require(r.controlMask == BigInt("301", 16))
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
      generator = "EmitStorageCommandAbi",
      comment = "GSM1 command transport.",
      guard = "OPENGPU_STORAGE_COMMAND_ABI_H",
      prefix = "OPENGPU_STORAGE_COMMAND",
      base = r.base,
      end = r.end,
      magic = r.magic,
      fields = fields,
      descriptorBytes = Some(StorageDescriptorLayout.bytes)
    )
    EmitCommandAbi.emit(args, "driver/opengpu_storage_command_abi.h", header)
  }
}
