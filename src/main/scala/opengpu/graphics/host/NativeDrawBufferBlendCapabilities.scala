package opengpu.graphics

/** GDB1 describes the implemented ordinary blend controls, not GLES conformance
  * or advanced blend equations. Only native typed/binned profiles expose it.
  */
object NativeDrawBufferBlendCapabilities {
  val base=0x400;val wordCount=8;val end=base+wordCount*4
  val magic=BigInt("47444201",16)
  val fields=Seq("MAGIC","WORDS","DRAW_VERSIONS","BUFFERS","FACTORS","EQUATIONS","FEATURES","RESERVED")
  // Independent enable, factors, equations, masks; shared blend color;
  // integer-target bypass and linear-space sRGB blending.
  val words=Seq[BigInt](magic,wordCount,6,4,0x7fff,0x1f,0x7f,0)
  def header(prefix: String): String={
    val result=new StringBuilder(s"#define ${prefix}BLEND_CAPABILITIES 0x${base.toHexString}u\n"+
      s"#define ${prefix}BLEND_CAPABILITY_MAGIC 0x${magic.toString(16)}u\n"+
      s"#define ${prefix}BLEND_CAPABILITY_WORDS ${wordCount}u\n")
    for((name,i)<-fields.zipWithIndex)result.append(s"#define ${prefix}BLEND_CAPABILITY_${name}_WORD ${i}u\n")
    for((name,i)<-Seq("INDEPENDENT_ENABLE","INDEPENDENT_FACTORS","INDEPENDENT_EQUATIONS","INDEPENDENT_MASKS",
      "SHARED_CONSTANT","INTEGER_BYPASS","SRGB_LINEAR").zipWithIndex)
      result.append(s"#define ${prefix}BLEND_CAP_$name ${1<<i}u\n")
    result.toString
  }
}
