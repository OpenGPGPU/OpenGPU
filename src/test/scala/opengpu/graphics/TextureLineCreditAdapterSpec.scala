package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureLineCreditAdapterSpec extends AnyFlatSpec {
  it should "map reordered replies retain stalled leases and reuse only retired physical IDs" in {
    simulate(new TextureLineCreditAdapter(GpuConfig(lanes=4,warps=2))){d=>
      WindowRecordTest.zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(id: Int,address: Int): Unit={
        d.io.in.bits.address.poke(address.U);d.io.in.bits.transactionId.poke(id.U);d.io.in.bits.sizeLog2.poke(6.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      }
      submit(9,0x1000);d.io.memoryRequest.bits.transactionId.expect(0.U)
      val held=d.io.memoryRequest.bits.peek().litValue
      for(_<-0 until 5){d.clock.step();d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==held)}
      d.io.memoryRequest.ready.poke(true.B);d.clock.step()
      submit(12,0x2000);d.io.memoryRequest.bits.transactionId.expect(1.U);d.clock.step()
      d.io.in.valid.poke(true.B);d.io.in.bits.transactionId.poke(4.U);d.io.in.bits.address.poke(0x3000.U);d.io.in.ready.expect(false.B)
      d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.bits.transactionId.poke(1.U);d.io.memoryResponse.bits.readData.poke(222.U)
      d.io.out.valid.expect(true.B);d.io.out.bits.transactionId.expect(12.U);d.io.out.bits.readData.expect(222.U)
      val reply=d.io.out.bits.peek().litValue
      for(_<-0 until 5){d.clock.step();d.io.memoryResponse.ready.expect(false.B);d.io.in.ready.expect(false.B);assert(d.io.out.bits.peek().litValue==reply)}
      d.io.out.ready.poke(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      // Slot1 is free; accept its new logical owner while slot0 retires.
      d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.bits.transactionId.poke(0.U);d.io.memoryResponse.bits.readData.poke(111.U)
      d.io.out.bits.transactionId.expect(9.U);d.io.in.ready.expect(true.B);d.io.memoryRequest.ready.poke(false.B);d.clock.step()
      d.io.in.valid.poke(false.B);d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryRequest.bits.transactionId.expect(1.U);d.io.memoryRequest.bits.address.expect(0x3000.U)
      d.io.memoryRequest.ready.poke(true.B);d.clock.step()
      d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.bits.transactionId.poke(1.U);d.io.memoryResponse.bits.readData.poke(333.U);d.io.memoryResponse.bits.fault.poke(true.B)
      d.io.out.valid.expect(true.B);d.io.out.bits.transactionId.expect(4.U);d.io.out.bits.readData.expect(333.U);d.io.out.bits.fault.expect(true.B)
      d.clock.step();d.io.busy.expect(false.B);d.io.out.valid.expect(false.B);d.io.memoryResponse.ready.expect(false.B)
      info("TEXTURE_LINE_CREDITS slots2=true logicalIDs9And12And4=true reorderedReplies=true heldOfferAndReply=true noEarlyReuse=true simultaneousDifferentSlotRetireAndAllocate=true faultPreserved=true unownedReplyBackpressured=true")
    }
  }
}
