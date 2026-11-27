package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

class TessellationVertexTypedRangeBegin(p: VertexShaderStageConfig) extends Bundle {
  val range = new TessellationVertexPatchSequenceDescriptor(p)
  val firstSequence = UInt(64.W); val firstPrimitiveId = UInt(32.W)

  /** An actual capture error or a cross-link rejection; zero admits the range. */
  val admissionStatus = UInt(3.W)
}
class TessellationVertexTypedRangeRetirement extends Bundle {
  val taskId = UInt(32.W); val status = UInt(3.W)
  val publishedValid = Bool(); val published = UInt(16.W)
}
class TessellationVertexTypedRangeResult extends Bundle {
  val range = new TessellationVertexPatchSequenceCompletion
  val admissionRejected = Bool(); val published = UInt(64.W)
}

/** Internal exclusive publication cursor. One-past-MAX is representable, but cannot be narrowed into another real
  * launch. This is not a wire ABI.
  */
class TessellationJointPublicationCursor extends Bundle {
  val sequence = UInt(65.W); val primitiveId = UInt(33.W)
}

/** Original-draw iteration plus publication cursors. Retirement is supplied by the typed parent's actual report; it is
  * not a publisher/CU completion. taskDrained excludes the held task report and all unrelated frame traffic.
  */
class TessellationVertexTypedRangeControl(p: VertexShaderStageConfig, jointCursors: Boolean = false) extends Module {
  def this(p: VertexShaderStageConfig) = this(p, false)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationVertexTypedRangeBegin(p)))
    val task = Decoupled(new TessellationVertexPatchTask(p))
    val firstSequence = Output(UInt(64.W)); val firstPrimitiveId = Output(UInt(32.W))
    val expectedTaskId = Output(UInt(32.W))
    val retired = Flipped(Decoupled(new TessellationVertexTypedRangeRetirement))
    val taskDrained = Input(Bool()); val cancel = Input(Bool()); val cancelChild = Output(Bool())
    val completion = Decoupled(new TessellationVertexTypedRangeResult)
    val busy = Output(Bool()); val drained = Output(Bool())
    val cursor = if (jointCursors) Some(Input(new TessellationJointPublicationCursor)) else None
    val nextCursor = if (jointCursors) Some(Output(new TessellationJointPublicationCursor)) else None
  })
  private object State extends ChiselEnum { val idle, start, running, reject, report = Value }
  private val state = RegInit(State.idle)
  private val descriptor = Reg(new TessellationVertexTypedRangeBegin(p))
  private val sequence = Module(new TessellationVertexPatchSequence(p, nextAdmissionAvailable = true))
  private val cursor = Reg(UInt(64.W)); private val primitive = Reg(UInt(32.W))
  private val withinInstance = Reg(UInt(32.W))
  private val nextFault = RegInit(false.B); private val expected = Reg(UInt(32.W))
  private val offered = RegInit(false.B)
  private val result = Reg(new TessellationVertexTypedRangeResult)
  private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel
  private val jointCursor = if (jointCursors) Some(Reg(new TessellationJointPublicationCursor)) else None
  private val cursorResult = if (jointCursors) Some(Reg(new TessellationJointPublicationCursor)) else None
  private val originalInstanceEnd = if (jointCursors) Some(Reg(Bool())) else None
  private val cursorFault = if (jointCursors) Some(RegInit(false.B)) else None
  if (jointCursors) io.nextCursor.get := cursorResult.get
  io.begin.ready := state === State.idle && io.taskDrained && !io.cancel
  when(io.begin.fire) {
    descriptor := io.begin.bits; cursor := io.begin.bits.firstSequence; primitive := io.begin.bits.firstPrimitiveId
    withinInstance := 0.U; nextFault := false.B; cancelled := false.B; offered := false.B
    result := 0.U.asTypeOf(result); state := State.start
    if (jointCursors) {
      jointCursor.get := io.cursor.get; cursorResult.get := io.cursor.get; cursorFault.get := false.B
      val r = io.begin.bits.range; val vertices = r.template.layout.inputVertices
      val divisor = Mux(vertices === 0.U, 1.U, vertices)
      val aligned = r.patchCount === 0.U || r.template.slice.firstOrdinal % divisor === 0.U
      val inDomain = io.cursor.get.sequence <= (BigInt(1) << 64).U &&
        io.cursor.get.primitiveId <= (BigInt(1) << 32).U
      val launchable = r.patchCount === 0.U || (io.cursor.get.sequence < (BigInt(1) << 64).U &&
        io.cursor.get.primitiveId < (BigInt(1) << 32).U)
      when(io.begin.bits.admissionStatus === 0.U && (!aligned || !inDomain || !launchable)) {
        descriptor.admissionStatus := 1.U
      }
    }
  }
  when(state =/= State.idle && state =/= State.report && io.cancel) { cancelled := true.B }
  sequence.io.begin.valid := state === State.start && descriptor.admissionStatus === 0.U
  sequence.io.begin.bits := descriptor.range
  sequence.io.cancel := stop && state === State.running
  sequence.io.childBusy := !io.taskDrained
  sequence.io.nextAdmissionFault.get := nextFault
  sequence.io.task.ready := io.task.ready && state === State.running
  io.task.valid := sequence.io.task.valid && state === State.running; io.task.bits := sequence.io.task.bits
  io.firstSequence := cursor; io.firstPrimitiveId := primitive; io.expectedTaskId := expected
  if (jointCursors) {
    io.firstSequence := jointCursor.get.sequence(63, 0); io.firstPrimitiveId := jointCursor.get.primitiveId(31, 0)
  }
  when(io.task.fire) {
    expected := io.task.bits.taskId; offered := true.B
    if (jointCursors) {
      val task = io.task.bits; val vertices = task.layout.inputVertices
      val divisor = Mux(vertices === 0.U, 1.U, vertices)
      // The original GND and the accepted actual task ordinal define the
      // instance boundary. A directory segment boundary grants no reset.
      originalInstanceEnd.get := ((task.slice.firstOrdinal / divisor) +& 1.U) === (task.draw.input.count / divisor)
    }
  }
  sequence.io.retired.valid := io.retired.valid && state === State.running
  sequence.io.retired.bits.taskId := io.retired.bits.taskId; sequence.io.retired.bits.status := io.retired.bits.status
  io.retired.ready := state === State.running && sequence.io.retired.ready
  when(io.retired.fire) {
    val c = io.retired.bits
    val owned = c.taskId === expected
    when(c.publishedValid && owned) {
      val total = result.published +& c.published
      assert(!total(64), "actual range publication total overflow")
      result.published := total(63, 0)
    }
    val next = cursor +& c.published; val nextPrimitive = primitive +& c.published
    val nextInstance = withinInstance +& 1.U === descriptor.range.patchesPerInstance
    // Rasterizer PrimitiveID resets between original instances, whereas the
    // independent 64-bit ordering sequence remains monotonic across them.
    nextFault := !c.publishedValid || next(64) || (nextPrimitive(32) && !nextInstance)
    when(c.status === 0.U && owned && c.publishedValid) {
      cursor := next(63, 0); primitive := Mux(nextInstance, 0.U, nextPrimitive(31, 0))
      withinInstance := Mux(nextInstance, 0.U, withinInstance + 1.U)
    }
    if (jointCursors) {
      val seq = jointCursor.get.sequence +& c.published
      val prim = jointCursor.get.primitiveId +& c.published
      val end = originalInstanceEnd.get
      val invalid = !c.publishedValid || !owned || seq > (BigInt(1) << 64).U ||
        (!end && prim > (BigInt(1) << 32).U)
      // Equality is a valid exclusive endpoint for this last task. It blocks
      // only a subsequent actual task that would need an unrepresentable start.
      nextFault := invalid || seq >= (BigInt(1) << 64).U || (!end && prim >= (BigInt(1) << 32).U)
      when(c.status === 0.U && owned && c.publishedValid) {
        jointCursor.get.sequence := seq(64, 0)
        jointCursor.get.primitiveId := Mux(end, 0.U, prim(32, 0))
      }
      when(c.status === 0.U && invalid) { cursorFault.get := true.B }
    }
  }
  when(state === State.start) {
    when(descriptor.admissionStatus =/= 0.U) {
      result.admissionRejected := true.B; result.range.status := descriptor.admissionStatus; state := State.reject
    }.elsewhen(sequence.io.begin.fire) { state := State.running }
  }
  sequence.io.completion.ready := state === State.running
  when(sequence.io.completion.fire) {
    result.range := sequence.io.completion.bits
    when(!offered && sequence.io.completion.bits.status === 1.U) { result.admissionRejected := true.B }
    state := State.report
    if (jointCursors) {
      cursorResult.get := jointCursor.get
      when(sequence.io.completion.bits.status === 0.U && cursorFault.get) {
        result.range.status := 1.U
        result.range.failedPatch := Mux(
          sequence.io.completion.bits.completed === 0.U,
          0.U,
          sequence.io.completion.bits.completed - 1.U
        )
      }
    }
  }
  when(state === State.reject && io.taskDrained) { state := State.report }
  io.cancelChild := sequence.io.cancelChild
  io.completion.valid := state === State.report; io.completion.bits := result
  when(io.completion.fire) { state := State.idle }
  io.busy := state =/= State.idle
  io.drained := (state === State.idle || state === State.report) && io.taskDrained
}

/** Cross-link captured metadata. No address/ISA/GEM authority is inferred here. */
object TessellationVertexTypedRangeBinding {
  def descriptor(
      p: VertexShaderStageConfig,
      c: TessellationVertexCaptureCompletion
  ): TessellationVertexPatchSequenceDescriptor = {
    val d = Wire(new TessellationVertexPatchSequenceDescriptor(p)); d := 0.U.asTypeOf(d)
    val r = c.root; val t = d.template
    t.version := 1.U; t.taskId := r.taskIdBase; t.layout := r.layout; t.draw := c.draw; t.components := r.components
    t.slice.version := r.sliceVersion; t.slice.firstOrdinal := r.firstOrdinal; t.slice.count := r.layout.inputVertices
    t.slice.firstInstance := r.firstInstance; t.slice.instances := 1.U
    d.patchesPerInstance := r.patchesPerInstance; d.instanceCount := r.instanceCount; d.patchCount := r.patchCount
    d
  }
  def matches(
      c: TessellationVertexCaptureCompletion,
      r: TessellationVertexSourceRequest,
      taskId: UInt,
      layout: Data,
      count: UInt,
      identity: GraphicsWorkIdentity
  ): Bool = {
    c.request.asUInt === r.asUInt && c.root.identity.asUInt === r.identity.asUInt &&
    c.root.sequenceIndex === r.sequenceIndex && c.root.drawIndex === r.drawIndex &&
    c.root.taskIdBase === r.taskIdBase && c.root.patchCount === r.patchCount &&
    c.root.layout.asUInt === r.layout.asUInt && taskId === r.taskIdBase && count === r.patchCount &&
    layout.asUInt === r.layout.asUInt && identity.asUInt === r.identity.asUInt &&
    c.draw.input.identity.asUInt === r.identity.asUInt && c.draw.version === c.root.vertexAbi && c.draw.shaderPc === c.root.entry
  }
  def admission(gnt: UInt, capture: UInt, matched: Bool): UInt = {
    Mux(
      gnt =/= 0.U && gnt =/= 4.U,
      gnt,
      Mux(capture =/= 0.U && capture =/= 4.U, capture, Mux(gnt === 4.U || capture === 4.U, 4.U, Mux(matched, 0.U, 1.U)))
    )
  }
}
