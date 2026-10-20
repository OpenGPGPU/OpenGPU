package opengpu.graphics

/** GVI1 is an optional, immutable discovery block for native input version 2.
  * Probe its exact magic; older/legacy hosts leave the entire block unmapped.
  * This is separate from shader export ABI and GNC image-address interpretation.
  */
object NativeVertexInputCapabilities {
  val base=0x1b0;val wordCount=4;val end=base+4*wordCount
  val magic=BigInt("47564901",16)
  val formatMask=VertexInputFormat.formats.foldLeft(BigInt(0)){case(mask,(_,id))=>mask.setBit(id)}
  require(VertexInputFormat.formats.forall(_._2<32))
  val words=Seq(magic,BigInt(wordCount),BigInt(VertexInputFormat.version)|(BigInt(VertexInputFormat.width)<<8),formatMask)
  def header(prefix: String): String=
    s"#define ${prefix}INPUT_CAPABILITIES 0x${base.toHexString}u\n"+
    s"#define ${prefix}INPUT_CAPABILITY_MAGIC 0x${magic.toString(16)}u\n"+
    s"#define ${prefix}INPUT_CAPABILITY_WORDS ${wordCount}u\n"+
    s"#define ${prefix}INPUT_CAPABILITY_VERSION_WORD 2u\n#define ${prefix}INPUT_CAPABILITY_VERSION_MASK 0xffu\n"+
    s"#define ${prefix}INPUT_CAPABILITY_FORMAT_BITS_SHIFT 8u\n#define ${prefix}INPUT_CAPABILITY_FORMAT_BITS_MASK 0xff00u\n"+
    s"#define ${prefix}INPUT_CAPABILITY_FORMAT_MASK_WORD 3u\n"
}
