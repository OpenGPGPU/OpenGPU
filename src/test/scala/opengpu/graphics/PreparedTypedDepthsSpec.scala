package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PreparedTypedDepthsSpec extends AnyFlatSpec {
  import TypedColorReference._
  private case class Work(format: Int,raw: Vector[BigInt],tag: Int)
  private val special=Vector(0L,0x80000000L,1L,0x7fffffL,0x00800000L,0x3f000000L,0x3f7fffffL,0x3f800000L,
    0x7f800000L,0xff800000L,0x7fc00001L,0xffc00001L).map(BigInt(_))
  private val boundaries=(for(bits<-Seq(16,24);n<-0 until 1024;delta<- -1 to 1)yield {
    val code=if(bits==16)n*63 else n*16381
    fp((code+0.5)/((1L<<bits)-1))+delta
  }).toVector
  private val random=new scala.util.Random(0x44505448L)
  private val values=boundaries++special++Vector.fill(4096)(BigInt(32,random))
  private def work(i: Int): Work=Work(i%5+1,Vector.tabulate(4)(s=>values((i*17+s*997)%values.size)),i&255)
  private def expected(raw: BigInt,format: Int): BigInt={
    if(format==1 || format==2 || format==4)unorm(raw,if(format==1)16 else 24)
    // Preserve the existing floating clamp's signed-bit ordering, including
    // representations rejected by the enclosing active-depth validator.
    else if(value(raw).isNaN){if(raw.testBit(31))0 else fp(1)}
    else TypedDepthReference.encode(raw,format)
  }
  private def offer(d: PreparedTypedDepths,w: Work,valid: Boolean=true): Unit={
    d.io.in.valid.poke(valid.B);d.io.in.bits.format.poke(w.format.U);d.io.in.bits.tag.poke(w.tag.U)
    d.io.in.bits.raw.zip(w.raw).foreach{case(a,b)=>a.poke(b.U)}
  }
  private def check(d: PreparedTypedDepths,w: Work): Unit={
    d.io.out.bits.tag.expect(w.tag.U)
    d.io.out.bits.values.zip(w.raw).foreach{case(a,b)=>a.expect(expected(b,w.format).U)}
  }
  it should "prepare four exact depths every clock across fixed and floating formats and every D16 round trip" in {
    simulate(new PreparedTypedDepths){d=>
      offer(d,work(0),false);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val directed=(for(f<-1 to 5;group<-special.grouped(4))yield Work(f,group.toVector,f)).toVector
      val tasks=directed++Vector.tabulate(2048)(work(_))++Vector.tabulate(16384)(i=>Work(1,Vector.tabulate(4)(s=>fp((i*4+s)/65535.0)),i&255))
      var received=0;var previous= -1
      for(cycle<-0 until tasks.size+3){
        if(cycle<tasks.size)offer(d,tasks(cycle))else offer(d,work(cycle),false)
        if(d.io.out.valid.peek().litToBoolean){
          check(d,tasks(received));if(previous>=0)assert(cycle==previous+1,"depth vector inserted a throughput bubble")
          previous=cycle;received+=1
        }
        d.clock.step()
      }
      assert(received==tasks.size);d.io.out.valid.expect(false.B)
      info(s"PREPARED_TYPED_DEPTH vectors=${tasks.size} depths=${tasks.size*4} admissionII=1 responseII=1 sourceToResultClocks=3 formats=5 allD16RoundTrips=65536 exactRNE=true")
    }
  }
  it should "snapshot formats and tags across input gaps and hold an isolated result against poisoned inputs" in {
    simulate(new PreparedTypedDepths){d=>
      offer(d,work(0),false);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val pending=mutable.Queue.empty[(Int,Work)];var cycle=0;var sent=0;var received=0;var held: Option[BigInt]=None
      while(received<512 && cycle<2000){
        val valid=sent<512 && cycle%5<3
        offer(d,if(valid)work(sent)else Work(7,Vector.fill(4)(BigInt("ffffffff",16)),255),valid)
        val due=pending.headOption.exists(_._1==cycle)
        d.io.out.valid.expect(due.B)
        if(due){val(_,w)=pending.dequeue();check(d,w);held=Some(d.io.out.bits.peek().litValue);received+=1}
        else held.foreach(x=>assert(d.io.out.bits.peek().litValue==x,"idle conversion output changed"))
        if(valid){pending.enqueue((cycle+3,work(sent)));sent+=1}
        d.clock.step();cycle+=1
      }
      assert(received==512 && pending.isEmpty)
      offer(d,Work(0,Vector.fill(4)(BigInt(0)),0),false)
      for(_<-0 until 8){d.clock.step();d.io.out.valid.expect(false.B);held.foreach(x=>assert(d.io.out.bits.peek().litValue==x))}
    }
  }
}
