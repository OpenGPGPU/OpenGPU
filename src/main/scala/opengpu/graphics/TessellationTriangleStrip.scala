package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationTriangleStripPoint extends Bundle {
  val u = UInt(25.W); val v = UInt(25.W); val w = UInt(25.W)
}
class TessellationTriangleStripTask extends Bundle {
  val taskId = UInt(32.W)
  /** Outer must have 1–64 segments; inner may be a single point. */
  val outerSegments = UInt(7.W); val innerSegments = UInt(7.W)
  val clockwise = Bool()
}
class TessellationTriangleStripTriangle extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(8.W)
  val vertices = Vec(3, new TessellationTriangleStripPoint)
}
class TessellationTriangleStripCompletion extends Bundle {
  val taskId = UInt(32.W); val emitted = UInt(8.W)
  /** 0 success, 1 invalid segment count, 4 cancellation. */
  val status = UInt(3.W)
}

/** Stitch two ordered sides of a triangle ring without storing the whole strip.
  * Each input supplies segments + 1 vertices, in the same boundary direction.
  * The outer side must run counter-clockwise with the inner side on its left;
  * The caller supplies both corners and guarantees that the strip polygon is
  * convex, with the two sides ordered consistently and without crossings.
  * An inner side of zero segments is a single point, producing a triangle fan.
  * Triangles are held under backpressure, including an asserted cancellation.
  */
class TessellationTriangleStrip extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationTriangleStripTask))
    val outer = Flipped(Decoupled(new TessellationTriangleStripPoint))
    val inner = Flipped(Decoupled(new TessellationTriangleStripPoint))
    val out = Decoupled(new TessellationTriangleStripTriangle)
    val completion = Decoupled(new TessellationTriangleStripCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, firstOuter, firstInner, choose, nextOuter, nextInner, emit, complete = Value
  }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationTriangleStripTask)
  private val result = Reg(new TessellationTriangleStripCompletion)
  private val outer = Reg(new TessellationTriangleStripPoint)
  private val inner = Reg(new TessellationTriangleStripPoint)
  private val next = Reg(new TessellationTriangleStripPoint)
  private val outerIndex = Reg(UInt(7.W)); private val innerIndex = Reg(UInt(7.W))
  private val advanceOuter = Reg(Bool())
  private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel
  private val outerDone = outerIndex === task.outerSegments
  private val innerDone = innerIndex === task.innerSegments
  // Compare normalized topological progress. Either choice at a tie is valid.
  private val outerProgress = (outerIndex +& 1.U) * task.innerSegments
  private val innerProgress = (innerIndex +& 1.U) * task.outerSegments
  private val takeOuter = !outerDone && (innerDone || outerProgress <= innerProgress)

  io.begin.ready := state === State.idle && !io.cancel
  io.busy := state =/= State.idle
  io.outer.ready := (state === State.firstOuter || state === State.nextOuter) && !stop
  io.inner.ready := (state === State.firstInner || state === State.nextInner) && !stop
  io.out.valid := state === State.emit
  io.out.bits.taskId := task.taskId; io.out.bits.sequence := result.emitted
  io.out.bits.vertices(0) := outer
  io.out.bits.vertices(1) := Mux(task.clockwise, inner, next)
  io.out.bits.vertices(2) := Mux(task.clockwise, next, inner)
  io.completion.valid := state === State.complete; io.completion.bits := result

  when(io.begin.fire) {
    task := io.begin.bits; result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    outerIndex := 0.U; innerIndex := 0.U; cancelled := false.B
    when(io.begin.bits.outerSegments === 0.U || io.begin.bits.outerSegments > 64.U ||
      io.begin.bits.innerSegments > 64.U) { result.status := 1.U; state := State.complete }
      .otherwise { state := State.firstOuter }
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(state === State.firstOuter) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(io.outer.fire) { outer := io.outer.bits; state := State.firstInner }
  }
  when(state === State.firstInner) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(io.inner.fire) { inner := io.inner.bits; state := State.choose }
  }
  when(state === State.choose) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(outerDone && innerDone) { state := State.complete }
      .otherwise {
        advanceOuter := takeOuter
        state := Mux(takeOuter, State.nextOuter, State.nextInner)
      }
  }
  when(state === State.nextOuter || state === State.nextInner) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(io.outer.fire || io.inner.fire) {
        next := Mux(advanceOuter, io.outer.bits, io.inner.bits); state := State.emit
      }
  }
  when(io.out.fire) {
    result.emitted := result.emitted + 1.U
    when(advanceOuter) { outer := next; outerIndex := outerIndex + 1.U }
      .otherwise { inner := next; innerIndex := innerIndex + 1.U }
    when(stop) { result.status := 4.U; state := State.complete }
      .otherwise { state := State.choose }
  }
  when(io.completion.fire) { state := State.idle }
}
