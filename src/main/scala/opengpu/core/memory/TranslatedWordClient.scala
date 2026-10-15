package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{BufferedCommandWordPort, BufferedTextureWordPort, CoalescedOmWordToLinePort, OmMemoryRequest, OmMemoryResponse, OmWordToLinePort}

/** A graphics word-port client with its own Sv32 translation TLB.
  *
  * Wraps the word-to-line bridge, a `GraphicsAddressTranslator` and the
  * fault/translated response arbiter that the command, framebuffer and texture
  * clients otherwise repeat. The translated line request and its page-walk
  * request are exposed for the caller's shared request arbiter, and the
  * external responses are routed back by transaction-ID range; the client
  * reports a fault when a translated or fault response is consumed.
  */
class TranslatedWordClient(
  config: GpuConfig = GpuConfig(),
  wordTransactions: Int = 4,
  outstanding: Int = 32,
  uncached: Boolean = false,
  preserveCachePolicy: Boolean = false,
  coalesceWrites: Boolean = false,
  bufferCommandReads: Boolean = false,
  bufferTextureReads: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new OmMemoryRequest))
    val out = Decoupled(new OmMemoryResponse)
    /** Translated line request to the shared arbiter. */
    val memReq = Decoupled(new ComputeMemoryRequest(config, 64, outstanding))
    /** The client's translated response, routed back by ID range. */
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse(64, outstanding)))
    /** PTE read request/response, kept on a reserved transaction range. */
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, outstanding))
    val pageWalkResp = Flipped(Decoupled(
      new ComputeMemoryResponse(64, outstanding)))
    val invalidateCommandReads = if (bufferCommandReads) Some(Input(Bool())) else None
    val invalidateTextureReads = if (bufferTextureReads) Some(Input(Bool())) else None
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    /** A translated or fault response for this client was consumed. */
    val fault = Output(Bool())
    val translationStall = Output(Bool())
    val translationMiss = Output(Bool())
    val walkActive = Output(Bool())
  })

  require(!bufferCommandReads || !coalesceWrites,
    "command read buffering and framebuffer combining are distinct clients")
  require(!bufferCommandReads || (uncached && preserveCachePolicy),
    "command read buffering requires uncached lines and preserved cache policy")
  require(!bufferTextureReads || (!bufferCommandReads && !coalesceWrites),
    "texture read buffering is distinct from command and framebuffer clients")
  private val previousSatp = if (bufferCommandReads || bufferTextureReads)
    Some(RegNext(io.satp, 0.U)) else None
  private val bridge = if (bufferCommandReads) {
    val buffered = Module(new BufferedCommandWordPort(config, wordTransactions))
    buffered.io.invalidate := io.invalidateCommandReads.get || io.flush.valid ||
      io.satp =/= previousSatp.get
    buffered.io.port
  } else if (bufferTextureReads) {
    val buffered = Module(new BufferedTextureWordPort(config, wordTransactions,
      cacheLines = math.min(16, 1 << log2Floor(wordTransactions)), uncached = uncached))
    buffered.io.invalidate := io.invalidateTextureReads.get || io.flush.valid ||
      io.satp =/= previousSatp.get
    buffered.io.port
  } else if (coalesceWrites)
    Module(new CoalescedOmWordToLinePort(config, wordTransactions, uncached,
      groups = if (wordTransactions >= 32) 8 else 4)).io
  else Module(new OmWordToLinePort(config, 64, wordTransactions, uncached)).io
  bridge.in.valid := io.in.valid
  bridge.in.bits := io.in.bits
  io.in.ready := bridge.in.ready
  io.out.valid := bridge.out.valid
  io.out.bits := bridge.out.bits
  bridge.out.ready := io.out.ready

  private val translator = Module(new GraphicsAddressTranslator(
    config, entries = 16, lineBytes = 64, maxOutstanding = outstanding,
    preserveCachePolicy = preserveCachePolicy))
  translator.io.satp := io.satp
  translator.io.flush := io.flush
  translator.io.pageWalkTransactionId := 0.U
  translator.io.in <> bridge.memoryRequest
  io.translationStall := translator.io.in.valid && !translator.io.in.ready
  io.translationMiss := translator.io.miss
  io.walkActive := translator.io.walkActive

  io.memReq.valid := translator.io.out.valid
  io.memReq.bits := translator.io.out.bits
  translator.io.out.ready := io.memReq.ready
  io.pageWalk.valid := translator.io.pageWalk.valid
  io.pageWalk.bits := translator.io.pageWalk.bits
  translator.io.pageWalk.ready := io.pageWalk.ready
  translator.io.pageWalkResp.valid := io.pageWalkResp.valid
  translator.io.pageWalkResp.bits := io.pageWalkResp.bits
  io.pageWalkResp.ready := translator.io.pageWalkResp.ready

  private val responseArb = Module(new RRArbiter(
    new ComputeMemoryResponse(64, outstanding), 2))
  responseArb.io.in(0) <> translator.io.faultResponse
  responseArb.io.in(1).valid := io.memResp.valid
  responseArb.io.in(1).bits := io.memResp.bits
  io.memResp.ready := responseArb.io.in(1).ready
  bridge.memoryResponse <> responseArb.io.out
  io.fault := responseArb.io.out.fire && responseArb.io.out.bits.fault
}
