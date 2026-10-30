package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class StorageVectorExecuteUnitSpec extends AnyFlatSpec {
  behavior of "StorageVectorExecuteUnit"
  it should "retain per-warp data, honor lane masks, hold offers and report checked faults" in {
    simulate(new StorageVectorExecuteUnit(GpuConfig(lanes = 4, warps = 2))) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.enabled.poke(true.B); d.io.kernelStart.poke(false.B); d.io.warpStart.valid.poke(false.B)
      d.io.warpStart.bits.poke(0.U); d.io.cancel.poke(false.B); d.io.in.valid.poke(false.B)
      d.io.commit.ready.poke(false.B); d.io.request.ready.poke(false.B)
      d.io.response.valid.poke(false.B); d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      def until(label: String)(f: => Boolean): Unit = {
        var n=0; while(!f && n<30){d.clock.step();n+=1}; assert(f, s"$label timed out")
      }
      def issue(fn: Int, execution: Int, sideEffects: Int): Unit = {
        val q=d.io.in.bits; q.poke(0.U.asTypeOf(q))
        q.issued.decode.instruction.poke(((BigInt(fn)<<26)|(BigInt(1)<<25)|0x2b|(if(fn==15) 5<<7 else 0)).U)
        q.issued.decode.warpId.poke(0.U); q.issued.decode.pc.poke(0x100.U); q.issued.decode.activeMask.poke(15.U)
        q.executionMask.poke(execution.U); q.sideEffectMask.poke(sideEffects.U)
        for(i<-0 until 4){q.issued.vs1Data(i).poke((if(fn==14) 100+i else i).U)
          q.issued.vs2Data(i).poke((i*4).U); q.issued.oldVdData(i).poke((200+i).U)}
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def finish(status: Int, writesVd: Boolean, scalar: Boolean): Unit = {
        until("commit")(d.io.commit.valid.peek().litToBoolean); d.io.status.expect(status.U)
        d.io.commit.bits.writesVd.expect(writesVd.B); d.io.commit.bits.writesScalar.expect(scalar.B)
        val held=d.io.commit.bits.peek().litValue; d.clock.step(2); assert(d.io.commit.bits.peek().litValue==held)
        d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      }
      def lane(index: Int, store: Boolean, value: Int = 0, fault: Boolean = false): Unit = {
        until("request")(d.io.request.valid.peek().litToBoolean)
        d.io.request.bits.tag.expect(index.U); d.io.request.bits.bindingIndex.expect(index.U)
        d.io.request.bits.offset.expect((index*4).U); d.io.request.bits.write.expect(store.B)
        if(store) d.io.request.bits.data.expect((100+index).U)
        val held=d.io.request.bits.peek().litValue; d.clock.step(2); assert(d.io.request.bits.peek().litValue==held)
        d.io.request.ready.poke(true.B); d.clock.step(); d.io.request.ready.poke(false.B)
        d.io.response.valid.poke(true.B); d.io.response.bits.tag.poke(index.U)
        d.io.response.bits.bindingIndex.poke(index.U); d.io.response.bits.data.poke(value.U)
        d.io.response.bits.status.poke((if(fault) StorageWordStatus.memoryFault else StorageWordStatus.ok).U)
        d.io.response.bits.faultAddressValid.poke(fault.B); d.io.response.bits.faultAddress.poke(0x4000.U)
        d.io.response.ready.expect(true.B); d.clock.step(); d.io.response.valid.poke(false.B)
      }
      issue(14, 3, 15); finish(0, writesVd=false, scalar=true)
      issue(16, 15, 5); until("rejected store")(d.io.commit.valid.peek().litToBoolean)
      d.io.request.valid.expect(false.B); finish(StorageWordStatus.invalidView, writesVd=false, scalar=false)
      issue(14, 15, 15); finish(0, writesVd=false, scalar=true)
      issue(16, 15, 5); lane(0, store=true); lane(2, store=true); finish(0, writesVd=false, scalar=true)
      issue(15, 10, 15); lane(1, store=false, value=111); lane(3, store=false, value=333)
      until("load commit")(d.io.commit.valid.peek().litToBoolean)
      d.io.commit.bits.writeback.data(0).expect(200.U); d.io.commit.bits.writeback.data(1).expect(111.U)
      d.io.commit.bits.writeback.data(2).expect(202.U); d.io.commit.bits.writeback.data(3).expect(333.U)
      finish(0, writesVd=true, scalar=false)
      issue(15, 2, 15); lane(1, store=false, fault=true)
      until("fault commit")(d.io.commit.valid.peek().litToBoolean)
      d.io.faultAddressValid.expect(true.B); d.io.faultAddress.expect(0x4000.U)
      finish(StorageWordStatus.memoryFault, writesVd=false, scalar=false)
      d.io.warpStart.valid.poke(true.B); d.clock.step(); d.io.warpStart.valid.poke(false.B)
      issue(16, 1, 1); finish(StorageWordStatus.invalidView, writesVd=false, scalar=false)
    }
  }
}
