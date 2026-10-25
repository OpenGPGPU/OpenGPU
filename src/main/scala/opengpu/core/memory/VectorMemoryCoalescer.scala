package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}

class VectorCacheLineRequest(
  config: GpuConfig,
  val lineBytes: Int = 64
) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val lineAddress = UInt(config.xLen.W)
  val writeData = UInt((lineBytes * 8).W)
  val byteMask = UInt(lineBytes.W)
  val isStore = Bool()
  /** Per-page cache policy; the producer sends the default and the TLB
    * replaces it with the translated page's policy. */
  val cachePolicy = UInt(CachePolicy.width.W)
}

class VectorCacheLineResponse(val lineBytes: Int = 64) extends Bundle {
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
  val pageFault = Bool()
}

/** Coalesces one vector transaction into the unique cache lines it touches.
  *
  * Unit-stride lanes normally collapse into one or two requests, while strided
  * lanes may occupy independent lines. Requests are issued one at a time and
  * load bytes are collected as lines respond and published only after every
  * required line has responded. Full response lines need not be retained.
  *
  * Accept is split across two cycles: capture the transaction into `pending`,
  * then compute the unique line set from that registered payload. That keeps
  * the elementSize/address dedup cone off the upstream
  * `vectorMemoryRouter` global-request path (integrated-top limiter on
  * `gpu-system-smulcs`).
  */
class VectorMemoryCoalescer(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64
) extends Module {
  require(isPow2(lineBytes), "cache line size must be a power of two")
  require(lineBytes >= config.xLen / 8, "one element must span at most two lines")

  private val lineOffsetWidth = log2Ceil(lineBytes)
  private val maxLines = config.lanes * 2
  private val requestIndexWidth = math.max(1, log2Ceil(maxLines))
  private val lineCountWidth = math.max(1, log2Ceil(maxLines + 1))
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorMemoryTransaction(config)))
    val cacheRequest =
      Decoupled(new VectorCacheLineRequest(config, lineBytes))
    val cacheResponse =
      Flipped(Decoupled(new VectorCacheLineResponse(lineBytes)))
    val out = Decoupled(new VectorMemoryResponse(config))
  })

  private val pendingValid = RegInit(false.B)
  private val pending = Reg(new VectorMemoryTransaction(config))
  private val transactionValid = RegInit(false.B)
  private val transaction = Reg(new VectorMemoryTransaction(config))
  private val requestIndex = RegInit(0.U(requestIndexWidth.W))
  private val lineCount = Reg(UInt(lineCountWidth.W))
  private val lineAddresses = Reg(Vec(maxLines, UInt(config.xLen.W)))
  private val waitingResponse = RegInit(false.B)
  private val collectedBytes = Reg(Vec(config.lanes, Vec(4, UInt(8.W))))
  private val collectedFaults = Reg(UInt(config.lanes.W))
  private val collectedPageFault = Reg(Bool())
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorMemoryResponse(config))

  private val elementBytes = 1.U << transaction.elementSize
  private val selectedLineAddress = lineAddresses(requestIndex)

  private def alignLine(address: UInt): UInt =
    Cat(
      address(config.xLen - 1, lineOffsetWidth),
      0.U(lineOffsetWidth.W)
    )

  // Line uniqueness is computed from the registered pending payload, not from
  // the live input, so the critical path cannot span the router.
  private val pendingElementBytes = 1.U << pending.elementSize
  private val candidateValid = Wire(Vec(maxLines, Bool()))
  private val candidateAddress = Wire(Vec(maxLines, UInt(config.xLen.W)))
  for (lane <- 0 until config.lanes) {
    val first = alignLine(pending.addresses(lane))
    val last = alignLine(
      pending.addresses(lane) + pendingElementBytes - 1.U
    )
    candidateValid(lane * 2) := pending.laneMask(lane)
    candidateAddress(lane * 2) := first
    candidateValid(lane * 2 + 1) :=
      pending.laneMask(lane) && last =/= first
    candidateAddress(lane * 2 + 1) := last
  }

  private val uniqueCandidate = Wire(Vec(maxLines, Bool()))
  private val inputLineAddresses = Wire(Vec(maxLines, UInt(config.xLen.W)))
  inputLineAddresses.foreach(_ := 0.U)
  for (candidate <- 0 until maxLines) {
    val duplicate = if (candidate == 0) {
      false.B
    } else {
      VecInit((0 until candidate).map { previous =>
        candidateValid(previous) &&
          candidateAddress(previous) === candidateAddress(candidate)
      }).asUInt.orR
    }
    uniqueCandidate(candidate) := candidateValid(candidate) && !duplicate
    val compactIndexRaw = if (candidate == 0) {
      0.U
    } else {
      PopCount(VecInit((0 until candidate).map(uniqueCandidate(_))))
    }
    val compactIndex = Wire(UInt(requestIndexWidth.W))
    compactIndex := compactIndexRaw
    when(uniqueCandidate(candidate)) {
      inputLineAddresses(compactIndex) := candidateAddress(candidate)
    }
  }
  private val inputLineCount = PopCount(uniqueCandidate)

  private val lineDataBytes = Wire(Vec(lineBytes, UInt(8.W)))
  private val lineByteMask = Wire(Vec(lineBytes, Bool()))
  lineDataBytes.foreach(_ := 0.U)
  lineByteMask.foreach(_ := false.B)
  for {
    lane <- 0 until config.lanes
    byte <- 0 until 4
  } {
    val byteEnabled =
      transaction.laneMask(lane) && byte.U < elementBytes
    val byteAddress = transaction.addresses(lane) + byte.U
    val belongsToSelectedLine =
      byteAddress(config.xLen - 1, lineOffsetWidth) ===
        selectedLineAddress(config.xLen - 1, lineOffsetWidth)
    val offset = byteAddress(lineOffsetWidth - 1, 0)
    when(byteEnabled && belongsToSelectedLine) {
      lineDataBytes(offset) :=
        transaction.writeData(lane)(byte * 8 + 7, byte * 8)
      lineByteMask(offset) := true.B
    }
  }

  io.in.ready := !pendingValid && !transactionValid && !outputValid
  when(io.in.fire) {
    when(io.in.bits.laneMask === 0.U) {
      outputValid := true.B
      outputBits.readData := 0.U.asTypeOf(outputBits.readData)
      outputBits.faultMask := 0.U
      outputBits.pageFault := false.B
    }.otherwise {
      pendingValid := true.B
      pending := io.in.bits
    }
  }
  when(pendingValid && !transactionValid) {
    pendingValid := false.B
    transactionValid := true.B
    transaction := pending
    requestIndex := 0.U
    lineCount := inputLineCount
    waitingResponse := false.B
    collectedBytes := 0.U.asTypeOf(collectedBytes)
    collectedFaults := 0.U
    collectedPageFault := false.B
    for (line <- 0 until maxLines) {
      lineAddresses(line) := inputLineAddresses(line)
    }
  }

  io.cacheRequest.valid := transactionValid && !waitingResponse
  io.cacheRequest.bits.warpId := transaction.warpId
  io.cacheRequest.bits.lineAddress := selectedLineAddress
  io.cacheRequest.bits.writeData := lineDataBytes.asUInt
  io.cacheRequest.bits.byteMask := lineByteMask.asUInt
  io.cacheRequest.bits.isStore := transaction.isStore
  io.cacheRequest.bits.cachePolicy := CachePolicy.cached
  when(io.cacheRequest.fire) {
    waitingResponse := true.B
  }

  io.cacheResponse.ready :=
    transactionValid && waitingResponse && !outputValid
  // The unique line set and response order are unchanged. Each output byte
  // belongs to at most one returned line, so collect it once at that response.
  // Include all four bytes even for narrow or inactive lanes: the original
  // interface returned those bytes whenever another lane requested their line.
  private val responseBytes = io.cacheResponse.bits.readData.asTypeOf(Vec(lineBytes, UInt(8.W)))
  private val nextBytes = Wire(Vec(config.lanes, Vec(4, UInt(8.W))))
  private val nextFaults = Wire(Vec(config.lanes, Bool()))
  for (lane <- 0 until config.lanes) {
    for (byte <- 0 until 4) {
      val address = transaction.addresses(lane) + byte.U
      nextBytes(lane)(byte) := Mux(alignLine(address) === selectedLineAddress,
        responseBytes(address(lineOffsetWidth - 1, 0)), collectedBytes(lane)(byte))
    }
    val first = alignLine(transaction.addresses(lane))
    val last = alignLine(transaction.addresses(lane) + elementBytes - 1.U)
    nextFaults(lane) := collectedFaults(lane) || (transaction.laneMask(lane) &&
      (first === selectedLineAddress || last === selectedLineAddress) && io.cacheResponse.bits.fault)
  }
  private val nextPageFault = collectedPageFault ||
    (io.cacheResponse.bits.fault && io.cacheResponse.bits.pageFault)
  when(io.cacheResponse.fire) {
    waitingResponse := false.B
    collectedBytes := nextBytes
    collectedFaults := nextFaults.asUInt
    collectedPageFault := nextPageFault

    when(requestIndex =/= lineCount - 1.U) {
      requestIndex := requestIndex + 1.U
    }.otherwise {
      transactionValid := false.B
      outputValid := true.B
      for (lane <- 0 until config.lanes) {
        // nextBytes includes this final response without an extra output cycle.
        outputBits.readData(lane) := nextBytes(lane).asUInt
      }
      outputBits.faultMask := nextFaults.asUInt
      outputBits.pageFault := nextPageFault
    }
  }

  io.out.valid := outputValid
  io.out.bits := outputBits
  when(io.out.fire) {
    outputValid := false.B
  }
}
