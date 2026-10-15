package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Converts the cache hierarchy's private lower-memory protocol into an AXI4
  * master port. The private protocol never crosses the product boundary.
  *
  * Independent read and write engines overlap transactions in both directions. A
  * 64-byte cache line is emitted as an INCR burst when the AXI data bus is
  * narrower than a cache line; four-byte page-table accesses use AXI narrow
  * transfers and are shifted to/from the addressed byte lane.
  * Up to four tagged read bursts overlap, with per-ID beat/fault reassembly.
  * Write bursts continue while earlier B replies are outstanding; IDs and
  * request credits remain reserved through acknowledged response retirement.
  */
class ComputeMemoryAxiMaster(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64,
  maxOutstanding: Int = 4,
  axiDataBytes: Int = 8
) extends Module {
  require(lineBytes == 64, "current cache hierarchy uses 64-byte lines")
  require(maxOutstanding > 0)
  require(axiDataBytes >= 4 && axiDataBytes <= lineBytes)
  require(isPow2(axiDataBytes), "AXI data width must be a power of two")
  require(lineBytes % axiDataBytes == 0)

  private val axiDataWidth = axiDataBytes * 8
  private val idWidth = math.max(1, log2Ceil(maxOutstanding))
  private val beatIndexWidth = math.max(1, log2Ceil(lineBytes / axiDataBytes))
  private val laneOffsetWidth = log2Ceil(axiDataBytes)

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
    val response = Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding))

    val m_axi_awid = Output(UInt(idWidth.W))
    val m_axi_awaddr = Output(UInt(config.xLen.W))
    val m_axi_awlen = Output(UInt(8.W))
    val m_axi_awsize = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awlock = Output(Bool())
    val m_axi_awcache = Output(UInt(4.W))
    val m_axi_awprot = Output(UInt(3.W))
    val m_axi_awqos = Output(UInt(4.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())

    val m_axi_wdata = Output(UInt(axiDataWidth.W))
    val m_axi_wstrb = Output(UInt(axiDataBytes.W))
    val m_axi_wlast = Output(Bool())
    val m_axi_wvalid = Output(Bool())
    val m_axi_wready = Input(Bool())

    val m_axi_bid = Input(UInt(idWidth.W))
    val m_axi_bresp = Input(UInt(2.W))
    val m_axi_bvalid = Input(Bool())
    val m_axi_bready = Output(Bool())

    val m_axi_arid = Output(UInt(idWidth.W))
    val m_axi_araddr = Output(UInt(config.xLen.W))
    val m_axi_arlen = Output(UInt(8.W))
    val m_axi_arsize = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arlock = Output(Bool())
    val m_axi_arcache = Output(UInt(4.W))
    val m_axi_arprot = Output(UInt(3.W))
    val m_axi_arqos = Output(UInt(4.W))
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())

    val m_axi_rid = Input(UInt(idWidth.W))
    val m_axi_rdata = Input(UInt(axiDataWidth.W))
    val m_axi_rresp = Input(UInt(2.W))
    val m_axi_rlast = Input(Bool())
    val m_axi_rvalid = Input(Bool())
    val m_axi_rready = Output(Bool())
  })

  private val readRequest = Reg(new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
  private val writeRequest = Reg(new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
  private val readActive = RegInit(false.B)
  private val writeActive = RegInit(false.B)
  private val writeAddressSent = RegInit(false.B)
  private val writeDataSent = RegInit(false.B)
  private val writeBeat = RegInit(0.U(beatIndexWidth.W))
  // Bound reassembly storage independently of the large global ID space.
  // AR issue can continue while up to four tagged bursts await R data.
  private val readSlots = math.min(maxOutstanding, 4)
  private val readSlotWidth = math.max(1, log2Ceil(readSlots))
  private val readAddressSlot = Reg(UInt(readSlotWidth.W))
  private val readValid = RegInit(VecInit(Seq.fill(readSlots)(false.B)))
  private val readIssued = RegInit(VecInit(Seq.fill(readSlots)(false.B)))
  private val readIds = Reg(Vec(readSlots, UInt(idWidth.W)))
  private val readOffsets = Reg(Vec(readSlots, UInt(laneOffsetWidth.W)))
  private val readBursts = Reg(Vec(readSlots, Bool()))
  private val readBeats = Reg(Vec(readSlots, UInt(beatIndexWidth.W)))
  private val readResults = Reg(Vec(readSlots, UInt((lineBytes * 8).W)))
  private val readFaults = Reg(Vec(readSlots, Bool()))
  private val freeReadSlots = ~readValid.asUInt
  private val allocateReadSlot = PriorityEncoder(freeReadSlots)
  // AW/W issue is independent of B retirement. Preserve an ID until its
  // physical acknowledgement is queued, then keep the global request credit
  // until the upper consumer accepts that reply.
  private val pendingWrites = RegInit(0.U(maxOutstanding.W))
  private val completedWrites = RegInit(0.U(maxOutstanding.W))
  private def writeBit(bitmap: UInt, id: UInt): Bool =
    if (maxOutstanding == 1) bitmap(0) else bitmap(id)
  // Include queued replies in the credit count. Backpressured consumers cannot
  // cause the bridge to accept more requests than its advertised capacity.
  private val outstanding = RegInit(0.U(log2Ceil(maxOutstanding + 1).W))
  // A pending second store must not hold the shared L2 arbiter in front of a
  // read while the first store waits for B. Each direction gets its own queue.
  private val readRequests = Module(new Queue(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding), 2, flow = true))
  private val writeRequests = Module(new Queue(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding), 2, flow = true))
  readRequests.io.enq.valid := io.request.valid && !io.request.bits.isWrite && outstanding < maxOutstanding.U
  writeRequests.io.enq.valid := io.request.valid && io.request.bits.isWrite && outstanding < maxOutstanding.U
  readRequests.io.enq.bits := io.request.bits
  writeRequests.io.enq.bits := io.request.bits
  private val responseArb = Module(new RRArbiter(
    new ComputeMemoryResponse(lineBytes, maxOutstanding), 2))
  private val responses = Module(new Queue(
    new ComputeMemoryResponse(lineBytes, maxOutstanding), 2, pipe = true))
  responses.io.enq <> responseArb.io.out
  io.response <> responses.io.deq

  private def lineTransfer(request: ComputeMemoryRequest): Bool =
    request.sizeLog2 > log2Ceil(axiDataBytes).U
  private def transferBeats(request: ComputeMemoryRequest): UInt =
    Mux(lineTransfer(request), (lineBytes / axiDataBytes).U(8.W), 1.U(8.W))
  private def laneOffset(request: ComputeMemoryRequest): UInt =
    request.address(laneOffsetWidth - 1, 0)
  private val writeLast = writeBeat === transferBeats(writeRequest) - 1.U
  private val writeShift = writeBeat << log2Ceil(axiDataWidth)

  io.request.ready := outstanding < maxOutstanding.U &&
    Mux(io.request.bits.isWrite, writeRequests.io.enq.ready, readRequests.io.enq.ready)
  when(io.request.fire =/= io.response.fire) {
    outstanding := Mux(io.request.fire, outstanding + 1.U, outstanding - 1.U)
  }
  when(io.request.fire) {
    assert(io.request.bits.transactionId < maxOutstanding.U,
      "lower-memory transaction ID must be in the advertised range")
    assert(io.request.bits.sizeLog2 === 2.U || io.request.bits.sizeLog2 === 6.U,
      "lower memory supports four-byte words and full cache lines")
    when(io.request.bits.sizeLog2 === 6.U) {
      assert(io.request.bits.address(5, 0) === 0.U,
        "cache-line AXI bursts must be naturally aligned")
    }
  }
  when(readRequests.io.deq.fire) {
    assert(!(0 until readSlots).map(i => readValid(i) &&
      readIds(i) === readRequests.io.deq.bits.transactionId).reduce(_ || _),
      "AXI read ID cannot be reused before its burst completes")
    readRequest := readRequests.io.deq.bits
    readActive := true.B
    readAddressSlot := allocateReadSlot
  }

  io.m_axi_awid := writeRequest.transactionId
  io.m_axi_awaddr := writeRequest.address
  io.m_axi_awlen := transferBeats(writeRequest) - 1.U
  io.m_axi_awsize := Mux(lineTransfer(writeRequest), log2Ceil(axiDataBytes).U, writeRequest.sizeLog2)
  io.m_axi_awburst := 1.U
  io.m_axi_awlock := false.B
  io.m_axi_awcache := "b0011".U
  io.m_axi_awprot := 0.U
  io.m_axi_awqos := 0.U
  io.m_axi_awvalid := writeActive && !writeAddressSent
  io.m_axi_wdata := Mux(lineTransfer(writeRequest),
    (writeRequest.writeData >> writeShift)(axiDataWidth - 1, 0),
    (writeRequest.writeData(axiDataWidth - 1, 0) << (laneOffset(writeRequest) << 3))(axiDataWidth - 1, 0))
  io.m_axi_wstrb := Mux(lineTransfer(writeRequest),
    (writeRequest.byteMask >> (writeBeat << log2Ceil(axiDataBytes)))(axiDataBytes - 1, 0),
    (writeRequest.byteMask(axiDataBytes - 1, 0) << laneOffset(writeRequest))(axiDataBytes - 1, 0))
  io.m_axi_wlast := writeLast
  io.m_axi_wvalid := writeActive && !writeDataSent
  when(io.m_axi_awvalid && io.m_axi_awready) { writeAddressSent := true.B }
  when(io.m_axi_wvalid && io.m_axi_wready) {
    when(writeLast) { writeDataSent := true.B }
      .otherwise { writeBeat := writeBeat + 1.U }
  }
  private val writeFinished = writeActive &&
    (writeAddressSent || (io.m_axi_awvalid && io.m_axi_awready)) &&
    (writeDataSent || (io.m_axi_wvalid && io.m_axi_wready && writeLast))
  writeRequests.io.deq.ready := !writeActive || writeFinished
  when(writeFinished) { writeActive := false.B }
  // Loading the next queued burst wins over the old burst's final handshakes.
  // With one-beat lines, AW/W can therefore issue on consecutive cycles.
  when(writeRequests.io.deq.fire) {
    assert(!writeBit(pendingWrites, writeRequests.io.deq.bits.transactionId),
      "AXI write ID cannot be reused before its acknowledgement")
    writeRequest := writeRequests.io.deq.bits
    writeActive := true.B
    writeAddressSent := false.B
    writeDataSent := false.B
    writeBeat := 0.U
  }
  private val bidInRange = io.m_axi_bid < maxOutstanding.U
  private val writeResponseExpected = bidInRange && writeBit(pendingWrites, io.m_axi_bid) &&
    writeBit(completedWrites, io.m_axi_bid)
  responseArb.io.in(1).valid := writeResponseExpected && io.m_axi_bvalid
  responseArb.io.in(1).bits.transactionId := io.m_axi_bid
  responseArb.io.in(1).bits.readData := 0.U
  responseArb.io.in(1).bits.fault := io.m_axi_bresp(1)
  io.m_axi_bready := writeResponseExpected && responseArb.io.in(1).ready
  when(io.m_axi_bvalid && io.m_axi_bready) {
    assert(writeResponseExpected,
      "AXI write response ID must identify an issued and completed burst")
  }
  when(io.m_axi_bvalid) {
    assert(writeResponseExpected,
      "AXI write acknowledgement must follow both address and final data handshakes")
  }
  private val allocatedWrite = Mux(writeRequests.io.deq.fire,
    UIntToOH(writeRequests.io.deq.bits.transactionId, maxOutstanding), 0.U(maxOutstanding.W))
  private val finishedWrite = Mux(writeFinished,
    UIntToOH(writeRequest.transactionId, maxOutstanding), 0.U(maxOutstanding.W))
  private val acknowledgedWrite = Mux(io.m_axi_bvalid && io.m_axi_bready,
    UIntToOH(io.m_axi_bid, maxOutstanding), 0.U(maxOutstanding.W))
  pendingWrites := (pendingWrites & ~acknowledgedWrite) | allocatedWrite
  completedWrites := (completedWrites & ~acknowledgedWrite) | finishedWrite

  io.m_axi_arid := readRequest.transactionId
  io.m_axi_araddr := readRequest.address
  io.m_axi_arlen := transferBeats(readRequest) - 1.U
  io.m_axi_arsize := Mux(lineTransfer(readRequest), log2Ceil(axiDataBytes).U, readRequest.sizeLog2)
  io.m_axi_arburst := 1.U
  io.m_axi_arlock := false.B
  io.m_axi_arcache := "b0011".U
  io.m_axi_arprot := 0.U
  io.m_axi_arqos := 0.U
  io.m_axi_arvalid := readActive
  private val readAddressFire = io.m_axi_arvalid && io.m_axi_arready
  readRequests.io.deq.ready := (!readActive || readAddressFire) && freeReadSlots.orR
  when(readAddressFire) { readActive := false.B }
  // A next address may replace the accepted one on this edge.
  when(readRequests.io.deq.fire) { readActive := true.B }

  private val readTagMatches = VecInit((0 until readSlots).map(i =>
    readValid(i) && readIds(i) === io.m_axi_rid))
  private val readMatches = VecInit((0 until readSlots).map(i =>
    readTagMatches(i) && readIssued(i)))
  private val readResponseExpected = readMatches.asUInt.orR
  private val responseBeat = Mux1H(readMatches, readBeats)
  private val responseBurst = Mux1H(readMatches, readBursts)
  private val responseOffset = Mux1H(readMatches, readOffsets)
  private val responseResult = Mux1H(readMatches, readResults)
  private val responseFault = Mux1H(readMatches, readFaults)
  private val readLast = responseBeat === Mux(responseBurst,
    (lineBytes / axiDataBytes - 1).U, 0.U)
  private val readShift = responseBeat << log2Ceil(axiDataWidth)
  private val nextReadResult = Mux(responseBurst,
    responseResult | (io.m_axi_rdata << readShift),
    io.m_axi_rdata >> (responseOffset << 3))
  responseArb.io.in(0).valid := readResponseExpected && io.m_axi_rvalid && readLast
  responseArb.io.in(0).bits.transactionId := io.m_axi_rid
  responseArb.io.in(0).bits.readData := nextReadResult
  responseArb.io.in(0).bits.fault := responseFault || io.m_axi_rresp(1) || (io.m_axi_rlast =/= readLast)
  io.m_axi_rready := readResponseExpected && (!readLast || responseArb.io.in(0).ready)
  when(io.m_axi_rvalid) {
    assert(PopCount(readTagMatches) === 1.U,
      "AXI read response ID must identify one reserved read context")
  }
  when(io.m_axi_rvalid && io.m_axi_rready) {
    assert(PopCount(readMatches) === 1.U,
      "AXI read response must follow its address handshake")
    assert(io.m_axi_rlast === readLast,
      "AXI RLAST must match the programmed burst length")
  }
  for (i <- 0 until readSlots) {
    when(readRequests.io.deq.fire && allocateReadSlot === i.U) {
      readValid(i) := true.B
      readIssued(i) := false.B
      readIds(i) := readRequests.io.deq.bits.transactionId
      readOffsets(i) := laneOffset(readRequests.io.deq.bits)
      readBursts(i) := lineTransfer(readRequests.io.deq.bits)
      readBeats(i) := 0.U
      readResults(i) := 0.U
      readFaults(i) := false.B
    }
    when(readAddressFire && readAddressSlot === i.U) { readIssued(i) := true.B }
    when(io.m_axi_rvalid && io.m_axi_rready && readMatches(i)) {
      readResults(i) := nextReadResult
      readFaults(i) := responseFault || io.m_axi_rresp(1)
      when(readLast) { readValid(i) := false.B; readIssued(i) := false.B }
        .otherwise { readBeats(i) := responseBeat + 1.U }
    }
  }
}
