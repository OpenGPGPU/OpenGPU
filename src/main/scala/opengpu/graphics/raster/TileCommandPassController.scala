package opengpu.graphics

import chisel3._
import chisel3.util._

/** Immutable command-list binding for one resident tile pass. The command
  * allocation is a virtual byte range in the owner's command-memory domain.
  * Attachment bindings come from passDescriptor; config supplies draw defaults
  * and the viewport. This is an experimental hardware submission interface.
  */
class TileCommandJob extends Bundle {
  val version = UInt(8.W)
  val passDescriptor = new TileRenderPassDescriptor
  val config = new JobConfig
  val commandLimit = UInt(33.W)
}

/** Own the command parser and tile pass through a single tagged completion.
  * Internal pass retirement cannot release the job while a command read is
  * still offered/in flight. Results are latched before external publication,
  * so late cancellation cannot change a backpressured completion.
  */
class TileCommandPassController extends Module {
  val io = IO(new Bundle {
    val job = Flipped(Decoupled(new TileCommandJob))
    val completion = Decoupled(new TileRenderPassCompletion)
    val cancel = Input(Bool())
    /** Pulse on an accepted command-memory response with a fault. */
    val commandFault = Input(Bool())
    val attachmentFault = Input(Bool())
    /** Pulse on an accepted texture response with a fault. */
    val textureFault = Input(Bool())
    val shaderFault = Input(Bool())
    val invalidCommand = Input(Bool())
    val clear = Flipped(Decoupled(new TileAttachmentClear))
    val clearComplete = Output(Bool())
    val typedCommands = Output(Bool())
    val pass = Flipped(new TileRenderPassPort)
    val admitted = Input(Bool())
    val commandsDrained = Input(Bool())
    val renderDrained = Input(Bool())
    val startCommands = Output(Bool())
    val cancelCommands = Output(Bool())
    val config = Output(new JobConfig)
    val busy = Output(Bool())
  })

  private val idle :: range :: validate :: begin :: admission :: launch :: running :: ending :: result :: Nil = Enum(9)
  private val state = RegInit(idle)
  private val snapshot = Reg(new TileCommandJob)
  private val commandEnd = Reg(UInt(33.W))
  private val passIssued = RegInit(false.B)
  private val draining = RegInit(false.B)
  private val abortStatus = RegInit(TilePassStatus.success.U(TilePassStatus.width.W))
  private val resultBits = Reg(new TileRenderPassCompletion)
  private val active = state =/= idle && state =/= result
  // clearDone incorporates the owner's cancellation input. Register its
  // failure before feeding cancellation back, avoiding a combinational loop.
  private val invalidClear = RegNext(io.pass.clearDone.fire && !io.pass.clearDone.bits && !draining &&
    !(io.cancel || io.attachmentFault || io.commandFault || io.textureFault || io.shaderFault), false.B)
  private val invalid = io.invalidCommand || invalidClear
  private val abortNow = io.cancel || io.attachmentFault || io.commandFault || io.textureFault || io.shaderFault || invalid
  private val effectiveStatus = Mux(io.attachmentFault || abortStatus === TilePassStatus.memoryFault.U,
    TilePassStatus.memoryFault.U, Mux(io.commandFault, TilePassStatus.commandFault.U,
    Mux(io.textureFault && abortStatus =/= TilePassStatus.commandFault.U, TilePassStatus.textureFault.U,
    Mux(io.shaderFault && abortStatus =/= TilePassStatus.commandFault.U && abortStatus =/= TilePassStatus.textureFault.U,
      TilePassStatus.executionFault.U,
      Mux(invalid && abortStatus === TilePassStatus.success.U, TilePassStatus.invalidDescriptor.U,
      Mux(io.cancel && abortStatus === TilePassStatus.success.U,
        TilePassStatus.cancelled.U, abortStatus))))))

  io.job.ready := state === idle && io.commandsDrained && io.renderDrained
  io.busy := state =/= idle
  io.config := snapshot.config
  io.typedCommands := snapshot.version === 2.U
  io.completion.valid := state === result
  io.completion.bits := resultBits
  io.pass.begin.valid := state === begin && !draining && !abortNow
  io.pass.begin.bits := snapshot.passDescriptor
  io.pass.end.valid := state === running && io.commandsDrained && io.renderDrained && !io.clear.valid && !draining && !abortNow
  io.pass.end.bits := true.B
  // Presenting a clear closes the pipeline's draw-admission gate. Wait for
  // older accepted geometry/fan work first, so it can still reach the OM.
  io.pass.clear.valid := state === running && io.clear.valid && io.renderDrained && !draining && !abortNow
  io.pass.clear.bits := io.clear.bits
  io.clear.ready := state === running && io.renderDrained && io.pass.clear.ready && !draining && !abortNow
  io.clearComplete := io.pass.clearDone.fire
  io.pass.clearDone.ready := true.B
  io.pass.cancel := passIssued && (draining || abortNow)
  io.pass.memoryFault := io.attachmentFault
  io.startCommands := state === launch && !draining && !abortNow
  io.cancelCommands := active && (draining || abortNow)
  io.pass.completion.ready := active && passIssued && io.commandsDrained && io.renderDrained

  private def finish(status: UInt): Unit = {
    resultBits.identity := snapshot.passDescriptor.identity
    resultBits.status := status
    state := result
  }
  when(io.job.fire) {
    snapshot := io.job.bits
    abortStatus := TilePassStatus.success.U
    draining := false.B
    passIssued := false.B
    state := range
  }
  when(state === range) {
    // A 16-bit record count times 160/164 bytes plus a 32-bit base fits in 33
    // bits. Preserve that carry until the exclusive allocation check.
    val payloadBytes = snapshot.config.cmdCount * TileCommandMemoryLayout.legacyBytes.U
    val headerBytes = Mux(snapshot.version === 2.U, snapshot.config.cmdCount << 2, 0.U)
    commandEnd := snapshot.config.cmdBase +& (payloadBytes +& headerBytes)
    state := validate
  }
  when(state === validate) {
    val valid = (snapshot.version === 1.U || snapshot.version === 2.U) && snapshot.config.cmdBase(1, 0) === 0.U &&
      snapshot.commandLimit <= (BigInt(1) << 32).U &&
      snapshot.config.cmdBase <= snapshot.commandLimit && commandEnd <= snapshot.commandLimit
    when(valid) { state := begin }
      .otherwise { finish(TilePassStatus.invalidDescriptor.U) }
  }
  when(io.pass.begin.fire) { passIssued := true.B; state := admission }
  when(state === admission && io.admitted && !draining && !abortNow) { state := launch }
  when(io.startCommands) { state := running }
  when(io.pass.end.fire) { state := ending }

  when(active && abortNow) {
    draining := true.B
    abortStatus := effectiveStatus
    when(!passIssued) { finish(effectiveStatus) }
  }
  when(io.pass.completion.fire) {
    assert(io.pass.completion.bits.identity.asUInt === snapshot.passDescriptor.identity.asUInt,
      "tile command completion must retain the submitted pass identity")
    // Preserve precise pass errors. A controller-originated failure replaces
    // a cancellation/success result from the attachment owner.
    val ownerStatus = io.pass.completion.bits.status
    val overrideOwner = effectiveStatus =/= TilePassStatus.success.U &&
      (ownerStatus === TilePassStatus.success.U || ownerStatus === TilePassStatus.cancelled.U)
    resultBits := io.pass.completion.bits
    resultBits.status := Mux(overrideOwner, effectiveStatus, ownerStatus)
    passIssued := false.B
    state := result
  }
  when(io.completion.fire) { state := idle }
}
