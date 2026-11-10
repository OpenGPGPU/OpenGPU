package opengpu.elaboration

import opengpu.graphics.AtomicCounterCommandMmioRegs
import opengpu.core.memory.StorageDescriptorLayout

object EmitAtomicCounterCommandAbi {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty || args.toSeq == Seq("--check"))
    val r = AtomicCounterCommandMmioRegs
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
      generator = "EmitAtomicCounterCommandAbi",
      comment = "GAM1 command transport.",
      guard = "OPENGPU_ATOMIC_COUNTER_COMMAND_ABI_H",
      prefix = "OPENGPU_ATOMIC_COUNTER_COMMAND",
      base = r.base,
      end = r.end,
      magic = r.magic,
      fields = fields,
      descriptorBytes = Some(StorageDescriptorLayout.bytes)
    )
    EmitCommandAbi.emit(args, "driver/opengpu_atomic_counter_command_abi.h", header)
  }
}
