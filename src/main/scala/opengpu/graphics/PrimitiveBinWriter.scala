package opengpu.graphics

import chisel3._
import chisel3.util._

/** Explicit word ABI; independent of Chisel Bundle packing. */
object PrimitiveBinPageLayout {
  val magic = BigInt("47424901", 16)
  val headerWords = 8
  val headerBytes = 32
  val recordWords = 4
  val recordBytes = 16
  val nextOffset = 4
  val countOffset = 8
  def recordsPerPage(bytes: Int): Int = (bytes - headerBytes) / recordBytes
}

class BinPrimitiveReference extends Bundle {
  /** Opaque handles to immutable primitive and draw-state records. */
  val primitive = UInt(32.W)
  val state = UInt(32.W)
  val sequence = UInt(32.W)
  val token = UInt(32.W)
}

class PrimitiveBinDescriptor extends Bundle {
  val version = UInt(8.W)
  val pool = new BinPagePoolDescriptor
  val tile = UInt(32.W)
}

object PrimitiveBinStatus {
  val width = 3
  val success = 0
  val invalidDescriptor = 1
  val cancelled = 2
  val exhausted = 3
  val memoryFault = 4
  val invalidOrder = 5
  /** Append to a tile whose chain was already sealed. */
  val closed = 7
}

class PrimitiveBinAppendResult extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val reference = new BinPrimitiveReference
  val status = UInt(PrimitiveBinStatus.width.W)
  /** Valid only on success. Address zero is legal when explicitly successful. */
  val recordAddress = UInt(32.W)
  val committedCount = UInt(32.W)
}

class PrimitiveBinCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val status = UInt(PrimitiveBinStatus.width.W)
  /** Only success/exhaustion exposes a usable committed prefix. */
  val prefixUsable = Bool()
  val headValid = Bool()
  val head = UInt(32.W)
  val tail = UInt(32.W)
  val committedCount = UInt(32.W)
  val allocatedPages = UInt(32.W)
  val spillTransitions = UInt(4.W)
  val restartValid = Bool()
  val restart = new BinPrimitiveReference
}

/** Build one ordered, memory-backed bin chain in a bounded software arena.
  * A header is [magic,next,count,context,pass,generation,tile,pageOrdinal].
  * Each reference is [primitive,state,sequence,token]. Sequences may have gaps
  * but cannot repeat or wrap within a binding. Readers wait for completion.
  *
  * Header initialization precedes linking a page. All four reference stores
  * drain before its count store; that store's successful acknowledgement is
  * the commit point. Exhaustion leaves an exact committed prefix and returns
  * the rejected reference. Memory faults invalidate the whole binding, since
  * an errored store may have changed memory. Cancellation preserves offered
  * memory requests and allocation/append replies through drain.
  *
  * This is the page-writer component. Primitive/state caches, a multi-tile
  * directory, replay and spill/restart scheduling are separate integrations.
  */
class PrimitiveBinWriter(pageBytes: Int = 4096, writeCredits: Int = 8) extends Module {
  private val capacity = PrimitiveBinPageLayout.recordsPerPage(pageBytes)
  require(capacity > 0)
  private val entryBits = log2Ceil(capacity + 1).max(1)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new PrimitiveBinDescriptor))
    val append = Flipped(Decoupled(new BinPrimitiveReference))
    val appended = Decoupled(new PrimitiveBinAppendResult)
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    val completion = Decoupled(new PrimitiveBinCompletion)
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
  private val Seq(idle, binding, ready, check, allocate, allocationWait,
    header, headerWait, link, linkWait, payload, payloadWait, commit,
    commitWait, reply, abortDrain, close, poolWait, done) = Enum(19)
  private val state = RegInit(idle)
  private val descriptor = Reg(new PrimitiveBinDescriptor)
  private val current = Reg(new BinPrimitiveReference)
  private val pending = RegInit(false.B)
  private val replyValid = RegInit(false.B)
  private val replyBits = Reg(new PrimitiveBinAppendResult)
  private val result = Reg(new PrimitiveBinCompletion)
  private val passStatus = RegInit(PrimitiveBinStatus.success.U(PrimitiveBinStatus.width.W))
  private val terminal = RegInit(false.B)
  private val aborting = RegInit(false.B)
  private val allocationPending = RegInit(false.B)
  private val headValid = RegInit(false.B)
  private val headAddress = RegInit(0.U(32.W))
  private val page = RegInit(0.U(32.W))
  private val newPage = Reg(UInt(32.W))
  private val newOrdinal = Reg(UInt(32.W))
  private val pageCount = RegInit(0.U(entryBits.W))
  private val count = RegInit(0.U(32.W))
  private val lastSequence = Reg(UInt(32.W))
  private val wordIndex = RegInit(0.U(4.W))
  private val recordAddress = Reg(UInt(32.W))
  private val commitLive = RegInit(false.B)
  private val restart = Reg(new BinPrimitiveReference)
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
  private val stop = aborting || io.cancel || faultNow
  private val effectiveStatus = Mux(faultNow || passStatus === PrimitiveBinStatus.memoryFault.U,
    PrimitiveBinStatus.memoryFault.U, Mux(io.cancel || aborting,
      PrimitiveBinStatus.cancelled.U, passStatus))
  writes.io.abort := stop
  writes.io.begin := io.begin.fire
  writes.io.enqueue.valid := false.B
  writes.io.enqueue.bits := 0.U.asTypeOf(new TileAttachmentWrite)

  pool.io.begin.valid := state === idle && io.begin.valid && !io.cancel
  pool.io.begin.bits := io.begin.bits.pool
  pool.io.begin.bits.version := Mux(io.begin.bits.version === 1.U, io.begin.bits.pool.version, 0.U)
  io.begin.ready := state === idle && pool.io.begin.ready && !io.cancel
  pool.io.allocate.valid := state === allocate && !stop
  pool.io.allocate.bits.tile := descriptor.tile
  pool.io.allocate.bits.token := current.token
  pool.io.allocation.ready := state === allocationWait || state === abortDrain
  pool.io.cancel := stop && state =/= idle && state =/= done
  pool.io.end.valid := state === close && !stop
  pool.io.end.bits := true.B
  pool.io.completion.ready := (state === binding || state === poolWait) &&
    writes.io.drained && !pending && !allocationPending && !replyValid
  io.append.ready := state === ready && !terminal && !stop && !io.end.valid
  io.end.ready := state === ready && !stop
  io.appended.valid := replyValid
  io.appended.bits := replyBits
  io.completion.valid := state === done
  io.completion.bits := result
  io.busy := state =/= idle
  io.storeBytes := Mux(io.mem.req.fire, 4.U, 0.U)
  io.pageAllocated := pool.io.allocated

  private def offerReply(status: UInt, address: UInt, committed: UInt): Unit = {
    replyBits.identity := descriptor.pool.identity
    replyBits.tile := descriptor.tile
    replyBits.reference := current
    replyBits.status := status
    replyBits.recordAddress := address
    replyBits.committedCount := committed
    replyValid := true.B
    state := reply
  }
  private def store(address: UInt, data: UInt): Unit = {
    writes.io.enqueue.valid := !stop
    writes.io.enqueue.bits.address := address
    writes.io.enqueue.bits.data := data
  }
  private def startPayload(): Unit = {
    recordAddress := page + PrimitiveBinPageLayout.headerBytes.U + (pageCount << 4)
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
  switch(state) {
    is(idle) {
      when(io.begin.fire) {
        descriptor := io.begin.bits
        pending := false.B
        terminal := false.B
        aborting := false.B
        passStatus := PrimitiveBinStatus.success.U
        headValid := false.B
        headAddress := 0.U
        page := 0.U
        pageCount := 0.U
        count := 0.U
        commitLive := false.B
        state := binding
      }
    }
    is(binding) { when(pool.io.allocate.ready && !stop) { state := ready } }
    is(ready) {
      when(io.append.fire) { current := io.append.bits; pending := true.B; state := check }
      when(io.end.fire) { state := close }
    }
    is(check) {
      when(!stop) {
        when(count =/= 0.U && current.sequence <= lastSequence) {
          passStatus := PrimitiveBinStatus.invalidOrder.U
          terminal := true.B
          offerReply(PrimitiveBinStatus.invalidOrder.U, 0.U, count)
        }.elsewhen(!headValid || pageCount === capacity.U) {
          state := allocate
        }.otherwise { startPayload() }
      }
    }
    is(allocate) { when(pool.io.allocate.fire) { state := allocationWait } }
    is(allocationWait) {
      when(pool.io.allocation.fire && !stop) {
        assert(pool.io.allocation.bits.identity.asUInt === descriptor.pool.identity.asUInt)
        assert(pool.io.allocation.bits.request.tile === descriptor.tile &&
          pool.io.allocation.bits.request.token === current.token)
        when(pool.io.allocation.bits.status === BinPageStatus.success.U) {
          newPage := pool.io.allocation.bits.address
          newOrdinal := pool.io.allocation.bits.ordinal
          wordIndex := 0.U
          state := header
        }.otherwise {
          passStatus := PrimitiveBinStatus.exhausted.U
          terminal := true.B
          restart := current
          offerReply(PrimitiveBinStatus.exhausted.U, 0.U, count)
        }
      }
    }
    is(header) {
      val words = VecInit(Seq(PrimitiveBinPageLayout.magic.U(32.W), 0.U(32.W), 0.U(32.W),
        descriptor.pool.identity.context, descriptor.pool.identity.pass,
        descriptor.pool.identity.generation, descriptor.tile, newOrdinal))
      store(newPage + (wordIndex << 2), words(wordIndex(2, 0)))
      when(writes.io.enqueue.fire) {
        wordIndex := wordIndex + 1.U
        when(wordIndex === 7.U) { state := headerWait }
      }
    }
    is(headerWait) {
      when(writes.io.drained && !stop) {
        when(!headValid) {
          headAddress := newPage
          headValid := true.B
          page := newPage
          pageCount := 0.U
          state := check
        }.otherwise { state := link }
      }
    }
    is(link) {
      store(page + PrimitiveBinPageLayout.nextOffset.U, newPage)
      when(writes.io.enqueue.fire) { state := linkWait }
    }
    is(linkWait) {
      when(writes.io.drained && !stop) { page := newPage; pageCount := 0.U; state := check }
    }
    is(payload) {
      val words = VecInit(Seq(current.primitive, current.state, current.sequence, current.token))
      store(recordAddress + (wordIndex << 2), words(wordIndex(1, 0)))
      when(writes.io.enqueue.fire) {
        wordIndex := wordIndex + 1.U
        when(wordIndex === 3.U) { state := payloadWait }
      }
    }
    is(payloadWait) { when(writes.io.drained && !stop) { state := commit } }
    is(commit) {
      store(page + PrimitiveBinPageLayout.countOffset.U, pageCount + 1.U)
      when(writes.io.enqueue.fire) { commitLive := true.B; state := commitWait }
    }
    is(abortDrain) {
      when(writes.io.drained && !allocationPending) {
        commitLive := false.B
        when(pending && !replyValid) { offerReply(effectiveStatus, 0.U, count) }
          .elsewhen(!pending && !replyValid) { state := poolWait }
      }
    }
    is(close) { when(pool.io.end.fire) { state := poolWait } }
    is(done) { when(io.completion.fire) { state := idle } }
  }

  // A commit can acknowledge after cancellation while its irrevocable store
  // drains. Preserve that physical commit exactly once, then fail the binding.
  private val commitAcknowledged = commitLive && io.mem.resp.fire && !io.mem.responseFault &&
    io.mem.resp.bits.addr === page + PrimitiveBinPageLayout.countOffset.U
  io.committed := commitAcknowledged
  when(stop && state =/= idle && state =/= binding && state =/= done &&
      state =/= reply && state =/= abortDrain && state =/= poolWait) { state := abortDrain }
  when(commitAcknowledged) {
    commitLive := false.B
    count := count + 1.U
    pageCount := pageCount + 1.U
    lastSequence := current.sequence
    offerReply(PrimitiveBinStatus.success.U, recordAddress, count + 1.U)
  }
  when(faultNow) {
    passStatus := PrimitiveBinStatus.memoryFault.U
    aborting := true.B
  }.elsewhen(io.cancel && state =/= idle && state =/= done) {
    when(passStatus =/= PrimitiveBinStatus.memoryFault.U) { passStatus := PrimitiveBinStatus.cancelled.U }
    aborting := true.B
  }
  when(pool.io.completion.fire) {
    val status = Mux(effectiveStatus === PrimitiveBinStatus.success.U,
      pool.io.completion.bits.status, effectiveStatus)
    result.identity := descriptor.pool.identity
    result.tile := descriptor.tile
    result.status := status
    result.prefixUsable := status === PrimitiveBinStatus.success.U || status === PrimitiveBinStatus.exhausted.U
    result.headValid := headValid
    result.head := headAddress
    result.tail := page
    result.committedCount := count
    result.allocatedPages := pool.io.completion.bits.allocatedPages
    result.spillTransitions:=pool.io.completion.bits.spillTransitions
    result.restartValid := status === PrimitiveBinStatus.exhausted.U
    result.restart := restart
    state := done
  }
}
