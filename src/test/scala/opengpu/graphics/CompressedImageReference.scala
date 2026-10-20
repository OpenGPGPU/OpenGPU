package opengpu.graphics

private[graphics] object CompressedImageReference {
  val names=Vector("RGB8_ETC2","SRGB8_ETC2","RGB8_PUNCHTHROUGH_ALPHA1_ETC2","SRGB8_PUNCHTHROUGH_ALPHA1_ETC2",
    "RGBA8_ETC2_EAC","SRGB8_ALPHA8_ETC2_EAC","R11_EAC","SIGNED_R11_EAC","RG11_EAC","SIGNED_RG11_EAC")
  case class Format(codec: Int,signed: Boolean,srgb: Boolean,channels: Int,bytes: Int)
  val formats=Vector(Format(1,false,false,3,8),Format(1,false,true,3,8),Format(2,false,false,4,8),Format(2,false,true,4,8),
    Format(3,false,false,4,16),Format(3,false,true,4,16),Format(4,false,false,1,8),Format(4,true,false,1,8),Format(5,false,false,2,16),Format(5,true,false,2,16))
  def format(id: Int): Format=formats(id-54)
  def decode(id: Int,raw: BigInt,x: Int=1,y: Int=1): Vector[BigInt]={
    val f=format(id);val p=CompressedBlockReference.block(CompressedBlockReference.Query(f.codec,f.signed,raw)).pixels(y*4+x)
    Vector.tabulate(4){c=>if(c>=f.channels){if(c==3)BigInt(0x3f800000)else BigInt(0)}else{
      val n=p(c);val v=if(f.codec>=4)n.toDouble/(if(f.signed)1023.0 else 2047.0)else{
        val v=n/255.0;if(f.srgb && c<3){if(v<=0.04045)v/12.92 else math.pow((v+0.055)/1.055,2.4)}else v}
      TypedColorReference.fp(v)}}
  }
}
