package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class ImageSwizzleHarness extends Module {
  val io=IO(new Bundle {
    val state=Input(new TextureImageSwizzleState);val rgba=Input(Vec(4,UInt(32.W)));val kind=Input(UInt(2.W))
    val legal=Output(Bool());val value=Output(Vec(4,UInt(32.W)))
  })
  io.legal:=TextureImageSwizzle.legal(io.state);io.value:=TextureImageSwizzle(io.rgba,io.kind,io.state)
}
class TextureImageSwizzleSpec extends AnyFlatSpec {
  it should "implement every selector tuple with typed constants while preserving all source component bits" in {
    simulate(new ImageSwizzleHarness){d=>
      val patterns=Seq(Seq[BigInt](0x7fc12345L,0xff800000L,0x80000000L,0x3f800000L),
        Seq[BigInt](0xffffffffL,0x80000001L,0x00000001L,0x7f800000L),Seq[BigInt](0,0,0,0x3f800000L))
      var cases=0
      for(kind<-0 to 2;raw<-patterns){d.io.kind.poke(kind.U);d.io.rgba.zip(raw).foreach{case(a,b)=>a.poke(b.U)}
        for(r<-0 to 5;g<-0 to 5;b<-0 to 5;a<-0 to 5){val selectors=Seq(r,g,b,a)
          d.io.state.version.poke(1.U);Seq(d.io.state.r,d.io.state.g,d.io.state.b,d.io.state.a).zip(selectors).foreach{case(v,s)=>v.poke(s.U)}
          d.clock.step();d.io.legal.expect(true.B)
          val options=raw++Seq(BigInt(0),if(kind==0)BigInt(0x3f800000L)else BigInt(1))
          d.io.value.zip(selectors.map(options)).foreach{case(v,e)=>v.expect(e.U)};cases+=1
        }
        d.io.state.version.poke(0.U);Seq(d.io.state.r,d.io.state.g,d.io.state.b,d.io.state.a).foreach(_.poke(0.U))
        d.clock.step();d.io.legal.expect(true.B);d.io.value.zip(raw).foreach{case(v,e)=>v.expect(e.U)}
      }
      info(s"IMAGE_SWIZZLE exhaustiveTuples1296=true kinds3=true rawPatterns3=true explicitQueries=$cases floatOneAndIntegerOne=true NaNPayloadSignedZeroInfinityAndIntegerBitsPreserved=true legacyIdentity=true")
    }
  }
  it should "validate every encoded version and selector state independently of texture data" in {
    simulate(new ImageSwizzleHarness){d=>d.io.kind.poke(0.U);d.io.rgba.foreach(_.poke(0.U));var cases=0
      for(v<-0 until 4;r<-0 until 8;g<-0 until 8;b<-0 until 8;a<-0 until 8){
        d.io.state.version.poke(v.U);val selectors=Seq(r,g,b,a)
        Seq(d.io.state.r,d.io.state.g,d.io.state.b,d.io.state.a).zip(selectors).foreach{case(x,y)=>x.poke(y.U)}
        d.clock.step();d.io.legal.expect(((v==0 && selectors.forall(_==0)) || (v==1 && selectors.forall(_<6))).B);cases+=1
      }
      info(s"IMAGE_SWIZZLE_ENCODING states=$cases versions4=true selectors8Each=true canonicalLegacyOnly=true unsupportedVersionsAndSelectorsRejected=true")
    }
  }
}
