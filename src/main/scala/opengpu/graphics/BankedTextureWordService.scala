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

private class QueuedTextureMiss(tagWidth: Int, indexWidth: Int, cachePolicyInput: Boolean) extends Bundle {
  val owner = UInt(indexWidth.W)
  val request = new BankedTextureLookup(tagWidth, cachePolicyInput)
}

/** Four-bank physical read-through service. Every input word requires successful
  * upstream translation/permission admission. A resident hit retains bank II1;
  * misses share sixteen line reads and forward their exact returned word after
  * refill acknowledgement. Direct forwarding completes even when requested
  * lines map to the same cache set, without an endless refill/retry loop.
  */
class BankedTextureWordService(config: GpuConfig = GpuConfig(), dataBytes: Int = 8192,
    tagWidth: Int = 16, wordsPerPort: Int = 16, fills: Int = 16,
    useBlackBoxes: Boolean = false, cachePolicyInput: Boolean = false) extends Module {
  require(isPow2(wordsPerPort) && wordsPerPort >= 4 && wordsPerPort <= 32 && tagWidth > 0)
  private val slotBits = log2Ceil(wordsPerPort)
  private val indexWidth = slotBits + 2
  val io = IO(new Bundle {
    val in = Vec(4, Flipped(Decoupled(new BankedTextureLookup(tagWidth, cachePolicyInput))))
    val out = Vec(4, Decoupled(new BankedTextureReadResult(tagWidth)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, fills))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, fills)))
    val cancel = Input(Bool()); val invalidate = Input(Bool()); val busy = Output(Bool())
    val tapIssues = Output(UInt(3.W)); val bankConflicts = Output(UInt(3.W))
    val occupiedFills = Output(UInt(log2Ceil(fills + 1).W)); val joined = Output(Bool())
  })
  private val cache = Module(new BankedTextureLineStore(dataBytes, indexWidth, useBlackBoxes))
  private val miss = Module(new BankedTextureFillOwner(config, fills, 32, forwardData = true, cachePolicyInput = cachePolicyInput))
  private val requests = Reg(Vec(4, Vec(wordsPerPort, new BankedTextureLookup(tagWidth, cachePolicyInput))))
  private val alive = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(wordsPerPort)(false.B)))))
  // Cancellation is global here. No new lookup enters until the entire
  // epoch (including public replies) drains,so every live owner shares it.
  private val epochCancelled = RegInit(false.B)
  private val draining = RegInit(false.B)
  private val stop = io.cancel || io.invalidate || draining
  for(p <- 0 until 4) {
    val free = ~alive(p).asUInt
    val slot = PriorityEncoder(free)
    val allowed = free.orR && !stop
    cache.io.in(p).valid := io.in(p).valid && allowed
    cache.io.in(p).bits.identity := io.in(p).bits.identity
    cache.io.in(p).bits.textureKey := io.in(p).bits.textureKey
    cache.io.in(p).bits.address := io.in(p).bits.address
    cache.io.in(p).bits.rowParity := io.in(p).bits.rowParity
    cache.io.in(p).bits.tag := Cat(p.U(2.W), slot)
    io.in(p).ready := cache.io.in(p).ready && allowed
    when(io.in(p).fire) {
      requests(p)(slot) := io.in(p).bits
      alive(p)(slot) := true.B
    }

  }
  cache.io.refill <> miss.io.refill
  miss.io.refillDone <> cache.io.refillDone
  miss.io.cancel := io.cancel
  miss.io.invalidate := io.invalidate || draining
  cache.io.invalidate := miss.io.cacheInvalidate || draining
  io.memoryRequest <> miss.io.memoryRequest
  miss.io.memoryResponse <> io.memoryResponse
  // Untagged performance events are registered;their counts are preserved.
  io.tapIssues := RegNext(cache.io.readIssues, 0.U)
  io.bankConflicts := RegNext(cache.io.conflicts, 0.U)
  io.occupiedFills := miss.io.occupiedFills; io.joined := RegNext(miss.io.joined, false.B)

  private val fillArbiter = Module(new RRArbiter(new QueuedTextureMiss(tagWidth, indexWidth, cachePolicyInput), 4))
  // Separate the four-way metadata selection from the sixteen-fill key match.
  // Keep the full original word so queued cancellation needs no new fill.
  private val fillInputs = Module(new Queue(new QueuedTextureMiss(tagWidth, indexWidth, cachePolicyInput), 2))
  fillInputs.io.enq <> fillArbiter.io.out
  // Register the complete lookup metadata before MSHR matching. Cache reply
  // credits must not depend on the owner lookup plus the miss-join ready path.
  private val pendingMisses = Seq.fill(4)(Module(new Queue(new QueuedTextureMiss(tagWidth, indexWidth, cachePolicyInput), 2)))
  private val outputs = Seq.fill(4)(Module(new Queue(new QueuedTextureReadResult(tagWidth, indexWidth), 2)))
  private val resultArbiters = Seq.fill(4)(Module(new RRArbiter(new QueuedTextureReadResult(tagWidth, indexWidth), 4)))
  private val fillIndex = miss.io.out.bits.tag(indexWidth - 1, 0)
  private val fillPort = fillIndex(indexWidth - 1, slotBits)
  private val fillSlot = fillIndex(slotBits - 1, 0)
  private val fillRequest = requests(fillPort)(fillSlot)
  private val fillBank = Cat(fillRequest.rowParity, fillRequest.address(2))
  private val fillCancelled = epochCancelled || miss.io.out.bits.cancelled || io.cancel
  private val fillInvalidated = miss.io.out.bits.invalidated || io.invalidate || draining
  private val fillFault = miss.io.out.bits.fault ||
    (!miss.io.out.bits.installed && !fillRequest.noCache.getOrElse(false.B) && !fillCancelled && !fillInvalidated)
  private val fillGood = !fillFault && !fillCancelled && !fillInvalidated
  private val fillReady = Wire(Vec(4, Bool()))
  private val shared = fillInputs.io.deq
  private val sharedIndex = shared.bits.owner
  private val sharedPort = sharedIndex(indexWidth - 1, slotBits)
  private val sharedSlot = sharedIndex(slotBits - 1, 0)
  private val sharedRequest = shared.bits.request
  private val sharedBank = Cat(sharedRequest.rowParity, sharedRequest.address(2))
  private val sharedCancelled = epochCancelled || io.cancel
  private val sharedInvalidated = io.invalidate || draining
  private val sharedStopped = sharedCancelled || sharedInvalidated
  private val sharedReady = Wire(Vec(4, Bool()))
  miss.io.in.valid := shared.valid && !sharedStopped
  miss.io.in.bits := 0.U.asTypeOf(miss.io.in.bits)
  miss.io.in.bits.identity := sharedRequest.identity
  miss.io.in.bits.textureKey := sharedRequest.textureKey
  miss.io.in.bits.address := Cat(sharedRequest.address(31, 6), 0.U(6.W))
  miss.io.in.bits.rowParity := sharedRequest.rowParity
  miss.io.in.bits.tag := sharedIndex
  miss.io.in.bits.noCache.foreach(_ := sharedRequest.noCache.get)
  shared.ready := Mux(sharedStopped, sharedReady.asUInt.orR, miss.io.in.ready)
  when(shared.valid) { assert(alive(sharedPort)(sharedSlot), "shared fill admission lost its word owner") }
  for(b <- 0 until 4) {
    val word = cache.io.out(b)
    val index = word.bits.tag
    val port = index(indexWidth - 1, slotBits)
    val slot = index(slotBits - 1, 0)
    val request = requests(port)(slot)
    val wasCancelled = epochCancelled || io.cancel
    val wasInvalidated = io.invalidate || draining
    val effectiveHit = word.bits.hit && !request.noCache.getOrElse(false.B)
    val direct = effectiveHit || wasCancelled || wasInvalidated
    val result = resultArbiters(b).io.in(0)
    result.valid := word.valid && direct
    result.bits := 0.U.asTypeOf(result.bits)
    result.bits.owner := index
    result.bits.result.tag := request.tag
    result.bits.result.address := request.address
    result.bits.result.hit := effectiveHit && !wasCancelled && !wasInvalidated
    result.bits.result.data := Mux(result.bits.result.hit, word.bits.data, 0.U)
    result.bits.result.cancelled := wasCancelled
    result.bits.result.invalidated := wasInvalidated
    val pending = pendingMisses(b)
    pending.io.enq.valid := word.valid && !direct
    pending.io.enq.bits.owner := index
    pending.io.enq.bits.request := request
    word.ready := Mux(direct, result.ready, pending.io.enq.ready)
    when(word.valid) { assert(alive(port)(slot), "banked word response lost its lookup owner") }

    val queued = pending.io.deq
    val queuedIndex = queued.bits.owner
    val queuedPort = queuedIndex(indexWidth - 1, slotBits)
    val queuedSlot = queuedIndex(slotBits - 1, 0)
    val queuedRequest = queued.bits.request
    val queuedCancelled = epochCancelled || io.cancel
    val queuedInvalidated = io.invalidate || draining
    val queuedStopped = queuedCancelled || queuedInvalidated
    val fill = fillArbiter.io.in(b)
    fill.valid := queued.valid && !queuedStopped
    fill.bits := queued.bits
    // Stopped misses bypass new fill admission,which is closed during drain.
    // Their lookup owners remain pinned until the public word reply retires.
    val poison = resultArbiters(b).io.in(2)
    poison.valid := queued.valid && queuedStopped
    poison.bits := 0.U.asTypeOf(poison.bits)
    poison.bits.owner := queuedIndex
    poison.bits.result.tag := queuedRequest.tag
    poison.bits.result.address := queuedRequest.address
    poison.bits.result.cancelled := queuedCancelled
    poison.bits.result.invalidated := queuedInvalidated
    queued.ready := Mux(queuedStopped, poison.ready, fill.ready)
    when(queued.valid) { assert(alive(queuedPort)(queuedSlot), "queued texture miss lost its lookup owner") }

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
    val sharedPoison = resultArbiters(b).io.in(3)
    sharedPoison.valid := shared.valid && sharedStopped && sharedBank === b.U
    sharedPoison.bits := 0.U.asTypeOf(sharedPoison.bits)
    sharedPoison.bits.owner := sharedIndex
    sharedPoison.bits.result.tag := sharedRequest.tag
    sharedPoison.bits.result.address := sharedRequest.address
    sharedPoison.bits.result.cancelled := sharedCancelled
    sharedPoison.bits.result.invalidated := sharedInvalidated
    sharedReady(b) := sharedPoison.ready && sharedBank === b.U
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
  miss.io.out.ready := fillReady.asUInt.orR
  when(miss.io.out.valid) { assert(alive(fillPort)(fillSlot), "fill forwarding lost its lookup owner") }
  io.busy := alive.asUInt.orR || cache.io.busy || miss.io.busy || outputs.map(_.io.deq.valid).reduce(_ || _) ||
    pendingMisses.map(_.io.deq.valid).reduce(_ || _) || fillInputs.io.deq.valid
  when((io.cancel || io.invalidate) && io.busy) { draining := true.B }
  when(io.cancel && io.busy) { epochCancelled := true.B }
  when(!io.busy) { draining := false.B; epochCancelled := false.B }
}
