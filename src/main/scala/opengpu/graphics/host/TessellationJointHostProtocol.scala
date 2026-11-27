package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.GpuCommand
import opengpu.config.GpuConfig
import opengpu.system.{TessellationJointJobLayout, TessellationJointJobRequest, TessellationJointNativeCompletion}

/** Private opcode-15 GNQ4/5/6 command and GNZ2 completion words. */
object TessellationJointHostProtocol {
  val commandTag = BigInt("474a0100", 16)
  val rootAllocationBytes = 4096
  val completionMagic = BigInt("474e5a02", 16)
  val completionWords = 16
  val completionTailBase = 0x540
  val completionTailEnd = completionTailBase + (completionWords - NativeBinnedCompletionInfo.wordCount) * 4
  val profileBase = 0x550
  val profileMagic = BigInt("474a5001", 16)
  val profileWordCount = 4
  val profileEnd = profileBase + profileWordCount * 4

  final case class Profile(
      engines: Int,
      scalarFpu: Boolean,
      advancedBlend: Boolean,
      sampleInputs: Boolean,
      feedback: Boolean
  ) {
    require(engines >= 1 && engines <= 4)
    def words: Seq[BigInt] = Seq(
      profileMagic,
      0x7f | (if (scalarFpu) 1 << 7 else 0) | (if (advancedBlend) 1 << 8 else 0) |
        (if (sampleInputs) 1 << 9 else 0) | (if (feedback) 1 << 10 else 0),
      (8 << 24) | (16 << 16) | (2 << 8) | 7,
      engines
    )
  }

  def profileFor(
      graphics: GraphicsConfig,
      gpu: GpuConfig,
      computeUnits: Int,
      native: NativeBinnedGraphicsConfig,
      scalarFpu: Boolean,
      sampleInputs: Boolean,
      feedback: Boolean
  ): Option[Profile] =
    Option.when(
      computeUnits == 2 && gpu.lanes == 16 && gpu.warps == 8 &&
        gpu.sharedMemoryBase == 0x10000000L && gpu.sharedMemoryBytes == 16384 &&
        native.vertex.input.attributes == 16 && native.vertex.input.bindings == 16 &&
        native.vertex.varyings == 16 && graphics.maxSampleCount == 4
    ) {
      Profile(native.engines, scalarFpu, native.storage.nativeAdvancedDraws, sampleInputs, feedback)
    }

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

  def completionDetails(report: TessellationJointNativeCompletion): Vec[UInt] = VecInit(
    Seq(
      completionMagic.U(32.W),
      Cat(0.U(25.W), report.clearOnly, report.faultStage, report.status),
      report.faultAddress,
      report.identity.context,
      report.identity.pass,
      report.identity.generation,
      report.capturedEntries,
      report.retiredRanges,
      report.retiredPatches(31, 0),
      report.retiredPatches(63, 32),
      report.published(31, 0),
      report.published(63, 32),
      report.storedBytes(31, 0),
      report.storedBytes(63, 32),
      report.resolvedBytes(31, 0),
      report.resolvedBytes(63, 32)
    )
  )
}
