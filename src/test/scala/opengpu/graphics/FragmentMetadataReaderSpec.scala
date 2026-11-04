package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class FragmentMetadataReaderHarness(val rows: Int,val quads: Int) extends Module {
  private val reader=Module(new FragmentMetadataReader(rows,quads))
  val io=IO(new Bundle {
    val start=Flipped(Decoupled(UInt(math.max(1,log2Ceil(quads)).W)))
    val out=Decoupled(chiselTypeOf(reader.io.out.bits))
    val write=Input(Valid(new Bundle {
      val address=UInt(math.max(1,log2Ceil(rows*quads)).W);val data=UInt(128.W)
    }))
    val read=Output(chiselTypeOf(reader.io.read));val cancel=Input(Bool())
  })
  private val memory=SyncReadMem(1<<log2Ceil(rows*quads),UInt(128.W))
  when(io.write.valid){memory.write(io.write.bits.address,io.write.bits.data)}
  reader.io.readData:=memory.read(reader.io.read.bits,reader.io.read.valid)
  reader.io.start<>io.start;io.out<>reader.io.out;reader.io.cancel:=io.cancel;io.read:=reader.io.read
}

class FragmentMetadataReaderSpec extends AnyFlatSpec {
  private def word(address: Int): BigInt=(BigInt(address+1)*BigInt("9e3779b97f4a7c1585ebca6bc2b2ae35",16) ^
    (BigInt(address+19)<<97) ^ (BigInt(address*address+7)<<33))&((BigInt(1)<<128)-1)
  private class Driver(val d: FragmentMetadataReaderHarness) {
    val reads=mutable.ArrayBuffer.empty[Int];var cycle=0
    d.io.start.valid.poke(false.B);d.io.start.bits.poke(0.U);d.io.out.ready.poke(false.B)
    d.io.write.valid.poke(false.B);d.io.write.bits.address.poke(0.U);d.io.write.bits.data.poke(0.U)
    d.io.cancel.poke(false.B);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    for(address<-0 until d.rows*d.quads){
      d.io.write.valid.poke(true.B);d.io.write.bits.address.poke(address.U)
      d.io.write.bits.data.poke(word(address).U);d.clock.step()
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
      for(row<-0 until d.rows)d.io.out.bits.data(row).expect(word(quad*d.rows+row).U)
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
}
