package opengpu.graphics

/** Optional GFI1 discovery accompanies native GVI1. Resource capacities remain
  * in the existing profile record; this block describes fragment input semantics.
  */
object NativeFragmentInputCapabilities {
  val base=0x1c0;val wordCount=4;val end=base+4*wordCount
  val magic=BigInt("47464901",16)
  val versionMask=BigInt(0x1e) // Fragment input versions 1 through 4.
  val features=BigInt(15) // Center, flat, centroid and coherent sample location.
  def words(maxSamples: Int): Seq[BigInt]={
    require(Set(1,2,4).contains(maxSamples))
    val modes=Seq(1,2,4).zipWithIndex.filter(_._1<=maxSamples).foldLeft(BigInt(0)){case(m,(_,i))=>m.setBit(i)}
    Seq(magic,BigInt(wordCount),versionMask,features|(modes<<8))
  }
  def header(prefix: String): String=
    s"#define ${prefix}FRAGMENT_CAPABILITIES 0x${base.toHexString}u\n"+
    s"#define ${prefix}FRAGMENT_CAPABILITY_MAGIC 0x${magic.toString(16)}u\n"+
    s"#define ${prefix}FRAGMENT_CAPABILITY_WORDS ${wordCount}u\n"+
    s"#define ${prefix}FRAGMENT_CAPABILITY_VERSIONS_WORD 2u\n"+
    s"#define ${prefix}FRAGMENT_CAPABILITY_FEATURES_WORD 3u\n"+
    s"#define ${prefix}FRAGMENT_CAP_CENTER 1u\n#define ${prefix}FRAGMENT_CAP_FLAT 2u\n"+
    s"#define ${prefix}FRAGMENT_CAP_CENTROID 4u\n#define ${prefix}FRAGMENT_CAP_SAMPLE_LOCATION 8u\n"+
    s"#define ${prefix}FRAGMENT_CAP_SAMPLE_MODES_SHIFT 8u\n#define ${prefix}FRAGMENT_CAP_SAMPLE_MODES_MASK 0x700u\n"
}
