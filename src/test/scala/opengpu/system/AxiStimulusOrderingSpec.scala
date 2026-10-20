package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Read/write sources whose next command becomes visible when the preceding
  * response is driven. Unaccepted command channels remain asserted. */
private class ResponseChainedMemoryMaster(readChain: Boolean) extends RawModule {
  val io = IO(new Bundle {
    val s_axi_aclk = Input(Clock())
    val reset = Input(Bool())
    val restart = Input(Bool())
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())
    val m_axi_araddr = Output(UInt(32.W))
    val m_axi_arid = Output(UInt(2.W))
    val m_axi_arlen = Output(UInt(8.W))
    val m_axi_rvalid = Input(Bool())
    val m_axi_rready = Output(Bool())
    val m_axi_rlast = Input(Bool())
    val m_axi_rid = Input(UInt(2.W))
    val m_axi_rdata = Input(UInt(64.W))
    val m_axi_rresp = Input(UInt(2.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_awaddr = Output(UInt(32.W))
    val m_axi_awid = Output(UInt(2.W))
    val m_axi_wvalid = Output(Bool())
    val m_axi_wready = Input(Bool())
    val m_axi_wlast = Output(Bool())
    val m_axi_wdata = Output(UInt(64.W))
    val m_axi_wstrb = Output(UInt(8.W))
    val m_axi_bvalid = Input(Bool())
    val m_axi_bready = Output(Bool())
    val m_axi_bid = Input(UInt(2.W))
    val m_axi_bresp = Input(UInt(2.W))
    val done = Output(Bool())
  })
  withClockAndReset(io.s_axi_aclk, io.reset) {
    if (readChain) {
      val first :: response :: secondResponse :: done :: Nil = Enum(4)
      val state = RegInit(first)
      val nextSent = RegInit(false.B)
      val firstAck = RegInit(false.B)
      io.m_axi_arvalid := state === first ||
        (state === response && (io.m_axi_rvalid || firstAck) && !nextSent)
      io.m_axi_araddr := Mux(state === first, 0x1000.U, 0x1040.U)
      io.m_axi_arid := Mux(state === first, 0.U, 1.U)
      io.m_axi_arlen := 0.U
      io.m_axi_rready := state === response || state === secondResponse
      when(state === first && io.m_axi_arvalid && io.m_axi_arready) { state := response }
      when(state === response && io.m_axi_arvalid && io.m_axi_arready) {
        nextSent := true.B
        state := secondResponse
      }
      when(state === response && io.m_axi_rvalid && io.m_axi_rready) {
        assert(io.m_axi_rid === 0.U)
        assert(io.m_axi_rdata === "h1122334455667788".U)
        firstAck := true.B
      }
      when(state === secondResponse && io.m_axi_rvalid && io.m_axi_rready) {
        assert(io.m_axi_rid === 1.U)
        assert(io.m_axi_rdata === "h99aabbccddeeff00".U)
        state := done
      }
      io.m_axi_awvalid := false.B
      io.m_axi_awaddr := 0.U
      io.m_axi_awid := 0.U
      io.m_axi_wvalid := false.B
      io.m_axi_wlast := true.B
      io.m_axi_wdata := 0.U
      io.m_axi_wstrb := 255.U
      io.m_axi_bready := true.B
      io.done := state === done
      when(state === done && io.restart) {
        state := first; nextSent := false.B; firstAck := false.B
      }
    } else {
      val first :: response :: secondResponse :: done :: Nil = Enum(4)
      val state = RegInit(first)
      val awSent = RegInit(false.B)
      val wSent = RegInit(false.B)
      val firstAck = RegInit(false.B)
      val command = state === first || (state === response && (io.m_axi_bvalid || firstAck))
      io.m_axi_awvalid := command && !awSent
      io.m_axi_wvalid := command && !wSent
      io.m_axi_awaddr := Mux(state === first, 0x1000.U, 0x1040.U)
      io.m_axi_awid := Mux(state === first, 0.U, 1.U)
      io.m_axi_wdata := Mux(state === first,
        "h1122334455667788".U, "h99aabbccddeeff00".U)
      io.m_axi_wstrb := 255.U
      io.m_axi_wlast := true.B
      io.m_axi_bready := state === response || state === secondResponse
      val awFire = io.m_axi_awvalid && io.m_axi_awready
      val wFire = io.m_axi_wvalid && io.m_axi_wready
      when(awFire) { awSent := true.B }
      when(wFire) { wSent := true.B }
      when((state === first || state === response) &&
          (awSent || awFire) && (wSent || wFire)) {
        when(state === first) {
          state := response
          awSent := false.B
          wSent := false.B
        }.otherwise { state := secondResponse }
      }
      when(state === response && io.m_axi_bvalid && io.m_axi_bready) { firstAck := true.B }
      when(state === secondResponse && io.m_axi_bvalid && io.m_axi_bready) {
        assert(io.m_axi_bid === 1.U)
        state := done
      }
      io.done := state === done
      when(state === done && io.restart) {
        state := first; awSent := false.B; wSent := false.B; firstAck := false.B
      }
      io.m_axi_arvalid := false.B
      io.m_axi_araddr := 0.U
      io.m_axi_arid := 0.U
      io.m_axi_arlen := 0.U
      io.m_axi_rready := true.B
    }
  }
}

class AxiStimulusOrderingSpec extends AnyFlatSpec with GpuHostTestSupport {
  behavior of "AXI memory stimulus"
  for (readChain <- Seq(false, true)) {
    it should s"observe a ${if (readChain) "read" else "write"} made visible by a response driven in the same cycle" in {
      simulate(new ResponseChainedMemoryMaster(readChain)) { dut =>
        dut.io.m_axi_arready.poke(false.B)
        dut.io.m_axi_rvalid.poke(false.B)
        dut.io.m_axi_rlast.poke(false.B)
        dut.io.m_axi_rid.poke(0.U)
        dut.io.m_axi_rdata.poke(0.U)
        dut.io.m_axi_rresp.poke(0.U)
        dut.io.m_axi_awready.poke(false.B)
        dut.io.m_axi_wready.poke(false.B)
        dut.io.m_axi_bvalid.poke(false.B)
        dut.io.m_axi_bid.poke(0.U)
        dut.io.m_axi_bresp.poke(0.U)
        dut.io.restart.poke(false.B)
        dut.io.reset.poke(true.B)
        dut.io.s_axi_aclk.step()
        dut.io.reset.poke(false.B)
        val writes = mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt)]
        val reads = mutable.ArrayBuffer.empty[BigInt]
        serviceMemoryMasterPort(new AxiMemoryMasterPort(dut.io), addr => {
          reads += addr
          if (addr == 0x1000) BigInt("1122334455667788", 16)
          else BigInt("99aabbccddeeff00", 16)
        },
          writeAckDelay = 1, maxCycles = 40, noProgressLimit = 20) { (addr, data, mask) =>
          writes += ((addr, data, mask))
        } { dut.io.done.peek().litToBoolean }
        // A caller can clock control MMIO after service returns. No memory
        // transfer may occur until a new service loop owns its bookkeeping.
        def expectPaused(): Unit = {
          dut.io.m_axi_arready.expect(false.B)
          dut.io.m_axi_rvalid.expect(false.B)
          dut.io.m_axi_awready.expect(false.B)
          dut.io.m_axi_wready.expect(false.B)
          dut.io.m_axi_bvalid.expect(false.B)
        }
        expectPaused()
        dut.io.restart.poke(true.B); dut.io.s_axi_aclk.step()
        dut.io.restart.poke(false.B); dut.io.s_axi_aclk.step(7)
        dut.io.done.expect(false.B)
        if (readChain) dut.io.m_axi_arvalid.expect(true.B)
        else { dut.io.m_axi_awvalid.expect(true.B); dut.io.m_axi_wvalid.expect(true.B) }
        expectPaused()
        serviceMemoryMasterPort(new AxiMemoryMasterPort(dut.io), addr => {
          reads += addr
          if (addr == 0x1000) BigInt("1122334455667788", 16)
          else BigInt("99aabbccddeeff00", 16)
        }, writeAckDelay = 1, maxCycles = 40, noProgressLimit = 20) {
          (addr, data, mask) => writes += ((addr, data, mask))
        } { dut.io.done.peek().litToBoolean }
        expectPaused()
        if (readChain) {
          assert(reads.toSeq == Seq.fill(2)(Seq(BigInt(0x1000), BigInt(0x1040))).flatten)
          assert(writes.isEmpty)
        } else {
          assert(reads.isEmpty)
          assert(writes.toSeq == Seq.fill(2)(Seq(
            (BigInt(0x1000), BigInt("1122334455667788", 16), BigInt(255)),
            (BigInt(0x1040), BigInt("99aabbccddeeff00", 16), BigInt(255)))).flatten)
        }
      }
    }
  }
}
