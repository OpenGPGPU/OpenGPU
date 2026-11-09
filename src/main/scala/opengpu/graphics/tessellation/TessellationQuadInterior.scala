package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._

class TessellationQuadInteriorTask extends Bundle {
  val taskId = UInt(32.W)
  val spacing = UInt(2.W)
  val outer = Vec(4, UInt(32.W))
  val inner = Vec(2, UInt(32.W))
}
class TessellationQuadInteriorVertex extends Bundle {
  val taskId = UInt(32.W)
  val sequence = UInt(12.W)
  /** Grid indices are topological identities, even if coordinates coincide. */
  val uIndex = UInt(7.W)
  val vIndex = UInt(7.W)
  val u = UInt(25.W)
  val v = UInt(25.W)
}
class TessellationQuadInteriorCompletion extends Bundle {
  val taskId = UInt(32.W)
  /** At most 63 * 63 interior grid vertices. */
  val emitted = UInt(12.W)
  val status = UInt(3.W)
  val discarded = Bool()
}

/** Stream the interior grid vertices of a quad patch in row-major order.
  * The first inner level controls v subdivisions and the second controls u.
  * This supplies grid vertices only; connecting them to triangles and to the
  * independently subdivided outer boundary is the caller's responsibility.
  */
class TessellationQuadInterior extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationQuadInteriorTask))
    val out = Decoupled(new TessellationQuadInteriorVertex)
    val completion = Decoupled(new TessellationQuadInteriorCompletion)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, prepareOffer, prepareWait, vOffer, vWait, uOffer, uWait, emit, complete = Value
  }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationQuadInteriorTask)
  private val prepared = Reg(new TessellationLevelResult)
  private val result = Reg(new TessellationQuadInteriorCompletion)
  private val uIndex = Reg(UInt(7.W)); private val vIndex = Reg(UInt(7.W))
  private val u = Reg(UInt(25.W)); private val v = Reg(UInt(25.W))
  private val cancelled = RegInit(false.B)
  private val stop = cancelled || io.cancel
  private val levels = Module(new TessellationLevelPrepare)
  private val edge = Module(new TessellationEdgeCoordinate)

  io.begin.ready := state === State.idle && !io.cancel
  io.busy := state =/= State.idle
  io.out.valid := state === State.emit
  io.out.bits.taskId := task.taskId; io.out.bits.sequence := result.emitted
  io.out.bits.uIndex := uIndex; io.out.bits.vIndex := vIndex
  io.out.bits.u := u; io.out.bits.v := v
  io.completion.valid := state === State.complete; io.completion.bits := result

  levels.io.in.valid := state === State.prepareOffer
  levels.io.in.bits.domain := TessellationDomain.quads.U
  levels.io.in.bits.spacing := task.spacing
  levels.io.in.bits.outer := task.outer; levels.io.in.bits.inner := task.inner
  levels.io.out.ready := state === State.prepareWait
  private val vertical = state === State.vOffer || state === State.vWait
  edge.io.in.valid := state === State.vOffer || state === State.uOffer
  edge.io.in.bits.level := Mux(vertical, prepared.inner(0).clamped, prepared.inner(1).clamped)
  edge.io.in.bits.promoted := Mux(vertical, prepared.inner(0).promoted, prepared.inner(1).promoted)
  edge.io.in.bits.spacing := task.spacing
  edge.io.in.bits.index := Mux(vertical, vIndex, uIndex)
  edge.io.out.ready := state === State.vWait || state === State.uWait
  edge.io.cancel := (state === State.vWait || state === State.uWait) && stop

  when(io.begin.fire) {
    task := io.begin.bits; result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    uIndex := 1.U; vIndex := 1.U; cancelled := false.B; state := State.prepareOffer
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(levels.io.in.fire) { state := State.prepareWait }
  when(levels.io.out.fire) {
    prepared := levels.io.out.bits
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(levels.io.out.bits.invalid) { result.status := 1.U; state := State.complete }
      .elsewhen(levels.io.out.bits.discard) { result.discarded := true.B; state := State.complete }
      .elsewhen(levels.io.out.bits.inner(0).segments <= 1.U || levels.io.out.bits.inner(1).segments <= 1.U) {
        state := State.complete
      }.otherwise { state := State.vOffer }
  }
  when(edge.io.in.fire) { state := Mux(state === State.vOffer, State.vWait, State.uWait) }
  when(edge.io.out.fire) {
    when(stop || edge.io.out.bits.status === 2.U) { result.status := 4.U; state := State.complete }
      .elsewhen(edge.io.out.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .elsewhen(state === State.vWait) { v := edge.io.out.bits.coordinate; state := State.uOffer }
      .otherwise { u := edge.io.out.bits.coordinate; state := State.emit }
  }
  when(io.out.fire) {
    result.emitted := result.emitted + 1.U
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(uIndex +& 1.U === prepared.inner(1).segments) {
        uIndex := 1.U
        when(vIndex +& 1.U === prepared.inner(0).segments) { state := State.complete }
          .otherwise { vIndex := vIndex + 1.U; state := State.vOffer }
      }.otherwise { uIndex := uIndex + 1.U; state := State.uOffer }
  }
  when(io.completion.fire) { state := State.idle }
}
