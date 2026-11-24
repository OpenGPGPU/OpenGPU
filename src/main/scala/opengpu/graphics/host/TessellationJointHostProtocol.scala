package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.GpuCommand
import opengpu.system.{TessellationJointJobLayout, TessellationJointJobRequest, TessellationJointNativeCompletion}

/** Private opcode-15 GNQ4/5/6 command and GNZ2 completion words. */
object TessellationJointHostProtocol {
  val commandTag = BigInt("474a0100", 16)
  val rootAllocationBytes = 4096
  val completionMagic = BigInt("474e5a02", 16)
  val completionWords = 16
  val completionTailBase = 0x540
  val completionTailEnd = completionTailBase + (completionWords - NativeBinnedCompletionInfo.wordCount) * 4

  def completionAddress(word: Int): Int =
    if (word < NativeBinnedCompletionInfo.wordCount) NativeBinnedCompletionInfo.base + word * 4
    else completionTailBase + (word - NativeBinnedCompletionInfo.wordCount) * 4

  def tagged(command: GpuCommand): Bool = command.pattern(31, 3) === (commandTag >> 3).U

  def decode(command: GpuCommand, request: TessellationJointJobRequest, transformFeedback: Boolean): Unit = {
    val base = command.sourceAddress
    val valid = tagged(command) && command.bytes === TessellationJointJobLayout.jobBytes.U &&
      base(11, 0) === 0.U && !command.waitForDma && !command.waitForEvent && !command.signalEvent &&
      (if (transformFeedback) true.B else !command.rasterDiscard && !command.feedbackCapture)
    request.commandId := command.commandId
    request.domain := command.pattern(2, 1)
    request.view.base := base
    request.view.limit := Mux(valid, base.pad(33) + TessellationJointJobLayout.jobBytes.U, 0.U)
    request.view.allocationBase := base
    request.view.allocationLimit := base.pad(33) + rootAllocationBytes.U
    request.owner.base := command.destinationAddress
    request.owner.limit := Cat(command.pattern(0), command.widthBytes)
    request.identity.context := command.height
    request.identity.pass := command.sourceStride
    request.identity.generation := command.destinationStride
  }

  def completionDetails(report: TessellationJointNativeCompletion): Vec[UInt] = VecInit(Seq(
    completionMagic.U(32.W), Cat(0.U(25.W), report.clearOnly, report.faultStage, report.status),
    report.faultAddress, report.identity.context, report.identity.pass, report.identity.generation,
    report.capturedEntries, report.retiredRanges,
    report.retiredPatches(31, 0), report.retiredPatches(63, 32),
    report.published(31, 0), report.published(63, 32),
    report.storedBytes(31, 0), report.storedBytes(63, 32),
    report.resolvedBytes(31, 0), report.resolvedBytes(63, 32)))
}
