package opengpu.graphics

import chisel3._
import chisel3.util._

/** One input primitive and its immutable vertex payload. Input counts encode
  * point, line, triangle, line adjacency and triangle adjacency respectively.
  */
class GeometryInvocationTask extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
  val inputVertices = UInt(3.W)
  val inputComponents = UInt(7.W)
  val vertices = Vec(6, new GeometryOutputVertex)
  val invocations = UInt(6.W)
  val topology = UInt(2.W)
  val maxVertices = UInt(9.W)
  val outputComponents = UInt(7.W)
}
class GeometryInvocationLaunch extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val inputVertices = UInt(3.W)
  val inputComponents = UInt(7.W)
  val vertices = Vec(6, new GeometryOutputVertex)
}
class GeometryInvocationWorkerResult extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val success = Bool()
}
class GeometryInvocationResult extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
  val completedInvocations = UInt(6.W)
  val status = UInt(2.W)
}
object GeometryInvocationStatus {
  val success = 0.U(2.W)
  val invalid = 1.U(2.W)
  val cancelled = 2.U(2.W)
  val workerFault = 3.U(2.W)
}

/** Retains one primitive until every ordered invocation and emitted primitive
  * drains. A worker must stop producing commands before its completion; on
  * abort it must acknowledge termination even if a command is still offered.
  * This internal interface does not yet launch a physical shader CU.
  */
class GeometryInvocationController extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new GeometryInvocationTask))
    val launch = Decoupled(new GeometryInvocationLaunch)
    val command = Flipped(Decoupled(new GeometryOutputCommand))
    val workerCompletion = Flipped(Decoupled(new GeometryInvocationWorkerResult))
    val abort = Output(Bool())
    val primitive = Decoupled(new GeometryOutputPrimitive)
    val completion = Decoupled(new GeometryInvocationResult)
    val cancel = Input(Bool())
  })
  private object State extends ChiselEnum { val idle, prepare, launch, run, finishOffer, waitAssembler, stop, complete = Value }
  private val state = RegInit(State.idle)
  private val task = Reg(new GeometryInvocationTask)
  private val invocation = RegInit(0.U(6.W))
  private val completed = RegInit(0.U(6.W))
  private val workerLive = RegInit(false.B)
  private val assemblerEnded = RegInit(true.B)
  private val status = RegInit(GeometryInvocationStatus.success)
  private val assembler = Module(new GeometryOutputAssembler)
  private val inputCountValid = Seq(1,2,3,4,6).map(n => io.task.bits.inputVertices === n.U).reduce(_ || _)
  private val product = io.task.bits.maxVertices * io.task.bits.outputComponents
  private val validTask = inputCountValid && io.task.bits.inputComponents <= 64.U &&
    io.task.bits.invocations >= 1.U && io.task.bits.invocations <= 32.U &&
    io.task.bits.topology <= GeometryOutputTopology.triangleStrip &&
    io.task.bits.maxVertices <= 256.U && io.task.bits.outputComponents <= 64.U && product <= 1024.U

  io.task.ready := state === State.idle && !io.cancel
  io.launch.valid := state === State.launch && !io.cancel
  io.launch.bits.taskId := task.taskId; io.launch.bits.primitiveId := task.primitiveId
  io.launch.bits.invocationId := invocation
  io.launch.bits.inputVertices := task.inputVertices; io.launch.bits.inputComponents := task.inputComponents
  io.launch.bits.vertices := task.vertices
  io.abort := state === State.stop && workerLive
  io.workerCompletion.ready := workerLive &&
    (state === State.stop || (state === State.run && !io.command.valid))
  io.command.ready := state === State.run && !io.cancel && assembler.io.command.ready
  assembler.io.command.valid := (state === State.run && !io.cancel && io.command.valid) ||
    (state === State.finishOffer && !io.cancel)
  assembler.io.command.bits := io.command.bits
  when(state === State.finishOffer) {
    assembler.io.command.bits := 0.U.asTypeOf(assembler.io.command.bits)
    assembler.io.command.bits.operation := GeometryOutputEvent.finish
  }.elsewhen(io.command.bits.operation === GeometryOutputEvent.finish) {
    // Only worker completion may close an invocation; an early finish is invalid.
    assembler.io.command.bits.operation := 3.U
  }
  assembler.io.task.valid := state === State.prepare && !io.cancel
  assembler.io.task.bits.taskId := task.taskId; assembler.io.task.bits.primitiveId := task.primitiveId
  assembler.io.task.bits.invocationId := invocation; assembler.io.task.bits.topology := task.topology
  assembler.io.task.bits.maxVertices := task.maxVertices; assembler.io.task.bits.outputComponents := task.outputComponents
  assembler.io.cancel := state === State.stop ||
    (io.cancel && (state === State.launch || state === State.run || state === State.finishOffer || state === State.waitAssembler))
  assembler.io.completion.ready := state === State.run || state === State.waitAssembler || state === State.stop
  io.primitive <> assembler.io.primitive
  io.completion.valid := state === State.complete
  io.completion.bits.taskId := task.taskId; io.completion.bits.primitiveId := task.primitiveId
  io.completion.bits.completedInvocations := completed; io.completion.bits.status := status

  when(io.workerCompletion.fire) {
    assert(io.workerCompletion.bits.taskId === task.taskId && io.workerCompletion.bits.primitiveId === task.primitiveId &&
      io.workerCompletion.bits.invocationId === invocation, "geometry worker completion must match its owner")
    workerLive := false.B
  }
  when(assembler.io.completion.fire) { assemblerEnded := true.B }
  when(io.task.fire) {
    task := io.task.bits; invocation := 0.U; completed := 0.U
    workerLive := false.B; assemblerEnded := true.B
    status := Mux(validTask, GeometryInvocationStatus.success, GeometryInvocationStatus.invalid)
    state := Mux(validTask, State.prepare, State.complete)
  }
  switch(state) {
    is(State.prepare) {
      when(io.cancel) { status := GeometryInvocationStatus.cancelled; state := State.complete }
        .elsewhen(assembler.io.task.fire) { assemblerEnded := false.B; state := State.launch }
    }
    is(State.launch) {
      when(io.cancel) { status := GeometryInvocationStatus.cancelled; state := State.stop }
        .elsewhen(io.launch.fire) { workerLive := true.B; state := State.run }
    }
    is(State.run) {
      when(io.cancel) { status := GeometryInvocationStatus.cancelled; state := State.stop }
        .elsewhen(assembler.io.completion.fire) {
          status := GeometryInvocationStatus.invalid; state := State.stop
        }.elsewhen(io.workerCompletion.fire) {
          when(io.workerCompletion.bits.success) { state := State.finishOffer }
            .otherwise { status := GeometryInvocationStatus.workerFault; state := State.stop }
        }
    }
    is(State.finishOffer) {
      when(io.cancel) { status := GeometryInvocationStatus.cancelled; state := State.stop }
        .elsewhen(assembler.io.command.fire) { state := State.waitAssembler }
    }
    is(State.waitAssembler) {
      when(io.cancel) { status := GeometryInvocationStatus.cancelled; state := State.stop }
        .elsewhen(assembler.io.completion.fire) {
          when(assembler.io.completion.bits.status =/= GeometryOutputStatus.success) {
            status := GeometryInvocationStatus.invalid; state := State.complete
          }.otherwise {
            completed := completed + 1.U
            when(invocation + 1.U === task.invocations) { state := State.complete }
              .otherwise { invocation := invocation + 1.U; state := State.prepare }
          }
        }
    }
    is(State.stop) { when(!workerLive && assemblerEnded) { state := State.complete } }
    is(State.complete) { when(io.completion.fire) { state := State.idle } }
  }
}
