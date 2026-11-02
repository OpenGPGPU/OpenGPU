package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationQuadTask extends Bundle {
  val taskId = UInt(32.W); val spacing = UInt(2.W)
  val outer = Vec(4, UInt(32.W)); val inner = Vec(2, UInt(32.W))
  val clockwise = Bool(); val pointMode = Bool()
}
class TessellationQuadPrimitive extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(14.W); val point = Bool()
  val vertices = Vec(3, new TessellationQuadStripPoint)
}
class TessellationQuadCompletion extends Bundle {
  val taskId = UInt(32.W); val emitted = UInt(14.W)
  val status = UInt(3.W); val discarded = Bool()
}

/** Generate a quad patch's points or oriented triangles. Coordinates are Q0.24.
  * Patch-local axis and boundary coordinates are retained while the interior
  * cells and four independently subdivided strips are emitted. Shader execution
  * and integration into the native render path are outside this module.
  */
class TessellationQuadGenerator extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationQuadTask))
    val out = Decoupled(new TessellationQuadPrimitive)
    val completion = Decoupled(new TessellationQuadCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, prepareOffer, prepareWait, boundaryStart, boundaryRun, interiorStart, interiorRun,
      cells, stripStart, stripRun, pointsOuter, pointsInner, trivial, complete = Value
  }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationQuadTask)
  private val prepared = Reg(new TessellationLevelResult)
  private val result = Reg(new TessellationQuadCompletion)
  private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel
  private val levels = Module(new TessellationLevelPrepare)
  private val boundary = Module(new TessellationQuadBoundary)
  private val interior = Module(new TessellationQuadInterior)
  private val strip = Module(new TessellationQuadStrip)
  private val outerValues = Reg(Vec(4, Vec(64, UInt(25.W))))
  private val uValues = Reg(Vec(64, UInt(25.W)))
  private val vValues = Reg(Vec(64, UInt(25.W)))
  private val captureSide = Reg(UInt(2.W)); private val captureIndex = Reg(UInt(7.W))
  private val side = Reg(UInt(2.W)); private val outerFeed = Reg(UInt(7.W)); private val innerFeed = Reg(UInt(7.W))
  private val cellU = Reg(UInt(7.W)); private val cellV = Reg(UInt(7.W)); private val half = Reg(Bool())
  private val trivialIndex = Reg(UInt(3.W))
  private val one = (BigInt(1) << 24).U(25.W)
  private val edgeMap = VecInit(Seq(1.U(2.W), 2.U(2.W), 3.U(2.W), 0.U(2.W)))
  private val n = prepared.inner(1).segments; private val m = prepared.inner(0).segments
  private val outerCount = prepared.outer(edgeMap(side)).segments
  private val innerCount = Mux(side(0), m - 2.U, n - 2.U)
  private def point(u: UInt, v: UInt): TessellationQuadStripPoint = {
    val p = Wire(new TessellationQuadStripPoint); p.u := u; p.v := v; p
  }
  private val corners = VecInit(Seq(point(0.U, 0.U), point(one, 0.U), point(one, one), point(0.U, one)))
  private val endCorner = corners((side + 1.U)(1, 0))
  private val outerCoordinate = outerValues(side)(outerFeed(5, 0))
  private val outerPoint = Wire(new TessellationQuadStripPoint)
  outerPoint := Mux(outerFeed === outerCount, endCorner,
    MuxLookup(side, point(0.U, outerCoordinate))(Seq(
      0.U -> point(outerCoordinate, 0.U), 1.U -> point(one, outerCoordinate),
      2.U -> point(outerCoordinate, one))))
  private val innerUIndex = MuxLookup(side, 1.U(7.W))(Seq(
    0.U -> (1.U + innerFeed), 1.U -> (n - 1.U), 2.U -> (n - 1.U - innerFeed)))
  private val innerVIndex = MuxLookup(side, 1.U(7.W))(Seq(
    1.U -> (1.U + innerFeed), 2.U -> (m - 1.U), 3.U -> (m - 1.U - innerFeed)))
  private val innerPoint = point(uValues(innerUIndex(5, 0)), vValues(innerVIndex(5, 0)))

  levels.io.in.valid := state === State.prepareOffer
  levels.io.in.bits.domain := TessellationDomain.quads.U; levels.io.in.bits.spacing := task.spacing
  levels.io.in.bits.outer := task.outer; levels.io.in.bits.inner := task.inner
  levels.io.out.ready := state === State.prepareWait
  boundary.io.begin.valid := state === State.boundaryStart && !stop
  boundary.io.begin.bits.taskId := task.taskId; boundary.io.begin.bits.spacing := task.spacing
  boundary.io.begin.bits.outer := task.outer
  boundary.io.out.ready := state === State.boundaryRun; boundary.io.completion.ready := state === State.boundaryRun
  boundary.io.cancel := state === State.boundaryRun && stop
  interior.io.begin.valid := state === State.interiorStart && !stop
  interior.io.begin.bits.taskId := task.taskId; interior.io.begin.bits.spacing := task.spacing
  interior.io.begin.bits.outer := task.outer; interior.io.begin.bits.inner := task.inner
  interior.io.out.ready := state === State.interiorRun; interior.io.completion.ready := state === State.interiorRun
  interior.io.cancel := state === State.interiorRun && stop
  strip.io.begin.valid := state === State.stripStart && !stop
  strip.io.begin.bits.taskId := task.taskId; strip.io.begin.bits.outerSegments := outerCount
  strip.io.begin.bits.innerSegments := innerCount; strip.io.begin.bits.clockwise := task.clockwise
  // The strip's ready is independent of valid. Offer only on its ready cycle,
  // so cancellation cannot strand an unaccepted internal coordinate offer.
  strip.io.outer.valid := state === State.stripRun && strip.io.outer.ready && !stop
  strip.io.outer.bits := outerPoint
  strip.io.inner.valid := state === State.stripRun && strip.io.inner.ready && !stop
  strip.io.inner.bits := innerPoint
  strip.io.out.ready := state === State.stripRun && io.out.ready
  strip.io.completion.ready := state === State.stripRun
  strip.io.cancel := state === State.stripRun && stop

  io.begin.ready := state === State.idle && !io.cancel; io.busy := state =/= State.idle
  io.completion.valid := state === State.complete; io.completion.bits := result
  private val cell00 = point(uValues(cellU(5, 0)), vValues(cellV(5, 0)))
  private val cell10 = point(uValues((cellU + 1.U)(5, 0)), vValues(cellV(5, 0)))
  private val cell01 = point(uValues(cellU(5, 0)), vValues((cellV + 1.U)(5, 0)))
  private val cell11 = point(uValues((cellU + 1.U)(5, 0)), vValues((cellV + 1.U)(5, 0)))
  private val innerVertex = point(uValues(cellU(5, 0)), vValues(cellV(5, 0)))
  private val triangle0 = Mux(half, cell10, cell00)
  private val triangle1 = Mux(half, cell11, cell10)
  private val triangle2 = cell01
  private val trivial0 = corners(0); private val trivial1 = Mux(trivialIndex === 0.U, corners(1), corners(2))
  private val trivial2 = Mux(trivialIndex === 0.U, corners(2), corners(3))
  private val p = Wire(new TessellationQuadPrimitive); p := 0.U.asTypeOf(p)
  p.taskId := task.taskId; p.sequence := result.emitted
  when(state === State.cells) {
    p.vertices(0) := triangle0; p.vertices(1) := Mux(task.clockwise, triangle2, triangle1)
    p.vertices(2) := Mux(task.clockwise, triangle1, triangle2)
  }.elsewhen(state === State.stripRun) { p.vertices := strip.io.out.bits.vertices }
    .elsewhen(state === State.trivial && !task.pointMode) {
      p.vertices(0) := trivial0; p.vertices(1) := Mux(task.clockwise, trivial2, trivial1)
      p.vertices(2) := Mux(task.clockwise, trivial1, trivial2)
    }.otherwise {
      val v = Mux(state === State.pointsOuter, outerPoint, Mux(state === State.pointsInner, innerVertex, corners(trivialIndex(1, 0))))
      p.point := true.B; p.vertices := VecInit(Seq.fill(3)(v))
    }
  io.out.valid := state === State.cells || state === State.pointsOuter || state === State.pointsInner ||
    state === State.trivial || (state === State.stripRun && strip.io.out.valid)
  io.out.bits := p

  when(io.begin.fire) {
    task := io.begin.bits; result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    cancelled := false.B; captureSide := 0.U; captureIndex := 0.U; side := 0.U
    outerFeed := 0.U; innerFeed := 0.U; cellU := 1.U; cellV := 1.U; half := false.B; trivialIndex := 0.U
    state := State.prepareOffer
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(levels.io.in.fire) { state := State.prepareWait }
  when(levels.io.out.fire) {
    prepared := levels.io.out.bits
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(levels.io.out.bits.invalid) { result.status := 1.U; state := State.complete }
      .elsewhen(levels.io.out.bits.discard) { result.discarded := true.B; state := State.complete }
      .elsewhen(levels.io.out.bits.inner(0).segments === 1.U && levels.io.out.bits.inner(1).segments === 1.U) { state := State.trivial }
      .otherwise { state := State.boundaryStart }
  }
  when(state === State.boundaryStart) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(boundary.io.begin.fire) { state := State.boundaryRun }
  }
  when(boundary.io.out.fire) {
    assert(boundary.io.out.bits.outerEdge === edgeMap(captureSide))
    outerValues(captureSide)(captureIndex(5, 0)) := Mux(captureSide(0), boundary.io.out.bits.v, boundary.io.out.bits.u)
    when(captureIndex +& 1.U === prepared.outer(edgeMap(captureSide)).segments) {
      captureIndex := 0.U; captureSide := captureSide + 1.U
    }.otherwise { captureIndex := captureIndex + 1.U }
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
  when(interior.io.out.fire) {
    when(interior.io.out.bits.vIndex === 1.U) { uValues(interior.io.out.bits.uIndex(5, 0)) := interior.io.out.bits.u }
    when(interior.io.out.bits.uIndex === 1.U) { vValues(interior.io.out.bits.vIndex(5, 0)) := interior.io.out.bits.v }
  }
  when(interior.io.completion.fire) {
    when(stop || interior.io.completion.bits.status === 4.U) { result.status := 4.U; state := State.complete }
      .elsewhen(interior.io.completion.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .elsewhen(task.pointMode) { side := 0.U; outerFeed := 0.U; state := State.pointsOuter }
      .elsewhen(n > 2.U && m > 2.U) { state := State.cells }
      .otherwise { side := 0.U; state := State.stripStart }
  }
  when(state === State.stripStart) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(strip.io.begin.fire) { outerFeed := 0.U; innerFeed := 0.U; state := State.stripRun }
  }
  when(strip.io.outer.fire) { outerFeed := outerFeed + 1.U }
  when(strip.io.inner.fire) { innerFeed := innerFeed + 1.U }
  when(strip.io.completion.fire) {
    when(stop || strip.io.completion.bits.status === 4.U) { result.status := 4.U; state := State.complete }
      .elsewhen(strip.io.completion.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .elsewhen(side === 3.U) { state := State.complete }
      .otherwise { side := side + 1.U; state := State.stripStart }
  }
  when(io.out.fire) {
    result.emitted := result.emitted + 1.U
    when(state =/= State.stripRun && stop) { result.status := 4.U; state := State.complete }
      .elsewhen(state === State.cells) {
        when(!half) { half := true.B }.otherwise {
          half := false.B
          when(cellU +& 2.U === n) {
            cellU := 1.U
            when(cellV +& 2.U === m) { side := 0.U; state := State.stripStart }
              .otherwise { cellV := cellV + 1.U }
          }.otherwise { cellU := cellU + 1.U }
        }
      }.elsewhen(state === State.pointsOuter) {
        when(outerFeed +& 1.U === outerCount) {
          outerFeed := 0.U
          when(side === 3.U) { cellU := 1.U; cellV := 1.U; state := State.pointsInner }
            .otherwise { side := side + 1.U }
        }.otherwise { outerFeed := outerFeed + 1.U }
      }.elsewhen(state === State.pointsInner) {
        when(cellU +& 1.U === n) {
          cellU := 1.U
          when(cellV +& 1.U === m) { state := State.complete }.otherwise { cellV := cellV + 1.U }
        }.otherwise { cellU := cellU + 1.U }
      }.elsewhen(state === State.trivial) {
        when(trivialIndex +& 1.U === Mux(task.pointMode, 4.U, 2.U)) { state := State.complete }
          .otherwise { trivialIndex := trivialIndex + 1.U }
      }
  }
  when(io.completion.fire) { state := State.idle }
}
