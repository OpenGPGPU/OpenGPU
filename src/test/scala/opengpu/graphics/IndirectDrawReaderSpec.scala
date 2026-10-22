package opengpu.graphics

import chisel3._
import opengpu.config.CachePolicy
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class IndirectDrawReaderSpec extends AnyFlatSpec {
  import WindowRecordTest._
  private class Driver(val d: IndirectDrawReader){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    val memory=mutable.Map.empty[BigInt,BigInt];val reads=mutable.ArrayBuffer.empty[BigInt]
    case class Reply(address: BigInt,due: Int,fault: Boolean)
    var pending=Option.empty[Reply];var cycle=0;var blockRequests=false;var blockResponses=false
    var faultAt=Set.empty[BigInt];var start=BigInt(0);var length=0
    private var held=Option.empty[BigInt]
    def put(address: BigInt,value: BigInt): Unit=for(b<-0 until 4)memory(address+b)=(value>>(8*b))&255
    def tick(): Unit={
      d.io.memoryRequest.ready.poke((!blockRequests && cycle%3!=0).B)
      d.io.memoryResponse.valid.poke((pending.exists(_.due<=cycle) && !blockResponses).B)
      pending.foreach{p=>
        d.io.memoryResponse.bits.transactionId.poke(0.U);d.io.memoryResponse.bits.fault.poke(p.fault.B)
        val data=(0 until 64).foldLeft(BigInt(0))((v,b)=>v|(memory.getOrElse(p.address+b,BigInt(0xa7))<<(8*b)))
        d.io.memoryResponse.bits.readData.poke(data.U)
      }
      if(d.io.memoryResponse.valid.peek().litToBoolean && d.io.memoryResponse.ready.peek().litToBoolean)pending=None
      if(d.io.memoryRequest.valid.peek().litToBoolean){
        val bits=d.io.memoryRequest.bits.peek().litValue;held.foreach(v=>assert(v==bits));held=Some(bits)
        if(d.io.memoryRequest.ready.peek().litToBoolean){
          assert(pending.isEmpty);val q=d.io.memoryRequest.bits;val address=q.address.peek().litValue
          assert(address==(start& ~BigInt(63)) || address==(start& ~BigInt(63))+64)
          q.isWrite.expect(false.B);q.sizeLog2.expect(6.U);q.cachePolicy.expect(CachePolicy.cached)
          val mask=(0 until 64).filter(b=>address+b>=start && address+b<start+length).foldLeft(BigInt(0))((m,b)=>m.setBit(b))
          q.byteMask.expect(mask.U);reads+=address;pending=Some(Reply(address,cycle+5,faultAt(address)));held=None
        }
      }
      d.clock.step();cycle+=1
    }
    def until(condition: =>Boolean): Unit={var n=0;while(!condition && n<128){tick();n+=1};assert(condition,s"indirect reader stalled at $cycle")}
    def submit(mode: Int,address: BigInt,values: Seq[BigInt],limit: Option[BigInt]=None): Unit={
      assert(pending.isEmpty);reads.clear();start=address;length=if(mode==1)16 else 20
      values.zipWithIndex.foreach{case(v,i)=>put(address+4*i,v)}
      val c=d.io.in.bits;zero(c);c.mode.poke(mode.U);c.view.base.poke(address.U)
      c.view.limit.poke(limit.getOrElse(address+length).U);c.view.allocationBase.poke((address& ~BigInt(63)).U)
      c.view.allocationLimit.poke(((address+length+63)& ~BigInt(63)).U)
      d.io.in.valid.poke(true.B);until(d.io.in.ready.peek().litToBoolean);tick();d.io.in.valid.poke(false.B);zero(c)
    }
    def complete(status: Int,lines: Int,address: BigInt=0,cancelHeld: Boolean=false): Unit={
      until(d.io.out.valid.peek().litToBoolean);d.io.out.bits.status.expect(status.U);d.io.out.bits.lines.expect(lines.U)
      d.io.out.bits.faultAddress.expect(address.U)
      val held=d.io.out.bits.peek().litValue
      if(cancelHeld)d.io.cancel.poke(true.B)
      for(_<-0 until 7){tick();d.io.out.valid.expect(true.B);d.io.in.ready.expect(false.B);assert(d.io.out.bits.peek().litValue==held)}
      d.io.out.ready.poke(true.B);tick();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B)
      d.io.busy.expect(false.B);assert(pending.isEmpty)
    }
  }
  it should "capture packed arrays and elements at every aligned line offset and the exclusive four GiB boundary" in {
    simulate(new IndirectDrawReader){d=>val t=new Driver(d)
      for(mode<-Seq(1,2);offset<-0 until 64 by 4){
        val address=BigInt(0x10000+offset);val values=if(mode==1)Seq[BigInt](0xffffffffL,0x80000001L,0xfffffffeL,0,0xdeadbeefL)
          else Seq[BigInt](0xffffffffL,0x80000001L,0xfffffffeL,0xffffffefL,0)
        t.submit(mode,address,values);t.until(d.io.out.valid.peek().litToBoolean)
        d.io.out.bits.count.expect("hffffffff".U);d.io.out.bits.instances.expect("h80000001".U)
        d.io.out.bits.first.expect("hfffffffe".U);d.io.out.bits.baseVertex.expect((if(mode==1)0 else -17).S)
        t.complete(0,if(offset+(if(mode==1)16 else 20)>64)2 else 1,cancelHeld=offset==60)
      }
      for(mode<-Seq(1,2)){
        val address=(BigInt(1)<<32)-(if(mode==1)16 else 20)
        t.submit(mode,address,if(mode==1)Seq(1,2,3,0).map(BigInt(_))else Seq(1,2,3,-1,0).map(v=>BigInt(v)&0xffffffffL))
        t.complete(0,1)
      }
    }
  }
  it should "reject malformed modes alignment short logical spans and nonzero reserved words without reading unrelated data" in {
    simulate(new IndirectDrawReader){d=>val t=new Driver(d)
      for(mode<-Seq(0,3)){t.submit(mode,0x10000,Seq.fill(5)(BigInt(0)));t.complete(1,0,0x10000);assert(t.reads.isEmpty)}
      t.submit(1,0x10002,Seq.fill(5)(BigInt(0)));t.complete(1,0,0x10002);assert(t.reads.isEmpty)
      for(mode<-Seq(1,2)){
        t.submit(mode,0x1003c,Seq.fill(5)(BigInt(0)),Some(0x1003c+(if(mode==1)15 else 19)))
        t.complete(2,0,0x1003c);assert(t.reads.isEmpty)
        val values=if(mode==1)Seq(3,1,0,1,0)else Seq(3,1,0,0,1)
        t.submit(mode,0x1003c,values.map(BigInt(_)));t.complete(1,2,0x1003c+(if(mode==1)12 else 16))
      }
      t.submit(2,0x10000,Seq[BigInt](0,0xffffffffL,0,0,0));t.complete(0,1)
    }
  }
  it should "drain faulted or canceled reads retain held offers and results and recover without an early second line" in {
    simulate(new IndirectDrawReader){d=>val t=new Driver(d)
      val values=Seq[BigInt](3,2,1,0xffffffffL,0)
      for(fault<-Seq[BigInt](0x10000,0x10040)){
        t.faultAt=Set(fault);t.submit(2,0x1003c,values);t.complete(3,if(fault==0x10000)1 else 2,fault)
      }
      t.faultAt=Set.empty;t.blockRequests=true;t.submit(2,0x1003c,values)
      t.until(d.io.memoryRequest.valid.peek().litToBoolean);val offered=d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for(_<-0 until 7){t.tick();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==offered);d.io.out.valid.expect(false.B)}
      t.blockRequests=false;t.complete(4,1);assert(t.reads.toVector==Vector(BigInt(0x10000)))
      t.blockResponses=true;t.submit(2,0x1003c,values);t.until(t.pending.nonEmpty)
      d.io.cancel.poke(true.B);for(_<-0 until 7){t.tick();d.io.out.valid.expect(false.B)}
      t.blockResponses=false;t.complete(4,1);assert(t.reads.size==1)
      d.io.cancel.poke(true.B);t.submit(2,0x1003c,values);t.complete(4,0);assert(t.reads.isEmpty)
      t.submit(2,0x1003c,values);t.complete(0,2)
    }
  }
}
