package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileSampleQuadExpanderSpec extends AnyFlatSpec {
  private case class Pixel(x: Int,y: Int,color: Long,mask: Int,depth: Vector[Int])
  private case class Quad(mode: Int,ox: Int,oy: Int,pixels: Vector[Pixel],state: Int)
  private case class Beat(ticket: Int,mask: Int,index: Vector[Int],color: Vector[Long],depth: Vector[Int],state: Int)
  private def poke(d: TileSampleQuadExpander,q: Quad): Unit = {
    val b=d.io.in.bits;b.sampleMode.poke(q.mode.U);b.originX.poke(q.ox.U);b.originY.poke(q.oy.U)
    b.state.poke(0.U.asTypeOf(b.state));b.state.colorMask.poke(q.state.U)
    b.state.stencilRef.poke((q.state*13).U);b.state.blendSrcFactor.poke(q.state.U)
    for(p <- 0 until 4) {
      val a=b.pixels(p);val v=q.pixels(p)
      a.x.poke(v.x.U);a.y.poke(v.y.U);a.color.poke(v.color.U);a.coverageMask.poke(v.mask.U)
      for(s <- v.depth.indices)a.depths(s).poke(v.depth(s).U)
    }
  }
  private def check(d: TileSampleQuadExpander,b: Beat): Unit = {
    val a=d.io.out.bits;a.ticket.expect(b.ticket.U);a.mask.expect(b.mask.U)
    a.state.colorMask.expect(b.state.U);a.state.stencilRef.expect((b.state*13).U)
    a.state.blendSrcFactor.expect(b.state.U)
    for(p <- 0 until 4) {
      a.index(p).expect(b.index(p).U);a.color(p).expect(b.color(p).U);a.depth(p).expect(b.depth(p).U)
    }
  }
  for(capacity <- Seq(1,2,4))it should s"preserve sample-major coverage depth and state through stalls at capacity $capacity" in {
    simulate(new TileSampleQuadExpander(capacity)) { d=>
      val rng=new scala.util.Random(7100+capacity)
      val quads=Vector.tabulate(180) { n=>
        val mode=rng.nextInt(Integer.numberOfTrailingZeros(capacity)+1)
        val ox=(n%3)*8;val oy=(n%4)*8;val x=(n%4)*2;val y=(n/4%4)*2
        Quad(mode,ox,oy,Vector.tabulate(4) { p=>
          Pixel(ox+x+(p&1),oy+y+(p>>1),rng.nextLong()&0xffffffffL,rng.nextInt(1<<capacity),
            Vector.tabulate(capacity)(s=>((n*7919+p*397+s*65537)|0x12000000)&0x3fffffff))
        },n%16)
      }
      val expected=mutable.Queue.empty[Beat]
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      var input=0;var cycle=0;var ticket=0;var held=Option.empty[BigInt];var outputs=0
      while((input<quads.size || expected.nonEmpty || !d.io.drained.peek().litToBoolean) && cycle<6000) {
        d.io.in.valid.poke((input<quads.size).B)
        if(input<quads.size)poke(d,quads(input))
        val ready=cycle%19>=8 && rng.nextInt(5)!=0
        d.io.out.ready.poke(ready.B)
        held.foreach(v=>{d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==v)})
        if(d.io.out.valid.peek().litToBoolean) {
          assert(expected.nonEmpty);check(d,expected.front)
          if(ready){expected.dequeue();outputs+=1;held=None}else held=Some(d.io.out.bits.peek().litValue)
        }else held=None
        if(input<quads.size && d.io.in.ready.peek().litToBoolean) {
          val q=quads(input)
          for(s <- 0 until (1<<q.mode)) {
            val mask=q.pixels.zipWithIndex.map{case(p,i)=>if((p.mask&(1<<s))!=0)1<<i else 0}.sum
            if(mask!=0) {
              expected.enqueue(Beat(ticket&255,mask,q.pixels.map(p=>(((p.y-q.oy)*16+p.x-q.ox)<<q.mode)|s),
                q.pixels.map(_.color),q.pixels.map(_.depth(s)&0xffffff),q.state));ticket+=1
            }
          }
          input+=1
        }
        d.clock.step();cycle+=1
      }
      assert(input==180 && expected.isEmpty && outputs==ticket && ticket>100)
      d.io.drained.expect(true.B)
    }
  }
  it should "replace complete single-sample quads every cycle without a bubble" in {
    simulate(new TileSampleQuadExpander) { d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(true.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      for(n <- 0 until 130) {
        val q=Quad(0,8,8,Vector.tabulate(4)(p=>Pixel(8+(p&1),8+(p>>1),n*4L+p,1,Vector.fill(4)(n*17+p))),n%16)
        poke(d,q);d.io.in.valid.poke((n<128).B);d.io.in.ready.expect(true.B)
        if(n>=1 && n<=128) {
          d.io.out.valid.expect(true.B);d.io.out.bits.mask.expect(15.U);d.io.out.bits.ticket.expect((n-1).U)
          for(p <- 0 until 4)d.io.out.bits.color(p).expect(((n-1)*4+p).U)
        }
        d.clock.step()
      }
      d.io.drained.expect(true.B)
    }
  }
}
