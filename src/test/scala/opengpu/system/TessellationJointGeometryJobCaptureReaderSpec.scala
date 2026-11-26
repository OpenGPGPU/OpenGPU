package opengpu.system

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointGeometryJobCaptureReaderSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  private def geometryJob(domain:Int=0,count:Int=1,frameBase:BigInt=0x2000):Array[BigInt]={
    val w=TessellationJointReaderTestSupport.job(domain,count,frameBase=frameBase)
    w(0)=BigInt("474e5107",16)+domain;w(2)=3
    if(count>0){w(36)=1;w(37)=0xc000;w(38)=0xc000+64+1024*count
      w(40)=count;w(41)=1024;w(42)=64+1024*count}
    w
  }
  it should "capture independent GNQ7-9 roots and reject geometry-table alias, dialect, bounds and cancellation faults" in {
    simulate(new TessellationJointGeometryJobCaptureReader(config,8,enabled=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val m=new Memory(d.clock,d.io.memoryRequest,d.io.memoryResponse)
      def begin(w:Array[BigInt],domain:Int=0,base:BigInt=0x1000,edit:()=>Unit=()=>()):Unit={
        m.clearReads();m.put(base,w);request(d.io.submit.bits,domain,base);edit()
        d.io.submit.valid.poke(true.B);m.until("submit")(d.io.submit.ready.peek().litToBoolean);m.tick();d.io.submit.valid.poke(false.B)
      }
      def finish(status:Int,bytes:Int=512,clear:Boolean=false):Unit={
        m.until("completion")(d.io.completion.valid.peek().litToBoolean)
        d.io.drained.expect(true.B);d.io.completion.bits.status.expect(status.U);d.io.completion.bits.commandId.expect(5.U)
        d.io.completion.bits.readBytes.expect(bytes.U);d.io.completion.bits.clearOnly.expect(clear.B)
        if(status!=0)assert(d.io.completion.bits.captured.peek().litValue==0)
        val value=d.io.completion.bits.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 4){m.tick();d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==value)
          d.io.submit.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);m.tick();d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B)
        assert(m.empty)
      }
      for(domain<-0 until 3){begin(geometryJob(domain),domain);m.until("healthy")(d.io.completion.valid.peek().litToBoolean)
        val c=d.io.completion.bits.captured;c.request.domain.expect(domain.U);c.job.identity.generation.expect(9.U)
        c.job.sequence.base.expect(0x4000.U);c.job.sequence.limit.expect(0x4800.U);c.job.directory.limit.expect(0x8080.U);c.job.geometry.base.expect(0xc000.U);c.job.geometry.limit.expect(0xc440.U);c.job.geometryCount.expect(1.U)
        finish(0);assert(m.reads.size==8)}
      begin(geometryJob(count=0));finish(0,clear=true)
      // A nonzero invocation may have zero work; it still has a real directory.
      begin(geometryJob());finish(0)
      val endJob=geometryJob(frameBase=top-2048);begin(endJob);finish(0)
      // The job view itself may end exactly at 2^32 with a larger allocation
      // forbidden only by the VA boundary, not by a truncated end calculation.
      begin(geometryJob(),base=top-512,edit=()=>d.io.submit.bits.view.allocationLimit.poke(top.U));finish(0)
      for(word<-Seq(8,15,43,127,19,23,30,39)){
        val w=geometryJob();w(word)=if(Seq(19,23,30,39).contains(word))2 else 1;begin(w);finish(1)
      }
      for(magic<-Seq(BigInt("474e5101",16),BigInt("474e5105",16),BigInt("47564d02",16))){
        val w=geometryJob();w(0)=magic;begin(w);finish(1)}
      // Root v2 and every historical GNQ magic remain rejected by this reader.
      for(i<-1 to 6){val w=geometryJob();w(0)=BigInt("474e5100",16)+i;begin(w);finish(1)}
      for((word,value)<-Seq(36->BigInt(0),40->BigInt(2),41->BigInt(512),42->BigInt(1024))){
        val w=geometryJob();w(word)=value;begin(w);finish(1)}
      // Exact GGA table may end at 2^32; an overflowing, truncated or aliased
      // table cannot acquire authority from a valid numeric owner envelope.
      val topTable=geometryJob();topTable(37)=top-1088;topTable(38)=0;topTable(39)=1
      begin(topTable);finish(0)
      val wrapped=topTable.clone();wrapped(37)=top-1024;wrapped(38)=64;begin(wrapped);finish(1)
      for(base<-Seq(0x1000,0x2000,0x4000,0x8000)){
        val w=geometryJob();w(37)=base;w(38)=base+1088;begin(w);finish(1)}
      val badEmpty=geometryJob(count=0);badEmpty(36)=1;begin(badEmpty);finish(1)
      val badVersion=geometryJob();badVersion(2)=1;begin(badVersion);finish(1)
      val badIdentity=geometryJob();badIdentity(25)=10;begin(badIdentity);finish(1)
      val oversize=geometryJob();oversize(17)=0xffffffffL;oversize(31)=0xffffffffL;oversize(20)=0xffffffc0L
      begin(oversize);finish(1)
      val directoryOverflow=geometryJob();directoryOverflow(17)=0x04000000;directoryOverflow(31)=0x04000000
      begin(directoryOverflow);finish(1)
      val frameWrap=geometryJob();frameWrap(21)=0xffffffc0L;frameWrap(22)=0x7c0;frameWrap(23)=1
      begin(frameWrap);finish(1)
      val wrongCount=geometryJob();wrongCount(31)=2;begin(wrongCount);finish(1)
      val emptyHasDirectory=geometryJob(count=0);emptyHasDirectory(27)=2;begin(emptyHasDirectory);finish(1)
      val alias=geometryJob();alias(21)=0x4000;alias(22)=0x4800;begin(alias);finish(1)
      begin(geometryJob(),edit=()=>d.io.submit.bits.owner.limit.poke(0x8000.U));finish(1)
      begin(geometryJob(),edit=()=>d.io.submit.bits.view.limit.poke(0x1240.U));finish(1,0)
      begin(geometryJob(),edit=()=>d.io.submit.bits.view.allocationLimit.poke(0x11c0.U));finish(1,0)
      begin(geometryJob(),edit=()=>d.io.submit.bits.owner.base.poke(0x1040.U));finish(1,0)
      // A logical view inside the owner cannot authorize allocation tails in
      // another owner's envelope, even when no tail bytes are read here.
      begin(geometryJob(),edit=()=>{d.io.submit.bits.owner.base.poke(0x1000.U)
        d.io.submit.bits.view.allocationBase.poke(0xfc0.U)});finish(1,0)
      begin(geometryJob(),edit=()=>{d.io.submit.bits.owner.limit.poke(0x9000.U)
        d.io.submit.bits.view.allocationLimit.poke(0x9040.U)});finish(1,0)
      // Cancel a genuinely held offer: it must fire and consume its late reply.
      m.holdRequest=true;begin(geometryJob());m.until("held request")(d.io.memoryRequest.valid.peek().litToBoolean)
      val offer=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 4){m.tick();assert(d.io.memoryRequest.bits.peek().litValue==offer);d.io.completion.valid.expect(false.B)}
      m.holdRequest=false;m.holdReply=true;m.until("accepted request")(m.reads.nonEmpty)
      for(_<-0 until 4){m.tick();d.io.drained.expect(false.B);d.io.completion.valid.expect(false.B)}
      m.holdReply=false;finish(4,64)
      // A real fault arriving after cancellation wins over cancellation.
      m.faultAt=Set(BigInt(0x1000));begin(geometryJob());m.until("request accepted")(m.reads.nonEmpty)
      d.io.cancel.poke(true.B);finish(2,64);m.faultAt=Set.empty
      begin(geometryJob());finish(0) // no reset, no stale cancelled latch/credit
    }
  }
  it should "remain unavailable by default without issuing memory" in {
    simulate(new TessellationJointGeometryJobCaptureReader(config,8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      request(d.io.submit.bits);d.io.submit.valid.poke(true.B);d.clock.step();d.io.submit.valid.poke(false.B)
      d.io.memoryRequest.valid.expect(false.B);d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(6.U);d.io.completion.bits.readBytes.expect(0.U)
    }
  }
}
