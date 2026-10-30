package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class AtomicCounterVectorExecuteUnitSpec extends AnyFlatSpec {
  it should "sequence checked counter lanes, preserve masks and drain a held offer" in {
    simulate(new AtomicCounterVectorExecuteUnit(GpuConfig(lanes=4,warps=2))) {d=>
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.enabled.poke(true.B);d.io.kernelStart.poke(false.B);d.io.cancel.poke(false.B)
      d.io.in.valid.poke(false.B);d.io.commit.ready.poke(false.B);d.io.request.ready.poke(false.B)
      d.io.response.valid.poke(false.B);d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      def until(label:String)(p: => Boolean):Unit={var n=0;while(!p && n<50){d.clock.step();n+=1};assert(p,s"$label stalled")}
      def issue(fn:Int,execution:Int,sideEffects:Int):Unit={val q=d.io.in.bits;q.poke(0.U.asTypeOf(q))
        q.issued.decode.instruction.poke(((BigInt(fn)<<26)|(BigInt(1)<<25)|(BigInt(5)<<7)|0x2b).U)
        q.issued.decode.warpId.poke(1.U);q.issued.decode.pc.poke(0x100.U);q.issued.decode.activeMask.poke(15.U)
        q.executionMask.poke(execution.U);q.sideEffectMask.poke(sideEffects.U)
        for(i<-0 until 4){q.issued.vs1Data(i).poke((i+4).U);q.issued.oldVdData(i).poke((200+i).U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      }
      def lane(l:Int,op:Int,value:Int,fault:Boolean=false,cancel:Boolean=false):Unit={
        until("request")(d.io.request.valid.peek().litToBoolean);d.io.request.bits.counter.expect((l+4).U)
        d.io.request.bits.operation.expect(op.U);d.io.request.bits.warpId.expect(1.U);d.io.request.bits.tag.expect(l.U)
        val held=d.io.request.bits.peek().litValue;if(cancel)d.io.cancel.poke(true.B)
        d.clock.step(2);d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==held)
        d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
        d.io.response.bits.tag.poke(l.U);d.io.response.bits.value.poke(value.U)
        d.io.response.bits.status.poke((if(fault)AtomicCounterStatus.memoryFault else AtomicCounterStatus.ok).U)
        d.io.response.bits.faultAddressValid.poke(fault.B);d.io.response.bits.faultAddress.poke(0x2040.U)
        d.io.response.bits.pageFault.poke(fault.B);d.io.response.valid.poke(true.B);d.io.response.ready.expect(true.B)
        d.clock.step();d.io.response.valid.poke(false.B);d.io.cancel.poke(false.B)
      }
      def finish(status:Int,write:Boolean,values:Seq[Int]=Seq.empty):Unit={
        until("commit")(d.io.commit.valid.peek().litToBoolean);d.io.status.expect(status.U)
        d.io.commit.bits.writesVd.expect(write.B);d.io.commit.bits.writesScalar.expect(false.B)
        if(write)for(i<-0 until 4)d.io.commit.bits.writeback.data(i).expect(values(i).U)
        val held=d.io.commit.bits.peek().litValue;d.clock.step(2);assert(d.io.commit.bits.peek().litValue==held)
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
      }
      issue(17,10,0);lane(1,AtomicCounterOperation.read,11);lane(3,AtomicCounterOperation.read,33)
      finish(0,true,Seq(200,11,202,33))
      issue(18,15,5);lane(0,AtomicCounterOperation.increment,7);lane(2,AtomicCounterOperation.increment,9)
      finish(0,true,Seq(7,201,9,203))
      issue(19,15,8);lane(3,AtomicCounterOperation.decrement,4);finish(0,true,Seq(200,201,202,4))
      issue(18,15,15);lane(0,AtomicCounterOperation.increment,1);lane(1,AtomicCounterOperation.increment,0,fault=true)
      until("fault commit")(d.io.commit.valid.peek().litToBoolean);d.io.faultAddressValid.expect(true.B)
      d.io.faultAddress.expect(0x2040.U);d.io.faultMask.expect(2.U);d.io.pageFault.expect(true.B)
      finish(AtomicCounterStatus.memoryFault,false)
      issue(18,1,1);lane(0,AtomicCounterOperation.increment,0,cancel=true);finish(AtomicCounterStatus.cancelled,false)
      d.io.enabled.poke(false.B);issue(18,1,1);finish(AtomicCounterStatus.invalid,false)
      d.io.enabled.poke(true.B);issue(17,1,1);lane(0,AtomicCounterOperation.read,12);finish(0,true,Seq(12,201,202,203))
    }
  }
}
