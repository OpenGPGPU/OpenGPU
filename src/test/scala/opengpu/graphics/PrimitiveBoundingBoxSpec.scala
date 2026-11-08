package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class PrimitiveBoundingBoxSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private def fp(v: Float): BigInt=BigInt(java.lang.Float.floatToRawIntBits(v)&0xffffffffL)
  private val one=fp(1.0f)
  behavior of "PrimitiveBoundingBox"
  it should "publish the default box validate replacements and select patch overrides" in {
    simulate(new PrimitiveBoundingBox) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.validBox.expect(true.B)
      for((v,i)<-Seq(-1f,-1f,-1f,1f).zipWithIndex)d.io.state.min(i).expect(fp(v).U)
      for((v,i)<-Seq(1f,1f,1f,1f).zipWithIndex)d.io.state.max(i).expect(fp(v).U)
      d.io.state.version.expect(1.U)
      def load(min: Seq[BigInt],max: Seq[BigInt],version: Int=1,overrideValid: Boolean=false,
        overrideMin: Seq[BigInt]=Seq.fill(4)(BigInt(0)),overrideMax: Seq[BigInt]=Seq.fill(4)(BigInt(0)),
        status: Int=0): Unit={
        val q=d.io.load.bits;zero(q);q.tag.poke(3.U);q.state.version.poke(version.U)
        for(i<-0 until 4){q.state.min(i).poke(min(i).U);q.state.max(i).poke(max(i).U)
          q.overrideMin(i).poke(overrideMin(i).U);q.overrideMax(i).poke(overrideMax(i).U)}
        q.overrideValid.poke(overrideValid.B)
        d.io.load.valid.poke(true.B);var n=0;while(!d.io.load.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.load.ready.peek().litToBoolean);d.clock.step();d.io.load.valid.poke(false.B)
        d.io.out.valid.expect(true.B);d.io.out.bits.tag.expect(3.U);d.io.out.bits.status.expect(status.U)
        d.io.out.bits.validBox.expect((status==0).B)
        d.io.validBox.expect((status==0).B)
        val held=d.io.out.bits.peek().litValue
        for(_<-0 until 3){d.clock.step();assert(d.io.out.bits.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      }
      val wide=Seq(fp(-2f),fp(0f),fp(-1f),fp(0.5f));val wideMax=Seq(fp(2f),fp(1f),fp(1f),fp(1f))
      load(wide,wideMax)
      for((v,i)<-wide.zipWithIndex)d.io.state.min(i).expect(v.U)
      for((v,i)<-wideMax.zipWithIndex)d.io.state.max(i).expect(v.U)
      load(wide,wideMax,overrideValid=true,overrideMin=Seq.fill(4)(fp(-0.5f)),overrideMax=Seq.fill(4)(fp(0.5f)))
      d.io.state.min(0).expect(fp(-0.5f).U);d.io.state.max(3).expect(fp(0.5f).U)
      load(wide,wideMax,version=2,status=1)
      load(Seq.fill(4)(fp(1f)),Seq.fill(4)(fp(0f)),status=1)
      load(Seq.fill(4)(BigInt("7fc00000",16)),wideMax,status=1)
      load(Seq.fill(4)(BigInt("7f800000",16)),wideMax,status=1)
      info("BOUNDING_BOX_STATE defaultFullFrustum=true versioned=true finiteOrdered=true overridePlane=true invalidRejected=true")
    }
  }
  it should "compute clip bounds and cull only definite disjointness" in {
    simulate(new PrimitiveClipBounds) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def bounds(a: Seq[Float],b: Seq[Float],c: Seq[Float]): (Seq[BigInt],Seq[BigInt],Boolean)={
        val q=d.io.in.bits;zero(q);q.tag.poke(7.U)
        for(i<-0 until 4){q.vertices(0)(i).poke(fp(a(i)).U);q.vertices(1)(i).poke(fp(b(i)).U);q.vertices(2)(i).poke(fp(c(i)).U)}
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);d.io.out.bits.tag.expect(7.U);d.io.out.bits.status.expect(0.U)
        val lo=(0 until 4).map(i=>d.io.out.bits.min(i).peek().litValue)
        val hi=(0 until 4).map(i=>d.io.out.bits.max(i).peek().litValue)
        val usable=d.io.out.bits.usable.peek().litToBoolean
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        (lo,hi,usable)
      }
      val (lo,hi,usable)=bounds(Seq(-1.5f,0.25f,0f,0.5f),Seq(0.5f,-2f,1f,1.5f),Seq(2f,1f,-0.5f,1f))
      assert(lo==Seq(fp(-1.5f),fp(-2f),fp(-0.5f),fp(0.5f)) && hi==Seq(fp(2f),fp(1f),fp(1f),fp(1.5f)) && usable)
      val (_,_,nanUsable)=bounds(Seq(Float.NaN,0f,0f,0f),Seq(0f,0f,0f,0f),Seq(0f,0f,0f,0f))
      assert(!nanUsable)
      info("BOUNDING_BOX_BOUNDS exactMinMax=true fourComponents=true nanUnusable=true")
    }
    simulate(new PrimitiveBoundingBoxCull) { d =>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.box.version.poke(1.U)
      for(c<-0 until 4){d.io.box.min(c).poke(fp(Seq(-1f,-1f,-1f,1f)(c)).U);d.io.box.max(c).poke(one.U)}
      d.io.validBox.poke(true.B)
      def cull(min: Seq[Float],max: Seq[Float],usable: Boolean=true,valid: Boolean=true): Boolean={
        d.io.validBox.poke(valid.B)
        val q=d.io.in.bits;zero(q);q.tag.poke(5.U);q.usable.poke(usable.B)
        for(i<-0 until 4){q.min(i).poke(fp(min(i)).U);q.max(i).poke(fp(max(i)).U)}
        d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        var n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);d.io.out.bits.tag.expect(5.U);d.io.out.bits.status.expect(0.U)
        val c=d.io.out.bits.culled.peek().litToBoolean
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
        c
      }
      val inside=Seq(-0.5f,-0.5f,-0.5f,0.5f);val insideMax=Seq(0.5f,0.5f,0.5f,1f)
      assert(!cull(inside,insideMax),"overlapping primitive must be retained")
      assert(cull(Seq(-3f,-1f,-1f,1f),Seq(-2f,1f,1f,2f)),"left-disjoint primitive culls")
      assert(cull(Seq(-1f,-1f,-1f,2.5f),Seq(1f,1f,1f,3f)),"w-disjoint primitive culls")
      assert(!cull(Seq(-2f,-0.5f,-0.5f,0.5f),Seq(0.5f,0.5f,0.5f,1f)),"straddling primitive stays")
      assert(!cull(Seq(-3f,-1f,-1f,1f),Seq(-2f,1f,1f,2f),usable=false),"unusable bounds never cull")
      assert(!cull(Seq(-3f,-1f,-1f,1f),Seq(-2f,1f,1f,2f),valid=false),"invalid box never culls")
      info("BOUNDING_BOX_CULL disjointAxes4=true straddleRetained=true invalidNeverCulls=true exactTag=true")
    }
  }
}
