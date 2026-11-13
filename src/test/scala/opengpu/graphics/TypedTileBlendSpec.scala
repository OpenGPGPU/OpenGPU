package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedTileBlendSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TypedColorReference.{encode,fp,value}
  private val destination=Vector(0.375,0.125,0.25,0.75).map(fp)
  private val finite=Vector(-0.25,1.25,0.5,1.5).map(fp)
  private val negative=Vector(-0.25,-0.5,-1.0,-0.5).map(fp)
  private val nanColor=Vector(BigInt("7fc01234",16),fp(Double.PositiveInfinity),fp(Double.NegativeInfinity),fp(Double.PositiveInfinity))
  private val nanAlpha=Vector(BigInt("7f800001",16),BigInt("ffc01234",16),fp(Double.NegativeInfinity),BigInt("7fc01234",16))
  private def expected(name: String,source: Vector[BigInt],equation: Int): Vector[BigInt]={
    val dst=TypedBlendReference.decode(name,encode(name,destination)._1).map(x=>value(x).toFloat)
    val src=source.map(x=>value(x).toFloat)
    (if(equation<11)TypedAdvancedColorBlendReference.expected(src,dst,equation)
      else TypedAdvancedHslBlendReference.expected(src,dst,equation-11)).toVector
  }
  private class Driver(d: TypedTileBlend){
    zero(d.io);d.advanced.poke(false.B);d.equation.poke(0.U)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var requests=0
    def run(name: String,source: Vector[BigInt],equation: Int=0,cancelAt: Int= -1,advanced: Boolean=true): Vector[BigInt]={
      val tag=requests&255;val packed=encode(name,destination)._1
      // The reference format names and numeric clamp are independent of the
      // RTL encoding lookup and bit-level sign/NaN/limit predicates.
      val effective=if(name.endsWith("F"))source else source.map(TypedBlendReference.clamp)
      val want=if(advanced)expected(name,effective,equation)
        else TypedBlendReference.blend(name,source,packed,TypedBlendReference.State())
      val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.format.poke(TypedColorFormat.id(name).U)
      q.source.zip(source).foreach{case(a,b)=>a.poke(b.U)};q.destination.poke(packed.U)
      TypedBlendReference.poke(q.state,TypedBlendReference.State())
      d.advanced.poke(advanced.B);d.equation.poke(equation.U)
      d.io.in.ready.expect(true.B);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      // Format, source and sidebands must all come from the accepted snapshot.
      zero(q);q.format.poke(63.U);q.tag.poke(255.U);d.advanced.poke(false.B);d.equation.poke(15.U)
      var clocks=0;var cancelled=false;val limit=if(advanced)10000 else 1000
      while(!d.io.out.valid.peek().litToBoolean && clocks<limit){
        val cancel=clocks==cancelAt;d.io.cancel.poke(cancel.B);cancelled=cancelled || cancel
        d.clock.step();clocks+=1
      }
      assert(clocks<limit && cancelled==(cancelAt>=0),s"tile blend fixture did not reach response/cancel format=$name equation=$equation clocks=$clocks")
      d.io.cancel.poke(false.B);d.io.out.bits.tag.expect(tag.U);d.io.out.bits.status.expect(0.U)
      d.io.out.bits.cancelled.expect(cancelled.B)
      val observed=d.io.out.bits.rgba.map(_.peek().litValue).toVector
      assert(observed==want,s"tile blend source policy differs format=$name equation=$equation source=$source got=$observed expected=$want")
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();d.io.out.valid.expect(true.B);d.io.in.ready.expect(false.B)
        assert(d.io.out.bits.peek().litValue==held,"late cancellation changed an offered tile blend result")}
      d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      d.io.busy.expect(false.B);d.io.in.ready.expect(true.B);requests+=1
      observed
    }
  }
  it should "clamp normalized advanced sources without gamma decoding them and preserve all floating-point formats" in {
    simulate(new TypedTileBlend){d=>val t=new Driver(d)
      val formats=TypedColorFormat.formats.filter(_.kind==0).map(_.name)
      val normalized=formats.filterNot(_.endsWith("F"));val floating=formats.filter(_.endsWith("F"))
      assert(normalized.size==9 && floating.size==8)
      for(name<-normalized;source<-Vector(finite,negative,nanColor,nanAlpha);equation<-Seq(0,11)){
        val got=t.run(name,source,equation)
        if(source==finite && equation==0)assert(got!=expected(name,source,equation),"source-clamp fixture did not distinguish the unclamped path")
      }
      for(name<-floating;source<-Vector(finite,negative)){
        val got=t.run(name,source)
        assert(got!=expected(name,source.map(TypedBlendReference.clamp),0),"floating fixture did not distinguish source clamping")
      }
      val linear=Vector(0.125,0.25,0.375,0.75).map(fp)
      val wrongGamma=linear.zipWithIndex.map{case(raw,c)=>
        if(c==3)raw else {val v=value(raw);fp(if(v<=0.04045)v/12.92 else math.pow((v+0.055)/1.055,2.4))}}
      assert(expected("SRGB8_ALPHA8",linear,0)!=expected("SRGB8_ALPHA8",wrongGamma,0))
      t.run("SRGB8_ALPHA8",linear)
      for(name<-Seq("RGBA8","SRGB8_ALPHA8","RGBA32F"))t.run(name,finite,advanced=false)
      info(s"ADVANCED_TILE_SOURCE formats=17 normalized=9 floating=8 requests=${t.requests} outOfRangeRGBAndAlpha=true NaNZeroPolicy=true linearSourceSRGB=true ordinaryScalarReference=true immutableSnapshot=true")
    }
  }
  it should "retain clamped snapshots through early and arithmetic cancellation held results and healthy reuse" in {
    simulate(new TypedTileBlend){d=>val t=new Driver(d)
      for(cut<-Seq(0,20,80)){
        t.run("SRGB8_ALPHA8",finite,equation=8,cancelAt=cut)
        t.run("RGBA16F",finite)
      }
      info("ADVANCED_TILE_SOURCE_DRAIN singlePulseCancelCuts=3 heldResultImmutable=true exactCancelledValues=true healthyReuse=true")
    }
  }
}
