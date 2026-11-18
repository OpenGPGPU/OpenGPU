package opengpu.graphics

/** GRI1 describes native raster versions and the implemented attachment
  * resolution policy. It is not a claim of complete GLES numeric conformance.
  */
object NativeRasterCapabilities {
  val base=0x1d0;val wordCount=8;val end=base+4*wordCount
  val magic=BigInt("47524901",16)
  /** Matches `Fp32RasterState.size` clamp to FP32 `[1.0, 64.0]`. */
  val lineWidthMin=1;val lineWidthMax=64;val lineWidthGranularity=1
  /** Bit0: the same integer range applies to multisample lines. */
  val lineWidthMultisampleSame=1
  val lineWidthWord: BigInt=
    BigInt(lineWidthMin)|(BigInt(lineWidthMax)<<8)|(BigInt(lineWidthGranularity)<<16)|
      (BigInt(lineWidthMultisampleSame)<<24)
  def words(maxSamples: Int): Seq[BigInt]={
    require(Set(1,2,4).contains(maxSamples))
    val modes=Seq(1,2,4).zipWithIndex.filter(_._1<=maxSamples).foldLeft(BigInt(0)){case(m,(_,i))=>m.setBit(i)}
    Seq(magic,BigInt(wordCount),BigInt(6),BigInt(0xff)|(BigInt(0x3e)<<8)|(modes<<16),
      BigInt("37804001",16),BigInt("33c00000",16),BigInt(3),lineWidthWord)
  }
  def header(prefix: String): String=Seq(
    "RASTER_CAPABILITIES"->"0x1d0u","RASTER_CAPABILITY_MAGIC"->"0x47524901u","RASTER_CAPABILITY_WORDS"->"8u",
    "RASTER_CAPABILITY_VERSIONS_WORD"->"2u","RASTER_CAPABILITY_FEATURES_WORD"->"3u",
    "RASTER_VERSION_LEGACY"->"1u","RASTER_VERSION_OFFSET"->"2u",
    "RASTER_CAP_POLYGON_OFFSET"->"1u","RASTER_CAP_FRAG_COORD_DEPTH"->"2u","RASTER_CAP_SAMPLE_DEPTH"->"4u",
    "RASTER_CAP_SHADER_DEPTH_OVERRIDE"->"8u","RASTER_CAP_LINE_POINT_BYPASS"->"16u",
    "RASTER_CAP_IMMUTABLE_DEPTH_FORMAT"->"32u","RASTER_CAP_BIAS_BEFORE_CLAMP"->"64u","RASTER_CAP_INACTIVE_DEPTH_BYPASS"->"128u",
    "RASTER_CAP_DEPTH_FORMATS_SHIFT"->"8u","RASTER_CAP_DEPTH_FORMATS_MASK"->"0x7f00u",
    "RASTER_CAP_SAMPLE_MODES_SHIFT"->"16u","RASTER_CAP_SAMPLE_MODES_MASK"->"0x70000u",
    "RASTER_CAPABILITY_D16_RESOLUTION_WORD"->"4u","RASTER_CAPABILITY_D24_RESOLUTION_WORD"->"5u",
    "RASTER_CAPABILITY_FLOAT_RESOLUTION_WORD"->"6u","RASTER_FLOAT_RESOLUTION_MAX_VERTEX_ULP"->"1u",
    "RASTER_FLOAT_RESOLUTION_MAX_VERTEX_ULP_RTZ"->"2u",
    "RASTER_FLOAT_RESOLUTION_MAX_VERTEX_ULP_RTZ_BOUNDED"->"3u",
    "RASTER_CAPABILITY_LINE_WIDTH_WORD"->"7u",
    "RASTER_LINE_WIDTH_MIN"->s"${lineWidthMin}u","RASTER_LINE_WIDTH_MAX"->s"${lineWidthMax}u",
    "RASTER_LINE_WIDTH_GRANULARITY"->s"${lineWidthGranularity}u",
    "RASTER_LINE_WIDTH_MIN_SHIFT"->"0u","RASTER_LINE_WIDTH_MIN_MASK"->"0xffu",
    "RASTER_LINE_WIDTH_MAX_SHIFT"->"8u","RASTER_LINE_WIDTH_MAX_MASK"->"0xff00u",
    "RASTER_LINE_WIDTH_GRANULARITY_SHIFT"->"16u","RASTER_LINE_WIDTH_GRANULARITY_MASK"->"0xff0000u",
    "RASTER_LINE_WIDTH_FLAGS_SHIFT"->"24u","RASTER_LINE_WIDTH_FLAGS_MASK"->"0xff000000u",
    "RASTER_LINE_WIDTH_MULTISAMPLE_SAME"->"1u"
  ).map{case(n,v)=>s"#define $prefix$n $v\n"}.mkString
}
