package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, VectorTlbFlush}

class Rgba8Fp32TextureRequest extends Bundle {
  val identity = new GraphicsWorkIdentity
  val textureKey = UInt(64.W)
  val tag = UInt(32.W)
  val addresses = Vec(4, UInt(32.W))
  val rowParity = Vec(4, Bool())
  val axisWeights = Vec(2, UInt(32.W))
}

/** Virtual RGBA8 taps to native FP32 bilinear results. Coordinate/resource
  * bounds are checked upstream. Every active tap is translated before data
  * access, including subnormal-weight contributions. No FP32 weight is
  * quantized: unit-sum Q16 values below describe admission masks only.
  *
  * Ownership extends through decode, filtering and the public result, pinning
  * the VM epoch and preserving scoped cancellation after the child retires.
  * faultSource is 1 for translation, 2 for data, 3 for invalid axis weights.
  * Raw child mode has no weighted-sum normalizer; four byte decoders precede
  * the native hierarchical FP32 filter. The whole pipeline drains on cancel.
  */
class TranslatedRgba8Fp32TextureService(config: GpuConfig = GpuConfig(),
    dataBytes: Int = 8192, entries: Int = 128, useBlackBoxes: Boolean = false) extends Module {
  require(config.xLen == 32 && isPow2(entries) && entries >= 16 && entries <= 256)
  private val indexBits = log2Ceil(entries)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Rgba8Fp32TextureRequest))
    val out = Decoupled(new TranslatedBankedRgba8Result)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 16))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 16)))
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val pageWalkResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool())
    val cancelGroup = Flipped(Valid(new BankedTextureGroupCancel))
    val invalidate = Input(Bool())
    val busy = Output(Bool())
    val occupiedFills = Output(UInt(5.W))
    val joined = Output(Bool())
  })
  private val backend = Module(new TranslatedBankedRgba8TextureService(config, dataBytes,
    useBlackBoxes = useBlackBoxes, rawTapOutput = true))
  private val decode = Seq.fill(4)(Module(new Rgba8WeightedToFp32(indexBits)))
  private val filter = Module(new TextureBilinearFilterPipeline())
  private val requests = Reg(Vec(entries, new Rgba8Fp32TextureRequest))
  private val results = Reg(Vec(entries, new TranslatedBankedRgba8Result))
  private val alive = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val completed = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val cancelled = Reg(Vec(entries, Bool()))
  private val badWeights = Reg(Vec(entries, Bool()))
  private val head = RegInit(0.U(indexBits.W))
  private val tail = RegInit(0.U(indexBits.W))
  private val count = RegInit(0.U(log2Ceil(entries + 1).W))
  private val pending = Module(new Queue(UInt(indexBits.W), 16))
  private val owners = Module(new Queue(UInt(indexBits.W), 32))
  private val outputs = Module(new Queue(new TranslatedBankedRgba8Result, 1, pipe = true))
  private val draining = RegInit(false.B)
  private val pinnedSatp = RegInit(0.U(32.W))
  private val vmChanged = io.satp =/= pinnedSatp
  private val stop = io.cancel || io.invalidate || draining
  private def scoped(e: UInt): Bool = io.cancelGroup.valid &&
    io.cancelGroup.bits.identity.asUInt === requests(e).identity.asUInt &&
    io.cancelGroup.bits.tag === requests(e).tag

  io.memoryRequest <> backend.io.memoryRequest
  backend.io.memoryResponse <> io.memoryResponse
  io.pageWalk <> backend.io.pageWalk
  backend.io.pageWalkResponse <> io.pageWalkResponse
  io.occupiedFills := backend.io.occupiedFills; io.joined := backend.io.joined
  backend.io.satp := pinnedSatp; backend.io.flush <> io.flush
  backend.io.cancel := io.cancel
  backend.io.invalidate := io.invalidate || draining
  backend.io.cancelGroup <> io.cancelGroup
  decode.foreach(_.io.cancel := false.B)
  filter.io.cancel := false.B
  io.out <> outputs.io.deq
  io.busy := count =/= 0.U || backend.io.busy || pending.io.deq.valid || owners.io.deq.valid ||
    decode.map(_.io.busy).reduce(_ || _) || filter.io.busy
  when(io.busy) { assert(!vmChanged, "native FP32 texture VM epoch changed before final retirement") }
  when(io.flush.valid) { assert(!io.busy, "native FP32 texture translation flush requires complete drain") }
  when(!io.busy && vmChanged) { pinnedSatp := io.satp }
  when((io.cancel || io.invalidate) && io.busy) { draining := true.B }
  when(!io.busy) { draining := false.B }
  for (e <- 0 until entries) {
    when(alive(e) && (stop || scoped(e.U))) { cancelled(e) := true.B }
  }

  io.in.ready := !stop && !vmChanged && !io.flush.valid && count < entries.U && pending.io.enq.ready
  pending.io.enq.valid := io.in.valid && !stop && !vmChanged && !io.flush.valid && count < entries.U
  pending.io.enq.bits := tail
  when(io.in.fire) {
    assert(!alive(tail), "native FP32 texture slot reused before final ownership transfer")
    val bad = io.in.bits.axisWeights.map(w =>
      (w(31) && w(30, 0).orR) || w(30, 0) > "h3f800000".U).reduce(_ || _)
    requests(tail) := io.in.bits; alive(tail) := true.B; completed(tail) := false.B
    badWeights(tail) := bad
    cancelled(tail) := io.cancelGroup.valid &&
      io.cancelGroup.bits.identity.asUInt === io.in.bits.identity.asUInt && io.cancelGroup.bits.tag === io.in.bits.tag
    results(tail) := 0.U.asTypeOf(results(tail))
    results(tail).identity := io.in.bits.identity; results(tail).tag := io.in.bits.tag
    results(tail).fault := bad; results(tail).faultSource := Mux(bad, 3.U, 0.U)
    tail := tail + 1.U
  }

  private val issue = pending.io.deq.bits
  private val skip = badWeights(issue) || cancelled(issue) || scoped(issue) || stop
  backend.io.in.valid := pending.io.deq.valid && !skip && owners.io.enq.ready
  pending.io.deq.ready := skip || (backend.io.in.ready && owners.io.enq.ready)
  backend.io.in.bits := 0.U.asTypeOf(backend.io.in.bits)
  backend.io.in.bits.identity := requests(issue).identity
  backend.io.in.bits.textureKey := requests(issue).textureKey
  backend.io.in.bits.tag := requests(issue).tag
  backend.io.in.bits.addresses := requests(issue).addresses
  backend.io.in.bits.rowParity := requests(issue).rowParity
  private val wx = requests(issue).axisWeights(0)
  private val wy = requests(issue).axisWeights(1)
  private val xActive = VecInit(Seq(wx =/= "h3f800000".U, wx(30, 0).orR))
  private val yActive = VecInit(Seq(wy =/= "h3f800000".U, wy(30, 0).orR))
  private val active = VecInit((0 until 4).map(t => xActive(t % 2) && yActive(t / 2)))
  private val activeCount = PopCount(active)
  private val admissionWeight = Mux(activeCount === 1.U, 65536.U, Mux(activeCount === 2.U, 32768.U, 16384.U))
  for (t <- 0 until 4) backend.io.in.bits.weights(t) := Mux(active(t), admissionWeight, 0.U)
  owners.io.enq.valid := backend.io.in.fire; owners.io.enq.bits := issue
  when(pending.io.deq.fire) {
    assert(alive(issue), "native FP32 texture request lost its slot")
    when(skip) { completed(issue) := true.B }
  }

  private val decodeReady = decode.map(_.io.in.ready).reduce(_ && _)
  backend.io.out.ready := owners.io.deq.valid && decodeReady
  owners.io.deq.ready := backend.io.out.valid && decodeReady
  for ((lane, t) <- decode.zipWithIndex) {
    lane.io.in.valid := backend.io.out.valid && owners.io.deq.valid && decodeReady
    lane.io.in.bits.tag := owners.io.deq.bits; lane.io.in.bits.cancelled := false.B
    for (c <- 0 until 4) lane.io.in.bits.numerators(c) :=
      Cat(backend.io.out.bits.rawTaps.get(t)(8 * c + 7, 8 * c), 0.U(16.W))
  }
  when(backend.io.out.fire) {
    val e = owners.io.deq.bits; val b = backend.io.out.bits
    assert(alive(e) && !completed(e), "raw texture reply lost its native FP32 owner")
    assert(b.identity.asUInt === requests(e).identity.asUInt && b.tag === requests(e).tag)
    results(e).hit := b.hit; results(e).fault := b.fault
    results(e).faultSource := b.faultSource; results(e).faultAddress := b.faultAddress
    results(e).missingTaps := b.missingTaps
    when(b.cancelled) { cancelled(e) := true.B }
  }

  private val decodedValid = decode.map(_.io.out.valid).reduce(_ && _)
  private val decodedOwner = decode.head.io.out.bits.tag
  filter.io.in.valid := decodedValid
  decode.foreach(_.io.out.ready := decodedValid && filter.io.in.ready)
  filter.io.in.bits.axes := 2.U; filter.io.in.bits.tag := decodedOwner
  filter.io.in.bits.weights := requests(decodedOwner).axisWeights
  for (t <- 0 until 4) filter.io.in.bits.values(t) := decode(t).io.out.bits.values
  when(filter.io.in.fire) {
    assert(alive(decodedOwner) && !completed(decodedOwner))
    decode.foreach(lane => assert(lane.io.out.bits.tag === decodedOwner, "RGBA8 decode tap association changed"))
  }
  filter.io.out.ready := true.B
  when(filter.io.out.fire) {
    val e = filter.io.out.bits.tag(indexBits - 1, 0)
    assert(filter.io.out.bits.tag < entries.U && alive(e) && !completed(e))
    assert(filter.io.out.bits.status === 0.U, "admitted FP32 texture weights faulted inside filter")
    results(e).values := filter.io.out.bits.rgba; completed(e) := true.B
  }

  private val rowStopped = cancelled(head) || scoped(head) || stop
  outputs.io.enq.valid := alive(head) && completed(head)
  outputs.io.enq.bits := results(head)
  outputs.io.enq.bits.cancelled := rowStopped
  outputs.io.enq.bits.hit := results(head).hit && !results(head).fault && !rowStopped
  outputs.io.enq.bits.missingTaps := Mux(rowStopped, 0.U, results(head).missingTaps)
  for (c <- 0 until 4) outputs.io.enq.bits.values(c) :=
    Mux(outputs.io.enq.bits.hit, results(head).values(c), 0.U)
  when(outputs.io.enq.fire) { alive(head) := false.B; head := head + 1.U }
  when(io.in.fire =/= io.out.fire) { count := Mux(io.in.fire, count + 1.U, count - 1.U) }
  assert(count <= entries.U)
  when(io.out.valid) { assert(count =/= 0.U) }
}
