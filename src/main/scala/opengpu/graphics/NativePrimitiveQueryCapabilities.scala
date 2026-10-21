package opengpu.graphics

/** GQP1 is exposed only when native feedback events, query commands and
  * translated result-record memory are all connected.
  */
object NativePrimitiveQueryCapabilities {
  val base=0x280;val wordCount=8;val end=base+wordCount*4
  val magic=BigInt("47515001",16)
  val opcode=13;val actions=4;val opcodeHighBit=16
  val activeContexts=8;val countBits=64;val apiBits=32
  // Saturating64, clamping32, full-root namespaces, GPU completed records,
  // acknowledged source primitives, drain-before-reset.
  val flags=63
  val words: Seq[BigInt]=Seq(magic,wordCount,
    BigInt(opcode)|(BigInt(actions)<<8)|(BigInt(opcodeHighBit)<<16),
    PrimitiveQueryRecord.magic,PrimitiveQueryRecord.bytes,
    BigInt(activeContexts)|(BigInt(countBits)<<8)|(BigInt(apiBits)<<16),
    PrimitiveQueryRecord.target,flags)
}
