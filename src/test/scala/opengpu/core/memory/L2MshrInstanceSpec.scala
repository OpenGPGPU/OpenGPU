package opengpu.core.memory

import chisel3._
import chisel3.experimental.hierarchy.Instantiate
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class IndependentL2MshrTables extends Module {
  private val tables=Seq.fill(2)(Instantiate(new L2MshrTable(
    GpuConfig(lanes=4),entries=4,maxOutstanding=17,numComputeUnits=2)))
  val io=IO(Vec(2,chiselTypeOf(tables.head.io)))
  for(i<-0 until 2)io(i)<>tables(i).io
}

class L2MshrInstanceSpec extends AnyFlatSpec {
  it should "keep cached tables independent through held responses faults merges and entry reuse" in {
    simulate(new IndependentL2MshrTables){d=>
      for(i<-0 until 2){
        d.io(i).request.valid.poke(false.B)
        d.io(i).request.bits.lineAddress.poke(0.U)
        d.io(i).request.bits.transactionId.poke(0.U)
        d.io(i).request.bits.requester.poke(0.U)
        d.io(i).request.bits.trackSharer.poke(false.B)
        d.io(i).request.bits.victimSet.poke(0.U)
        d.io(i).request.bits.victimWay.poke(0.U)
        d.io(i).allocation.ready.poke(true.B)
        d.io(i).refill.valid.poke(false.B)
        d.io(i).refill.bits.entryId.poke(0.U)
        d.io(i).refill.bits.readData.poke(0.U)
        d.io(i).refill.bits.fault.poke(false.B)
        d.io(i).fill.ready.poke(true.B)
        d.io(i).response.ready.poke(false.B)
      }
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def allocate(bank: Int,line: Int,id: Int,cu: Int,set: Int=0): BigInt={
        val port=d.io(bank)
        port.request.bits.lineAddress.poke(line.U)
        port.request.bits.transactionId.poke(id.U)
        port.request.bits.requester.poke(cu.U)
        port.request.bits.trackSharer.poke(true.B)
        port.request.bits.victimSet.poke(set.U)
        port.request.bits.victimWay.poke(0.U)
        port.request.valid.poke(true.B);port.request.ready.expect(true.B)
        d.clock.step();port.request.valid.poke(false.B)
        port.allocation.valid.expect(true.B)
        val entry=port.allocation.bits.entryId.peek().litValue
        d.clock.step();entry
      }
      def refill(bank: Int,entry: BigInt,data: Int,fault: Boolean=false): Unit={
        val port=d.io(bank)
        port.refill.bits.entryId.poke(entry.U)
        port.refill.bits.readData.poke(data.U);port.refill.bits.fault.poke(fault.B)
        port.refill.valid.poke(true.B);port.refill.ready.expect(true.B)
        d.clock.step();port.refill.valid.poke(false.B)
        port.fill.valid.expect(true.B);port.fill.bits.readData.expect(data.U)
        port.fill.bits.fault.expect(fault.B);d.clock.step()
      }
      def retire(bank: Int,line: Int,id: Int,data: Int,fault: Boolean=false): Unit={
        val port=d.io(bank)
        port.response.valid.expect(true.B)
        port.response.bits.lineAddress.expect(line.U)
        port.response.bits.transactionId.expect(id.U)
        port.response.bits.readData.expect(data.U);port.response.bits.fault.expect(fault.B)
        port.response.ready.poke(true.B);d.clock.step();port.response.ready.poke(false.B)
      }
      // Identical IDs and victim indices belong to distinct physical banks.
      val first=allocate(0,0x1000,16,0)
      val peer=allocate(1,0x2000,16,1)
      refill(0,first,0xaa)
      val held=d.io(0).response.bits.peek().litValue
      d.io(0).response.valid.expect(true.B)
      refill(1,peer,0xbb,fault=true)
      retire(1,0x2000,16,0xbb,fault=true)
      d.io(1).validEntries.expect(0.U)
      assert(d.io(0).response.bits.peek().litValue==held)
      d.io(0).validEntries.expect(1.U)
      val reused=allocate(1,0x3000,0,0)
      allocate(1,0x3000,16,1)
      refill(1,reused,0xcc)
      d.io(1).response.bits.transactionId.expect(0.U)
      retire(1,0x3000,0,0xcc)
      retire(1,0x3000,16,0xcc)
      d.io(1).validEntries.expect(0.U)
      d.io(0).response.valid.expect(true.B)
      assert(d.io(0).response.bits.peek().litValue==held)
      retire(0,0x1000,16,0xaa)
      d.io(0).validEntries.expect(0.U)
      val healthy=allocate(0,0x4000,8,1)
      refill(0,healthy,0xdd);retire(0,0x4000,8,0xdd)
      d.io(0).validEntries.expect(0.U);d.io(1).validEntries.expect(0.U)
    }
  }
}
