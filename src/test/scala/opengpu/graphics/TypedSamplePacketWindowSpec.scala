package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class NativePacketWindowHarness extends Module {
  val io=IO(new Bundle {
    val bind=Flipped(Decoupled(new TypedTilePixelDescriptor));val in=Flipped(Decoupled(new TaggedTypedPixelQuad))
    val work=Decoupled(new TypedSamplePacketWork(3));val complete=Flipped(Decoupled(new TypedSamplePacketDone(3)))
    val out=Decoupled(new TypedSamplePacketRetired);val cancel=Input(Bool());val drained=Output(Bool());val cancelSource=Output(Bool())
  })
  private val expand=Module(new TypedSamplePacketExpander)
  private val window=Module(new TypedSamplePacketWindow(4))
  expand.io.bind.valid:=io.bind.valid && window.io.drained;expand.io.bind.bits:=io.bind.bits
  io.bind.ready:=expand.io.bind.ready && window.io.drained
  expand.io.in<>io.in;expand.io.cancel:=io.cancel
  window.io.in<>expand.io.out;window.io.begin:=io.bind.fire;window.io.cancel:=io.cancel
  io.work<>window.io.work;window.io.complete<>io.complete;io.out<>window.io.out
  io.drained:=expand.io.drained && window.io.drained;io.cancelSource:=window.io.cancelSource
}

class TypedSamplePacketWindowSpec extends AnyFlatSpec {
  private case class Packet(tag: Int,base: Int,active: Int=15,color: Int=15,depth: Boolean=true,stencil: Boolean=true,depthOffset: Int=2048){
    val words=Vector(base,base+1,base+depthOffset,base+depthOffset+1)
    val channel=(tag%15)+1
  }
  private case class Task(token: Int,p: Packet,normal: BigInt,cancelMask: BigInt,issuedAt: Int)
  private class Driver(val d: TypedSamplePacketWindow){
    var cycle=0;val pending=mutable.Queue.empty[(Packet,BigInt,BigInt)]
    val retirement=mutable.Queue.empty[Packet];val tasks=mutable.ArrayBuffer.empty[Task]
    val terminals=mutable.Map.empty[Int,(BigInt,Int)]
    val admissions=mutable.ArrayBuffer.empty[Int];val completions=mutable.ArrayBuffer.empty[Int]
    var heldWork: Option[BigInt]=None;var heldOut: Option[BigInt]=None
    d.io.begin.poke(false.B);d.io.cancel.poke(false.B);d.io.in.valid.poke(false.B)
    d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.work.ready.poke(true.B)
    d.io.complete.valid.poke(false.B);d.io.complete.bits.poke(0.U.asTypeOf(d.io.complete.bits));d.io.out.ready.poke(true.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);begin()
    def begin(): Unit={d.io.drained.expect(true.B);d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)}
    def offer(p: Packet): (BigInt,BigInt)={
      val b=d.io.in.bits;b.poke(0.U.asTypeOf(b));b.tag.poke(p.tag.U);b.packet.poke((p.tag%4).U)
      b.last.poke((p.tag%4==3).B);b.channelMask.poke(p.channel.U);b.active.poke(p.active.U);b.colorActive.poke(p.color.U)
      b.tests.version.poke(1.U);b.tests.depthEnable.poke(p.depth.B);b.tests.stencilEnable.poke(p.stencil.B)
      b.frontFacing.poke((p.tag%2==0).B);b.blend.version.poke(1.U)
      for(i<-0 until 4){b.words(i).poke(p.words(i).U);b.depths(i).poke((p.tag*17+i).U)
        for(c<-0 until 4)b.colors(i)(c).poke((p.tag*31+i*7+c).U)}
      val normal=b.peek().litValue;b.cancelled.poke(true.B);val cancelled=b.peek().litValue;b.cancelled.poke(false.B)
      (normal,normal^cancelled)
    }
    def terminal(t: Task,status: Int): BigInt={
      val r=d.io.complete.bits;r.token.poke(t.token.U);r.result.poke(0.U.asTypeOf(r.result))
      r.result.status.poke(status.U);r.result.faultOffset.poke((if(status==0)0 else t.p.tag*97).U)
      r.result.tested.poke(t.p.active.U);r.result.passing.poke(t.p.active.U);r.result.colorWritten.poke((t.p.color&t.p.active).U)
      r.result.depthWritten.poke((if(t.p.depth)t.p.active else 0).U);r.result.stencilWritten.poke((if(t.p.stencil)(t.p.tag%16)&t.p.active else 0).U)
      r.result.dsReads.poke((if(t.p.depth || t.p.stencil)Integer.bitCount(t.p.active)else 0).U);r.result.dsFills.poke((if(t.p.active!=0 && (t.p.depth || t.p.stencil))t.p.tag%3 else 0).U)
      r.result.peek().litValue
    }
    def tick(in: Option[Packet]=None,done: Option[(Task,Int)]=None,outReady: Boolean=true,workReady: Boolean=true,
        status: (Int,Int)=>Int=(_,s)=>s): Boolean={
      d.io.in.valid.poke(in.nonEmpty.B);val snap=in.map(offer)
      d.io.complete.valid.poke(done.nonEmpty.B);val bits=done.map{case(t,s)=>terminal(t,s)}
      d.io.out.ready.poke(outReady.B);d.io.work.ready.poke(workReady.B)
      val accepted=in.nonEmpty && d.io.in.ready.peek().litToBoolean
      if(accepted){val p=in.get;val(normal,mask)=snap.get;pending.enqueue((p,normal,mask));retirement.enqueue(p);admissions+=cycle}
      val w=d.io.work
      heldWork.foreach(v=>{w.valid.expect(true.B);assert(w.bits.peek().litValue==v,"offered sample work changed")})
      if(w.valid.peek().litToBoolean){
        assert(pending.nonEmpty);val(p,normal,mask)=pending.front
        assert((w.bits.packet.peek().litValue& ~mask)==(normal& ~mask),"sample work lost packet snapshot")
        if(workReady){pending.dequeue();tasks+=Task(w.bits.token.peek().litValue.toInt,p,normal,mask,cycle);heldWork=None}
        else heldWork=Some(w.bits.peek().litValue)
      }
      if(done.nonEmpty){d.io.complete.ready.expect(true.B);val(t,s)=done.get;terminals(t.p.tag)=(bits.get,s)}
      val out=d.io.out
      heldOut.foreach(v=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==v,"held sample retirement changed")})
      if(out.valid.peek().litToBoolean){
        assert(retirement.nonEmpty);val p=retirement.front;assert(terminals.contains(p.tag))
        out.bits.tag.expect(p.tag.U);out.bits.packet.expect((p.tag%4).U);out.bits.last.expect((p.tag%4==3).B)
        out.bits.channelMask.expect(p.channel.U)
        val(expected,s)=terminals(p.tag);out.bits.result.status.expect(status(p.tag,s).U)
        // Mask only the status field by independently reading the same record
        // with the expected status; every other worker field must be retained.
        val saved=d.io.complete.bits.result.peek().litValue
        d.io.complete.bits.result.poke(expected.U.asTypeOf(d.io.complete.bits.result))
        d.io.complete.bits.result.status.poke(status(p.tag,s).U)
        assert(out.bits.result.peek().litValue==d.io.complete.bits.result.peek().litValue)
        d.io.complete.bits.result.poke(saved.U.asTypeOf(d.io.complete.bits.result))
        if(outReady){retirement.dequeue();completions+=cycle;heldOut=None}else heldOut=Some(out.bits.peek().litValue)
      }
      d.clock.step();cycle+=1;accepted
    }
    def until(condition: => Boolean,limit: Int=200): Unit={var n=0;while(!condition && n<limit){tick();n+=1};assert(condition,"packet window did not make progress")}
  }
  it should "overlap128 packet owners and sustain consecutive allocation work and ordered retirement" in {
    simulate(new TypedSamplePacketWindow){dut=>
      val d=new Driver(dut);val ready=mutable.Queue.empty[(Int,Task)];var next=0;var scheduled=0;var cycle=0
      while((next<512 || d.retirement.nonEmpty || ready.nonEmpty) && cycle<1000){
        while(scheduled<d.tasks.size){ready.enqueue((d.tasks(scheduled).issuedAt+64,d.tasks(scheduled)));scheduled+=1}
        val done=if(ready.headOption.exists(_._1<=d.cycle))Some((ready.dequeue()._2,0))else None
        if(d.tick(if(next<512)Some(Packet(next,(next%256)*4))else None,done))next+=1
        cycle+=1
      }
      assert(next==512 && d.completions.size==512 && d.tasks.size==512);dut.io.drained.expect(true.B)
      assert(d.admissions.sliding(2).forall(x=>x(1)==x(0)+1))
      assert(d.completions.sliding(2).forall(x=>x(1)==x(0)+1))
      info("TYPED_PACKET_WINDOW contexts=128 packets=512 allocationII=1 retirementII=1 workerLatency=64 wordReservations=true orderedRetirement=true")
    }
  }
  it should "reserve only accessed planes and allow empty or depth-only packets past unrelated color owners" in {
    simulate(new TypedSamplePacketWindow(4)){dut=>
      val d=new Driver(dut)
      d.tick(Some(Packet(0,0)));d.until(d.tasks.size==1)
      d.tick(Some(Packet(1,0,active=0,color=0)));d.until(d.tasks.size==2)
      d.tick(Some(Packet(2,0,color=0,depthOffset=1024)));d.until(d.tasks.size==3)
      d.tick(Some(Packet(3,0)))
      for(_<-0 until 8)d.tick()
      assert(d.tasks.size==3,"a packet acquired a still-owned color/depth word")
      d.tick(done=Some((d.tasks.head,0)));d.until(d.tasks.size==4)
      for(i<-Seq(3,2,1))d.tick(done=Some((d.tasks(i),0)))
      d.until(dut.io.drained.peek().litToBoolean)
    }
  }
  it should "hold alias reservations through terminal acknowledgement and preserve generations behind stalled ordered output" in {
    simulate(new TypedSamplePacketWindow(4)){dut=>
      val d=new Driver(dut)
      d.tick(Some(Packet(0,0)));d.until(d.tasks.size==1)
      d.tick(Some(Packet(1,0)));d.tick(Some(Packet(2,8)))
      for(_<-0 until 8)d.tick()
      assert(d.tasks.size==1,"a younger alias passed a live word reservation")
      d.tick(done=Some((d.tasks.head,0)));d.until(d.tasks.size==3)
      d.until(d.completions.size==1)
      d.tick(done=Some((d.tasks(2),0)),outReady=false)
      for(_<-0 until 4){d.tick(outReady=false);dut.io.out.valid.expect(false.B)}
      d.tick(done=Some((d.tasks(1),0)),outReady=false)
      d.tick(outReady=false);d.tick(outReady=false);dut.io.out.valid.expect(true.B)
      val held=dut.io.out.bits.peek().litValue
      // Packet1 has physically completed, so a new alias may progress even
      // though its already formed ordered retirement is still held.
      d.tick(Some(Packet(3,0)),outReady=false);d.tick(outReady=false);d.tick(outReady=false)
      assert(d.tasks.size==4);assert(dut.io.out.bits.peek().litValue==held)
      d.tick(done=Some((d.tasks(3),0)),outReady=false)
      for(_<-0 until 6){d.tick(outReady=false);assert(dut.io.out.bits.peek().litValue==held)}
      d.until(dut.io.drained.peek().litToBoolean)
      val old=d.tasks.head.token
      d.tick(Some(Packet(4,0)));d.until(d.tasks.size==5)
      val reused=d.tasks.last;assert((old&3)==(reused.token&3) && old!=reused.token,"slot generation did not change on reuse")
      dut.io.complete.bits.token.poke(old.U);dut.io.complete.valid.poke(false.B);dut.io.complete.ready.expect(false.B)
      d.tick(done=Some((reused,0)));d.until(dut.io.drained.peek().litToBoolean)
    }
  }
  it should "preserve offered output on cancel while draining accepted upstream packets without alias deadlock" in {
    simulate(new NativePacketWindowHarness){d=>
      d.io.bind.valid.poke(false.B);d.io.bind.bits.poke(0.U.asTypeOf(d.io.bind.bits))
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.cancel.poke(false.B)
      d.io.work.ready.poke(false.B);d.io.complete.valid.poke(false.B);d.io.complete.bits.poke(0.U.asTypeOf(d.io.complete.bits))
      d.io.out.ready.poke(false.B);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val b=d.io.bind.bits;b.version.poke(1.U);b.colors.version.poke(1.U);b.colors.tileLog2.poke(3.U)
      b.colors.sampleMode.poke(2.U);b.colors.tileWidth.poke(8.U);b.colors.tileHeight.poke(8.U)
      b.colors.colors(0).enabled.poke(true.B);b.colors.colors(0).format.poke(TypedColorFormat.id("RGBA8").U)
      b.colors.colors(0).offset.poke(1024.U);b.colors.colors(0).channelMask.poke(15.U);b.depth.format.poke(TypedDepthFormat.d24s8.U)
      d.io.bind.valid.poke(true.B);d.io.bind.ready.expect(true.B);d.clock.step();d.io.bind.valid.poke(false.B)
      val q=d.io.in.bits.quad;q.color.quad.sampleMode.poke(2.U);q.color.quad.colorSlots.poke(1.U);q.state.version.poke(1.U)
      q.state.depthEnable.poke(true.B);q.state.stencilEnable.poke(true.B);q.color.blend.version.poke(1.U)
      for(l<-0 until 4){q.color.quad.lanes(l).x.poke((l%2).S);q.color.quad.lanes(l).y.poke((l/2).S)
        q.color.quad.lanes(l).colorMask.poke(1.U);q.color.samples(l).poke(15.U)}
      var accepted=0
      for(_<-0 until 20){d.io.in.valid.poke(true.B);d.io.in.bits.tag.poke(accepted.U)
        if(d.io.in.ready.peek().litToBoolean)accepted+=1;d.clock.step()}
      assert(accepted>=2);d.io.work.valid.expect(true.B);val held=d.io.work.bits.peek().litValue
      d.io.cancel.poke(true.B);d.io.in.valid.poke(false.B)
      for(_<-0 until 6){d.clock.step();assert(d.io.work.bits.peek().litValue==held);d.io.bind.ready.expect(false.B)}
      d.io.cancel.poke(false.B);d.io.cancelSource.expect(true.B);d.io.work.ready.poke(true.B)
      val pending=mutable.Queue.empty[(Int,Int)];var clock=0;var retired=0;var dispatched=0
      while(retired<accepted*4 && clock<500){
        val complete=pending.headOption.filter(_._1<=clock)
        d.io.complete.valid.poke(complete.nonEmpty.B)
        complete.foreach{case(_,token)=>d.io.complete.bits.token.poke(token.U);d.io.complete.bits.result.poke(0.U.asTypeOf(d.io.complete.bits.result))}
        val ready=clock%3!=0;d.io.out.ready.poke(ready.B)
        if(d.io.work.valid.peek().litToBoolean){pending.enqueue((clock+5,d.io.work.bits.token.peek().litValue.toInt));dispatched+=1}
        if(complete.nonEmpty){d.io.complete.ready.expect(true.B);pending.dequeue()}
        if(d.io.out.valid.peek().litToBoolean){
          d.io.out.bits.tag.expect((retired/4).U);d.io.out.bits.packet.expect((retired%4).U)
          d.io.out.bits.last.expect((retired%4==3).B);d.io.out.bits.result.status.expect(4.U)
          if(ready)retired+=1
        }
        d.clock.step();clock+=1
      }
      assert(retired==accepted*4 && dispatched==accepted*4);d.io.drained.expect(true.B);d.io.cancelSource.expect(true.B)
      d.io.bind.valid.poke(true.B);d.io.bind.ready.expect(true.B);d.clock.step();d.io.bind.valid.poke(false.B);d.io.cancelSource.expect(false.B)
      d.io.complete.valid.poke(false.B);d.io.in.bits.tag.poke(100.U);d.io.in.valid.poke(true.B)
      d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      // A new bound pass must produce healthy work after the canceled prefix.
      var cycles=0;while(!d.io.work.valid.peek().litToBoolean && cycles<20){d.clock.step();cycles+=1}
      assert(cycles<20);d.io.work.bits.packet.tag.expect(100.U);d.io.work.bits.packet.cancelled.expect(false.B)
      val healthy=mutable.Queue.empty[(Int,Int)];var completed=0;var step=0
      d.io.out.ready.poke(true.B)
      while(completed<4 && step<100){
        val reply=healthy.headOption.filter(_._1<=step)
        d.io.complete.valid.poke(reply.nonEmpty.B)
        reply.foreach{case(_,token)=>d.io.complete.bits.token.poke(token.U);d.io.complete.bits.result.poke(0.U.asTypeOf(d.io.complete.bits.result))}
        if(d.io.work.valid.peek().litToBoolean){
          d.io.work.bits.packet.cancelled.expect(false.B);healthy.enqueue((step+2,d.io.work.bits.token.peek().litValue.toInt))
        }
        if(reply.nonEmpty){d.io.complete.ready.expect(true.B);healthy.dequeue()}
        if(d.io.out.valid.peek().litToBoolean){
          d.io.out.bits.tag.expect(100.U);d.io.out.bits.packet.expect(completed.U);d.io.out.bits.result.status.expect(0.U);completed+=1
        }
        d.clock.step();step+=1
      }
      assert(completed==4 && healthy.isEmpty);d.io.drained.expect(true.B)
    }
  }
}
