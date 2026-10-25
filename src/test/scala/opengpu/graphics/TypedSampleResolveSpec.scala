package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

private[graphics] object TypedResolveReference {
  private def mean(values: Vector[BigInt],mode: Int): BigInt={
    val positive=values.contains(BigInt("7f800000",16));val negative=values.contains(BigInt("ff800000",16))
    if(values.exists(v=>(v>>23&255)==255 && (v&0x7fffff)!=0) || (positive && negative))return BigInt("7fc00000",16)
    if(positive)return BigInt("7f800000",16);if(negative)return BigInt("ff800000",16)
    val sum=values.map{v=>val e=(v>>23&255).toInt;val f=v&0x7fffff
      val n=(if(e==0)f else (f|(BigInt(1)<<23))<<(e-1));if(v.testBit(31))-n else n}.sum
    if(sum==0)return if(values.forall(_==BigInt("80000000",16)))BigInt("80000000",16)else BigInt(0)
    val sign=if(sum<0)BigInt(1)<<31 else BigInt(0);val n=sum.abs;val msb=n.bitLength-1
    val shift=math.max(mode,msb-23);val q=n>>shift;val rem=n-(q<<shift)
    val r=q+(if(shift>0 && (rem>(BigInt(1)<<(shift-1)) || (rem==(BigInt(1)<<(shift-1)) && q.testBit(0))))1 else 0)
    if(msb<23+mode)sign|r else {val carry=if(r.bitLength>24)1 else 0;val sig=r>>carry
      sign|(BigInt(msb-22-mode+carry)<<23)|(sig&0x7fffff)}
  }
  def resolve(name: String,samples: Vector[BigInt],mode: Int,depth: Boolean=false): BigInt={
    if(depth || mode==0 || TypedColorReference.format(name).kind!=0)return samples.head
    val rgba=samples.take(1<<mode).map(TypedBlendReference.decode(name,_))
    TypedColorReference.encode(name,Vector.tabulate(4)(c=>mean(rgba.map(_(c)),mode)))._1
  }
}
class TypedSampleResolveSpec extends AnyFlatSpec {
  private class Driver(val d: TypedSampleResolve){
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    var count=0
    def send(name: String,values: Vector[BigInt],mode: Int,depth: Boolean=false): BigInt={
      d.io.in.bits.format.poke(TypedColorFormat.id(name).U);d.io.in.bits.depth.poke(depth.B);d.io.in.bits.sampleMode.poke(mode.U)
      d.io.in.bits.samples.zip(values).foreach{case(a,b)=>a.poke(b.U)};d.io.in.bits.tag.poke((count%256).U)
      d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      TypedResolveReference.resolve(name,values,mode,depth)
    }
    def finish(expected: BigInt,status: Int=0,cancel: Boolean=false): Unit={var n=0
      while(!d.io.out.valid.peek().litToBoolean && n<150){d.clock.step();n+=1};assert(n<150)
      d.io.out.bits.status.expect(status.U);d.io.out.bits.cancelled.expect(cancel.B);d.io.out.bits.tag.expect((count%256).U)
      if(status==0)d.io.out.bits.value.expect(expected.U)
      val held=d.io.out.bits.peek().litValue;d.clock.step(3);assert(d.io.out.bits.peek().litValue==held)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);count+=1
    }
  }
  it should "resolve every typed format at 1x 2x and 4x with linear sRGB and raw integer depth selection" in {
    simulate(new TypedSampleResolve){d=>val t=new Driver(d);val rng=new Random(0x5a7e)
      for(f<-TypedColorFormat.formats;mode<-0 to 2;i<-0 until 8){
        val values=Vector.fill(4)(BigInt(f.bytes*8,rng));t.finish(t.send(f.name,values,mode))}
      for(mode<-0 to 2;i<-0 until 16){val values=Vector.fill(4)(BigInt(128,rng));t.finish(t.send("RGBA32F",values,mode,depth=true))}
      info(s"TYPED_RESOLVE formats=36 modes=3 requests=${t.count} exactMean=true integerSampleZero=true depthStencilRaw=true sRGBLinear=true heldResult=true")
    }
  }
  it should "average finite extremes exactly and preserve special values while draining cancellation and rejecting invalid modes" in {
    simulate(new TypedSampleResolve){d=>val t=new Driver(d);val mask=BigInt("ffffffff",16)
      val cases=Vector(Vector(0x7f7fffffL,0x7f7fffffL,0x7f7fffffL,0x7f7fffffL),Vector(1L,0L,1L,0L),Vector(3L,0L,3L,0L),
        Vector(0x7f7fffffL,0xff7fffffL,1L,1L),Vector(0x00800000L,0x007fffffL,0x00800000L,0x007fffffL),
        Vector.fill(4)(0x80000000L),Vector(0x80000000L,0L,0L,0L),Vector(0x80000001L,0L,0L,0L),
        Vector(0x7f800000L,0xff800000L,1L,0L),Vector(0x7fc12345L,0L,1L,2L),Vector(0x7f800000L,0L,1L,2L),Vector(0xff800000L,0L,1L,2L))
      for(v<-cases;mode<-Seq(1,2))t.finish(t.send("R32F",v.map(BigInt(_)&mask),mode))
      val samples=Vector(0L,0x3f800000L,0x3f000000L,0x3e800000L).map(BigInt(_))
      val e=t.send("R32F",samples,2);d.clock.step(7);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B);t.finish(e,cancel=true)
      val held=t.send("R32F",samples,2);while(!d.io.out.valid.peek().litToBoolean)d.clock.step()
      d.io.cancel.poke(true.B);d.clock.step(4);d.io.cancel.poke(false.B);t.finish(held)
      t.send("R8",Vector.fill(4)(BigInt(1)),3);t.finish(0,status=1)
      t.finish(t.send("R8",Vector.fill(4)(BigInt(1)),2))
      info("TYPED_RESOLVE extremeCases=24 overflowFreeMean=true subnormalRNE=true cancellationExact=true signedZero=true nonfinitePolicy=true cancelDrains=true immutableHeldResult=true invalidMode=true healthyReuse=true")
    }
  }
  it should "drain every pipelined cancellation offset and reset without using live request operands" in {
    simulate(new TypedSampleResolve){d=>val t=new Driver(d)
      val values=Vector("7f7fffff","ff7fffff","00000001","00000001").map(BigInt(_,16))
      val first=t.send("R32F",values,2);var latency=0
      while(!d.io.out.valid.peek().litToBoolean && latency<150){d.clock.step();latency+=1}
      assert(latency<150);t.finish(first)
      for(offset<-0 to (latency+2)){
        val expected=t.send("R32F",values,2)
        d.io.in.bits.format.poke(63.U);d.io.in.bits.samples.foreach(_.poke(0.U));d.io.in.bits.tag.poke(255.U)
        d.clock.step(offset)
        val alreadyHeld=d.io.out.valid.peek().litToBoolean
        val held=if(alreadyHeld)Some(d.io.out.bits.peek().litValue)else None
        d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
        held.foreach(v=>assert(d.io.out.bits.peek().litValue==v))
        t.finish(expected,cancel = !alreadyHeld)
      }
      val resetOffsets=((0 to 6)++Seq(latency/2,latency-1)).distinct
      for(offset<-resetOffsets){
        t.send("R32F",values,2);d.clock.step(offset)
        d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);t.count+=1
        d.io.in.ready.expect(true.B);d.io.out.valid.expect(false.B)
        t.finish(t.send("R32F",values,2))
      }
      info(s"TYPED_RESOLVE_PIPELINE measuredLatency=$latency cancelOffsets=${latency+3} resetOffsets=${resetOffsets.size} exactNumericDrain=true immutableHeldResult=true liveOperandIsolation=true healthyReuse=true original150CycleBound=true")
    }
  }
}
