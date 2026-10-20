package opengpu.graphics

import chisel3._
import java.io.ByteArrayInputStream
import java.nio.file.{Files,Paths}
import scala.sys.process._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private[graphics] object CompressedBlockReference {
  case class Query(codec: Int,signed: Boolean,raw: BigInt)
  case class PixelBlock(mode: Int,pixels: Vector[Vector[Int]])
  private lazy val binary={
    val base=Paths.get("src/test/resources/opengpu/texture/detex");val out=Paths.get("target/texture-detex-oracle").toAbsolutePath
    Files.createDirectories(out.getParent)
    val args=Seq("cc","-std=c99","-O2","-I",base.toString)++Seq("oracle.c","decompress-etc.c","decompress-eac.c","clamp.c").map(base.resolve(_).toString)++Seq("-o",out.toString)
    require(Process(args).! ==0,"independent detex oracle did not compile");out.toString
  }
  def batch(queries: Seq[Query]): Vector[PixelBlock]={
    val input=queries.map(q=>s"${q.codec} ${if(q.signed)1 else 0} "+(0 until 16).map(n=>f"${((q.raw>>(8*n))&255).toInt}%02x").mkString).mkString("\n")+"\n"
    val rows=(Process(Seq(binary)) #< new ByteArrayInputStream(input.getBytes("US-ASCII"))).!!.linesIterator.toVector
    require(rows.size==queries.size)
    rows.map{row=>val n=row.split(" ").map(_.toInt).toVector;require(n.size==65);PixelBlock(n.head,n.tail.grouped(4).map(_.toVector).toVector)}
  }
  private val cache=scala.collection.mutable.Map.empty[Query,PixelBlock]
  def block(q: Query): PixelBlock=cache.getOrElseUpdate(q,batch(Seq(q)).head)
  def mode(raw: BigInt,punch: Boolean=false,rgba: Boolean=false): Int={
    val b=(0 until 4).map(n=>((raw>>(8*(n+(if(rgba)8 else 0))))&255).toInt)
    def sum(n: Int): Int=(b(n)>>3)+(if((b(n)&4)!=0)(b(n)&7)-8 else b(n)&7)
    if(!punch && (b(3)&2)==0)0 else if(sum(0)<0 || sum(0)>31)2 else if(sum(1)<0 || sum(1)>31)3 else if(sum(2)<0 || sum(2)>31)4 else 1
  }
}

class TextureCompressedBlockDecoderSpec extends AnyFlatSpec {
  import CompressedBlockReference._
  private class Driver(d: TextureCompressedBlockDecoder){
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var count=0
    def check(q: Query,x: Int,y: Int,expected: Vector[Int],hold: Boolean=false): Unit={
      d.io.in.ready.expect(true.B);d.io.in.bits.raw.poke(q.raw.U);d.io.in.bits.codec.poke(q.codec.U);d.io.in.bits.signed.poke(q.signed.B)
      d.io.in.bits.x.poke(x.U);d.io.in.bits.y.poke(y.U);d.io.in.bits.tag.poke((count&255).U);d.io.in.valid.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      d.io.in.bits.raw.poke(0.U);d.io.in.bits.codec.poke(7.U);d.io.in.bits.x.poke(3.U);d.io.in.bits.y.poke(3.U)
      var n=0;while(!d.io.out.valid.peek().litToBoolean && n<10){d.clock.step();n+=1};assert(n<10)
      d.io.out.bits.tag.expect((count&255).U);d.io.out.bits.status.expect(0.U)
      withClue(s"codec=${q.codec} signed=${q.signed} raw=${q.raw.toString(16)} x=$x y=$y "){
        d.io.out.bits.rgba.zip(expected).zipWithIndex.foreach{case((a,b),c)=>val observed=a.peek().litValue;assert(observed==(BigInt(b)&4095),s"component=$c observed=$observed expected=$b")}}
      if(hold){val held=d.io.out.bits.peek().litValue;d.clock.step(5);d.io.in.ready.expect(false.B);assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);count+=1
    }
  }
  it should "match an independent decoder for all ETC2 color modes alpha selectors and every block coordinate" in {
    val random=new scala.util.Random(0x45544332);val queries=scala.collection.mutable.ArrayBuffer.empty[Query]
    for(codec<-1 to 3){val modes=if(codec==2)Seq(1,2,3,4)else Seq(0,1,2,3,4)
      for(m<-modes){var n=0;while(n<128){val raw=BigInt(128,random);if(mode(raw,codec==2,codec==3)==m){queries+=Query(codec,false,raw);n+=1}}}}
    val expected=batch(queries.toSeq)
    simulate(new TextureCompressedBlockDecoder){d=>val t=new Driver(d)
      for((q,reference)<-queries.zip(expected)){assert(reference.mode==mode(q.raw,q.codec==2,q.codec==3))
        for(y<-0 until 4;x<-0 until 4)t.check(q,x,y,reference.pixels(y*4+x),(x+y)%7==0)}
      info(s"ETC2_BLOCK modesIndividualDifferentialTHPlanar=true RGBPunchRGBA=true everyCoordinate16=true independentDetex=true queries=${t.count}")
    }
  }
  it should "cover every EAC base table multiplier including signed minus128 and both zero multiplier meanings" in {
    val random=new scala.util.Random(0x45414331)
    val queries=for(signed<-Seq(false,true);base<-0 until 256;parameters<-0 until 256)yield{
      val r=BigInt(48,random)<<16|BigInt(parameters)<<8|base
      val g=BigInt(48,random)<<16|BigInt(parameters^255)<<8|(255-base)
      Query(if((parameters&1)==0)4 else 5,signed,r|(g<<64))}
    val expected=batch(queries)
    simulate(new TextureCompressedBlockDecoder){d=>val t=new Driver(d)
      for(((q,reference),n)<-queries.zip(expected).zipWithIndex){val pixel=n&15;t.check(q,pixel&3,pixel>>2,reference.pixels(pixel),n%8191==0)}
      val rgba=Query(3,false,BigInt("0000000000000000ffffffffffff00ff",16));val ref=block(rgba)
      for(y<-0 until 4;x<-0 until 4)t.check(rgba,x,y,ref.pixels(y*4+x))
      info(s"EAC_BLOCK base256=true tables16=true multipliers16=true unsignedSigned=true minus128MapsMinus127=true zeroMultiplier=true RAndRG=true queries=${t.count}")
    }
  }
  it should "match independent random EAC channels at all coordinates including clamped extremes" in {
    val random=new scala.util.Random(0x45414332)
    val queries=for(codec<-4 to 5;signed<-Seq(false,true);n<-0 until 256)yield Query(codec,signed,BigInt(128,random))
    val expected=batch(queries)
    simulate(new TextureCompressedBlockDecoder){d=>val t=new Driver(d)
      for((q,r)<-queries.zip(expected);y<-0 until 4;x<-0 until 4)t.check(q,x,y,r.pixels(y*4+x))
      info(s"EAC_BLOCK independentChannels=true allCoordinates16=true independentDetex=true queries=${t.count}")
    }
  }
}
