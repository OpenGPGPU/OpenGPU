package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CounterAtomicArbiterSpec extends AnyFlatSpec {
  it should "hold a selected request and translation context through a backpressured response" in {
    simulate(new CounterAtomicArbiter(GpuConfig(lanes=4,warps=2))){d=>
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.request.ready.poke(false.B);d.io.response.valid.poke(false.B)
      d.io.response.bits.poke(0.U.asTypeOf(d.io.response.bits))
      for(i<-0 until 2){d.io.in(i).valid.poke(false.B);d.io.in(i).bits.poke(0.U.asTypeOf(d.io.in(i).bits));d.io.out(i).ready.poke(false.B);d.io.satp(i).poke((i+1).U)}
      def offer(i:Int,address:Int):Unit={val q=d.io.in(i);q.bits.address.poke(address.U);q.bits.warpId.poke(i.U)
        q.bits.operand.poke((10+i).U);q.bits.operation.poke(AtomicMemoryOp.add);q.valid.poke(true.B)}
      offer(1,0x2000);d.io.in(1).ready.expect(true.B);d.clock.step();d.io.in(1).valid.poke(false.B)
      offer(0,0x1000);d.io.satp(1).poke(99.U);d.io.request.valid.expect(true.B)
      d.io.request.bits.address.expect(0x2000.U);d.io.requestSatp.expect(2.U)
      val held=d.io.request.bits.peek().litValue
      for(_<-0 until 3){d.clock.step();d.io.request.valid.expect(true.B);assert(d.io.request.bits.peek().litValue==held);d.io.in(0).ready.expect(false.B)}
      d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
      d.io.response.bits.warpId.poke(1.U);d.io.response.bits.oldValue.poke(47.U);d.io.response.valid.poke(true.B)
      d.io.response.ready.expect(true.B);d.clock.step();d.io.response.valid.poke(false.B)
      d.io.out(1).valid.expect(true.B);d.io.out(0).valid.expect(false.B);d.io.out(1).bits.oldValue.expect(47.U)
      for(_<-0 until 3){d.clock.step();d.io.out(1).valid.expect(true.B);d.io.in(0).ready.expect(false.B)}
      d.io.out(1).ready.poke(true.B);d.clock.step();d.io.out(1).ready.poke(false.B)
      d.io.in(0).ready.expect(true.B);d.clock.step();d.io.in(0).valid.poke(false.B)
      d.io.request.bits.address.expect(0x1000.U);d.io.requestSatp.expect(1.U)
      d.io.request.ready.poke(true.B);d.clock.step();d.io.request.ready.poke(false.B)
      d.io.response.bits.warpId.poke(0.U);d.io.response.bits.oldValue.poke(9.U);d.io.response.valid.poke(true.B)
      d.clock.step();d.io.response.valid.poke(false.B);d.io.out(0).valid.expect(true.B)
      d.io.out(0).bits.oldValue.expect(9.U);d.io.out(0).ready.poke(true.B);d.clock.step();d.io.busy.expect(false.B)
    }
  }
}
