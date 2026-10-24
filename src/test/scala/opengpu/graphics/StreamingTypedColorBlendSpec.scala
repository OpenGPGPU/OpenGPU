package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class StreamingTypedBlendComparison extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedColorBlendRequest));val cancel=Input(Bool())
    val out=Decoupled(new Bundle {val serial=new TypedColorBlendResult;val parallel=new TypedColorBlendResult})
    val serialOffered=Output(Bool());val parallelOffered=Output(Bool())
  })
  private val serial=Module(new TypedColorBlend);private val parallel=Module(new StreamingTypedColorBlend)
  io.in.ready:=serial.io.in.ready && parallel.io.in.ready
  serial.io.in.valid:=io.in.valid && parallel.io.in.ready;parallel.io.in.valid:=io.in.valid && serial.io.in.ready
  serial.io.in.bits:=io.in.bits;parallel.io.in.bits:=io.in.bits
  serial.io.cancel:=io.cancel;parallel.io.cancel:=io.cancel
  io.out.valid:=serial.io.out.valid && parallel.io.out.valid
  io.out.bits.serial:=serial.io.out.bits;io.out.bits.parallel:=parallel.io.out.bits
  serial.io.out.ready:=io.out.ready && parallel.io.out.valid
  parallel.io.out.ready:=io.out.ready && serial.io.out.valid
  io.serialOffered:=serial.io.out.valid;io.parallelOffered:=parallel.io.out.valid
}

class StreamingTypedColorBlendSpec extends AnyFlatSpec {
  import TypedColorReference.{fp,encode}
  import TypedBlendReference.{State,blend,poke}
  private val source=Vector(0.25,0.8,0.125,0.6).map(fp)
  private val destination=Vector(0.7,0.2,0.4,0.3).map(fp)
  private class Driver(d: StreamingTypedBlendComparison) {
    d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
    d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
    var count=0
    def run(name: String,state: State=State(),src: Vector[BigInt]=source,dst: Vector[BigInt]=destination,
        cancelAt: Int= -1,lateCancel: Boolean=false,badFormat: Boolean=false,badState: Boolean=false): (Int,Int)={
      val packed=encode(name,dst)._1;val tag=count&255
      d.io.in.bits.format.poke((if(badFormat)63 else TypedColorFormat.id(name)).U)
      d.io.in.bits.source.zip(src).foreach{case(a,b)=>a.poke(b.U)};d.io.in.bits.destination.poke(packed.U)
      poke(d.io.in.bits.state,state);if(badState)d.io.in.bits.state.srcRGB.poke(15.U)
      d.io.in.bits.tag.poke(tag.U);d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B)
      d.clock.step();d.io.in.valid.poke(false.B)
      // Live input changes cannot affect the accepted descriptor or its decoder.
      d.io.in.bits.format.poke(63.U);d.io.in.bits.tag.poke(255.U)
      d.io.in.bits.source.foreach(_.poke("hffffffff".U));d.io.in.bits.destination.poke(0.U)
      var cycle=0;var serialAt= -1;var parallelAt= -1;var lateSent=false
      while(!d.io.out.valid.peek().litToBoolean && cycle<1000){
        val s=d.io.serialOffered.peek().litToBoolean;val p=d.io.parallelOffered.peek().litToBoolean
        if(s && serialAt<0)serialAt=cycle
        if(p && parallelAt<0)parallelAt=cycle
        val late=lateCancel && p && !s && !lateSent
        d.io.cancel.poke((cycle==cancelAt || late).B);if(late)lateSent=true
        d.clock.step();cycle+=1
      }
      assert(cycle<1000,s"parallel blend timeout request=$count format=$name")
      d.io.cancel.poke(false.B)
      if(serialAt<0)serialAt=cycle;if(parallelAt<0)parallelAt=cycle
      val s=d.io.out.bits.serial;val p=d.io.out.bits.parallel
      for(r<-Seq(s,p)){r.tag.expect(tag.U);r.status.expect((if(badFormat || badState)1 else 0).U)}
      if(!badFormat && !badState){
        val expected=blend(name,src,packed,state)
        for(r<-Seq(s,p))r.rgba.zip(expected).foreach{case(a,b)=>a.expect(b.U)}
      }
      p.rgba.zip(s.rgba).foreach{case(a,b)=>a.expect(b.peek())};p.arithmeticStatus.expect(s.arithmeticStatus.peek())
      s.cancelled.expect((cancelAt>=0 || lateSent).B);p.cancelled.expect((cancelAt>=0).B)
      if(lateCancel)assert(lateSent,"latency separation did not expose a held parallel result")
      val held=d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B);d.clock.step(3)
      assert(d.io.out.bits.peek().litValue==held,"offered blend result changed on late cancellation")
      d.io.in.ready.expect(false.B);d.io.cancel.poke(false.B)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B)
      var cooldown=0
      while(!d.io.in.ready.peek().litToBoolean && cooldown<4){d.clock.step();cooldown+=1}
      d.io.in.ready.expect(true.B);count+=1
      (serialAt,parallelAt)
    }
  }

  it should "match serial and independent FP32 results across every format factor and equation" in {
    simulate(new StreamingTypedBlendComparison){d=>val t=new Driver(d)
      for(f<-TypedColorFormat.formats if f.kind==0;factor<-0 until 15){
        t.run(f.name,State(factor,(factor+7)%15,(factor+3)%15,(factor+11)%15,factor%5,(factor+2)%5))
      }
      val formats=Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8","RGBA32F")
      for(a<-0 until 15;b<-0 until 15){
        t.run(formats((a*15+b)%formats.size),State(a,b,(a+7)%15,(b+11)%15,(a+b)%5,(a*2+b)%5))
      }
      for(f<-TypedColorFormat.formats if f.kind!=0)t.run(f.name)
      t.run("RGBA8",State(enable=false));t.run("RGBA32F",State(enable=false))
      info(s"STREAMING_TYPED_BLEND numericRequests=${t.count} formats=36 factors=15 factorPairs=225 equations=5 independentReference=true exactSerialValuesAndFlags=true inputSnapshot=true heldReplies=true")
    }
  }

  it should "preserve special values cancellation drain invalid inputs and immediate reuse" in {
    simulate(new StreamingTypedBlendComparison){d=>val t=new Driver(d)
      val special=Vector(0L,0x80000000L,1L,0x7fffffL,0x00800000L,0x7f7fffffL,0x7f800000L,0xff800000L,0x7fc00001L).map(BigInt(_))
      for(raw<-special;eq<-0 until 5){
        t.run("RGBA32F",State(4,5,1,1,eq,eq),Vector(raw,fp(0.5),raw,fp(1)),Vector(fp(0),raw,fp(1),fp(0.5)))
      }
      val costly=State(11,12,13,14,0,2)
      t.run("RGBA8",costly,cancelAt=3);t.run("RGBA16F",costly,cancelAt=20)
      t.run("RGBA16F",costly,lateCancel=true)
      t.run("RGBA32UI",cancelAt=0);t.run("RGBA8",badFormat=true);t.run("RGBA8",badState=true)
      t.run("RGBA8",costly)
      info(s"STREAMING_TYPED_BLEND lifetimeRequests=${t.count} specialCases=45 exactArithmeticFlags=true earlyCancel=true heldLateCancel=true invalidFormatAndState=true integerBypass=true healthyReuse=true")
    }
  }

  it should "reduce whole blend latency for each measured mixed-target format" in {
    simulate(new StreamingTypedBlendComparison){d=>val t=new Driver(d)
      for(name<-Vector("RGBA8","RGB8","RGBA16F","SRGB8_ALPHA8")){
        val(serial,parallel)=t.run(name,State(11,12,13,14,0,2))
        assert(parallel*2<serial,s"component arithmetic did not halve latency for $name: $parallel vs $serial")
        info(s"STREAMING_TYPED_BLEND_RATE format=$name serialClocks=$serial parallelClocks=$parallel fp32Units=20 sharedDecoders=1 exactOutput=true")
      }
    }
  }

  private def stream(d: StreamingTypedColorBlend,n: Int,heldCancel: Boolean): Unit={
    val expected=scala.collection.mutable.Queue.empty[(Vector[BigInt],Int,Int,Boolean)]
    var sent=0;var retired=0;var cycle=0;var peak=0;var longest=0;var consecutive=0;var last= -1
    var held: Option[BigInt]=None;var firstOffer= -1;var cancelled=0;var latency= -1
    while(retired<n && cycle<n+1000){
      val valid=sent<n;val format=TypedColorFormat.formats(sent%36)
      val src=Vector.tabulate(4)(c=>if(format.kind==0)fp(((sent+c)%17).toDouble/16)else BigInt(sent*7+c))
      val dst=encode(format.name,Vector(0.7,0.2,0.4,0.3).map(fp))._1
      val state=State(sent%15,(sent+7)%15,(sent+3)%15,(sent+11)%15,sent%5,(sent+2)%5)
      val bad=sent%53==17;val badState=sent%59==19
      d.io.in.valid.poke(valid.B);d.io.in.bits.format.poke((if(bad)63 else sent%36).U)
      d.io.in.bits.destination.poke(dst.U);d.io.in.bits.source.zip(src).foreach{case(a,b)=>a.poke(b.U)}
      d.io.in.bits.tag.poke((sent%256).U);poke(d.io.in.bits.state,state)
      if(badState)d.io.in.bits.state.srcRGB.poke(15.U)
      d.io.cancel.poke((heldCancel && cycle==60).B)
      val ready= !heldCancel || (cycle>=80 && cycle%4!=0)
      d.io.out.ready.poke(ready.B)
      if(held.nonEmpty){d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held.get)}
      if(d.io.out.valid.peek().litToBoolean){
        if(firstOffer<0)firstOffer=cycle
        assert(expected.nonEmpty,"streaming blend produced an unowned result")
        val(values,tag,accepted,error)=expected.front
        d.io.out.bits.rgba.zip(values).foreach{case(a,b)=>a.expect(b.U)}
        d.io.out.bits.tag.expect(tag.U);d.io.out.bits.status.expect((if(error)1 else 0).U)
        val cancel=heldCancel && retired>0
        d.io.out.bits.cancelled.expect(cancel.B)
        if(!heldCancel){
          if(latency<0)latency=cycle-accepted
          assert(cycle-accepted==latency,"format/state changes altered dense pipeline latency")
        }
        if(ready){
          expected.dequeue();retired+=1;if(cancel)cancelled+=1
          consecutive=if(last==cycle-1)consecutive+1 else 1;longest=longest.max(consecutive);last=cycle;held=None
        }else held=Some(d.io.out.bits.peek().litValue)
      }else held=None
      if(valid && d.io.in.ready.peek().litToBoolean){
        val error=bad || badState
        val values=if(error)Vector.fill(4)(BigInt(0))else blend(format.name,src,dst,state)
        expected.enqueue((values,sent%256,cycle,error));sent+=1
      }else if(valid && !heldCancel)assert(false,"typed blend did not accept a dense vector each clock")
      peak=peak.max(expected.size);assert(expected.size<=64)
      d.clock.step();cycle+=1
    }
    assert(sent==n && retired==n && expected.isEmpty)
    if(heldCancel){assert(firstOffer<60 && cancelled==n-1)}else assert(longest==n)
    d.io.in.valid.poke(false.B);d.io.cancel.poke(false.B);d.io.out.ready.poke(true.B);d.clock.step(3)
    d.io.busy.expect(false.B);d.io.out.valid.expect(false.B);d.io.in.ready.expect(true.B)
    info(s"STREAMING_TYPED_BLEND_DENSE requests=$n heldCancel=$heldCancel clocks=$cycle latency=$latency consecutiveReplies=$longest peakOwned=$peak cancelled=$cancelled exactMixedFormats=true")
  }
  it should "sustain exact mixed-format vectors every clock and retain cancellation cohorts through a held result" in {
    simulate(new StreamingTypedColorBlend){d=>
      d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.reset.poke(true.B);d.clock.step(2);d.reset.poke(false.B)
      stream(d,512,heldCancel=false)
      stream(d,32,heldCancel=true)
      stream(d,128,heldCancel=false)
    }
  }

}
