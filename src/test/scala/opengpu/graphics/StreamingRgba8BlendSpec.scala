package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class Rgba8BlendComparison extends Module {
  val io=IO(new Bundle {val streaming=new TypedColorBlendIO;val serial=new TypedColorBlendIO})
  private val streaming=Module(new StreamingRgba8Blend);private val serial=Module(new TypedColorBlend)
  io.streaming<>streaming.io;io.serial<>serial.io
}
class StreamingRgba8BlendSpec extends AnyFlatSpec {
  import TypedColorReference.fp
  import TypedBlendReference._
  private case class Work(source: Vector[BigInt],destination: BigInt,state: State,tag: Int,invalid: Boolean=false,format: Int=TypedColorFormat.id("RGBA8")) {
    def expected: Vector[BigInt]=if(invalid)Vector.fill(4)(BigInt(0))else blend("RGBA8",source,destination,state)
  }
  private val special=Vector(0L,0x80000000L,1L,0x7fffffL,0x00800000L,0x3eaaaaabL,0x3f7fffffL,0x3f800000L,0x7f800000L,0xff800000L,0x7fc00001L).map(BigInt(_))
  private def work(i: Int): Work={
    val src=Vector.tabulate(4)(c=>if(i%7==0)special((i/7+c)%special.size)else fp(((i*17+c*29)%130-10)/100.0))
    val dst=(0 until 4).foldLeft(BigInt(0))((value,c)=>value|(BigInt((i*(2*c+1)+c*17)&255)<<(8*c)))
    val state=State(i%15,(i/15)%15,(i+7)%15,(i/15+11)%15,(i/225)%5,(i/225+2)%5,
      Vector.tabulate(4)(c=>if(i%11==0)special((i+c)%special.size)else fp(((i*3+c*13)%140-20)/100.0)),i<1125 || i%29!=0)
    Work(src,dst,state,i&255)
  }
  private def initialize(io: TypedColorBlendIO): Unit={io.in.valid.poke(false.B);io.out.ready.poke(false.B);io.cancel.poke(false.B)}
  private def offer(io: TypedColorBlendIO,w: Work): Unit={
    io.in.bits.source.zip(w.source).foreach{case(a,b)=>a.poke(b.U)};io.in.bits.destination.poke(w.destination.U)
    poke(io.in.bits.state,w.state);io.in.bits.format.poke(w.format.U);io.in.bits.tag.poke(w.tag.U);io.in.valid.poke(true.B)
  }
  private def check(io: TypedColorBlendIO,w: Work,cancelled: Boolean=false): Unit={
    io.out.bits.tag.expect(w.tag.U);io.out.bits.status.expect((if(w.invalid)1 else 0).U);io.out.bits.cancelled.expect(cancelled.B)
    io.out.bits.rgba.zip(w.expected).foreach{case(a,b)=>a.expect(b.U)}
    if(!w.state.enable || w.invalid)io.out.bits.arithmeticStatus.expect(0.U)
  }
  it should "accept and retire one complete RGBA8 pixel every clock across all factor pairs and equations" in {
    simulate(new StreamingRgba8Blend){d=>
      initialize(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.out.ready.poke(true.B)
      val n=1536;var sent=0;var received=0;var cycle=0;var previousReply= -1;var firstReply= -1
      while(received<n && cycle<n+256){
        if(sent<n){offer(d.io,work(sent));d.io.in.ready.expect(true.B)}else d.io.in.valid.poke(false.B)
        if(d.io.out.valid.peek().litToBoolean){
          if(firstReply<0)firstReply=cycle
          check(d.io,work(received));if(previousReply>=0)assert(cycle==previousReply+1,"RGBA8 arithmetic inserted a reply bubble")
          previousReply=cycle;received+=1
        }
        if(sent<n)sent+=1
        d.clock.step();cycle+=1
      }
      assert(sent==n && received==n);d.io.busy.expect(false.B);d.io.out.valid.expect(false.B)
      info(s"STREAMING_RGBA8 pixels=$n admissionII=1 responseII=1 latencyClocks=$firstReply factors=15 factorPairs=225 equations=5 separateAlpha=true exactFP32=true allUNORM8Codes=true disabledBypass=true specialSources=true")
    }
  }
  it should "retain queued pixels through full backpressure and cancellation then resume a healthy cohort" in {
    simulate(new StreamingRgba8Blend){d=>
      initialize(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val accepted=mutable.Queue.empty[Work];var sent=0;var cycle=0
      while((sent<32 || d.io.in.ready.peek().litToBoolean) && cycle<200){
        val w=work(sent);offer(d.io,w)
        if(d.io.in.ready.peek().litToBoolean){accepted.enqueue(w);sent+=1}
        d.clock.step();cycle+=1
      }
      assert(sent>=32 && sent<64);d.io.in.valid.poke(false.B);d.io.out.valid.expect(true.B)
      val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 8){d.clock.step();d.io.in.ready.expect(false.B);d.io.busy.expect(true.B);assert(d.io.out.bits.peek().litValue==held)}
      d.io.cancel.poke(false.B);var retired=0;var heldReply: Option[BigInt]=None
      while(accepted.nonEmpty && cycle<1000){
        val ready=cycle%4!=0;d.io.out.ready.poke(ready.B);d.io.in.ready.expect(false.B)
        if(d.io.out.valid.peek().litToBoolean){
          val bits=d.io.out.bits.peek().litValue;heldReply.foreach(x=>assert(bits==x,"held RGBA8 result changed"))
          check(d.io,accepted.front,cancelled=retired!=0)
          if(ready){accepted.dequeue();retired+=1;heldReply=None}else heldReply=Some(bits)
        }
        d.clock.step();cycle+=1
      }
      assert(accepted.isEmpty && retired==sent);d.io.busy.expect(false.B);d.io.out.ready.poke(true.B)
      d.clock.step(2);val healthy=work(1701);offer(d.io,healthy);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      var wait=0;while(!d.io.out.valid.peek().litToBoolean && wait<100){d.clock.step();wait+=1}
      assert(wait<100);check(d.io,healthy);d.clock.step();d.io.busy.expect(false.B)
      info(s"STREAMING_RGBA8 queued=$sent fullBackpressure=true heldReplyImmutable=true cancelledCohortDrained=true noNewAdmissionDuringDrain=true healthyReuse=true")
    }
  }
  it should "preserve serial FP32 flags, bypass rules and invalid-state results" in {
    simulate(new Rgba8BlendComparison){d=>
      initialize(d.io.streaming);initialize(d.io.serial);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val directed=Vector.tabulate(16)(i=>work(7*i).copy(state=State((i*3)%15,(i*7)%15,10,(i+5)%15,i%5,(i+2)%5))) ++
        Vector(work(500).copy(state=State(enable=false)),work(501).copy(state=State(src=15),invalid=true),work(502).copy(format=63,invalid=true))
      for(w<-directed){
        offer(d.io.streaming,w);offer(d.io.serial,w);d.io.streaming.in.ready.expect(true.B);d.io.serial.in.ready.expect(true.B)
        d.clock.step();d.io.streaming.in.valid.poke(false.B);d.io.serial.in.valid.poke(false.B)
        var cycles=0
        while((!d.io.streaming.out.valid.peek().litToBoolean || !d.io.serial.out.valid.peek().litToBoolean) && cycles<1500){d.clock.step();cycles+=1}
        assert(cycles<1500);check(d.io.streaming,w);check(d.io.serial,w)
        d.io.streaming.out.bits.arithmeticStatus.expect(d.io.serial.out.bits.arithmeticStatus.peek().litValue.U)
        val a=d.io.streaming.out.bits.peek().litValue;val b=d.io.serial.out.bits.peek().litValue
        d.io.streaming.cancel.poke(true.B);d.io.serial.cancel.poke(true.B);d.clock.step(3)
        assert(d.io.streaming.out.bits.peek().litValue==a && d.io.serial.out.bits.peek().litValue==b)
        d.io.streaming.cancel.poke(false.B);d.io.serial.cancel.poke(false.B)
        d.io.streaming.out.ready.poke(true.B);d.io.serial.out.ready.poke(true.B);d.clock.step()
        d.io.streaming.out.ready.poke(false.B);d.io.serial.out.ready.poke(false.B);d.clock.step(2)
      }
      info(s"STREAMING_RGBA8 serialComparisons=${directed.size} arithmeticFlagsExact=true zeroOneProductsBypass=true invalidState=true invalidFormat=true heldCancellationImmutable=true")
    }
  }
}
