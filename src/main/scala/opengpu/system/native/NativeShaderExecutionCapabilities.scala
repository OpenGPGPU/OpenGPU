package opengpu.system

/** Native shared-CU admitted instruction limits. This record describes
  * cooperative fetch-boundary interruption, not a cycle timeout for memory.
  */
object NativeShaderExecutionCapabilities {
  val base = 0x300
  val wordCount = 8
  val quotaRegister = 0x320
  val end = 0x324
  val magic = BigInt("47534c01", 16)
  val defaultQuota = 1 << 20
  // Per-warp quota, kernel snapshot, command-reset interruption, attributed
  // stop causes and nonzero native quota policy.
  val flags = 31
  val words: Seq[BigInt] = Seq(magic, wordCount, flags, 32, defaultQuota,
    BigInt("04051918", 16), quotaRegister, opengpu.graphics.GpuCommandMmioRegs.RESET)
}
