package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class BankedTextureFillRequest extends Bundle {
  val identity = new GraphicsWorkIdentity
  val textureKey = UInt(64.W)
  /** Successfully translated, read-permission-admitted physical line. */
  val address = UInt(32.W)
  val rowParity = Bool()
  val tag = UInt(32.W)
}
class BankedTextureFillResult(forwardData: Boolean = false) extends Bundle {
  val identity = new GraphicsWorkIdentity
  val textureKey = UInt(64.W)
  val address = UInt(32.W)
  val rowParity = Bool()
  val tag = UInt(32.W)
  val installed = Bool()
  val fault = Bool()
  val cancelled = Bool()
  val invalidated = Bool()
  /** Optional refill forwarding avoids cache-set conflict/retry livelock. */
  val data = if(forwardData) Some(UInt(512.W)) else None
}

/** Bounded physical line-fill ownership for the banked texture store.
  * Each distinct physical/context/view/parity key owns one memory ID until its
  * read, refill acknowledgement and all waiting completions have drained.
  * Permission admission is required for EVERY waiter before joining; this
  * block does not replace translation. A failed fill never becomes zero data.
  * Global cancellation drains irrevocable offers and blocks a new epoch until
  * every completion is consumed. Shared task integration must scope that drain.
  */
class BankedTextureFillOwner(config: GpuConfig = GpuConfig(), fills: Int = 16, waiters: Int = 32,
    forwardData: Boolean = false) extends Module {
  require(config.xLen == 32 && isPow2(fills) && fills >= 2 && fills <= 32)
  require(isPow2(waiters) && waiters >= fills && waiters <= 128)
  private val fillBits = log2Ceil(fills)
  private val waiterBits = log2Ceil(waiters)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new BankedTextureFillRequest))
    val out = Decoupled(new BankedTextureFillResult(forwardData))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, fills))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, fills)))
    val refill = Decoupled(new BankedTextureRefill)
    val refillDone = Flipped(Decoupled(new BankedTextureRefillCompletion))
    val cancel = Input(Bool())
    val invalidate = Input(Bool())
    val cacheInvalidate = Output(Bool())
    val busy = Output(Bool())
    val occupiedFills = Output(UInt(log2Ceil(fills + 1).W))
    val waitingConsumers = Output(UInt(log2Ceil(waiters + 1).W))
    val joined = Output(Bool())
  })
  private object State extends ChiselEnum {
    val free, queued, offered, pending, returned, refilling, done = Value
  }
  private val states = RegInit(VecInit(Seq.fill(fills)(State.free)))
  private val keys = Reg(Vec(fills, new BankedTextureFillRequest))
  private val data = Reg(Vec(fills, UInt(512.W)))
  private val failed = Reg(Vec(fills, Bool()))
  private val stopped = Reg(Vec(fills, Bool()))
  private val noFill = Reg(Vec(fills, Bool()))
  private val installed = Reg(Vec(fills, Bool()))
  private val invalidated = Reg(Vec(fills, Bool()))
  private val alive = RegInit(VecInit(Seq.fill(waiters)(false.B)))
  private val waiterSlot = Reg(Vec(waiters, UInt(fillBits.W)))
  private val waiterTag = Reg(Vec(waiters, UInt(32.W)))
  private val draining = RegInit(false.B)
  private val outputValid = RegInit(false.B)
  private val output = Reg(new BankedTextureFillResult(forwardData))
  private val outputSlot = Reg(UInt(fillBits.W))
  io.out.valid := outputValid
  io.out.bits := output

  private val matches = VecInit((0 until fills).map { s =>
    // A new miss after acknowledgement must start a new fill: another line
    // may already have replaced the installed cache set while replies wait.
    states(s) =/= State.free && states(s) =/= State.done && !stopped(s) && !noFill(s) &&
      keys(s).identity.asUInt === io.in.bits.identity.asUInt &&
      keys(s).textureKey === io.in.bits.textureKey &&
      keys(s).address === io.in.bits.address && keys(s).rowParity === io.in.bits.rowParity
  })
  private val free = VecInit(states.map(_ === State.free))
  private val freeWaiter = ~alive.asUInt
  private val selected = Mux(matches.asUInt.orR, PriorityEncoder(matches), PriorityEncoder(free))
  private val nextWaiter = PriorityEncoder(freeWaiter)
  io.in.ready := !io.cancel && !io.invalidate && !draining && freeWaiter.orR &&
    (matches.asUInt.orR || free.asUInt.orR)
  io.joined := io.in.fire && matches.asUInt.orR
  when(io.in.fire) {
    assert(io.in.bits.address(5, 0) === 0.U, "texture fill requires an admitted aligned physical line")
    alive(nextWaiter) := true.B
    waiterSlot(nextWaiter) := selected
    waiterTag(nextWaiter) := io.in.bits.tag
    when(!matches.asUInt.orR) {
      states(selected) := State.queued
      keys(selected) := io.in.bits
      failed(selected) := false.B
      stopped(selected) := false.B
      noFill(selected) := false.B
      installed(selected) := false.B
      invalidated(selected) := false.B
    }
  }

  // Register the complete memory offer before presenting it. Cancellation may
  // stop unoffered reads, but never withdraw or change an offered transaction.
  private val memoryOffers = Module(new Queue(new ComputeMemoryRequest(config, 64, fills), 1))
  private val memoryArbiter = Module(new RRArbiter(UInt(fillBits.W), fills))
  for(s <- 0 until fills) {
    memoryArbiter.io.in(s).valid := states(s) === State.queued && !stopped(s) && !noFill(s) &&
      !io.cancel && !io.invalidate && !draining
    memoryArbiter.io.in(s).bits := s.U
  }
  private val issuing = memoryArbiter.io.out.bits
  memoryArbiter.io.out.ready := memoryOffers.io.enq.ready
  memoryOffers.io.enq.valid := memoryArbiter.io.out.valid
  memoryOffers.io.enq.bits := 0.U.asTypeOf(memoryOffers.io.enq.bits)
  memoryOffers.io.enq.bits.address := keys(issuing).address
  memoryOffers.io.enq.bits.sizeLog2 := 6.U
  memoryOffers.io.enq.bits.byteMask := Fill(64, 1.U(1.W))
  memoryOffers.io.enq.bits.cachePolicy := CachePolicy.uncached
  memoryOffers.io.enq.bits.transactionId := issuing
  when(memoryArbiter.io.out.fire) { states(issuing) := State.offered }
  io.memoryRequest <> memoryOffers.io.deq
  when(io.memoryRequest.fire) {
    val slot = io.memoryRequest.bits.transactionId
    assert(states(slot) === State.offered, "texture memory offer lost its fill owner")
    states(slot) := State.pending
  }
  private val responseSlot = io.memoryResponse.bits.transactionId
  io.memoryResponse.ready := states(responseSlot) === State.pending
  when(io.memoryResponse.valid) {
    assert(states(responseSlot) === State.pending, "texture memory reply must retain its fill owner")
  }
  when(io.memoryResponse.fire) {
    failed(responseSlot) := io.memoryResponse.bits.fault
    data(responseSlot) := io.memoryResponse.bits.readData
    states(responseSlot) := Mux(io.memoryResponse.bits.fault, State.done, State.returned)
  }

  // Refill data and metadata stay immutable through an arbitrarily blocked
  // offer. The store returns an acknowledgement after all eight write beats.
  private val refillValid = RegInit(false.B)
  private val refillPending = RegInit(false.B)
  private val refillSlot = Reg(UInt(fillBits.W))
  private val refill = Reg(new BankedTextureRefill)
  private val refillArbiter = Module(new RRArbiter(UInt(fillBits.W), fills))
  for(s <- 0 until fills) {
    refillArbiter.io.in(s).valid := states(s) === State.returned && !stopped(s) && !noFill(s) &&
      !io.cancel && !io.invalidate
    refillArbiter.io.in(s).bits := s.U
  }
  refillArbiter.io.out.ready := !refillValid && !refillPending
  when(refillArbiter.io.out.fire) {
    val slot = refillArbiter.io.out.bits
    refillSlot := slot
    refill := 0.U.asTypeOf(refill)
    refill.identity := keys(slot).identity
    refill.textureKey := keys(slot).textureKey
    refill.address := keys(slot).address
    refill.rowParity := keys(slot).rowParity
    refill.data := data(slot)
    refillValid := true.B
    states(slot) := State.refilling
  }
  io.refill.valid := refillValid
  io.refill.bits := refill
  when(io.refill.fire) { refillValid := false.B; refillPending := true.B }
  io.refillDone.ready := refillPending
  when(io.refillDone.fire) {
    val done = io.refillDone.bits
    assert(done.identity.asUInt === refill.identity.asUInt && done.textureKey === refill.textureKey &&
      done.address === refill.address && done.rowParity === refill.rowParity,
      "texture refill acknowledgement lost its owner")
    failed(refillSlot) := done.fault
    installed(refillSlot) := done.installed && !stopped(refillSlot) && !noFill(refillSlot) && !io.cancel && !io.invalidate
    invalidated(refillSlot) := done.invalidated || noFill(refillSlot) || io.invalidate
    states(refillSlot) := State.done
    refillPending := false.B
  }

  for(s <- 0 until fills) {
    when(states(s) =/= State.free) {
      when(io.cancel) { stopped(s) := true.B; noFill(s) := true.B }
      when(io.invalidate) { noFill(s) := true.B; invalidated(s) := true.B }
      when((states(s) === State.queued || states(s) === State.returned) &&
        (stopped(s) || noFill(s) || io.cancel || io.invalidate)) {
        states(s) := State.done
        installed(s) := false.B
      }
    }
    val hasWaiter = VecInit((0 until waiters).map(w => alive(w) && waiterSlot(w) === s.U)).asUInt.orR
    val heldCompletion = outputValid && outputSlot === s.U
    when(states(s) === State.done && !hasWaiter && !heldCompletion && !(io.in.fire && selected === s.U)) {
      states(s) := State.free
    }
  }
  private val completionArbiter = Module(new RRArbiter(UInt(waiterBits.W), waiters))
  for(w <- 0 until waiters) {
    completionArbiter.io.in(w).valid := alive(w) && states(waiterSlot(w)) === State.done
    completionArbiter.io.in(w).bits := w.U
  }
  completionArbiter.io.out.ready := !outputValid || io.out.ready
  when(completionArbiter.io.out.ready) { outputValid := completionArbiter.io.out.valid }
  when(completionArbiter.io.out.fire) {
    val waiter = completionArbiter.io.out.bits
    val slot = waiterSlot(waiter)
    alive(waiter) := false.B
    outputSlot := slot
    output := 0.U.asTypeOf(output)
    output.identity := keys(slot).identity
    output.textureKey := keys(slot).textureKey
    output.address := keys(slot).address
    output.rowParity := keys(slot).rowParity
    output.tag := waiterTag(waiter)
    output.fault := failed(slot)
    output.cancelled := stopped(slot) || io.cancel
    output.invalidated := invalidated(slot) || noFill(slot) || io.invalidate
    output.installed := installed(slot) && !stopped(slot) && !noFill(slot) && !io.cancel && !io.invalidate
    output.data.foreach(_ := Mux(failed(slot) || stopped(slot) || noFill(slot) || io.cancel || io.invalidate, 0.U, data(slot)))
  }
  io.occupiedFills := PopCount(states.map(_ =/= State.free))
  io.waitingConsumers := PopCount(alive)
  io.busy := states.map(_ =/= State.free).reduce(_ || _) || outputValid ||
    memoryOffers.io.deq.valid || refillValid || refillPending
  // Invalidation also owns an epoch drain. A refill already offered under
  // backpressure must still observe invalidation when it is finally accepted.
  when((io.cancel || io.invalidate) && io.busy) { draining := true.B }
  when(!io.busy) { draining := false.B }
  io.cacheInvalidate := io.invalidate || io.cancel || draining
}
