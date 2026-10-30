package opengpu.graphics

import chisel3._
import chisel3.util._

/** Internal single-invocation geometry output protocol. Values are opaque
  * 32-bit components; their linked-program layout is owned by the caller.
  */
object GeometryOutputTopology {
  val points = 0.U(2.W)
  val lineStrip = 1.U(2.W)
  val triangleStrip = 2.U(2.W)
}
object GeometryOutputEvent {
  val emit = 0.U(2.W)
  val endPrimitive = 1.U(2.W)
  val finish = 2.U(2.W)
}
object GeometryOutputStatus {
  val success = 0.U(2.W)
  val invalid = 1.U(2.W)
  val cancelled = 2.U(2.W)
}
class GeometryOutputTask extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val topology = UInt(2.W)
  val maxVertices = UInt(9.W)
  val outputComponents = UInt(7.W)
  val builtinOutputMask = UInt(2.W)
}
class GeometryOutputVertex extends Bundle {
  val components = Vec(64, UInt(32.W))
  val layer = UInt(32.W)
  val shaderPrimitiveId = UInt(32.W)
}
class GeometryOutputCommand extends Bundle {
  val operation = UInt(2.W)
  val vertex = new GeometryOutputVertex
}
class GeometryOutputPrimitive extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val vertexCount = UInt(2.W)
  val vertices = Vec(3, new GeometryOutputVertex)
}
class GeometryOutputCompletion extends Bundle {
  val taskId = UInt(32.W)
  val primitiveId = UInt(32.W)
  val invocationId = UInt(6.W)
  val emittedVertices = UInt(9.W)
  val status = UInt(2.W)
}

/** Serial geometry-output assembly with bounded ownership and held outputs.
  * The caller must provide invocations in the required primitive/invocation
  * order, and must separately capture emitted vertices for transform feedback.
  */
class GeometryOutputAssembler extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new GeometryOutputTask))
    val command = Flipped(Decoupled(new GeometryOutputCommand))
    val primitive = Decoupled(new GeometryOutputPrimitive)
    val completion = Decoupled(new GeometryOutputCompletion)
    val cancel = Input(Bool())
  })
  private object State extends ChiselEnum { val idle, active, drain, complete = Value }
  private val state = RegInit(State.idle)
  private val task = Reg(new GeometryOutputTask)
  private val emitted = RegInit(0.U(9.W))
  private val stripSeen = RegInit(0.U(2.W))
  private val odd = RegInit(false.B)
  private val previous = Reg(new GeometryOutputVertex)
  private val latest = Reg(new GeometryOutputVertex)
  private val outputValid = RegInit(false.B)
  private val output = Reg(new GeometryOutputPrimitive)
  private val status = RegInit(GeometryOutputStatus.success)
  private val totalComponents = io.task.bits.outputComponents +& PopCount(io.task.bits.builtinOutputMask)
  private val product = io.task.bits.maxVertices * totalComponents
  private val validTask = io.task.bits.topology <= GeometryOutputTopology.triangleStrip &&
    io.task.bits.invocationId < 32.U && io.task.bits.maxVertices <= 256.U &&
    totalComponents <= 64.U && product <= 1024.U

  io.task.ready := state === State.idle && !io.cancel
  io.command.ready := state === State.active && !outputValid && !io.cancel
  io.primitive.valid := outputValid
  io.primitive.bits := output
  io.completion.valid := state === State.complete
  io.completion.bits.taskId := task.taskId
  io.completion.bits.primitiveId := task.primitiveId
  io.completion.bits.invocationId := task.invocationId
  io.completion.bits.emittedVertices := emitted
  io.completion.bits.status := status
  when(io.primitive.fire) { outputValid := false.B }
  when(io.task.fire) {
    task := io.task.bits; emitted := 0.U; stripSeen := 0.U; odd := false.B
    status := Mux(validTask, GeometryOutputStatus.success, GeometryOutputStatus.invalid)
    state := Mux(validTask, State.active, State.complete)
  }
  when(state === State.active) {
    when(io.cancel) {
      status := GeometryOutputStatus.cancelled
      state := Mux(outputValid && !io.primitive.ready, State.drain, State.complete)
    }.elsewhen(io.command.fire) {
      switch(io.command.bits.operation) {
        is(GeometryOutputEvent.endPrimitive) { stripSeen := 0.U; odd := false.B }
        is(GeometryOutputEvent.finish) { state := State.complete }
        is(GeometryOutputEvent.emit) {
          when(emitted >= task.maxVertices) {
            status := GeometryOutputStatus.invalid; state := State.complete
          }.otherwise {
            emitted := emitted + 1.U
            when(task.topology === GeometryOutputTopology.points) {
              outputValid := true.B
              output.taskId := task.taskId; output.primitiveId := task.primitiveId
              output.invocationId := task.invocationId; output.vertexCount := 1.U
              output.vertices(0) := io.command.bits.vertex
              output.vertices(1) := 0.U.asTypeOf(output.vertices(1))
              output.vertices(2) := 0.U.asTypeOf(output.vertices(2))
            }.elsewhen(task.topology === GeometryOutputTopology.lineStrip) {
              when(stripSeen =/= 0.U) {
                outputValid := true.B
                output.taskId := task.taskId; output.primitiveId := task.primitiveId
                output.invocationId := task.invocationId; output.vertexCount := 2.U
                output.vertices(0) := previous; output.vertices(1) := io.command.bits.vertex
                output.vertices(2) := 0.U.asTypeOf(output.vertices(2))
              }
              previous := io.command.bits.vertex; stripSeen := 1.U
            }.otherwise {
              when(stripSeen === 0.U) { previous := io.command.bits.vertex; stripSeen := 1.U }
                .elsewhen(stripSeen === 1.U) { latest := io.command.bits.vertex; stripSeen := 2.U }
                .otherwise {
                  outputValid := true.B
                  output.taskId := task.taskId; output.primitiveId := task.primitiveId
                  output.invocationId := task.invocationId; output.vertexCount := 3.U
                  output.vertices(0) := Mux(odd, latest, previous)
                  output.vertices(1) := Mux(odd, previous, latest)
                  output.vertices(2) := io.command.bits.vertex
                  previous := latest; latest := io.command.bits.vertex; odd := !odd
                }
            }
          }
        }
      }
      when(io.command.bits.operation > GeometryOutputEvent.finish) {
        status := GeometryOutputStatus.invalid; state := State.complete
      }
    }
  }
  when(state === State.drain && (!outputValid || io.primitive.fire)) { state := State.complete }
  when(io.completion.fire) { state := State.idle }
}
