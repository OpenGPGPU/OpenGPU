package opengpu.graphics

import chisel3._
import chisel3.util._

/** Internal image storage IDs, independent of attachment renderability. The
  * first36 IDs preserve the original typed image/attachment byte layouts.
  * Depth occupies low bits; stencil and padding are ignored by depth fetch.
  */
object TextureImageFormat {
  case class Info(name: String,kind: Int,widths: Seq[Int],offsets: Seq[Int],encoding: Int,
      storageBytes: Int=0,depth: Boolean=false,codec: Int=0) {
    val bytes=if(storageBytes!=0)storageBytes else (widths.zip(offsets).map{case(w,o)=>w+o}.max+7)/8
    val block4x4=codec!=0
    val filterable=kind==0 && encoding!=4 && !depth
  }
  private def ordinary(name: String,kind: Int,width: Int,channels: Int,encoding: Int): Info=
    Info(name,kind,Seq.tabulate(4)(i=>if(i<channels)width else 0),Seq.tabulate(4)(i=>if(i<channels)i*width else 0),encoding)
  // Encodings0..5 retain TypedColorFormat meanings;6 SNORM8,7 unsigned
  // mini-float,8 shared exponent,9 fixed depth UNORM,10 raw FP32 depth.
  // Compressed outputs:11 UNORM8,12 sRGB8,13 UNORM11,14 SNORM11.
  private def compressed(name: String,channels: Int,codec: Int,encoding: Int): Info=
    Info(name,0,Seq.tabulate(4)(i=>if(i<channels){if(codec>=4)11 else 8}else 0),Seq.fill(4)(0),encoding,if(codec==3 || codec==5)16 else 8,false,codec)
  val formats: Vector[Info]=TypedColorFormat.formats.map(f=>Info(f.name,f.kind,f.widths,f.offsets,f.encoding)) ++
    (1 to 4).map(n=>ordinary(Seq("R","RG","RGB","RGBA")(n-1)+"8_SNORM",0,8,n,6)) ++
    (for(w<-Seq(8,16,32);k<-Seq(2,1))yield ordinary("RGB"+w+(if(k==2)"I"else"UI"),k,w,3,k)) ++
    Vector(ordinary("SRGB8",0,8,3,5),
      Info("R11F_G11F_B10F",0,Seq(11,11,10,0),Seq(0,11,22,0),7),
      Info("RGB9_E5",0,Seq(9,9,9,0),Seq(0,9,18,0),8,4),
      Info("DEPTH_COMPONENT16",0,Seq(16,0,0,0),Seq(0,0,0,0),9,2,true),
      Info("DEPTH_COMPONENT24",0,Seq(24,0,0,0),Seq(0,0,0,0),9,4,true),
      Info("DEPTH_COMPONENT32F",0,Seq(32,0,0,0),Seq(0,0,0,0),10,4,true),
      Info("DEPTH24_STENCIL8",0,Seq(24,0,0,0),Seq(0,0,0,0),9,4,true),
      Info("DEPTH32F_STENCIL8",0,Seq(32,0,0,0),Seq(0,0,0,0),10,8,true)) ++
    Vector(compressed("RGB8_ETC2",3,1,11),compressed("SRGB8_ETC2",3,1,12),
      compressed("RGB8_PUNCHTHROUGH_ALPHA1_ETC2",4,2,11),compressed("SRGB8_PUNCHTHROUGH_ALPHA1_ETC2",4,2,12),
      compressed("RGBA8_ETC2_EAC",4,3,11),compressed("SRGB8_ALPHA8_ETC2_EAC",4,3,12),
      compressed("R11_EAC",1,4,13),compressed("SIGNED_R11_EAC",1,4,14),
      compressed("RG11_EAC",2,5,13),compressed("SIGNED_RG11_EAC",2,5,14))
  require(formats.size==64 && formats.map(_.name).distinct.size==64)
  val width=6
  def id(name: String): Int={val n=formats.indexWhere(_.name==name);require(n>=0);n}
  def valid(format: UInt): Bool=format<formats.size.U
  def bytes(format: UInt): UInt=VecInit(formats.map(_.bytes.U(5.W)))(format)
  def kind(format: UInt): UInt=VecInit(formats.map(_.kind.U(2.W)))(format)
  def encoding(format: UInt): UInt=VecInit(formats.map(_.encoding.U(4.W)))(format)
  def filterable(format: UInt): Bool=VecInit(formats.map(_.filterable.B))(format)
  def depth(format: UInt): Bool=VecInit(formats.map(_.depth.B))(format)
  def codec(format: UInt): UInt=VecInit(formats.map(_.codec.U(3.W)))(format)
  def block4x4(format: UInt): Bool=VecInit(formats.map(_.block4x4.B))(format)
  def targetLegal(format: UInt,target: UInt): Bool=target<=3.U && !((depth(format) || block4x4(format)) && target===3.U)
  def componentWidth(format: UInt,c: UInt): UInt=VecInit(formats.map(f=>VecInit(f.widths.map(_.U(6.W)))))(format)(c)
  def componentOffset(format: UInt,c: UInt): UInt=VecInit(formats.map(f=>VecInit(f.offsets.map(_.U(7.W)))))(format)(c)
}

/** GTF1 enumerates typed component-fetch storage, independently of GTX1's
  * legacy packed sampler mask. A GTX1 typed-fetch device without GTF1 supports
  * only the original36 image IDs. No filtering/comparison feature is implied.
  */
object NativeTextureFormatCapabilities {
  val base=0x220;val wordCount=8;val end=base+4*wordCount
  val magic=BigInt("47544601",16)
  val mask=(BigInt(1)<<TextureImageFormat.formats.size)-1
  val words: Seq[BigInt]=Seq(magic,BigInt(wordCount),BigInt(1),BigInt(TextureImageFormat.width),
    BigInt(TextureImageFormat.formats.size),mask&BigInt("ffffffff",16),mask>>32,BigInt(1))
  def header(prefix: String): String=Seq(
    "TEXTURE_FORMAT_CAPABILITIES"->"0x220u","TEXTURE_FORMAT_CAPABILITY_MAGIC"->"0x47544601u",
    "TEXTURE_FORMAT_CAPABILITY_WORDS"->"8u","TEXTURE_FORMAT_CAPABILITY_VERSION_WORD"->"2u",
    "TEXTURE_FORMAT_CAPABILITY_BITS_WORD"->"3u","TEXTURE_FORMAT_CAPABILITY_COUNT_WORD"->"4u",
    "TEXTURE_FORMAT_CAPABILITY_MASK_LOW_WORD"->"5u","TEXTURE_FORMAT_CAPABILITY_MASK_HIGH_WORD"->"6u",
    "TEXTURE_FORMAT_CAPABILITY_RESERVED_WORD"->"7u","TEXTURE_FORMAT_CAPABILITY_FEATURES_WORD"->"7u",
    "TEXTURE_FORMAT_CAP_IMAGE_SWIZZLE"->"1u","TYPED_FETCH_LEGACY_FORMAT_COUNT"->"36u"
  ).map{case(n,v)=>s"#define $prefix$n $v\n"}.mkString
}
