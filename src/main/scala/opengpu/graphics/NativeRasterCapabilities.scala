package opengpu.graphics

/** GRI1 describes native raster versions and the implemented attachment
  * resolution policy. It is not a claim of complete GLES numeric conformance.
  */
object NativeRasterCapabilities {
  val base=0x1d0;val wordCount=8;val end=base+4*wordCount
  val magic=BigInt("47524901",16)
  def words(maxSamples: Int): Seq[BigInt]={
    require(Set(1,2,4).contains(maxSamples))
    val modes=Seq(1,2,4).zipWithIndex.filter(_._1<=maxSamples).foldLeft(BigInt(0)){case(m,(_,i))=>m.setBit(i)}
    Seq(magic,BigInt(wordCount),BigInt(6),BigInt(0xff)|(BigInt(0x3e)<<8)|(modes<<16),
      BigInt("37804001",16),BigInt("33c00000",16),BigInt(1),BigInt(0))
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
    "RASTER_CAPABILITY_FLOAT_RESOLUTION_WORD"->"6u","RASTER_FLOAT_RESOLUTION_MAX_VERTEX_ULP"->"1u"
  ).map{case(n,v)=>s"#define $prefix$n $v\n"}.mkString
}
