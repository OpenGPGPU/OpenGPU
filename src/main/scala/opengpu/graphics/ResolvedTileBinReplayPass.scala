package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Version 1 handles name consecutive immutable 64-byte records. Handle
  * firstHandle+n resolves to records.base+64*n. Primitive and state handles
  * share this arena; the full handle interval must fit without 32-bit wrap.
  */
class ResolvedTileBinDescriptor extends Bundle {
  val version = UInt(8.W)
  val bins = new TileBinDirectoryDescriptor
  val records = new PrimitiveStateDescriptor
  val firstHandle = UInt(32.W)
}

object ResolvedTileBinStatus {
  // Keep every bin status, including invalidPage=6 and closed=7, distinct.
  val width = 4
  val invalidHandle = 8
}

/** Both records are complete before this irrevocable output is offered.
  * Record layout is deliberately opaque to replay; no failed fetch becomes
  * a successful record containing substitute words.
  */
class ResolvedPrimitiveBinReference(val primitiveLines: Int = 1) extends Bundle {
  val source = new PrimitiveBinReference
  val primitive = Vec(16 * primitiveLines, UInt(32.W))
  val state = Vec(16, UInt(32.W))
}

class ResolvedTileBinCompletion extends Bundle {
  val replay = new TileBinReplayCompletion
  val status = UInt(ResolvedTileBinStatus.width.W)
  val resolved = UInt(32.W)
}

class ResolvedTileBinPassCompletion extends Bundle {
  val replay = new TileBinReplayPassCompletion
  val status = UInt(ResolvedTileBinStatus.width.W)
  val resolved = UInt(32.W)
  val cacheLookups = UInt(32.W)
  val cacheHits = UInt(32.W)
  val cacheMisses = UInt(32.W)
  val recordReadLines = UInt(32.W)
}

/** Build, replay and resolve tile bins over one tagged memory client.
  *
  * Each engine has its own record cache and retirement port, so a stalled
  * record or tile result cannot stop another engine from resolving work.
  * Tile completion waits for that engine's last resolved output handshake.
  * Invalid handles drop the failed pair and mark the tile/pass; a record
  * memory fault poisons only that engine's cache until the next pass. Other
  * engines continue. Consumers must discard the outputs of failed tiles.
  *
  * Cancel drains offered reads and resolved outputs, discards unfinished
  * pairs, and retains ownership until every tile result and the pass summary
  * drain. Records must be immutable and published to this memory domain before
  * begin; this block does not implement cache publication or rasterization.
  */
class ResolvedTileBinReplayPass(
  config: GpuConfig = GpuConfig(),
  engines: Int = 2,
  pageBytes: Int = 4096,
  writeCredits: Int = 8,
  tileSlots: Int = 8,
  cacheEntries: Int = 8,
  primitiveLines: Int = 1,
  pagedCapacity: Int = 0,
  useDirectorySramBlackBoxes: Boolean = false
) extends Module {
  require(engines >= 1 && engines <= 4)
  require(primitiveLines >= 1 && primitiveLines <= 4)
  private val binTransactions = writeCredits + engines+(if (pagedCapacity > 0) 1 else 0)
  private val transactions = binTransactions + engines
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new ResolvedTileBinDescriptor))
    val append = Flipped(Decoupled(new TileBinAppend))
    val appended = Decoupled(new TileBinAppendResult)
    val seal = Flipped(Decoupled(UInt(32.W)))
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    val reference = Vec(engines, Decoupled(new ResolvedPrimitiveBinReference(primitiveLines)))
    val tileDone = Vec(engines, Decoupled(new ResolvedTileBinCompletion))
    val completion = Decoupled(new ResolvedTileBinPassCompletion)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val busy = Output(Bool())
  })
  private val replay = Module(new TileBinReplayPass(config,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,useDirectorySramBlackBoxes,externalDispatchCredits=pagedCapacity>0))
  private val caches = Seq.tabulate(engines)(e => Module(new PrimitiveStateCache(
    config,cacheEntries,transactions,binTransactions+e)))
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config,64,transactions),engines+1))
  private val requests = Module(new Queue(new ComputeMemoryRequest(config,64,transactions),2,pipe=true))
  private val Seq(idle,check,start,run,done) = Enum(5)
  private val phase = RegInit(idle)
  private val descriptor = Reg(new ResolvedTileBinDescriptor)
  private val legal = Reg(Bool())
  private val recordCount = Reg(UInt(33.W))
  private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel
  private val replayDone = RegInit(false.B)
  private val replayResult = Reg(new TileBinReplayPassCompletion)
  private val cacheDone = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val cacheResults = Reg(Vec(engines,new PrimitiveStateSummary))
  private val result = Reg(new ResolvedTileBinPassCompletion)
  private val Seq(waitReference,validate,primitiveLookup,primitiveWait,stateLookup,stateWait,emit) = Enum(7)
  private val enginePhase = RegInit(VecInit(Seq.fill(engines)(waitReference)))
  private val records = Reg(Vec(engines,new ResolvedPrimitiveBinReference(primitiveLines)))
  private val primitiveLine = if (primitiveLines > 1)
    Some(Reg(Vec(engines,UInt(log2Ceil(primitiveLines).W)))) else None
  private val lookupHandle = Reg(Vec(engines,UInt(32.W)))
  private val stateHandle = Reg(Vec(engines,UInt(32.W)))
  private val tileOwned = RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val tileId = Reg(Vec(engines,UInt(32.W)))
  private val tileStatus = RegInit(VecInit(Seq.fill(engines)(0.U(ResolvedTileBinStatus.width.W))))
  private val passStatus = RegInit(VecInit(Seq.fill(engines)(0.U(ResolvedTileBinStatus.width.W))))
  private val tileResolved = RegInit(VecInit(Seq.fill(engines)(0.U(32.W))))
  private val resolved = RegInit(VecInit(Seq.fill(engines)(0.U(32.W))))
  private val poisoned = RegInit(VecInit(Seq.fill(engines)(false.B)))
  // Resident passes have at most tileSlots tiles. Paged passes reserve the
  // same bounded per-engine result capacity before dispatch, so empty or
  // cancelled results cannot pin the shared replay port behind a stalled consumer.
  private val tileResults = Seq.fill(engines)(Module(new Queue(new TileBinReplayCompletion,tileSlots)))
  // Paged passes can exceed the bounded result queues. Reserve each result
  // before scheduler dispatch, through final resolved-result consumption.
  // Otherwise a full queue of empty results on one stalled engine can pin
  // the shared raw-result port and stop the other engine indefinitely.
  if (pagedCapacity > 0) {
    val reservations=RegInit(VecInit(Seq.fill(engines)(0.U(log2Ceil(tileSlots+1).max(1).W))))
    for (e <- 0 until engines) {
      replay.io.dispatchPermit.get(e):=reservations(e)<tileSlots.U
      val assigned=replay.io.tileAssigned.get(e)
      val retired=io.tileDone(e).fire
      when(io.begin.fire) { reservations(e):=0.U }
        .elsewhen(assigned && !retired) { reservations(e):=reservations(e)+1.U }
        .elsewhen(retired && !assigned) { reservations(e):=reservations(e)-1.U }
      when(assigned) { assert(reservations(e)<tileSlots.U) }
      when(retired) { assert(reservations(e)>0.U) }
    }
  }

  private def merge(a: UInt,b: UInt): UInt =
    Mux(a === PrimitiveBinStatus.memoryFault.U || b === PrimitiveBinStatus.memoryFault.U,
      PrimitiveBinStatus.memoryFault.U,
      Mux(a === PrimitiveBinStatus.cancelled.U || b === PrimitiveBinStatus.cancelled.U,
        PrimitiveBinStatus.cancelled.U,Mux(a === 0.U,b,a)))

  io.begin.ready := phase === idle && !io.cancel
  io.busy := phase =/= idle
  io.completion.valid := phase === done
  io.completion.bits := result
  when(io.begin.fire) {
    descriptor := io.begin.bits
    cancelled := false.B
    replayDone := false.B
    phase := check
    for (e <- 0 until engines) {
      cacheDone(e) := false.B
      enginePhase(e) := waitReference
      tileOwned(e) := false.B
      tileStatus(e) := 0.U; passStatus(e) := 0.U
      tileResolved(e) := 0.U; resolved(e) := 0.U
      poisoned(e) := false.B
    }
  }
  when(phase === check) {
    val r = descriptor.records
    val count = (r.limit - r.base.pad(33)) >> 6
    recordCount := count
    legal := descriptor.version === 1.U && r.version === 1.U &&
      r.identity.asUInt === descriptor.bins.pool.identity.asUInt &&
      r.base(5,0) === 0.U && r.limit(5,0) === 0.U &&
      r.limit >= r.base.pad(33) && r.limit <= (BigInt(1)<<32).U &&
      (count === 0.U || descriptor.bins.pool.base.pad(33) === descriptor.bins.pool.limit ||
        r.limit <= descriptor.bins.pool.base.pad(33) || descriptor.bins.pool.limit <= r.base.pad(33)) &&
      (if (pagedCapacity == 0) true.B else
        r.limit <= descriptor.bins.directoryBase.pad(33) || descriptor.bins.directoryLimit <= r.base.pad(33)) &&
      descriptor.firstHandle.pad(34) + count.pad(34) <= (BigInt(1)<<32).U
    phase := start
  }
  private val allReady = replay.io.begin.ready && caches.map(_.io.begin.ready).reduce(_ && _)
  private val launch = phase === start && allReady
  replay.io.begin.valid := launch
  replay.io.begin.bits := descriptor.bins
  replay.io.begin.bits.version := Mux(legal,descriptor.bins.version,0.U)
  when(launch) { phase := run }
  // A cancellation during descriptor checking is retained through child begin.
  // Children receive it only after the atomic begin, so none can be stranded.
  when(io.cancel && phase =/= idle && phase =/= done) { cancelled := true.B }
  replay.io.cancel := phase === run && stop
  replay.io.append <> io.append
  io.appended <> replay.io.appended
  replay.io.seal <> io.seal
  replay.io.end <> io.end
  replay.io.completion.ready := !replayDone
  when(replay.io.completion.fire) {
    replayDone := true.B
    replayResult := replay.io.completion.bits
  }
  replay.io.tileDone.ready := false.B

  for (e <- 0 until engines) {
    val cache = caches(e)
    cache.io.begin.valid := launch
    cache.io.begin.bits := descriptor.records
    cache.io.begin.bits.version := Mux(legal,descriptor.records.version,0.U)
    cache.io.invalidate := false.B
    cache.io.cancel := phase === run && stop
    cache.io.end.valid := phase === run && replayDone && !cacheDone(e) &&
      enginePhase(e) === waitReference && !tileResults(e).io.deq.valid && !tileOwned(e)
    cache.io.end.bits := true.B
    cache.io.completion.ready := !cacheDone(e)
    when(cache.io.completion.fire) {
      cacheDone(e) := true.B
      cacheResults(e) := cache.io.completion.bits
    }
    val rawTile = replay.io.tileDone
    val incomingTile = rawTile.valid && rawTile.bits.engine === e.U
    val tileQueue = tileResults(e)
    tileQueue.io.enq.valid := incomingTile
    tileQueue.io.enq.bits := rawTile.bits
    when(rawTile.bits.engine === e.U) { rawTile.ready := tileQueue.io.enq.ready }
    // During cancellation the scheduler can place an unstarted tile result
    // on an engine still draining another tile. Only the matching tile owns
    // this engine's resolved count/status; unrelated zero-reference results
    // can retire without releasing the live tile's records or accounting.
    val tileMatches = tileOwned(e) && tileQueue.io.deq.bits.tile === tileId(e)
    val resultReady = !tileMatches || enginePhase(e) === waitReference
    io.tileDone(e).valid := tileQueue.io.deq.valid && resultReady
    io.tileDone(e).bits.replay := tileQueue.io.deq.bits
    io.tileDone(e).bits.status := Mux(tileMatches,
      merge(tileQueue.io.deq.bits.status,tileStatus(e)),tileQueue.io.deq.bits.status)
    io.tileDone(e).bits.resolved := Mux(tileMatches,tileResolved(e),0.U)
    tileQueue.io.deq.ready := io.tileDone(e).ready && resultReady
    when(io.tileDone(e).valid && !tileMatches) {
      assert(tileQueue.io.deq.bits.emitted === 0.U)
    }
    when(io.tileDone(e).fire && tileMatches) {
      tileOwned(e) := false.B
      tileStatus(e) := 0.U
      tileResolved(e) := 0.U
    }

    val in = replay.io.reference(e)
    in.ready := phase === run && enginePhase(e) === waitReference &&
      !tileQueue.io.deq.valid && !incomingTile &&
      (!tileOwned(e) || in.bits.tile === tileId(e))
    when(in.fire) {
      records(e).source := in.bits
      // Resolve both ordinals before cache admission. Keeping subtraction and
      // phase selection off the cache-hit/counter path is necessary for timing.
      lookupHandle(e) := in.bits.reference.primitive - descriptor.firstHandle
      stateHandle(e) := in.bits.reference.state - descriptor.firstHandle
      primitiveLine.foreach(_(e) := 0.U)
      tileOwned(e) := true.B
      tileId(e) := in.bits.tile
      enginePhase(e) := validate
    }
    val source = records(e).source.reference
    cache.io.lookup.valid := (enginePhase(e) === primitiveLookup ||
      enginePhase(e) === stateLookup) && !stop && !poisoned(e)
    cache.io.lookup.bits.handle := lookupHandle(e)
    when(cache.io.lookup.fire) {
      enginePhase(e) := Mux(enginePhase(e) === primitiveLookup,primitiveWait,stateWait)
    }
    cache.io.word.ready := true.B
    when(cache.io.word.fire) {
      when(enginePhase(e) === primitiveWait) {
        val wordIndex = primitiveLine.map(lines => Cat(lines(e),cache.io.word.bits.index))
          .getOrElse(cache.io.word.bits.index)
        records(e).primitive(wordIndex) := cache.io.word.bits.data
      }.otherwise {
        records(e).state(cache.io.word.bits.index) := cache.io.word.bits.data
      }
    }
    cache.io.lookupDone.ready := true.B
    when(cache.io.lookupDone.fire) {
      val status = cache.io.lookupDone.bits.status
      when(status =/= PrimitiveStateStatus.success.U) {
        val mapped = Mux(status === PrimitiveStateStatus.invalidHandle.U,
          ResolvedTileBinStatus.invalidHandle.U,status)
        tileStatus(e) := merge(tileStatus(e),mapped)
        passStatus(e) := merge(passStatus(e),mapped)
        when(status === PrimitiveStateStatus.memoryFault.U) { poisoned(e) := true.B }
        enginePhase(e) := waitReference
      }.elsewhen(stop) {
        tileStatus(e) := merge(tileStatus(e),PrimitiveBinStatus.cancelled.U)
        passStatus(e) := merge(passStatus(e),PrimitiveBinStatus.cancelled.U)
        enginePhase(e) := waitReference
      }.otherwise {
        if (primitiveLines == 1) {
          when(enginePhase(e) === primitiveWait) { lookupHandle(e) := stateHandle(e) }
          enginePhase(e) := Mux(enginePhase(e) === primitiveWait,stateLookup,emit)
        } else {
          when(enginePhase(e) === primitiveWait) {
            when(primitiveLine.get(e) === (primitiveLines-1).U) {
              lookupHandle(e) := stateHandle(e)
              enginePhase(e) := stateLookup
            }.otherwise {
              primitiveLine.get(e) := primitiveLine.get(e) + 1.U
              lookupHandle(e) := lookupHandle(e) + 1.U
              enginePhase(e) := primitiveLookup
            }
          }.otherwise { enginePhase(e) := emit }
        }
      }
    }
    when(enginePhase(e) === validate) {
      val p = source.primitive
      val s = source.state
      val primitiveFits = if (primitiveLines == 1) lookupHandle(e).pad(33) < recordCount
        else lookupHandle(e).pad(33) + primitiveLines.U <= recordCount
      val handlesValid = p >= descriptor.firstHandle && s >= descriptor.firstHandle &&
        primitiveFits && stateHandle(e).pad(33) < recordCount
      when(stop || poisoned(e) || !handlesValid) {
        val status = Mux(poisoned(e),PrimitiveBinStatus.memoryFault.U,
          Mux(stop,PrimitiveBinStatus.cancelled.U,ResolvedTileBinStatus.invalidHandle.U))
        tileStatus(e) := merge(tileStatus(e),status)
        passStatus(e) := merge(passStatus(e),status)
        enginePhase(e) := waitReference
      }.otherwise { enginePhase(e) := primitiveLookup }
    }
    when(stop && (enginePhase(e) === primitiveLookup || enginePhase(e) === stateLookup)) {
      tileStatus(e) := merge(tileStatus(e),PrimitiveBinStatus.cancelled.U)
      passStatus(e) := merge(passStatus(e),PrimitiveBinStatus.cancelled.U)
      enginePhase(e) := waitReference
    }
    io.reference(e).valid := enginePhase(e) === emit
    io.reference(e).bits := records(e)
    when(io.reference(e).fire) {
      resolved(e) := resolved(e) + 1.U
      tileResolved(e) := tileResolved(e) + 1.U
      enginePhase(e) := waitReference
    }
    // An already offered pair still retires on cancellation, but its tile is
    // failed. An already offered tile completion retains its published value.
    when(stop && enginePhase(e) === emit) {
      tileStatus(e) := merge(tileStatus(e),PrimitiveBinStatus.cancelled.U)
      passStatus(e) := merge(passStatus(e),PrimitiveBinStatus.cancelled.U)
    }
  }
  private val drained = (0 until engines).map(e =>
    enginePhase(e) === waitReference && !tileResults(e).io.deq.valid && !tileOwned(e)).reduce(_ && _)
  when(phase === run && replayDone && cacheDone.asUInt.andR && drained) {
    result.replay := replayResult
    val status = (Seq(replayResult.status.pad(ResolvedTileBinStatus.width)) ++
      passStatus.toSeq ++ cacheResults.map(_.status.pad(ResolvedTileBinStatus.width))).reduce(merge)
    result.status := merge(status,Mux(stop,PrimitiveBinStatus.cancelled.U,0.U))
    result.resolved := resolved.reduce(_ + _)
    result.cacheLookups := cacheResults.map(_.lookups).reduce(_ + _)
    result.cacheHits := cacheResults.map(_.hits).reduce(_ + _)
    result.cacheMisses := cacheResults.map(_.misses).reduce(_ + _)
    result.recordReadLines := cacheResults.map(_.readLines).reduce(_ + _)
    phase := done
  }
  when(io.completion.fire) { phase := idle }

  // Disjoint ids: directory words, replay engines, then record-cache engines.
  arbiter.io.in(0).valid := replay.io.memoryRequest.valid
  arbiter.io.in(0).bits := replay.io.memoryRequest.bits
  replay.io.memoryRequest.ready := arbiter.io.in(0).ready
  replay.io.memoryResponse.valid := io.memoryResponse.valid &&
    io.memoryResponse.bits.transactionId < binTransactions.U
  replay.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := Mux(io.memoryResponse.bits.transactionId < binTransactions.U,
    replay.io.memoryResponse.ready,false.B)
  for (e <- 0 until engines) {
    arbiter.io.in(e+1) <> caches(e).io.memoryRequest
    caches(e).io.memoryResponse.valid := io.memoryResponse.valid &&
      io.memoryResponse.bits.transactionId === (binTransactions+e).U
    caches(e).io.memoryResponse.bits := io.memoryResponse.bits
    when(io.memoryResponse.bits.transactionId === (binTransactions+e).U) {
      io.memoryResponse.ready := caches(e).io.memoryResponse.ready
    }
  }
  requests.io.enq <> arbiter.io.out
  io.memoryRequest <> requests.io.deq
}
