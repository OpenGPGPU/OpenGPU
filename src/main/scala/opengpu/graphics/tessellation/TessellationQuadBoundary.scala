package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._

/** A boundary vertex in counter-clockwise quad-domain order. */
class TessellationQuadBoundaryVertex extends Bundle {
  val taskId = UInt(32.W)
  val sequence = UInt(8.W)
  /** GLES outer-level index for the edge beginning at this vertex. */
  val outerEdge = UInt(2.W)
  /** Unsigned Q0.24 coordinates, including 1.0 as 1 << 24. */
  val u = UInt(25.W)
  val v = UInt(25.W)
}
class TessellationQuadBoundaryTask extends Bundle {
  val taskId = UInt(32.W)
  val spacing = UInt(2.W)
  val outer = Vec(4, UInt(32.W))
}
class TessellationQuadBoundaryCompletion extends Bundle {
  val taskId = UInt(32.W)
  /** Can equal 256 at four maximum-level edges. */
  val emitted = UInt(9.W)
  /** 0 success or discarded patch, 1 invalid input, 4 cancelled. */
  val status = UInt(3.W)
  val discarded = Bool()
}

/** Stream the independently subdivided outer boundary of a quad patch.
  * Corners occur once: each edge offers its starting vertex and excludes its
  * ending vertex. This is a building block, not a quad primitive generator.
  */
class TessellationQuadBoundary extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationQuadBoundaryTask))
    val out = Decoupled(new TessellationQuadBoundaryVertex)
    val completion = Decoupled(new TessellationQuadBoundaryCompletion)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, prepareOffer, prepareWait, edgeOffer, edgeWait, emit, complete = Value
  }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationQuadBoundaryTask)
  private val prepared = Reg(new TessellationLevelResult)
  private val result = Reg(new TessellationQuadBoundaryCompletion)
  private val edgeNumber = Reg(UInt(2.W))
  private val edgeIndex = Reg(UInt(7.W))
  private val coordinate = Reg(UInt(25.W))
  private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel
  private val levels = Module(new TessellationLevelPrepare)
  private val edge = Module(new TessellationEdgeCoordinate)
  // CCW: bottom, right, top, left; top and left run in reverse.
  private val outerIndex = VecInit(Seq(1.U(2.W), 2.U(2.W), 3.U(2.W), 0.U(2.W)))(edgeNumber)
  private val count = prepared.outer(outerIndex).segments
  private val one = (BigInt(1) << 24).U(25.W)

  io.begin.ready := state === State.idle && !io.cancel
  io.busy := state =/= State.idle
  io.out.valid := state === State.emit
  io.out.bits.taskId := task.taskId
  io.out.bits.sequence := result.emitted(7, 0)
  io.out.bits.outerEdge := outerIndex
  io.out.bits.u := MuxLookup(edgeNumber, 0.U)(Seq(0.U -> coordinate, 1.U -> one, 2.U -> coordinate))
  io.out.bits.v := MuxLookup(edgeNumber, 0.U)(Seq(1.U -> coordinate, 2.U -> one, 3.U -> coordinate))
  io.completion.valid := state === State.complete
  io.completion.bits := result

  levels.io.in.valid := state === State.prepareOffer
  levels.io.in.bits.domain := TessellationDomain.quads.U
  levels.io.in.bits.spacing := task.spacing
  levels.io.in.bits.outer := task.outer
  levels.io.in.bits.inner := VecInit(Seq.fill(2)("h3f800000".U(32.W)))
  levels.io.out.ready := state === State.prepareWait
  edge.io.in.valid := state === State.edgeOffer
  edge.io.in.bits.level := prepared.outer(outerIndex).clamped
  edge.io.in.bits.spacing := task.spacing
  edge.io.in.bits.index := Mux(edgeNumber >= 2.U, count - edgeIndex, edgeIndex)
  edge.io.in.bits.promoted := false.B
  edge.io.out.ready := state === State.edgeWait
  edge.io.cancel := state === State.edgeWait && stop

  when(io.begin.fire) {
    task := io.begin.bits
    result := 0.U.asTypeOf(result)
    result.taskId := io.begin.bits.taskId
    edgeNumber := 0.U
    edgeIndex := 0.U
    cancelled := false.B
    state := State.prepareOffer
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(levels.io.in.fire) { state := State.prepareWait }
  when(levels.io.out.fire) {
    prepared := levels.io.out.bits
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(levels.io.out.bits.invalid) { result.status := 1.U; state := State.complete }
      .elsewhen(levels.io.out.bits.discard) { result.discarded := true.B; state := State.complete }
      .otherwise { state := State.edgeOffer }
  }
  when(edge.io.in.fire) { state := State.edgeWait }
  when(edge.io.out.fire) {
    when(stop || edge.io.out.bits.status === 2.U) { result.status := 4.U; state := State.complete }
      .elsewhen(edge.io.out.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .otherwise { coordinate := edge.io.out.bits.coordinate; state := State.emit }
  }
  when(io.out.fire) {
    result.emitted := result.emitted + 1.U
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(edgeIndex +& 1.U === count) {
        edgeIndex := 0.U
        when(edgeNumber === 3.U) { state := State.complete }
          .otherwise { edgeNumber := edgeNumber + 1.U; state := State.edgeOffer }
      }.otherwise { edgeIndex := edgeIndex + 1.U; state := State.edgeOffer }
  }
  when(io.completion.fire) { state := State.idle }
}
