package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Immutable primitive/state arena bound to one pass.
  *
  * Handles are arena-relative 64-byte line ordinals, so `handle` zero is the
  * line at `base`. A handle outside `[0,(limit-base)/64)` is rejected before
  * any memory request, and lines are never read across the arena bound.
  */
class PrimitiveStateDescriptor extends Bundle {
  val version = UInt(8.W)
  val identity = new GraphicsWorkIdentity
  val base = UInt(32.W)
  /** Exclusive bound; 4 GiB is representable without wrapping to zero. */
  val limit = UInt(33.W)
}

object PrimitiveStateStatus {
  val width = 3
  val success = 0
  val invalidDescriptor = 1
  val cancelled = 2
  val memoryFault = 4
  val invalidHandle = 5
}

class PrimitiveStateLookup extends Bundle {
  val handle = UInt(32.W)
}

/** One 32-bit word of a resolved line. `last` marks the final word of a
  * successful lookup; a cancelled or faulted lookup emits no `last` word and
  * is reported through its completion instead.
  */
class PrimitiveStateWord extends Bundle {
  val handle = UInt(32.W)
  val index = UInt(4.W)
  val data = UInt(32.W)
  val last = Bool()
}

class PrimitiveStateLookupCompletion extends Bundle {
  val handle = UInt(32.W)
  val status = UInt(PrimitiveStateStatus.width.W)
  val words = UInt(5.W)
  val hit = Bool()
}

class PrimitiveStateSummary extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(PrimitiveStateStatus.width.W)
  val lookups = UInt(32.W)
  val hits = UInt(32.W)
  val misses = UInt(32.W)
  val faults = UInt(32.W)
  val readLines = UInt(32.W)
  val words = UInt(32.W)
}

/** Bounded direct-mapped cache for immutable primitive and draw-state records.
  *
  * Each 64-byte line is fetched once per residency and then served as sixteen
  * ordered 32-bit words. Lookups are admitted one at a time and complete in
  * order. A miss holds the lookup stream until its tagged read returns; a
  * faulted line is reported as `memoryFault` rather than replacing the record
  * with zero words. `invalidate` drops resident lines for one or more rebinds
  * without touching a fill already offered; each pass starts with no residents,
  * so a handle cannot hit data retained from an earlier identity.
  *
  * One tagged read may be outstanding. Cancel stops admitting lookups, drains
  * the offered read and any offered word, and reports the active lookup (if
  * any) as cancelled before the pass summary. End is accepted only after the
  * last word and lookup completion are accepted. The caller must publish
  * primitive/state stores to this memory domain and discard outputs from a
  * failed pass.
  *
  * This block does not schedule tile engines or own page/bin descriptors.
  */
class PrimitiveStateCache(
  config: GpuConfig = GpuConfig(),
  entries: Int = 8,
  memoryTransactions: Int = 8,
  transactionId: Int = 0
) extends Module {
  require(config.xLen == 32)
  require(entries >= 1 && entries <= 32 && (entries & (entries-1)) == 0)
  require(transactionId >= 0 && transactionId < memoryTransactions)
  val lineBytes = 64
  private val wordsPerLine = lineBytes / 4
  private val indexBits = log2Ceil(entries).max(1)
  private val wordBits = log2Ceil(wordsPerLine)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new PrimitiveStateDescriptor))
    val lookup = Flipped(Decoupled(new PrimitiveStateLookup))
    val word = Decoupled(new PrimitiveStateWord)
    val lookupDone = Decoupled(new PrimitiveStateLookupCompletion)
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    val invalidate = Input(Bool())
    val completion = Decoupled(new PrimitiveStateSummary)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,lineBytes,memoryTransactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(lineBytes,memoryTransactions)))
    val busy = Output(Bool())
  })
  private val Seq(idle,check,run,offer,waitLine,done) = Enum(6)
  private val state = RegInit(idle)
  private val descriptor = Reg(new PrimitiveStateDescriptor)
  private val lineCount = Reg(UInt(33.W))
  // Word-major storage keeps the word-select mux narrow: one 32-bit word is
  // read from the indexed entry instead of a 512-bit line mux per cycle.
  private val data = Reg(Vec(wordsPerLine, Vec(entries, UInt(32.W))))
  private val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val tag = Reg(Vec(entries, UInt(32.W)))
  private val lookupHandle = Reg(UInt(32.W))
  private val lookupIndex = Reg(UInt(indexBits.W))
  private val lookupHit = Reg(Bool())
  private val serveValid = RegInit(false.B)
  private val serveWord = Reg(UInt(wordBits.W))
  private val served = RegInit(0.U(5.W))
  private val lookupValid = RegInit(false.B)
  private val lookupResult = Reg(new PrimitiveStateLookupCompletion)
  private val fillIndex = Reg(UInt(indexBits.W))
  private val fillHandle = Reg(UInt(32.W))
  private val requestAddress = Reg(UInt(32.W))
  private val active = RegInit(false.B)
  private val terminal = RegInit(false.B)
  private val endPending = RegInit(false.B)
  private val cancelled = RegInit(false.B)
  private val summaryValid = RegInit(false.B)
  private val passStatus = RegInit(PrimitiveStateStatus.success.U(PrimitiveStateStatus.width.W))
  private val result = Reg(new PrimitiveStateSummary)
  private val lookups = RegInit(0.U(32.W))
  private val hits = RegInit(0.U(32.W))
  private val misses = RegInit(0.U(32.W))
  private val faults = RegInit(0.U(32.W))
  private val readLines = RegInit(0.U(32.W))
  private val wordsOut = RegInit(0.U(32.W))
  private val stop = cancelled || io.cancel
  private val faultNow = io.memoryResponse.fire && io.memoryResponse.bits.fault
  private def effectiveStatus: UInt = Mux(stop && passStatus === PrimitiveStateStatus.success.U,
    PrimitiveStateStatus.cancelled.U,passStatus)
  private def mergeStatus(a: UInt,b: UInt): UInt =
    Mux(a === PrimitiveStateStatus.memoryFault.U || b === PrimitiveStateStatus.memoryFault.U,
      PrimitiveStateStatus.memoryFault.U,
      Mux(a === PrimitiveStateStatus.cancelled.U || b === PrimitiveStateStatus.cancelled.U,
        PrimitiveStateStatus.cancelled.U,
        Mux(a === PrimitiveStateStatus.success.U,b,a)))

  io.begin.ready := state === idle && !io.cancel
  io.lookup.ready := state === run && active && !terminal && !endPending &&
    !stop && !serveValid && !lookupValid
  io.end.ready := state === run && active && !terminal && !endPending && !stop &&
    !serveValid && !lookupValid
  io.busy := state =/= idle
  io.completion.valid := summaryValid
  io.completion.bits := result
  io.word.valid := serveValid
  io.word.bits.handle := lookupHandle
  io.word.bits.index := serveWord
  io.word.bits.data := data(serveWord)(lookupIndex)
  io.word.bits.last := serveWord === (wordsPerLine-1).U
  io.lookupDone.valid := lookupValid
  io.lookupDone.bits := lookupResult

  when(io.begin.fire) {
    descriptor := io.begin.bits
    active := true.B
    terminal := false.B
    endPending := false.B
    cancelled := false.B
    summaryValid := false.B
    passStatus := PrimitiveStateStatus.success.U
    lookups := 0.U; hits := 0.U; misses := 0.U; faults := 0.U
    readLines := 0.U; wordsOut := 0.U
    serveValid := false.B
    lookupValid := false.B
    // Residency is pass-scoped: a prior pass's identity must never hit.
    for (e <- 0 until entries) { valid(e) := false.B }
    state := check
  }
  // An explicit invalidation drops resident lines mid-pass. A fill already
  // offered still lands, because the consumer's ownership of that read may
  // not be withdrawn.
  when(io.invalidate) {
    for (e <- 0 until entries) { valid(e) := false.B }
  }
  switch(state) {
    is(check) {
      val legal = descriptor.version === 1.U &&
        (descriptor.base & (lineBytes-1).U) === 0.U &&
        (descriptor.limit & (lineBytes-1).U(33.W)) === 0.U &&
        descriptor.base.pad(33) <= descriptor.limit &&
        descriptor.limit <= (BigInt(1)<<32).U(33.W)
      when(!legal) {
        passStatus := PrimitiveStateStatus.invalidDescriptor.U
        terminal := true.B
        lineCount := 0.U
        state := run
      }.otherwise {
        lineCount := (descriptor.limit - descriptor.base.pad(33)) >> 6
        state := run
      }
    }
    is(run) {
      when(io.lookup.fire) {
        val handle = io.lookup.bits.handle
        val idx = handle(indexBits-1,0)
        val inRange = handle.pad(33) < lineCount
        val hit = valid(idx) && tag(idx) === handle
        lookupHandle := handle
        lookupIndex := idx
        served := 0.U
        lookups := lookups + 1.U
        when(!inRange) {
          lookupResult.handle := handle
          lookupResult.status := PrimitiveStateStatus.invalidHandle.U
          lookupResult.words := 0.U
          lookupResult.hit := false.B
          lookupValid := true.B
          passStatus := mergeStatus(passStatus,PrimitiveStateStatus.invalidHandle.U)
        }.elsewhen(hit) {
          hits := hits + 1.U
          lookupHit := true.B
          serveWord := 0.U
          serveValid := true.B
        }.otherwise {
          misses := misses + 1.U
          lookupHit := false.B
          fillIndex := idx
          fillHandle := handle
          requestAddress := (descriptor.base.pad(34) +
            (handle << 6).pad(34))(31,0)
          state := offer
        }
      }
    }
    is(offer) {
      when(io.memoryRequest.fire) {
        readLines := readLines + 1.U
        state := waitLine
      }
    }
    is(waitLine) {
      when(io.memoryResponse.fire) {
        val bits = io.memoryResponse.bits
        when(bits.fault) {
          faults := faults + 1.U
          passStatus := PrimitiveStateStatus.memoryFault.U
          terminal := true.B
          lookupResult.handle := fillHandle
          lookupResult.status := PrimitiveStateStatus.memoryFault.U
          lookupResult.words := 0.U
          lookupResult.hit := false.B
          lookupValid := true.B
          serveValid := false.B
          state := run
        }.elsewhen(stop) {
          lookupResult.handle := fillHandle
          lookupResult.status := PrimitiveStateStatus.cancelled.U
          lookupResult.words := 0.U
          lookupResult.hit := false.B
          lookupValid := true.B
          serveValid := false.B
          state := run
        }.otherwise {
          for (w <- 0 until wordsPerLine) {
            data(w)(fillIndex) := bits.readData(32*w+31,32*w)
          }
          tag(fillIndex) := fillHandle
          valid(fillIndex) := true.B
          serveWord := 0.U
          serveValid := true.B
          state := run
        }
      }
    }
  }
  when(io.word.fire) {
    wordsOut := wordsOut + 1.U
    served := served + 1.U
    when(stop) {
      serveValid := false.B
      lookupResult.handle := lookupHandle
      lookupResult.status := PrimitiveStateStatus.cancelled.U
      lookupResult.words := served + 1.U
      lookupResult.hit := lookupHit
      lookupValid := true.B
    }.elsewhen(serveWord === (wordsPerLine-1).U) {
      serveValid := false.B
      lookupResult.handle := lookupHandle
      lookupResult.status := PrimitiveStateStatus.success.U
      lookupResult.words := served + 1.U
      lookupResult.hit := lookupHit
      lookupValid := true.B
    }.otherwise {
      serveWord := serveWord + 1.U
    }
  }
  when(io.lookupDone.fire) { lookupValid := false.B }
  when(io.end.fire) { endPending := true.B }
  when(io.cancel && active && state =/= idle && state =/= done && !cancelled) {
    cancelled := true.B
    when(passStatus === PrimitiveStateStatus.success.U && !faultNow) {
      passStatus := PrimitiveStateStatus.cancelled.U
    }
  }
  // The pass summary waits for the offered word, lookup completion and any
  // owned read; those cannot be withdrawn once offered.
  private val drained = !serveValid && !lookupValid && state =/= offer && state =/= waitLine
  when(active && (endPending || cancelled || terminal) && drained && !summaryValid) {
    result.identity := descriptor.identity
    result.status := effectiveStatus
    result.lookups := lookups
    result.hits := hits
    result.misses := misses
    result.faults := faults
    result.readLines := readLines
    result.words := wordsOut
    summaryValid := true.B
    state := done
  }
  when(summaryValid && io.completion.fire) {
    summaryValid := false.B
    active := false.B
    state := idle
  }
  // Request and response sinks are driven unconditionally so no path depends
  // on a state that may be unreachable after reset.
  io.memoryRequest.valid := state === offer
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := requestAddress
  io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  io.memoryRequest.bits.transactionId := transactionId.U
  io.memoryResponse.ready := state === waitLine &&
    io.memoryResponse.bits.transactionId === transactionId.U
}
