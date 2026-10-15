package opengpu.system

import chisel3._
import opengpu.testutil.GpuSim._

/** Shared cycle-accurate AXI stimulus for integration tests and workload measurement. */
trait GpuHostTestSupport {
  protected def initialize(dut: GpuHostSystemAxi): Unit = {
    dut.io.s_axi_awvalid.poke(false.B)
    dut.io.s_axi_wvalid.poke(false.B)
    dut.io.s_axi_wlast.poke(false.B)
    dut.io.s_axi_bready.poke(false.B)
    dut.io.s_axi_arvalid.poke(false.B)
    dut.io.s_axi_rready.poke(false.B)
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
    dut.io.s_axi_aresetn.poke(false.B)
    dut.io.s_axi_aclk.step()
    dut.io.s_axi_aresetn.poke(true.B)
    dut.io.s_axi_aclk.step()
  }

  protected def axiWrite(
    dut: GpuHostSystemAxi,
    address: Int,
    data: Int
  ): Unit = {
    dut.io.s_axi_awaddr.poke(address.U)
    dut.io.s_axi_awlen.poke(0.U)
    dut.io.s_axi_awsize.poke(2.U)
    dut.io.s_axi_awburst.poke(0.U)
    dut.io.s_axi_awvalid.poke(true.B)
    dut.io.s_axi_wdata.poke((data.toLong & 0xffffffffL).U)
    dut.io.s_axi_wstrb.poke(0xf.U)
    dut.io.s_axi_wlast.poke(true.B)
    dut.io.s_axi_wvalid.poke(true.B)
    dut.io.s_axi_bready.poke(true.B)

    var addressDone = false
    var dataDone = false
    var responseDone = false
    var cycles = 0
    while (!responseDone && cycles < 64) {
      if (!addressDone && dut.io.s_axi_awready.peek().litToBoolean)
        addressDone = true
      if (!dataDone && dut.io.s_axi_wready.peek().litToBoolean)
        dataDone = true
      if (dut.io.s_axi_bvalid.peek().litToBoolean)
        responseDone = true
      dut.io.s_axi_aclk.step()
      if (addressDone) dut.io.s_axi_awvalid.poke(false.B)
      if (dataDone) dut.io.s_axi_wvalid.poke(false.B)
      cycles += 1
    }
    assert(responseDone, f"AXI write to 0x$address%x timed out")
    dut.io.s_axi_awvalid.poke(false.B)
    dut.io.s_axi_wvalid.poke(false.B)
    dut.io.s_axi_wlast.poke(false.B)
    dut.io.s_axi_bready.poke(false.B)
  }

  protected def axiRead(dut: GpuHostSystemAxi, address: Int): BigInt = {
    dut.io.s_axi_araddr.poke(address.U)
    dut.io.s_axi_arlen.poke(0.U)
    dut.io.s_axi_arsize.poke(2.U)
    dut.io.s_axi_arburst.poke(0.U)
    dut.io.s_axi_arvalid.poke(true.B)
    dut.io.s_axi_rready.poke(true.B)

    var addressDone = false
    var responseDone = false
    var result = BigInt(0)
    var cycles = 0
    while (!responseDone && cycles < 64) {
      if (!addressDone && dut.io.s_axi_arready.peek().litToBoolean)
        addressDone = true
      if (dut.io.s_axi_rvalid.peek().litToBoolean) {
        result = dut.io.s_axi_rdata.peek().litValue
        responseDone = true
      }
      dut.io.s_axi_aclk.step()
      if (addressDone) dut.io.s_axi_arvalid.poke(false.B)
      cycles += 1
    }
    assert(responseDone, f"AXI read from 0x$address%x timed out")
    dut.io.s_axi_arvalid.poke(false.B)
    dut.io.s_axi_rready.poke(false.B)
    result
  }

  protected case class MemoryWrite(
    address: BigInt, id: BigInt, data: BigInt, mask: BigInt)

  protected def acceptMemoryWrite(dut: GpuHostSystemAxi): MemoryWrite = {
    dut.io.m_axi_awready.poke(true.B)
    dut.io.m_axi_wready.poke(true.B)
    val axiDataBytes = dut.io.m_axi_wstrb.getWidth
    val axiDataBits = axiDataBytes * 8
    var address = BigInt(0)
    var id = BigInt(0)
    var data = BigInt(0)
    var mask = BigInt(0)
    var addressDone = false
    var dataDone = false
    var beat = 0
    var cycles = 0
    while ((!addressDone || !dataDone) && cycles < 160) {
      if (!addressDone && dut.io.m_axi_awvalid.peek().litToBoolean) {
        address = dut.io.m_axi_awaddr.peek().litValue
        id = dut.io.m_axi_awid.peek().litValue
        dut.io.m_axi_awburst.expect(1.U)
        addressDone = true
      }
      if (!dataDone && dut.io.m_axi_wvalid.peek().litToBoolean) {
        data |= dut.io.m_axi_wdata.peek().litValue << (beat * axiDataBits)
        mask |= dut.io.m_axi_wstrb.peek().litValue << (beat * axiDataBytes)
        dataDone = dut.io.m_axi_wlast.peek().litToBoolean
        beat += 1
      }
      dut.io.s_axi_aclk.step()
      cycles += 1
    }
    assert(addressDone && dataDone, "AXI memory write timed out")
    dut.io.m_axi_awready.poke(false.B)
    dut.io.m_axi_wready.poke(false.B)
    dut.io.m_axi_bid.poke(id.U)
    dut.io.m_axi_bresp.poke(0.U)
    dut.io.m_axi_bvalid.poke(true.B)
    dut.io.m_axi_bready.expect(true.B)
    dut.io.s_axi_aclk.step()
    dut.io.m_axi_bvalid.poke(false.B)
    val byteOffset = address.toInt & (64 - axiDataBytes)
    MemoryWrite(address & ~BigInt(63), id,
      data << (byteOffset * 8), mask << byteOffset)
  }

  protected def acceptMemoryRead(
    dut: GpuHostSystemAxi,
    line: BigInt,
    respond: Boolean = true
  ): (BigInt, BigInt) = {
    val axiDataBytes = dut.io.m_axi_wstrb.getWidth
    val axiDataBits = axiDataBytes * 8
    dut.io.m_axi_arready.poke(true.B)
    var address = BigInt(0)
    var id = BigInt(0)
    var cycles = 0
    while (!dut.io.m_axi_arvalid.peek().litToBoolean && cycles < 160) {
      dut.io.s_axi_aclk.step()
      cycles += 1
    }
    assert(cycles < 160, "AXI memory read address timed out")
    address = dut.io.m_axi_araddr.peek().litValue
    id = dut.io.m_axi_arid.peek().litValue
    dut.io.m_axi_arlen.expect((64 / axiDataBytes - 1).U)
    dut.io.m_axi_arsize.expect(chisel3.util.log2Ceil(axiDataBytes).U)
    dut.io.m_axi_arburst.expect(1.U)
    dut.io.s_axi_aclk.step()
    dut.io.m_axi_arready.poke(false.B)
    if (respond) {
      for (beat <- 0 until 64 / axiDataBytes) {
        dut.io.m_axi_rid.poke(id.U)
        dut.io.m_axi_rdata.poke(((line >> (beat * axiDataBits)) &
          ((BigInt(1) << axiDataBits) - 1)).U)
        dut.io.m_axi_rresp.poke(0.U)
        dut.io.m_axi_rlast.poke((beat == 64 / axiDataBytes - 1).B)
        dut.io.m_axi_rvalid.poke(true.B)
        dut.io.m_axi_rready.expect(true.B)
        dut.io.s_axi_aclk.step()
      }
      dut.io.m_axi_rvalid.poke(false.B)
      dut.io.m_axi_rlast.poke(false.B)
    }
    (address, id)
  }

  /** Services the configured-width AXI memory master until `until` holds, accepting any
    * mix of full-line and narrow reads and full-line writes. `readLine` supplies the 64-byte line
    * for a read address; `onWrite` receives every completed write burst as
    * (address, data, byte strobe). `onReadAccepted` runs after the AR handshake
    * with memory handshakes paused, allowing control-MMIO traffic while the
    * burst remains outstanding. Use a positive `readResponseDelay` to invoke
    * it before any R beat has been presented. */
  protected def serviceMemoryMaster(
    dut: GpuHostSystemAxi,
    readLine: BigInt => BigInt,
    readFault: BigInt => Boolean = _ => false,
    writeAckDelay: Int = 0,
    maxCycles: Int = 20000,
    readResponseDelay: Int = 0,
    onReadAccepted: BigInt => Unit = _ => (),
    onCycle: () => Unit = () => (),
    noProgressLimit: Int = 0
  )(onWrite: (BigInt, BigInt, BigInt) => Unit)(until: => Boolean): Unit = {
    val axiDataBytes = dut.io.m_axi_wstrb.getWidth
    val axiDataBits = axiDataBytes * 8
    val beatMask = (BigInt(1) << axiDataBits) - 1
    case class PendingRead(address: BigInt, id: BigInt, line: BigInt,
      beats: Int, fault: Boolean, due: Int, var beat: Int = 0)
    val pendingReads = scala.collection.mutable.Queue.empty[PendingRead]
    var awDone = false
    var awAddr = BigInt(0)
    var awId = BigInt(0)
    var wBeat = 0
    var wData = BigInt(0)
    var wStrb = BigInt(0)
    var wDone = false
    val pendingWriteAcks = scala.collection.mutable.Queue.empty[(BigInt, Int)]
    var guard = 0
    var quietCycles = 0
    while (!until && guard < maxCycles) {
      var acceptedReadAddress: Option[BigInt] = None
      var memoryProgress = false
      // Accept independent read addresses while older R bursts are pending.
      // Every burst retains its original snapshot and fixed response latency.
      dut.io.m_axi_arready.poke((pendingReads.size < 16).B)
      if (dut.io.m_axi_arvalid.peek().litToBoolean &&
          dut.io.m_axi_arready.peek().litToBoolean) {
        val address = dut.io.m_axi_araddr.peek().litValue
        val id = dut.io.m_axi_arid.peek().litValue
        val line = readLine(address) >> ((address.toInt & (64 - axiDataBytes)) * 8)
        val beats = dut.io.m_axi_arlen.peek().litValue.toInt + 1
        pendingReads.enqueue(PendingRead(address, id, line, beats,
          readFault(address), guard + math.max(1, readResponseDelay)))
        acceptedReadAddress = Some(address)
        memoryProgress = true
      }
      // R is ordered here; dedicated bridge units also exercise interleaving
      // and out-of-order IDs. Raising BVALID may change this R's readiness.
      pendingReads.headOption.filter(_.due <= guard) match {
        case Some(r) =>
          dut.io.m_axi_rvalid.poke(true.B)
          dut.io.m_axi_rid.poke(r.id.U)
          dut.io.m_axi_rdata.poke(((r.line >> (r.beat * axiDataBits)) & beatMask).U)
          dut.io.m_axi_rresp.poke((if (r.fault) 2 else 0).U)
          dut.io.m_axi_rlast.poke((r.beat == r.beats - 1).B)
        case None =>
          dut.io.m_axi_rvalid.poke(false.B)
          dut.io.m_axi_rlast.poke(false.B)
      }
      // Write address channel.
      dut.io.m_axi_awready.poke((!awDone && !wDone).B)
      if (!awDone && dut.io.m_axi_awvalid.peek().litToBoolean &&
          dut.io.m_axi_awready.peek().litToBoolean) {
        memoryProgress = true
        awAddr = dut.io.m_axi_awaddr.peek().litValue
        awId = dut.io.m_axi_awid.peek().litValue
        awDone = true
      }
      // Write data channel.
      dut.io.m_axi_wready.poke((!wDone).B)
      if (!wDone && dut.io.m_axi_wvalid.peek().litToBoolean &&
          dut.io.m_axi_wready.peek().litToBoolean) {
        memoryProgress = true
        wData |= dut.io.m_axi_wdata.peek().litValue << (wBeat * axiDataBits)
        wStrb |= dut.io.m_axi_wstrb.peek().litValue << (wBeat * axiDataBytes)
        wBeat += 1
        if (dut.io.m_axi_wlast.peek().litToBoolean) wDone = true
      }
      if (awDone && wDone) {
        // Complete the burst's data independently of acknowledgement delay.
        // AXI permits another AW/W burst while earlier B replies are pending.
        val byteOffset = awAddr.toInt & (64 - axiDataBytes)
        onWrite(awAddr & ~BigInt(63), wData << (byteOffset * 8), wStrb << byteOffset)
        pendingWriteAcks.enqueue((awId, guard + math.max(1, writeAckDelay)))
        awDone = false
        wDone = false
        wBeat = 0
        wData = BigInt(0)
        wStrb = BigInt(0)
      }
      // B may not precede the edge accepting AW and WLAST. Once presented,
      // keep the head ID stable until the DUT accepts it.
      val bReadyToPresent = pendingWriteAcks.headOption.exists(_._2 <= guard)
      dut.io.m_axi_bvalid.poke(bReadyToPresent.B)
      dut.io.m_axi_bid.poke(pendingWriteAcks.headOption.map(_._1).getOrElse(BigInt(0)).U)
      dut.io.m_axi_bresp.poke(0.U)
      // Drive BOTH response channels before sampling either handshake.
      // RREADY/BREADY share the bridge's response arbiter: raising BVALID can
      // revoke RREADY in this cycle. Sampling R earlier would silently drop
      // a read beat that the DUT never accepted, causing a false deadlock.
      if (dut.io.m_axi_rvalid.peek().litToBoolean && dut.io.m_axi_rready.peek().litToBoolean) {
        memoryProgress = true
        val r = pendingReads.front
        if (r.beat == r.beats - 1) pendingReads.dequeue() else r.beat += 1
      }
      if (dut.io.m_axi_bvalid.peek().litToBoolean && dut.io.m_axi_bready.peek().litToBoolean) {
        memoryProgress = true
        pendingWriteAcks.dequeue()
      }
      quietCycles = if (memoryProgress) 0 else quietCycles + 1
      assert(noProgressLimit == 0 || quietCycles < noProgressLimit,
        s"AXI no progress for $quietCycles cycles at cycle=$guard pendingR=${pendingReads.size} awDone=$awDone wDone=$wDone pendingB=${pendingWriteAcks.size}")
      onCycle()
      dut.io.s_axi_aclk.step()
      acceptedReadAddress.foreach { address =>
        // Pause memory handshakes while the hook drives control MMIO. Local
        // burst state survives, including an accepted AR awaiting its R data.
        dut.io.m_axi_arready.poke(false.B)
        dut.io.m_axi_rvalid.poke(false.B)
        dut.io.m_axi_awready.poke(false.B)
        dut.io.m_axi_wready.poke(false.B)
        dut.io.m_axi_bvalid.poke(false.B)
        onReadAccepted(address)
      }
      guard += 1
    }
    assert(guard < maxCycles, "AXI memory master service timed out")
  }
}
