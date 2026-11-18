package opengpu.graphics

/** GQV1 is present only on a host with the complete native query path enabled. */
object NativeVisibilityQueryCapabilities {
  val base = 0x240; val wordCount = 8; val end = base + wordCount * 4
  val magic = BigInt("47515601", 16)
  val activeContexts = 8
  val opcodeHighBit = 16
  // Word6: format version, opcode extension bit, value bit, available bit.
  val completionFormat = BigInt(1) | (BigInt(opcodeHighBit) << 8) | (BigInt(1) << 24)
  val words: Seq[BigInt] = Seq(magic, wordCount, BigInt("0b0a0908", 16),
    VisibilityQueryRecord.magic, VisibilityQueryRecord.bytes, activeContexts,
    completionFormat, BigInt(31))
}
