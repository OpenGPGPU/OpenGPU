package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

class TessellationGeometryPipelineTask(p: VertexShaderStageConfig, commandIdWidth: Int) extends Bundle {
  val raw = new TessellationGeometryRawRequest(p)
  val commandId = UInt(commandIdWidth.W)
}
class TessellationGeometryPipelineResult(commandIdWidth: Int) extends Bundle {
  val identity = new GraphicsWorkIdentity
  val commandId = UInt(commandIdWidth.W)
  val tesTaskId = UInt(32.W); val tesSequence = UInt(16.W); val tesLanguagePatchId = UInt(32.W)
  val gsTaskId = UInt(32.W); val gsInputPrimitiveId = UInt(32.W)
  val inputSequence = UInt(64.W); val firstOutputSequence = UInt(64.W)

  /** 0 success, 1 invalid, 2 GS worker fault, 3 protocol, 4 cancelled. */
  val status = UInt(3.W)
  val bridgeFaultValid = Bool(); val bridgeFault = new TessellationGeometryRawFault

  /** True only after an actual child completion handshake. */
  val geometryValid = Bool(); val geometry = new GeometryPrimitivePipelineResult
}

/** Ownership controller, shared by the actual composition and the explicitly peer-driven interface test. A captured raw
  * packet is an irrevocable GS offer, even when cancellation precedes the GS acceptance. Only that offer's handshake
  * authorizes forwarding cancellation to the child's !cancel gate.
  */
class TessellationGeometryPipelineControl(
    p: VertexShaderStageConfig,
    commandIdWidth: Int,
    exclusiveGsSequence: Boolean = false
) extends Module {
  def this(p: VertexShaderStageConfig, commandIdWidth: Int) = this(p, commandIdWidth, false)
  require(commandIdWidth > 0)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationGeometryPipelineTask(p, commandIdWidth)))
    val completion = Decoupled(new TessellationGeometryPipelineResult(commandIdWidth))
    val rawIn = Decoupled(new TessellationGeometryRawRequest(p))
    val rawOut = Flipped(Decoupled(new TessellationGeometryRawPacket(p)))
    val rawFault = Flipped(Decoupled(new TessellationGeometryRawFault))
    val rawBusy = Input(Bool()); val rawCancel = Output(Bool())
    val geometryTask = Decoupled(new NativeGeometryPrimitivePipelineTask(p, commandIdWidth))
    val geometryCompletion = Flipped(Decoupled(new GeometryPrimitivePipelineResult))
    val geometryNextSequenceExclusive = if (exclusiveGsSequence) Some(Input(UInt(65.W))) else None
    val nextSequenceExclusive = if (exclusiveGsSequence) Some(Output(UInt(65.W))) else None
    val geometryBusy = Input(Bool()); val geometryOffersDrained = Input(Bool()); val geometryCancel = Output(Bool())

    /** Precise extra debt of this logical GS client/command: router, translation and invalidation. Never
      * shared-system/other-owner idle or permission.
      */
    val ownerTransportDrained = Input(Bool())
    val cancel = Input(Bool()); val busy = Output(Bool()); val drained = Output(Bool())
  })
  private object Phase extends ChiselEnum { val idle, raw, offer, running, drain, report = Value }
  private val phase = RegInit(Phase.idle)
  private val result = Reg(new TessellationGeometryPipelineResult(commandIdWidth))
  private val slot = Reg(new NativeGeometryPrimitivePipelineTask(p, commandIdWidth))
  private val exclusiveResult = if (exclusiveGsSequence) Some(Reg(UInt(65.W))) else None
  if (exclusiveGsSequence) io.nextSequenceExclusive.get := exclusiveResult.get
  private val cancelled = RegInit(false.B); private val protocolFault = RegInit(false.B)
  private val active = phase =/= Phase.idle && phase =/= Phase.report
  private val childrenDrained = !io.rawBusy && !io.geometryBusy && io.geometryOffersDrained && io.ownerTransportDrained
  io.task.ready := phase === Phase.idle && childrenDrained && io.rawIn.ready
  io.rawIn.valid := phase === Phase.idle && childrenDrained && io.task.valid
  io.rawIn.bits := io.task.bits.raw
  io.rawOut.ready := phase === Phase.raw
  io.rawFault.ready := active
  io.rawCancel := active && (cancelled || io.cancel)
  io.geometryTask.valid := phase === Phase.offer; io.geometryTask.bits := slot
  io.geometryCancel := phase === Phase.running && (cancelled || io.cancel)
  io.geometryCompletion.ready := phase === Phase.running
  io.completion.valid := phase === Phase.report; io.completion.bits := result
  io.busy := phase =/= Phase.idle
  // Held reports contain no child credit. Later flush/debt changes this live
  // predicate and blocks a new task, but cannot retract a captured report.
  io.drained := childrenDrained && !io.rawIn.valid && !io.rawOut.valid && !io.rawFault.valid &&
    !io.geometryTask.valid && !io.geometryCompletion.valid &&
    (phase === Phase.idle || phase === Phase.report)
  when(io.task.fire) {
    result := 0.U.asTypeOf(result)
    val t = io.task.bits.raw; val b = t.link
    result.identity := b.identity; result.commandId := io.task.bits.commandId
    result.tesTaskId := t.tesTaskId; result.tesSequence := t.tesSequence;
    result.tesLanguagePatchId := b.tesLanguagePatchId
    result.gsTaskId := b.gsTaskId; result.gsInputPrimitiveId := b.gsInputPrimitiveId
    result.inputSequence := b.inputSequence; result.firstOutputSequence := b.firstOutputSequence
    if (exclusiveGsSequence) exclusiveResult.get := b.firstOutputSequence
    cancelled := io.cancel; protocolFault := false.B; phase := Phase.raw
  }
  when(active && io.cancel) { cancelled := true.B }
  when(io.rawOut.fire) {
    slot.input := io.rawOut.bits.request; slot.commandId := result.commandId
    slot.firstSequence := io.rawOut.bits.firstOutputSequence
    // The actual bridge owns and checks its packet. Keep every captured word,
    // including GNG/GUR2, unchanged, rather than reconstructing a descriptor.
    assert(io.rawOut.bits.tesTaskId === result.tesTaskId && io.rawOut.bits.tesSequence === result.tesSequence)
    assert(
      io.rawOut.bits.request.taskId === result.gsTaskId &&
        io.rawOut.bits.request.primitive.primitiveId === result.gsInputPrimitiveId
    )
    assert(
      io.rawOut.bits.tesLanguagePatchId === result.tesLanguagePatchId &&
        io.rawOut.bits.firstOutputSequence === result.firstOutputSequence
    )
    phase := Phase.offer
  }
  when(io.rawFault.fire) {
    result.bridgeFaultValid := true.B; result.bridgeFault := io.rawFault.bits
    when(
      result.bridgeFaultValid || io.rawFault.bits.tesTaskId =/= result.tesTaskId ||
        io.rawFault.bits.tesSequence =/= result.tesSequence || io.rawFault.bits.gsTaskId =/= result.gsTaskId ||
        io.rawFault.bits.gsInputPrimitiveId =/= result.gsInputPrimitiveId ||
        (io.rawFault.bits.status =/= 1.U && io.rawFault.bits.status =/= 4.U)
    ) { protocolFault := true.B }
    // Before raw capture there is no GS offer/credit. Otherwise this local
    // cancellation report cannot replace the owed actual child completion.
    when(phase === Phase.raw) { phase := Phase.drain }
  }
  when(io.geometryTask.fire) { phase := Phase.running }
  when(io.geometryCompletion.fire) {
    result.geometryValid := true.B; result.geometry := io.geometryCompletion.bits
    if (exclusiveGsSequence) {
      exclusiveResult.get := io.geometryNextSequenceExclusive.get
      when(
        io.geometryNextSequenceExclusive.get(63, 0) =/= io.geometryCompletion.bits.nextSequence ||
          io.geometryNextSequenceExclusive.get < result.firstOutputSequence ||
          (io.geometryNextSequenceExclusive.get - result.firstOutputSequence)
            .pad(65) =/= io.geometryCompletion.bits.producedPrimitives.pad(65)
      ) {
        protocolFault := true.B
      }
    }
    when(
      io.geometryCompletion.bits.taskId =/= result.gsTaskId ||
        io.geometryCompletion.bits.inputPrimitiveId =/= result.gsInputPrimitiveId
    ) { protocolFault := true.B }
    phase := Phase.drain
  }
  private val geometryInvalid = result.geometryValid && result.geometry.status === GeometryInvocationStatus.invalid
  private val geometryWorkerFault =
    result.geometryValid && result.geometry.status === GeometryInvocationStatus.workerFault
  private val localInvalid = result.bridgeFaultValid && result.bridgeFault.status === 1.U
  private val stopped = cancelled || io.cancel || (result.bridgeFaultValid && result.bridgeFault.status === 4.U) ||
    (result.geometryValid && result.geometry.status === GeometryInvocationStatus.cancelled)
  when(
    phase === Phase.drain && childrenDrained && !io.rawOut.valid && !io.rawFault.valid &&
      !io.geometryCompletion.valid
  ) {
    result.status := Mux(
      protocolFault,
      3.U,
      Mux(localInvalid || geometryInvalid, 1.U, Mux(geometryWorkerFault, 2.U, Mux(stopped, 4.U, 0.U)))
    )
    assert(result.geometryValid || result.bridgeFaultValid, "a report must have an actual origin")
    phase := Phase.report
  }
  when(io.completion.fire) { phase := Phase.idle }
}
