package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class SampleSelectionHarness extends Module {
  val io=IO(new Bundle {
    val raw=Input(UInt(32.W));val state=Input(new TextureSamplerState)
    val base=Input(UInt(4.W));val last=Input(UInt(4.W));val selection=Output(new TextureLodSelection)
    val count=Input(UInt(16.W));val layer=Output(UInt(16.W))
  })
  io.selection:=TextureSampleSelection.lod(io.raw,io.state,io.base,io.last)
  io.layer:=TextureSampleSelection.layer(io.raw,io.count)
}
class TextureSampleSelectionSpec extends AnyFlatSpec {
  import TextureFilterReference.{bits,value}
  import WindowRecordTest.zero
  it should "select min mag and mip levels across FP32 boundaries with independent scalar arithmetic" in {
    simulate(new SampleSelectionHarness){d=>zero(d.io);var cases=0
      def check(raw: BigInt,base: Int,last: Int,min: Int,mag: Int,lo: Float= -1000f,hi: Float=1000f): Unit={
        d.io.raw.poke(raw.U);d.io.base.poke(base.U);d.io.last.poke(last.U)
        d.io.state.minFilter.poke(min.U);d.io.state.magFilter.poke(mag.U)
        d.io.state.minLod.poke(bits(lo).U);d.io.state.maxLod.poke(bits(hi).U);d.clock.step()
        val valid= !value(raw).isNaN && last>=base && min<=5
        val o=d.io.selection;o.valid.expect(valid.B)
        if(valid){
          val lambda=math.min(hi,math.max(lo,value(raw)));val minifying=lambda>0f
          var first=base;var second=base;var weight=0f
          if(minifying && min>=2){
            if(lambda>=last-base){first=last;second=last}
            else {val floor=math.floor(lambda.toDouble).toInt;val frac=lambda-floor
              if(min>=4){first=base+floor;second=first+1;weight=frac}
              else{first=(base+floor+(if(frac>0.5f)1 else 0)).min(last);second=first}}
          }
          withClue(s"raw=${raw.toString(16)} base=$base last=$last min=$min mag=$mag clamps=$lo,$hi "){
            o.first.expect(first.U);o.second.expect(second.U);o.weight.expect(bits(weight).U)
            o.minifying.expect(minifying.B);o.linear.expect((if(minifying)Set(1,3,5)(min)else mag==1).B)
            // Signed zero is retained by hardware clamps; numeric equality is sufficient here.
            assert(value(o.lod.peek().litValue)==lambda)
          }
        };cases+=1
      }
      val boundaries=(Seq(0f,-0f,Float.MinPositiveValue,-Float.MinPositiveValue,Float.MaxValue,-Float.MaxValue,
        Float.PositiveInfinity,Float.NegativeInfinity)++(0 to 15).flatMap(n=>Seq(n.toFloat,n+0.5f).flatMap(v=>
        Seq(java.lang.Math.nextDown(v),v,java.lang.Math.nextUp(v))))).map(bits).distinct
      for(base<-0 to 15;last<-base to 15;min<-0 to 5;mag<-0 to 1;raw<-boundaries)check(raw,base,last,min,mag)
      for((lo,hi)<-Seq((-2f,-1f),(0f,0f),(0.25f,1.75f),(2f,1f),(Float.NegativeInfinity,Float.PositiveInfinity));
        raw<-boundaries;min<-0 to 5)check(raw,3,9,min,1,lo,hi)
      val random=new scala.util.Random(0x4c4f4453)
      for(n<-0 until 8192){val base=random.nextInt(16);val last=base+random.nextInt(16-base)
        val raw=if(n%2==0)bits(random.nextFloat()*32f-8f)else BigInt(random.nextLong()&0xffffffffL)
        check(raw,base,last,random.nextInt(6),random.nextInt(2))}
      for(raw<-Seq(BigInt(0x7fc12345L),BigInt(0xff800001L)))check(raw,0,15,5,1)
      check(bits(1f),7,6,5,1);check(bits(1f),0,15,6,1)
      info(s"SAMPLE_LOD queries=$cases bases16=true allLastLevels=true filters6=true exactMipTiesLower=true minMagBoundaryZero=true finiteAndInfinityClamps=true reversedClampUpperWins=true")
    }
  }
  it should "round array layers before saturation without narrowing large finite inputs" in {
    simulate(new SampleSelectionHarness){d=>zero(d.io);var cases=0
      def check(raw: BigInt,count: Int): Unit={
        val f=value(raw);require(!f.isNaN && !f.isInfinity)
        val expected=math.floor(f.toDouble+0.5).max(0).min(count-1).toInt
        d.io.raw.poke(raw.U);d.io.count.poke(count.U);d.clock.step()
        withClue(s"layer=${raw.toString(16)} count=$count "){d.io.layer.expect(expected.U)};cases+=1
      }
      for(count<-1 to 65535){val edge=(count-1).toFloat+0.5f
        for(v<-Seq(java.lang.Math.nextDown(edge),edge,java.lang.Math.nextUp(edge)))check(bits(v),count)}
      val random=new scala.util.Random(0x4c415945)
      for(_<-0 until 8192){val raw=BigInt(random.nextLong()&0xffffffffL);if(((raw>>23)&255)!=255)check(raw,1+random.nextInt(65535))}
      for(count<-Seq(1,3,256,65535);v<-Seq(-Float.MaxValue,-0.5f,-0f,0f,Float.MinPositiveValue,0.49999997f,0.5f,0.50000006f,Float.MaxValue))check(bits(v),count)
      info(s"SAMPLE_ARRAY_LAYER queries=$cases counts65535=true halfBoundaryNeighbors=true tiesUpper=true randomFullExponents=true hugeFiniteSaturation=true")
    }
  }
}
