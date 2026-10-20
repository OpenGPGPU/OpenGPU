package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureCubeGradientUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TextureGradientReference._
  import TextureFilterReference.bits
  private def ratio(n: BigInt,d: BigInt): (Int,BigInt)={
    require(n>0 && d>0)
    var e=n.bitLength-d.bitLength
    if((if(e>=0)n<(d<<e)else(n<<(-e))<d))e-=1
    val shift=23-e;val numerator=if(shift>=0)n<<shift else n;val denominator=if(shift<0)d<<(-shift)else d
    val q=numerator/denominator;val r=numerator%denominator
    val rounded=q+(if(2*r>denominator || 2*r==denominator && q.testBit(0))1 else 0)
    if(rounded.bitLength>24)(e+1,rounded>>1)else(e,rounded)
  }
  private class Driver(val d: TextureCubeGradientUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var tag=0;var vector=Seq.fill(3)(BigInt(0));var gradient=Seq.fill(2)(Seq.fill(3)(BigInt(0)));var exponents=Set.empty[Int];var exactZeros=0
    var wide=Option.empty[Seq[Seq[Extended]]]
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p,"cube gradient did not drain")}
    def query(v: Seq[BigInt],g: Seq[Seq[BigInt]],extended: Option[Seq[Seq[Extended]]]=None): Unit={tag+=1;vector=v;gradient=g;wide=extended
      d.io.in.bits.vector.zip(v).foreach{case(a,b)=>a.poke(b.U)}
      d.io.in.bits.gradients.zip(g).foreach{case(a,b)=>a.zip(b).foreach{case(x,y)=>x.poke(y.U)}}
      d.io.in.bits.extended.poke(extended.nonEmpty.B)
      extended.foreach(g=>d.io.in.bits.wideGradients.zip(g).foreach{case(a,b)=>a.zip(b).foreach{case(x,y)=>x.negative.poke(y.negative.B);x.exponent.poke(y.e.S);x.significand.poke(y.sig.U)}})
      d.io.in.bits.tag.poke(tag.U);d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(d.io.in.bits)
    }
    def check(cancelled: Boolean=false): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;o.tag.expect(tag.U)
      val invalid=(vector++(if(wide.isEmpty)gradient.flatten else Seq.empty)).exists(v=>((v>>23)&255)==255) || vector.forall(v=>(v&0x7fffffff)==0)
      val malformed=wide.exists(_.flatten.exists(v=>v.e< -149 || v.e>129 || (if(v.sig==0)v.e!=0 else !v.sig.testBit(23))))
      val status=if(cancelled)4 else if(invalid)5 else if(malformed)6 else 0;o.status.expect(status.U)
      if(status==0){
        val v=vector.map(integer);val major=v.map(_.abs).max;val a=v.indexWhere(_.abs==major);val face=2*a+(if(v(a)<0)1 else 0)
        o.face.expect(face.U);o.major.expect((vector(a)&0x7fffffff).U)
        def sc(g: Seq[BigInt]): BigInt=face match{case 0=> -g(2);case 1=>g(2);case 5=> -g(0);case _=>g(0)}
        def tc(g: Seq[BigInt]): BigInt=face match{case 2=>g(2);case 3=> -g(2);case _=> -g(1)}
        val denominator=major*major;val (_,ed,sd)=rounded(denominator,-298)
        for(row<-0 to 1;axis<-0 to 1){
          // Gradient integers use units2^-172, preserving every allowed
          // extended input bit; direction integers still use units2^-149.
          val g=wide.map(_(row).map(x=>(if(x.negative)-x.sig else x.sig)<<(x.e+149))).getOrElse(gradient(row).map(x=>integer(x)<<23))
          val dma=if(v(a)<0)-g(a)else g(a)
          val n=major*(if(axis==0)sc(g)else tc(g))-(if(axis==0)sc(v)else tc(v))*dma
          val expected=if(n==0)(false,0,BigInt(0))else{
            val (negative,en,sn)=rounded(n,-321);val (extra,sig)=ratio(sn,sd);(negative,en-ed-1+extra,sig)
          }
          val r=o.gradients(row)(axis);val actual=(r.negative.peek().litToBoolean,r.exponent.peek().litValue.toInt,r.significand.peek().litValue)
          assert(actual==expected,s"vector=${vector.map(_.toString(16))} gradient=${gradient.map(_.map(_.toString(16)))} row=$row axis=$axis actual=$actual expected=$expected")
          if(n==0){exactZeros+=1}else{
            exponents+=actual._2
            val shift=actual._2-23;val an=if(shift>=0)actual._3<<shift else actual._3
            val ad=if(shift<0)BigInt(1)<<(-shift)else BigInt(1)
            // |actual / exact - 1| <= 4*2^-24, without host FP overflow.
            val error=(an*(2*denominator<<23)-n.abs*ad).abs
            assert((error<<24)<=4*n.abs*ad,s"cube Jacobian relative-error bound failed at tag=$tag")
          }
        }
      }else{o.gradients.foreach(_.foreach(r=>{r.negative.expect(false.B);r.exponent.expect(0.S);r.significand.expect(0.U)}));o.major.expect(0.U);o.face.expect(0.U)}
      val held=o.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "differentiate every cube face with exact cancellation and retain derivatives beyond FP32 and clip exponent ranges" in {
    simulate(new TextureCubeGradientUnit){d=>val t=new Driver(d);var cases=0
      def query(v: Seq[BigInt],g: Seq[Seq[BigInt]]): Unit={t.query(v,g);t.check();cases+=1}
      for(v<-Seq(Seq(1f,0.3f,-0.7f),Seq(-1f,0.3f,-0.7f),Seq(0.3f,1f,-0.7f),Seq(0.3f,-1f,-0.7f),Seq(0.3f,-0.7f,1f),Seq(0.3f,-0.7f,-1f),Seq(1f,1f,1f),Seq(-1f,-1f,-1f))){
        query(v.map(bits),Seq(v,v.map(-_)).map(_.map(bits)))
        query(v.map(bits),Seq(Seq(0.1f,-0.2f,0.3f),Seq(-0.4f,0.5f,-0.6f)).map(_.map(bits)))
      }
      query(Seq[BigInt](0x7f7fffffL,1,1),Seq(Seq[BigInt](1,0,0),Seq[BigInt](0,0x7f7fffffL,0x7f7fffffL)))
      query(Seq[BigInt](1,1,1),Seq(Seq[BigInt](0,0x7f7fffffL,0x7f7fffffL),Seq[BigInt](0x7f7fffffL,0,0)))
      for(e<-0 until 255;axis<-0 to 2){val v=Array.fill(3)(BigInt(1));v(axis)=(BigInt(e)<<23)|0x7fffff
        query(v.toSeq,Seq(Seq[BigInt](1,0x7f7fffffL,0x00800001L),Seq[BigInt](0x80000001L,0x3f800001L,0x7f7fffffL)))
      }
      val random=new scala.util.Random(0x4a41434f)
      for(_<-0 until 1024){val v=Seq.fill(3)(BigInt(random.nextLong()&0xffffffffL));val g=Seq.fill(2)(Seq.fill(3)(BigInt(random.nextLong()&0xffffffffL)))
        if((v++g.flatten).forall(x=>((x>>23)&255)!=255))query(v,g)}
      query(Seq.fill(3)(BigInt(0)),Seq.fill(2)(Seq.fill(3)(BigInt(0))))
      query(Seq[BigInt](0x7f800000L,1,1),Seq.fill(2)(Seq.fill(3)(BigInt(0))))
      query(Seq[BigInt](0x3f800000L,1,1),Seq(Seq[BigInt](0x7fc12345L,0,0),Seq.fill(3)(BigInt(0))))
      for(v<-Seq(Seq(1f,0.3f,-0.7f),Seq(-1f,0.3f,-0.7f),Seq(0.3f,1f,-0.7f),Seq(0.3f,-1f,-0.7f),Seq(0.3f,-0.7f,1f),Seq(0.3f,-0.7f,-1f))){
        def scaled(offset: Int): Seq[Extended]=v.map{x=>val (sign,e,sig)=rounded(integer(bits(x)),-149);Extended(sign,if(sig==0)0 else e+offset,sig)}
        t.query(v.map(bits),Seq.fill(2)(Seq.fill(3)(BigInt(0))),Some(Seq(scaled(128),scaled(-120))));t.check();cases+=1
      }
      val large=Extended(false,128,0xffffff);val small=Extended(false,-149,0x800000);val zero=Extended(false,0,0)
      t.query(Seq.fill(3)(BigInt(1)),Seq.fill(2)(Seq.fill(3)(BigInt(0))),Some(Seq(Seq(zero,large,large),Seq(large,zero,zero))));t.check();cases+=1
      t.query(Seq[BigInt](0x7f7fffffL,1,1),Seq.fill(2)(Seq.fill(3)(BigInt(0))),Some(Seq(Seq(small,zero,zero),Seq(zero,large,large))));t.check();cases+=1
      assert(t.exponents.min< -512 && t.exponents.max>127 && t.exactZeros>0)
      info(s"CUBE_GRADIENT queries=$cases exactRationalJacobian=true roundedPolicyBitExact=true relativeErrorAtMost4Times2PowMinus24=true exponentMin=${t.exponents.min} exponentMax=${t.exponents.max} proportionalZeros=${t.exactZeros} faces6=true tiesXYZ=true finiteExponents255=true heldOutput=true")
    }
  }
  it should "drain accepted numerator and divide work at cancellation cuts and preserve immutable completions" in {
    simulate(new TextureCubeGradientUnit){d=>val t=new Driver(d);var cancelled=0;var held=0
      val v=Seq(1f,0.3f,-0.7f).map(bits);val g=Seq(Seq(0.1f,-0.2f,0.3f),Seq(-0.4f,0.5f,-0.6f)).map(_.map(bits))
      for(cut<-(0 until 96)++Seq(128,160,192,224,256,384)){t.query(v,g);d.clock.step(cut)
        val complete=d.io.out.valid.peek().litToBoolean;d.io.cancel.poke(true.B);t.check(cancelled= !complete)
        if(complete)held+=1 else cancelled+=1
        t.query(v,g);t.check()
      }
      assert(cancelled>0 && held>0)
      info(s"CUBE_GRADIENT_DRAIN cuts102=true cancelled=$cancelled held=$held acceptedDividerNeverFlushed=true exactHealthyReuse102=true")
    }
  }
}
