package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class MacroShaderPayloadStore(p: ShaderPayloadConfig) extends BankedShaderPayloadStore(p,true) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new TileSramSimulationResources)
  macroModelPresent:=resources.io.present
}

class BankedShaderPayloadStoreSpec extends AnyFlatSpec {
  private val cfg=ShaderPayloadConfig(capacityBytes=512,maxPayloadBytes=128,owners=2,ports=3)
  private val full=(BigInt(1)<<64)-1
  private case class Lease(handle: Int,generation: BigInt,context: BigInt,owner: Int,bytes: Int)
  private def model(macros: Boolean,p: ShaderPayloadConfig=cfg): BankedShaderPayloadStore=
    if(macros)new MacroShaderPayloadStore(p)else new BankedShaderPayloadStore(p)
  private class Driver(val d: BankedShaderPayloadStore,val p: ShaderPayloadConfig=cfg) {
    for(c <- d.io.control) {c.valid.poke(false.B);c.bits.poke(0.U.asTypeOf(c.bits))}
    for(c <- d.io.controlResponse)c.ready.poke(false.B)
    for(r <- d.io.request) {r.valid.poke(false.B);r.bits.poke(0.U.asTypeOf(r.bits))}
    for(r <- d.io.response)r.ready.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    def until(condition: => Boolean): Unit={
      var n=0;while(!condition && n<10000){d.clock.step();n+=1}
      assert(condition,"payload service did not progress")
    }
    def offerControl(who: Int,op: Int,l: Lease=Lease(0,0,0,0,0),n: Int=128,spill: Boolean=false,tag: Int=0): Unit={
      val b=d.io.control(who).bits
      b.operation.poke(op.U);b.tag.poke(tag.U);b.bytes.poke(n.U);b.allowSpill.poke(spill.B)
      b.handle.poke(l.handle.U);b.generation.poke(l.generation.U);b.context.poke(l.context.U)
      d.io.control(who).valid.poke(true.B)
    }
    def control(who: Int,op: Int,l: Lease=Lease(0,0,0,0,0),n: Int=128,spill: Boolean=false,
        fault: Boolean=false,isSpill: Boolean=false): Lease={
      offerControl(who,op,l,n,spill)
      until(d.io.control(who).ready.peek().litToBoolean)
      d.clock.step();d.io.control(who).valid.poke(false.B)
      until(d.io.controlResponse(who).valid.peek().litToBoolean)
      val b=d.io.controlResponse(who).bits
      b.fault.expect(fault.B);b.spill.expect(isSpill.B)
      val result=Lease(b.handle.peek().litValue.toInt,b.generation.peek().litValue,
        b.context.peek().litValue,who,b.bytes.peek().litValue.toInt)
      val snapshot=b.peek().litValue
      d.clock.step(3);assert(b.peek().litValue==snapshot,"held control reply changed")
      d.io.controlResponse(who).ready.poke(true.B);d.clock.step();d.io.controlResponse(who).ready.poke(false.B)
      result
    }
    def allocate(who: Int,n: Int=128,context: Int=19): Lease=
      control(who,ShaderPayloadControl.allocate,l=Lease(0,0,context,who,0),n=n)
    def offer(port: Int,l: Lease,offset: Int=0,write: Boolean=false,data: BigInt=0,mask: BigInt=full,tag: Int=0): Unit={
      val b=d.io.request(port).bits
      b.handle.poke(l.handle.U);b.generation.poke(l.generation.U);b.context.poke(l.context.U)
      b.owner.poke(l.owner.U);b.offset.poke(offset.U);b.write.poke(write.B)
      b.data.poke(data.U);b.mask.poke(mask.U);b.tag.poke(tag.U)
      d.io.request(port).valid.poke(true.B)
    }
    def submit(port: Int,l: Lease,offset: Int=0,write: Boolean=false,data: BigInt=0,mask: BigInt=full,tag: Int=0): Unit={
      offer(port,l,offset,write,data,mask,tag)
      until(d.io.request(port).ready.peek().litToBoolean)
      d.clock.step();d.io.request(port).valid.poke(false.B)
    }
    def reply(port: Int,l: Lease,fault: Boolean=false,data: BigInt=0,tag: Int=0): Unit={
      until(d.io.response(port).valid.peek().litToBoolean)
      val b=d.io.response(port).bits
      b.handle.expect(l.handle.U);b.generation.expect(l.generation.U);b.context.expect(l.context.U)
      b.fault.expect(fault.B);b.data.expect(data.U);b.tag.expect(tag.U)
      val snapshot=b.peek().litValue;d.clock.step(5)
      assert(b.peek().litValue==snapshot,"held payload reply changed")
      d.io.response(port).ready.poke(true.B);d.clock.step();d.io.response(port).ready.poke(false.B)
    }
    def access(port: Int,l: Lease,offset: Int=0,write: Boolean=false,data: BigInt=0,mask: BigInt=full,
        fault: Boolean=false,expected: BigInt=0,tag: Int=0): Unit={
      submit(port,l,offset,write,data,mask,tag);reply(port,l,fault,expected,tag)
    }
  }

  it should "reserve complete slots within the shared byte budget" in {
    val p=ShaderPayloadConfig()
    assert(p.slots==14 && p.slotBytes==4608 && p.reservedBytes==64512)
    assert(p.physicalBytes==65536 && p.segments*16==64)
    assert(cfg.physicalBytes==16384 && cfg.reservedBytes==512)
    assert(ShaderPayloadConfig(maxPayloadBytes=32768).slots==2)
  }

  for(macros <- Seq(false,true)) {
  it should s"zero a reused lease and preserve every masked byte through synchronous SRAM macros=$macros" in {
    simulate(model(macros)) { d=>
      val t=new Driver(d);val l=t.allocate(0)
      t.access(0,l,expected=0)
      val original=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(b+1)<<(8*b)))
      t.access(0,l,write=true,data=original)
      val mask=(0 until 64).filter(b=>b%3==1).foldLeft(BigInt(0))((m,b)=>m.setBit(b))
      val replacement=(BigInt(1)<<512)-1
      t.access(1,l,write=true,data=replacement,mask=mask)
      val expected=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|((if(mask.testBit(b))BigInt(255)else BigInt(b+1))<<(8*b)))
      t.access(2,l,expected=expected)
      t.access(0,l,offset=64,expected=0)
      t.control(0,ShaderPayloadControl.release,l)
      val next=t.allocate(1)
      assert(next.handle==l.handle && next.generation!=l.generation)
      t.access(1,next,expected=0);t.access(2,next,offset=64,expected=0)
      t.control(1,ShaderPayloadControl.release,next);d.io.drained.expect(true.B)
    }
  }

  it should s"reject foreign stale misaligned and tail-overrun accesses without corrupting live data macros=$macros" in {
    simulate(model(macros)) { d=>
      val t=new Driver(d);val l=t.allocate(0,n=80)
      t.access(0,l,offset=64,write=true,data=0x12345678,mask=15)
      t.access(0,l,offset=64,mask=(BigInt(1)<<16)-1,expected=0x12345678)
      for(bad <- Seq(l.copy(owner=1),l.copy(generation=l.generation+1),l.copy(context=77)))
        t.access(1,bad,write=true,data=full,fault=true)
      t.access(1,l,offset=4,write=true,data=full,fault=true)
      t.access(1,l,offset=64,write=true,data=full,mask=BigInt(1)<<16,fault=true)
      t.access(1,l,offset=128,mask=0,fault=true)
      t.access(2,l,offset=64,mask=15,expected=0x12345678)
      t.control(1,ShaderPayloadControl.release,l,fault=true)
      t.control(0,ShaderPayloadControl.release,l);d.io.drained.expect(true.B)
    }
  }

  it should s"retain a canceled lease until its accepted operation and held reply drain macros=$macros" in {
    simulate(model(macros)) { d=>
      val t=new Driver(d);val l=t.allocate(0)
      t.access(0,l)
      t.submit(1,l,tag=9)
      t.until(d.io.response(1).valid.peek().litToBoolean)
      val held=d.io.response(1).bits.peek().litValue
      t.submit(0,l,write=true,data=full,tag=7)
      t.control(0,ShaderPayloadControl.cancel,l)
      d.io.response(1).valid.expect(true.B)
      assert(d.io.response(1).bits.peek().litValue==held,"cancellation changed an offered reply")
      t.offerControl(0,ShaderPayloadControl.release,l)
      for(_ <- 0 until 12){d.io.control(0).ready.expect(false.B);d.clock.step()}
      t.reply(0,l,fault=true,tag=7)
      d.io.control(0).ready.expect(false.B)
      t.reply(1,l,tag=9)
      t.until(d.io.control(0).ready.peek().litToBoolean)
      d.clock.step();d.io.control(0).valid.poke(false.B)
      t.until(d.io.controlResponse(0).valid.peek().litToBoolean)
      d.io.controlResponse(0).bits.fault.expect(false.B)
      d.io.controlResponse(0).ready.poke(true.B);d.clock.step();d.io.controlResponse(0).ready.poke(false.B)
      val next=t.allocate(0)
      assert(next.handle==l.handle && next.generation!=l.generation)
      t.access(1,l,fault=true)
      t.access(2,next,expected=0)
      t.control(0,ShaderPayloadControl.release,next);d.io.drained.expect(true.B)
    }
  }

  it should s"skip a blocked allocation so another owner can cancel and release progress capacity macros=$macros" in {
    simulate(model(macros)) { d=>
      val t=new Driver(d);val leases=Vector.tabulate(cfg.slots)(i=>t.allocate(1,context=100+i))
      d.io.reservedBytes.expect(512.U)
      t.control(0,ShaderPayloadControl.allocate,n=129,isSpill=true)
      t.control(0,ShaderPayloadControl.allocate,n=128,spill=true,isSpill=true)
      t.control(0,ShaderPayloadControl.allocate,n=0,fault=true)
      t.offerControl(0,ShaderPayloadControl.allocate,l=Lease(0,0,33,0,0))
      d.clock.step(10);d.io.control(0).ready.expect(false.B)
      t.control(1,ShaderPayloadControl.cancel,leases.head)
      t.control(1,ShaderPayloadControl.release,leases.head)
      t.until(d.io.controlResponse(0).valid.peek().litToBoolean)
      d.io.control(0).valid.poke(false.B)
      val b=d.io.controlResponse(0).bits
      b.resident.expect(true.B);b.spill.expect(false.B);b.fault.expect(false.B)
      val next=Lease(b.handle.peek().litValue.toInt,b.generation.peek().litValue,33,0,128)
      d.io.controlResponse(0).ready.poke(true.B);d.clock.step();d.io.controlResponse(0).ready.poke(false.B)
      t.access(0,next)
      t.control(0,ShaderPayloadControl.release,next)
      leases.tail.foreach(l=>t.control(1,ShaderPayloadControl.release,l))
      d.io.drained.expect(true.B)
    }
  }

  it should s"drain initialization before immediate cancellation release and cross-owner reuse macros=$macros" in {
    val p=cfg.copy(maxPayloadBytes=512)
    for(abort <- Seq(false,true);delay <- 0 until 3)simulate(model(macros,p)) { d=>
      val t=new Driver(d,p);val old=t.allocate(0,n=512)
      d.io.initializing.expect(true.B)
      d.clock.step(delay)
      if(abort)t.control(0,ShaderPayloadControl.cancel,old)
      t.control(0,ShaderPayloadControl.release,old)
      val next=t.allocate(1,n=512)
      assert(next.handle==old.handle && next.generation!=old.generation)
      t.access(0,old,write=true,data=(BigInt(1)<<512)-1,fault=true)
      t.access(1,next,expected=0);t.access(2,next,offset=448,expected=0)
      t.control(1,ShaderPayloadControl.release,next);d.io.drained.expect(true.B)
    }
  }

  it should s"reserve queued line replies and retain same-line order before lease release macros=$macros" in {
    simulate(model(macros)){d=>val t=new Driver(d);val l=t.allocate(0)
      t.until(!d.io.initializing.peek().litToBoolean)
      val pattern=BigInt("123456789abcdef",16)
      t.offer(0,l,write=true,data=pattern,tag=1);d.io.request(0).ready.expect(true.B);d.clock.step()
      t.offer(0,l,tag=2);d.io.request(0).ready.expect(true.B);d.clock.step()
      d.io.request(0).valid.poke(false.B);d.io.response(0).valid.expect(false.B)
      t.offer(0,l,offset=64,tag=3)
      for(_<-0 until 8){d.io.request(0).ready.expect(false.B);d.clock.step()}
      t.reply(0,l,tag=1);d.io.request(0).ready.expect(true.B);d.clock.step();d.io.request(0).valid.poke(false.B)
      t.offerControl(0,ShaderPayloadControl.release,l);d.io.control(0).ready.expect(false.B)
      t.reply(0,l,data=pattern,tag=2);d.io.control(0).ready.expect(false.B)
      t.reply(0,l,tag=3);t.until(d.io.control(0).ready.peek().litToBoolean)
      d.clock.step();d.io.control(0).valid.poke(false.B)
      t.until(d.io.controlResponse(0).valid.peek().litToBoolean)
      d.io.controlResponse(0).bits.fault.expect(false.B);d.io.controlResponse(0).ready.poke(true.B);d.clock.step()
      d.io.controlResponse(0).ready.poke(false.B);d.io.drained.expect(true.B)
    }
  }
  it should s"sustain a masked line update every two clocks with exact queued byte order macros=$macros" in {
    simulate(model(macros)){d=>val t=new Driver(d);val l=t.allocate(0)
      t.until(!d.io.initializing.peek().litToBoolean)
      val sent=Array.fill(cfg.ports)(0);val expected=Array.fill(128)(0)
      val pending=Array.fill(cfg.ports)(mutable.Queue.empty[Int])
      val commits=mutable.ArrayBuffer.empty[Int]
      var replies=0;var cycle=0
      while(replies<24 && cycle<1000){
        for(port<-0 until cfg.ports){
          if(sent(port)<8){
            val value=port*16+sent(port)+1
            val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(value)<<(8*b)))
            t.offer(port,l,offset=64*(sent(port)%2),write=true,data=data,
              mask=BigInt(65535)<<(8*port),tag=sent(port))
          }else d.io.request(port).valid.poke(false.B)
          d.io.response(port).ready.poke(true.B)
        }
        if(d.io.writeCommitted.peek().litToBoolean)commits+=cycle
        for(port<-0 until cfg.ports){
          if(d.io.request(port).valid.peek().litToBoolean && d.io.request(port).ready.peek().litToBoolean){
            val offset=64*(sent(port)%2);val value=port*16+sent(port)+1
            for(b<-8*port until 8*port+16)expected(offset+b)=value
            pending(port).enqueue(sent(port));sent(port)+=1
          }
          if(d.io.response(port).valid.peek().litToBoolean){
            val r=d.io.response(port).bits
            assert(pending(port).nonEmpty);r.tag.expect(pending(port).dequeue().U)
            r.fault.expect(false.B);r.data.expect(0.U);replies+=1
          }
        }
        d.clock.step();cycle+=1
      }
      assert(replies==24 && commits.size==24 && pending.forall(_.isEmpty))
      val periods=commits.sliding(2).map(p=>p(1)-p(0)).toVector
      assert(periods.forall(_==2),s"queued SRAM service inserted idle clocks: $periods")
      d.io.request.foreach(_.valid.poke(false.B));d.io.response.foreach(_.ready.poke(false.B))
      for(line<-0 until 2){
        val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(expected(64*line+b))<<(8*b)))
        t.access(0,l,offset=64*line,expected=data)
      }
      t.control(0,ShaderPayloadControl.release,l);d.io.drained.expect(true.B)
      info(s"PAYLOAD_SERVICE macros=$macros maskedWrites=24 initiationInterval=${periods.max} exactBytes=128 cycles=$cycle")
    }
  }
  it should s"isolate concurrent clients under independent response backpressure macros=$macros" in {
    simulate(model(macros)) { d=>
      val t=new Driver(d);val leases=Vector(t.allocate(0),t.allocate(1,context=23))
      leases.foreach(l=>t.access(0,l))
      case class Request(lease: Lease,offset: Int,write: Boolean,data: BigInt,mask: BigInt,tag: Int)
      val random=new scala.util.Random(0x7135)
      val streams=Vector.tabulate(cfg.ports)(port=>Vector.tabulate(60)(i=>Request(
        leases((i+port)%2),64*(i%2),i%3!=0,BigInt(512,random),BigInt(64,random),i)))
      val index=Array.fill(cfg.ports)(0)
      val expected=Array.fill(cfg.ports)(mutable.Queue.empty[(Request,BigInt)])
      val memory=Array.fill(2,128)(0)
      val held=Array.fill[Option[BigInt]](cfg.ports)(None)
      var cycle=0
      while((index.exists(_<60) || expected.exists(_.nonEmpty)) && cycle<20000) {
        for(port <- 0 until cfg.ports) {
          if(index(port)<60) {
            val r=streams(port)(index(port));t.offer(port,r.lease,r.offset,r.write,r.data,r.mask,r.tag)
          }else d.io.request(port).valid.poke(false.B)
          d.io.response(port).ready.poke(((cycle+port*3)%11<7).B)
          val out=d.io.response(port)
          held(port).foreach(v=>{out.valid.expect(true.B);assert(out.bits.peek().litValue==v)})
          if(out.valid.peek().litToBoolean) {
            assert(expected(port).nonEmpty)
            val (r,value)=expected(port).front
            out.bits.fault.expect(false.B);out.bits.tag.expect(r.tag.U);out.bits.data.expect(value.U)
            if(out.ready.peek().litToBoolean){expected(port).dequeue();held(port)=None}
            else held(port)=Some(out.bits.peek().litValue)
          }
        }
        for(port <- 0 until cfg.ports if d.io.request(port).valid.peek().litToBoolean && d.io.request(port).ready.peek().litToBoolean) {
          val r=streams(port)(index(port));val which=leases.indexOf(r.lease)
          val value=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(if(r.mask.testBit(b))BigInt(memory(which)(r.offset+b))<<(8*b)else BigInt(0)))
          if(r.write)for(b <- 0 until 64 if r.mask.testBit(b))memory(which)(r.offset+b)=((r.data>>(8*b))&255).toInt
          expected(port).enqueue((r,if(r.write)BigInt(0)else value));index(port)+=1
        }
        d.clock.step();cycle+=1
      }
      assert(index.forall(_==60) && expected.forall(_.isEmpty))
      for(port <- 0 until cfg.ports)d.io.request(port).valid.poke(false.B)
      leases.foreach(l=>t.control(l.owner,ShaderPayloadControl.release,l));d.io.drained.expect(true.B)
      info(s"PAYLOAD_CONCURRENT macros=$macros operations=180 cycles=$cycle exactBytes=256")
    }
  }

  it should s"separate all fourteen default reservations across SRAM segments macros=$macros" in {
    val p=ShaderPayloadConfig(owners=2,ports=2)
    simulate(model(macros,p)) { d=>
      val t=new Driver(d,p)
      val leases=Vector.tabulate(p.slots)(i=>t.allocate(i%2,p.maxPayloadBytes,context=100+i))
      d.io.reservedBytes.expect(p.reservedBytes.U);d.io.liveBytes.expect(p.reservedBytes.U)
      t.control(0,ShaderPayloadControl.allocate,n=p.maxPayloadBytes,spill=true,isSpill=true)
      t.access(0,leases.head.copy(handle=15),fault=true)
      for((l,i) <- leases.zipWithIndex;offset <- Seq(0,p.slotBytes-64)) {
        val data=Vector.tabulate(16)(word=>BigInt(0x10000000L+i*0x10000L+offset+word))
          .zipWithIndex.foldLeft(BigInt(0)){case(v,(word,n))=>v|(word<<(32*n))}
        t.access(i%2,l,offset,write=true,data=data)
      }
      for((l,i) <- leases.zipWithIndex;offset <- Seq(0,p.slotBytes-64)) {
        val data=Vector.tabulate(16)(word=>BigInt(0x10000000L+i*0x10000L+offset+word))
          .zipWithIndex.foldLeft(BigInt(0)){case(v,(word,n))=>v|(word<<(32*n))}
        t.access((i+1)%2,l,offset,expected=data)
      }
      leases.foreach(l=>t.control(l.owner,ShaderPayloadControl.release,l))
      d.io.drained.expect(true.B)
      info(s"PAYLOAD_SEGMENTS macros=$macros leases=${p.slots} reservedBytes=${p.reservedBytes} physicalBytes=${p.physicalBytes} macrosCount=${16*p.segments}")
    }
  }
  }
}
