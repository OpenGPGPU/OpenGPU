package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class FragmentMetadataReaderHarness(val rows: Int,val quads: Int,val wordBits: Int=128) extends Module {
  private val reader=Module(new FragmentMetadataReader(rows,quads,wordBits))
  val io=IO(new Bundle {
    val start=Flipped(Decoupled(UInt(math.max(1,log2Ceil(quads)).W)))
    val out=Decoupled(chiselTypeOf(reader.io.out.bits))
    val write=Input(Valid(new Bundle {
      val address=UInt(math.max(1,log2Ceil(rows*quads)).W);val data=UInt(wordBits.W)
    }))
    val read=Output(chiselTypeOf(reader.io.read));val cancel=Input(Bool())
  })
  private val memory=SyncReadMem(1<<log2Ceil(rows*quads),UInt(wordBits.W))
  when(io.write.valid){memory.write(io.write.bits.address,io.write.bits.data)}
  reader.io.readData:=memory.read(reader.io.read.bits,reader.io.read.valid)
  reader.io.start<>io.start;io.out<>reader.io.out;reader.io.cancel:=io.cancel;io.read:=reader.io.read
}

private class FragmentMetadataPackingHarness extends FragmentMetadataReaderHarness(5,32,256) {
  private val configs=Seq(1,2,4).map(s=>GraphicsConfig(maxSampleCount=s))
  val layouts=configs.map(g=>FragmentMetadataLayout.select((new FragmentQuadMetadata(g)).getWidth,32))
  val codec=IO(new Bundle {
    val input=Input(Vec(3,UInt(1146.W)))
    val words=Output(MixedVec(layouts.map(l=>Vec(l.rows,UInt(l.wordBits.W)))))
    val restored=Output(MixedVec(configs.map(g=>new FragmentQuadMetadata(g))))
  })
  for((g,i)<-configs.zipWithIndex) {
    val layout=layouts(i)
    val original=codec.input(i).asTypeOf(new FragmentQuadMetadata(g))
    for(row<-0 until layout.rows)codec.words(i)(row):=layout.word(original.asUInt,row.U)
    codec.restored(i):=codec.words(i).asUInt(layout.metadataBits-1,0).asTypeOf(new FragmentQuadMetadata(g))
  }
}

class FragmentMetadataReaderSpec extends AnyFlatSpec {
  private def word(address: Int,bits: Int): BigInt=(BigInt(address+1)*BigInt(
    "d6e8feb86659fd93a5a3564e27f8862b9e3779b97f4a7c1585ebca6bc2b2ae35",16) ^
    (BigInt(address+19)<<97) ^ (BigInt(address*address+7)<<33))&((BigInt(1)<<bits)-1)
  private class Driver(val d: FragmentMetadataReaderHarness) {
    val reads=mutable.ArrayBuffer.empty[Int];var cycle=0
    d.io.start.valid.poke(false.B);d.io.start.bits.poke(0.U);d.io.out.ready.poke(false.B)
    d.io.write.valid.poke(false.B);d.io.write.bits.address.poke(0.U);d.io.write.bits.data.poke(0.U)
    d.io.cancel.poke(false.B);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    for(address<-0 until d.rows*d.quads){
      d.io.write.valid.poke(true.B);d.io.write.bits.address.poke(address.U)
      d.io.write.bits.data.poke(word(address,d.wordBits).U);d.clock.step()
    }
    d.io.write.valid.poke(false.B)
    def tick(): Unit={
      if(d.io.read.valid.peek().litToBoolean)reads+=d.io.read.bits.peek().litValue.toInt
      d.clock.step();cycle+=1
    }
    def start(quad: Int): Unit={
      d.io.start.bits.poke(quad.U);d.io.start.valid.poke(true.B);d.io.start.ready.expect(true.B)
      tick();d.io.start.valid.poke(false.B)
    }
    def check(quad: Int): Unit={
      var left=d.rows+3
      while(!d.io.out.valid.peek().litToBoolean && left>0){tick();left-=1}
      d.io.out.valid.expect(true.B);d.io.out.bits.quad.expect(quad.U)
      for(row<-0 until d.rows)d.io.out.bits.data(row).expect(word(quad*d.rows+row,d.wordBits).U)
    }
    def retire(): Unit={d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B)}
  }

  it should "retain all metadata rows while a successor waits and start it when the held quad retires" in {
    simulate(new FragmentMetadataReaderHarness(9,32)){d=>
      val t=new Driver(d);t.start(28);t.check(28)
      d.io.start.valid.poke(true.B);d.io.start.bits.poke(29.U)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 13){
        d.io.start.ready.expect(false.B);d.io.read.valid.expect(false.B);t.tick()
        d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)
      }
      d.io.out.ready.poke(true.B);d.io.start.ready.expect(true.B);t.tick()
      d.io.out.ready.poke(false.B);d.io.start.valid.poke(false.B)
      d.io.out.valid.expect(false.B);t.check(29);t.retire()
      assert(t.reads.toVector==Vector.range(252,270),"metadata prefetch changed row addresses or reread held data")
    }
  }

  it should "discard every canceled prefix including its final synchronous reply before immediate healthy reuse" in {
    simulate(new FragmentMetadataReaderHarness(9,32)){d=>
      val t=new Driver(d)
      for(cut<-0 to 12){
        t.start(cut);for(_<-0 until cut)t.tick()
        d.io.cancel.poke(true.B);d.io.start.valid.poke(true.B);d.io.start.bits.poke(31.U)
        d.io.start.ready.expect(false.B);d.io.out.valid.expect(false.B);d.io.read.valid.expect(false.B)
        t.tick();d.io.cancel.poke(false.B);d.io.start.valid.poke(false.B)
        d.io.out.valid.expect(false.B);t.start(31-cut);t.check(31-cut);t.retire()
        d.io.out.valid.expect(false.B)
      }
    }
  }

  it should "stream exact complete quads at single-row and non-power-of-two capacities" in {
    for((rows,quads)<-Seq((1,1),(5,3),(9,32))){
      simulate(new FragmentMetadataReaderHarness(rows,quads)){d=>
        val t=new Driver(d);val order=Vector(quads-1,0,quads/2,quads-1)
        for(quad<-order){
          val begin=t.cycle;t.start(quad);t.check(quad)
          assert(t.cycle-begin==rows+2,"metadata prefetch added a bubble between synchronous SRAM rows")
          t.retire()
        }
        assert(t.reads.toVector==order.flatMap(q=>Vector.range(q*rows,(q+1)*rows)))
      }
    }
  }

  it should "select no additional native macros at every capacity and maximum sample count" in {
    var wide=0;var fallback=0
    for(capacity<-4 until 256 by 4;samples<-Seq(1,2,4)) {
      val bits=(new FragmentQuadMetadata(GraphicsConfig(maxSampleCount=samples))).getWidth
      assert(bits==618+132*samples)
      val layout=FragmentMetadataLayout.select(bits,capacity/4)
      // Independent threshold oracle for minimum-256-row, 64-bit native macros.
      val expectWide=samples match {
        case 1=>capacity>=172
        case 2=>capacity>=148
        case 4=>(capacity>=116 && capacity<=204) || capacity>=228
      }
      val oldMacros=if(samples==4 && capacity>224)8
        else if(capacity>(if(samples==1)168 else if(samples==2)144 else 112))4 else 2
      assert(layout.wordBits==(if(expectWide)256 else 128),s"capacity=$capacity samples=$samples")
      assert(layout.nativeMacros==oldMacros && layout.depth>=layout.rows*(capacity/4))
      assert(layout.rows*layout.wordBits>=bits)
      if(expectWide)wide+=1 else fallback+=1
    }
    assert(wide==78 && fallback==111)
  }

  it should "preserve every real metadata bit and wide-row ownership through stalls cancellation and reuse" in {
    simulate(new FragmentMetadataPackingHarness){d=>
      d.codec.input.foreach(_.poke(0.U))
      val t=new Driver(d)
      for((layout,i)<-d.layouts.zipWithIndex) {
        val mask=(BigInt(1)<<layout.metadataBits)-1
        val patterns=Iterator.tabulate(layout.metadataBits)(bit=>BigInt(1)<<bit) ++
          Iterator(mask,BigInt("fedcba9876543210",16)*mask,BigInt(1)<<1145)
        for(raw<-patterns) {
          val input=raw&((BigInt(1)<<1146)-1);val expected=input&mask
          d.codec.input(i).poke(input.U)
          for(row<-0 until layout.rows) {
            val expectedWord=(expected>>(row*layout.wordBits))&((BigInt(1)<<layout.wordBits)-1)
            d.codec.words(i)(row).expect(expectedWord.U)
          }
          assert(d.codec.restored(i).peek().litValue==expected,"metadata field order or high padding changed")
        }
      }
      t.start(28);t.check(28)
      d.io.start.valid.poke(true.B);d.io.start.bits.poke(29.U)
      val held=d.io.out.bits.peek().litValue
      for(_<-0 until 13) {
        d.io.start.ready.expect(false.B);d.io.read.valid.expect(false.B);t.tick()
        d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held)
      }
      d.io.out.ready.poke(true.B);d.io.start.ready.expect(true.B);t.tick()
      d.io.out.ready.poke(false.B);d.io.start.valid.poke(false.B)
      t.check(29);t.retire()
      assert(t.reads.toVector==Vector.range(140,150))
      for(cut<-0 to 8) {
        t.start(cut);for(_<-0 until cut)t.tick()
        d.io.cancel.poke(true.B);d.io.start.valid.poke(true.B);d.io.start.bits.poke(31.U)
        d.io.start.ready.expect(false.B);d.io.out.valid.expect(false.B);d.io.read.valid.expect(false.B)
        t.tick();d.io.cancel.poke(false.B);d.io.start.valid.poke(false.B)
        d.io.out.valid.expect(false.B);t.start(31-cut);t.check(31-cut);t.retire()
        d.io.out.valid.expect(false.B)
      }
      assert(d.layouts.map(_.wordBits)==Seq(128,128,256))
    }
  }
}
