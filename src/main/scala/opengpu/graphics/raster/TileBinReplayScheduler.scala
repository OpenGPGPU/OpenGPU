package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Pass-level arena binding shared by every replay engine. */
class TileBinReplayDescriptor extends Bundle {
  val version = UInt(8.W)
  val pool = new BinPagePoolDescriptor
}

/** One tile replay result. `status` is the replay error if one occurred;
  * otherwise it retains the directory status, so an exhausted build reports
  * `exhausted` even when its committed prefix replays cleanly.
  */
class TileBinReplayCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val engine = UInt(8.W)
  val status = UInt(PrimitiveBinStatus.width.W)
  val prefixUsable = Bool()
  /** Exact first uncommitted reference when a usable prefix exhausted its
    * backing arena. It must survive every replay/resolution wrapper.
    */
  val restartValid = Bool()
  val restart = new BinPrimitiveReference
  val emitted = UInt(32.W)
  val pages = UInt(32.W)
  val readLines = UInt(32.W)
}

class TileBinReplaySummary extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(PrimitiveBinStatus.width.W)
  val tiles = UInt(32.W)
  val references = UInt(32.W)
  val pages = UInt(32.W)
  val readLines = UInt(32.W)
}

/** Distribute completed tile chains across independently progressing readers.
  *
  * Each engine owns a two-entry job queue (running plus one buffered job) and
  * its own PrimitiveBinReader, so one engine's completion, backpressure or
  * fault does not stop the other. Engines pull jobs from the pass-level input
  * in round-robin order; a shared memory port arbitrates their reads.
  *
  * Tiles with no usable prefix are reported without memory traffic. Cancel
  * stops dispatch, drains every started reader (irrevocable references still
  * leave that engine), reports unstarted jobs as cancelled, and consumes tile
  * completions still offered by the producer as cancelled results so a
  * directory can finish reporting before the pass summary. The caller must drain every per-tile completion before `end` can
  * be accepted and must keep the arena owned until the summary.
  *
  * This block does not fetch primitive/state handles, integrate a cache
  * domain or run the downstream tile engines.
  */
class TileBinReplayScheduler(
  config: GpuConfig = GpuConfig(),
  engines: Int = 2,
  pageBytes: Int = 4096,
  memoryTransactions: Int = 0,
  transactionBase: Int = 0,
  externalDispatchCredits: Boolean = false
) extends Module {
  require(config.xLen == 32)
  require(engines >= 1 && engines <= 4)
  require(pageBytes >= 64 && pageBytes <= 65536 && (pageBytes & (pageBytes-1)) == 0)
  private val txCount = if (memoryTransactions == 0) engines else memoryTransactions
  require(transactionBase >= 0 && transactionBase + engines <= txCount)
  private val engineBits = log2Ceil(engines).max(1)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TileBinReplayDescriptor))
    val tile = Flipped(Decoupled(new TileBinCompletion))
    val tileDone = Decoupled(new TileBinReplayCompletion)
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    /** No further tile completions will be offered. Cancellation uses this to
      * summarize only after a directory has reported its last tile. */
    val producerDone = Input(Bool())
    val completion = Decoupled(new TileBinReplaySummary)
    val reference = Vec(engines, Decoupled(new PrimitiveBinReference))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,txCount))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,txCount)))
    val busy = Output(Bool())
    val engineBusy = Output(Vec(engines, Bool()))
    val dispatchPermit = if (externalDispatchCredits) Some(Input(Vec(engines,Bool()))) else None
    val tileAssigned = if (externalDispatchCredits) Some(Output(Vec(engines,Bool()))) else None
  })
  private val descriptor = Reg(new TileBinReplayDescriptor)
  private val active = RegInit(false.B)
  private val aborting = RegInit(false.B)
  private val endPending = RegInit(false.B)
  private val summaryValid = RegInit(false.B)
  private val passStatus = RegInit(PrimitiveBinStatus.success.U(PrimitiveBinStatus.width.W))
  private val result = Reg(new TileBinReplaySummary)
  private val dispatchIndex = RegInit(0.U(engineBits.W))
  private val doneIndex = RegInit(0.U(engineBits.W))
  private val tilesCompleted = RegInit(0.U(32.W))
  private val totalReferences = RegInit(0.U(32.W))
  private val totalPages = RegInit(0.U(32.W))
  private val totalLines = RegInit(0.U(32.W))
  // Per-engine running and buffered jobs. The done register holds a result
  // until the consumer accepts it, preserving per-engine completion order.
  private val curValid = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val cur = Reg(Vec(engines, new TileBinCompletion))
  private val curStarted = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val nextValid = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val next = Reg(Vec(engines, new TileBinCompletion))
  private val doneValid = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val done = Reg(Vec(engines, new TileBinReplayCompletion))
  private val readers = Seq.tabulate(engines)(e =>
    Module(new PrimitiveBinReader(config,pageBytes,txCount,transactionBase+e)))
  private val arbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(config,64,txCount),engines))
  // Reader arrivals may change arbitration priority while external memory
  // withholds ready. Freeze the selected request/ID before offering it on
  // the public memory port; the reader retains its response credit until
  // that request and its response drain, including cancellation.
  private val requests = Module(new Queue(
    new ComputeMemoryRequest(config,64,txCount),2,pipe=true))
  private val stop = aborting || io.cancel
  private val engineIdBits = 8

  io.begin.ready := !active && !summaryValid && !io.cancel
  io.end.ready := active && !io.tile.valid && !summaryValid && !endPending &&
    (0 until engines).map(e => !curValid(e) && !nextValid(e) &&
      !doneValid(e) && !readers(e).io.busy).reduce(_ && _)
  io.busy := active
  io.completion.valid := summaryValid
  io.completion.bits := result
  io.tileDone.valid := doneValid.asUInt.orR
  for (e <- 0 until engines) {
    io.engineBusy(e) := curValid(e) || nextValid(e) || readers(e).io.busy
    arbiter.io.in(e) <> readers(e).io.memoryRequest
    readers(e).io.cancel := active && stop
    readers(e).io.memoryResponse.valid := io.memoryResponse.valid &&
      io.memoryResponse.bits.transactionId === (transactionBase+e).U
    readers(e).io.memoryResponse.bits := io.memoryResponse.bits
  }
  requests.io.enq <> arbiter.io.out
  io.memoryRequest <> requests.io.deq
  io.memoryResponse.ready := readers.map(_.io.memoryResponse.ready).reduce(_ || _)

  // Pick the first free engine, rotating the starting point so neither engine
  // is preferred when both have room.
  private def rotate(start: UInt, offset: Int): UInt =
    ((start +& offset.U) % engines.U)(engineBits-1,0)
  private def permitted(e: Int): Bool = io.dispatchPermit.map(_(e)).getOrElse(true.B)
  private val free = VecInit((0 until engines).map(e => !nextValid(e) && permitted(e)))
  private val choices = (0 until engines).map(i => rotate(dispatchIndex,i))
  private val pick = PriorityMux(choices.map(e => free(e) -> e))
  private val doneOrder = (0 until engines).map(i => rotate(doneIndex,i))
  private val donePickRaw = PriorityMux(doneOrder.map(e => doneValid(e) -> e))
  // The offered completion must not change while the consumer withholds
  // ready: latch the first selected slot until it is accepted, even if an
  // earlier slot in rotation becomes valid later.
  private val doneHeadValid = RegInit(false.B)
  private val doneHead = RegInit(0.U(engineBits.W))
  private val doneAny = doneValid.asUInt.orR
  private val donePick = Mux(doneHeadValid, doneHead, donePickRaw)
  // During cancellation a producer may still offer completed tiles; they are
  // accepted into a free done slot as cancelled so the producer can finish
  // reporting. The slot must not also receive a reader or local completion.
  private val discardFree = VecInit((0 until engines).map(e => !doneValid(e) && permitted(e)))
  private val discardPick = PriorityMux(doneOrder.map(e => discardFree(e) -> e))
  private val localCompleting = (0 until engines).map(e =>
    curValid(e) && !curStarted(e) && !doneValid(e) && (stop || !playable(cur(e)))).reduce(_ || _)
  private val readerCompleting = readers.map(_.io.completion.fire).reduce(_ || _)
  private val canDiscard = discardFree.asUInt.orR && !localCompleting && !readerCompleting
  io.tile.ready := active && Mux(stop, canDiscard, free.asUInt.orR)
  io.tileAssigned.foreach { assigned=>
    for (e <- 0 until engines) assigned(e):=io.tile.fire && Mux(stop,discardPick,pick)===e.U
  }
  private def playable(job: TileBinCompletion): Bool =
    job.prefixUsable && job.headValid &&
      (job.status === PrimitiveBinStatus.success.U ||
        job.status === PrimitiveBinStatus.exhausted.U)
  private def stopStatus: UInt = Mux(passStatus === PrimitiveBinStatus.memoryFault.U,
    PrimitiveBinStatus.memoryFault.U, PrimitiveBinStatus.cancelled.U)
  private def mergeStatus(a: UInt, b: UInt): UInt =
    Mux(a === PrimitiveBinStatus.memoryFault.U || b === PrimitiveBinStatus.memoryFault.U,
      PrimitiveBinStatus.memoryFault.U,
      Mux(a === PrimitiveBinStatus.cancelled.U || b === PrimitiveBinStatus.cancelled.U,
        PrimitiveBinStatus.cancelled.U,
        Mux(a === PrimitiveBinStatus.success.U, b, a)))

  when(io.begin.fire) {
    descriptor := io.begin.bits
    active := true.B
    aborting := false.B
    endPending := false.B
    passStatus := PrimitiveBinStatus.success.U
    dispatchIndex := 0.U
    doneIndex := 0.U
    tilesCompleted := 0.U
    totalReferences := 0.U
    totalPages := 0.U
    totalLines := 0.U
    doneHeadValid := false.B
    for (e <- 0 until engines) {
      curValid(e) := false.B
      curStarted(e) := false.B
      nextValid(e) := false.B
      doneValid(e) := false.B
    }
  }
  // Reader inputs are driven even while the pass is inactive so every sink is
  // unconditionally initialized.
  for (e <- 0 until engines) {
    val reader = readers(e)
    reader.io.begin.valid := active && !stop && curValid(e) && !curStarted(e) &&
      playable(cur(e))
    reader.io.begin.bits.version := 1.U
    reader.io.begin.bits.pool := descriptor.pool
    reader.io.begin.bits.tile := cur(e).tile
    reader.io.begin.bits.headValid := cur(e).headValid
    reader.io.begin.bits.head := cur(e).head
    reader.io.begin.bits.count := cur(e).committedCount
    reader.io.completion.ready := !doneValid(e)
    io.reference(e).valid := reader.io.reference.valid
    io.reference(e).bits := reader.io.reference.bits
    reader.io.reference.ready := io.reference(e).ready
  }
  when(active) {
    when(io.tile.fire) {
      when(stop) {
        done(discardPick).identity := io.tile.bits.identity
        done(discardPick).tile := io.tile.bits.tile
        done(discardPick).engine := discardPick
        done(discardPick).status := stopStatus
        done(discardPick).prefixUsable := false.B
        done(discardPick).restartValid := false.B
        done(discardPick).restart := 0.U.asTypeOf(done(discardPick).restart)
        done(discardPick).emitted := 0.U
        done(discardPick).pages := 0.U
        done(discardPick).readLines := 0.U
        doneValid(discardPick) := true.B
      }.elsewhen(!curValid(pick)) {
        cur(pick) := io.tile.bits
        curValid(pick) := true.B
        curStarted(pick) := false.B
      }.otherwise {
        next(pick) := io.tile.bits
        nextValid(pick) := true.B
      }
      dispatchIndex := Mux(pick === (engines-1).U, 0.U, pick + 1.U)
    }
    // A free running slot takes the buffered job.
    for (e <- 0 until engines) {
      when(!curValid(e) && nextValid(e)) {
        cur(e) := next(e)
        curValid(e) := true.B
        curStarted(e) := false.B
        nextValid(e) := false.B
      }
    }
    for (e <- 0 until engines) {
      val reader = readers(e)
      // A job that cannot be replayed, or any job when stopping, completes
      // locally without touching memory.
      when(curValid(e) && !curStarted(e) && !doneValid(e) &&
          (stop || !playable(cur(e)))) {
        done(e).identity := cur(e).identity
        done(e).tile := cur(e).tile
        done(e).engine := e.U(engineIdBits.W)
        done(e).status := Mux(stop, stopStatus, cur(e).status)
        done(e).prefixUsable := cur(e).prefixUsable && !stop
        done(e).restartValid := !stop && cur(e).prefixUsable && cur(e).restartValid &&
          cur(e).status === PrimitiveBinStatus.exhausted.U
        done(e).restart := cur(e).restart
        done(e).emitted := 0.U
        done(e).pages := 0.U
        done(e).readLines := 0.U
        doneValid(e) := true.B
        curValid(e) := false.B
      }
      when(reader.io.begin.fire) { curStarted(e) := true.B }
      when(reader.io.completion.fire) {
        assert(reader.io.completion.bits.identity.asUInt === descriptor.pool.identity.asUInt)
        assert(reader.io.completion.bits.tile === cur(e).tile)
        done(e).identity := reader.io.completion.bits.identity
        done(e).tile := reader.io.completion.bits.tile
        done(e).engine := e.U(engineIdBits.W)
        done(e).status := Mux(reader.io.completion.bits.status === PrimitiveBinStatus.success.U,
          cur(e).status, reader.io.completion.bits.status)
        done(e).prefixUsable := cur(e).prefixUsable &&
          reader.io.completion.bits.status === PrimitiveBinStatus.success.U
        done(e).restartValid := cur(e).prefixUsable && cur(e).restartValid &&
          cur(e).status === PrimitiveBinStatus.exhausted.U &&
          reader.io.completion.bits.status === PrimitiveBinStatus.success.U
        done(e).restart := cur(e).restart
        done(e).emitted := reader.io.completion.bits.emitted
        done(e).pages := reader.io.completion.bits.pages
        done(e).readLines := reader.io.completion.bits.readLines
        doneValid(e) := true.B
        curValid(e) := false.B
        curStarted(e) := false.B
      }
    }
  }
  io.tileDone.bits := done(donePick)
  when(io.tileDone.fire) {
    doneHeadValid := false.B
    doneValid(donePick) := false.B
    doneIndex := Mux(donePick === (engines-1).U, 0.U, donePick + 1.U)
    tilesCompleted := tilesCompleted + 1.U
    totalReferences := totalReferences + io.tileDone.bits.emitted
    totalPages := totalPages + io.tileDone.bits.pages
    totalLines := totalLines + io.tileDone.bits.readLines
    passStatus := mergeStatus(passStatus, io.tileDone.bits.status)
  }.elsewhen(doneAny && !doneHeadValid) {
    doneHeadValid := true.B
    doneHead := donePickRaw
  }
  when(io.end.fire) { endPending := true.B }
  private val enginesEmpty = (0 until engines).map(e => !curValid(e) && !nextValid(e) &&
    !doneValid(e) && !readers(e).io.busy).reduce(_ && _)
  // Cancellation abandons the remaining work, so a drained pass reports its
  // summary without requiring a later end. It still waits for the producer to
  // finish offering tile results so no late completion is omitted from the
  // counts.
  when(active && (endPending || (aborting && io.producerDone)) && enginesEmpty &&
      !summaryValid && !io.tile.valid) {
    result.identity := descriptor.pool.identity
    result.status := Mux(stop,mergeStatus(passStatus,PrimitiveBinStatus.cancelled.U),passStatus)
    result.tiles := tilesCompleted
    result.references := totalReferences
    result.pages := totalPages
    result.readLines := totalLines
    summaryValid := true.B
    endPending := false.B
  }
  when(summaryValid && io.completion.fire) {
    summaryValid := false.B
    active := false.B
  }
  when(active && io.cancel && !aborting) {
    aborting := true.B
    // Include a completion accepted on this edge before merging cancellation,
    // so a simultaneous memory fault retains priority.
    val completedStatus = Mux(io.tileDone.fire,
      mergeStatus(passStatus,io.tileDone.bits.status),passStatus)
    passStatus := mergeStatus(completedStatus,PrimitiveBinStatus.cancelled.U)
  }
}
