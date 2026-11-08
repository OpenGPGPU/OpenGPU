package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedAdvancedColorBlendSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def bits(v: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private def f(b: BigInt): Float=java.lang.Float.intBitsToFloat(b.toInt)

  /** Mirrors the hardware micro-operation order exactly. */
  private def expected(source: Seq[Float],destination: Seq[Float],equation: Int): Seq[BigInt]={
    def unpremultiply(color: Float,alpha: Float): Float=if(alpha==0f)0f else color/alpha
    val as=source(3);val ad=destination(3)
    val mask=0x7fffffffL
    val rgb=(0 until 3).map{c=>
      val cs=unpremultiply(source(c),as);val cd=unpremultiply(destination(c),ad)
      val mul=cs*cd;val sum=cs+cd;val screen=sum-mul
      val b1=(2f*cs)*cd
      val oneCs=1f-cs;val oneCd=1f-cd
      val b2=(2f*oneCs)*oneCd;val c2=1f-b2
      val excl=screen-mul;val diff=cd-cs
      val mode=equation match{
        case 0=>mul
        case 1=>screen
        case 2=>if(!(cd>0.5f))b1 else c2
        case 3=>math.min(cs,cd)
        case 4=>math.max(cs,cd)
        case 5=>if(!(cs>0.5f))b1 else c2
        case 6=>java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(diff)&mask.toInt)
        case 7=>excl
      }
      val p0=as*ad;val p1=1f-ad;val p2=1f-as
      val t0=mode*p0;val t1=source(c)*p1;val t01=t0+t1;val t2=destination(c)*p2
      val r=t01+t2
      bits(r)
    }
    val alpha=(as+ad)-(as*ad)
    rgb.toSeq:+bits(alpha)
  }

  behavior of "TypedAdvancedColorBlend"
  it should "evaluate every supported separable equation with KHR premultiplied composition" in {
    simulate(new TypedAdvancedColorBlend) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(cs: Seq[Float],cd: Seq[Float],as: Float,ad: Float,equation: Int,status: Int=0): Unit={
        tag=(tag+1)&0xff
        val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.equation.poke(equation.U)
        for(c<-0 until 3){q.source(c).poke(bits(cs(c)*as).U);q.destination(c).poke(bits(cd(c)*ad).U)}
        q.source(3).poke(bits(as).U);q.destination(3).poke(bits(ad).U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<4000){d.clock.step();n+=1}
        assert(n<4000,s"advanced blend timeout tag=$tag")
        d.io.out.bits.tag.expect(tag.U);d.io.out.bits.status.expect(status.U)
        if(status==0){
          val want=expected(cs.map(_*as):+as,cd.map(_*ad):+ad,equation)
          for(ci<-0 until 4){val got=d.io.out.bits.rgba(ci).peek().litValue
            assert(got==want(ci),s"eq=$equation cs=$cs cd=$cd as=$as ad=$ad component=$ci got=0x${got.toString(16)} want=0x${want(ci).toString(16)}")}
          d.io.out.bits.cancelled.expect(false.B)
        }else{d.io.out.bits.rgba.foreach(_.expect(0.U))}
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      val values=Seq(0f,0.25f,0.5f,0.75f,1f)
      val alphas=Seq((1f,1f),(1f,0f),(0f,1f),(0.5f,0.5f),(0.5f,1f),(1f,0.5f),(0.25f,0.75f),(0f,0f))
      var cases=0
      for(equation<-0 until TypedAdvancedBlendEquation.supported;v<-values;w<-values;(as,ad)<-alphas){
        val cs=Seq(v,w,(v+w)/2f);val cd=Seq(w,v,(1f-v)/2f)
        run(cs,cd,as,ad,equation);cases+=1
      }
      info(s"ADVANCED_BLEND_EQUATIONS cases=$cases equations=${TypedAdvancedBlendEquation.supported} premultipliedComposition=true perChannel=true")
    }
  }
  it should "reject unsupported equations and honour cancellation and backpressure" in {
    simulate(new TypedAdvancedColorBlend) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def start(equation: Int,tag: Int): Unit={
        val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.equation.poke(equation.U)
        for(c<-0 until 3){q.source(c).poke(bits(0.5f).U);q.destination(c).poke(bits(0.25f).U)}
        q.source(3).poke(bits(1f).U);q.destination(3).poke(bits(1f).U)
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
      }
      for(equation<-TypedAdvancedBlendEquation.supported until 16){
        start(equation,equation);var n=0
        while(!d.io.out.valid.peek().litToBoolean && n<200){d.clock.step();n+=1}
        assert(n<200);d.io.out.bits.status.expect(1.U);d.io.out.bits.tag.expect(equation.U)
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      start(0,77)
      var n=0;while(!d.io.out.valid.peek().litToBoolean && n<4000){d.clock.step();n+=1}
      assert(n<4000);val held=d.io.out.bits.peek().litValue
      for(_<-0 until 5){d.clock.step();assert(d.io.out.bits.peek().litValue==held);d.io.in.ready.expect(false.B)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      start(0,78)
      for(_<-0 until 20)d.clock.step()
      d.io.cancel.poke(true.B)
      n=0;while(!d.io.out.valid.peek().litToBoolean && n<4000){d.clock.step();n+=1}
      assert(n<4000);d.io.out.bits.cancelled.expect(true.B);d.io.out.bits.tag.expect(78.U)
      d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      info("ADVANCED_BLEND_REJECT unsupported8=true heldResult=true cancelFlag=true")
    }
  }
}
