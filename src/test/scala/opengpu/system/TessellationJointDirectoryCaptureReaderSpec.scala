package opengpu.system

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointDirectoryCaptureReaderSpec extends AnyFlatSpec {
  import TessellationJointReaderTestSupport._
  it should "capture independent directory identities, enforce stream order and retain cancelled offers" in {
    simulate(new TessellationJointDirectoryCaptureReader(config,8,enabled=true)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val m=new Memory(d.clock,d.io.memoryRequest,d.io.memoryResponse)
      def begin(rows:Seq[Array[BigInt]],invocations:Int=1,domain:Int=0,
          editHeader:Array[BigInt]=>Unit=_=>(),edit:()=>Unit=()=>()):Unit={
        m.clearReads();val h=directoryHeader(rows.size,invocations);editHeader(h);m.put(0x8000,h)
        rows.zipWithIndex.foreach{case(w,i)=>m.put(0x8040+i*64,w)}
        captured(d.io.submit.bits,domain,rows.size);d.io.submit.bits.job.invocationCount.poke(invocations.U);edit()
        d.io.submit.valid.poke(true.B);m.until("submit")(d.io.submit.ready.peek().litToBoolean);m.tick();d.io.submit.valid.poke(false.B)
      }
      def finish(status:Int,count:Int,bytes:Int):Unit={
        m.until("completion")(d.io.completion.valid.peek().litToBoolean)
        val c=d.io.completion.bits;c.status.expect(status.U);c.commandId.expect(5.U)
        c.capturedEntries.expect(count.U);c.readBytes.expect(bytes.U);d.io.drained.expect(true.B)
        val value=c.peek().litValue;d.io.cancel.poke(true.B)
        for(_<-0 until 4){m.tick();d.io.completion.valid.expect(true.B);assert(c.peek().litValue==value);d.io.submit.ready.expect(false.B)}
        d.io.completion.ready.poke(true.B);m.tick();d.io.completion.ready.poke(false.B);d.io.cancel.poke(false.B);assert(m.empty)
      }
      def accept():Unit={
        m.until("entry or error")(d.io.entry.valid.peek().litToBoolean || d.io.completion.valid.peek().litToBoolean)
        d.io.entry.valid.expect(true.B)
        val value=d.io.entry.bits.peek().litValue
        for(_<-0 until 3){m.tick();d.io.entry.valid.expect(true.B);assert(d.io.entry.bits.peek().litValue==value)}
        d.io.entry.ready.poke(true.B);m.tick();d.io.entry.ready.poke(false.B)
      }
      for(domain<-0 until 3){begin(Seq(entry()),domain=domain)
        m.until("metadata")(d.io.entry.valid.peek().litToBoolean)
        val c=d.io.entry.bits;c.domain.expect(domain.U);c.entry.drawIndex.expect(2.U);c.entry.invocationId.expect(0.U)
        c.sequence.base.expect(0x4000.U);c.sequence.limit.expect(0x4800.U)
        c.entry.source.base.expect(0xa000.U);accept();finish(0,1,128)}
      // Separate original invocations may have descending IDs, including zero.
      val second=entry(index=1,id=0,source=0xa200);second(1)=1
      begin(Seq(entry(id=(BigInt(1)<<64)-1),second),invocations=2);accept();accept();finish(0,2,192)
      // Continuation uses a separate range ordinal, not the vertex ordinal.
      val cont=Seq(entry(flags=1,per=2),entry(index=1,flags=6,ordinal=1,firstPatch=2,per=3,source=0xa200))
      begin(cont);accept();m.until("continuation")(d.io.entry.valid.peek().litToBoolean)
      d.io.entry.bits.entry.rangeOrdinal.expect(1.U);d.io.entry.bits.entry.firstInputPatch.expect(2.U)
      d.io.entry.bits.sequence.base.expect(0x5000.U);accept();finish(0,2,192)
      val boundary=entry(source=top-512);begin(Seq(boundary));accept();finish(0,1,128)
      begin(Seq(entry(instances=0,per=0)));accept();finish(0,1,128) // real zero-work invocation
      for(word<-0 until 16){
        begin(Seq(entry()),editHeader=h=>h(word)=h(word)^1);finish(1,0,64)
      }
      for((word,value)<-Seq(0->BigInt(1),1->BigInt(3),4->BigInt(11),5->BigInt(1),
          6->BigInt(1),8->BigInt(1),11->BigInt(3),14->BigInt(2),15->BigInt(1))){
        val row=entry();row(word)=value;begin(Seq(row));finish(1,0,128)
      }
      val unaligned=entry();unaligned(12)=0xa004;unaligned(13)=0xa204;begin(Seq(unaligned));finish(1,0,128)
      val larger=entry();larger(13)=0xa240;begin(Seq(larger));finish(1,0,128)
      val wrapped=entry();wrapped(12)=0xffffffc0L;wrapped(13)=0x1c0;wrapped(14)=1;begin(Seq(wrapped));finish(1,0,128)
      val taskOverflow=entry(task=0xffffffffL);begin(Seq(taskOverflow));finish(1,0,128)
      val instanceOverflow=entry(firstInstance= -1,instances=2);instanceOverflow(6)=0xffffffffL
      begin(Seq(instanceOverflow));finish(1,0,128)
      val emptyBad=entry(instances=0,per=0,firstPatch=1);begin(Seq(emptyBad));finish(1,0,128)
      val noLast=entry(flags=1);begin(Seq(noLast));finish(1,0,128)
      for((word,value)<-Seq(1->BigInt(1),2->BigInt(1),4->BigInt(3),5->BigInt(2),8->BigInt(3))){
        val bad=cont(1).clone();bad(word)=value;begin(Seq(cont.head,bad));accept();finish(1,1,192)
      }
      // Wrong captured owner/frame links are rejected before a directory read.
      begin(Seq(entry()),edit=()=>d.io.submit.bits.request.identity.pass.poke(9.U));finish(1,0,0)
      begin(Seq(entry()),edit=()=>d.io.submit.bits.request.owner.limit.poke(0x8000.U));finish(1,0,0)
      // A held metadata offer is immutable even when cancelled. It must be
      // accepted/dropped by the caller before final cancellation/drain.
      begin(Seq(entry()));m.until("held metadata")(d.io.entry.valid.peek().litToBoolean)
      val stable=d.io.entry.bits.peek().litValue;d.io.cancel.poke(true.B)
      for(_<-0 until 5){m.tick();d.io.stop.expect(true.B);d.io.drained.expect(false.B)
        d.io.completion.valid.expect(false.B);d.io.entry.valid.expect(true.B);assert(d.io.entry.bits.peek().litValue==stable)}
      d.io.entry.ready.poke(true.B);m.tick();d.io.entry.ready.poke(false.B);finish(4,1,128)
      // A held tag-zero read cannot disappear on cancel or report before reply.
      m.holdRequest=true;begin(Seq(entry()));m.until("held read")(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.cancel.poke(true.B);for(_<-0 until 3){m.tick();d.io.memoryRequest.valid.expect(true.B)}
      m.holdRequest=false;m.holdReply=true;m.until("accepted read")(m.reads.nonEmpty)
      for(_<-0 until 4){m.tick();d.io.completion.valid.expect(false.B);d.io.drained.expect(false.B)}
      m.holdReply=false;finish(4,0,64)
      m.faultAt=Set(BigInt(0x8000));begin(Seq(entry()));m.until("late fault request")(m.reads.nonEmpty)
      d.io.cancel.poke(true.B);finish(2,0,64);m.faultAt=Set.empty
      begin(Seq(entry()));accept();finish(0,1,128)
      // No invocation has no directory at all. This is capture, not a clear run.
      m.clearReads();captured(d.io.submit.bits,count=0);d.io.submit.valid.poke(true.B);m.tick();d.io.submit.valid.poke(false.B)
      finish(0,0,0);d.io.completion.bits.clearOnly.expect(true.B)
    }
  }
  it should "reject use while the independent route is disabled" in {
    simulate(new TessellationJointDirectoryCaptureReader(config,8)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);captured(d.io.submit.bits)
      d.io.submit.valid.poke(true.B);d.clock.step();d.io.submit.valid.poke(false.B)
      d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(6.U);d.io.memoryRequest.valid.expect(false.B)
    }
  }
}
