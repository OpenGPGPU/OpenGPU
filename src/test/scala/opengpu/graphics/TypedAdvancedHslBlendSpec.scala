package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private[graphics] object TypedAdvancedHslBlendReference {
  def bits(v: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private def max3(c: Seq[Float]): Float=math.max(c(0),math.max(c(1),c(2)))
  private def min3(c: Seq[Float]): Float=math.min(c(0),math.min(c(1),c(2)))
  private def lum(c: Seq[Float]): Float={
    val m0=0.3f*c(0);val m1=0.59f*c(1);val s=m0+m1;val m2=0.11f*c(2);s+m2
  }
  private def sat(c: Seq[Float]): Float=max3(c)-min3(c)
  private def setSat(base: Seq[Float],target: Float): Seq[Float]={
    val mn=min3(base);val sb=sat(base)
    if(sb==0f)Seq.fill(3)(0f)else base.map(v=>((v-mn)*target)/sb)
  }
  private def clipColor(color: Seq[Float]): Seq[Float]={
    val l=lum(color);val mn=min3(color);val mx=max3(color)
    val fa=if(mn<0f){if(l-mn!=0f)l/(l-mn)else 1f}else 1f
    var c=color.map(v=>l+(v-l)*fa)
    val fb=if(mx>1f){if(mx-l!=0f)(1f-l)/(mx-l)else 1f}else 1f
    c=c.map(v=>l+(v-l)*fb)
    c
  }
  private def setLum(base: Seq[Float],l: Float): Seq[Float]={
    val lb=lum(base);clipColor(base.map(v=>v+(l-lb)))
  }
  def expected(source: Seq[Float],destination: Seq[Float],equation: Int): Seq[BigInt]={
    def unpremultiply(color: Float,alpha: Float): Float=if(alpha==0f)0f else color/alpha
    val as=source(3);val ad=destination(3)
    val cs=(0 until 3).map(c=>unpremultiply(source(c),as)).toSeq
    val cd=(0 until 3).map(c=>unpremultiply(destination(c),ad)).toSeq
    val f=equation match{
      case 0=>setLum(setSat(cs,sat(cd)),lum(cd))
      case 1=>setLum(setSat(cd,sat(cs)),lum(cd))
      case 2=>setLum(cs,lum(cd))
      case _=>setLum(cd,lum(cs))
    }
    val p0=as*ad;val p1=1f-ad;val p2=1f-as
    val rgb=(0 until 3).map{c=>
      val t0=f(c)*p0;val t1=source(c)*p1;val t01=t0+t1;val t2=destination(c)*p2
      bits(t01+t2)
    }
    val alpha=(as+ad)-p0
    rgb.toSeq:+bits(alpha)
  }

}

class TypedAdvancedHslBlendSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TypedAdvancedHslBlendReference.{bits,expected}
  behavior of "TypedAdvancedHslBlend"
  it should "evaluate every non-separable KHR blend mode with KHR composition" in {
    simulate(new TypedAdvancedHslBlend) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(cs: Seq[Float],cd: Seq[Float],as: Float,ad: Float,equation: Int): Unit={
        tag=(tag+1)&0xff
        val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.equation.poke(equation.U)
        for(c<-0 until 3){q.source(c).poke(bits(cs(c)*as).U);q.destination(c).poke(bits(cd(c)*ad).U)}
        q.source(3).poke(bits(as).U);q.destination(3).poke(bits(ad).U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<8000){d.clock.step();n+=1}
        assert(n<8000,s"HSL blend timeout tag=$tag")
        d.io.out.bits.tag.expect(tag.U);d.io.out.bits.status.expect(0.U)
        val want=expected(cs.map(_*as):+as,cd.map(_*ad):+ad,equation)
        for(ci<-0 until 4){val got=d.io.out.bits.rgba(ci).peek().litValue
          assert(got==want(ci),s"eq=$equation cs=$cs cd=$cd as=$as ad=$ad component=$ci got=0x${got.toString(16)} want=0x${want(ci).toString(16)}")}
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      val triples=Seq(Seq(0.25f,0.5f,0.75f),Seq(0.5f,0f,0.5f),Seq(0.75f,0.25f,0f),Seq(0f,0f,0f),
        Seq(1f,1f,1f),Seq(0.5f,0.5f,0.5f),Seq(0.25f,0.25f,0.75f),Seq(0.75f,0.75f,0.25f))
      val alphas=Seq((1f,1f),(1f,0f),(0.5f,0.5f),(0.25f,0.75f))
      var cases=0
      for(equation<-0 until TypedAdvancedHslEquation.supported;cs<-triples;cd<-triples;(as,ad)<-alphas){
        run(cs,cd,as,ad,equation);cases+=1
      }
      info(s"ADVANCED_HSL_EQUATIONS cases=$cases modes=4 setLumSat=true clipColor=true premultipliedComposition=true")
    }
  }
  it should "hold results and cancel into a completed result" in {
    simulate(new TypedAdvancedHslBlend) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def start(tag: Int): Unit={
        val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.equation.poke(0.U)
        for(c<-0 until 3){q.source(c).poke(bits(0.5f).U);q.destination(c).poke(bits(0.25f).U)}
        q.source(3).poke(bits(1f).U);q.destination(3).poke(bits(1f).U)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      }
      start(51);d.io.cancel.poke(true.B)
      var n=0;while(!d.io.out.valid.peek().litToBoolean && n<8000){d.clock.step();n+=1}
      assert(n<8000);d.io.out.bits.cancelled.expect(true.B);d.io.out.bits.tag.expect(51.U)
      d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      start(52)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<8000){d.clock.step();n+=1}
      assert(n<8000);val held=d.io.out.bits.peek().litValue
      for(_<-0 until 4){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      info("ADVANCED_HSL_HOLD immutable=true cancelCompletes=true")
    }
  }
}
