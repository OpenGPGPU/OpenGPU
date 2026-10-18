package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class TileBinReplayPassCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(PrimitiveBinStatus.width.W)
  val tilesOpened = UInt(16.W)
  val allocatedPages = UInt(32.W)
  val committed = UInt(32.W)
  val tiles = UInt(32.W)
  val references = UInt(32.W)
  val readPages = UInt(32.W)
  val readLines = UInt(32.W)
}

/** One pass over the scalable bin path: build per-tile chains through a
  * TileBinDirectory, seal them, and replay each completed chain through the
  * two-engine TileBinReplayScheduler while both share one tagged line client.
  *
  * The directory's word port is adapted to 64-byte lines and tagged with
  * transaction ids below `writeCredits`; scheduler reads use the ids from
  * `writeCredits` to `writeCredits+engines-1`. A round-robin arbiter places
  * both on the public request port, and responses are demultiplexed by range.
  *
  * Begin is offered to both blocks together; tile completions flow directly
  * from the directory to the scheduler. End is sent to the directory first;
  * once the directory has reported every tile, the scheduler end drains the
  * last replays and produces the pass summary. Cancellation drives both blocks
  * and waits for a directory completion and a replay summary. A directory that
  * finishes without an end (invalid descriptor or fault) also ends the
  * scheduler so the pass always reports one merged status.
  *
  * The caller must drain per-tile replay completions and all references during
  * the pass, keep the arena owned until the pass summary, and publish any
  * bin-arena stores into this memory domain before replay begins.
  */
class TileBinReplayPass(
  config: GpuConfig = GpuConfig(),
  engines: Int = 2,
  pageBytes: Int = 4096,
  writeCredits: Int = 8,
  tileSlots: Int = 8
) extends Module {
  require(config.xLen == 32)
  require(engines >= 1 && engines <= 4)
  require(pageBytes >= 64 && pageBytes <= 65536 && (pageBytes & (pageBytes-1)) == 0)
  require(writeCredits >= 1 && writeCredits <= 32)
  require(tileSlots >= 1 && tileSlots <= 64)
  private val txCount = writeCredits + engines
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TileBinDirectoryDescriptor))
    val append = Flipped(Decoupled(new TileBinAppend))
    val appended = Decoupled(new TileBinAppendResult)
    val seal = Flipped(Decoupled(UInt(32.W)))
    val tileDone = Decoupled(new TileBinReplayCompletion)
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    val completion = Decoupled(new TileBinReplayPassCompletion)
    val reference = Vec(engines, Decoupled(new PrimitiveBinReference))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,txCount))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,txCount)))
    val busy = Output(Bool())
    val engineBusy = Output(Vec(engines, Bool()))
  })
  private val directory = Module(new TileBinDirectory(tileSlots,pageBytes,writeCredits))
  private val scheduler = Module(new TileBinReplayScheduler(
    config,engines,pageBytes,txCount,writeCredits))
  private val words = Module(new OmWordToLinePort(config,64,writeCredits,uncached=true))
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config,64,txCount),2))
  private val requests = Module(new Queue(new ComputeMemoryRequest(config,64,txCount),2,pipe=true))
  private val started = RegInit(false.B)
  private val cancelled = RegInit(false.B)
  private val identity = Reg(new GraphicsWorkIdentity)
  private val dirDone = RegInit(false.B)
  private val dirResult = Reg(new TileBinDirectoryCompletion)
  private val replayDone = RegInit(false.B)
  private val replayResult = Reg(new TileBinReplaySummary)
  private val summaryValid = RegInit(false.B)
  private val result = Reg(new TileBinReplayPassCompletion)

  // Child completions are consumed internally before the public summary.
  // Keep the pass and its arena owned until that summary is accepted.
  private val canBegin = !started && !summaryValid && !io.cancel
  private val beginFire = io.begin.valid && directory.io.begin.ready &&
    scheduler.io.begin.ready && canBegin
  directory.io.begin.valid := beginFire
  directory.io.begin.bits := io.begin.bits
  scheduler.io.begin.valid := beginFire
  scheduler.io.begin.bits.version := 1.U
  scheduler.io.begin.bits.pool := io.begin.bits.pool
  io.begin.ready := directory.io.begin.ready && scheduler.io.begin.ready && canBegin
  when(io.begin.fire) {
    identity := io.begin.bits.pool.identity
    started := true.B
    cancelled := false.B
    dirDone := false.B
    replayDone := false.B
    summaryValid := false.B
  }
  directory.io.append.valid := io.append.valid
  directory.io.append.bits := io.append.bits
  io.append.ready := directory.io.append.ready
  io.appended <> directory.io.appended
  directory.io.seal.valid := io.seal.valid
  directory.io.seal.bits := io.seal.bits
  io.seal.ready := directory.io.seal.ready
  scheduler.io.tile <> directory.io.tileClosed
  io.tileDone <> scheduler.io.tileDone
  for (e <- 0 until engines) { io.reference(e) <> scheduler.io.reference(e) }
  io.engineBusy := scheduler.io.engineBusy

  directory.io.end.valid := io.end.valid
  directory.io.end.bits := io.end.bits
  io.end.ready := directory.io.end.ready
  // Once the directory has reported every tile, end the replay pass so its
  // last completions drain. On cancel the scheduler summarizes by itself.
  scheduler.io.end.valid := started && dirDone && !replayDone && !cancelled
  scheduler.io.end.bits := true.B
  // The directory reports every tile before its own completion, so its result
  // also tells a cancelled scheduler that no more tile results can arrive.
  scheduler.io.producerDone := dirDone
  directory.io.cancel := io.cancel || cancelled
  scheduler.io.cancel := io.cancel || cancelled
  when(io.cancel && started && !cancelled) { cancelled := true.B }

  directory.io.completion.ready := !dirDone
  when(directory.io.completion.fire) {
    dirResult := directory.io.completion.bits
    dirDone := true.B
  }
  scheduler.io.completion.ready := !replayDone
  when(scheduler.io.completion.fire) {
    replayResult := scheduler.io.completion.bits
    replayDone := true.B
  }

  private def mergeStatus(a: UInt,b: UInt): UInt =
    Mux(a === PrimitiveBinStatus.memoryFault.U || b === PrimitiveBinStatus.memoryFault.U,
      PrimitiveBinStatus.memoryFault.U,
      Mux(a === PrimitiveBinStatus.cancelled.U || b === PrimitiveBinStatus.cancelled.U,
        PrimitiveBinStatus.cancelled.U,
        Mux(a === PrimitiveBinStatus.success.U,b,a)))
  io.completion.valid := summaryValid
  io.completion.bits := result
  when(started && dirDone && replayDone && !summaryValid) {
    result.identity := identity
    result.status := mergeStatus(dirResult.status,replayResult.status)
    result.tilesOpened := dirResult.tilesOpened
    result.allocatedPages := dirResult.allocatedPages
    result.committed := dirResult.committedCount
    result.tiles := replayResult.tiles
    result.references := replayResult.references
    result.readPages := replayResult.pages
    result.readLines := replayResult.readLines
    summaryValid := true.B
  }
  when(summaryValid && io.completion.fire) {
    summaryValid := false.B
    started := false.B
    dirDone := false.B
    replayDone := false.B
    // Release the latched cancellation so the next begin can be accepted.
    cancelled := false.B
  }
  io.busy := started || directory.io.busy || scheduler.io.busy

  // Directory words and scheduler lines share one tagged request stream. Word
  // ids stay below writeCredits; replay ids start there.
  arbiter.io.in(0).valid := words.io.memoryRequest.valid
  arbiter.io.in(0).bits := words.io.memoryRequest.bits
  words.io.memoryRequest.ready := arbiter.io.in(0).ready
  arbiter.io.in(1) <> scheduler.io.memoryRequest
  requests.io.enq <> arbiter.io.out
  io.memoryRequest <> requests.io.deq
  words.io.in <> directory.io.mem.req
  directory.io.mem.resp <> words.io.out
  directory.io.mem.responseFault := words.io.memoryResponse.bits.fault
  private val wordResponse = io.memoryResponse.bits.transactionId < writeCredits.U
  private val replayResponse = io.memoryResponse.bits.transactionId >= writeCredits.U &&
    io.memoryResponse.bits.transactionId < txCount.U
  scheduler.io.memoryResponse.valid := io.memoryResponse.valid && replayResponse
  scheduler.io.memoryResponse.bits := io.memoryResponse.bits
  words.io.memoryResponse.valid := io.memoryResponse.valid && wordResponse
  words.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := Mux(replayResponse,scheduler.io.memoryResponse.ready,
    wordResponse && words.io.memoryResponse.ready)
}
