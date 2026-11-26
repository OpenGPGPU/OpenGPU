package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Actual accepted memory replies remain authoritative across cancellation. */
class WindowPrimitiveRecordFaultPrioritySpec extends AnyFlatSpec {
  import WindowRecordTest._
  behavior of "WindowPrimitiveRecordTransfer accepted faults"
  it should "retain same-edge and delayed read/write faults through cancel and reuse without reset" in {
    simulate(new WindowPrimitiveRecordTransfer(p=VertexShaderStageConfig())){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val memory=new Memory(d.io.memoryRequest.bits,d.io.memoryResponse.bits,d.io.memoryRequest.valid,
        d.io.memoryRequest.ready,d.io.memoryResponse.valid,d.io.memoryResponse.ready)
      memory.delay=0
      def tick():Unit={memory.drive();memory.capture();d.clock.step()}
      def until(condition: => Boolean):Unit={var n=0;while(!condition && n<5000){tick();n+=1};assert(condition,"record owner did not drain")}
      def submit(write:Boolean):Unit={
        val q=d.io.request.bits;q.arena.version.poke(1.U);identity(q.arena.identity)
        q.arena.base.poke(0x10000.U);q.arena.limit.poke(0x40000.U);q.handle.poke(0.U);q.write.poke(write.B)
        d.io.request.valid.poke(true.B);until(d.io.request.ready.peek().litToBoolean);tick();d.io.request.valid.poke(false.B)
      }
      def finish(code:Int):Unit={
        until(d.io.result.valid.peek().litToBoolean);d.io.result.bits.status.expect(code.U)
        assert(memory.pending.isEmpty && memory.reply.isEmpty,"report escaped accepted reply")
        val held=d.io.result.bits.peek().litValue
        for(n<-0 until 5){d.io.cancel.poke((n%2==0).B);tick();d.io.result.valid.expect(true.B)
          assert(d.io.result.bits.peek().litValue==held,"held result changed after cancellation")}
        d.io.result.ready.poke(true.B);tick();d.io.result.ready.poke(false.B);d.io.cancel.poke(false.B)
      }
      fill(d.io.request.bits.record,71)
      for(write<-Seq(true,false);lead<-Seq(0,3)){
        memory.holdResponses=true;memory.faultAt=Set(BigInt(0x10000));val before=memory.seen.size
        submit(write);until(memory.seen.size==before+1)
        d.io.cancel.poke(true.B)
        for(_<-0 until lead){tick();d.io.result.valid.expect(false.B)}
        memory.holdResponses=false;finish(3);d.io.result.bits.recordValid.expect(false.B)
      }
      // A successful accepted reply under cancellation remains cancellation,
      // and subsequent healthy stores/reads retain the complete payload.
      for(write<-Seq(true,false)){
        memory.faultAt=Set.empty;memory.holdResponses=true;val before=memory.seen.size
        submit(write);until(memory.seen.size==before+1);d.io.cancel.poke(true.B)
        for(_<-0 until 3){tick();d.io.result.valid.expect(false.B)}
        memory.holdResponses=false;finish(4)
      }
      val expected=fill(d.io.request.bits.record,79)
      submit(true);finish(0);submit(false);until(d.io.result.valid.peek().litToBoolean)
      d.io.result.bits.recordValid.expect(true.B)
      assert(values(d.io.result.bits.record)==expected,"healthy reuse changed record bytes")
      finish(0)
    }
  }
}
