package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PipelinedRasterAttributesSpec extends AnyFlatSpec {
  private def signed(value: BigInt, width: Int): BigInt = {
    val bits = value & ((BigInt(1) << width) - 1)
    if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
  }
  private def divide(n: BigInt, d: BigInt, width: Int): BigInt =
    signed(if (d == 0) BigInt(0) else n / d, width)
  for (lanes <- Seq(1,4)) {
    it should s"retain modular attributes sample gradients and atomic metadata at II1 lanes=$lanes" in {
      val config = GraphicsConfig()
      val random = new scala.util.Random(0x7a643L + lanes)
      def value(width: Int): BigInt = signed(BigInt(width,random),width)
      case class Input(index: Int, edges: Vector[Vector[BigInt]], areas: Vector[BigInt],
        rgba: Vector[Vector[BigInt]], depths: Vector[BigInt], planes: Vector[Vector[BigInt]], planeArea: BigInt) {
        val mode = index % 3
        def quotient(lane: Int, channel: Int): BigInt = {
          val attrs = if (channel == 4) depths else rgba.map(_(channel))
          val sum = signed(edges(lane).zip(attrs).map { case (e,a) => e*a }.sum, if(channel==4)96 else 72)
          divide(sum,areas(lane),if(channel==4)32 else 8)
        }
        def gradient(axis: Int): BigInt = {
          val sum = signed(planes(axis).zip(depths).map { case (p,d) => p*d }.sum,66)
          divide(sum << 6,planeArea,32)
        }
        val zero = areas.contains(BigInt(0)) || planeArea==0
        def mask(lane: Int): Int = (index*3+lane*5)&15
        def sample(lane: Int, sample: Int): BigInt = {
          val center = quotient(lane,4)
          if (mode==0) { if(sample==0)center&0xffffff else BigInt(0) }
          else {
            val positions = if(mode==1)Vector((-1,-1),(1,1)) else Vector((-1,-1),(1,-1),(-1,1),(1,1))
            if(sample>=positions.size)BigInt(0)
            else {
              val (x,y)=positions(sample)
              // Preserve SampleDepth's fixed-width negation before widened additions.
              val z=center+signed(gradient(0)*x,32)+signed(gradient(1)*y,32)
              z.max(BigInt(0)).min(BigInt(0xffffff))
            }
          }
        }
      }
      val cases = Vector.tabulate(384) { index =>
        val edges = if(index==0)Vector.fill(lanes)(Vector.fill(3)(-(BigInt(1)<<63)))
          else if(index==1)Vector.fill(lanes)(Vector(BigInt(-1),BigInt(0),BigInt(0)))
          else Vector.fill(lanes)(Vector.fill(3)(value(64)))
        val depths = if(index<=1)Vector(-(BigInt(1)<<31),BigInt(0),BigInt(0)) else Vector.fill(3)(value(32))
        val areas = if(index==0)Vector.fill(lanes)(BigInt(-1)) else if(index==1)Vector.fill(lanes)(BigInt(1))
          else Vector.tabulate(lanes)(l=>if((index+l)%23==0)BigInt(0)else if(index%7==0)BigInt(1)else value(64))
        val planes = if(index==1)Vector.fill(2)(Vector(BigInt(1),BigInt(0),BigInt(0)))
          else Vector.fill(2)(Vector.fill(3)(value(34)))
        Input(index,edges,areas,Vector.fill(3)(Vector.fill(4)(BigInt(random.nextInt(256)))),depths,planes,
          if(index==1)BigInt(64)else if(index%29==0)BigInt(0)else if(index%11==0)BigInt(-1)else value(64))
      }
      simulate(new PipelinedRasterAttributes(config,lanes)) { d =>
        d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits)); d.io.out.ready.poke(false.B)
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.drained.expect(true.B)
        def drive(in: Input): Unit = {
          for(l<-0 until lanes) {
            val p=d.io.in.bits.pixels(l)
            p.x.poke((in.index*8+l).S);p.y.poke((in.index*13+l).S)
            Seq(p.e0,p.e1,p.e2).zip(in.edges(l)).foreach {case(port,v)=>port.poke(v.S(64.W))}
            p.area.poke(in.areas(l).S(64.W));p.coverageMask.poke(in.mask(l).U);p.covered.poke((in.mask(l)!=0).B)
          }
          for(v<-0 until 3) {
            val c=d.io.in.bits.colors(v)
            c.r.poke(in.rgba(v)(0).U);c.g.poke(in.rgba(v)(1).U);c.b.poke(in.rgba(v)(2).U)
            d.io.in.bits.alphas(v).poke(in.rgba(v)(3).U);d.io.in.bits.depths(v).poke(in.depths(v).S(32.W))
            d.io.in.bits.planeA(v).poke(in.planes(0)(v).S(34.W));d.io.in.bits.planeB(v).poke(in.planes(1)(v).S(34.W))
          }
          d.io.in.bits.planeArea.poke(in.planeArea.S(64.W));d.io.in.bits.sampleMode.poke(in.mode.U)
        }
        def check(in: Input): Unit = {
          d.io.out.bits.divideByZero.expect(in.zero.B)
          for(l<-0 until lanes) {
            val p=d.io.out.bits.fragments(l)
            p.x.expect((in.index*8+l).S);p.y.expect((in.index*13+l).S)
            Seq(p.e0,p.e1,p.e2).zip(in.edges(l)).foreach {case(port,v)=>port.expect(v.S(64.W))}
            p.covered.expect((in.mask(l)!=0).B);p.coverageMask.expect(in.mask(l).U)
            Seq(p.color.r,p.color.g,p.color.b,p.alpha).zipWithIndex.foreach {case(port,c)=>port.expect((in.quotient(l,c)&255).U)}
            p.depth.expect(in.quotient(l,4).S(32.W))
            for(s<-0 until 4)d.io.out.bits.depthSamples(l)(s).expect(in.sample(l,s).U)
          }
        }
        val expected=mutable.Queue.empty[Input];val accepts=mutable.ArrayBuffer.empty[Int];val returns=mutable.ArrayBuffer.empty[Int]
        var sent=0;var received=0;var cycle=0;var held: Option[BigInt]=None
        while(received<cases.size && cycle<6000) {
          val outstandingBefore=sent-received
          d.io.in.valid.poke((sent<cases.size).B);if(sent<cases.size)drive(cases(sent))
          val ready=cycle<260 || (cycle>=480 && cycle%17>=9);d.io.out.ready.poke(ready.B)
          val put=sent<cases.size && d.io.in.ready.peek().litToBoolean;val valid=d.io.out.valid.peek().litToBoolean
          if(put){expected.enqueue(cases(sent));accepts+=cycle;sent+=1}
          held.foreach(bits=>{assert(valid);assert(d.io.out.bits.peek().litValue==bits)})
          if(valid){check(expected.front);if(ready){expected.dequeue();returns+=cycle;received+=1;held=None}else held=Some(d.io.out.bits.peek().litValue)}
          if(outstandingBefore>0)d.io.drained.expect(false.B)
          d.clock.step();cycle+=1
        }
        assert(received==cases.size && expected.isEmpty)
        assert(accepts.take(128).toVector==(0 until 128).toVector)
        assert(returns.take(128).toVector==(d.latency until d.latency+128).toVector)
        d.io.in.valid.poke(false.B);d.clock.step();d.io.drained.expect(true.B)
        d.io.out.ready.poke(false.B);d.io.in.valid.poke(true.B);drive(cases(1));d.clock.step(320)
        d.io.in.valid.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.out.ready.poke(true.B)
        for(_<-0 until d.latency+8){d.io.out.valid.expect(false.B);d.io.drained.expect(true.B);d.clock.step()}
        drive(cases(1));d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
        for(_<-0 until d.latency-1)d.clock.step()
        d.io.out.valid.expect(true.B);check(cases(1));d.clock.step();d.io.drained.expect(true.B)
        info(s"PIPELINED_RASTER_ATTRIBUTES lanes=$lanes packets=$received latency=${d.latency} inputII1=true outputII1=true modularColor72=true modularDepth96=true gradientSum66BeforeShift=true sampleModes3=true orderedMetadata=true longStalls=true resetHealthyReuse=true")
      }
    }
  }
}
