package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ComputeMemoryAxiMasterSpec extends AnyFlatSpec {
  behavior of "ComputeMemoryAxiMaster"

  private def initialize(dut: ComputeMemoryAxiMaster): Unit = {
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
    dut.io.response.ready.poke(false.B)
    dut.io.m_axi_awready.poke(false.B)
    dut.io.m_axi_wready.poke(false.B)
    dut.io.m_axi_bid.poke(0.U)
    dut.io.m_axi_bresp.poke(0.U)
    dut.io.m_axi_bvalid.poke(false.B)
    dut.io.m_axi_arready.poke(false.B)
    dut.io.m_axi_rid.poke(0.U)
    dut.io.m_axi_rdata.poke(0.U)
    dut.io.m_axi_rresp.poke(0.U)
    dut.io.m_axi_rlast.poke(false.B)
    dut.io.m_axi_rvalid.poke(false.B)
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
  }

  private def submit(
    dut: ComputeMemoryAxiMaster,
    address: BigInt,
    data: BigInt,
    mask: BigInt,
    write: Boolean,
    sizeLog2: Int,
    id: Int
  ): Unit = {
    dut.io.request.bits.address.poke(address.U)
    dut.io.request.bits.writeData.poke(data.U)
    dut.io.request.bits.byteMask.poke(mask.U)
    dut.io.request.bits.isWrite.poke(write.B)
    dut.io.request.bits.sizeLog2.poke(sizeLog2.U)
    dut.io.request.bits.cacheClient.poke(false.B)
    dut.io.request.bits.cacheResident.poke(false.B)
    dut.io.request.bits.transactionId.poke(id.U)
    dut.io.request.valid.poke(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  it should "convert a cache-line read into an AXI INCR burst" in {
    simulate(new ComputeMemoryAxiMaster(
      GpuConfig(), maxOutstanding = 16, axiDataBytes = 8)) { dut =>
      initialize(dut)
      submit(dut, 0x4000, 0, 0, write = false, sizeLog2 = 6, id = 9)

      dut.io.m_axi_arvalid.expect(true.B)
      dut.io.m_axi_arid.expect(9.U)
      dut.io.m_axi_araddr.expect(0x4000.U)
      dut.io.m_axi_arlen.expect(7.U)
      dut.io.m_axi_arsize.expect(3.U)
      dut.io.m_axi_arburst.expect(1.U)
      dut.io.m_axi_arready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_arready.poke(false.B)

      var expected = BigInt(0)
      for (beat <- 0 until 8) {
        val word = BigInt("1122334400000000", 16) + beat
        expected |= word << (beat * 64)
        dut.io.m_axi_rid.poke(9.U)
        dut.io.m_axi_rdata.poke(word.U)
        dut.io.m_axi_rresp.poke(0.U)
        dut.io.m_axi_rlast.poke((beat == 7).B)
        dut.io.m_axi_rvalid.poke(true.B)
        dut.io.m_axi_rready.expect(true.B)
        dut.clock.step()
      }
      dut.io.m_axi_rvalid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(9.U)
      dut.io.response.bits.readData.expect(expected.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.ready.expect(true.B)
    }
  }

  it should "place a narrow word in the addressed AXI byte lanes" in {
    simulate(new ComputeMemoryAxiMaster(
      GpuConfig(), maxOutstanding = 8, axiDataBytes = 8)) { dut =>
      initialize(dut)
      submit(dut, 0x1004, 0xaabbccddL, 0xf, write = true,
        sizeLog2 = 2, id = 5)

      dut.io.m_axi_awvalid.expect(true.B)
      dut.io.m_axi_awaddr.expect(0x1004.U)
      dut.io.m_axi_awlen.expect(0.U)
      dut.io.m_axi_awsize.expect(2.U)
      dut.io.m_axi_wvalid.expect(true.B)
      dut.io.m_axi_wdata.expect(BigInt("aabbccdd00000000", 16).U)
      dut.io.m_axi_wstrb.expect(0xf0.U)
      dut.io.m_axi_wlast.expect(true.B)

      // AW and W are independent: accept data first, then the address.
      dut.io.m_axi_wready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_wready.poke(false.B)
      dut.io.m_axi_wvalid.expect(false.B)
      dut.io.m_axi_awvalid.expect(true.B)
      dut.io.m_axi_awready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_awready.poke(false.B)

      dut.io.m_axi_bready.expect(true.B)
      dut.io.m_axi_bid.poke(5.U)
      dut.io.m_axi_bresp.poke(2.U) // SLVERR
      dut.io.m_axi_bvalid.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_bvalid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(5.U)
      dut.io.response.bits.readData.expect(0.U)
      dut.io.response.bits.fault.expect(true.B)
    }
  }

  it should "extract a narrow read and hold its response under backpressure" in {
    simulate(new ComputeMemoryAxiMaster(
      GpuConfig(), maxOutstanding = 4, axiDataBytes = 8)) { dut =>
      initialize(dut)
      submit(dut, 0x2004, 0, 0, write = false, sizeLog2 = 2, id = 2)
      dut.io.m_axi_arready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_arready.poke(false.B)
      dut.io.m_axi_rid.poke(2.U)
      dut.io.m_axi_rdata.poke(BigInt("deadbeef55667788", 16).U)
      dut.io.m_axi_rresp.poke(0.U)
      dut.io.m_axi_rlast.poke(true.B)
      dut.io.m_axi_rvalid.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_rvalid.poke(false.B)

      for (_ <- 0 until 3) {
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.readData.expect(0xdeadbeefL.U)
        dut.io.response.bits.transactionId.expect(2.U)
        // The completed reply owns a queue credit; the read engine can now
        // accept another request while this reply remains stable.
        dut.io.request.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.request.ready.expect(true.B)
    }
  }
  for (bytes <- Seq(4, 16, 32, 64)) {
    it should s"preserve narrow-word lanes and acknowledgements on ${bytes * 8}-bit AXI" in {
      simulate(new ComputeMemoryAxiMaster(GpuConfig(), maxOutstanding = 8,
        axiDataBytes = bytes)) { dut =>
        initialize(dut)
        val address = 0x1000 + bytes - 4
        val shift = (bytes - 4) * 8
        val word = BigInt("aabbccdd", 16)
        submit(dut, address, word, 15, write = true, sizeLog2 = 2, id = 5)
        dut.io.m_axi_awaddr.expect(address.U)
        dut.io.m_axi_awlen.expect(0.U)
        dut.io.m_axi_awsize.expect(2.U)
        dut.io.m_axi_wdata.expect((word << shift).U)
        dut.io.m_axi_wstrb.expect((BigInt(15) << (bytes - 4)).U)
        dut.io.m_axi_wlast.expect(true.B)
        // Independently stalled AW/W channels keep the full wide payload.
        dut.io.m_axi_awready.poke(true.B); dut.clock.step()
        dut.io.m_axi_awready.poke(false.B)
        dut.clock.step(2)
        dut.io.m_axi_wvalid.expect(true.B)
        dut.io.m_axi_wdata.expect((word << shift).U)
        dut.io.m_axi_wready.poke(true.B); dut.clock.step()
        dut.io.m_axi_wready.poke(false.B)
        dut.io.m_axi_bid.poke(5.U); dut.io.m_axi_bvalid.poke(true.B)
        dut.clock.step(); dut.io.m_axi_bvalid.poke(false.B)
        dut.io.response.valid.expect(true.B)
        dut.io.response.bits.transactionId.expect(5.U)
        dut.io.response.bits.fault.expect(false.B)
        dut.io.response.ready.poke(true.B); dut.clock.step()
        dut.io.response.ready.poke(false.B)
        submit(dut, address, 0, 0, write = false, sizeLog2 = 2, id = 3)
        dut.io.m_axi_araddr.expect(address.U)
        dut.io.m_axi_arlen.expect(0.U)
        dut.io.m_axi_arsize.expect(2.U)
        dut.io.m_axi_arready.poke(true.B); dut.clock.step()
        dut.io.m_axi_arready.poke(false.B)
        dut.io.m_axi_rid.poke(3.U); dut.io.m_axi_rdata.poke((word << shift).U)
        dut.io.m_axi_rlast.poke(true.B); dut.io.m_axi_rvalid.poke(true.B)
        dut.clock.step(); dut.io.m_axi_rvalid.poke(false.B)
        for (_ <- 0 until 3) {
          dut.io.response.valid.expect(true.B)
          dut.io.response.bits.transactionId.expect(3.U)
          dut.io.response.bits.readData.expect(word.U)
          dut.io.response.bits.fault.expect(false.B)
          dut.clock.step()
        }
      }
    }
  }

  for (bytes <- Seq(8, 32, 64)) {
    it should s"overlap read and write bursts and arbitrate simultaneous replies on ${bytes * 8}-bit AXI" in {
      simulate(new ComputeMemoryAxiMaster(GpuConfig(), maxOutstanding = 4,
        axiDataBytes = bytes)) { dut =>
        initialize(dut)
        val line = (BigInt(1) << 511) | BigInt("123456789abcdef", 16)
        val mask = (BigInt(1) << 64) - 1
        for (writeFirst <- Seq(true, false)) {
          val firstId = if (writeFirst) 1 else 2
          submit(dut, if (writeFirst) 0x1000 else 0x2000, line, mask,
            write = writeFirst, sizeLog2 = 6, id = firstId)
          // Neither AR nor AW/W is ready: accepting the opposite direction
          // must not alter the already presented address/data.
          submit(dut, if (writeFirst) 0x2000 else 0x1000, line, mask,
            write = !writeFirst, sizeLog2 = 6, id = (if (writeFirst) 2 else 1))
          dut.io.m_axi_awvalid.expect(true.B)
          dut.io.m_axi_arvalid.expect(true.B)
          dut.io.m_axi_awid.expect(1.U)
          dut.io.m_axi_arid.expect(2.U)
          dut.io.m_axi_awaddr.expect(0x1000.U)
          dut.io.m_axi_araddr.expect(0x2000.U)
          dut.io.m_axi_awready.poke(true.B)
          dut.io.m_axi_arready.poke(true.B)
          dut.clock.step()
          dut.io.m_axi_awready.poke(false.B)
          dut.io.m_axi_arready.poke(false.B)
          dut.io.m_axi_wready.poke(true.B)
          for (beat <- 0 until 64 / bytes) {
            val expected = (line >> (beat * bytes * 8)) & ((BigInt(1) << (bytes * 8)) - 1)
            dut.io.m_axi_wdata.expect(expected.U)
            dut.io.m_axi_wstrb.expect(((BigInt(1) << bytes) - 1).U)
            dut.io.m_axi_wlast.expect((beat == 64 / bytes - 1).B)
            dut.clock.step()
          }
          dut.io.m_axi_wready.poke(false.B)
          // Accumulate an error from the first read beat, including when it
          // is not the last beat. Keep the write acknowledgement pending.
          dut.io.m_axi_rid.poke(2.U)
          dut.io.m_axi_rvalid.poke(true.B)
          for (beat <- 0 until 64 / bytes - 1) {
            dut.io.m_axi_rdata.poke(((line >> (beat * bytes * 8)) & ((BigInt(1) << (bytes * 8)) - 1)).U)
            dut.io.m_axi_rresp.poke((if (beat == 0) 2 else 0).U)
            dut.io.m_axi_rlast.poke(false.B)
            dut.io.m_axi_rready.expect(true.B)
            dut.clock.step()
          }
          dut.io.m_axi_rdata.poke((line >> ((64 / bytes - 1) * bytes * 8)).U)
          dut.io.m_axi_rresp.poke((if (bytes == 64) 2 else 0).U)
          dut.io.m_axi_rlast.poke(true.B)
          dut.io.m_axi_bid.poke(1.U)
          dut.io.m_axi_bresp.poke(0.U)
          dut.io.m_axi_bvalid.poke(true.B)
          val readAccepted = dut.io.m_axi_rready.peek().litToBoolean
          val writeAccepted = dut.io.m_axi_bready.peek().litToBoolean
          assert(readAccepted != writeAccepted, "simultaneous completions share one response enqueue")
          dut.clock.step()
          if (readAccepted) dut.io.m_axi_rvalid.poke(false.B)
          else dut.io.m_axi_bvalid.poke(false.B)
          if (!readAccepted) dut.io.m_axi_rready.expect(true.B)
          else dut.io.m_axi_bready.expect(true.B)
          dut.clock.step()
          dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_bvalid.poke(false.B)
          val head = dut.io.response.bits.transactionId.peek().litValue
          dut.clock.step(4)
          dut.io.response.bits.transactionId.expect(head.U)
          dut.io.response.ready.poke(true.B)
          val seen = scala.collection.mutable.Set.empty[BigInt]
          for (_ <- 0 until 2) {
            dut.io.response.valid.expect(true.B)
            val id = dut.io.response.bits.transactionId.peek().litValue
            seen += id
            dut.io.response.bits.fault.expect((id == 2).B)
            dut.io.response.bits.readData.expect((if (id == 2) line else BigInt(0)).U)
            dut.clock.step()
          }
          assert(seen == Set(BigInt(1), BigInt(2)))
          dut.io.response.ready.poke(false.B)
        }
      }
    }
  }

  it should "retain the advertised outstanding credit until a queued reply is consumed" in {
    simulate(new ComputeMemoryAxiMaster(GpuConfig(), maxOutstanding = 1,
      axiDataBytes = 64)) { dut =>
      initialize(dut)
      submit(dut, 0x1000, 0, 0, write = false, sizeLog2 = 6, id = 0)
      dut.io.request.bits.isWrite.poke(true.B)
      dut.io.request.ready.expect(false.B)
      dut.io.m_axi_arready.poke(true.B); dut.clock.step()
      dut.io.m_axi_rvalid.poke(true.B)
      dut.io.m_axi_rlast.poke(true.B)
      dut.clock.step(); dut.io.m_axi_rvalid.poke(false.B)
      dut.clock.step(3)
      dut.io.response.valid.expect(true.B)
      dut.io.request.ready.expect(false.B)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.request.ready.expect(true.B)
    }
  }

  it should "queue pending requests by direction and count active, queued and completed transactions" in {
    simulate(new ComputeMemoryAxiMaster(GpuConfig(), maxOutstanding = 4,
      axiDataBytes = 64)) { dut =>
      initialize(dut)
      // One stalled AR plus two queued reads must leave admission available
      // for a write, rather than obstruct the opposite direction.
      for (id <- 0 until 3)
        submit(dut, 0x1000 + id * 64, 0, 0, write = false, sizeLog2 = 6, id = id)
      submit(dut, 0x2000, 0x55, 15, write = true, sizeLog2 = 6, id = 3)
      dut.io.request.bits.isWrite.poke(false.B)
      dut.io.request.ready.expect(false.B)
      dut.io.m_axi_arid.expect(0.U)
      dut.io.m_axi_awid.expect(3.U)
      dut.io.m_axi_arready.poke(true.B)
      dut.io.m_axi_awready.poke(true.B)
      dut.io.m_axi_wready.poke(true.B)
      dut.clock.step()
      dut.io.m_axi_arready.poke(false.B)
      dut.io.m_axi_awready.poke(false.B)
      dut.io.m_axi_wready.poke(false.B)
      dut.io.m_axi_rvalid.poke(true.B)
      dut.io.m_axi_rlast.poke(true.B)
      dut.clock.step(); dut.io.m_axi_rvalid.poke(false.B)
      dut.clock.step(3)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(0.U)
      dut.io.m_axi_arid.expect(1.U)
      dut.io.request.ready.expect(false.B)
      // Merely completing R did not release a credit; consuming the queued
      // response does. The queued second AR remains stable throughout.
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.request.ready.expect(true.B)
      dut.io.m_axi_arid.expect(1.U)
    }
  }

}
