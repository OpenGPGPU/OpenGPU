package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileAttachmentTransferSpec extends AnyFlatSpec {
  private def initialize(d: TileAttachmentTransfer): Unit={
    d.io.begin.poke(false.B);d.io.abort.poke(false.B);d.io.resolve.poke(false.B)
    d.io.local.req.valid.poke(false.B);d.io.local.req.bits.poke(0.U.asTypeOf(d.io.local.req.bits))
    d.io.local.resp.ready.poke(false.B);d.io.local.ackReplaceReady.poke(false.B)
    d.io.mem.req.ready.poke(false.B);d.io.mem.resp.valid.poke(false.B)
    d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits));d.io.mem.responseFault.poke(false.B)
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)
  }
  private def write(d: TileAttachmentTransfer,address: Int,data: Int): Unit={
    d.io.local.req.valid.poke(true.B);d.io.local.req.bits.write.poke(true.B)
    d.io.local.req.bits.addr.poke(address.U);d.io.local.req.bits.data.poke(data.U)
  }
  private def drain(d: TileAttachmentTransfer,expected: Map[Int,Int]): Unit={
    d.io.local.req.valid.poke(false.B);d.io.local.resp.ready.poke(true.B)
    d.io.mem.req.ready.poke(true.B)
    val seen=collection.mutable.Map.empty[Int,Int]
    var cycles=0
    while(seen.size<expected.size && cycles<40) {
      if(d.io.mem.req.valid.peek().litToBoolean) {
        d.io.mem.req.bits.write.expect(true.B)
        val address=d.io.mem.req.bits.addr.peek().litValue.toInt
        assert(!seen.contains(address));seen(address)=d.io.mem.req.bits.data.peek().litValue.toInt
      }
      d.clock.step();cycles+=1
    }
    assert(seen.toMap==expected);d.io.drained.expect(false.B)
    for(address <- expected.keys.toVector.sorted.reverse) {
      d.io.mem.resp.valid.poke(true.B);d.io.mem.resp.bits.write.poke(true.B)
      d.io.mem.resp.bits.addr.poke(address.U);d.io.mem.resp.bits.data.poke(0.U)
      d.io.mem.resp.ready.expect(true.B);d.clock.step()
    }
    d.io.mem.resp.valid.poke(false.B);d.clock.step();d.io.drained.expect(true.B)
    d.io.local.resp.valid.expect(false.B);d.io.fault.expect(false.B)
  }
  it should "replace export acknowledgements every cycle while retaining external write ownership" in {
    simulate(new TileAttachmentTransfer(8)) { d=>
      initialize(d);d.io.local.resp.ready.poke(true.B);d.io.local.ackReplaceReady.poke(true.B)
      val words=(0 until 6).map(i=>(0x1000+4*i)->(0x6000+i)).toMap
      for(i <- 0 until 6) {
        write(d,0x1000+4*i,0x6000+i);d.io.local.req.ready.expect(true.B)
        d.io.local.resp.valid.expect((i>0).B)
        if(i>0){d.io.local.resp.bits.write.expect(true.B);d.io.local.resp.bits.addr.expect((0x1000+4*(i-1)).U)}
        d.clock.step()
      }
      d.io.local.req.valid.poke(false.B);d.io.local.resp.valid.expect(true.B)
      d.io.local.resp.bits.addr.expect((0x1000+4*5).U);d.clock.step()
      d.io.occupancy.expect(6.U);drain(d,words)
    }
  }
  it should "keep private request admission independent of a held acknowledgement consumer" in {
    simulate(new TileAttachmentTransfer(8)) { d=>
      initialize(d);write(d,0x2000,0x1234);d.io.local.req.ready.expect(true.B);d.clock.step()
      write(d,0x2004,0x5678)
      for(_ <- 0 until 3) {
        d.io.local.resp.valid.expect(true.B);d.io.local.resp.bits.addr.expect(0x2000.U)
        d.io.local.req.ready.expect(false.B);d.clock.step()
      }
      d.io.local.resp.ready.poke(true.B);d.io.local.req.ready.expect(false.B)
      d.clock.step();d.io.local.req.ready.expect(true.B);d.clock.step()
      d.io.local.req.valid.poke(false.B);d.io.local.resp.valid.expect(true.B)
      d.io.local.resp.bits.addr.expect(0x2004.U);d.clock.step()
      drain(d,Map(0x2000->0x1234,0x2004->0x5678))
    }
  }
}
