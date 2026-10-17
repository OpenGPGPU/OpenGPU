package opengpu.graphics
import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
class StagingResponseQuietSpec extends AnyFlatSpec {
  it should "keep inactive payloads quiet while retaining stalled replies and independent owners" in {
    simulate(new FragmentStagingArbiter(GpuConfig(lanes=4,warps=2),2,
      slots=4,localOutstanding=2,flowRequests=true)) { d =>
      d.io.memoryRequest.ready.poke(true.B)
      d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.io.clients.foreach { c =>
        c.request.valid.poke(false.B);c.request.bits.poke(0.U.asTypeOf(c.request.bits))
        c.response.ready.poke(false.B)
      }
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def quiet(owner:Int):Unit = {
        d.io.clients(owner).response.valid.expect(false.B)
        assert(d.io.clients(owner).response.bits.readData.peek().litValue==0,
          "inactive staging worker payload must remain quiet")
      }
      for(i <- 1 to 5) {
        d.io.memoryResponse.bits.readData.poke(((BigInt(i)<<480)|i).U)
        quiet(0);quiet(1);d.clock.step()
      }
      def send(owner:Int,local:Int):BigInt = {
        val c=d.io.clients(owner);c.request.valid.poke(true.B)
        c.request.bits.address.poke((0x1000+owner*64).U)
        c.request.bits.sizeLog2.poke(6.U);c.request.bits.transactionId.poke(local.U)
        c.request.ready.expect(true.B);d.io.memoryRequest.valid.expect(true.B)
        val global=d.io.memoryRequest.bits.transactionId.peek().litValue
        d.clock.step();c.request.valid.poke(false.B);global
      }
      val a=send(0,1);val b=send(1,0);assert(a!=b)
      val first=(BigInt(0x12345678L)<<480)|BigInt(0x99aabbccL)
      val second=(BigInt(0xfedcba98L)<<480)|BigInt(0x55667788L)
      d.io.memoryResponse.valid.poke(true.B)
      d.io.memoryResponse.bits.transactionId.poke(a.U)
      d.io.memoryResponse.bits.readData.poke(first.U);d.io.memoryResponse.bits.fault.poke(true.B)
      d.io.memoryResponse.ready.expect(true.B)
      d.io.clients(0).response.valid.expect(true.B)
      d.io.clients(0).response.bits.readData.expect(first.U);quiet(1)
      d.clock.step()
      d.io.clients(1).response.ready.poke(true.B)
      d.io.memoryResponse.bits.transactionId.poke(b.U)
      d.io.memoryResponse.bits.readData.poke(second.U);d.io.memoryResponse.bits.fault.poke(false.B)
      d.io.clients(0).response.bits.readData.expect(first.U)
      d.io.clients(0).response.bits.transactionId.expect(1.U)
      d.io.clients(0).response.bits.fault.expect(true.B)
      d.io.clients(1).response.valid.expect(true.B)
      d.io.clients(1).response.bits.readData.expect(second.U)
      d.io.clients(1).response.bits.transactionId.expect(0.U)
      d.io.clients(1).response.bits.fault.expect(false.B)
      d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      for(i <- 1 to 4) {
        d.io.memoryResponse.bits.readData.poke(((BigInt(i)<<400)|i).U)
        d.io.clients(0).response.valid.expect(true.B)
        d.io.clients(0).response.bits.readData.expect(first.U)
        d.io.clients(0).response.bits.fault.expect(true.B);quiet(1);d.clock.step()
      }
      d.io.clients(0).response.ready.poke(true.B);d.clock.step()
      quiet(0);quiet(1);d.io.drained.expect(true.B)
    }
  }
}
