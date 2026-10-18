package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class PrimitiveBinStoreCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val tile = UInt(32.W)
  val status = UInt(PrimitiveBinStatus.width.W)
  val prefixUsable = Bool()
  val committed = UInt(32.W)
  val emitted = UInt(32.W)
  val allocatedPages = UInt(32.W)
  val spillTransitions = UInt(4.W)
  val restartValid = Bool()
  val restart = new BinPrimitiveReference
}

/** One-bin build/replay composition on a tagged64-byte memory client.
  * Writer stores and readback use the uncached domain; the memory client must
  * make acknowledged stores visible to subsequent reads. Writer completion
  * supplies the immutable replay descriptor. New bindings wait for both
  * phases and the final completion, preserving arena ownership through replay.
  * References from an unsuccessful final binding must not publish a pass.
  * Multi-tile directories, primitive/state resolution and tile engines are
  * separate integrations. This component provides no CPU-cache coherence.
  */
class PrimitiveBinStore(
  config: GpuConfig = GpuConfig(),
  pageBytes: Int = 4096,
  writeCredits: Int = 8
) extends Module {
  private val transactions = writeCredits+1
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new PrimitiveBinDescriptor))
    val append = Flipped(Decoupled(new BinPrimitiveReference))
    val appended = Decoupled(new PrimitiveBinAppendResult)
    val end = Flipped(Decoupled(Bool()))
    val reference = Decoupled(new PrimitiveBinReference)
    val cancel = Input(Bool())
    val completion = Decoupled(new PrimitiveBinStoreCompletion)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val busy = Output(Bool())
  })
  private val idle :: building :: readStart :: reading :: done :: Nil = Enum(5)
  private val phase = RegInit(idle)
  private val descriptor = Reg(new PrimitiveBinDescriptor)
  private val written = Reg(new PrimitiveBinCompletion)
  private val result = Reg(new PrimitiveBinStoreCompletion)
  private val cancelled = RegInit(false.B)
  private val writer = Module(new PrimitiveBinWriter(pageBytes,writeCredits))
  private val reader = Module(new PrimitiveBinReader(config,pageBytes,transactions,writeCredits))
  private val words = Module(new OmWordToLinePort(config,64,writeCredits,uncached=true))
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config,64,transactions),2))
  private val requests = Module(new Queue(new ComputeMemoryRequest(config,64,transactions),2,pipe=true))

  // The queue freezes public request IDs/data even if a free internal word
  // transaction is reselected while the downstream memory port is stalled.
  arbiter.io.in(0).valid := words.io.memoryRequest.valid
  arbiter.io.in(0).bits := words.io.memoryRequest.bits
  words.io.memoryRequest.ready := arbiter.io.in(0).ready
  arbiter.io.in(1) <> reader.io.memoryRequest
  requests.io.enq <> arbiter.io.out
  io.memoryRequest <> requests.io.deq
  private val readerResponse = io.memoryResponse.bits.transactionId === writeCredits.U
  private val wordResponse = io.memoryResponse.bits.transactionId < writeCredits.U
  reader.io.memoryResponse.valid := io.memoryResponse.valid && readerResponse
  reader.io.memoryResponse.bits := io.memoryResponse.bits
  words.io.memoryResponse.valid := io.memoryResponse.valid && wordResponse
  words.io.memoryResponse.bits := io.memoryResponse.bits
  io.memoryResponse.ready := Mux(readerResponse,reader.io.memoryResponse.ready,
    wordResponse && words.io.memoryResponse.ready)
  words.io.in <> writer.io.mem.req
  writer.io.mem.resp <> words.io.out
  // The non-buffered adapter's word reply and line response share a handshake.
  writer.io.mem.responseFault := words.io.memoryResponse.bits.fault

  writer.io.begin.valid := phase === idle && io.begin.valid && !io.cancel
  writer.io.begin.bits := io.begin.bits
  io.begin.ready := phase === idle && writer.io.begin.ready && !io.cancel
  writer.io.append.valid := phase === building && io.append.valid
  writer.io.append.bits := io.append.bits
  io.append.ready := phase === building && writer.io.append.ready
  writer.io.end.valid := phase === building && io.end.valid
  writer.io.end.bits := io.end.bits
  io.end.ready := phase === building && writer.io.end.ready
  io.appended <> writer.io.appended
  writer.io.cancel := io.cancel && phase === building
  writer.io.completion.ready := phase === building

  reader.io.begin.valid := phase === readStart && !cancelled && !io.cancel
  reader.io.begin.bits.version := 1.U
  reader.io.begin.bits.pool := descriptor.pool
  reader.io.begin.bits.tile := written.tile
  reader.io.begin.bits.headValid := written.headValid
  reader.io.begin.bits.head := written.head
  reader.io.begin.bits.count := written.committedCount
  reader.io.cancel := (phase === readStart || phase === reading) && (cancelled || io.cancel)
  io.reference <> reader.io.reference
  reader.io.completion.ready := phase === reading
  io.completion.valid := phase === done
  io.completion.bits := result
  io.busy := phase =/= idle

  private def complete(write: PrimitiveBinCompletion,status: UInt,emitted: UInt): Unit = {
    result.identity := write.identity
    result.tile := write.tile
    result.status := status
    result.prefixUsable := status === PrimitiveBinStatus.success.U || status === PrimitiveBinStatus.exhausted.U
    result.committed := write.committedCount
    result.emitted := emitted
    result.allocatedPages := write.allocatedPages
    result.spillTransitions:=write.spillTransitions
    result.restartValid := status === PrimitiveBinStatus.exhausted.U && write.restartValid
    result.restart := write.restart
    phase := done
  }
  when(io.begin.fire) { descriptor := io.begin.bits; cancelled := false.B; phase := building }
  when(io.cancel && phase =/= idle && phase =/= done) { cancelled := true.B }
  when(writer.io.completion.fire) {
    written := writer.io.completion.bits
    when(writer.io.completion.bits.prefixUsable && !cancelled && !io.cancel) {
      phase := readStart
    }.otherwise {
      val status = Mux((cancelled || io.cancel) && writer.io.completion.bits.status =/= PrimitiveBinStatus.memoryFault.U,
        PrimitiveBinStatus.cancelled.U,writer.io.completion.bits.status)
      complete(writer.io.completion.bits,status,0.U)
    }
  }
  when(phase === readStart) {
    when(cancelled || io.cancel) { complete(written,PrimitiveBinStatus.cancelled.U,0.U) }
      .elsewhen(reader.io.begin.fire) { phase := reading }
  }
  when(reader.io.completion.fire) {
    val status = Mux(reader.io.completion.bits.status =/= PrimitiveBinStatus.success.U,
      reader.io.completion.bits.status,Mux(cancelled || io.cancel,
        PrimitiveBinStatus.cancelled.U,written.status))
    complete(written,status,reader.io.completion.bits.emitted)
  }
  when(io.completion.fire) { phase := idle }
}
