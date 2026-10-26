package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class MacroShaderPayloadStore(p: ShaderPayloadConfig,taskPins: Int=0) extends BankedShaderPayloadStore(p,true,taskPins) {
  val macroModelPresent=IO(Output(Bool()))
  private val resources=Module(new TileSramSimulationResources)
  macroModelPresent:=resources.io.present
}

private class PayloadInitializationProbe(p: ShaderPayloadConfig,macros: Boolean)
    extends BankedShaderPayloadStore(p,macros) {
  val lineWrites=IO(Output(UInt(32.W)))
  private val count=RegInit(0.U(32.W))
  // All sixteen word banks share one line address. Observe physical writes in
  // one bank; no client operations are offered during initialization measures.
  when(VecInit(memories.head.map(_.io.writeEnable)).asUInt.orR){count:=count+1.U}
  lineWrites:=count
  if(macros){
    val present=IO(Output(Bool()))
    val resources=Module(new TileSramSimulationResources)
    present:=resources.io.present
  }
}

class BankedShaderPayloadStoreSpec extends AnyFlatSpec {
  private val cfg=ShaderPayloadConfig(capacityBytes=512,maxPayloadBytes=128,owners=2,ports=3)
  private val nativeTaskPins=opengpu.config.GpuConfig.MobileComputeUnits+1+TypedVertexBinImagePipeline.payloadPins(4)
  private val full=(BigInt(1)<<64)-1
  private case class Lease(handle: Int,generation: BigInt,context: BigInt,owner: Int,bytes: Int)
  private def model(macros: Boolean,p: ShaderPayloadConfig=cfg,taskPins: Int=0): BankedShaderPayloadStore=
    if(macros)new MacroShaderPayloadStore(p,taskPins)else new BankedShaderPayloadStore(p,taskPins=taskPins)
  private class Driver(val d: BankedShaderPayloadStore,val p: ShaderPayloadConfig=cfg) {
    for(c <- d.io.control) {c.valid.poke(false.B);c.bits.poke(0.U.asTypeOf(c.bits))}
    for(c <- d.io.controlResponse)c.ready.poke(false.B)
    for(r <- d.io.request) {r.valid.poke(false.B);r.bits.poke(0.U.asTypeOf(r.bits))}
    for(r <- d.io.response)r.ready.poke(false.B)
    d.io.pins.foreach(_.foreach{pin=>pin.valid.poke(false.B);pin.bits.poke(0.U.asTypeOf(pin.bits))})
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
        fault: Boolean=false,isSpill: Boolean=false,afterAccept: ()=>Unit=()=>()): Lease={
      offerControl(who,op,l,n,spill)
      until(d.io.control(who).ready.peek().litToBoolean)
      d.clock.step();afterAccept();d.io.control(who).valid.poke(false.B)
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
  it should s"initialize only visible lease lines and reinitialize newly exposed data on reuse macros=$macros" in {
    val p=ShaderPayloadConfig(capacityBytes=2048,maxPayloadBytes=2048,owners=2,ports=3)
    simulate(new PayloadInitializationProbe(p,macros)){d=>
      val t=new Driver(d,p)
      val poison=(BigInt(1)<<512)-1
      val seed=t.allocate(0,n=2048)
      for(offset<-0 until 2048 by 64)t.access(0,seed,offset,write=true,data=poison)
      t.control(0,ShaderPayloadControl.release,seed)
      for(length<-Seq(1,63,64,65,127,128,129,2048)){
        val before=d.lineWrites.peek().litValue
        val l=t.allocate(1,n=length)
        t.until(!d.io.initializing.peek().litToBoolean)
        val lines=(length+63)/64
        assert(d.lineWrites.peek().litValue-before==lines,
          s"initialization cleared ${d.lineWrites.peek().litValue-before} lines for $length live bytes; expected $lines")
        for(offset<-0 until length by 64){
          val mask=(BigInt(1)<<math.min(64,length-offset))-1
          t.access(0,l,offset,mask=mask,expected=0)
        }
        if(length<2048){
          val outside=64*((length+63)/64)
          t.access(0,l,outside,mask=0,expected=0)
          t.access(0,l,outside,mask=1,fault=true)
        }
        if(length%64!=0)t.access(0,l,64*(length/64),write=true,data=poison,
          mask=BigInt(1)<<(length%64),fault=true)
        t.control(1,ShaderPayloadControl.release,l);d.io.drained.expect(true.B)
      }
      val canceled=t.allocate(0,n=2048)
      d.io.initializing.expect(true.B)
      t.control(0,ShaderPayloadControl.cancel,canceled)
      val stopped=d.lineWrites.peek().litValue
      d.clock.step(10);d.lineWrites.expect(stopped.U)
      t.control(0,ShaderPayloadControl.release,canceled);d.io.drained.expect(true.B)
      val before=d.lineWrites.peek().litValue
      val healthy=t.allocate(1,n=65)
      t.until(!d.io.initializing.peek().litToBoolean)
      assert(d.lineWrites.peek().litValue-before==2)
      t.access(0,healthy,expected=0);t.access(1,healthy,64,mask=1,expected=0)
      t.control(1,ShaderPayloadControl.release,healthy);d.io.drained.expect(true.B)
      info(s"PAYLOAD_VISIBLE_INIT macros=$macros slotBytes=2048 leaseBytes1x63x64x65x127x128x129x2048=true exactPhysicalLineWrites=true poisonedReuse=true maskedBounds=true zeroMaskReply=true cancelAndHealthyReuse=true")
    }
  }

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

  it should s"preserve allocation tails around byte and line boundaries macros=$macros" in {
    simulate(model(macros)){d=>val t=new Driver(d)
      for(length<-Seq(1,2,31,32,63,64,65,79,127,128)){
        val l=t.allocate(0,n=length);val line=64*((length-1)/64)
        val tail=length-line;val mask=(BigInt(1)<<tail)-1
        val expected=(BigInt(1)<<(8*tail))-1
        t.access(0,l,offset=line,write=true,data=(BigInt(1)<<512)-1,mask=mask)
        if(tail<64)t.access(1,l,offset=line,write=true,mask=BigInt(1)<<tail,fault=true)
        else t.access(1,l,offset=line+64,write=true,fault=true)
        t.access(2,l,offset=line,mask=mask,expected=expected)
        t.access(0,l,offset=64,mask=0)
        t.control(0,ShaderPayloadControl.release,l);d.io.drained.expect(true.B)
      }
    }
  }

  it should s"keep accepted data healthy when a foreign-context cancel is offered macros=$macros" in {
    simulate(model(macros)){d=>val t=new Driver(d);val l=t.allocate(0)
      t.until(!d.io.initializing.peek().litToBoolean)
      t.submit(0,l,write=true,data=0x76543210,mask=15,tag=9)
      t.offerControl(0,ShaderPayloadControl.cancel,l.copy(context=77))
      t.until(d.io.control(0).ready.peek().litToBoolean);d.clock.step()
      d.io.control(0).valid.poke(false.B)
      t.reply(0,l,tag=9)
      t.until(d.io.controlResponse(0).valid.peek().litToBoolean)
      d.io.controlResponse(0).bits.fault.expect(true.B)
      d.io.controlResponse(0).ready.poke(true.B);d.clock.step()
      d.io.controlResponse(0).ready.poke(false.B)
      t.access(1,l,mask=15,expected=0x76543210)
      t.control(0,ShaderPayloadControl.release,l);d.io.drained.expect(true.B)
    }
  }

  it should s"allow pinned shader traffic to drain while release waits macros=$macros" in {
    simulate(model(macros,taskPins=1)){d=>val t=new Driver(d);val l=t.allocate(0)
      t.until(!d.io.initializing.peek().litToBoolean)
      val pin=d.io.pins.get(0)
      pin.bits.handle.poke(l.handle.U);pin.bits.generation.poke(l.generation.U)
      pin.bits.context.poke(l.context.U);pin.bits.owner.poke(l.owner.U)
      t.submit(0,l,tag=9);t.until(d.io.response(0).valid.peek().litToBoolean)
      t.offerControl(0,ShaderPayloadControl.release,l)
      d.clock.step(4);d.io.control(0).ready.expect(false.B)
      // A new task pin must veto release while the held last reply retires.
      pin.valid.poke(true.B);t.reply(0,l,tag=9)
      d.io.control(0).ready.expect(false.B)
      t.access(0,l,write=true,data=0x12345678,mask=15)
      t.access(1,l,mask=15,expected=0x12345678)
      d.io.control(0).ready.expect(false.B)
      pin.bits.generation.poke((l.generation+1).U)
      t.until(d.io.control(0).ready.peek().litToBoolean);d.clock.step()
      d.io.control(0).valid.poke(false.B)
      t.until(d.io.controlResponse(0).valid.peek().litToBoolean)
      d.io.controlResponse(0).bits.fault.expect(false.B)
      d.io.controlResponse(0).ready.poke(true.B);d.clock.step()
      d.io.controlResponse(0).ready.poke(false.B);pin.valid.poke(false.B)
      d.io.drained.expect(true.B)
    }
  }

  it should s"veto a raced release across $nativeTaskPins task pins while other owners and data traffic progress macros=$macros" in {
    val p=ShaderPayloadConfig(capacityBytes=16384,maxPayloadBytes=2304,owners=5,ports=12)
    assert(p.slots==7)
    simulate(model(macros,p,taskPins=nativeTaskPins)){d=>val t=new Driver(d,p)
      val lease=t.allocate(0,n=129,context=301);val peer=t.allocate(4,n=65,context=404)
      t.until(!d.io.initializing.peek().litToBoolean)
      def pin(index: Int,valid: Boolean): Unit={
        val b=d.io.pins.get(index);b.bits.handle.poke(lease.handle.U);b.bits.generation.poke(lease.generation.U)
        b.bits.context.poke(lease.context.U);b.bits.owner.poke(lease.owner.U);b.valid.poke(valid.B)
      }
      t.offerControl(0,ShaderPayloadControl.release,lease)
      d.io.control(0).ready.expect(false.B);d.clock.step()
      // The release fence is now established, but a pin arriving before the
      // handshake must still veto release without a reply or state mutation.
      pin(nativeTaskPins-1,true);d.io.control(0).ready.expect(false.B)
      d.clock.step(5);d.io.controlResponse(0).valid.expect(false.B)
      d.io.reservedBytes.expect((2*p.slotBytes).U)
      t.access(11,lease,write=true,data=0x12345678,mask=15)
      t.access(10,lease,mask=15,expected=0x12345678)
      t.submit(8,peer,write=true,data=0x1234,mask=3,tag=18)
      t.control(4,ShaderPayloadControl.cancel,peer.copy(context=999),fault=true)
      t.reply(8,peer,tag=18);t.access(8,peer,mask=3,expected=0x1234)
      t.submit(7,peer,write=true,data=0x5678,mask=3,tag=19)
      t.control(4,ShaderPayloadControl.cancel,peer);t.reply(7,peer,fault=true,tag=19)
      t.control(4,ShaderPayloadControl.release,peer)
      t.submit(9,lease,write=true,data=0x55551234,mask=15,tag=17)
      t.control(3,ShaderPayloadControl.cancel,lease,fault=true)
      t.reply(9,lease,tag=17);t.access(10,lease,mask=15,expected=0x55551234)
      d.io.control(0).ready.expect(false.B);d.io.controlResponse(0).valid.expect(false.B)
      pin(0,true);pin(nativeTaskPins-1,false);d.clock.step(3);d.io.control(0).ready.expect(false.B)
      pin(0,false);t.until(d.io.control(0).ready.peek().litToBoolean);d.clock.step()
      d.io.control(0).valid.poke(false.B);t.until(d.io.controlResponse(0).valid.peek().litToBoolean)
      d.io.controlResponse(0).bits.fault.expect(false.B);d.io.controlResponse(0).bits.bytes.expect(129.U)
      d.io.controlResponse(0).ready.poke(true.B);d.clock.step();d.io.controlResponse(0).ready.poke(false.B)
      d.io.drained.expect(true.B)
      val next=t.allocate(0,n=129,context=301)
      assert(next.handle==lease.handle && next.generation==lease.generation+1)
      t.access(10,lease,fault=true);t.access(11,next,expected=0)
      t.control(0,ShaderPayloadControl.release,next);d.io.drained.expect(true.B)
    }
  }

  it should s"count exact live bytes across all seven slots and canceled ownership until release macros=$macros" in {
    val p=ShaderPayloadConfig(capacityBytes=16384,maxPayloadBytes=2304,owners=5,ports=12)
    simulate(model(macros,p,taskPins=nativeTaskPins)){d=>val t=new Driver(d,p)
      val lengths=Vector(2303,2049,1025,511,255,127,65)
      var live=0
      val leases=lengths.zipWithIndex.map{case(n,i)=>
        val who=i%p.owners
        t.control(who,ShaderPayloadControl.allocate,l=Lease(0,0,500+i,who,0),n=n,
          afterAccept=()=>{live+=n;d.io.liveBytes.expect(live.U)})
      }
      d.io.reservedBytes.expect((7*p.slotBytes).U)
      t.control(leases(3).owner,ShaderPayloadControl.cancel,leases(3),afterAccept=()=>d.io.liveBytes.expect(live.U))
      for(i<-Seq(6,0,4,2,5,1,3)){
        t.control(leases(i).owner,ShaderPayloadControl.release,leases(i),
          afterAccept=()=>{live-=lengths(i);d.io.liveBytes.expect(live.U)})
      }
      d.io.liveBytes.expect(0.U);d.io.drained.expect(true.B)
      val healthy=t.control(4,ShaderPayloadControl.allocate,l=Lease(0,0,999,4,0),n=p.maxPayloadBytes,
        afterAccept=()=>d.io.liveBytes.expect(p.maxPayloadBytes.U))
      d.io.liveBytes.expect(p.maxPayloadBytes.U);t.access(11,healthy,expected=0)
      t.control(4,ShaderPayloadControl.release,healthy,afterAccept=()=>d.io.liveBytes.expect(0.U))
      d.io.liveBytes.expect(0.U);d.io.drained.expect(true.B)
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
  for(partial<-Seq(false,true))
  it should s"sustain ${if(partial)"byte-masked updates every two clocks" else "whole-word masked updates every clock"} with exact queued byte order macros=$macros" in {
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
              mask=BigInt(if(partial)0x5555 else 65535)<<(8*port),tag=sent(port))
          }else d.io.request(port).valid.poke(false.B)
          d.io.response(port).ready.poke(true.B)
        }
        if(d.io.writeCommitted.peek().litToBoolean)commits+=cycle
        for(port<-0 until cfg.ports){
          if(d.io.request(port).valid.peek().litToBoolean && d.io.request(port).ready.peek().litToBoolean){
            val offset=64*(sent(port)%2);val value=port*16+sent(port)+1
            val mask=BigInt(if(partial)0x5555 else 65535)<<(8*port)
            for(b<-0 until 64 if mask.testBit(b))expected(offset+b)=value
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
      d.io.request.foreach(_.valid.poke(false.B));d.io.response.foreach(_.ready.poke(false.B))
      for(line<-0 until 2){
        val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(expected(64*line+b))<<(8*b)))
        t.access(0,l,offset=64*line,expected=data)
      }
      t.control(0,ShaderPayloadControl.release,l);d.io.drained.expect(true.B)
      info(s"PAYLOAD_SERVICE macros=$macros byteRmw=$partial maskedWrites=24 initiationInterval=${periods.max} exactBytes=128 cycles=$cycle")
      val budget=if(partial)2 else 1
      assert(periods.forall(_==budget),s"queued SRAM service exceeds $budget clocks: $periods")
    }
  }
  it should s"sustain line reads every clock across queued clients with exact byte masks macros=$macros" in {
    simulate(model(macros)){d=>val t=new Driver(d);val l=t.allocate(0)
      val lines=Vector.tabulate(2)(line=>(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(BigInt(1+line*64+b)<<(8*b))))
      for(line<-0 until 2)t.access(0,l,offset=64*line,write=true,data=lines(line))
      val sent=Array.fill(cfg.ports)(0);val pending=Array.fill(cfg.ports)(mutable.Queue.empty[(Int,BigInt)])
      val issued=mutable.ArrayBuffer.empty[Int];var replies=0;var cycle=0
      def mask(port: Int,index: Int): BigInt=if(index%3==0)full else (BigInt("5555555555555555",16)<<(port%2))&full
      while(replies<48 && cycle<1000){
        for(port<-0 until cfg.ports){
          if(sent(port)<16)t.offer(port,l,offset=64*(sent(port)%2),mask=mask(port,sent(port)),tag=sent(port))
          else d.io.request(port).valid.poke(false.B)
          d.io.response(port).ready.poke(true.B)
        }
        if(d.io.readIssued.peek().litToBoolean)issued+=cycle
        for(port<-0 until cfg.ports){
          if(d.io.request(port).valid.peek().litToBoolean && d.io.request(port).ready.peek().litToBoolean){
            val index=sent(port);val selected=mask(port,index)
            val expected=(0 until 64).foldLeft(BigInt(0))((v,b)=>if(selected.testBit(b))v|(lines(index%2)&(BigInt(255)<<(8*b)))else v)
            pending(port).enqueue((index,expected));sent(port)+=1
          }
          if(d.io.response(port).valid.peek().litToBoolean){
            assert(pending(port).nonEmpty);val(tag,data)=pending(port).dequeue();val r=d.io.response(port).bits
            r.tag.expect(tag.U);r.data.expect(data.U);r.fault.expect(false.B);replies+=1
          }
        }
        d.clock.step();cycle+=1
      }
      assert(replies==48 && issued.size==48 && pending.forall(_.isEmpty))
      d.io.request.foreach(_.valid.poke(false.B));d.io.response.foreach(_.ready.poke(false.B))
      t.control(0,ShaderPayloadControl.release,l);d.io.drained.expect(true.B)
      val gaps=issued.sliding(2).map(p=>p(1)-p(0)).toVector
      info(s"PAYLOAD_READ_RATE macros=$macros reads=48 intervals=${gaps.size} maxGap=${gaps.max} exactMaskedBytes=true heldLeaseUntilDrain=true")
      assert(gaps.forall(_==1),s"queued line reads exceed one clock: $gaps")
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
