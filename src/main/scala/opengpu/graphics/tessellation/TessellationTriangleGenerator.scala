package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._

class TessellationTriangleTask extends Bundle {
  val taskId = UInt(32.W); val spacing = UInt(2.W)
  val outer = Vec(4, UInt(32.W)); val inner = Vec(2, UInt(32.W))
  val clockwise = Bool(); val pointMode = Bool()
}
class TessellationTrianglePrimitive extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(14.W); val point = Bool()
  val vertices = Vec(3, new TessellationTriangleStripPoint)
}
class TessellationTriangleCompletion extends Bundle {
  val taskId = UInt(32.W); val emitted = UInt(14.W)
  val status = UInt(3.W); val discarded = Bool()
}

/** Assemble triangle-domain rings into points or consistently wound triangles.
  * Two bounded ring banks retain only the adjacent rings currently being joined.
  * Coordinates are unsigned Q0.24 barycentrics. Outputs are provisional until
  * completion, and a held primitive remains owned through cancellation.
  */
class TessellationTriangleGenerator extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationTriangleTask))
    val out = Decoupled(new TessellationTrianglePrimitive)
    val completion = Decoupled(new TessellationTriangleCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, prepareOffer, prepareWait, boundaryStart, boundaryRun, interiorStart,
      interiorRun, stripStart, stripRun, central, interiorDrain, complete = Value
  }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationTriangleTask)
  private val result = Reg(new TessellationTriangleCompletion)
  private val cancelled = RegInit(false.B); private val stop = cancelled || io.cancel
  private val levels = Module(new TessellationLevelPrepare)
  private val boundary = Module(new TessellationTriangleBoundary)
  private val interior = Module(new TessellationTriangleInterior)
  private val strip = Module(new TessellationTriangleStrip)
  // Each directed side excludes its final corner; the next side owns it.
  private val banks = Reg(Vec(2, Vec(3, Vec(64, new TessellationTriangleStripPoint))))
  private val outerBank = RegInit(false.B)
  private val outerCounts = Reg(Vec(3, UInt(7.W)))
  private val innerCount = Reg(UInt(7.W))
  private val boundarySide = Reg(UInt(2.W)); private val boundaryIndex = Reg(UInt(7.W))
  private val side = Reg(UInt(2.W)); private val outerFeed = Reg(UInt(7.W)); private val innerFeed = Reg(UInt(7.W))
  private val sawInterior = RegInit(false.B)
  private val edgeMap = VecInit(Seq(2.U(2.W), 0.U(2.W), 1.U(2.W)))
  private val nextSide = Mux(side === 2.U, 0.U, side + 1.U)
  private val outerSideCount = outerCounts(side)
  private val outerPoint = Mux(outerFeed === outerSideCount, banks(outerBank)(nextSide)(0),
    banks(outerBank)(side)(outerFeed(5, 0)))
  private val innerPoint = Mux(innerCount === 0.U, banks(!outerBank)(0)(0),
    Mux(innerFeed === innerCount, banks(!outerBank)(nextSide)(0), banks(!outerBank)(side)(innerFeed(5, 0))))

  levels.io.in.valid := state === State.prepareOffer
  levels.io.in.bits.domain := TessellationDomain.triangles.U; levels.io.in.bits.spacing := task.spacing
  levels.io.in.bits.outer := task.outer; levels.io.in.bits.inner := task.inner
  levels.io.out.ready := state === State.prepareWait
  boundary.io.begin.valid := state === State.boundaryStart && !stop
  boundary.io.begin.bits.taskId := task.taskId; boundary.io.begin.bits.spacing := task.spacing
  boundary.io.begin.bits.outer := task.outer
  boundary.io.cancel := state === State.boundaryRun && stop
  boundary.io.out.ready := state === State.boundaryRun && Mux(task.pointMode, io.out.ready, true.B)
  boundary.io.completion.ready := state === State.boundaryRun
  interior.io.begin.valid := state === State.interiorStart && !stop
  interior.io.begin.bits.taskId := task.taskId; interior.io.begin.bits.spacing := task.spacing
  interior.io.begin.bits.outer := task.outer; interior.io.begin.bits.inner := task.inner
  interior.io.cancel := (state === State.interiorRun || state === State.stripStart || state === State.stripRun ||
    state === State.central || state === State.interiorDrain) && stop
  interior.io.out.ready := Mux(state === State.interiorDrain, true.B,
    state === State.interiorRun && Mux(task.pointMode, io.out.ready, true.B))
  interior.io.completion.ready := state === State.interiorRun || state === State.interiorDrain
  strip.io.begin.valid := state === State.stripStart && !stop
  strip.io.begin.bits.taskId := task.taskId; strip.io.begin.bits.outerSegments := outerSideCount
  strip.io.begin.bits.innerSegments := innerCount; strip.io.begin.bits.clockwise := task.clockwise
  strip.io.outer.valid := state === State.stripRun && strip.io.outer.ready && !stop
  strip.io.outer.bits := outerPoint
  strip.io.inner.valid := state === State.stripRun && strip.io.inner.ready && !stop
  strip.io.inner.bits := innerPoint
  strip.io.out.ready := state === State.stripRun && io.out.ready
  strip.io.completion.ready := state === State.stripRun
  strip.io.cancel := state === State.stripRun && stop

  io.begin.ready := state === State.idle && !io.cancel; io.busy := state =/= State.idle
  io.completion.valid := state === State.complete; io.completion.bits := result
  private val point = Wire(new TessellationTriangleStripPoint)
  point.u := Mux(state === State.boundaryRun, boundary.io.out.bits.u, interior.io.out.bits.u)
  point.v := Mux(state === State.boundaryRun, boundary.io.out.bits.v, interior.io.out.bits.v)
  point.w := Mux(state === State.boundaryRun, boundary.io.out.bits.w, interior.io.out.bits.w)
  private val centralBank = Mux(sawInterior, !outerBank, outerBank)
  private val a = banks(centralBank)(0)(0); private val b = banks(centralBank)(1)(0); private val c = banks(centralBank)(2)(0)
  private val output = Wire(new TessellationTrianglePrimitive); output := 0.U.asTypeOf(output)
  output.taskId := task.taskId; output.sequence := result.emitted
  when(state === State.stripRun) { output.vertices := strip.io.out.bits.vertices }
    .elsewhen(state === State.central) {
      output.vertices(0) := a; output.vertices(1) := Mux(task.clockwise, c, b)
      output.vertices(2) := Mux(task.clockwise, b, c)
    }.otherwise { output.point := true.B; output.vertices := VecInit(Seq.fill(3)(point)) }
  io.out.bits := output
  io.out.valid := (state === State.stripRun && strip.io.out.valid) || state === State.central ||
    (task.pointMode && ((state === State.boundaryRun && boundary.io.out.valid) ||
      (state === State.interiorRun && interior.io.out.valid)))

  when(io.begin.fire) {
    task := io.begin.bits; result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    cancelled := false.B; outerBank := false.B; boundarySide := 0.U; boundaryIndex := 0.U
    side := 0.U; outerFeed := 0.U; innerFeed := 0.U; sawInterior := false.B
    state := State.prepareOffer
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(levels.io.in.fire) { state := State.prepareWait }
  when(levels.io.out.fire) {
    for (i <- 0 until 3) { outerCounts(i) := levels.io.out.bits.outer(edgeMap(i)).segments }
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(levels.io.out.bits.invalid) { result.status := 1.U; state := State.complete }
      .elsewhen(levels.io.out.bits.discard) { result.discarded := true.B; state := State.complete }
      .otherwise { state := State.boundaryStart }
  }
  when(state === State.boundaryStart) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(boundary.io.begin.fire) { state := State.boundaryRun }
  }
  when(boundary.io.out.fire && !task.pointMode) {
    assert(boundary.io.out.bits.outerEdge === edgeMap(boundarySide))
    val p = banks(outerBank)(boundarySide)(boundaryIndex(5, 0))
    p.u := boundary.io.out.bits.u; p.v := boundary.io.out.bits.v; p.w := boundary.io.out.bits.w
    when(boundaryIndex +& 1.U === outerCounts(boundarySide)) {
      boundaryIndex := 0.U; boundarySide := boundarySide + 1.U
    }.otherwise { boundaryIndex := boundaryIndex + 1.U }
  }
  when(boundary.io.completion.fire) {
    when(stop || boundary.io.completion.bits.status === 4.U) { result.status := 4.U; state := State.complete }
      .elsewhen(boundary.io.completion.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .otherwise { state := State.interiorStart }
  }
  when(state === State.interiorStart) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(interior.io.begin.fire) { state := State.interiorRun }
  }
  when(interior.io.out.fire && state === State.interiorRun && !task.pointMode) {
    val s = Mux(interior.io.out.bits.side === 3.U, 0.U, interior.io.out.bits.side)
    val p = banks(!outerBank)(s)(interior.io.out.bits.index(5, 0))
    p.u := interior.io.out.bits.u; p.v := interior.io.out.bits.v; p.w := interior.io.out.bits.w
    when(interior.io.out.bits.side === 3.U ||
      (interior.io.out.bits.side === 2.U && interior.io.out.bits.index +& 1.U === interior.io.out.bits.segments)) {
      sawInterior := true.B; innerCount := interior.io.out.bits.segments; side := 0.U
      state := State.stripStart
    }
  }
  when(interior.io.completion.fire) {
    when(stop || interior.io.completion.bits.status === 4.U) { result.status := 4.U; state := State.complete }
      .elsewhen(interior.io.completion.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .elsewhen(task.pointMode || state === State.interiorDrain) { state := State.complete }
      .elsewhen(!sawInterior) { state := State.central }
      .otherwise { state := State.complete }
  }
  when(state === State.stripStart) {
    when(stop) { result.status := 4.U; state := State.interiorDrain }
      .elsewhen(strip.io.begin.fire) { outerFeed := 0.U; innerFeed := 0.U; state := State.stripRun }
  }
  when(strip.io.outer.fire) { outerFeed := outerFeed + 1.U }
  when(strip.io.inner.fire) { innerFeed := innerFeed + 1.U }
  when(strip.io.completion.fire) {
    when(stop || strip.io.completion.bits.status === 4.U) { result.status := 4.U; state := State.interiorDrain }
      .elsewhen(strip.io.completion.bits.status =/= 0.U) { result.status := 1.U; state := State.interiorDrain }
      .elsewhen(side =/= 2.U) { side := side + 1.U; state := State.stripStart }
      .elsewhen(innerCount === 0.U) { state := State.interiorDrain }
      .elsewhen(innerCount === 1.U) { state := State.central }
      .otherwise {
        outerBank := !outerBank; for (i <- 0 until 3) { outerCounts(i) := innerCount }
        state := State.interiorRun
      }
  }
  when(io.out.fire) {
    result.emitted := result.emitted + 1.U
    when(state === State.central) {
      when(stop) { result.status := 4.U }
      state := Mux(sawInterior, State.interiorDrain, State.complete)
    }
  }
  when(io.completion.fire) { state := State.idle }
}
