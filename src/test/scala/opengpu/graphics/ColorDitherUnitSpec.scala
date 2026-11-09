package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ColorDitherUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  private val bayer=Array(Array(0,8,2,10),Array(12,4,14,6),Array(3,11,1,9),Array(15,7,13,5))
  private def expect(enable: Boolean,x: Int,y: Int,color: Seq[Int]): (Seq[Int],Int)={
    val off=if(enable)bayer(y&3)(x&3)*16+8 else 0
    val shifted=color.map(c=>(c+off)>>8)
    (shifted.map(s=>math.min(s,255)),shifted.zipWithIndex.foldLeft(0){case(m,(s,i))=>if(s>255)m|(1<<i) else m})
  }
  behavior of "ColorDitherUnit"
  it should "quantize with a 4x4 Bayer threshold, bypass when disabled and saturate" in {
    simulate(new ColorDitherUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var tag=0
      def run(enable: Boolean,x: Int,y: Int,color: Seq[Int]): (Seq[Int],Int)={
        tag=(tag+1)&0xff;val q=d.io.in.bits;zero(q);q.tag.poke(tag.U);q.enable.poke(enable.B)
        q.x.poke(x.U);q.y.poke(y.U)
        for(i<-0 until 4)q.color(i).poke(color(i).U)
        d.io.in.valid.poke(true.B);var n=0;while(!d.io.in.ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(q)
        n=0;while(!d.io.out.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);val o=d.io.out.bits;o.tag.expect(tag.U)
        val r=(Seq(0,1,2,3).map(i=>o.color(i).peek().litValue.toInt),o.saturated.peek().litValue.toInt)
        val held=o.peek().litValue
        for(_<-0 until 3){d.clock.step();assert(o.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);r
      }
      val mid=Seq(100*256+128,50*256+128,0*256+128,200*256+128)
      for(y<-0 until 4;x<-0 until 4)assert(run(true,x,y,mid)==expect(true,x,y,mid),s"at $x,$y")
      assert((for(y<-0 until 4;x<-0 until 4)yield run(true,x,y,mid)._1.head).count(_==101)==8)
      for(y<-0 until 4;x<-0 until 4)assert(run(true,x+4,y+8,mid)==expect(true,x,y,mid),s"periodic at $x,$y")
      assert(run(true,1027,2051,mid)==expect(true,3,3,mid))
      for(y<-0 until 4;x<-0 until 4)assert(run(false,x,y,mid)==expect(false,x,y,mid),s"bypass at $x,$y")
      assert(run(false,0,0,Seq(0xffff,0xff00,0x1234,0))==(Seq(255,255,0x12,0),0))
      assert(run(true,0,0,Seq(0xfff0,0xfff0,0xfff0,0xfff0))==(Seq(255,255,255,255),0))
      assert(run(true,3,3,Seq(0xfff0,0xfff0,0xfff0,0xfff0))==(Seq(255,255,255,255),15))
      assert(run(true,1,0,Seq(0xfff0,0x0000,0x0000,0x0000))==(Seq(255,0,0,0),1))
      info("COLOR_DITHER bayer=true bypass=true saturate=true held=true")
    }
  }
}
