package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationTriangleInteriorTask extends Bundle {
  val taskId = UInt(32.W); val spacing = UInt(2.W)
  val outer = Vec(4, UInt(32.W)); val inner = Vec(2, UInt(32.W))
}
class TessellationTriangleInteriorVertex extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(12.W)
  /** Ring starts at zero; each nondegenerate ring owns three directed sides.
    * Side 3 denotes the center. Indices preserve coincident vertices.
    */
  val ring = UInt(6.W); val side = UInt(2.W); val index = UInt(7.W); val segments = UInt(7.W)
  val u = UInt(25.W); val v = UInt(25.W); val w = UInt(25.W)
}
class TessellationTriangleInteriorCompletion extends Bundle {
  val taskId = UInt(32.W); val emitted = UInt(12.W); val status = UInt(3.W); val discarded = Bool()
}

/** Stream concentric triangle-ring vertices; the caller joins adjacent rings
  * and the separately subdivided outer boundary. Values use Q0.24. Each
  * perpendicular projection rounds locally; the center is the nearest common
  * Q0.24 value to one third and can sum to one minus one LSB.
  */
class TessellationTriangleInterior extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationTriangleInteriorTask))
    val out = Decoupled(new TessellationTriangleInteriorVertex)
    val completion = Decoupled(new TessellationTriangleInteriorCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, prepareOffer, prepareWait, edgeOffer, edgeWait,
    ringStart, build, emit, center, complete = Value }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationTriangleInteriorTask)
  private val prepared = Reg(new TessellationLevelResult)
  private val result = Reg(new TessellationTriangleInteriorCompletion)
  private val cancelled = RegInit(false.B); private val stop = cancelled || io.cancel
  private val segments = Reg(UInt(7.W)); private val edgeIndex = Reg(UInt(7.W))
  private val low = Reg(UInt(25.W)); private val nextLow = Reg(UInt(25.W)); private val halfDelta = Reg(UInt(25.W))
  private val buildIndex = Reg(UInt(7.W)); private val side = Reg(UInt(2.W)); private val index = Reg(UInt(7.W))
  private val ring = Reg(UInt(6.W)); private val values = Reg(Vec(65, UInt(25.W)))
  private val levels = Module(new TessellationLevelPrepare); private val edge = Module(new TessellationEdgeCoordinate)
  private val one = (BigInt(1) << 24).U(25.W); private val third = ((BigInt(1) << 24) / 3).U(25.W)
  levels.io.in.valid := state === State.prepareOffer
  levels.io.in.bits.domain := TessellationDomain.triangles.U; levels.io.in.bits.spacing := task.spacing
  levels.io.in.bits.outer := task.outer; levels.io.in.bits.inner := task.inner
  levels.io.out.ready := state === State.prepareWait
  edge.io.in.valid := state === State.edgeOffer; edge.io.in.bits.level := prepared.inner(0).clamped
  edge.io.in.bits.spacing := task.spacing; edge.io.in.bits.index := edgeIndex; edge.io.in.bits.promoted := prepared.inner(0).promoted
  edge.io.out.ready := state === State.edgeWait; edge.io.cancel := state === State.edgeWait && stop
  io.begin.ready := state === State.idle && !io.cancel; io.busy := state =/= State.idle
  io.completion.valid := state === State.complete; io.completion.bits := result
  private val v = values(index); private val u = one - low - v
  io.out.valid := state === State.emit || state === State.center
  io.out.bits.taskId := task.taskId; io.out.bits.sequence := result.emitted; io.out.bits.ring := ring
  io.out.bits.side := Mux(state === State.center, 3.U, side); io.out.bits.index := Mux(state === State.center, 0.U, index)
  io.out.bits.segments := Mux(state === State.center, 0.U, segments)
  io.out.bits.u := Mux(state === State.center, third, MuxLookup(side, u)(Seq(1.U -> low, 2.U -> v)))
  io.out.bits.v := Mux(state === State.center, third, MuxLookup(side, v)(Seq(1.U -> u, 2.U -> low)))
  io.out.bits.w := Mux(state === State.center, third, MuxLookup(side, low)(Seq(1.U -> v, 2.U -> u)))
  when(io.begin.fire) {
    task := io.begin.bits; result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    cancelled := false.B; low := 0.U; ring := 0.U; edgeIndex := 0.U; state := State.prepareOffer
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(levels.io.in.fire) { state := State.prepareWait }
  when(levels.io.out.fire) {
    prepared := levels.io.out.bits; segments := levels.io.out.bits.inner(0).segments
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(levels.io.out.bits.invalid) { result.status := 1.U; state := State.complete }
      .elsewhen(levels.io.out.bits.discard) { result.discarded := true.B; state := State.complete }
      .elsewhen(levels.io.out.bits.inner(0).segments <= 1.U) { state := State.complete }
      .elsewhen(levels.io.out.bits.inner(0).segments === 2.U) { state := State.center }
      .otherwise { state := State.edgeOffer }
  }
  when(edge.io.in.fire) { state := State.edgeWait }
  when(edge.io.out.fire) {
    when(stop || edge.io.out.bits.status === 2.U) { result.status := 4.U; state := State.complete }
      .elsewhen(edge.io.out.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .otherwise {
        values(edgeIndex) := edge.io.out.bits.coordinate
        when(edgeIndex === segments) { state := State.ringStart }
          .otherwise { edgeIndex := edgeIndex + 1.U; state := State.edgeOffer }
      }
  }
  // Projection from the first parent edge vertex gives the inset 2*d/3.
  private val distance = values(1) - low
  private val delta = (((distance << 1) +& 1.U) / 3.U)(24, 0)
  private val roundedHalf = (delta >> 1) + (delta(0) && delta(1)).asUInt
  when(state === State.ringStart) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(segments === 2.U) { state := State.center }
      .otherwise { nextLow := low + delta; halfDelta := roundedHalf; buildIndex := 1.U; state := State.build }
  }
  private val high = one - (nextLow << 1)
  private val projected = values(buildIndex) - halfDelta
  private val bounded = Mux(projected < nextLow, nextLow, Mux(projected > high, high, projected))
  when(state === State.build) {
    when(stop) { result.status := 4.U; state := State.complete }
      .otherwise {
        values(buildIndex - 1.U) := Mux(buildIndex === 1.U, nextLow, Mux(buildIndex +& 1.U === segments, high, bounded))
        when(buildIndex +& 1.U === segments) { low := nextLow; segments := segments - 2.U; side := 0.U; index := 0.U; state := State.emit }
          .otherwise { buildIndex := buildIndex + 1.U }
      }
  }
  when(io.out.fire) {
    result.emitted := result.emitted + 1.U
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(state === State.center) { state := State.complete }
      .elsewhen(index +& 1.U === segments) {
        index := 0.U
        when(side === 2.U) {
          ring := ring + 1.U
          when(segments === 1.U) { state := State.complete }.otherwise { state := State.ringStart }
        }.otherwise { side := side + 1.U }
      }.otherwise { index := index + 1.U }
  }
  when(io.completion.fire) { state := State.idle }
}
