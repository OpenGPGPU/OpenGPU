package opengpu.graphics

import chisel3._
import chisel3.util._

/** A native level owner has already admitted binding/sampler identity, format
  * compatibility and swizzle. Keep only the selected immutable level here.
  */
class TextureRgba8GroupRequest extends Bundle {
  val lookup = new TextureImageLevelLookup
  val view = new TextureImageLevelView
  val format = UInt(6.W)
  val coordinates = Vec(2, UInt(32.W))
  val offsets = Vec(2, SInt(5.W))
  val wraps = Vec(2, UInt(2.W))
  val layer = UInt(32.W); val face = UInt(32.W)
  val linear = Bool(); val shadow = Bool(); val tag = UInt(32.W)
}
object TextureRgba8GroupRequest {
  /** Used only after TextureImageLevelSampleUnit's existing check succeeds. */
  def capture(q: TextureImageLevelSampleRequest): TextureRgba8GroupRequest = {
    val result = Wire(new TextureRgba8GroupRequest)
    result.lookup := q.image.selected; result.view := q.image.view; result.format := q.image.format
    for (a <- 0 until 2) { result.coordinates(a) := q.coordinates(a); result.offsets(a) := q.offsets(a) }
    result.wraps := VecInit(Seq(q.sampler.state.wrapS, q.sampler.state.wrapT))
    result.layer := q.layer; result.face := q.face; result.linear := q.linear
    result.shadow := q.shadow; result.tag := q.tag
    result
  }
}
class TextureRgba8GroupPlan extends Bundle {
  val group = new Rgba8Fp32TextureRequest
  val tag = UInt(32.W)
  val status = UInt(3.W); val faultSource = UInt(3.W)
  val eligible = Bool(); val fallback = Bool(); val cancelled = Bool()
  val uniqueTaps = UInt(3.W)
}

/** Two exact FP32 coordinate pipelines and four complete checked-address
  * pipelines form one group/cycle. No descriptor, PTE or data reads occur.
  * Unsupported formats/modes or valid unaligned spans request the general
  * native fallback; malformed supported spans never publish a group.
  * Every active span is checked before any group can reach translation.
  */
class TextureRgba8GroupPlanner(p: TextureImageAddressConfig = TextureImageAddressConfig(), entries: Int = 64) extends Module {
  require(isPow2(entries) && entries >= 16 && entries <= 256)
  private val indexBits = log2Ceil(entries)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TextureRgba8GroupRequest))
    val out = Decoupled(new TextureRgba8GroupPlan)
    val cancel = Input(Bool())
    val cancelGroup = Flipped(Valid(new BankedTextureGroupCancel))
    val busy = Output(Bool())
  })
  private class Metadata extends Bundle {
    val view = new TextureImageLevelView
    val layer = UInt(32.W); val face = UInt(3.W); val linear = Bool()
    val supported = Bool(); val admissionStatus = UInt(3.W); val tableStatus = UInt(3.W)
  }
  private val metadata = Reg(Vec(entries, new Metadata))
  // Check table capacity in parallel with the eight-stage coordinate pipeline.
  // Splitting span subtraction from handle comparison removes the composed
  // request -> wide record-end arithmetic -> per-row status timing path.
  // For a nonempty aligned span, base + handle*128 + 128 <= limit is exactly
  // handle < (limit-base)/128. Keep all33 span bits, including the4GiB endpoint.
  private class TableAdmission extends Bundle {
    val owner = UInt(indexBits.W); val span = UInt(33.W); val handle = UInt(32.W)
    val header = Bool(); val sameLevel = Bool()
  }
  private val tableAdmission = Reg(new TableAdmission)
  private val tableAdmissionValid = RegNext(io.in.fire, false.B)
  private val results = Reg(Vec(entries, new TextureRgba8GroupPlan))
  private val alive = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val completed = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val cancelled = Reg(Vec(entries, Bool()))
  private val axisStatus = Reg(Vec(entries, UInt(3.W)))
  private val head = RegInit(0.U(indexBits.W)); private val tail = RegInit(0.U(indexBits.W))
  private val count = RegInit(0.U(log2Ceil(entries + 1).W))
  private val draining = RegInit(false.B)
  private val axes = Seq.fill(2)(Module(new TextureFloatAxisPipeline))
  private val addresses = Seq.fill(4)(Module(new TextureImageAddressUnit(p, pipelineBounds = true, pipelineProducts = true)))
  private val output = Module(new Queue(new TextureRgba8GroupPlan, 1, pipe = true))
  private val stop = io.cancel || draining
  io.busy := count =/= 0.U
  when(io.cancel && io.busy) { draining := true.B }
  when(!io.busy) { draining := false.B }
  private def scoped(e: UInt): Bool = io.cancelGroup.valid &&
    io.cancelGroup.bits.identity.asUInt === results(e).group.identity.asUInt && io.cancelGroup.bits.tag === results(e).tag
  for (e <- 0 until entries) { when(alive(e) && scoped(e.U)) { cancelled(e) := true.B } }

  private val axisReady = axes.map(_.io.in.ready).reduce(_ && _)
  private val allow = !stop && count < entries.U
  io.in.ready := allow && axisReady
  for ((axis, a) <- axes.zipWithIndex) {
    val q = io.in.bits; val cube = q.view.target === TextureImageTarget.cube.U
    axis.io.in.valid := io.in.valid && allow && axisReady
    axis.io.in.bits.coordinate := q.coordinates(a)
    axis.io.in.bits.extent := (if (a == 0) q.view.texWidth else q.view.texHeight)
    axis.io.in.bits.wrap := Mux(cube, Mux(q.linear, TextureFloatAxisWrap.cubeBorder.U, TextureFloatAxisWrap.clamp.U), q.wraps(a))
    axis.io.in.bits.offset := q.offsets(a); axis.io.in.bits.linear := q.linear; axis.io.in.bits.tag := tail
  }
  when(io.in.fire) {
    assert(!alive(tail), "group planner reused an owned slot")
    val q = io.in.bits; val v = q.view; val table = q.lookup.table
    val cube = v.target === TextureImageTarget.cube.U; val array = v.target === TextureImageTarget.array2D.U
    val selected = Mux(array, q.layer < v.layers, q.layer === 0.U) && Mux(cube, q.face < 6.U, q.face === 0.U)
    val offsets = q.offsets.map(o => o >= (-8).S && o <= 7.S).reduce(_ && _) && (!cube || q.offsets.map(_ === 0.S).reduce(_ && _))
    val layout = TextureImageFormat.targetLegal(q.format, v.target) && v.block4x4 === TextureImageFormat.block4x4(q.format) &&
      v.blockBytes === TextureImageFormat.bytes(q.format) && (!q.linear || q.shadow || TextureImageFormat.filterable(q.format))
    val wraps = q.wraps.map(_ <= TextureFloatAxisWrap.mirroredRepeat.U).reduce(_ && _)
    tableAdmission.owner := tail
    tableAdmission.span := table.limit - table.base.pad(33)
    tableAdmission.handle := q.lookup.handle
    tableAdmission.header := table.version === 1.U && table.base(5, 0) === 0.U && table.limit(5, 0) === 0.U &&
      table.limit > table.base && table.limit <= (BigInt(1) << 32).U
    tableAdmission.sameLevel := v.level === q.lookup.level
    metadata(tail).view := v; metadata(tail).layer := q.layer; metadata(tail).face := q.face(2, 0); metadata(tail).linear := q.linear
    metadata(tail).supported := q.format === TextureImageFormat.id("RGBA8").U && !q.shadow && v.target <= TextureImageTarget.cube.U
    metadata(tail).admissionStatus := Mux(!layout || !wraps, TextureImageFetchStatus.format.U,
      Mux(!offsets, TextureImageResourceStatus.view.U, Mux(!selected, TextureImageResourceStatus.coordinate.U, 0.U)))
    results(tail) := 0.U.asTypeOf(results(tail)); results(tail).tag := q.tag
    results(tail).group.identity := q.lookup.table.identity
    // Full table address/record handle, with no lossy descriptor hash.
    results(tail).group.textureKey := Cat(table.base, q.lookup.handle)
    results(tail).group.tag := q.tag
    alive(tail) := true.B; completed(tail) := false.B
    cancelled(tail) := io.cancelGroup.valid && io.cancelGroup.bits.identity.asUInt === q.lookup.table.identity.asUInt && io.cancelGroup.bits.tag === q.tag
    tail := tail + 1.U
  }

  // This owned update drains even after cancellation, preserving primary
  // table faults. Coordinate/address results cannot arrive before this edge.
  when(tableAdmissionValid) {
    val row = tableAdmission.owner
    assert(alive(row) && !completed(row), "table admission lost its planner owner")
    val legalTable = tableAdmission.header && tableAdmission.span(6, 0) === 0.U &&
      tableAdmission.handle < tableAdmission.span(32, 7)
    metadata(row).tableStatus := Mux(!legalTable, TextureImageResourceStatus.view.U,
      Mux(!tableAdmission.sameLevel, TextureImageResourceStatus.encoding.U, 0.U))
  }

  private val axesValid = axes.map(_.io.out.valid).reduce(_ && _)
  private val owner = axes.head.io.out.bits.tag(indexBits - 1, 0)
  private val m = metadata(owner)
  private val weights = VecInit(axes.map(a => Mux(m.linear, a.io.out.bits.weight, 0.U)))
  private val active = VecInit((0 until 4).map(t => (0 until 2).map(a =>
    Mux(weights(a)(30, 0) === 0.U, ((t & (1 << a)) == 0).B,
      Mux(weights(a) === "h3f800000".U, ((t & (1 << a)) != 0).B, true.B))).reduce(_ && _)))
  private class Location extends Bundle { val x = UInt(14.W); val y = UInt(14.W); val face = UInt(3.W) }
  private val locations = Wire(Vec(4, new Location))
  for (t <- 0 until 4) {
    val x = if (t % 2 == 0) axes(0).io.out.bits.first else axes(0).io.out.bits.second
    val y = if (t / 2 == 0) axes(1).io.out.bits.first else axes(1).io.out.bits.second
    val cube = TextureCubeTap(m.face, x, y, m.view.texWidth)
    locations(t).x := Mux(m.view.target === TextureImageTarget.cube.U, cube.x, x.asUInt)
    locations(t).y := Mux(m.view.target === TextureImageTarget.cube.U, cube.y, y.asUInt)
    locations(t).face := Mux(m.view.target === TextureImageTarget.cube.U, cube.face, 0.U)
  }
  // Register the selected metadata and projected locations before duplicate
  // detection/popcount and address issue. The old composed timing path crossed
  // the owner-indexed metadata mux, cube-edge mapping and unique-tap count.
  // Two elastic entries retain one complete group per cycle under backpressure.
  private class LocatedGroup extends Bundle {
    val owner = UInt(indexBits.W); val view = new TextureImageLevelView
    val layer = UInt(32.W); val locations = Vec(4, new Location)
    val active = Vec(4, Bool()); val weights = Vec(2, UInt(32.W)); val axisStatus = UInt(3.W)
  }
  private val located = Module(new Queue(new LocatedGroup, 2))
  located.io.enq.valid := axesValid
  located.io.enq.bits.owner := owner; located.io.enq.bits.view := m.view
  located.io.enq.bits.layer := m.layer; located.io.enq.bits.locations := locations
  located.io.enq.bits.active := active; located.io.enq.bits.weights := weights
  private val badAxes = VecInit(axes.map(_.io.out.bits.status =/= 0.U))
  located.io.enq.bits.axisStatus := Mux(badAxes.asUInt.orR,
    VecInit(axes.map(_.io.out.bits.status))(PriorityEncoder(badAxes)), 0.U)
  axes.foreach(_.io.out.ready := axesValid && located.io.enq.ready)
  when(located.io.enq.fire) {
    axes.foreach(a => assert(a.io.out.bits.tag < entries.U && a.io.out.bits.tag === owner))
    assert(alive(owner) && !completed(owner))
  }
  private val point = located.io.deq.bits
  private val first = PriorityEncoder(point.active)
  private val addressReady = addresses.map(_.io.in.ready).reduce(_ && _)
  located.io.deq.ready := addressReady
  for ((address, t) <- addresses.zipWithIndex) {
    val loc = point.locations(Mux(point.active(t), t.U, first))
    address.io.in.valid := located.io.deq.valid && addressReady
    address.io.in.bits.view := point.view; address.io.in.bits.tag := point.owner
    address.io.in.bits.x := loc.x; address.io.in.bits.y := loc.y; address.io.in.bits.z := 0.U
    address.io.in.bits.layer := point.layer; address.io.in.bits.face := loc.face
  }
  when(located.io.deq.fire) {
    val row = point.owner
    assert(alive(row) && !completed(row))
    axisStatus(row) := point.axisStatus
    results(row).group.axisWeights := point.weights
    for (t <- 0 until 4) results(row).group.rowParity(t) := point.locations(Mux(point.active(t), t.U, first)).y(0)
    results(row).uniqueTaps := PopCount(VecInit((0 until 4).map(t => point.active(t) &&
      !(0 until t).map(j => point.active(j) && point.locations(j).asUInt === point.locations(t).asUInt).foldLeft(false.B)(_ || _))))
  }

  private val addressValid = addresses.map(_.io.out.valid).reduce(_ && _)
  addresses.foreach(_.io.out.ready := addressValid)
  private val readyOwner = addresses.head.io.out.bits.tag(indexBits - 1, 0)
  when(addressValid) {
    addresses.foreach(a => assert(a.io.out.bits.tag < entries.U && a.io.out.bits.tag === readyOwner))
    assert(alive(readyOwner) && !completed(readyOwner))
    val m = metadata(readyOwner)
    val bad = VecInit(addresses.map(_.io.out.bits.status =/= 0.U))
    val addrStatus = VecInit(addresses.map(_.io.out.bits.status))(PriorityEncoder(bad))
    val mapped = MuxLookup(addrStatus, TextureImageResourceStatus.view.U)(Seq(
      TextureImageAddressStatus.ok.U -> 0.U,
      TextureImageAddressStatus.invalidView.U -> TextureImageResourceStatus.view.U,
      TextureImageAddressStatus.coordinate.U -> TextureImageResourceStatus.coordinate.U,
      TextureImageAddressStatus.bounds.U -> TextureImageResourceStatus.bounds.U))
    val checked = Mux(axisStatus(readyOwner) =/= 0.U, axisStatus(readyOwner),
      Mux(m.tableStatus =/= 0.U, m.tableStatus, Mux(bad.asUInt.orR, mapped, 0.U)))
    val code = Mux(m.admissionStatus =/= 0.U, m.admissionStatus, Mux(m.supported, checked, 0.U))
    val source = Mux(m.admissionStatus =/= 0.U, Mux(m.admissionStatus === TextureImageResourceStatus.coordinate.U, 4.U, 3.U),
      Mux(axisStatus(readyOwner) =/= 0.U, 4.U, 1.U))
    results(readyOwner).status := code; results(readyOwner).faultSource := Mux(code =/= 0.U, source, 0.U)
    results(readyOwner).eligible := code === 0.U && m.supported && addresses.map(_.io.out.bits.address(1, 0) === 0.U).reduce(_ && _)
    for (t <- 0 until 4) results(readyOwner).group.addresses(t) := addresses(t).io.out.bits.address
    completed(readyOwner) := true.B
  }

  private val rowStopped = stop || cancelled(head) || scoped(head)
  output.io.enq.valid := alive(head) && completed(head)
  output.io.enq.bits := results(head)
  output.io.enq.bits.status := Mux(results(head).status =/= 0.U, results(head).status,
    Mux(rowStopped, TextureImageResourceStatus.cancelled.U, 0.U))
  output.io.enq.bits.cancelled := rowStopped
  output.io.enq.bits.faultSource := Mux(results(head).status =/= 0.U, results(head).faultSource, 0.U)
  output.io.enq.bits.eligible := results(head).eligible && !rowStopped
  output.io.enq.bits.fallback := output.io.enq.bits.status === 0.U && !output.io.enq.bits.eligible
  when(!output.io.enq.bits.eligible) { output.io.enq.bits.group := 0.U.asTypeOf(output.io.enq.bits.group); output.io.enq.bits.uniqueTaps := 0.U }
  when(output.io.enq.fire) { alive(head) := false.B; head := head + 1.U }
  io.out <> output.io.deq
  when(io.in.fire =/= io.out.fire) { count := Mux(io.in.fire, count + 1.U, count - 1.U) }
  assert(count <= entries.U)
}
