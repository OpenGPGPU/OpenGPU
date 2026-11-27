package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics._

class TessellationJointFrameCaptureCompletion(idWidth: Int) extends Bundle {
  val commandId = UInt(idWidth.W); val status = UInt(3.W); val faultAddress = UInt(32.W); val readBytes = UInt(12.W)
  val clearOnly = Bool(); val frame = new TypedVertexBinImageDescriptor
}

/** Real GNF1 capture for an independently accepted GNQ4/5/6 owner. Reuses the original field map and canonical LE
  * decoder, never a second offset table. This performs metadata/scope checks; actual per-tile image/state admission,
  * immutable BUILD authority, physical aliases and mappings remain necessary. It neither begins an image nor
  * manufactures a clear or shader completion.
  */
class TessellationJointFrameCaptureReader(
    config: GpuConfig,
    idWidth: Int,
    engines: Int = 4,
    pagedCapacity: Int = 65536,
    enabled: Boolean = false
) extends Module {
  require(config.xLen == 32 && config.lanes == 16 && config.warps == 8 && idWidth > 0 && idWidth <= 12)
  require(engines >= 1 && engines <= 4 && pagedCapacity >= 0 && pagedCapacity <= 65536)
  import TessellationJointJobLayout.{wide, inside, overlaps, maxVA}
  val io = IO(new Bundle {
    val submit = Flipped(Decoupled(new TessellationJointJobCapture(idWidth)))
    val completion = Decoupled(new TessellationJointFrameCaptureCompletion(idWidth))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, offer, waitReply, check, complete = Value }
  private val state = RegInit(State.idle); private val q = Reg(new TessellationJointJobCapture(idWidth))
  private val record = Reg(new NativeBinnedGraphicsFrameRecord)
  private val result = Reg(new TessellationJointFrameCaptureCompletion(idWidth))
  private val line = Reg(UInt(5.W)); private val cancelled = RegInit(false.B); private val malformed = RegInit(false.B)
  private val fields = NativeBinnedGraphicsLayout.frameFields(record)
  NativeTypedGraphicsLayout.check(record, fields, NativeBinnedGraphicsLayout.frameWords)
  private val write = state === State.waitReply && io.memoryResponse.fire && !io.memoryResponse.bits.fault
  private val malformedLine = NativeTypedGraphicsLayout.receive(
    fields,
    NativeBinnedGraphicsLayout.frameWords,
    NativeBinnedGraphicsLayout.frameMagic,
    line,
    io.memoryResponse.bits.readData,
    write
  )
  private val f = record.frame; private val b = f.bins; private val j = q.job
  private val empty = j.sequenceCount === 0.U
  private def span(base: UInt, end: UInt): TessellationJointView = {
    val v = Wire(new TessellationJointView); v.base := base; v.limit := end; v
  }
  private def aligned(base: UInt, end: UInt): Bool =
    base(5, 0) === 0.U && end(5, 0) === 0.U && inside(base, wide(end), q.request.owner)
  private val states = span(f.states.base, f.states.limit)
  private val fixed =
    Seq(span(b.producer.arena.base, b.producer.arena.limit), span(b.bins.pool.base, b.bins.pool.limit)) ++
      (if (pagedCapacity > 0) Seq(span(b.bins.directoryBase, b.bins.directoryLimit)) else Seq.empty)
  private val writers = fixed ++ f.scratch.take(engines).map(s => span(s.base, s.limit))
  private val descriptors = Seq(q.request.view, j.frame) ++ Seq(j.sequence, j.directory)
  private val stateCount = (wide(f.states.limit) - wide(f.states.base)) >> 9
  private val stateLegal = f.states.version === 1.U &&
    Mux(
      empty,
      f.states.base === 0.U && f.states.limit === 0.U,
      aligned(f.states.base, f.states.limit) && ((wide(f.states.limit) - wide(f.states.base)) & 511.U) === 0.U
    )
  private val scratchLegal = f.scratch.zipWithIndex
    .map { case (s, e) =>
      if (e < engines) aligned(s.base, s.limit) && wide(s.base) + stateCount * wide(f.scratchStride) <= wide(s.limit)
      else s.asUInt === 0.U
    }
    .reduce(_ && _)
  private val writerLegal = writers
    .map(s =>
      aligned(s.base, s.limit) &&
        !descriptors.map(overlaps(s, _)).reduce(_ || _) && !overlaps(s, states)
    )
    .reduce(_ && _) &&
    (for (a <- writers.indices; b <- a + 1 until writers.size) yield !overlaps(writers(a), writers(b))).reduce(_ && _)
  private val imageEnabled = TypedTileImageRules.enabled(f.image.pixels)
  private val loads = TypedTileImageRules.loads(f.image.pixels)
  private val images = f.image.views.zipWithIndex.map { case (v, i) =>
    (
      imageEnabled(i) &&
        (v.store || loads(i) === AttachmentLoadOp.load.U),
      v,
      v.store
    )
  } ++
    f.image.resolves.map(r => (r.enable, r.destination, true.B))
  private val imageLegal = images
    .map { case (active, v, write) =>
      val allocation = span(v.allocationBase, v.allocationLimit)
      !active || (aligned(v.allocationBase, v.allocationLimit) && inside(v.base, wide(v.limit), q.request.owner) &&
        v.base >= v.allocationBase && v.limit <= v.allocationLimit &&
        !writers.map(overlaps(allocation, _)).reduce(_ || _) &&
        (!write || (!descriptors.map(overlaps(allocation, _)).reduce(_ || _) && !overlaps(allocation, states))))
    }
    .reduce(_ && _)
  private val ids =
    Seq(f.states.identity, f.image.pixels.colors.identity, b.producer.arena.identity, b.bins.pool.identity)
      .map(_.asUInt === q.request.identity.asUInt)
      .reduce(_ && _)
  private val frameVersion = (f.version === 1.U && !b.bins.tileCount(31, 16).orR) ||
    ((pagedCapacity == 65536).B && f.version === 2.U)
  private val legal = NativeBinnedGraphicsLayout.headerLegal(record.header) && frameVersion && ids &&
    b.bins.version === 3.U && !b.bins.resume && b.producer.version === 1.U &&
    b.producer.screenWidth =/= 0.U && b.producer.screenHeight =/= 0.U &&
    (b.producer.tileLog2 === 3.U || b.producer.tileLog2 === 4.U) &&
    f.image.pixels.colors.tileLog2 === b.producer.tileLog2 && f.image.pixels.colors.originX === 0.S &&
    f.image.pixels.colors.originY === 0.S && f.image.pixels.colors.sampleMode <= 2.U &&
    f.scratchStride >= 64.U && f.scratchStride(5, 0) === 0.U && stateLegal && scratchLegal && writerLegal && imageLegal
  private val address = wide(q.job.frame.base) + (wide(line) << 6)
  io.submit.ready := state === State.idle && !io.cancel
  io.memoryRequest.valid := state === State.offer; io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := address(31, 0); io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryRequest.bits.byteMask := Fill(64, 1.U(1.W)); io.memoryRequest.bits.cachePolicy := CachePolicy.uncached
  io.memoryResponse.ready := state === State.waitReply && io.memoryResponse.bits.transactionId === 0.U
  io.completion.valid := state === State.complete; io.completion.bits := result
  io.busy := state =/= State.idle; io.drained := state =/= State.offer && state =/= State.waitReply
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(io.submit.fire) {
    q := io.submit.bits; record := 0.U.asTypeOf(record); result := 0.U.asTypeOf(result)
    result.commandId := io.submit.bits.request.commandId; line := 0.U; cancelled := false.B; malformed := false.B
    if (!enabled) { result.status := 6.U; state := State.complete }
    else {
      when(
        !TessellationJointJobLayout.requestLegal(io.submit.bits.request) ||
          !TessellationJointJobLayout.jobLegal(io.submit.bits.job, io.submit.bits.request)
      ) {
        result.status := 1.U; result.faultAddress := io.submit.bits.job.frame.base; state := State.complete
      }.otherwise { state := State.offer }
    }
  }
  when(io.memoryRequest.fire) { state := State.waitReply }
  when(io.memoryResponse.fire) {
    result.readBytes := result.readBytes + 64.U
    when(io.memoryResponse.bits.fault) {
      result.status := 2.U; result.faultAddress := address(31, 0); state := State.complete
    }
      .elsewhen(cancelled || io.cancel) { result.status := 4.U; state := State.complete }
      .otherwise {
        when(malformedLine) { malformed := true.B }
        when(line === 31.U) { state := State.check }.otherwise { line := line + 1.U; state := State.offer }
      }
  }
  when(state === State.check) {
    when(cancelled || io.cancel) { result.status := 4.U }
      .elsewhen(malformed || !legal) { result.status := 1.U; result.faultAddress := q.job.frame.base }
      .otherwise { result.frame := f; result.clearOnly := empty }
    state := State.complete
  }
  when(io.completion.fire) { state := State.idle }
}
