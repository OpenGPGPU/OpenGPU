package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureCompressedImageDecoderSpec extends AnyFlatSpec {
  import CompressedBlockReference._
  private class Driver(d: TextureImageFetchDecoder){
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var count=0
    def check(format: Int,raw: BigInt,x: Int,y: Int,expected: Vector[BigInt],hold: Boolean=false): Unit={
      d.io.in.ready.expect(true.B);d.io.in.bits.format.poke(format.U);d.io.in.bits.value.poke(raw.U);d.io.in.bits.blockX.poke(x.U);d.io.in.bits.blockY.poke(y.U);d.io.in.bits.tag.poke((count&255).U)
      d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);d.io.in.bits.format.poke(0.U);d.io.in.bits.value.poke(0.U);d.io.in.bits.blockX.poke(0.U);d.io.in.bits.blockY.poke(0.U)
      var cycles=0;while(!d.io.out.valid.peek().litToBoolean && cycles<50){d.clock.step();cycles+=1};assert(cycles<50)
      d.io.out.bits.tag.expect((count&255).U);d.io.out.bits.status.expect(0.U);d.io.out.bits.colorType.expect(0.U)
      for(c<-0 until 4){val value=d.io.out.bits.rgba(c).peek().litValue
        assert(value==expected(c),s"format=$format raw=${raw.toString(16)} x=$x y=$y c=$c got=${value.toString(16)} expected=${expected(c).toString(16)}")}
      if(hold){val held=d.io.out.bits.peek().litValue;d.clock.step(9);d.io.in.ready.expect(false.B);assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);count+=1
    }
  }
  it should "return exact floating components for every compressed format and every local block coordinate" in {
    val random=new scala.util.Random(0x47544301)
    simulate(new TextureImageFetchDecoder){d=>val t=new Driver(d)
      for(format<-54 until 64;n<-0 until 64){val f=CompressedImageReference.format(format);val raw=BigInt(f.bytes*8,random)
        for(y<-0 until 4;x<-0 until 4)t.check(format,raw,x,y,CompressedImageReference.decode(format,raw,x,y),n==0 && x==0 && y==0)}
      info(s"COMPRESSED_IMAGE_DECODE formats10=true blockCoordinates16=true exactFP32=true sRGBBeforeReturn=true punchAlpha=true defaultComponents=true snapshotAndHeld=true queries=${t.count}")
    }
  }
  it should "normalize every EAC11 reconstruction value independently to FP32 including signed saturation and minus128" in {
    def replicated(index: Int): BigInt=(0 until 16).map(n=>BigInt(index)<<(3*n)).reduce(_|_)
    val queries=for(signed<-Seq(false,true);base<-0 until 256;table<-0 until 16;index<-0 until 8)yield
      Query(4,signed,BigInt(base)|(BigInt(table)<<8)|(0 until 6).map(n=>((replicated(index)>>(8*(5-n)))&255)<<(16+8*n)).reduce(_|_))
    val reference=batch(queries)
    val values=queries.zip(reference).groupBy(_._1.signed).view.mapValues(_.groupBy(_._2.pixels.head.head).view.mapValues(_.head._1).toMap).toMap
    assert(values(false).keySet==(0 to 2047).toSet);assert(values(true).keySet==(-1023 to 1023).toSet)
    simulate(new TextureImageFetchDecoder){d=>val t=new Driver(d)
      for(signed<-Seq(false,true);(n,q)<-values(signed).toVector.sortBy(_._1);rg<-Seq(false,true)){
        val id=60+(if(signed)1 else 0)+(if(rg)2 else 0);val raw=if(rg)q.raw|(q.raw<<64)else q.raw
        val value=TypedColorReference.fp(n.toDouble/(if(signed)1023.0 else 2047.0))
        t.check(id,raw,3,2,Vector(value,if(rg)value else BigInt(0),BigInt(0),BigInt(0x3f800000)))
      }
      info(s"COMPRESSED_IMAGE_EAC_NORMALIZE allUnsigned2048=true allSigned2047=true RAndRG=true exactFP32=true queries=${t.count}")
    }
  }
}
