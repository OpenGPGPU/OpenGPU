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
  private val readAddressSent = RegInit(false.B)
  private val writeAddressSent = RegInit(false.B)
  private val writeDataSent = RegInit(false.B)
  private val readBeat = RegInit(0.U(beatIndexWidth.W))
  private val writeBeat = RegInit(0.U(beatIndexWidth.W))
  private val readResult = RegInit(0.U((lineBytes * 8).W))
  private val readFault = RegInit(false.B)
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
  readRequests.io.deq.ready := !readActive
  writeRequests.io.deq.ready := !writeActive
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
  private val readLast = readBeat === transferBeats(readRequest) - 1.U
  private val writeLast = writeBeat === transferBeats(writeRequest) - 1.U
  private val readShift = readBeat << log2Ceil(axiDataWidth)
  private val writeShift = writeBeat << log2Ceil(axiDataWidth)
  private val nextReadResult = Mux(lineTransfer(readRequest),
    readResult | (io.m_axi_rdata << readShift),
    io.m_axi_rdata >> (laneOffset(readRequest) << 3))

  io.request.ready := outstanding < maxOutstanding.U &&
    Mux(io.request.bits.isWrite, writeRequests.io.enq.ready, readRequests.io.enq.ready)
  when(io.request.fire =/= io.response.fire) {
    outstanding := Mux(io.request.fire, outstanding + 1.U, outstanding - 1.U)
  }
  when(io.request.fire) {
    assert(io.request.bits.sizeLog2 === 2.U || io.request.bits.sizeLog2 === 6.U,
      "lower memory supports four-byte words and full cache lines")
    when(io.request.bits.sizeLog2 === 6.U) {
      assert(io.request.bits.address(5, 0) === 0.U,
        "cache-line AXI bursts must be naturally aligned")
    }
  }
  when(writeRequests.io.deq.fire) {
    writeRequest := writeRequests.io.deq.bits
    writeActive := true.B
    writeAddressSent := false.B
    writeDataSent := false.B
    writeBeat := 0.U
  }
  when(readRequests.io.deq.fire) {
    readRequest := readRequests.io.deq.bits
    readActive := true.B
    readAddressSent := false.B
    readBeat := 0.U
    readResult := 0.U
    readFault := false.B
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
  private val writeResponseExpected = writeActive && writeAddressSent && writeDataSent
  responseArb.io.in(1).valid := writeResponseExpected && io.m_axi_bvalid
  responseArb.io.in(1).bits.transactionId := writeRequest.transactionId
  responseArb.io.in(1).bits.readData := 0.U
  responseArb.io.in(1).bits.fault := io.m_axi_bresp(1)
  io.m_axi_bready := writeResponseExpected && responseArb.io.in(1).ready
  when(io.m_axi_bvalid && io.m_axi_bready) {
    assert(io.m_axi_bid === writeRequest.transactionId,
      "AXI write response ID must match the active lower-memory request")
    writeActive := false.B
  }

  io.m_axi_arid := readRequest.transactionId
  io.m_axi_araddr := readRequest.address
  io.m_axi_arlen := transferBeats(readRequest) - 1.U
  io.m_axi_arsize := Mux(lineTransfer(readRequest), log2Ceil(axiDataBytes).U, readRequest.sizeLog2)
  io.m_axi_arburst := 1.U
  io.m_axi_arlock := false.B
  io.m_axi_arcache := "b0011".U
  io.m_axi_arprot := 0.U
  io.m_axi_arqos := 0.U
  io.m_axi_arvalid := readActive && !readAddressSent
  when(io.m_axi_arvalid && io.m_axi_arready) { readAddressSent := true.B }
  private val readResponseExpected = readActive && readAddressSent
  responseArb.io.in(0).valid := readResponseExpected && io.m_axi_rvalid && readLast
  responseArb.io.in(0).bits.transactionId := readRequest.transactionId
  responseArb.io.in(0).bits.readData := nextReadResult
  responseArb.io.in(0).bits.fault := readFault || io.m_axi_rresp(1) || (io.m_axi_rlast =/= readLast)
  io.m_axi_rready := readResponseExpected && (!readLast || responseArb.io.in(0).ready)
  when(io.m_axi_rvalid && io.m_axi_rready) {
    assert(io.m_axi_rid === readRequest.transactionId,
      "AXI read response ID must match the active lower-memory request")
    assert(io.m_axi_rlast === readLast,
      "AXI RLAST must match the programmed burst length")
    readResult := nextReadResult
    readFault := readFault || io.m_axi_rresp(1)
    when(readLast) { readActive := false.B }
      .otherwise { readBeat := readBeat + 1.U }
  }
}
