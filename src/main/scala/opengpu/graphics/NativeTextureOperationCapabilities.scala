package opengpu.graphics

/** GTO1 describes existing resource-backed scalar/vector texture instructions.
  * GTX1 retains its fixed-coordinate policy contract and GTF1 its storage mask.
  * These are ISA/semantic limits,not an API,shader-lowering or throughput claim.
  * Native host profiles alone expose this immutable read-only block.
  */
object NativeTextureOperationCapabilities {
  val base=0x2c0;val wordCount=16;val end=base+4*wordCount
  val magic=BigInt("47544f01",16);val version=1
  val functionMask=BigInt(0x7c) // 2=packet fetch,3=parameters,4=sample,5=captured fetch,6=size.
  val features=BigInt(0x3f)
  val offsetRange=BigInt("0007fff8",16) // Signed16-bit minimum and maximum: -8,+7.
  val words: Seq[BigInt]=Seq(magic,BigInt(wordCount),BigInt(version),functionMask,functionMask,
    BigInt(0xff),BigInt(15),BigInt(7),BigInt(7),BigInt(3),BigInt(ShaderTextureResources.maxBindings),BigInt(16),BigInt(15),
    BigInt(TextureImageFormat.formats.size),features,offsetRange)
  require(base>=NativePrimitiveQueryCapabilities.end && (base&63)==0 && end<=0x400 && words.size==wordCount)
  def header(prefix: String): String=Seq(
    "TEXTURE_OPERATION_CAPABILITIES"->"0x2c0u",
    "TEXTURE_OPERATION_CAPABILITY_MAGIC"->"0x47544f01u",
    "TEXTURE_OPERATION_CAPABILITY_VERSION"->"1u",
    "TEXTURE_OPERATION_CAPABILITY_WORDS"->"16u",
    "TEXTURE_OPERATION_CAPABILITY_VERSION_WORD"->"2u",
    "TEXTURE_OPERATION_CAPABILITY_SCALAR_FUNCTIONS_WORD"->"3u",
    "TEXTURE_OPERATION_CAPABILITY_VECTOR_FUNCTIONS_WORD"->"4u",
    "TEXTURE_OPERATION_CAPABILITY_PARAMETER_PAIRS_WORD"->"5u",
    "TEXTURE_OPERATION_CAPABILITY_COMPONENTS_WORD"->"6u",
    "TEXTURE_OPERATION_CAPABILITY_SIZE_COMPONENTS_WORD"->"7u",
    "TEXTURE_OPERATION_CAPABILITY_LOD_MODES_WORD"->"8u",
    "TEXTURE_OPERATION_CAPABILITY_STAGES_WORD"->"9u",
    "TEXTURE_OPERATION_CAPABILITY_BINDINGS_WORD"->"10u",
    "TEXTURE_OPERATION_CAPABILITY_PARAMETER_WORDS_WORD"->"11u",
    "TEXTURE_OPERATION_CAPABILITY_TARGETS_WORD"->"12u",
    "TEXTURE_OPERATION_CAPABILITY_FORMAT_COUNT_WORD"->"13u",
    "TEXTURE_OPERATION_CAPABILITY_FEATURES_WORD"->"14u",
    "TEXTURE_OPERATION_CAPABILITY_OFFSETS_WORD"->"15u",
    "TEXTURE_OPERATION_PACKET_FETCH"->"2u",
    "TEXTURE_OPERATION_PARAMETERS"->"3u",
    "TEXTURE_OPERATION_SAMPLE"->"4u",
    "TEXTURE_OPERATION_CAPTURED_FETCH"->"5u",
    "TEXTURE_OPERATION_SIZE"->"6u",
    "TEXTURE_OPERATION_CAP_CAPTURED_FETCH_RELATIVE_LOD"->"1u",
    "TEXTURE_OPERATION_CAP_SIZE_RELATIVE_LOD"->"2u",
    "TEXTURE_OPERATION_CAP_FRAGMENT_QUAD_LOD"->"4u",
    "TEXTURE_OPERATION_CAP_TASK_PARAMETER_BANK"->"8u",
    "TEXTURE_OPERATION_CAP_VECTOR_FAULT_ATOMIC"->"16u",
    "TEXTURE_OPERATION_CAP_METADATA_ONLY_SIZE"->"32u",
    "TEXTURE_OPERATION_LOD_AUTOMATIC"->"0u",
    "TEXTURE_OPERATION_LOD_EXPLICIT"->"1u",
    "TEXTURE_OPERATION_LOD_GRADIENT"->"2u",
    "TEXTURE_OPERATION_STAGE_VERTEX"->"1u",
    "TEXTURE_OPERATION_STAGE_FRAGMENT"->"2u",
    "TEXTURE_OPERATION_OFFSET_MIN_SHIFT"->"0u",
    "TEXTURE_OPERATION_OFFSET_MAX_SHIFT"->"16u",
    "TEXTURE_OPERATION_OFFSET_FIELD_MASK"->"0xffffu"
  ).map{case(n,v)=>s"#define $prefix$n $v\n"}.mkString
}
