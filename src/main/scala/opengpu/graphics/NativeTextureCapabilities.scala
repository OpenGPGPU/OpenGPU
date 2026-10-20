package opengpu.graphics

/** GTX1 describes native fixed-point sampler policy and typed component
  * fetch. It does not advertise floating-coordinate sampling or complete GLES.
  */
object NativeTextureCapabilities {
  val base=0x200;val wordCount=8;val end=base+wordCount*4
  val magic=BigInt("47545801",16)
  val words: Seq[BigInt]=Seq(magic,BigInt(wordCount),BigInt(3),BigInt(0x3ff),
    BigInt(7),BigInt(16),BigInt(16383),BigInt(0x1f))
  def header(prefix: String): String=Seq(
    "TEXTURE_CAPABILITIES"->"0x200u","TEXTURE_CAPABILITY_MAGIC"->"0x47545801u","TEXTURE_CAPABILITY_WORDS"->"8u",
    "TEXTURE_CAPABILITY_POLICY_VERSIONS_WORD"->"2u","TEXTURE_CAPABILITY_FEATURES_WORD"->"3u",
    "TEXTURE_CAPABILITY_WRAP_MODES_WORD"->"4u","TEXTURE_CAPABILITY_FRACTION_BITS_WORD"->"5u",
    "TEXTURE_CAPABILITY_MAX_DIMENSION_WORD"->"6u","TEXTURE_CAPABILITY_FORMATS_WORD"->"7u",
    "SAMPLER_POLICY_LEGACY"->"0u","SAMPLER_POLICY_EXPLICIT"->"1u",
    "SAMPLER_WRAP_REPEAT"->"0u","SAMPLER_WRAP_CLAMP"->"1u","SAMPLER_WRAP_MIRRORED_REPEAT"->"2u",
    "TEXTURE_CAP_REPEAT"->"1u","TEXTURE_CAP_CLAMP"->"2u","TEXTURE_CAP_MIRRORED_REPEAT"->"4u",
    "TEXTURE_CAP_SIGNED_COORDINATES"->"8u","TEXTURE_CAP_INDEPENDENT_AXES"->"16u",
    "TEXTURE_CAP_CAPTURED_BINDING"->"32u","TEXTURE_CAP_TRAPPED_COORDINATE_FAULT"->"64u",
    "TEXTURE_CAP_SCALAR"->"128u","TEXTURE_CAP_VECTOR"->"256u",
    "TEXTURE_CAP_TYPED_COMPONENT_FETCH"->"512u","TYPED_FETCH_BINDINGS_PER_STAGE"->"32u","TYPED_FETCH_FORMAT_COUNT"->s"${TextureImageFormat.formats.size}u"
  ).map{case(n,v)=>s"#define $prefix$n $v\n"}.mkString+NativeTextureFormatCapabilities.header(prefix)
}
