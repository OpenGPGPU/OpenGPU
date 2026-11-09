package opengpu.graphics

import chisel3._
import chisel3.util._

/** Internal image storage IDs, independent of attachment renderability. The
  * first36 IDs preserve the original typed image/attachment byte layouts.
  * Depth occupies low bits; stencil and padding are ignored by depth fetch.
  */
object TextureImageFormat {
  case class Info(name: String,kind: Int,widths: Seq[Int],offsets: Seq[Int],encoding: Int,
      storageBytes: Int=0,depth: Boolean=false,codec: Int=0,stencil: Boolean=false,
      blockWidth: Int=1,blockHeight: Int=1) {
    val bytes=if(storageBytes!=0)storageBytes else (widths.zip(offsets).map{case(w,o)=>w+o}.max+7)/8
    /** Legacy ETC2/EAC 4x4 codecs use the original block4x4 addressing path. */
    val block4x4=codec>=1 && codec<=5
    /** Sampling remains limited to the legacy codecs until the normalized
      * sampling path consumes ASTC blocks; typed fetch decodes codec6 blocks. */
    val decodable=codec<=5
    val fetchable=codec<=6
    val filterable=kind==0 && encoding!=4 && !depth && fetchable
  }
  private def ordinary(name: String,kind: Int,width: Int,channels: Int,encoding: Int): Info=
    Info(name,kind,Seq.tabulate(4)(i=>if(i<channels)width else 0),Seq.tabulate(4)(i=>if(i<channels)i*width else 0),encoding)
  // Encodings0..5 retain TypedColorFormat meanings;6 SNORM8,7 unsigned
  // mini-float,8 shared exponent,9 fixed depth UNORM,10 raw FP32 depth.
  // Compressed outputs:11 UNORM8,12 sRGB8,13 UNORM11,14 SNORM11.
  private def compressed(name: String,channels: Int,codec: Int,encoding: Int): Info=
    Info(name,0,Seq.tabulate(4)(i=>if(i<channels){if(codec>=4)11 else 8}else 0),Seq.fill(4)(0),encoding,
      if(codec==3 || codec==5)16 else 8,false,codec,false,4,4)
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
      compressed("RG11_EAC",2,5,13),compressed("SIGNED_RG11_EAC",2,5,14),
      // Stencil-only storage returns its 8-bit value as an unsigned integer in
      // component0. Nearest sampling only; 2D and 2D-array targets.
      Info("STENCIL_INDEX8",1,Seq(8,0,0,0),Seq(0,0,0,0),1,1,false,0,true)) ++
    (for((w,h)<-Seq((4,4),(5,4),(5,5),(6,5),(6,6),(8,5),(8,6),(8,8),(10,5),(10,6),(10,8),(10,10),(12,10),(12,12));
      srgb<-Seq(false,true))yield{
      val prefix=if(srgb)"COMPRESSED_SRGB8_ALPHA8_ASTC_" else "COMPRESSED_RGBA_ASTC_"
      Info(prefix+s"${w}x${h}_KHR",0,Seq(0,0,0,0),Seq(0,0,0,0),0,16,false,6,false,w,h)
    })
  require(formats.size==93 && formats.map(_.name).distinct.size==93)
  val width=7
  def id(name: String): Int={val n=formats.indexWhere(_.name==name);require(n>=0);n}
  /** Narrow callers still carry the legacy six-bit attachment format. */
  private def index(format: UInt): UInt=format.pad(width)
  def valid(format: UInt): Bool=format.pad(width)<formats.size.U
  def bytes(format: UInt): UInt=VecInit(formats.map(_.bytes.U(5.W)))(index(format))
  def kind(format: UInt): UInt=VecInit(formats.map(_.kind.U(2.W)))(index(format))
  def encoding(format: UInt): UInt=VecInit(formats.map(_.encoding.U(4.W)))(index(format))
  def filterable(format: UInt): Bool=VecInit(formats.map(_.filterable.B))(index(format))
  def depth(format: UInt): Bool=VecInit(formats.map(_.depth.B))(index(format))
  def stencil(format: UInt): Bool=VecInit(formats.map(_.stencil.B))(index(format))
  def decodable(format: UInt): Bool=VecInit(formats.map(_.decodable.B))(index(format))
  def fetchable(format: UInt): Bool=VecInit(formats.map(_.fetchable.B))(index(format))
  def srgb(format: UInt): Bool=VecInit(formats.map(_.name.contains("SRGB").B))(index(format))
  def blockWidth(format: UInt): UInt=VecInit(formats.map(_.blockWidth.U(4.W)))(index(format))
  def blockHeight(format: UInt): UInt=VecInit(formats.map(_.blockHeight.U(4.W)))(index(format))
  def blocked(format: UInt): Bool=block4x4(format) || blockWidth(format)>1.U || blockHeight(format)>1.U
  /** Level views carry zero dimensions for packed and legacy 4x4 formats. */
  def viewBlockWidth(format: UInt): UInt=Mux(block4x4(format),0.U(4.W),Mux(blocked(format),blockWidth(format),0.U(4.W)))
  def viewBlockHeight(format: UInt): UInt=Mux(block4x4(format),0.U(4.W),Mux(blocked(format),blockHeight(format),0.U(4.W)))
  def codec(format: UInt): UInt=VecInit(formats.map(_.codec.U(3.W)))(index(format))
  def block4x4(format: UInt): Bool=VecInit(formats.map(_.block4x4.B))(index(format))
  def targetLegal(format: UInt,target: UInt): Bool={
    val msaa=target===TextureImageTarget.image2DMS.U || target===TextureImageTarget.array2DMS.U
    val stencilOnly=stencil(format)
    Mux(target===TextureImageTarget.buffer.U,TextureBufferFormat.valid(format),
      target<=TextureImageTarget.array2DMS.U &&
        (!stencilOnly || target===TextureImageTarget.image2D.U || target===TextureImageTarget.array2D.U) &&
        !((depth(format) || block4x4(format) || codec(format)===6.U) && target===TextureImageTarget.image3D.U) &&
        !(msaa && (block4x4(format) || codec(format)===6.U)))
  }
  def componentWidth(format: UInt,c: UInt): UInt=VecInit(formats.map(f=>VecInit(f.widths.map(_.U(6.W)))))(index(format))(c)
  def componentOffset(format: UInt,c: UInt): UInt=VecInit(formats.map(f=>VecInit(f.offsets.map(_.U(7.W)))))(index(format))(c)
}

/** GLES 3.2 table8.18. These are existing storage IDs; buffer eligibility does
  * not enable filtering, mipmaps, attachment stores or shader image stores.
  */
object TextureBufferFormat {
  val names: Vector[String]=Vector("R8","R16F","R32F","R8I","R16I","R32I","R8UI","R16UI","R32UI",
    "RG8","RG16F","RG32F","RG8I","RG16I","RG32I","RG8UI","RG16UI","RG32UI",
    "RGB32F","RGB32I","RGB32UI","RGBA8","RGBA16F","RGBA32F","RGBA8I","RGBA16I","RGBA32I",
    "RGBA8UI","RGBA16UI","RGBA32UI")
  val ids: Vector[Int]=names.map(TextureImageFormat.id)
  require(ids.size==30 && ids.distinct.size==30)
  def valid(format: UInt): Bool=ids.map(format===_.U).reduce(_ || _)
}

/** GTF1 enumerates typed component-fetch storage, independently of GTX1's
  * legacy packed sampler mask. A GTX1 typed-fetch device without GTF1 supports
  * only the original36 image IDs. No filtering/comparison feature is implied.
  */
object NativeTextureFormatCapabilities {
  val base=0x220;val wordCount=8;val end=base+4*wordCount
  val magic=BigInt("47544601",16)
  /** GTF1 has one64-bit mask word pair. IDs at or above64 are enumerated by
    * COUNT and are contiguous from0; the feature bit marks that rule. */
  val mask=(BigInt(1)<<math.min(TextureImageFormat.formats.size,64))-1
  val words: Seq[BigInt]=Seq(magic,BigInt(wordCount),BigInt(1),BigInt(TextureImageFormat.width),
    BigInt(TextureImageFormat.formats.size),mask&BigInt("ffffffff",16),(mask>>32)&BigInt("ffffffff",16),BigInt(3))
  def header(prefix: String): String=Seq(
    "TEXTURE_FORMAT_CAPABILITIES"->"0x220u","TEXTURE_FORMAT_CAPABILITY_MAGIC"->"0x47544601u",
    "TEXTURE_FORMAT_CAPABILITY_WORDS"->"8u","TEXTURE_FORMAT_CAPABILITY_VERSION_WORD"->"2u",
    "TEXTURE_FORMAT_CAPABILITY_BITS_WORD"->"3u","TEXTURE_FORMAT_CAPABILITY_COUNT_WORD"->"4u",
    "TEXTURE_FORMAT_CAPABILITY_MASK_LOW_WORD"->"5u","TEXTURE_FORMAT_CAPABILITY_MASK_HIGH_WORD"->"6u",
    "TEXTURE_FORMAT_CAPABILITY_RESERVED_WORD"->"7u","TEXTURE_FORMAT_CAPABILITY_FEATURES_WORD"->"7u",
    "TEXTURE_FORMAT_CAP_IMAGE_SWIZZLE"->"1u","TEXTURE_FORMAT_CAP_COUNT_AUTHORITATIVE"->"2u","TYPED_FETCH_LEGACY_FORMAT_COUNT"->"36u"
  ).map{case(n,v)=>s"#define $prefix$n $v\n"}.mkString
}
