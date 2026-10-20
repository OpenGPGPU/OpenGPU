package opengpu.graphics

/** Ordinary shader arithmetic followed by the existing captured sample ISA.
  * Explicit gradients are already projected, as required by GLSL ES3.00 8.7.
  * Exact dyadic inputs make the coordinate/division and sampling oracle
  * independent of any host floating-point rounding approximation.
  */
private[graphics] object NativeProjectiveShaderTestData {
  def fp(x: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(x).toLong&0xffffffffL)
  def addi(rd: Int,rs: Int,n: Int): BigInt=(BigInt(n&4095)<<20)|(BigInt(rs)<<15)|(BigInt(rd)<<7)|0x13
  def constant(rd: Int,n: BigInt): Seq[BigInt]={val hi=(n+2048)>>12;val lo=(n-(hi<<12)).toInt
    Seq((hi<<12)|(BigInt(rd)<<7)|0x37,addi(rd,rd,lo))}
  private def broadcast(v: Int,x: Int): BigInt=(BigInt(0x17)<<26)|(BigInt(1)<<25)|(BigInt(x)<<15)|(BigInt(4)<<12)|(BigInt(v)<<7)|0x57
  private def vfdiv(v: Int): BigInt=(BigInt(0x20)<<26)|(BigInt(1)<<25)|(BigInt(v)<<20)|(BigInt(12)<<15)|(BigInt(1)<<12)|(BigInt(v)<<7)|0x57
  private def moveToFloat(f: Int,x: Int): BigInt=(BigInt(0x78)<<25)|(BigInt(x)<<15)|(BigInt(f)<<7)|0x53
  private def moveToInteger(x: Int,f: Int): BigInt=(BigInt(0x70)<<25)|(BigInt(f)<<15)|(BigInt(x)<<7)|0x53
  private def fdiv(f: Int): BigInt=(BigInt(0x0c)<<25)|(BigInt(12)<<20)|(BigInt(f)<<15)|(BigInt(f)<<7)|0x53
  def operandCode(scalar: Boolean,volume: Boolean,shadow: Boolean,mode: Int,offset: Int,
      load: (Int,Int)=>Seq[BigInt]): Seq[BigInt]={
    require(!(volume && shadow) && mode>=0 && mode<=2 && (offset==0 || offset==15))
    def param(p: Int,a: Int,b: Int): BigInt=(BigInt(3)<<(if(scalar)25 else 26))|
      (if(scalar)BigInt(0)else BigInt(1)<<25)|(BigInt(b)<<20)|(BigInt(a)<<15)|(BigInt(p)<<12)|(if(scalar)0x0b else 0x2b)
    val sentinel=constant(12,fp(0.75f))++Seq(broadcast(0,12),broadcast(31,0))
    val projection=if(scalar){
      val source=Seq(12->1.5f,13->1.5f,14->(if(shadow)0.5f else 1.5f),15->2f).flatMap{case(r,x)=>constant(r,fp(x))}
      source++Seq(moveToFloat(10,12),moveToFloat(11,13),moveToFloat(13,14),moveToFloat(12,15),fdiv(10),fdiv(11),fdiv(13),
        moveToInteger(20,10),moveToInteger(21,11),moveToInteger(22,13),broadcast(10,20),broadcast(11,21),broadcast(13,22))
    }else{
      load(0,10)++load(1,11)++load(2,13)++load(3,12)++Seq(vfdiv(10),vfdiv(11),vfdiv(13))
    }
    // Scalar source22 or vector source13 is projected Z/reference. Unused2D
    // Z is deliberately ignored; it is not fed into the captured tuple.
    val coords=if(scalar)Seq(param(0,20,21),param(1,if(volume)22 else 0,0))
      else Seq(param(0,10,11),param(1,if(volume)13 else 31,31))
    val policy=constant(16,if(mode==1)fp(1f)else BigInt(0))++constant(17,if(mode==2)fp(0.75f)else BigInt(0))++Seq(addi(18,0,offset))
    val rest=if(scalar)Seq(param(2,if(shadow)22 else 0,16),param(3,0,17),param(4,0,0),param(5,0,17),param(6,0,18),param(7,0,0))
      else Seq(broadcast(26,16),broadcast(27,17),broadcast(28,18),param(2,if(shadow)13 else 31,26),param(3,31,27),
        param(4,31,31),param(5,31,27),param(6,31,28),param(7,31,31))
    val control=mode+(if(shadow)4 else 0)
    val sample=if(scalar)Seq(addi(23,0,0),addi(24,0,control),
      (BigInt(4)<<25)|(BigInt(24)<<20)|(BigInt(23)<<15)|(BigInt(25)<<7)|0x0b,broadcast(7,25))
      else Seq(broadcast(29,0),addi(24,0,control),broadcast(30,24),
        (BigInt(4)<<26)|(BigInt(1)<<25)|(BigInt(30)<<20)|(BigInt(29)<<15)|(BigInt(7)<<7)|0x2b)
    sentinel++projection++coords++policy++rest++sample
  }
  def coordinates(scalar: Boolean,step: Float,shadow: Boolean,volume: Boolean): Vector[Vector[Float]]=
    Vector.tabulate(4){l=>val q=if(scalar)2f else (1<<(l+1)).toFloat
      val u=if(scalar)0.75f else if(l%2==0)0.5f-step else 0.5f
      val v=if(scalar)0.75f else if(l<2)0.5f-step else 0.5f
      Vector(u*q,v*q,(if(shadow)0.25f else if(volume)u else 123f)*q,q)}
  def expected(scalar: Boolean,step: Float,volume: Boolean,shadow: Boolean,mode: Int,offset: Int,lane: Int,fragment: Boolean): Float={
    if(offset!=0)0f
    else {val u=if(scalar)0.75f else if(lane%2==0)0.5f-step else 0.5f
      val v=if(scalar)0.75f else if(lane<2)0.5f-step else 0.5f
      val z=if(scalar)0.75f else u
      val linear=mode!=0 || fragment && !scalar && step>0.5f
      def axis(x: Float): Float={val repeat=x-math.floor(x.toDouble).toFloat
        if(!linear){if(repeat>=0.5f)1f else 0f}else if(repeat==0.75f)1f else if(repeat==0.5f)0.5f else 0f}
      axis(u)*axis(v)*(if(volume)axis(z)else 1f)
    }
  }
}
