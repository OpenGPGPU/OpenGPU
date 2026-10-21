package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class NativePrimitiveQueryEngineSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private val root=BigInt(0x80000030L)
  private val max64=(BigInt(1)<<64)-1
  private def module=new NativePrimitiveQueryEngine(GpuConfig(lanes=4,warps=2),producers=2)
  private class Driver(val d: NativePrimitiveQueryEngine){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.satp.poke(root.U);d.io.renderIdle.poke(true.B)
    val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,
      d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
    var address=BigInt(0);var context=0;var cookie=0;var tag=0
    def tick(): Unit={memory.drive();memory.capture();d.clock.step()}
    def until(c: =>Boolean): Unit={var n=0;while(!c && n<10000){tick();n+=1};assert(c,s"primitive query stalled cycle=${memory.cycle}")}
    def submit(action: Int=0,at: BigInt=0x50000,ctx: Int=1,reuse: Int=11,
        id: Int=77,limit: Option[BigInt]=None): Unit={
      address=at;context=ctx;cookie=reuse;tag=id
      val q=d.io.command.bits;q.action.poke(action.U);q.address.poke(at.U)
      q.limit.poke(limit.getOrElse(at+64).U);q.context.poke(ctx.U);q.cookie.poke(reuse.U);q.tag.poke(id.U)
      d.io.command.valid.poke(true.B);until(d.io.command.ready.peek().litToBoolean)
      tick();d.io.command.valid.poke(false.B)
    }
    def reply(status: Int=0,available: Boolean=false,count: BigInt=0): Unit={
      until(d.io.reply.valid.peek().litToBoolean);val r=d.io.reply.bits
      r.status.expect(status.U);r.available.expect(available.B);r.count.expect(count.U)
      r.value.expect(count.min((BigInt(1)<<32)-1).U)
      r.address.expect(address.U);r.context.expect(context.U);r.cookie.expect(cookie.U);r.tag.expect(tag.U)
      val held=r.peek().litValue
      for(_<-0 until 4){tick();assert(r.peek().litValue==held);d.io.busy.expect(true.B);d.io.command.ready.expect(false.B)}
      d.io.reply.ready.poke(true.B);tick();d.io.reply.ready.poke(false.B);until(!d.io.busy.peek().litToBoolean)
    }
    def event(port: Int=0,ctx: Int=1,vm: BigInt=root,count: BigInt=1): Unit={
      val e=d.io.written(port);e.valid.poke(true.B);e.bits.context.poke(ctx.U);e.bits.root.poke(vm.U);e.bits.count.poke(count.U)
    }
    def clear(): Unit=d.io.written.foreach(_.valid.poke(false.B))
    def written(ctx: Int=1,vm: BigInt=root,count: BigInt=1): Unit={event(ctx=ctx,vm=vm,count=count);tick();clear()}
    def job(ctx: Int=1,vm: BigInt=root,status: Int=3): Unit={
      d.io.jobFault.valid.poke(true.B);d.io.jobFault.bits.context.poke(ctx.U)
      d.io.jobFault.bits.root.poke(vm.U);d.io.jobFault.bits.status.poke(status.U);tick();d.io.jobFault.valid.poke(false.B)
    }
    def put(at: BigInt,index: Int,value: BigInt): Unit=memory.putWord(at+index*4,value)
    def word(at: BigInt,index: Int): BigInt=memory.word(at+index*4)
    def install(at: BigInt=0x70000,ctx: Int=1,reuse: Int=11,vm: BigInt=root,
        available: Boolean=true,count: BigInt=17,status: Int=0): Unit={
      for(i<-0 until 16)put(at,i,0)
      Seq[BigInt](BigInt("47504f01",16),64,ctx,reuse,(status<<2)|(if(available)1 else 0),
        count&0xffffffffL,count>>32,vm,0x8c88).zipWithIndex.foreach{case(v,i)=>put(at,i,v)}
    }
  }

  it should "count exact64-bit acknowledged batches and clamp32-bit results without counting other roots or post-end events" in {
    simulate(module){d=>val t=new Driver(d)
      d.io.renderIdle.poke(false.B);d.io.command.valid.poke(true.B)
      d.io.command.bits.action.poke(0.U);d.io.command.ready.expect(false.B);t.tick();d.io.command.valid.poke(false.B)
      d.io.renderIdle.poke(true.B);t.submit();t.reply();d.io.activeContexts.expect(1.U)
      t.event(count=0xffffffffL);t.event(1,count=2);t.tick();t.clear()
      t.written(vm=root+1,count=max64);t.written(ctx=2,count=max64)
      t.memory.holdRequests=true;t.event(count=7);t.submit(1);t.clear()
      t.until(d.io.memoryRequest.valid.peek().litToBoolean)
      val held=d.io.memoryRequest.bits.peek().litValue;d.io.satp.poke((root+1).U)
      t.event(count=max64)
      for(_<-0 until 7){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.memorySatp.expect(root.U)}
      t.clear();t.memory.holdRequests=false;val expected=BigInt(0x100000008L)
      t.reply(available=true,count=expected);d.io.activeContexts.expect(0.U)
      assert(t.word(0x50000,4)==1 && t.word(0x50000,5)==8 && t.word(0x50000,6)==1 && t.word(0x50000,7)==root)
      d.io.satp.poke(root.U);t.submit(3);t.reply(available=true,count=expected)
      t.submit(at=0x50040,reuse=12);t.reply()
      t.event(count=max64-2);t.event(1,count=9);t.tick();t.clear()
      t.submit(1,at=0x50040,reuse=12);t.reply(available=true,count=max64)
      t.submit(3,at=0x50040,reuse=12);t.reply(available=true,count=max64)
      assert(t.memory.seen.forall(r=>r.tag==0 && (r.address&63)==0))
      info("PRIMITIVE_QUERY countBits=64 simultaneousProducers=2 carry32=true saturate64=true clamp32=true exactGpuRecord=true endBoundarySnapshot=true heldOfferRoot=true postEndEventsIgnored=true")
    }
  }

  it should "retain eight active context/root counters and free slots for unlimited completed unread GPU records" in {
    simulate(module){d=>val t=new Driver(d)
      for(i<-0 until 8){val vm=root+i%2;val ctx=100+i/2;d.io.satp.poke(vm.U)
        t.submit(at=0x60000+i*64,ctx=ctx,reuse=1);t.reply();t.written(ctx,vm,i+1)}
      d.io.activeContexts.expect(8.U);d.io.satp.poke(root.U)
      val old=t.memory.seen.size
      t.submit(at=0x70000,ctx=999);t.reply(6)
      t.submit(at=0x70100,ctx=100,reuse=2);t.reply(5)
      t.submit(at=0x60000,ctx=999);t.reply(5)
      t.submit(2,at=0x60000,ctx=100,reuse=1);t.reply(5)
      t.submit(1,at=0x60000,ctx=100,reuse=999);t.reply(5)
      assert(t.memory.seen.size==old)
      t.memory.holdRequests=true;t.submit(1,at=0x60000,ctx=100,reuse=1)
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);t.written(ctx=100,vm=root+1,count=50)
      t.memory.holdRequests=false;t.reply(available=true,count=1)
      for(i<-1 until 8){val vm=root+i%2;d.io.satp.poke(vm.U)
        t.submit(1,at=0x60000+i*64,ctx=100+i/2,reuse=1);t.reply(available=true,count=if(i==1)52 else i+1)}
      d.io.activeContexts.expect(0.U);d.io.satp.poke(root.U)
      for(i<-0 until 20){t.submit(at=0x80000+i*64,reuse=200+i);t.reply();t.written(count=i)
        t.submit(1,at=0x80000+i*64,reuse=200+i);t.reply(available=true,count=i);d.io.activeContexts.expect(0.U)}
      for(i<-0 until 20){t.submit(3,at=0x80000+i*64,reuse=200+i);t.reply(available=true,count=i)}
      for(i<-0 until 8){d.io.satp.poke((root+i%2).U)
        t.submit(3,at=0x60000+i*64,ctx=100+i/2,reuse=1);t.reply(available=true,count=if(i==1)52 else i+1)}
      info("PRIMITIVE_QUERY activeSlots=8 ninthRejected=true rootIsolation=true aliasesRejected=true completedUnreadObjects=28 activeSlotsFreed=true peerCountsThroughHeldEnd=true")
    }
  }

  it should "validate fourGiB bounds and typed records before returning availability or data" in {
    simulate(module){d=>val t=new Driver(d);val before=t.memory.seen.size
      for((address,limit)<-Seq((BigInt(0xffffffe0L),BigInt(1)<<32),(BigInt(0xffffffc0L),(BigInt(1)<<32)+1))){
        t.submit(at=address,limit=Some(limit));t.reply(1)}
      assert(t.memory.seen.size==before)
      t.submit(at=0xffffffc0L,limit=Some(BigInt(1)<<32));t.reply()
      t.submit(1,at=0xffffffc0L,limit=Some(BigInt(1)<<32));t.reply(available=true)
      val mutations=Seq((0,BigInt(0),1),(1,BigInt(128),1),(4,BigInt(3),1),(4,BigInt(29),1),
        (8,BigInt(0),1),(9,BigInt(1),1),(2,BigInt(7),2),(3,BigInt(12),2),(7,root+1,2))
      for((index,value,status)<-mutations){t.install();t.put(0x70000,index,value)
        t.submit(3,at=0x70000);t.reply(status)}
      t.install(available=false,count=0);t.submit(2,at=0x70000);t.reply()
      t.submit(3,at=0x70000);t.reply()
      t.install(available=false,count=1);t.submit(3,at=0x70000);t.reply(1)
      t.install(status=3);t.submit(2,at=0x70000);t.reply(available=true)
      t.submit(3,at=0x70000);t.reply(3,available=true)
      d.io.activeContexts.expect(0.U)
    }
  }

  it should "attribute primary draw faults to one query while a peer retains its acknowledged count" in {
    simulate(module){d=>val t=new Driver(d)
      t.submit();t.reply();t.submit(at=0x50040,ctx=2,reuse=12);t.reply()
      t.written(count=7);t.written(ctx=2,count=11)
      t.job(status=4);t.job(status=1);t.job(ctx=2,vm=root+1,status=3)
      t.submit(1);t.reply(3,available=true)
      assert(t.word(0x50000,4)==13 && t.word(0x50000,5)==7)
      t.submit(2);t.reply(available=true);t.submit(3);t.reply(3,available=true)
      t.submit(1,at=0x50040,ctx=2,reuse=12);t.reply(available=true,count=11)
      t.submit(3,at=0x50040,ctx=2,reuse=12);t.reply(available=true,count=11)
    }
  }

  it should "drain immutable offers and primary memory faults through reset then reject stale active objects and allow healthy reuse" in {
    simulate(module){d=>val t=new Driver(d)
      for(fault<-Seq(false,true)){
        t.submit();t.reply();t.submit(at=0x50040,ctx=2,reuse=12);t.reply()
        t.written(count=5);t.memory.holdRequests=true;t.submit(1)
        t.until(d.io.memoryRequest.valid.peek().litToBoolean)
        val held=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
        d.io.satp.poke((root+1).U);d.io.renderIdle.poke(false.B)
        for(_<-0 until 7){t.tick();assert(d.io.memoryRequest.bits.peek().litValue==held);d.io.memorySatp.expect(root.U)}
        if(fault)t.memory.faultAt=Set(BigInt(0x50000))
        d.io.renderIdle.poke(true.B);t.memory.holdRequests=false;t.reply(if(fault)3 else 4)
        d.io.cancel.poke(false.B);d.io.satp.poke(root.U);t.memory.faultAt=Set.empty
        d.io.activeContexts.expect(0.U);t.submit(1,at=0x50040,ctx=2,reuse=12);t.reply(5)
        t.submit(at=0x50100,reuse=99);t.reply();t.written(count=23)
        t.submit(1,at=0x50100,reuse=99);t.reply(available=true,count=23)
      }
    }
  }
}
