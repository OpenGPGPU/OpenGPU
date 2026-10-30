package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest

/** Internal CUSTOM-0 scalar geometry operations; not a public shader ABI. */
object GeometryScalarOpcode {
  private def function(i: UInt, n: Int): Bool =
    i(6,0) === "h0b".U && i(31,25) === n.U && i(14,12) === 0.U
  def input(i: UInt): Bool = function(i, 32)
  def output(i: UInt): Bool = function(i, 33)
  def layer(i: UInt): Bool = function(i, 34)
  def emit(i: UInt): Bool = function(i, 35)
  def end(i: UInt): Bool = function(i, 36)
  def info(i: UInt): Bool = function(i, 37)
  def legal(i: UInt): Bool = input(i) || output(i) || layer(i) || emit(i) || end(i) || info(i)
}
class GeometryScalarBinding(config: GpuConfig) extends Bundle {
  val invocation = new GeometryInvocationLaunch
  val warpId = UInt(config.warpIdWidth.W)
}

/** Task-owned scalar instruction adapter. Accepted emit/end offers are held
  * until transferred, including during cancellation. The caller releases the
  * binding only after the physical worker and all accepted instructions drain.
  */
class GeometryScalarWorker(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val bind = Flipped(Decoupled(new GeometryScalarBinding(config)))
    val release = Flipped(Decoupled(Bool()))
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val commit = Decoupled(new ScalarCommitRequest(config))
    val event = Decoupled(new GeometryOutputCommand)
    val cancel = Input(Bool())
    val active = Output(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, event, commit = Value }
  private val state = RegInit(State.idle)
  private val active = RegInit(false.B)
  private val cancelled = RegInit(false.B)
  private val binding = Reg(new GeometryScalarBinding(config))
  private val staged = Reg(new GeometryOutputVertex)
  private val event = Reg(new GeometryOutputCommand)
  private val result = Reg(new ScalarCommitRequest(config))
  io.active := active
  io.busy := state =/= State.idle
  io.bind.ready := !active && state === State.idle && !io.cancel
  io.release.ready := active && state === State.idle
  io.in.ready := active && state === State.idle && !io.cancel && !cancelled && !io.release.valid
  io.event.valid := state === State.event
  io.event.bits := event
  io.commit.valid := state === State.commit
  io.commit.bits := result

  when(io.bind.fire) {
    binding := io.bind.bits; staged := 0.U.asTypeOf(staged)
    active := true.B; cancelled := false.B
  }
  when(active && io.cancel) { cancelled := true.B }
  when(io.release.fire) { active := false.B; cancelled := false.B }
  when(io.in.fire) {
    val d = io.in.bits.decode; val i = d.instruction
    val a = io.in.bits.rs1Data; val b = io.in.bits.rs2Data
    val inputValid = a < 6.U && a < binding.invocation.inputVertices && b < 64.U && b < binding.invocation.inputComponents
    val outputValid = a < 64.U && a < binding.invocation.outputComponents
    val infoValid = a < 6.U
    val valid = GeometryScalarOpcode.legal(i) && d.warpId === binding.warpId &&
      (!GeometryScalarOpcode.input(i) || inputValid) &&
      (!GeometryScalarOpcode.output(i) || outputValid) && (!GeometryScalarOpcode.info(i) || infoValid)
    result := 0.U.asTypeOf(result); result.warpId := d.warpId; result.nextPc := d.pc + 4.U
    result.activeMask := d.activeMask; result.rd := i(11,7); result.writeRd := valid
    result.data := 0.U
    when(valid && GeometryScalarOpcode.input(i)) {
      result.data := binding.invocation.vertices(a(2,0)).components(b(5,0))
    }
    when(valid && GeometryScalarOpcode.output(i)) { staged.components(a(5,0)) := b }
    when(valid && GeometryScalarOpcode.layer(i)) { staged.layer := a }
    when(valid && GeometryScalarOpcode.info(i)) {
      result.data := MuxLookup(a, 0.U)(Seq(
        0.U -> binding.invocation.invocationId, 1.U -> binding.invocation.primitiveId,
        2.U -> binding.invocation.inputVertices, 3.U -> binding.invocation.inputComponents,
        4.U -> binding.invocation.outputComponents, 5.U -> binding.invocation.taskId))
    }
    when(valid && (GeometryScalarOpcode.emit(i) || GeometryScalarOpcode.end(i))) {
      event := 0.U.asTypeOf(event)
      event.operation := Mux(GeometryScalarOpcode.emit(i), GeometryOutputEvent.emit, GeometryOutputEvent.endPrimitive)
      event.vertex := staged; state := State.event
    }.otherwise { state := State.commit }
  }
  when(state === State.event && io.event.fire) {
    when(io.cancel || cancelled) { result.writeRd := false.B }
    when(event.operation === GeometryOutputEvent.emit) { staged := 0.U.asTypeOf(staged) }
    state := State.commit
  }
  when(state === State.commit && io.commit.fire) { state := State.idle }
}
