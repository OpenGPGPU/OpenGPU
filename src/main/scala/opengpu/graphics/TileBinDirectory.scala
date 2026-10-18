package opengpu.graphics

import chisel3._
import chisel3.util._

/** One pass-level bin descriptor: a shared page arena and its active tiles. */
class TileBinDirectoryDescriptor extends Bundle {
  val version = UInt(8.W)
  val pool = new BinPagePoolDescriptor
  /** Every active tile id must be below this value, at most the table size. */
  val tileCount = UInt(16.W)
}

class TileBinAppend extends Bundle {
  val tile = UInt(32.W)
  val reference = new BinPrimitiveReference
}

class TileBinAppendResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val reference = new BinPrimitiveReference
  val status = UInt(PrimitiveBinStatus.width.W)
  /** Valid only on success. */
  val recordAddress = UInt(32.W)
  val committedCount = UInt(32.W)
}

/** Final descriptor of one tile chain. An empty chain has `headValid` low. */
class TileBinCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val status = UInt(PrimitiveBinStatus.width.W)
  /** Only success/exhaustion exposes a usable committed prefix. */
  val prefixUsable = Bool()
  val headValid = Bool()
  val head = UInt(32.W)
  val tail = UInt(32.W)
  val committedCount = UInt(32.W)
  val restartValid = Bool()
  val restart = new BinPrimitiveReference
}

class TileBinDirectoryCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(PrimitiveBinStatus.width.W)
  val tilesOpened = UInt(16.W)
  val allocatedPages = UInt(32.W)
  val committedCount = UInt(32.W)
}

/** Build independently appendable tile chains in one shared page arena.
  *
  * Each active tile owns a separate page chain, header tile field and
  * monotonic reference sequence; pages from different tiles interleave in
  * the arena, so a chain's page ordinals need not be contiguous. A tile can
  * be sealed as soon as its last reference commits, letting replay of a
  * finished tile proceed while other tiles are still being written.
  *
  * Appends to an unknown tile return `invalidDescriptor`; appends to a sealed
  * tile return `closed`. A per-tile ordering violation seals only that tile
  * (its committed prefix stays exact) and the other tiles continue. Page
  * exhaustion seals the requesting tile with its exact restart reference.
  * Pass-level cancellation or a memory fault stops all appends, replies to
  * the pending one, and reports every unreported tile as unusable.
  *
  * The caller must drain all consumers before ending the binding or reusing
  * the arena. This directory does not replay, schedule engines or resolve
  * primitive/state handles.
  */
class TileBinDirectory(
  tileSlots: Int = 8,
  pageBytes: Int = 4096,
  writeCredits: Int = 8
) extends Module {
  require(tileSlots >= 1 && tileSlots <= 64)
  private val capacity = PrimitiveBinPageLayout.recordsPerPage(pageBytes)
  require(capacity > 0)
  private val entryBits = log2Ceil(capacity + 1).max(1)
  private val indexBits = log2Ceil(tileSlots).max(1)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TileBinDirectoryDescriptor))
    val append = Flipped(Decoupled(new TileBinAppend))
    val appended = Decoupled(new TileBinAppendResult)
    /** Close one tile without ending the pass. Repeated seals of an already
      * reported tile are accepted as no-ops. */
    val seal = Flipped(Decoupled(UInt(32.W)))
    val tileClosed = Decoupled(new TileBinCompletion)
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    val completion = Decoupled(new TileBinDirectoryCompletion)
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
      val responseFault = Input(Bool())
    }
    val busy = Output(Bool())
    val committed = Output(Bool())
    val storeBytes = Output(UInt(3.W))
    val pageAllocated = Output(Bool())
  })
  private val Seq(idle, binding, ready, check, allocate, allocationWait, header,
    headerWait, link, linkWait, payload, payloadWait, commit, commitWait, reply,
    abortDrain, scan, close, poolWait, tileReply, done) = Enum(21)
  private val state = RegInit(idle)
  private val descriptor = Reg(new TileBinDirectoryDescriptor)
  private val current = Reg(new TileBinAppend)
  private val pending = RegInit(false.B)
  private val replyValid = RegInit(false.B)
  private val replyBits = Reg(new TileBinAppendResult)
  private val result = Reg(new TileBinDirectoryCompletion)
  private val passStatus = RegInit(PrimitiveBinStatus.success.U(PrimitiveBinStatus.width.W))
  private val aborting = RegInit(false.B)
  private val allocationPending = RegInit(false.B)
  private val workPage = RegInit(0.U(32.W))
  private val newPage = Reg(UInt(32.W))
  private val newOrdinal = Reg(UInt(32.W))
  private val wordIndex = RegInit(0.U(4.W))
  private val recordAddress = Reg(UInt(32.W))
  private val commitLive = RegInit(false.B)
  // The closing walk must represent `tileSlots` itself as its end marker.
  private val scanBits = log2Ceil(tileSlots+1).max(1)
  private val closeIndex = RegInit(0.U(scanBits.W))
  private val tileReplyValid = RegInit(false.B)
  private val tileReplyBits = Reg(new TileBinCompletion)
  private val tileReplyNext = RegInit(0.U(1.W))
  // Per-tile page chain, ordering and directory state.
  private val headValid = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val opened = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val closedSlot = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val reported = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val restartValid = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val head = Reg(Vec(tileSlots, UInt(32.W)))
  private val tail = Reg(Vec(tileSlots, UInt(32.W)))
  private val count = Reg(Vec(tileSlots, UInt(32.W)))
  // Running pass total: incrementing one register at commit keeps the
  // pass-completion sum off a many-input adder chain in a single cycle.
  private val totalCommitted = RegInit(0.U(32.W))
  private val pageCount = Reg(Vec(tileSlots, UInt(entryBits.W)))
  private val lastSequence = Reg(Vec(tileSlots, UInt(32.W)))
  private val tileStatus = RegInit(VecInit(Seq.fill(tileSlots)(
    PrimitiveBinStatus.success.U(PrimitiveBinStatus.width.W))))
  private val restart = Reg(Vec(tileSlots, new BinPrimitiveReference))
  private val pool = Module(new BinPagePool(pageBytes))
  private val writes = Module(new TileAttachmentWriteQueue(writeCredits))

  io.mem.req.valid := writes.io.request.valid
  io.mem.req.bits.write := true.B
  io.mem.req.bits.addr := writes.io.request.bits.address
  io.mem.req.bits.data := writes.io.request.bits.data
  writes.io.request.ready := io.mem.req.ready
  writes.io.response.valid := io.mem.resp.valid
  writes.io.response.bits := io.mem.resp.bits
  writes.io.responseFault := io.mem.responseFault
  io.mem.resp.ready := writes.io.response.ready
  private val faultNow = io.mem.resp.fire && io.mem.responseFault
  private val badDescriptor = state =/= idle && (descriptor.version =/= 1.U ||
    descriptor.tileCount === 0.U || descriptor.tileCount > tileSlots.U)
  private val stop = aborting || io.cancel || faultNow || badDescriptor
  private val effectiveStatus = Mux(faultNow || passStatus === PrimitiveBinStatus.memoryFault.U,
    PrimitiveBinStatus.memoryFault.U, Mux(badDescriptor,
      PrimitiveBinStatus.invalidDescriptor.U, Mux(io.cancel || aborting,
        PrimitiveBinStatus.cancelled.U, passStatus)))
  private val currentIdx = current.tile(indexBits - 1, 0)
  private val currentValid = current.tile < descriptor.tileCount.pad(32)
  private val currentClosed = closedSlot(currentIdx)
  private val currentCount = count(currentIdx)
  private val currentPageCount = pageCount(currentIdx)
  private val currentLastSequence = lastSequence(currentIdx)
  private val currentHeadValid = headValid(currentIdx)
  private val readyToAccept = state === ready && !stop
  writes.io.abort := stop
  writes.io.begin := io.begin.fire
  writes.io.enqueue.valid := false.B
  writes.io.enqueue.bits := 0.U.asTypeOf(new TileAttachmentWrite)

  pool.io.begin.valid := state === idle && io.begin.valid && !io.cancel
  pool.io.begin.bits := io.begin.bits.pool
  pool.io.begin.bits.version := Mux(io.begin.bits.version === 1.U, io.begin.bits.pool.version, 0.U)
  io.begin.ready := state === idle && pool.io.begin.ready && !io.cancel
  pool.io.allocate.valid := state === allocate && !stop
  pool.io.allocate.bits.tile := current.tile
  pool.io.allocate.bits.token := current.reference.token
  pool.io.allocation.ready := state === allocationWait || state === abortDrain
  pool.io.cancel := stop && state =/= idle && state =/= done
  pool.io.end.valid := state === close && !stop
  pool.io.end.bits := true.B
  pool.io.completion.ready := (state === binding || state === poolWait) &&
    writes.io.drained && !pending && !allocationPending && !replyValid && !tileReplyValid
  // Ready must not depend on the corresponding valid: an idle caller has to
  // observe capacity before it offers work.
  // Seal wins over append, and both precede end. Keep ready and the state
  // transitions consistent when independent producers offer work together.
  io.append.ready := readyToAccept && !io.seal.valid
  io.seal.ready := readyToAccept
  io.end.ready := readyToAccept && !io.append.valid && !io.seal.valid
  io.appended.valid := replyValid
  io.appended.bits := replyBits
  io.tileClosed.valid := tileReplyValid
  io.tileClosed.bits := tileReplyBits
  io.completion.valid := state === done
  io.completion.bits := result
  io.busy := state =/= idle
  io.storeBytes := Mux(io.mem.req.fire, 4.U, 0.U)
  io.pageAllocated := pool.io.allocated

  private def offerReply(status: UInt, address: UInt, committed: UInt): Unit = {
    replyBits.identity := descriptor.pool.identity
    replyBits.tile := current.tile
    replyBits.reference := current.reference
    replyBits.status := status
    replyBits.recordAddress := address
    replyBits.committedCount := committed
    replyValid := true.B
    state := reply
  }
  private def tileCompletionBits(tile: UInt, status: UInt): TileBinCompletion = {
    val valid = tile < descriptor.tileCount.pad(32)
    val index = tile(indexBits - 1, 0)
    val bits = Wire(new TileBinCompletion)
    bits.identity := descriptor.pool.identity
    bits.tile := tile
    bits.status := status
    bits.prefixUsable := valid &&
      (status === PrimitiveBinStatus.success.U || status === PrimitiveBinStatus.exhausted.U)
    bits.headValid := valid && headValid(index)
    bits.head := Mux(valid, head(index), 0.U)
    bits.tail := Mux(valid, tail(index), 0.U)
    bits.committedCount := Mux(valid, count(index), 0.U)
    bits.restartValid := valid && restartValid(index) &&
      status === PrimitiveBinStatus.exhausted.U
    bits.restart := restart(index)
    bits
  }
  // Exhaustion is sticky: the pool refuses further allocation admission, so a
  // tile that needs a new page fails immediately instead of waiting for an
  // allocate handshake that can no longer fire.
  private def exhaustCurrent(): Unit = {
    tileStatus(currentIdx) := PrimitiveBinStatus.exhausted.U
    closedSlot(currentIdx) := true.B
    restart(currentIdx) := current.reference
    restartValid(currentIdx) := true.B
    when(passStatus === PrimitiveBinStatus.success.U) {
      passStatus := PrimitiveBinStatus.exhausted.U
    }
    offerReply(PrimitiveBinStatus.exhausted.U, 0.U, currentCount)
  }
  private def store(address: UInt, data: UInt): Unit = {
    writes.io.enqueue.valid := !stop
    writes.io.enqueue.bits.address := address
    writes.io.enqueue.bits.data := data
  }
  private def startPayload(): Unit = {
    recordAddress := workPage + PrimitiveBinPageLayout.headerBytes.U +
      (currentPageCount << 4)
    wordIndex := 0.U
    state := payload
  }

  when(pool.io.allocate.fire) { allocationPending := true.B }
  when(pool.io.allocation.fire) { allocationPending := false.B }
  when(io.appended.fire) {
    replyValid := false.B
    pending := false.B
    state := Mux(stop, abortDrain, ready)
  }
  when(io.tileClosed.fire) {
    tileReplyValid := false.B
    when(tileReplyBits.tile < descriptor.tileCount.pad(32)) {
      reported(tileReplyBits.tile(indexBits - 1, 0)) := true.B
    }
    state := Mux(tileReplyNext === 0.U, ready, scan)
  }
  switch(state) {
    is(idle) {
      when(io.begin.fire) {
        descriptor := io.begin.bits
        pending := false.B
        aborting := false.B
        passStatus := PrimitiveBinStatus.success.U
        totalCommitted := 0.U
        closeIndex := 0.U
        for (i <- 0 until tileSlots) {
          headValid(i) := false.B
          opened(i) := false.B
          closedSlot(i) := false.B
          reported(i) := false.B
          restartValid(i) := false.B
          head(i) := 0.U
          tail(i) := 0.U
          count(i) := 0.U
          pageCount(i) := 0.U
          lastSequence(i) := 0.U
          tileStatus(i) := PrimitiveBinStatus.success.U
        }
        state := binding
      }
    }
    is(binding) { when(pool.io.allocate.ready && !stop) { state := ready } }
    is(ready) {
      when(io.seal.fire) {
        val tile = io.seal.bits
        val valid = tile < descriptor.tileCount.pad(32)
        val index = tile(indexBits - 1, 0)
        when(valid) { closedSlot(index) := true.B }
        // tileClosed is a replay descriptor, not a seal acknowledgement:
        // publishing it twice would execute the same primitives twice.
        when(!valid || !reported(index)) {
          tileReplyBits := tileCompletionBits(tile, Mux(valid,
            tileStatus(index), PrimitiveBinStatus.invalidDescriptor.U(PrimitiveBinStatus.width.W)))
          tileReplyNext := 0.U
          tileReplyValid := true.B
          state := tileReply
        }
      }.elsewhen(io.append.fire) {
        current := io.append.bits
        pending := true.B
        workPage := tail(io.append.bits.tile(indexBits - 1, 0))
        state := check
      }.elsewhen(io.end.fire) {
        closeIndex := 0.U
        state := scan
      }
    }
    is(check) {
      when(!stop) {
        when(!currentValid) {
          offerReply(PrimitiveBinStatus.invalidDescriptor.U, 0.U, 0.U)
        }.elsewhen(currentClosed) {
          offerReply(PrimitiveBinStatus.closed.U, 0.U, currentCount)
        }.elsewhen(currentCount =/= 0.U && current.reference.sequence <= currentLastSequence) {
          tileStatus(currentIdx) := PrimitiveBinStatus.invalidOrder.U
          closedSlot(currentIdx) := true.B
          when(passStatus === PrimitiveBinStatus.success.U) {
            passStatus := PrimitiveBinStatus.invalidOrder.U
          }
          offerReply(PrimitiveBinStatus.invalidOrder.U, 0.U, currentCount)
        }.elsewhen(!currentHeadValid || currentPageCount === capacity.U) {
          when(pool.io.exhausted) { exhaustCurrent() }.otherwise { state := allocate }
        }.otherwise { startPayload() }
      }
    }
    is(allocate) {
      when(pool.io.allocate.fire) { state := allocationWait }
        .elsewhen(pool.io.exhausted) { exhaustCurrent() }
    }
    is(allocationWait) {
      when(pool.io.allocation.fire && !stop) {
        assert(pool.io.allocation.bits.identity.asUInt === descriptor.pool.identity.asUInt)
        assert(pool.io.allocation.bits.request.tile === current.tile &&
          pool.io.allocation.bits.request.token === current.reference.token)
        when(pool.io.allocation.bits.status === BinPageStatus.success.U) {
          newPage := pool.io.allocation.bits.address
          newOrdinal := pool.io.allocation.bits.ordinal
          wordIndex := 0.U
          state := header
        }.otherwise { exhaustCurrent() }
      }
    }
    is(header) {
      val words = VecInit(Seq(PrimitiveBinPageLayout.magic.U(32.W), 0.U(32.W), 0.U(32.W),
        descriptor.pool.identity.context, descriptor.pool.identity.pass,
        descriptor.pool.identity.generation, current.tile, newOrdinal))
      store(newPage + (wordIndex << 2), words(wordIndex(2, 0)))
      when(writes.io.enqueue.fire) {
        wordIndex := wordIndex + 1.U
        when(wordIndex === 7.U) { state := headerWait }
      }
    }
    is(headerWait) {
      when(writes.io.drained && !stop) {
        when(!currentHeadValid) {
          head(currentIdx) := newPage
          tail(currentIdx) := newPage
          headValid(currentIdx) := true.B
          opened(currentIdx) := true.B
          pageCount(currentIdx) := 0.U
          workPage := newPage
          state := check
        }.otherwise { state := link }
      }
    }
    is(link) {
      store(workPage + PrimitiveBinPageLayout.nextOffset.U, newPage)
      when(writes.io.enqueue.fire) { state := linkWait }
    }
    is(linkWait) {
      when(writes.io.drained && !stop) {
        tail(currentIdx) := newPage
        pageCount(currentIdx) := 0.U
        workPage := newPage
        state := check
      }
    }
    is(payload) {
      val words = VecInit(Seq(current.reference.primitive, current.reference.state,
        current.reference.sequence, current.reference.token))
      store(recordAddress + (wordIndex << 2), words(wordIndex(1, 0)))
      when(writes.io.enqueue.fire) {
        wordIndex := wordIndex + 1.U
        when(wordIndex === 3.U) { state := payloadWait }
      }
    }
    is(payloadWait) { when(writes.io.drained && !stop) { state := commit } }
    is(commit) {
      store(workPage + PrimitiveBinPageLayout.countOffset.U, currentPageCount + 1.U)
      when(writes.io.enqueue.fire) { commitLive := true.B; state := commitWait }
    }
    is(abortDrain) {
      when(writes.io.drained && !allocationPending) {
        commitLive := false.B
        when(pending && !replyValid) { offerReply(effectiveStatus, 0.U, currentCount) }
          .elsewhen(!pending && !replyValid) {
            closeIndex := 0.U
            state := scan
          }
      }
    }
    // A pass that is stopping keeps this walk uninterrupted; the per-tile
    // status below is the failure status instead of a late seal.
    is(scan) {
      val slot = closeIndex(indexBits - 1, 0)
      when(closeIndex === descriptor.tileCount) {
        state := Mux(stop, poolWait, close)
      }.elsewhen(!reported(slot) && (opened(slot) || closedSlot(slot))) {
        closedSlot(slot) := true.B
        tileReplyBits := tileCompletionBits(closeIndex.pad(32),
          Mux(stop, effectiveStatus, tileStatus(slot)))
        tileReplyNext := 1.U
        tileReplyValid := true.B
        closeIndex := closeIndex + 1.U
        state := tileReply
      }.otherwise { closeIndex := closeIndex + 1.U }
    }
    is(close) { when(pool.io.end.fire) { state := poolWait } }
    is(done) { when(io.completion.fire) { state := idle } }
  }
  when(pool.io.completion.fire) {
    val poolStatus = pool.io.completion.bits.status
    val base = Mux(effectiveStatus === PrimitiveBinStatus.success.U,
      poolStatus, effectiveStatus)
    result.identity := descriptor.pool.identity
    result.status := Mux(base === PrimitiveBinStatus.success.U, passStatus, base)
    result.tilesOpened := PopCount(opened.asUInt).pad(16)
    result.allocatedPages := pool.io.completion.bits.allocatedPages
    result.committedCount := totalCommitted
    state := done
  }

  // A commit can acknowledge after cancellation while its irrevocable store
  // drains. Preserve that physical commit exactly once, then fail the pass.
  private val commitAcknowledged = commitLive && io.mem.resp.fire && !io.mem.responseFault &&
    io.mem.resp.bits.addr === workPage + PrimitiveBinPageLayout.countOffset.U
  io.committed := commitAcknowledged
  when(stop && state =/= idle && state =/= binding && state =/= done &&
      state =/= reply && state =/= abortDrain && state =/= poolWait &&
      state =/= tileReply && state =/= scan) { state := abortDrain }
  when(commitAcknowledged) {
    commitLive := false.B
    count(currentIdx) := currentCount + 1.U
    totalCommitted := totalCommitted + 1.U
    pageCount(currentIdx) := currentPageCount + 1.U
    lastSequence(currentIdx) := current.reference.sequence
    offerReply(PrimitiveBinStatus.success.U, recordAddress, currentCount + 1.U)
  }
  when(faultNow) {
    passStatus := PrimitiveBinStatus.memoryFault.U
    aborting := true.B
  }.elsewhen(io.cancel && state =/= idle && state =/= done && !badDescriptor) {
    when(passStatus =/= PrimitiveBinStatus.memoryFault.U) {
      passStatus := PrimitiveBinStatus.cancelled.U
    }
    aborting := true.B
  }
}
