package opengpu.graphics

import chisel3._

/** Snapshots a validated publication at the same boundary as its draw. */
class GraphicsImageDrawCapture extends Module {
  val io = IO(new Bundle {
    val capture = Input(Bool())
    val task = Input(new ShaderImageTask)
    val identity = Input(new GraphicsWorkIdentity)
    val sideEffectFree = Input(Bool())
    val captured = Output(new ShaderImageTask)
    val invalid = Output(Bool())
  })
  private val task = RegInit(0.U.asTypeOf(new ShaderImageTask))
  private val identity = RegInit(0.U.asTypeOf(new GraphicsWorkIdentity))
  private val sideEffectFree = RegInit(false.B)
  when(io.capture) {
    task := io.task
    identity := io.identity
    sideEffectFree := io.sideEffectFree
  }
  io.captured := task
  io.invalid := task.enabled && (task.table.identity.asUInt =/= identity.asUInt || sideEffectFree)
}
