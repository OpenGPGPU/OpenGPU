package opengpu.graphics

import chisel3._
import chisel3.util._

/** Private interface from primitive/quad preparation, not a memory descriptor.
  * Position, reciprocal W, depth, sample state and point coordinates are already
  * prepared. Varying words in `quad` are ignored. Flat values are canonicalized
  * at vertex zero by clipping. Unused line/point vertices are ignored.
  */
class PreparedFragmentInterpolation(p: VertexShaderStageConfig, g: GraphicsConfig) extends Bundle {
  val quad = new Fp32InterpolatedQuad(p, g)
  val values = Vec(3, Vec(p.varyings, Vec(4, UInt(32.W))))
  val weights = Vec(4, Vec(3, new ClipFloat))
  val denominators = Vec(4, new ClipFloat)
  val centroidWeights = Vec(4, Vec(3, new ClipFloat))
  val centroidDenominators = Vec(4, new ClipFloat)
  val centroidActive = UInt(4.W)
  val centroidMask = UInt((p.varyings * 4).W)
  val preparationStatus = UInt(3.W)
  val preparationFaultLane = UInt(2.W)
  /** Shared setup precedes the lane-major arithmetic accounting. */
  val setupOperations = UInt(16.W)
  val setupArithmeticStatus = UInt(5.W)
  val setupPackingStatus = UInt(5.W)
  val laneOperations = Vec(4, UInt(16.W))
  val laneArithmeticStatus = Vec(4, UInt(5.W))
  val lanePackingStatus = Vec(4, UInt(5.W))
}

/** Four privately owned prepared quads feeding the four spatial component lanes.
  * Accepted data is immutable until its ordered completion retires. Posted row
  * requests drain on cancellation; unposted rows can be abandoned. A held quad
  * or completion remains immutable. A cancellation after a quad offer is
  * reflected in the subsequent completion, which records that output escaped.
  *
  * Per-lane prefixes preserve scalar lane-major first-error accounting despite
  * parallel evaluation. Prepared metadata/depth/sample work remains outside
  * this owner; this block does not establish native renderer throughput.
  * With reservedInputs, an enclosing owner must reserve capacity before
  * preparation and stop fresh work on cancellation. Already owned transfers
  * can then enter during cancellation, avoiding an internal admission deadlock.
  */
class Fp32PreparedQuadInterpolator(p: VertexShaderStageConfig = VertexShaderStageConfig(),
    g: GraphicsConfig = GraphicsConfig(), contexts: Int = 4, reservedInputs: Boolean = false) extends Module {
  require(contexts >= 2 && contexts <= 8 && isPow2(contexts))
  private val contextBits = log2Ceil(contexts)
  private val components = p.varyings * 4
  private val componentBits = log2Ceil(components)
  private val tagBits = contextBits + componentBits
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new PreparedFragmentInterpolation(p, g)))
    val out = Decoupled(new Fp32InterpolatedQuad(p, g))
    val completion = Decoupled(new FragmentInterpolationCompletion)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val bank = Module(new ClipFloatQuadComponent(tagBits))
  bank.io.cancel := false.B // This owner drains every posted row itself.
  private val inputs = Reg(Vec(contexts, new PreparedFragmentInterpolation(p, g)))
  private val quads = Reg(Vec(contexts, new Fp32InterpolatedQuad(p, g)))
  private val live = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val scanDone = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val cancelled = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val status = Reg(Vec(contexts, UInt(3.W)))
  private val cursor = Reg(Vec(contexts, UInt(7.W)))
  private val posted = Reg(Vec(contexts, UInt(7.W)))
  private val returned = Reg(Vec(contexts, UInt(7.W)))
  private val laneFailed = Reg(Vec(contexts, Vec(4, Bool())))
  private val laneOperations = Reg(Vec(contexts, Vec(4, UInt(16.W))))
  private val laneArithmetic = Reg(Vec(contexts, Vec(4, UInt(5.W))))
  private val lanePacking = Reg(Vec(contexts, Vec(4, UInt(5.W))))
  private val allocate = RegInit(0.U(contextBits.W))
  private val issue = RegInit(0.U(contextBits.W))
  private val retire = RegInit(0.U(contextBits.W))
  private val owned = RegInit(0.U(log2Ceil(contexts + 1).W))
  private val cancelling = RegInit(false.B)
  private val idle :: emitQuad :: emitCompletion :: Nil = Enum(3)
  private val publication = RegInit(idle)
  private val publishedQuad = Reg(new Fp32InterpolatedQuad(p, g))
  private val result = Reg(new FragmentInterpolationCompletion)
  private val publicationCancelled = RegInit(false.B)
  io.busy := owned =/= 0.U
  io.in.ready := owned < contexts.U && (if (reservedInputs) true.B else !io.cancel && !cancelling)
  io.out.valid := publication === emitQuad
  io.out.bits := publishedQuad
  io.completion.valid := publication === emitCompletion
  io.completion.bits := result
  when(!io.busy) { cancelling := false.B }
  when(io.cancel && io.busy) { cancelling := true.B }
  switch(Cat(io.in.fire, io.completion.fire)) {
    is("b10".U) { owned := owned + 1.U }
    is("b01".U) { owned := owned - 1.U }
  }

  private val metadataLegal = io.in.bits.quad.primitiveKind <= 2.U &&
    io.in.bits.quad.varyingSlots <= p.varyings.U &&
    !(io.in.bits.quad.flatMask >> (io.in.bits.quad.varyingSlots << 2)).orR &&
    !(io.in.bits.centroidMask >> (io.in.bits.quad.varyingSlots << 2)).orR
  when(io.in.fire) {
    assert(!live(allocate), "prepared quad allocation overwrote owned context")
    inputs(allocate) := io.in.bits
    quads(allocate) := io.in.bits.quad
    for (lane <- 0 until 4; slot <- 0 until p.varyings; component <- 0 until 4) {
      val raw = io.in.bits.quad.primitiveKind === 0.U || io.in.bits.quad.flatMask(slot * 4 + component)
      quads(allocate).lanes(lane).varyings(slot)(component) :=
        Mux(slot.U < io.in.bits.quad.varyingSlots && raw, io.in.bits.values(0)(slot)(component), 0.U)
    }
    live(allocate) := true.B
    scanDone(allocate) := false.B
    cancelled(allocate) := (if (reservedInputs) io.cancel || cancelling else false.B)
    status(allocate) := Mux(io.in.bits.preparationStatus =/= 0.U, io.in.bits.preparationStatus,
      Mux(metadataLegal, 0.U, 1.U))
    cursor(allocate) := 0.U
    posted(allocate) := 0.U
    returned(allocate) := 0.U
    laneFailed(allocate) := VecInit(Seq.fill(4)(false.B))
    laneOperations(allocate) := io.in.bits.laneOperations
    laneArithmetic(allocate) := io.in.bits.laneArithmeticStatus
    lanePacking(allocate) := io.in.bits.lanePackingStatus
    allocate := allocate + 1.U
  }
  when(io.cancel) {
    for (i <- 0 until contexts) {
      when(live(i) && status(i) === 0.U && !laneFailed(i).asUInt.orR) { cancelled(i) := true.B }
    }
  }

  // Separate attribute selection from FP32 decode and bank admission. Posted
  // accounting includes both registered offers, so cancellation cannot free
  // their contexts before they are accepted and returned.
  private val fetchedValid = RegInit(false.B)
  private val fetchedRaw = Reg(Vec(3, UInt(32.W)))
  private val fetchedWeights = Reg(Vec(4, Vec(3, new ClipFloat)))
  private val fetchedDenominators = Reg(Vec(4, new ClipFloat))
  private val fetchedTag = Reg(UInt(tagBits.W))
  private val offerValid = RegInit(false.B)
  private val offer = Reg(new ClipFloatQuadComponentRequest(tagBits))
  private val offerReady = !offerValid || bank.io.in.ready
  private val fetchReady = !fetchedValid || offerReady
  bank.io.in.valid := offerValid
  bank.io.in.bits := offer
  when(offerReady) {
    offerValid := fetchedValid
    when(fetchedValid) {
      offer.tag := fetchedTag
      offer.weights := fetchedWeights
      offer.denominators := fetchedDenominators
      for (v <- 0 until 3) { offer.values(v) := ClipFloat.fromFp32(fetchedRaw(v)) }
    }
  }
  when(fetchReady) { fetchedValid := false.B }
  private val row = cursor(issue)(componentBits - 1, 0)
  private val slot = if (p.varyings == 1) 0.U else row(componentBits - 1, 2)
  private val request = inputs(issue)
  private val rows = request.quad.varyingSlots << 2
  private val skip = status(issue) =/= 0.U || cancelled(issue) || io.cancel || cancelling ||
    request.quad.primitiveKind === 0.U || !VecInit(request.quad.lanes.map(_.perspectiveValid)).asUInt.orR
  when(live(issue) && !scanDone(issue)) {
    when(skip || cursor(issue) >= rows) {
      scanDone(issue) := true.B
      issue := issue + 1.U
    }.elsewhen(request.quad.flatMask(row)) {
      cursor(issue) := cursor(issue) + 1.U // Raw flat words were copied at allocation.
      when(cursor(issue) + 1.U >= rows) { scanDone(issue) := true.B; issue := issue + 1.U }
    }.elsewhen(fetchReady) {
      fetchedValid := true.B
      fetchedTag := Cat(issue, row)
      for (v <- 0 until 3) {
        fetchedRaw(v) := Mux(v.U <= request.quad.primitiveKind, request.values(v)(slot)(row(1, 0)), 0.U)
      }
      for (lane <- 0 until 4) {
        val centroid = request.centroidActive(lane) &&
          (request.centroidMask(row) || request.quad.lanes(lane).sampleLocationValid)
        val active = request.quad.lanes(lane).perspectiveValid
        for (v <- 0 until 3) {
          fetchedWeights(lane)(v) := Mux(active,
            Mux(centroid, request.centroidWeights(lane)(v), request.weights(lane)(v)), ClipFloat.zero)
        }
        fetchedDenominators(lane) := Mux(active,
          Mux(centroid, request.centroidDenominators(lane), request.denominators(lane)), ClipFloat.fromFp32("h3f800000".U))
      }
      posted(issue) := posted(issue) + 1.U
      cursor(issue) := cursor(issue) + 1.U
      when(cursor(issue) + 1.U >= rows) { scanDone(issue) := true.B; issue := issue + 1.U }
    }
  }

  private val writebackValid = RegInit(false.B)
  private val writeback = Reg(new ClipFloatQuadComponentResponse(tagBits))
  bank.io.out.ready := true.B
  writebackValid := bank.io.out.valid
  when(bank.io.out.fire) { writeback := bank.io.out.bits }
  private val target = writeback.tag(tagBits - 1, componentBits)
  private val targetRow = writeback.tag(componentBits - 1, 0)
  private val targetSlot = if (p.varyings == 1) 0.U else targetRow(componentBits - 1, 2)
  when(writebackValid) {
    assert(live(target) && returned(target) < posted(target), "prepared quad lost arithmetic ownership")
    assert(!inputs(target).quad.flatMask(targetRow), "flat component unexpectedly entered arithmetic")
    returned(target) := returned(target) + 1.U
    for (lane <- 0 until 4) {
      val response = writeback.lanes(lane)
      when(inputs(target).quad.lanes(lane).perspectiveValid && !laneFailed(target)(lane)) {
        quads(target).lanes(lane).varyings(targetSlot)(targetRow(1, 0)) := response.fp32
        laneOperations(target)(lane) := laneOperations(target)(lane) + response.operations
        laneArithmetic(target)(lane) := laneArithmetic(target)(lane) | response.arithmeticStatus
        lanePacking(target)(lane) := lanePacking(target)(lane) | response.packingStatus
        when(response.error =/= 0.U && !cancelled(target) && (!io.cancel || laneFailed(target).asUInt.orR)) {
          laneFailed(target)(lane) := true.B
        }
      }
    }
  }

  private val failures = laneFailed(retire).asUInt
  private val faultLane = PriorityEncoder(failures)
  private val failed = failures.orR
  private val cutoff = Mux(status(retire) =/= 0.U, inputs(retire).preparationFaultLane, faultLane)
  private val includeLane = (0 until 4).map(lane => !(failed || status(retire) =/= 0.U) || lane.U <= cutoff)
  private val operations = (0 until 4).map(lane => Mux(includeLane(lane), laneOperations(retire)(lane), 0.U))
  private val arithmetic = (0 until 4).map(lane => Mux(includeLane(lane), laneArithmetic(retire)(lane), 0.U))
  private val packing = (0 until 4).map(lane => Mux(includeLane(lane), lanePacking(retire)(lane), 0.U))
  private val headReady = live(retire) && scanDone(retire) && returned(retire) === posted(retire)
  when(publication === idle && headReady) {
    publishedQuad := quads(retire)
    result := 0.U.asTypeOf(result)
    val q = quads(retire)
    result.identity := q.identity
    result.sequence := q.sequence
    result.primitiveId := q.primitiveId
    result.instanceId := q.instanceId
    result.partIndex := q.partIndex
    result.status := Mux(status(retire) =/= 0.U, status(retire), Mux(failed, 7.U,
      Mux(cancelled(retire) || io.cancel, 4.U, 0.U)))
    result.faultLane := Mux(status(retire) =/= 0.U, inputs(retire).preparationFaultLane, Mux(failed, faultLane, 0.U))
    result.invalidHelpers := VecInit(q.lanes.map(lane => !lane.covered && !lane.perspectiveValid)).asUInt
    result.arithmeticOperations := inputs(retire).setupOperations + operations.reduce(_ +& _)
    result.arithmeticStatus := inputs(retire).setupArithmeticStatus | arithmetic.reduce(_ | _)
    result.packingStatus := inputs(retire).setupPackingStatus | packing.reduce(_ | _)
    publicationCancelled := false.B
    publication := Mux(status(retire) === 0.U && !failed && !cancelled(retire) && !io.cancel, emitQuad, emitCompletion)
  }
  when(publication === emitQuad) {
    when(io.cancel) { publicationCancelled := true.B }
    when(io.out.fire) {
      result.output := true.B
      when(publicationCancelled || io.cancel) { result.status := 4.U }
      publication := emitCompletion
    }
  }
  when(io.completion.fire) {
    assert(headReady, "prepared quad retired before all posted arithmetic drained")
    live(retire) := false.B
    retire := retire + 1.U
    publication := idle
  }
}
