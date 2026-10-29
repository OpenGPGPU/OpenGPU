package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class SharedDataPageTableWalkerSpec extends AnyFlatSpec {
  it should "retain queued roots and held reply owners across ordinary and atomic page walks" in {
    simulate(new SharedDataPageTableWalker(GpuConfig())){d=>
      d.io.request.foreach(_.valid.poke(false.B));d.io.request.foreach(_.bits.poke(0.U.asTypeOf(d.io.request(0).bits)))
      d.io.response.foreach(_.ready.poke(false.B));d.io.memoryRequest.ready.poke(true.B)
      d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def submit(port: Int,root: Int,vpn: Int): Unit={
        d.io.request(port).bits.rootPpn.poke(root.U)
        d.io.request(port).bits.request.virtualPageNumber.poke(vpn.U)
        d.io.request(port).bits.request.isStore.poke((port==1).B)
        d.io.request(port).valid.poke(true.B)
        var n=0;while(!d.io.request(port).ready.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);d.clock.step();d.io.request(port).valid.poke(false.B)
      }
      def memory(address: Int,pte: BigInt): Unit={
        var n=0;while(!d.io.memoryRequest.valid.peek().litToBoolean && n<20){d.clock.step();n+=1}
        assert(n<20);d.io.memoryRequest.bits.address.expect(address.U);d.clock.step()
        d.io.memoryResponse.valid.poke(true.B);d.io.memoryResponse.bits.pte.poke(pte.U)
        d.io.memoryResponse.ready.expect(true.B);d.clock.step();d.io.memoryResponse.valid.poke(false.B)
      }
      submit(0,0x100,0x800)
      // The first walker request is already issued when the peer is queued.
      // Hold its memory request so no backing transaction is lost here.
      d.io.memoryRequest.ready.poke(false.B)
      submit(1,0x200,0xc00)
      d.io.request(1).bits.rootPpn.poke(0x999.U)
      d.io.memoryRequest.ready.poke(true.B)
      memory(0x100008,(BigInt(0x40000)<<10)|0xc7)
      d.io.response(0).valid.expect(true.B);d.io.response(1).valid.expect(false.B)
      d.io.response(0).bits.physicalPageNumber.expect(0x40000.U)
      val held=d.io.response(0).bits.peek().litValue
      for(_<-0 until 4){d.clock.step();d.io.memoryRequest.valid.expect(false.B)
        d.io.response(1).valid.expect(false.B);assert(d.io.response(0).bits.peek().litValue==held)}
      d.io.response(0).ready.poke(true.B);d.clock.step();d.io.response(0).ready.poke(false.B)
      memory(0x20000c,(BigInt(0x80000)<<10)|0xc7)
      d.io.response(1).valid.expect(true.B);d.io.response(0).valid.expect(false.B)
      d.io.response(1).bits.physicalPageNumber.expect(0x80000.U)
      d.io.response(1).bits.writable.expect(true.B)
      d.io.response(1).ready.poke(true.B);d.clock.step()
    }
  }
}
