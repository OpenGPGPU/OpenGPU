package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureGradientLodUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TextureFilterReference.{bits,value}
  private case class Scalar(sig: BigInt=0,e: Int=0,negative: Boolean=false)
  private case class Query(gradients: Seq[Seq[Scalar]],extents: Seq[Int]=Seq(7,5,3),dimensions: Int=2)
  private class Driver(val d: TextureGradientLodUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);var tag=0;var query=Query(Seq.fill(2)(Seq.fill(3)(Scalar())))
    var peakMin=2048;var peakMax= -2048
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p,"gradient LOD did not drain")}
    def submit(q: Query): Unit={tag+=1;query=q
      d.io.in.bits.gradients.zip(q.gradients).foreach{case(a,b)=>a.zip(b).foreach{case(x,y)=>
        x.negative.poke(y.negative.B);x.exponent.poke(y.e.S);x.significand.poke(y.sig.U)}}
      d.io.in.bits.extents.zip(q.extents).foreach{case(a,b)=>a.poke(b.U)};d.io.in.bits.dimensions.poke(q.dimensions.U);d.io.in.bits.tag.poke(tag.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(d.io.in.bits)
    }
    def check(status: Int=0): Float={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;o.status.expect(status.U);o.tag.expect(tag.U)
      var actual=0f
      if(status==0){
        val all=for(row<-0 to 1;a<-0 until query.dimensions if query.gradients(row)(a).sig!=0)yield{
          val g=query.gradients(row)(a);val p=g.sig*query.extents(a);val lead=p.bitLength-1
          (g.e-23+lead,p<<(39-lead))
        }
        val peak=if(all.isEmpty)(0,BigInt(0))else all.maxBy(x=>(x._1,x._2))
        o.zero.expect(all.isEmpty.B);o.peakExponent.expect(peak._1.S);o.peakSignificand.expect(peak._2.U)
        if(all.isEmpty){o.lod.expect("hff800000".U);actual=Float.NegativeInfinity}else{
          peakMin=peakMin.min(peak._1);peakMax=peakMax.max(peak._1)
          val reference=peak._1.toDouble+java.lang.StrictMath.log(peak._2.toDouble/java.lang.Math.scalb(1.0,39))/java.lang.StrictMath.log(2.0)
          actual=value(o.lod.peek().litValue)
          val tolerance=4e-12+java.lang.Math.ulp(reference.toFloat).toDouble/2+2e-13
          assert(math.abs(actual.toDouble-reference)<=tolerance,s"q=$query peak=$peak lod=$actual expected=$reference tolerance=$tolerance")
          if(peak._2==(BigInt(1)<<39))o.lod.expect(bits(peak._1.toFloat).U)
          assert((actual>0f)==(peak._1>0 || peak._1==0 && peak._2>(BigInt(1)<<39)),s"min/mag classification changed at rho=$peak")
        }
      }else{o.lod.expect(0.U);o.peakExponent.expect(0.S);o.peakSignificand.expect(0.U)}
      val held=o.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 2){d.clock.step();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B);actual
    }
  }
  it should "derive bounded-error lambda from exact scaled gradients across all extents and extended exponent extremes" in {
    simulate(new TextureGradientLodUnit){d=>val t=new Driver(d);var cases=0
      def check(g: Seq[Seq[Scalar]],extents: Seq[Int]=Seq(7,5,3),dimensions: Int=2): Unit={t.submit(Query(g,extents,dimensions));t.check();cases+=1}
      val z=Scalar();check(Seq.fill(2)(Seq.fill(3)(z)))
      for(e<-Seq(-1024,-555,278,1023))check(Seq(Seq(Scalar(0x800001,e),z,z),Seq.fill(3)(z)),Seq(1,1,1))
      for(e<-Seq(-1024,-555,-512,-149,-126,-14,-1,0,1,13,127,275,278,1023);sig<-Seq[BigInt](0x800000,0x800001,0xb504f3,0xfffffe,0xffffff);dimensions<-Seq(2,3)){
        val g=Seq(Seq(Scalar(sig,e),z,z),Seq(z,Scalar(sig,-e-1,true),Scalar(sig,e)))
        check(g,dimensions=dimensions)
      }
      for(n<-1 to 16383 by 17){val g=Seq(Seq(Scalar(0x800001,-13),Scalar(0xffffff,-7),z),Seq(Scalar(0x800000,2),z,z))
        check(g,Seq(n,1,1))}
      for(e<- -24 to 24){check(Seq(Seq(Scalar(0x800000,e),z,z),Seq.fill(3)(z)),Seq(1,1,1))}
      val random=new scala.util.Random(0x4c4f4752)
      for(_<-0 until 2048){
        val g=Seq.fill(2)(Seq.fill(3)(if(random.nextInt(8)==0)z else Scalar(BigInt(0x800000|random.nextInt(0x800000)),random.nextInt(834)-555,random.nextBoolean())))
        check(g,Seq.fill(3)(1+random.nextInt(16383)),2+random.nextInt(2))
      }
      // Unused third coordinates and extent must not invalidate a 2D/array/cube rho.
      check(Seq.fill(2)(Seq(z,z,Scalar(1,2047))),Seq(7,5,0))
      assert(t.peakMin<= -555 && t.peakMax>=1023)
      info(s"GRADIENT_LOD queries=$cases scaledMagnitudeExact=true extendedExponentMin=${t.peakMin} extendedExponentMax=${t.peakMax} FP32PackingRNE=true logAbsoluteErrorBelow4eMinus12BeforePacking=true zeroRhoMinusInfinity=true minMagBoundaryPreserved=true dimensions2And3=true unusedAxisIgnored=true heldOutput=true")
    }
  }
  it should "retain monotonic logarithms around powers of two and reject malformed used inputs" in {
    simulate(new TextureGradientLodUnit){d=>val t=new Driver(d);val z=Scalar();var cases=0
      for(e<-Seq(-1,0,1);n<-1 to 3){var previous=Float.NegativeInfinity
        for(sig<-(0x800000 to 0x800100)++(0xffff00 to 0xffffff)){
          t.submit(Query(Seq(Seq(Scalar(sig,e),z,z),Seq.fill(3)(z)),Seq(n,1,1)))
          val actual=t.check();assert(actual>=previous);previous=actual;cases+=1
        }
      }
      t.submit(Query(Seq.fill(2)(Seq.fill(3)(z)),dimensions=1));t.check(1)
      t.submit(Query(Seq.fill(2)(Seq.fill(3)(z)),extents=Seq(0,5,3)));t.check(5)
      for(s<-Seq(Scalar(1,0),Scalar(0x800000,1024),Scalar(0x800000,-1025),Scalar(0,1))){
        t.submit(Query(Seq(Seq(s,z,z),Seq.fill(3)(z))));t.check(6)
      }
      info(s"GRADIENT_LOD_MONOTONIC queries=$cases powersOfTwoAndEndpointNeighbors=true malformedUsedScalarsRejected=true zeroExtentRejected=true")
    }
  }
  it should "cancel every selection logarithm and packing stage before healthy reuse" in {
    simulate(new TextureGradientLodUnit){d=>val t=new Driver(d);val z=Scalar();var cancelled=0;var held=0
      val q=Query(Seq(Seq(Scalar(0xabcdef,127),Scalar(0xffffff,-555),z),Seq(z,z,Scalar(0xeeeeee,275))),dimensions=3)
      for(cut<-0 until 170){t.submit(q);d.clock.step(cut);val complete=d.io.out.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.check(if(complete)0 else 4);if(complete)held+=1 else cancelled+=1
        t.submit(q);t.check()
      }
      assert(cancelled>0 && held>0)
      info(s"GRADIENT_LOD_DRAIN cuts170=true cancelled=$cancelled held=$held noPartialLod=true exactHealthyReuse170=true")
    }
  }
}
