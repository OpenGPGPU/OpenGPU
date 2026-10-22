package opengpu.command

import opengpu.config.GpuConfig

/** GCI1 is an optional native hardware contract, not a GLES capability claim. */
object ComputeIndirectCapabilities {
  val base = 0x340
  val wordCount = 8
  val end = base + wordCount * 4
  val magic = BigInt("47434901",16)
  val opcode = 14
  val flags = 0x7f
  def words(config: GpuConfig): Seq[BigInt] = Seq(magic, wordCount, flags,
    opcode, 12, 4, BigInt("ffffffff",16), BigInt(config.lanes) * config.warps)
}
