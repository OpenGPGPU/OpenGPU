package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationIsolineTask extends Bundle {
  val taskId = UInt(32.W)
  val spacing = UInt(2.W)
  val outer = Vec(2, UInt(32.W))
  val pointMode = Bool()
}
class TessellationIsolineVertex extends Bundle {
  /** Unsigned Q0.24 tessellation coordinates, including 1.0 as 1<<24. */
  val u = UInt(25.W); val v = UInt(25.W)
}
class TessellationIsolinePrimitive extends Bundle {
  val taskId = UInt(32.W); val sequence = UInt(16.W)
  val point = Bool(); val vertices = Vec(2, new TessellationIsolineVertex)
}
class TessellationIsolineCompletion extends Bundle {
  val taskId = UInt(32.W); val emitted = UInt(16.W)
  /** 0 success or discarded patch, 1 invalid input, 4 cancellation. */
  val status = UInt(3.W); val discarded = Bool()
}

/** Generate isoline-domain points or ordered line segments for one patch.
  * Edge coordinates are generated serially and every offered primitive is
  * retained through backpressure and cancellation. The consumer must run the
  * evaluation shader for each vertex before treating output as geometry.
  */
class TessellationIsolineGenerator extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationIsolineTask))
    val out = Decoupled(new TessellationIsolinePrimitive)
    val completion = Decoupled(new TessellationIsolineCompletion)
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle, prepareOffer, prepareWait, verticalOffer, verticalWait,
      horizontalOffer, horizontalWait, emit, complete = Value
  }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationIsolineTask)
  private val prepared = Reg(new TessellationLevelResult)
  private val result = Reg(new TessellationIsolineCompletion)
  private val cancelled = RegInit(false.B)
  private val line = Reg(UInt(7.W)); private val index = Reg(UInt(7.W))
  private val vertical = Reg(UInt(25.W)); private val previous = Reg(UInt(25.W))
  private val current = Reg(UInt(25.W))
  private val levels = Module(new TessellationLevelPrepare)
  private val edge = Module(new TessellationEdgeCoordinate)
  private val stop = cancelled || io.cancel

  io.begin.ready := state === State.idle && !io.cancel
  io.busy := state =/= State.idle
  io.out.valid := state === State.emit
  io.out.bits.taskId := task.taskId; io.out.bits.sequence := result.emitted
  io.out.bits.point := task.pointMode
  io.out.bits.vertices(0).u := Mux(task.pointMode, current, previous)
  io.out.bits.vertices(0).v := vertical
  io.out.bits.vertices(1).u := current; io.out.bits.vertices(1).v := vertical
  io.completion.valid := state === State.complete; io.completion.bits := result

  levels.io.in.valid := state === State.prepareOffer
  levels.io.in.bits.domain := TessellationDomain.isolines.U
  levels.io.in.bits.spacing := task.spacing
  levels.io.in.bits.outer := VecInit(Seq(task.outer(0), task.outer(1), 0.U(32.W), 0.U(32.W)))
  levels.io.in.bits.inner := VecInit(Seq.fill(2)(0.U(32.W)))
  levels.io.out.ready := state === State.prepareWait
  edge.io.in.valid := state === State.verticalOffer || state === State.horizontalOffer
  edge.io.in.bits.level := Mux(state === State.verticalOffer, prepared.outer(0).clamped, prepared.outer(1).clamped)
  edge.io.in.bits.spacing := Mux(state === State.verticalOffer, TessellationSpacing.equal.U, task.spacing)
  edge.io.in.bits.index := Mux(state === State.verticalOffer, line, index)
  edge.io.in.bits.promoted := false.B
  edge.io.out.ready := state === State.verticalWait || state === State.horizontalWait
  // An already offered edge request remains valid until accepted; cancellation
  // then reaches the accepted computation and its reply is drained.
  edge.io.cancel := (state === State.verticalWait || state === State.horizontalWait) && stop

  when(io.begin.fire) {
    task := io.begin.bits; result := 0.U.asTypeOf(result); result.taskId := io.begin.bits.taskId
    cancelled := false.B; line := 0.U; index := 0.U; state := State.prepareOffer
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(levels.io.in.fire) { state := State.prepareWait }
  when(levels.io.out.fire) {
    prepared := levels.io.out.bits
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(levels.io.out.bits.invalid) { result.status := 1.U; state := State.complete }
      .elsewhen(levels.io.out.bits.discard) { result.discarded := true.B; state := State.complete }
      .otherwise { state := State.verticalOffer }
  }
  when(edge.io.in.fire) {
    state := Mux(state === State.verticalOffer, State.verticalWait, State.horizontalWait)
  }
  when(edge.io.out.fire) {
    when(stop || edge.io.out.bits.status === 2.U) { result.status := 4.U; state := State.complete }
      .elsewhen(edge.io.out.bits.status =/= 0.U) { result.status := 1.U; state := State.complete }
      .elsewhen(state === State.verticalWait) {
        vertical := edge.io.out.bits.coordinate; previous := 0.U
        index := Mux(task.pointMode, 0.U, 1.U); state := State.horizontalOffer
      }.otherwise { current := edge.io.out.bits.coordinate; state := State.emit }
  }
  when(io.out.fire) {
    result.emitted := result.emitted + 1.U
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(index === prepared.outer(1).segments) {
        when(line + 1.U === prepared.outer(0).segments) { state := State.complete }
          .otherwise { line := line + 1.U; state := State.verticalOffer }
      }.otherwise { previous := current; index := index + 1.U; state := State.horizontalOffer }
  }
  when(io.completion.fire) { state := State.idle }
}
