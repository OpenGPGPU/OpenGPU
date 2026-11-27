package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.{GpuCommand, GpuCommandOpcode}
import opengpu.system.{
  TessellationJointGeometryJobRequest,
  TessellationJointGeometryNativeCompletion
}
import scala.util.Try

object TessellationJointGeometryHostProtocol {
  val commandTag = BigInt("474a0200", 16)
  val commandOpcode = 15
  val rootBytes = 512
  val rootAllocationBytes = 4096
  val sourceAlignmentBytes = 4096
  val domainCount = 3
  val ownerLimitBits = 33
  val ownerLimitMaximum = BigInt(1) << 32
  val commandPatternTagShift = 3
  val commandPatternTagBits = 29
  val commandPatternDomainShift = 1
  val commandPatternDomainBits = 2
  val commandPatternOwnerLimitHighShift = 0

  val completionMagic = BigInt("474e5a03", 16)
  val completionWords = 46
  val completionPrefixBase = NativeBinnedCompletionInfo.base
  val completionPrefixWords = 16
  val completionTailBase = 0x540
  val completionGeometryBase = 0x560
  val completionGeometryWords = 30
  val completionGeometryEnd = 0x5d8
  val completionReservedBase = 0x5d8
  val completionReservedWords = 4
  val completionTailEnd = 0x5e8

  val profileMagic = BigInt("474a5002", 16)
  val profileBase = 0x600
  val profileEnd = 0x610
  val profileWordCount = 4
  val profileGcr1PresenceBit = 23
  val profileOwnedVertexSource = 1 << 0
  val profilePatchZeroInitialized = 1 << 1
  val profileOwnedPatchReads = 1 << 2
  val profileOwnedTessellationInfo = 1 << 3
  val profileGeometryInputs = 1 << 4
  val profileGeometryRoute = 1 << 5
  val profileJointDirectoryRoute = 1 << 6
  val profileWholeOwnerRetirement = 1 << 7
  val profileScalarFpu = 1 << 8
  val profileAdvancedBlend = 1 << 9
  val profileSampleInputs = 1 << 10
  val profileGeometryPixelMask = 1 << 11
  val profileGeometryUniforms = 1 << 12
  val profileGeometryTransformFeedback = 1 << 13
  val profileGcr1Presence = 1 << profileGcr1PresenceBit
  val profileFeatureFlags = profileOwnedVertexSource | profilePatchZeroInitialized |
    profileOwnedPatchReads | profileOwnedTessellationInfo | profileGeometryInputs |
    profileGeometryRoute | profileJointDirectoryRoute | profileWholeOwnerRetirement |
    profileScalarFpu | profileSampleInputs | profileGeometryPixelMask | profileGeometryUniforms |
    profileGeometryTransformFeedback
  val profileFlags = BigInt(profileFeatureFlags | profileGcr1Presence)
  val profileProvider = "two-cu-vga60-owner-v3-five-stage"
  val profileShapeWord = BigInt("08100207", 16)
  val profileCapacityWord = BigInt("8ce58b2c", 16)

  final case class Identity(context: BigInt, pass: BigInt, generation: BigInt)
  final case class CommandWords(
      commandId: BigInt,
      opcode: BigInt,
      pattern: BigInt,
      sourceAddress: BigInt,
      destinationAddress: BigInt,
      bytes: BigInt,
      widthBytes: BigInt,
      height: BigInt,
      sourceStride: BigInt,
      destinationStride: BigInt,
      waitForDma: Boolean,
      waitForEvent: Boolean,
      signalEvent: Boolean,
      rasterDiscard: Boolean,
      feedbackCapture: Boolean
  )
  final case class Command(
      commandId: BigInt,
      domain: Int,
      sourceAddress: BigInt,
      ownerBase: BigInt,
      ownerLimit: BigInt,
      identity: Identity
  )
  final case class Completion(
      status: Int,
      faultStage: Int,
      clearOnly: Boolean,
      geometryReportValid: Boolean,
      faultAddress: BigInt,
      identity: Identity,
      capturedEntries: BigInt,
      retiredRanges: BigInt,
      retiredPatches: BigInt,
      published: BigInt,
      storedBytes: BigInt,
      resolvedBytes: BigInt,
      actualGsPublished: BigInt,
      received: BigInt,
      dropped: BigInt,
      admitted: BigInt,
      geometryTasks: BigInt,
      nextGsSequenceExclusive: BigInt,
      nextGsSequence: BigInt,
      nextBinSequence: BigInt
  )
  final case class Profile(
      flags: BigInt,
      domainsMask: Int,
      computeUnits: Int,
      lanes: Int,
      warps: Int,
      engines: Int,
      payloadBytes: BigInt,
      maxPayloadBytes: BigInt,
      payloadOwners: Int,
      payloadPorts: Int,
      imageProducerPins: Int,
      singleDomainTotalPins: Int
  ) {
    require(flags >= 0 && flags.bitLength <= 14)
    require(domainsMask >= 0 && domainsMask < 256)
    require(computeUnits >= 0 && computeUnits < 256)
    require(lanes >= 0 && lanes < 256)
    require(warps >= 0 && warps < 256)
    require(engines >= 0 && engines < 8)
    require(payloadBytes > 0 && (payloadBytes & (payloadBytes - 1)) == 0)
    require(payloadBytes.bitLength - 1 < 32)
    require(maxPayloadBytes > 0 && maxPayloadBytes % 4096 == 0)
    require(maxPayloadBytes / 4096 < 32)
    require(payloadOwners >= 0 && payloadOwners < 8)
    require(payloadPorts >= 0 && payloadPorts < 16)
    require(imageProducerPins >= 0 && imageProducerPins < 64)
    require(singleDomainTotalPins >= 0 && singleDomainTotalPins < 64)

    def words: Vector[BigInt] = {
      val shape = (BigInt(warps) << 24) | (BigInt(lanes) << 16) |
        (BigInt(computeUnits) << 8) | BigInt(domainsMask)
      val capacity = BigInt(engines) | (BigInt(payloadOwners) << 3) |
        (BigInt(payloadPorts) << 6) | (BigInt(imageProducerPins) << 10) |
        (BigInt(singleDomainTotalPins) << 16) |
        (BigInt(payloadBytes.bitLength - 1) << 22) | ((maxPayloadBytes / 4096) << 27)
      Vector(
        profileMagic,
        flags | profileGcr1Presence,
        shape,
        capacity
      )
    }
  }

  val targetProfile = Profile(
    flags = BigInt(profileFeatureFlags),
    domainsMask = 7,
    computeUnits = 2,
    lanes = 16,
    warps = 8,
    engines = 4,
    payloadBytes = BigInt(524288),
    maxPayloadBytes = BigInt(69632),
    payloadOwners = 5,
    payloadPorts = 12,
    imageProducerPins = 34,
    singleDomainTotalPins = 37
  )
  val profileWords = targetProfile.words

  private def unsigned(value: BigInt, bits: Int, name: String): Unit =
    require(value >= 0 && value.bitLength <= bits, s"$name is out of range")

  private def wideWords(value: BigInt, bits: Int, words: Int, name: String): Vector[BigInt] = {
    unsigned(value, bits, name)
    require(words * 32 >= bits && (words - 1) * 32 < bits, s"$name has an invalid word count")
    Vector.tabulate(words)(index => (value >> (32 * index)) & BigInt("ffffffff", 16))
  }

  def decode(command: CommandWords): Try[Command] = Try {
    unsigned(command.commandId, 12, "commandId")
    unsigned(command.opcode, 4, "opcode")
    unsigned(command.pattern, 32, "pattern")
    unsigned(command.sourceAddress, 32, "sourceAddress")
    unsigned(command.destinationAddress, 32, "destinationAddress")
    unsigned(command.bytes, 32, "bytes")
    unsigned(command.widthBytes, 32, "widthBytes")
    unsigned(command.height, 32, "height")
    unsigned(command.sourceStride, 32, "sourceStride")
    unsigned(command.destinationStride, 32, "destinationStride")
    require(command.opcode == commandOpcode, "wrong geometry host opcode")
    require(
      (command.pattern >> commandPatternTagShift) == (commandTag >> commandPatternTagShift),
      "wrong geometry host tag"
    )
    val domain = ((command.pattern >> commandPatternDomainShift) & 3).toInt
    require(domain < domainCount, "invalid geometry host domain")
    require(command.bytes == rootBytes, "wrong geometry root bytes")
    require(
      (command.sourceAddress & BigInt(sourceAlignmentBytes - 1)) == 0,
      "unaligned geometry root"
    )
    require(
      command.sourceAddress + rootAllocationBytes <= ownerLimitMaximum,
      "geometry root allocation exceeds 4 GiB"
    )
    val ownerLimit = ((command.pattern & 1) << 32) | command.widthBytes
    require(ownerLimit > command.destinationAddress, "empty or reversed owner range")
    require(ownerLimit <= ownerLimitMaximum, "owner limit exceeds 4 GiB")
    require(
      !command.waitForDma && !command.waitForEvent && !command.signalEvent &&
        !command.rasterDiscard && !command.feedbackCapture,
      "unsupported geometry host flags"
    )
    Command(
      commandId = command.commandId,
      domain = domain,
      sourceAddress = command.sourceAddress,
      ownerBase = command.destinationAddress,
      ownerLimit = ownerLimit,
      identity = Identity(command.height, command.sourceStride, command.destinationStride)
    )
  }

  def tagged(command: GpuCommand): Bool =
    command.pattern(31, commandPatternTagShift) === (commandTag >> commandPatternTagShift).U

  def commandValid(command: GpuCommand): Bool = {
    val ownerLimit = Cat(command.pattern(commandPatternOwnerLimitHighShift), command.widthBytes)
    command.opcode === GpuCommandOpcode.tessellationJob && tagged(command) &&
      command.pattern(2, 1) =/= domainCount.U && command.bytes === rootBytes.U &&
      command.sourceAddress(11, 0) === 0.U &&
      command.sourceAddress.pad(33) <= (ownerLimitMaximum - rootAllocationBytes).U(33.W) &&
      ownerLimit > command.destinationAddress.pad(33) &&
      ownerLimit <= ownerLimitMaximum.U(33.W) && !command.waitForDma && !command.waitForEvent &&
      !command.signalEvent && !command.rasterDiscard && !command.feedbackCapture
  }

  def decode(command: GpuCommand, request: TessellationJointGeometryJobRequest): Unit = {
    val valid = commandValid(command)
    request.commandId := command.commandId
    request.domain := command.pattern(2, 1)
    request.view.base := command.sourceAddress
    request.view.limit := Mux(valid, command.sourceAddress +& rootBytes.U, 0.U)
    request.view.allocationBase := command.sourceAddress
    request.view.allocationLimit := command.sourceAddress +& rootAllocationBytes.U
    request.owner.base := command.destinationAddress
    request.owner.limit := Cat(command.pattern(0), command.widthBytes)
    request.identity.context := command.height
    request.identity.pass := command.sourceStride
    request.identity.generation := command.destinationStride
  }

  def validateCompletion(completion: Completion): Unit = {
    require(completion.status >= 0 && completion.status < 8, "invalid completion status")
    require(completion.faultStage >= 0 && completion.faultStage < 8, "invalid fault stage")
    unsigned(completion.faultAddress, 32, "faultAddress")
    unsigned(completion.identity.context, 32, "context")
    unsigned(completion.identity.pass, 32, "pass")
    unsigned(completion.identity.generation, 32, "generation")
    unsigned(completion.capturedEntries, 32, "capturedEntries")
    unsigned(completion.retiredRanges, 32, "retiredRanges")
    unsigned(completion.retiredPatches, 64, "retiredPatches")
    unsigned(completion.published, 64, "published")
    unsigned(completion.storedBytes, 64, "storedBytes")
    unsigned(completion.resolvedBytes, 64, "resolvedBytes")
    unsigned(completion.actualGsPublished, 158, "actualGsPublished")
    unsigned(completion.received, 161, "received")
    unsigned(completion.dropped, 161, "dropped")
    unsigned(completion.admitted, 97, "admitted")
    unsigned(completion.geometryTasks, 64, "geometryTasks")
    unsigned(completion.nextGsSequenceExclusive, 65, "nextGsSequenceExclusive")
    unsigned(completion.nextGsSequence, 64, "nextGsSequence")
    unsigned(completion.nextBinSequence, 33, "nextBinSequence")
    require(
      completion.nextGsSequence == (completion.nextGsSequenceExclusive & BigInt("ffffffffffffffff", 16)),
      "GS sequence low64 mismatch"
    )
    require(
      completion.received == completion.admitted + completion.dropped,
      "received does not equal admitted plus dropped"
    )
    if (!completion.geometryReportValid) {
      require(
        completion.actualGsPublished == 0 && completion.received == 0 && completion.dropped == 0 &&
          completion.admitted == 0 && completion.geometryTasks == 0 &&
          completion.nextGsSequenceExclusive == 0 && completion.nextGsSequence == 0 &&
          completion.nextBinSequence == 0,
        "invalid geometry report has nonzero geometry details"
      )
    }
    if (completion.status == 0) {
      require(completion.faultStage == 0 && completion.faultAddress == 0, "success retains a fault")
    }
    if (completion.faultStage == 0) {
      require(completion.faultAddress == 0, "fault address has no stage")
    } else {
      require(completion.status != 0, "fault stage has success status")
    }
  }

  def validateCompletion(completion: Completion, expectedIdentity: Identity, expectedStatus: Int): Unit = {
    require(expectedStatus >= 0 && expectedStatus < 8, "invalid expected status")
    validateCompletion(completion)
    require(completion.identity == expectedIdentity, "completion identity mismatch")
    require(completion.status == expectedStatus, "completion status mismatch")
  }

  def pack(completion: Completion): Vector[BigInt] = {
    validateCompletion(completion)
    val flags = BigInt(completion.status) | (BigInt(completion.faultStage) << 3) |
      (if (completion.clearOnly) BigInt(1) << 6 else BigInt(0)) |
      (if (completion.geometryReportValid) BigInt(1) << 7 else BigInt(0))
    Vector(
      completionMagic,
      flags,
      completion.faultAddress,
      completion.identity.context,
      completion.identity.pass,
      completion.identity.generation,
      completion.capturedEntries,
      completion.retiredRanges
    ) ++ wideWords(completion.retiredPatches, 64, 2, "retiredPatches") ++
      wideWords(completion.published, 64, 2, "published") ++
      wideWords(completion.storedBytes, 64, 2, "storedBytes") ++
      wideWords(completion.resolvedBytes, 64, 2, "resolvedBytes") ++
      wideWords(completion.actualGsPublished, 158, 5, "actualGsPublished") ++
      wideWords(completion.received, 161, 6, "received") ++
      wideWords(completion.dropped, 161, 6, "dropped") ++
      wideWords(completion.admitted, 97, 4, "admitted") ++
      wideWords(completion.geometryTasks, 64, 2, "geometryTasks") ++
      wideWords(completion.nextGsSequenceExclusive, 65, 3, "nextGsSequenceExclusive") ++
      wideWords(completion.nextGsSequence, 64, 2, "nextGsSequence") ++
      wideWords(completion.nextBinSequence, 33, 2, "nextBinSequence")
  }

  def completionDetails(words: Seq[BigInt]): Try[Completion] = Try {
    require(words.size == completionWords, "wrong GNZ3 word count")
    val w = words.toVector
    w.foreach(value => unsigned(value, 32, "completion word"))
    require(w(0) == completionMagic, "wrong GNZ3 magic")
    require((w(1) & ~BigInt(255)) == 0, "nonzero GNZ3 flags high bits")
    require((w(20) & ~BigInt("3fffffff", 16)) == 0, "nonzero actualGsPublished high bits")
    for (index <- Seq(26, 32, 36, 41, 45)) {
      require((w(index) & ~BigInt("7fffffff", 16)) == 0, s"nonzero GNZ3 word$index high bits")
    }
    def wide(word: Int, count: Int): BigInt =
      w.slice(word, word + count).zipWithIndex.map { case (value, index) =>
        value << (32 * index)
      }.reduce(_ | _)
    val flags = w(1)
    val completion = Completion(
      status = (flags & 7).toInt,
      faultStage = ((flags >> 3) & 7).toInt,
      clearOnly = ((flags >> 6) & 1) != 0,
      geometryReportValid = ((flags >> 7) & 1) != 0,
      faultAddress = w(2),
      identity = Identity(w(3), w(4), w(5)),
      capturedEntries = w(6),
      retiredRanges = w(7),
      retiredPatches = wide(8, 2),
      published = wide(10, 2),
      storedBytes = wide(12, 2),
      resolvedBytes = wide(14, 2),
      actualGsPublished = wide(16, 5),
      received = wide(21, 6),
      dropped = wide(27, 6),
      admitted = wide(33, 4),
      geometryTasks = wide(37, 2),
      nextGsSequenceExclusive = wide(39, 3),
      nextGsSequence = wide(42, 2),
      nextBinSequence = wide(44, 2)
    )
    validateCompletion(completion)
    completion
  }

  private def wideDetails(value: UInt): Seq[UInt] = Seq(value(31, 0), value(63, 32))

  def completionDetails(report: TessellationJointGeometryNativeCompletion): Vec[UInt] = {
    val base = report.base
    VecInit(
      Seq(
        completionMagic.U(32.W),
        Cat(0.U(24.W), report.geometryReportValid, base.clearOnly, base.faultStage, base.status),
        base.faultAddress,
        base.identity.context,
        base.identity.pass,
        base.identity.generation,
        base.capturedEntries,
        base.retiredRanges
      ) ++ wideDetails(base.retiredPatches) ++ wideDetails(base.published) ++
        wideDetails(base.storedBytes) ++ wideDetails(base.resolvedBytes) ++ Seq(
          report.actualGsPublished(31, 0),
          report.actualGsPublished(63, 32),
          report.actualGsPublished(95, 64),
          report.actualGsPublished(127, 96),
          Cat(0.U(2.W), report.actualGsPublished(157, 128)),
          report.received(31, 0),
          report.received(63, 32),
          report.received(95, 64),
          report.received(127, 96),
          report.received(159, 128),
          Cat(0.U(31.W), report.received(160, 160)),
          report.dropped(31, 0),
          report.dropped(63, 32),
          report.dropped(95, 64),
          report.dropped(127, 96),
          report.dropped(159, 128),
          Cat(0.U(31.W), report.dropped(160, 160)),
          report.admitted(31, 0),
          report.admitted(63, 32),
          report.admitted(95, 64),
          Cat(0.U(31.W), report.admitted(96, 96)),
          report.geometryTasks(31, 0),
          report.geometryTasks(63, 32),
          report.nextGsSequenceExclusive(31, 0),
          report.nextGsSequenceExclusive(63, 32),
          Cat(0.U(31.W), report.nextGsSequenceExclusive(64, 64)),
          report.nextGsSequence(31, 0),
          report.nextGsSequence(63, 32),
          report.nextBinSequence(31, 0),
          Cat(0.U(31.W), report.nextBinSequence(32, 32))
        )
    )
  }

  def completionAddress(word: Int): Int = {
    require(word >= 0 && word < completionWords, "invalid GNZ3 word")
    if (word < NativeBinnedCompletionInfo.wordCount) completionPrefixBase + word * 4
    else if (word < completionPrefixWords) completionTailBase + (word - NativeBinnedCompletionInfo.wordCount) * 4
    else completionGeometryBase + (word - completionPrefixWords) * 4
  }

  def completionReadable(address: Int): Boolean =
    (address >= completionPrefixBase &&
      address < completionPrefixBase + NativeBinnedCompletionInfo.wordCount * 4) ||
      (address >= completionTailBase && address < completionTailEnd)

  def parseProfile(words: Seq[BigInt]): Try[Profile] = Try {
    require(words.size == profileWordCount, "wrong GJP2 word count")
    val w = words.toVector
    w.foreach(value => unsigned(value, 32, "GJP2 word"))
    require(w == profileWords, "unsupported GJP2 profile")
    targetProfile
  }

  def profileAddress(word: Int): Int = {
    require(word >= 0 && word < profileWordCount, "invalid GJP2 word")
    profileBase + word * 4
  }
}
