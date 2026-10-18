package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.util.CarrySaveAccumulator

/** One pass-level bin descriptor: a shared page arena and its active tiles. */
class TileBinDirectoryDescriptor extends Bundle {
  val version = UInt(8.W)
  /** Version 3 retains per-tile sequence history across a fully drained,
    * successful/exhausted batch with identical identity, pool and grid backing.
    * Every tile publishes a closed entry before a version-3 batch completes.
    */
  val resume = Bool()
  val pool = new BinPagePoolDescriptor
  /** Version 1 is resident; version 2 uses checked 64-byte backing entries. */
  val directoryBase = UInt(32.W)
  val directoryLimit = UInt(33.W)
  /** Every active tile id must be below this value. */
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
  val directoryReadBytes = UInt(64.W)
  val directoryWriteBytes = UInt(64.W)
  val directoryMisses = UInt(32.W)
  val allocatedPages = UInt(32.W)
  val spillTransitions = UInt(4.W)
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
  * With pagedCapacity > 0, version 2 binds a disjoint directoryBase/Limit
  * allocation. Hardware zeroes every 64-byte entry before admitting appends.
  * A direct-mapped tileSlots-entry cache writes dirty victims and verifies
  * identity, tile and pointer bounds on refill; clean completion flushes it.
  * A one-bit-per-tile synchronous report SRAM makes fault drain independent
  * of corrupt backing state. Failed nonresident reports contain no usable
  * chain/count; the exact pass-level physical commit count remains available.
  * The cache has a separate one-transaction uncached line client; both that
  * client and the bin word client must drain before arena reuse.
  *
  * The caller must drain all consumers before ending the binding or reusing
  * the arena. This directory does not replay, schedule engines or resolve
  * primitive/state handles.
  */
class TileBinDirectory(
  tileSlots: Int = 8,
  pageBytes: Int = 4096,
  writeCredits: Int = 8,
  pagedCapacity: Int = 0,
  config: GpuConfig = GpuConfig(),
  useDirectorySramBlackBoxes: Boolean = false
) extends Module {
  require(tileSlots >= 1 && tileSlots <= 64)
  private val paged = pagedCapacity > 0
  private val maximumTiles = if (paged) pagedCapacity else tileSlots
  require(!paged || (isPow2(tileSlots) && pagedCapacity >= tileSlots && pagedCapacity <= 65535))
  private val capacity = PrimitiveBinPageLayout.recordsPerPage(pageBytes)
  require(capacity > 0)
  private val entryBits = log2Ceil(capacity + 1).max(1)
  private val indexBits = log2Ceil(tileSlots).max(1)
  private def slotIndex(tile: UInt): UInt = if (tileSlots == 1) 0.U else tile(indexBits-1,0)
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
    val directoryMemoryRequest = if (paged) Some(Decoupled(new ComputeMemoryRequest(config,64,1))) else None
    val directoryMemoryResponse = if (paged) Some(Flipped(Decoupled(new ComputeMemoryResponse(64,1)))) else None
    val busy = Output(Bool())
    val committed = Output(Bool())
    val storeBytes = Output(UInt(3.W))
    val pageAllocated = Output(Bool())
  })
  private val directoryStates = Enum(40)
  private val Seq(idle, binding, ready, check, allocate, allocationWait, header,
    headerWait, link, linkWait, payload, payloadWait, commit, commitWait, reply,
    abortDrain, scan, close, poolWait, tileReply, done) = directoryStates.take(21)
  private val Seq(initialize, initializeWait,
    directoryLookup, directoryWriteback, directoryWritebackWait, directoryRead,
    directoryReadWait, directoryLoaded, directoryFlush, directoryFlushWait,
    sealTile, scanTile, abortScanRead, abortScanWait, directoryValidate, directoryInstall,
    initializeReadWait, initializeValidate, initializeInstall) = directoryStates.drop(21)
  private val state = RegInit(idle)
  private val descriptor = Reg(new TileBinDirectoryDescriptor)
  private val resumeReady = RegInit(false.B)
  private val resumeChecked = RegInit(true.B)
  private val canResume = resumeReady && descriptor.version===3.U &&
    io.begin.bits.version===3.U && io.begin.bits.pool.asUInt===descriptor.pool.asUInt &&
    io.begin.bits.tileCount===descriptor.tileCount &&
    io.begin.bits.directoryBase===descriptor.directoryBase && io.begin.bits.directoryLimit===descriptor.directoryLimit
  private def versionLegal(d: TileBinDirectoryDescriptor): Bool =
    (d.version===(if (paged) 2.U else 1.U) || d.version===3.U) && (!d.resume || d.version===3.U)
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
  private val scanBits = log2Ceil(maximumTiles+1).max(1)
  private val closeIndex = RegInit(0.U(scanBits.W))
  private val tileReplyValid = RegInit(false.B)
  private val tileReplyBits = Reg(new TileBinCompletion)
  private val tileReplyNext = RegInit(0.U(2.W))
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
  private val sequenceValid = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val tileStatus = RegInit(VecInit(Seq.fill(tileSlots)(
    PrimitiveBinStatus.success.U(PrimitiveBinStatus.width.W))))
  private val restart = Reg(Vec(tileSlots, new BinPrimitiveReference))
  // Direct-mapped resident metadata. Full-frame state lives in 64-byte
  // directory entries; only the report bitmap scales to the configured frame
  // capacity (one bit/tile, synchronous SRAM). It prevents duplicate failure
  // reports when backing metadata itself faults during cancellation/drain.
  private val cacheValid = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val cacheDirty = RegInit(VecInit(Seq.fill(tileSlots)(false.B)))
  private val cacheTag = Reg(Vec(tileSlots,UInt(32.W)))
  private val cacheTile = Reg(UInt(32.W))
  private val cacheResume = Reg(UInt(6.W))
  private val cacheIndex = slotIndex(cacheTile)
  private val initializeIndex = RegInit(0.U(16.W))
  private val clearedTiles = RegInit(0.U(16.W))
  private val flushIndex = RegInit(0.U(log2Ceil(tileSlots+1).max(1).W))
  private val flushSlot = slotIndex(flushIndex)
  private val openedCount = RegInit(0.U(16.W))
  private val metadataReadBytes = Module(new CarrySaveAccumulator(64,prefixValue=true))
  private val metadataWriteBytes = Module(new CarrySaveAccumulator(64,prefixValue=true))
  metadataReadBytes.io.clear:=io.begin.fire; metadataWriteBytes.io.clear:=io.begin.fire
  metadataReadBytes.io.addend:=0.U; metadataWriteBytes.io.addend:=0.U
  private val metadataMisses = RegInit(0.U(32.W))
  private val reportedBitmap = if (paged) Some(Module(new TileReportBitmap(pagedCapacity,useDirectorySramBlackBoxes))) else None
  private val bitmapWrite = WireDefault(false.B)
  private val bitmapWriteTile = WireDefault(0.U(32.W))
  private val bitmapWriteValue = WireDefault(false.B)
  private val bitmapReady = reportedBitmap.map(_.io.ready).getOrElse(true.B)
  private val abortReported = reportedBitmap.map(_.io.readData)
  reportedBitmap.foreach { bitmap=>
    bitmap.io.update.valid:=bitmapWrite
    bitmap.io.update.bits.tile:=bitmapWriteTile; bitmap.io.update.bits.value:=bitmapWriteValue
    bitmap.io.read.valid:=state===abortScanRead && closeIndex<descriptor.tileCount && bitmapReady
    bitmap.io.read.bits:=closeIndex
  }
  private val metadataQueue = if (paged) Some(Module(new Queue(new ComputeMemoryRequest(config,64,1),1))) else None
  private val metadataPending = RegInit(false.B)
  private def packed(index: UInt): UInt = {
    val flags = Cat(0.U(20.W),tileStatus(index).pad(4),0.U(2.W),
      sequenceValid(index) && descriptor.version===3.U,restartValid(index),
      reported(index),closedSlot(index),opened(index),headValid(index))
    VecInit(Seq(Mux(descriptor.version===3.U,"h47444402".U(32.W),"h47444401".U(32.W)),descriptor.pool.identity.context,
      descriptor.pool.identity.pass,descriptor.pool.identity.generation,cacheTag(index),
      flags,head(index),tail(index),count(index),pageCount(index).pad(32),lastSequence(index),
      restart(index).primitive,restart(index).state,restart(index).sequence,restart(index).token,
      0.U(32.W))).asUInt
  }
  private val metadataBits = if (paged) Some(Reg(UInt(512.W))) else None
  private val metadataValid = Reg(Bool())
  private def word(n: Int): UInt = metadataBits.get(32*n+31,32*n)
  private val metadataLegal = if (!paged) true.B else {
    val flags = word(5)
    def pageValid(address: UInt): Bool = BinPagePoolRules.pageInBounds(address,descriptor.pool,pageBytes)
    metadataBits.get === 0.U || (word(0) === Mux(descriptor.version===3.U,"h47444402".U,"h47444401".U) &&
      word(1) === descriptor.pool.identity.context && word(2) === descriptor.pool.identity.pass &&
      word(3) === descriptor.pool.identity.generation && word(4) === cacheTile &&
      flags(31,12) === 0.U && flags(7,6) === 0.U &&
      (descriptor.version===3.U || !flags(5)) &&
      (descriptor.version=/=3.U || !flags(0) || flags(5)) &&
      (flags(11,8) === 0.U || flags(11,8) === PrimitiveBinStatus.exhausted.U ||
        flags(11,8) === PrimitiveBinStatus.invalidOrder.U) && flags(1) === flags(0) &&
      (!flags(3) || flags(2)) && (flags(11,8) === 0.U || flags(2)) &&
      word(15) === 0.U && word(9) <= capacity.U &&
      (flags(0) === (word(8) =/= 0.U)) && (!flags(0) || (pageValid(word(6)) && pageValid(word(7)))) &&
      (!flags(0) || (word(9)>0.U && word(8)>=word(9))) &&
      (flags(4) === (flags(11,8) === PrimitiveBinStatus.exhausted.U)))
  }
  private val metadataResponse = io.directoryMemoryResponse.map(_.fire).getOrElse(false.B)
  private val metadataFault = if (!paged) false.B else
    (metadataResponse && io.directoryMemoryResponse.get.bits.fault) ||
      ((state === directoryInstall || state===initializeInstall) && !metadataValid)
  private def lookup(tile: UInt,resume: UInt): Unit = {
    cacheTile := tile; cacheResume := resume; state := directoryLookup
  }
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
  private val faultNow = (io.mem.resp.fire && io.mem.responseFault) || metadataFault
  private val badDescriptor = state =/= idle && (!versionLegal(descriptor) || !resumeChecked ||
    descriptor.tileCount === 0.U || descriptor.tileCount > maximumTiles.U ||
    (if (!paged) false.B else descriptor.directoryBase(5,0) =/= 0.U ||
      descriptor.directoryLimit > (BigInt(1)<<32).U ||
      descriptor.directoryBase.pad(33)+(descriptor.tileCount.pad(33)<<6) > descriptor.directoryLimit ||
      !(descriptor.directoryLimit <= descriptor.pool.base.pad(33) ||
        descriptor.pool.limit <= descriptor.directoryBase.pad(33))))
  private val stop = aborting || io.cancel || faultNow || badDescriptor
  private val effectiveStatus = Mux(faultNow || passStatus === PrimitiveBinStatus.memoryFault.U,
    PrimitiveBinStatus.memoryFault.U, Mux(badDescriptor,
      PrimitiveBinStatus.invalidDescriptor.U, Mux(io.cancel || aborting,
        PrimitiveBinStatus.cancelled.U, passStatus)))
  private val currentIdx = slotIndex(current.tile)
  private val currentValid = current.tile < descriptor.tileCount.pad(32)
  private val currentClosed = closedSlot(currentIdx)
  private val currentCount = Mux((if (paged) cacheValid(currentIdx) && cacheTag(currentIdx) === current.tile else true.B),count(currentIdx),0.U)
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
  pool.io.begin.bits.version := Mux(versionLegal(io.begin.bits) &&
    (!io.begin.bits.resume || canResume) &&
    io.begin.bits.tileCount > 0.U && io.begin.bits.tileCount <= maximumTiles.U &&
    (if (!paged) true.B else io.begin.bits.directoryBase(5,0) === 0.U &&
      io.begin.bits.directoryLimit <= (BigInt(1)<<32).U &&
      io.begin.bits.directoryBase.pad(33)+(io.begin.bits.tileCount.pad(33)<<6) <= io.begin.bits.directoryLimit &&
      (io.begin.bits.directoryLimit <= io.begin.bits.pool.base.pad(33) ||
        io.begin.bits.pool.limit <= io.begin.bits.directoryBase.pad(33))),io.begin.bits.pool.version,0.U)
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
    val index = slotIndex(tile)
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
      val i = slotIndex(tileReplyBits.tile)
      when((if (paged) cacheValid(i) && cacheTag(i) === tileReplyBits.tile else true.B)) {
        reported(i) := true.B
        if (paged) cacheDirty(i) := true.B
      }
      if (paged) {
        bitmapWrite:=true.B; bitmapWriteTile:=tileReplyBits.tile; bitmapWriteValue:=true.B
      }
    }
    state := Mux(tileReplyNext === 0.U, ready,Mux(tileReplyNext === 1.U,scan,abortScanRead))
  }
  switch(state) {
    is(idle) {
      when(io.begin.fire) {
        descriptor := io.begin.bits
        resumeChecked := !io.begin.bits.resume || canResume
        resumeReady := false.B
        pending := false.B
        aborting := false.B
        passStatus := PrimitiveBinStatus.success.U
        totalCommitted := 0.U
        openedCount := 0.U; initializeIndex := 0.U; clearedTiles := 0.U
        metadataMisses := 0.U
        for (i <- 0 until tileSlots) { cacheValid(i) := false.B; cacheDirty(i) := false.B }
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
          when(!io.begin.bits.resume) { lastSequence(i) := 0.U; sequenceValid(i) := false.B }
          tileStatus(i) := PrimitiveBinStatus.success.U
        }
        state := binding
      }
    }
    is(binding) { when(pool.io.allocate.ready && !stop) { state := (if (paged) initialize else ready) } }
    is(ready) {
      when(io.seal.fire) {
        if (paged) {
          when(io.seal.bits < descriptor.tileCount) { lookup(io.seal.bits,sealTile) }
            .otherwise {
              tileReplyBits := tileCompletionBits(io.seal.bits,PrimitiveBinStatus.invalidDescriptor.U)
              tileReplyNext := 0.U; tileReplyValid := true.B; state := tileReply
            }
        } else {
          val tile = io.seal.bits
          val valid = tile < descriptor.tileCount.pad(32)
          val index = slotIndex(tile)
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
        }
      }.elsewhen(io.append.fire) {
        current := io.append.bits
        pending := true.B
        workPage := tail(slotIndex(io.append.bits.tile))
        if (paged) {
          when(io.append.bits.tile < descriptor.tileCount) { lookup(io.append.bits.tile,check) }
            .otherwise { state := check }
        } else state := check
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
        }.elsewhen(sequenceValid(currentIdx) && current.reference.sequence <= currentLastSequence) {
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
          openedCount := openedCount + 1.U
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
            state := (if (paged) abortScanRead else scan)
          }
      }
    }
    // A pass that is stopping keeps this walk uninterrupted; the per-tile
    // status below is the failure status instead of a late seal.
    is(scan) {
      if (paged) {
        when(closeIndex === descriptor.tileCount) { flushIndex := 0.U; state := directoryFlush }
          .otherwise { lookup(closeIndex.pad(32),scanTile) }
      } else {

        val slot = slotIndex(closeIndex)
        when(closeIndex === descriptor.tileCount) {
          state := Mux(stop, poolWait, close)
        }.elsewhen(!reported(slot) && (opened(slot) || closedSlot(slot) || descriptor.version===3.U)) {
          closedSlot(slot) := true.B
          tileReplyBits := tileCompletionBits(closeIndex.pad(32),
            Mux(stop, effectiveStatus, tileStatus(slot)))
          tileReplyNext := 1.U
          tileReplyValid := true.B
          closeIndex := closeIndex + 1.U
          state := tileReply
        }.otherwise { closeIndex := closeIndex + 1.U }
      }
    }
    is(close) { when(pool.io.end.fire) { state := poolWait } }
    is(done) { when(io.completion.fire) {
      resumeReady := descriptor.version===3.U &&
        (result.status===PrimitiveBinStatus.success.U || result.status===PrimitiveBinStatus.exhausted.U)
      state := idle
    } }
  }
  if (paged) {
    val q = metadataQueue.get.io
    io.directoryMemoryRequest.get <> q.deq
    q.enq.valid := false.B
    q.enq.bits := 0.U.asTypeOf(q.enq.bits)
    q.enq.bits.sizeLog2 := 6.U
    q.enq.bits.byteMask := Fill(64,1.U(1.W))
    q.enq.bits.cachePolicy := CachePolicy.uncached
    io.directoryMemoryResponse.get.ready := metadataPending
    when(q.enq.fire) { metadataPending := true.B }
    when(metadataResponse) { metadataPending := false.B }
    when(io.directoryMemoryRequest.get.fire) {
      when(io.directoryMemoryRequest.get.bits.isWrite) { metadataWriteBytes.io.addend := 64.U }
        .otherwise { metadataReadBytes.io.addend := 64.U }
    }
    def issue(tile: UInt,write: Boolean,data: UInt=0.U): Unit = {
      q.enq.valid := !stop
      q.enq.bits.address := descriptor.directoryBase+(tile<<6)
      q.enq.bits.isWrite := write.B; q.enq.bits.writeData := data
      q.enq.bits.byteMask := (if (write) Fill(64,1.U(1.W)) else 0.U)
    }
    switch(state) {
      is(initialize) {
        when(descriptor.resume) { issue(initializeIndex,false) }
          .otherwise { issue(initializeIndex,true) }
        when(q.enq.fire) {
          when(descriptor.resume) { cacheTile:=initializeIndex; state:=initializeReadWait }
            .otherwise {
              bitmapWrite:=true.B; bitmapWriteTile:=initializeIndex; bitmapWriteValue:=false.B
              clearedTiles := initializeIndex+1.U; state := initializeWait
            }
        }
      }
      is(initializeReadWait) {
        when(metadataResponse) {
          metadataBits.get:=io.directoryMemoryResponse.get.bits.readData
          when(io.directoryMemoryResponse.get.bits.fault) { state:=abortDrain }
            .otherwise { state:=initializeValidate }
        }
      }
      is(initializeValidate) {
        // Every safe version-3 predecessor published the whole grid. Empty or
        // unreported backing cannot erase an older tile's sequence history.
        metadataValid:=metadataLegal && metadataBits.get=/=0.U && word(5)(3,2)===3.U
        state:=initializeInstall
      }
      is(initializeInstall) {
        when(stop) { state:=abortDrain }.otherwise {
          val reset=Wire(Vec(16,UInt(32.W))); reset:=0.U.asTypeOf(reset)
          reset(0):="h47444402".U; reset(1):=descriptor.pool.identity.context
          reset(2):=descriptor.pool.identity.pass; reset(3):=descriptor.pool.identity.generation
          reset(4):=initializeIndex; reset(5):=Mux(word(5)(5),32.U,0.U)
          reset(10):=Mux(word(5)(5),word(10),0.U)
          issue(initializeIndex,true,reset.asUInt)
          when(q.enq.fire) {
            bitmapWrite:=true.B; bitmapWriteTile:=initializeIndex; bitmapWriteValue:=false.B
            clearedTiles:=initializeIndex+1.U; state:=initializeWait
          }
        }
      }
      is(initializeWait) {
        when(metadataResponse) {
          when(stop) { state := abortDrain }
            .elsewhen(initializeIndex+1.U === descriptor.tileCount) { state := ready }
            .otherwise { initializeIndex := initializeIndex+1.U; state := initialize }
        }
      }
      is(directoryLookup) {
        when(cacheValid(cacheIndex) && cacheTag(cacheIndex) === cacheTile) {
          cacheDirty(cacheIndex) := true.B; workPage := tail(cacheIndex); state := cacheResume
        }.otherwise {
          metadataMisses := metadataMisses+1.U
          state := Mux(cacheValid(cacheIndex) && cacheDirty(cacheIndex),directoryWriteback,directoryRead)
        }
      }
      is(directoryWriteback) {
        issue(cacheTag(cacheIndex),true,packed(cacheIndex))
        when(q.enq.fire) { state := directoryWritebackWait }
      }
      is(directoryWritebackWait) {
        when(metadataResponse) {
          cacheDirty(cacheIndex) := false.B
          state := Mux(stop,abortDrain,directoryRead)
        }
      }
      is(directoryRead) { issue(cacheTile,false); when(q.enq.fire) { state := directoryReadWait } }
      is(directoryReadWait) {
        when(metadataResponse) {
          metadataBits.get:=io.directoryMemoryResponse.get.bits.readData
          when(io.directoryMemoryResponse.get.bits.fault) { state:=abortDrain }
            .otherwise { state:=directoryValidate }
        }
      }
      // Always validate an accepted read, even if cancel arrived with/after
      // its response. Integrity faults retain priority over cancellation.
      is(directoryValidate) { metadataValid:=metadataLegal; state:=directoryInstall }
      is(directoryInstall) {
          when(stop) { state := abortDrain }.otherwise {
            val i=cacheIndex; val f=word(5)
            cacheValid(i) := true.B; cacheDirty(i) := false.B; cacheTag(i) := cacheTile
            headValid(i):=f(0); opened(i):=f(1); closedSlot(i):=f(2); reported(i):=f(3); restartValid(i):=f(4)
            tileStatus(i):=f(11,8); head(i):=word(6); tail(i):=word(7); count(i):=word(8)
            pageCount(i):=word(9); lastSequence(i):=word(10)
            sequenceValid(i):=Mux(descriptor.version===3.U,f(5),f(0))
            restart(i).primitive:=word(11); restart(i).state:=word(12)
            restart(i).sequence:=word(13); restart(i).token:=word(14)
            state := directoryLoaded
          }
      }
      is(directoryLoaded) { cacheDirty(cacheIndex):=true.B; workPage:=tail(cacheIndex); state:=cacheResume }
      is(directoryFlush) {
        when(flushIndex === tileSlots.U) { state := close }
          .elsewhen(cacheValid(flushSlot) && cacheDirty(flushSlot)) {
            issue(cacheTag(flushSlot),true,packed(flushSlot))
            when(q.enq.fire) { state := directoryFlushWait }
          }.otherwise { flushIndex:=flushIndex+1.U }
      }
      is(directoryFlushWait) {
        when(metadataResponse) {
          cacheDirty(flushSlot):=false.B; flushIndex:=flushIndex+1.U
          state:=Mux(stop,abortDrain,directoryFlush)
        }
      }
      is(sealTile) {
        val tile = cacheTile
        val valid = tile < descriptor.tileCount.pad(32)
        val index = slotIndex(tile)
        when(valid) { closedSlot(index) := true.B }
        // tileClosed is a replay descriptor, not a seal acknowledgement:
        // publishing it twice would execute the same primitives twice.
        when(!valid || !reported(index)) {
          tileReplyBits := tileCompletionBits(tile, Mux(valid,
            tileStatus(index), PrimitiveBinStatus.invalidDescriptor.U(PrimitiveBinStatus.width.W)))
          tileReplyNext := 0.U
          tileReplyValid := true.B
          state := tileReply
        }.otherwise { state := ready }
      }
      is(scanTile) {
        val slot = slotIndex(closeIndex)
        when(closeIndex === descriptor.tileCount) {
          flushIndex := 0.U; state := Mux(stop,poolWait,directoryFlush)
        }.elsewhen(!reported(slot) && (opened(slot) || closedSlot(slot) || descriptor.version===3.U)) {
          closedSlot(slot) := true.B
          tileReplyBits := tileCompletionBits(closeIndex.pad(32),
            Mux(stop, effectiveStatus, tileStatus(slot)))
          tileReplyNext := 1.U
          tileReplyValid := true.B
          closeIndex := closeIndex + 1.U
          state := tileReply
        }.otherwise { closeIndex := closeIndex + 1.U; state := scan }
      }

      is(abortScanRead) {
        when(closeIndex === descriptor.tileCount) { state:=poolWait }
          .elsewhen(bitmapReady) { state:=abortScanWait }
      }
      is(abortScanWait) {
        when(closeIndex < clearedTiles && abortReported.get) { closeIndex:=closeIndex+1.U; state:=abortScanRead }
          .otherwise {
            val i=slotIndex(closeIndex)
            val resident=cacheValid(i) && cacheTag(i) === closeIndex
            tileReplyBits:=0.U.asTypeOf(tileReplyBits)
            tileReplyBits.identity:=descriptor.pool.identity; tileReplyBits.tile:=closeIndex
            tileReplyBits.status:=effectiveStatus
            tileReplyBits.headValid:=resident && headValid(i)
            tileReplyBits.head:=Mux(resident,head(i),0.U); tileReplyBits.tail:=Mux(resident,tail(i),0.U)
            tileReplyBits.committedCount:=Mux(resident,count(i),0.U)
            tileReplyNext:=2.U; tileReplyValid:=true.B; closeIndex:=closeIndex+1.U; state:=tileReply
          }
      }
    }
  }

  when(pool.io.completion.fire) {
    val poolStatus = pool.io.completion.bits.status
    val base = Mux(effectiveStatus === PrimitiveBinStatus.success.U,
      poolStatus, effectiveStatus)
    result.identity := descriptor.pool.identity
    result.status := Mux(base === PrimitiveBinStatus.success.U, passStatus, base)
    result.tilesOpened := (if (paged) openedCount else PopCount(opened.asUInt).pad(16))
    result.directoryReadBytes:=metadataReadBytes.io.value; result.directoryWriteBytes:=metadataWriteBytes.io.value
    result.directoryMisses:=metadataMisses
    result.allocatedPages := pool.io.completion.bits.allocatedPages
    result.spillTransitions:=pool.io.completion.bits.spillTransitions
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
      state =/= tileReply && state =/= scan && state =/= abortScanRead && state =/= abortScanWait &&
      state =/= directoryValidate && state =/= directoryInstall &&
      state =/= initializeValidate && state =/= initializeInstall &&
      !metadataPending && !(metadataQueue.map(_.io.enq.fire).getOrElse(false.B))) { state := abortDrain }
  when(commitAcknowledged) {
    commitLive := false.B
    count(currentIdx) := currentCount + 1.U
    totalCommitted := totalCommitted + 1.U
    pageCount(currentIdx) := currentPageCount + 1.U
    lastSequence(currentIdx) := current.reference.sequence
    sequenceValid(currentIdx) := true.B
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
