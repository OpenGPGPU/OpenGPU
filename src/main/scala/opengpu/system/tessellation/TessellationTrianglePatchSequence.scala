package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._

class TessellationTrianglePatchSequenceDescriptor(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int) extends Bundle {
  val template = new TessellationSystemTriangleInputImageTask(config, p, idWidth)
  val count = UInt(32.W)
  val patchStride = UInt(32.W)
}
class TessellationTrianglePatchSequenceCompletion extends Bundle {
  val status = UInt(3.W)
  val completed = UInt(32.W)
  val failedPatch = UInt(32.W)
  val child = new TessellationSystemTriangleInputImageTaskCompletion
}

/** Issue a bounded series of input-backed patches to an already-open image frame.
  * The enclosing frame owner retains allocations until both this sequence and
  * the frame have drained. It forwards childCancel to the frame, rather than
  * cancelling the frame before a held task offer has been accepted. A child
  * offer remains stable through cancellation, and accepted work is retired
  * before completion. A terminal frameStopped signal flushes an unaccepted
  * offer; the frame must then be unable to accept it. Task IDs advance per
  * patch; the draw token stays fixed.
  */
class TessellationTrianglePatchSequence(config: GpuConfig, p: VertexShaderStageConfig, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationTrianglePatchSequenceDescriptor(config, p, idWidth)))
    val task = Decoupled(new TessellationSystemTriangleInputImageTask(config, p, idWidth))
    val taskCompletion = Flipped(Decoupled(new TessellationSystemTriangleInputImageTaskCompletion))
    val childCancel = Output(Bool())
    val frameStopped = Input(Bool())
    val completion = Decoupled(new TessellationTrianglePatchSequenceCompletion)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, offer, waiting, complete = Value }
  private val state = RegInit(State.idle)
  private val task = Reg(new TessellationSystemTriangleInputImageTask(config, p, idWidth))
  private val count = Reg(UInt(32.W)); private val stride = Reg(UInt(32.W))
  private val index = Reg(UInt(32.W)); private val cancelled = RegInit(false.B)
  private val result = Reg(new TessellationTrianglePatchSequenceCompletion)
  private val input = io.begin.bits.template.input
  private val patch = io.begin.bits.template.source.viewport.system.patch.control.patch.context
  private val lastBase = input.base.pad(65) +& ((io.begin.bits.count - 1.U) * io.begin.bits.patchStride).pad(65)
  private val lastEnd = lastBase +& ((patch.layout.inputVertices - 1.U) * io.begin.bits.template.stride).pad(65) +&
    (patch.layout.inputComponents << 2).pad(65)
  private val lastId = patch.taskId +& (io.begin.bits.count - 1.U)
  private val activeInput = patch.layout.inputComponents =/= 0.U
  private val legalInput = input.allocationBase(5,0) === 0.U && input.allocationLimit(5,0) === 0.U &&
    input.allocationLimit > input.allocationBase && input.allocationLimit <= (BigInt(1) << 32).U &&
    input.base >= input.allocationBase && input.limit >= input.base && input.limit <= input.allocationLimit &&
    input.base(1,0) === 0.U && io.begin.bits.template.stride(1,0) === 0.U &&
    io.begin.bits.patchStride(1,0) === 0.U && lastEnd <= input.limit && lastEnd <= (BigInt(1) << 32).U
  private val legal = patch.layout.inputVertices >= 1.U && patch.layout.inputVertices <= TessellationLimits.maxPatchVertices.U &&
    patch.layout.inputComponents <= 64.U && patch.layout.inputVertices * patch.layout.inputComponents <= TessellationLimits.maxControlOutputComponents.U &&
    !lastId(32) && (!activeInput || legalInput) && lastBase < (BigInt(1) << 32).U
  io.begin.ready := state === State.idle && !io.cancel
  when(io.begin.fire) {
    task := io.begin.bits.template; count := io.begin.bits.count; stride := io.begin.bits.patchStride
    index := 0.U; cancelled := false.B; result := 0.U.asTypeOf(result)
    when(io.begin.bits.count === 0.U) { state := State.complete }
      .elsewhen(!legal) { result.status := 1.U; state := State.complete }
      .otherwise { state := State.offer }
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  io.task.valid := state === State.offer && !io.frameStopped; io.task.bits := task
  when(state === State.offer && io.frameStopped) {
    result.status := Mux(cancelled || io.cancel, 4.U, 1.U); result.failedPatch := index; state := State.complete
  }
  when(io.task.fire) { state := State.waiting }
  io.childCancel := (state === State.waiting) && (cancelled || io.cancel)
  io.taskCompletion.ready := state === State.waiting
  when(io.taskCompletion.fire) {
    result.child := io.taskCompletion.bits
    val feedbackStatus=io.taskCompletion.bits.source.source.feedbackStatus
    val feedbackFault=io.taskCompletion.bits.status===5.U && feedbackStatus=/=0.U && feedbackStatus=/=4.U
    when(feedbackFault) { result.status := 5.U; result.failedPatch := index; state := State.complete }
      .elsewhen(cancelled || io.cancel) { result.status := 4.U; result.failedPatch := index; state := State.complete }
      .elsewhen(io.taskCompletion.bits.status =/= 0.U) {
        result.status := io.taskCompletion.bits.status; result.failedPatch := index; state := State.complete
      }.otherwise {
        result.completed := result.completed + 1.U
        when(index +& 1.U === count) { state := State.complete }
          .otherwise {
            val published = io.taskCompletion.bits.source.source.patch.published
            val nextSequence = task.source.viewport.firstSequence +& published
            val nextPrimitive = task.source.viewport.firstPrimitiveId +& published
            when(nextSequence(64) || nextPrimitive(32)) {
              result.status := 1.U; result.failedPatch := index + 1.U; state := State.complete
            }.otherwise {
              index := index + 1.U
              task.input.base := task.input.base + stride
              task.source.viewport.firstSequence := nextSequence(63, 0)
              task.source.viewport.firstPrimitiveId := nextPrimitive(31, 0)
              task.source.viewport.system.patch.control.patch.context.taskId :=
                task.source.viewport.system.patch.control.patch.context.taskId + 1.U
              state := State.offer
            }
          }
      }
  }
  io.completion.valid := state === State.complete; io.completion.bits := result
  io.busy := state =/= State.idle
  when(io.completion.fire) { state := State.idle }
}
