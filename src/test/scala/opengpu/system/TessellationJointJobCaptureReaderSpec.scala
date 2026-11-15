package opengpu.system

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointJobCaptureReaderSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  it should "capture exact three-domain GNQ metadata and reject reserved, scope and wide bounds" in {
    simulate(new TessellationJointJobCaptureReader(config,8,enabled=true)){d=>
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
      for(domain<-0 until 3){begin(job(domain),domain);m.until("healthy")(d.io.completion.valid.peek().litToBoolean)
        val c=d.io.completion.bits.captured;c.request.domain.expect(domain.U);c.job.identity.generation.expect(9.U)
        c.job.sequence.base.expect(0x4000.U);c.job.sequence.limit.expect(0x4800.U);c.job.directory.limit.expect(0x8080.U)
        finish(0);assert(m.reads.size==8)}
      begin(job(count=0));finish(0,clear=true)
      // A nonzero invocation may have zero work; it still has a real directory.
      begin(job());finish(0)
      val endJob=job(frameBase=top-2048);begin(endJob);finish(0)
      // The job view itself may end exactly at 2^32 with a larger allocation
      // forbidden only by the VA boundary, not by a truncated end calculation.
      begin(job(),base=top-512,edit=()=>d.io.submit.bits.view.allocationLimit.poke(top.U));finish(0)
      for(word<-Seq(8,15,36,127,19,23,30)){
        val w=job();w(word)=if(Seq(19,23,30).contains(word))2 else 1;begin(w);finish(1)
      }
      for(magic<-Seq(BigInt("474e5101",16),BigInt("474e5105",16),BigInt("47564d02",16))){
        val w=job();w(0)=magic;begin(w);finish(1)}
      val badVersion=job();badVersion(2)=1;begin(badVersion);finish(1)
      val badIdentity=job();badIdentity(25)=10;begin(badIdentity);finish(1)
      val oversize=job();oversize(17)=0xffffffffL;oversize(31)=0xffffffffL;oversize(20)=0xffffffc0L
      begin(oversize);finish(1)
      val directoryOverflow=job();directoryOverflow(17)=0x04000000;directoryOverflow(31)=0x04000000
      begin(directoryOverflow);finish(1)
      val frameWrap=job();frameWrap(21)=0xffffffc0L;frameWrap(22)=0x7c0;frameWrap(23)=1
      begin(frameWrap);finish(1)
      val wrongCount=job();wrongCount(31)=2;begin(wrongCount);finish(1)
      val emptyHasDirectory=job(count=0);emptyHasDirectory(27)=2;begin(emptyHasDirectory);finish(1)
      val alias=job();alias(21)=0x4000;alias(22)=0x4800;begin(alias);finish(1)
      begin(job(),edit=()=>d.io.submit.bits.owner.limit.poke(0x8000.U));finish(1)
      begin(job(),edit=()=>d.io.submit.bits.view.limit.poke(0x1240.U));finish(1,0)
      begin(job(),edit=()=>d.io.submit.bits.view.allocationLimit.poke(0x11c0.U));finish(1,0)
      begin(job(),edit=()=>d.io.submit.bits.owner.base.poke(0x1040.U));finish(1,0)
      // A logical view inside the owner cannot authorize allocation tails in
      // another owner's envelope, even when no tail bytes are read here.
      begin(job(),edit=()=>{d.io.submit.bits.owner.base.poke(0x1000.U)
        d.io.submit.bits.view.allocationBase.poke(0xfc0.U)});finish(1,0)
      begin(job(),edit=()=>{d.io.submit.bits.owner.limit.poke(0x9000.U)
        d.io.submit.bits.view.allocationLimit.poke(0x9040.U)});finish(1,0)
      // Cancel a genuinely held offer: it must fire and consume its late reply.
      m.holdRequest=true;begin(job());m.until("held request")(d.io.memoryRequest.valid.peek().litToBoolean)
      val offer=d.io.memoryRequest.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 4){m.tick();assert(d.io.memoryRequest.bits.peek().litValue==offer);d.io.completion.valid.expect(false.B)}
      m.holdRequest=false;m.holdReply=true;m.until("accepted request")(m.reads.nonEmpty)
      for(_<-0 until 4){m.tick();d.io.drained.expect(false.B);d.io.completion.valid.expect(false.B)}
      m.holdReply=false;finish(4,64)
      // A real fault arriving after cancellation wins over cancellation.
      m.faultAt=Set(BigInt(0x1000));begin(job());m.until("request accepted")(m.reads.nonEmpty)
      d.io.cancel.poke(true.B);finish(2,64);m.faultAt=Set.empty
      begin(job());finish(0) // no reset, no stale cancelled latch/credit
    }
  }
  it should "remain unavailable by default without issuing memory" in {
    simulate(new TessellationJointJobCaptureReader(config,8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      request(d.io.submit.bits);d.io.submit.valid.poke(true.B);d.clock.step();d.io.submit.valid.poke(false.B)
      d.io.memoryRequest.valid.expect(false.B);d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(6.U);d.io.completion.bits.readBytes.expect(0.U)
    }
  }
}
