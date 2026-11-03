package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StorageAtomicVectorExecuteUnitSpec extends AnyFlatSpec {
  behavior of "StorageAtomicVectorExecuteUnit"
  it should "capture lane operands, suppress helpers, return old values and drain faults" in {
    simulate(new StorageAtomicVectorExecuteUnit(GpuConfig(lanes=4,warps=2))) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.enabled.poke(true.B); d.io.kernelStart.poke(false.B); d.io.warpStart.valid.poke(false.B)
      d.io.warpStart.bits.poke(0.U); d.io.cancel.poke(false.B); d.io.in.valid.poke(false.B)
      d.io.commit.ready.poke(false.B); d.io.request.ready.poke(false.B)
      d.io.response.valid.poke(false.B); d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      def until(label:String)(f: => Boolean):Unit={var n=0;while(!f && n<30){d.clock.step();n+=1};assert(f,s"$label timed out")}
      def issue(params:Boolean,op:Int=0,execution:Int=15,sideEffects:Int=15):Unit={
        val q=d.io.in.bits; q.poke(0.U.asTypeOf(q))
        val fn=if(params)24 else if(op<8)25 else 26
        q.issued.decode.instruction.poke(((BigInt(fn)<<26)|(BigInt(1)<<25)|0x2b|
          (if(params)0 else (5<<7)|((op%8)<<12))).U)
        q.issued.decode.warpId.poke(0.U);q.issued.decode.pc.poke(0x100.U);q.issued.decode.activeMask.poke(15.U)
        q.executionMask.poke(execution.U);q.sideEffectMask.poke(sideEffects.U)
        for(i<-0 until 4){q.issued.vs1Data(i).poke((if(params)100+i else i).U)
          q.issued.vs2Data(i).poke((if(params)300+i else i*4).U);q.issued.oldVdData(i).poke((200+i).U)}
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      }
      def finish(status:Int,writes:Boolean,scalar:Boolean):Unit={
        until("commit")(d.io.commit.valid.peek().litToBoolean);d.io.status.expect(status.U)
        d.io.commit.bits.writesVd.expect(writes.B);d.io.commit.bits.writesScalar.expect(scalar.B)
        val held=d.io.commit.bits.peek().litValue;d.clock.step(2);assert(d.io.commit.bits.peek().litValue==held)
        d.io.commit.ready.poke(true.B);d.clock.step();d.io.commit.ready.poke(false.B)
      }
      def lane(index:Int,op:Int,value:Int,fault:Boolean=false,cancel:Boolean=false):Unit={
        until("request")(d.io.request.valid.peek().litToBoolean)
        d.io.request.bits.tag.expect(index.U);d.io.request.bits.warpId.expect(0.U)
        d.io.request.bits.bindingIndex.expect(index.U);d.io.request.bits.offset.expect((index*4).U)
        d.io.request.bits.operation.expect(op.U);d.io.request.bits.operand.expect((100+index).U)
        d.io.request.bits.compareValue.expect((300+index).U)
        val held=d.io.request.bits.peek().litValue;if(cancel)d.io.cancel.poke(true.B)
        d.clock.step(2);assert(d.io.request.bits.peek().litValue==held)
        d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
        d.io.response.valid.poke(true.B);d.io.response.bits.tag.poke(index.U);d.io.response.bits.value.poke(value.U)
        d.io.response.bits.status.poke((if(fault)StorageAtomicStatus.memoryFault else StorageAtomicStatus.ok).U)
        d.io.response.bits.pageFault.poke(fault.B);d.io.response.bits.faultAddressValid.poke(fault.B)
        d.io.response.bits.faultAddress.poke(0x4000.U);d.io.response.ready.expect(true.B)
        d.clock.step();d.io.response.valid.poke(false.B);d.io.cancel.poke(false.B)
      }
      issue(params=true,execution=3);finish(0,writes=false,scalar=true)
      issue(params=false,op=9,sideEffects=5);finish(StorageAtomicStatus.invalid,writes=false,scalar=false)
      issue(params=true);finish(0,writes=false,scalar=true)
      for(op<-0 until 10){
        issue(params=false,op=op,sideEffects=5);lane(0,op,110);lane(2,op,112)
        until("atomic commit")(d.io.commit.valid.peek().litToBoolean)
        d.io.commit.bits.writeback.data(0).expect(110.U);d.io.commit.bits.writeback.data(1).expect(201.U)
        d.io.commit.bits.writeback.data(2).expect(112.U);d.io.commit.bits.writeback.data(3).expect(203.U)
        finish(0,writes=true,scalar=false)
      }
      issue(params=false,op=9,sideEffects=5);lane(0,9,110);lane(2,9,0,fault=true)
      until("fault commit")(d.io.commit.valid.peek().litToBoolean)
      d.io.faultAddressValid.expect(true.B);d.io.faultAddress.expect(0x4000.U)
      d.io.faultMask.expect(4.U);d.io.pageFault.expect(true.B)
      finish(StorageAtomicStatus.memoryFault,writes=false,scalar=false)
      issue(params=false,op=1,sideEffects=1);lane(0,1,110,cancel=true)
      finish(StorageAtomicStatus.cancelled,writes=false,scalar=false)
      issue(params=false,op=1,sideEffects=1);lane(0,1,110);finish(0,writes=true,scalar=false)
      d.io.warpStart.valid.poke(true.B);d.clock.step();d.io.warpStart.valid.poke(false.B)
      issue(params=false,op=1,sideEffects=1);finish(StorageAtomicStatus.invalid,writes=false,scalar=false)
    }
  }
}
