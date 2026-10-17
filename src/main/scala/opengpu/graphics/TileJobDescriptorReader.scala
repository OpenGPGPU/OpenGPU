package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Explicit little-endian memory ABI. Never derive this layout from Bundle packing. */
object TileJobMemoryLayout {
  val magic = BigInt("47545001", 16)
  val words = 64
  val bytes = words * 4
  val lines = bytes / 64
}

class TileJobDescriptorRequest(commandIdWidth: Int) extends Bundle {
  val commandId = UInt(commandIdWidth.W)
  val base = UInt(32.W)
  val limit = UInt(33.W)
  val envelopeValid = Bool()
}

class TileHostCompletion(commandIdWidth: Int) extends Bundle {
  val commandId = UInt(commandIdWidth.W)
  val pass = new TileRenderPassCompletion
  /** Accepted draw-record read bytes, excluding descriptor lines. */
  val commandBytesFetched = UInt(64.W)
}

/** Own one bounded descriptor, immutable job and tagged result through drain.
  * Descriptors use four 64-byte uncached reads, not sixty-four word reads.
  * Cancellation drains every offered/accepted request before publishing a
  * result, and preserves an offered job until the core accepts/cancels it.
  */
class TileJobDescriptorReader(
  config: GpuConfig = GpuConfig(),
  graphics: GraphicsConfig = GraphicsConfig(),
  commandIdWidth: Int = 8,
  memoryTransactions: Int = 8,
  transactionId: Int = 0
) extends Module {
  require(transactionId >= 0 && transactionId < memoryTransactions)
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TileJobDescriptorRequest(commandIdWidth)))
    val job = Decoupled(new TileCommandJob)
    val passCompletion = Flipped(Decoupled(new TileRenderPassCompletion))
    val completion = Decoupled(new TileHostCompletion(commandIdWidth))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, memoryTransactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, memoryTransactions)))
    val cancel = Input(Bool())
    val cancelCore = Output(Bool())
    val commandReadAccepted = Input(Bool())
    val busy = Output(Bool())
    val fetching = Output(Bool())
  })
  private val idle :: bounds :: request :: response :: decode :: offer :: running :: result :: Nil = Enum(8)
  private val state = RegInit(idle)
  private val binding = Reg(new TileJobDescriptorRequest(commandIdWidth))
  private val words = Reg(Vec(TileJobMemoryLayout.words, UInt(32.W)))
  private val line = RegInit(0.U(2.W))
  private val address = Reg(UInt(32.W))
  private val cancelled = RegInit(false.B)
  private val fetched = RegInit(0.U(64.W))
  private val resultBits = Reg(new TileHostCompletion(commandIdWidth))
  private val abort = cancelled || io.cancel
  private val decoded = WireDefault(0.U.asTypeOf(new TileCommandJob))
  private val p = decoded.passDescriptor
  private val c = decoded.config
  decoded.version := 1.U
  decoded.commandLimit := Cat(words(8)(0), words(7))
  p.version := 1.U
  p.identity.context := words(2); p.identity.pass := words(3); p.identity.generation := words(4)
  c.cmdBase := words(5); c.cmdCount := words(6)(15, 0)
  c.screenW := words(9)(15, 0); c.screenH := words(10)(15, 0)
  p.originX := words(11)(15, 0); p.originY := words(11)(31, 16)
  p.tileLog2 := words(12)(2, 0); p.tileWidth := words(12)(12, 8)
  p.tileHeight := words(12)(20, 16); p.sampleMode := words(12)(25, 24)
  p.colorFormat := words(12)(30, 28); p.resolve := words(12)(31)
  for ((view, index) <- Seq((p.color, 13), (p.depth, 19))) {
    view.base := words(index); view.limit := Cat(words(index + 2)(0), words(index + 1))
    view.rowPitch := words(index + 3); view.loadOp := words(index + 4)(1, 0)
    view.store := words(index + 4)(2); view.clearValue := words(index + 5)
  }
  p.resolveBase := words(25); p.resolveLimit := Cat(words(27)(0), words(26))
  p.resolveRowPitch := words(28)
  c.colorBase := p.color.base; c.depthBase := p.depth.base; c.stride := p.color.rowPitch
  c.sampleMode := p.sampleMode; c.colorFormat := p.colorFormat
  c.depthTestEnable := words(29)(0); c.depthFunc := words(29)(6, 4)
  c.depthWriteEnable := words(29)(7); c.cullMode := words(29)(9, 8)
  c.blendEnable := words(29)(16); c.stencilTestEnable := words(29)(17)
  c.stencilFunc := words(30)(2, 0); c.stencilFailOp := words(30)(5, 3)
  c.stencilZFailOp := words(30)(8, 6); c.stencilZPassOp := words(30)(11, 9)
  c.stencilRef := words(31)(7, 0); c.stencilReadMask := words(31)(15, 8)
  c.stencilWriteMask := words(31)(23, 16)
  c.blendCfgEnable := words(32)(0); c.blendSrcFactor := words(32)(7, 4)
  c.blendDstFactor := words(32)(11, 8); c.blendEquation := words(32)(14, 12)
  c.texBase := words(33); c.texWidth := words(34)(13, 0); c.texHeight := words(34)(29, 16)
  c.texWrapClamp := words(35)(0); c.texMaxLevel := words(35)(5, 2)
  c.texEnable := words(35)(8); c.texMinLevel := words(35)(15, 12)
  c.texLodBias := words(35)(20, 16).asSInt; c.texFormat := words(36)(2, 0)
  private def masked(index: Int, allowed: BigInt): Bool =
    (words(index) & ((BigInt(1) << 32) - 1 - allowed).U(32.W)) === 0.U
  private val legalEncoding = words(0) === TileJobMemoryLayout.magic.U &&
    words(1) === TileJobMemoryLayout.words.U &&
    Seq(8, 15, 21, 27).map(i => masked(i, 1)).reduce(_ && _) &&
    Seq(6, 9, 10).map(i => masked(i, 65535)).reduce(_ && _) &&
    masked(12, BigInt(7) | (BigInt(31) << 8) | (BigInt(31) << 16) |
      (BigInt(3) << 24) | (BigInt(15) << 28)) &&
    masked(17, 7) && masked(23, 7) &&
    masked(29, BigInt(1) | (BigInt(7) << 4) | (BigInt(1) << 7) |
      (BigInt(3) << 8) | (BigInt(3) << 16)) &&
    masked(30, 4095) && masked(31, 0xffffff) && masked(32, 0x7ff1) &&
    masked(34, 0x3fff3fff) && masked(35, BigInt(1) | (BigInt(15) << 2) |
      (BigInt(1) << 8) | (BigInt(15) << 12) | (BigInt(31) << 16)) && masked(36, 7) &&
    words.drop(37).map(_ === 0.U).reduce(_ && _) &&
    c.screenW > 0.U && c.screenH > 0.U &&
    c.screenW <= graphics.screenWidth.U && c.screenH <= graphics.screenHeight.U &&
    c.texFormat <= PixelFormat.bgrx.U && c.blendSrcFactor <= 10.U &&
    c.blendDstFactor <= 10.U && c.blendEquation <= 4.U

  io.submit.ready := state === idle
  io.busy := state =/= idle
  io.fetching := state === bounds || state === request || state === response || state === decode
  io.job.valid := state === offer
  io.job.bits := decoded
  io.passCompletion.ready := state === running
  io.cancelCore := (state === offer || state === running) && abort
  io.completion.valid := state === result
  io.completion.bits := resultBits
  io.memoryRequest.valid := state === request
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := address
  io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  io.memoryRequest.bits.transactionId := transactionId.U
  io.memoryResponse.ready := state === response
  private def finish(status: UInt): Unit = {
    resultBits := 0.U.asTypeOf(resultBits)
    resultBits.commandId := binding.commandId
    resultBits.pass.status := status
    resultBits.commandBytesFetched := fetched
    state := result
  }
  when(io.submit.fire) {
    binding := io.submit.bits; address := io.submit.bits.base
    line := 0.U; cancelled := false.B; fetched := 0.U; state := bounds
  }
  when(state =/= idle && state =/= result && io.cancel) { cancelled := true.B }
  when(state === bounds) {
    val end = binding.base +& TileJobMemoryLayout.bytes.U
    val valid = binding.envelopeValid && binding.base(5, 0) === 0.U &&
      binding.limit <= (BigInt(1) << 32).U && end <= binding.limit
    when(abort) { finish(TilePassStatus.cancelled.U) }
      .elsewhen(!valid) { finish(TilePassStatus.invalidDescriptor.U) }
      .otherwise { state := request }
  }
  when(io.memoryRequest.fire) { state := response }
  when(io.memoryResponse.fire) {
    assert(io.memoryResponse.bits.transactionId === transactionId.U,
      "descriptor response must retain its reserved transaction ID")
    for (i <- 0 until 16) words(Cat(line, i.U(4.W))) := io.memoryResponse.bits.readData(32 * i + 31, 32 * i)
    when(io.memoryResponse.bits.fault) { finish(TilePassStatus.commandFault.U) }
      .elsewhen(abort) { finish(TilePassStatus.cancelled.U) }
      .elsewhen(line === 3.U) { state := decode }
      .otherwise { line := line + 1.U; address := address + 64.U; state := request }
  }
  when(state === decode) {
    when(abort) { finish(TilePassStatus.cancelled.U) }
      .elsewhen(!legalEncoding) { finish(TilePassStatus.invalidDescriptor.U) }
      .otherwise { state := offer }
  }
  when(io.job.fire) { state := running }
  when(state === running && io.commandReadAccepted) { fetched := fetched + 4.U }
  when(io.passCompletion.fire) {
    assert(io.passCompletion.bits.identity.asUInt === decoded.passDescriptor.identity.asUInt,
      "host completion must retain the admitted job identity")
    finish(io.passCompletion.bits.status)
    resultBits.pass := io.passCompletion.bits
    resultBits.commandBytesFetched := fetched + Mux(io.commandReadAccepted, 4.U, 0.U)
  }
  when(io.completion.fire) { state := idle }
}
