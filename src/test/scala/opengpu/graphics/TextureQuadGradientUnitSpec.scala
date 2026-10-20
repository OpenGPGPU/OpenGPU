package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureQuadGradientUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  import TextureGradientReference._
  import TextureFilterReference.bits
  private class Driver(val d: TextureQuadGradientUnit) {
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    var tag=0;var coordinates=Seq.fill(4)(Seq.fill(3)(BigInt(0)));var lane=0;var dimensions=2;var valid=15;var exponents=Set.empty[Int]
    def until(p: =>Boolean): Unit={var n=0;while(!p && n<1000){d.clock.step();n+=1};assert(p,"quad gradients did not drain")}
    def query(v: Seq[Seq[BigInt]],selected: Int=0,dims: Int=2,mask: Int=15): Unit={
      tag+=1;coordinates=v;lane=selected;dimensions=dims;valid=mask
      d.io.in.bits.coordinates.zip(v).foreach{case(a,b)=>a.zip(b).foreach{case(x,y)=>x.poke(y.U)}}
      d.io.in.bits.lane.poke(selected.U);d.io.in.bits.dimensions.poke(dims.U);d.io.in.bits.validMask.poke(mask.U);d.io.in.bits.tag.poke(tag.U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);d.clock.step();d.io.in.valid.poke(false.B);zero(d.io.in.bits)
    }
    def check(cancelled: Boolean=false): Unit={
      until(d.io.out.valid.peek().litToBoolean);val o=d.io.out.bits;o.tag.expect(tag.U)
      val invalid=valid!=15 || (dimensions!=2 && dimensions!=3)
      val bad=coordinates.exists(_.take(dimensions).exists(v=>((v>>23)&255)==255))
      val status=if(cancelled)4 else if(invalid)1 else if(bad)5 else 0;o.status.expect(status.U)
      for(row<-0 to 1;a<-0 to 2){
        val lo=if(row==0)(lane/2)*2 else lane%2;val hi=lo+(if(row==0)1 else 2)
        val expected=if(status==0 && a<dimensions)rounded(integer(coordinates(hi)(a))-integer(coordinates(lo)(a)),-149)else(false,0,BigInt(0))
        val r=o.gradients(row)(a);val actual=(r.negative.peek().litToBoolean,r.exponent.peek().litValue.toInt,r.significand.peek().litValue)
        assert(actual==expected,s"tag=$tag lane=$lane row=$row axis=$a got=$actual expected=$expected")
        if(expected._3!=0)exponents+=expected._2
      }
      val held=o.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 3){d.clock.step();assert(o.peek().litValue==held)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.busy.expect(false.B)
    }
  }
  it should "differentiate complete helper quads with extended range and exact row column selection" in {
    simulate(new TextureQuadGradientUnit){d=>val t=new Driver(d);var cases=0
      def check(v: Seq[Seq[BigInt]],dims: Int=2,mask: Int=15): Unit=for(lane<-0 until 4){t.query(v,lane,dims,mask);t.check();cases+=1}
      check(Seq(Seq(1f,2f,3f),Seq(3f,5f,7f),Seq(11f,17f,23f),Seq(29f,31f,37f)).map(_.map(bits)),3)
      check(Seq(Seq[BigInt](0xff7fffffL,0,0),Seq[BigInt](0x7f7fffffL,1,0),Seq[BigInt](0xff7fffffL,1,0),Seq[BigInt](0x7f7fffffL,2,0)),3)
      check(Seq.fill(4)(Seq(bits(-0f),bits(1f),BigInt(0x7fc12345L))))
      for(mask<-0 until 15)check(Seq.fill(4)(Seq.fill(3)(bits(1f))),mask=mask)
      check(Seq.fill(4)(Seq(bits(1f),BigInt(0x7fc12345L),bits(0f))))
      val random=new scala.util.Random(0x51554144)
      for(_<-0 until 512){val v=Seq.fill(4)(Seq.fill(3)(BigInt(random.nextLong()&0xffffffffL)))
        if(v.flatten.forall(x=>((x>>23)&255)!=255)){check(v,2);check(v,3)}}
      assert(t.exponents.max==128 && t.exponents.min== -149)
      info(s"QUAD_GRADIENT queries=$cases fineRowAndColumn=true lanesTLTRBLBR=true fullHelpersRequired=true unusedAxisIgnored=true exactBigIntRNE=true exponentMin=${t.exponents.min} exponentMax=${t.exponents.max} noFP32DifferenceOverflow=true heldOutput=true")
    }
  }
  it should "cancel every helper arithmetic stage before healthy reuse" in {
    simulate(new TextureQuadGradientUnit){d=>val t=new Driver(d);var cancelled=0;var held=0
      val v=Seq(Seq(1f,2f,3f),Seq(3f,5f,7f),Seq(11f,17f,23f),Seq(29f,31f,37f)).map(_.map(bits))
      for(cut<-0 until 128){t.query(v,cut%4,3);d.clock.step(cut);val done=d.io.out.valid.peek().litToBoolean
        d.io.cancel.poke(true.B);t.check(cancelled= !done);if(done)held+=1 else cancelled+=1
        t.query(v,cut%4,3);t.check()
      }
      assert(cancelled>0 && held>0)
      info(s"QUAD_GRADIENT_DRAIN cuts128=true cancelled=$cancelled held=$held noAbandonedChild=true healthyReuse128=true")
    }
  }
}
