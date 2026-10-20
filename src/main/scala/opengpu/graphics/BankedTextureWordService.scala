package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class BankedTextureReadResult(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val address = UInt(32.W)
  val data = UInt(32.W)
  val hit = Bool()
  val fault = Bool()
  val cancelled = Bool()
  val invalidated = Bool()
}
private class QueuedTextureReadResult(tagWidth: Int, indexWidth: Int) extends Bundle {
  val owner = UInt(indexWidth.W)
  val result = new BankedTextureReadResult(tagWidth)
}

/** Four-bank physical read-through service. Every input word requires successful
  * upstream translation/permission admission. A resident hit retains bank II1;
  * misses share sixteen line reads and forward their exact returned word after
  * refill acknowledgement. Direct forwarding completes even when requested
  * lines map to the same cache set, without an endless refill/retry loop.
  */
class BankedTextureWordService(config: GpuConfig = GpuConfig(), dataBytes: Int = 8192,
    tagWidth: Int = 16, wordsPerPort: Int = 16, fills: Int = 16,
    useBlackBoxes: Boolean = false) extends Module {
  require(isPow2(wordsPerPort) && wordsPerPort >= 4 && wordsPerPort <= 32 && tagWidth > 0)
  private val slotBits = log2Ceil(wordsPerPort)
  private val indexWidth = slotBits + 2
  val io = IO(new Bundle {
    val in = Vec(4, Flipped(Decoupled(new BankedTextureLookup(tagWidth))))
    val out = Vec(4, Decoupled(new BankedTextureReadResult(tagWidth)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, fills))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, fills)))
    val cancel = Input(Bool()); val invalidate = Input(Bool()); val busy = Output(Bool())
    val tapIssues = Output(UInt(3.W)); val bankConflicts = Output(UInt(3.W))
    val occupiedFills = Output(UInt(log2Ceil(fills + 1).W)); val joined = Output(Bool())
  })
  private val cache = Module(new BankedTextureLineStore(dataBytes, indexWidth, useBlackBoxes))
  private val miss = Module(new BankedTextureFillOwner(config, fills, 32, forwardData = true))
  private val requests = Reg(Vec(4, Vec(wordsPerPort, new BankedTextureLookup(tagWidth))))
  private val alive = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(wordsPerPort)(false.B)))))
  private val cancelled = Reg(Vec(4, Vec(wordsPerPort, Bool())))
  private val invalidated = Reg(Vec(4, Vec(wordsPerPort, Bool())))
  private val draining = RegInit(false.B)
  private val stop = io.cancel || io.invalidate || draining
  for(p <- 0 until 4) {
    val free = ~alive(p).asUInt
    val slot = PriorityEncoder(free)
    val allowed = free.orR && !stop
    cache.io.in(p).valid := io.in(p).valid && allowed
    cache.io.in(p).bits := io.in(p).bits
    cache.io.in(p).bits.tag := Cat(p.U(2.W), slot)
    io.in(p).ready := cache.io.in(p).ready && allowed
    when(io.in(p).fire) {
      requests(p)(slot) := io.in(p).bits
      alive(p)(slot) := true.B
      cancelled(p)(slot) := false.B
      invalidated(p)(slot) := false.B
    }
    for(s <- 0 until wordsPerPort) {
      when(alive(p)(s)) {
        when(io.cancel) { cancelled(p)(s) := true.B }
        when(io.invalidate || draining) { invalidated(p)(s) := true.B }
      }
    }
  }
  cache.io.refill <> miss.io.refill
  miss.io.refillDone <> cache.io.refillDone
  miss.io.cancel := io.cancel
  miss.io.invalidate := io.invalidate || draining
  cache.io.invalidate := miss.io.cacheInvalidate || draining
  io.memoryRequest <> miss.io.memoryRequest
  miss.io.memoryResponse <> io.memoryResponse
  io.tapIssues := cache.io.readIssues; io.bankConflicts := cache.io.conflicts
  io.occupiedFills := miss.io.occupiedFills; io.joined := miss.io.joined

  private val fillArbiter = Module(new RRArbiter(new BankedTextureFillRequest, 4))
  private val outputs = Seq.fill(4)(Module(new Queue(new QueuedTextureReadResult(tagWidth, indexWidth), 2)))
  private val resultArbiters = Seq.fill(4)(Module(new RRArbiter(new QueuedTextureReadResult(tagWidth, indexWidth), 2)))
  private val fillIndex = miss.io.out.bits.tag(indexWidth - 1, 0)
  private val fillPort = fillIndex(indexWidth - 1, slotBits)
  private val fillSlot = fillIndex(slotBits - 1, 0)
  private val fillRequest = requests(fillPort)(fillSlot)
  private val fillBank = Cat(fillRequest.rowParity, fillRequest.address(2))
  private val fillCancelled = cancelled(fillPort)(fillSlot) || miss.io.out.bits.cancelled || io.cancel
  private val fillInvalidated = invalidated(fillPort)(fillSlot) || miss.io.out.bits.invalidated || io.invalidate || draining
  private val fillFault = miss.io.out.bits.fault ||
    (!miss.io.out.bits.installed && !fillCancelled && !fillInvalidated)
  private val fillGood = !fillFault && !fillCancelled && !fillInvalidated
  private val fillReady = Wire(Vec(4, Bool()))
  for(b <- 0 until 4) {
    val word = cache.io.out(b)
    val index = word.bits.tag
    val port = index(indexWidth - 1, slotBits)
    val slot = index(slotBits - 1, 0)
    val request = requests(port)(slot)
    val wasCancelled = cancelled(port)(slot) || io.cancel
    val wasInvalidated = invalidated(port)(slot) || io.invalidate || draining
    val direct = word.bits.hit || wasCancelled || wasInvalidated
    val result = resultArbiters(b).io.in(0)
    result.valid := word.valid && direct
    result.bits := 0.U.asTypeOf(result.bits)
    result.bits.owner := index
    result.bits.result.tag := request.tag
    result.bits.result.address := request.address
    result.bits.result.hit := word.bits.hit && !wasCancelled && !wasInvalidated
    result.bits.result.data := Mux(result.bits.result.hit, word.bits.data, 0.U)
    result.bits.result.cancelled := wasCancelled
    result.bits.result.invalidated := wasInvalidated
    val fill = fillArbiter.io.in(b)
    fill.valid := word.valid && !direct
    fill.bits := 0.U.asTypeOf(fill.bits)
    fill.bits.identity := request.identity
    fill.bits.textureKey := request.textureKey
    fill.bits.address := Cat(request.address(31, 6), 0.U(6.W))
    fill.bits.rowParity := request.rowParity
    fill.bits.tag := index
    word.ready := Mux(direct, result.ready, fill.ready)
    when(word.valid) { assert(alive(port)(slot), "banked word response lost its lookup owner") }

    val forwarded = resultArbiters(b).io.in(1)
    forwarded.valid := miss.io.out.valid && fillBank === b.U
    forwarded.bits := 0.U.asTypeOf(forwarded.bits)
    forwarded.bits.owner := fillIndex
    forwarded.bits.result.tag := fillRequest.tag
    forwarded.bits.result.address := fillRequest.address
    forwarded.bits.result.data := Mux(fillGood,
      (miss.io.out.bits.data.get >> (fillRequest.address(5, 0) << 3))(31, 0), 0.U)
    forwarded.bits.result.hit := fillGood
    forwarded.bits.result.fault := fillFault
    forwarded.bits.result.cancelled := fillCancelled
    forwarded.bits.result.invalidated := fillInvalidated
    fillReady(b) := forwarded.ready && fillBank === b.U
    outputs(b).io.enq <> resultArbiters(b).io.out
    io.out(b).valid := outputs(b).io.deq.valid
    io.out(b).bits := outputs(b).io.deq.bits.result
    outputs(b).io.deq.ready := io.out(b).ready
    when(outputs(b).io.deq.fire) {
      val owner = outputs(b).io.deq.bits.owner
      val p = owner(indexWidth - 1, slotBits); val s = owner(slotBits - 1, 0)
      assert(alive(p)(s), "texture word completion recycled its lookup owner")
      alive(p)(s) := false.B
    }
  }
  miss.io.in <> fillArbiter.io.out
  miss.io.out.ready := fillReady.asUInt.orR
  when(miss.io.out.valid) { assert(alive(fillPort)(fillSlot), "fill forwarding lost its lookup owner") }
  io.busy := alive.asUInt.orR || cache.io.busy || miss.io.busy || outputs.map(_.io.deq.valid).reduce(_ || _)
  when((io.cancel || io.invalidate) && io.busy) { draining := true.B }
  when(!io.busy) { draining := false.B }
}
