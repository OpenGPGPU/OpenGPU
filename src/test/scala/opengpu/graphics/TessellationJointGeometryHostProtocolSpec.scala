package opengpu.graphics

import chisel3._
import opengpu.command.GpuCommand
import opengpu.config.GpuConfig
import opengpu.system.{
  TessellationJointGeometryJobRequest,
  TessellationJointGeometryNativeCompletion
}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointGeometryHostProtocolSpec extends AnyFlatSpec {
  private val protocol = TessellationJointGeometryHostProtocol
  private val identity = protocol.Identity(BigInt("12345678", 16), BigInt("90abcdef", 16), 0x76543210)

  private def commandWords(
      opcode: BigInt = protocol.commandOpcode,
      pattern: BigInt = protocol.commandTag | (1 << 1),
      sourceAddress: BigInt = 0x1000,
      destinationAddress: BigInt = 0x2000,
      bytes: BigInt = protocol.rootBytes,
      widthBytes: BigInt = 0x4000,
      waitForDma: Boolean = false,
      waitForEvent: Boolean = false,
      signalEvent: Boolean = false,
      rasterDiscard: Boolean = false,
      feedbackCapture: Boolean = false
  ): protocol.CommandWords = protocol.CommandWords(
    commandId = 0x123,
    opcode = opcode,
    pattern = pattern,
    sourceAddress = sourceAddress,
    destinationAddress = destinationAddress,
    bytes = bytes,
    widthBytes = widthBytes,
    height = identity.context,
    sourceStride = identity.pass,
    destinationStride = identity.generation,
    waitForDma = waitForDma,
    waitForEvent = waitForEvent,
    signalEvent = signalEvent,
    rasterDiscard = rasterDiscard,
    feedbackCapture = feedbackCapture
  )

  private def completion(
      status: Int = 0,
      faultStage: Int = 0,
      clearOnly: Boolean = false,
      geometryReportValid: Boolean = true,
      faultAddress: BigInt = 0,
      actualGsPublished: BigInt = 0x158,
      received: BigInt = 0x161,
      dropped: BigInt = 3,
      admitted: BigInt = 0x161 - 3,
      geometryTasks: BigInt = 0x64,
      nextGsSequenceExclusive: BigInt = BigInt("1fffffffffffffff", 16),
      nextGsSequence: BigInt = BigInt("1fffffffffffffff", 16),
      nextBinSequence: BigInt = BigInt("1ffffffff", 16)
  ): protocol.Completion = protocol.Completion(
    status = status,
    faultStage = faultStage,
    clearOnly = clearOnly,
    geometryReportValid = geometryReportValid,
    faultAddress = faultAddress,
    identity = identity,
    capturedEntries = BigInt("ffffffff", 16),
    retiredRanges = BigInt("fffffffe", 16),
    retiredPatches = BigInt("ffffffffffffffff", 16),
    published = BigInt("fedcba9876543210", 16),
    storedBytes = BigInt("0123456789abcdef", 16),
    resolvedBytes = BigInt("8000000000000000", 16),
    actualGsPublished = actualGsPublished,
    received = received,
    dropped = dropped,
    admitted = admitted,
    geometryTasks = geometryTasks,
    nextGsSequenceExclusive = nextGsSequenceExclusive,
    nextGsSequence = nextGsSequence,
    nextBinSequence = nextBinSequence
  )

  private def boundaryCompletion: protocol.Completion = {
    val max158 = (BigInt(1) << 158) - 1
    val max161 = (BigInt(1) << 161) - 1
    val max97 = (BigInt(1) << 97) - 1
    completion(
      status = 3,
      faultStage = 6,
      clearOnly = true,
      faultAddress = BigInt("deadbeef", 16),
      actualGsPublished = max158,
      received = max161,
      dropped = max161 - max97,
      admitted = max97,
      geometryTasks = BigInt("ffffffffffffffff", 16),
      nextGsSequenceExclusive = BigInt(1) << 64,
      nextGsSequence = 0,
      nextBinSequence = (BigInt(1) << 33) - 1
    )
  }

  private def rejected(words: Seq[BigInt]): Unit =
    assert(protocol.completionDetails(words).isFailure)

  it should "freeze the new constants, legacy protocol and completion address map" in {
    assert(protocol.commandTag == BigInt("474a0200", 16))
    assert(protocol.completionMagic == BigInt("474e5a03", 16))
    assert(protocol.completionWords == 46)
    assert(TessellationJointHostProtocol.commandTag == BigInt("474a0100", 16))
    assert(TessellationJointHostProtocol.completionMagic == BigInt("474e5a02", 16))
    assert(TessellationJointHostProtocol.completionWords == 16)
    assert(TessellationJointHostProtocol.profileBase == 0x550)
    assert(TessellationJointHostProtocol.profileMagic == BigInt("474a5001", 16))
    assert((0 until 16).map(protocol.completionAddress) ==
      (0 until 16).map(TessellationJointHostProtocol.completionAddress))
    assert(protocol.completionAddress(16) == 0x560)
    assert(protocol.completionAddress(45) == 0x5d4)
    assert(protocol.completionGeometryEnd == 0x5d8)
    assert(protocol.completionReservedBase == 0x5d8 && protocol.completionReservedWords == 4)
    assert(protocol.completionReadable(0x5d7))
    assert(protocol.completionReadable(0x5e7))
    assert(!protocol.completionReadable(0x5e8))
    assert(!protocol.completionReadable(0x1b0))
    assert(!protocol.completionReadable(0x53c))
  }

  it should "decode exact tags, all three domains and the 4 GiB owner endpoint" in {
    for (domain <- 0 until 3) {
      val decoded = protocol.decode(commandWords(pattern = protocol.commandTag | (domain << 1))).get
      assert(decoded.domain == domain)
      assert(decoded.sourceAddress == 0x1000)
      assert(decoded.ownerBase == 0x2000)
      assert(decoded.ownerLimit == 0x4000)
      assert(decoded.identity == identity)
    }
    val end = protocol.decode(
      commandWords(
        pattern = protocol.commandTag | (2 << 1) | 1,
        sourceAddress = BigInt("fffff000", 16),
        destinationAddress = 0,
        widthBytes = 0
      )
    ).get
    assert(end.sourceAddress + protocol.rootAllocationBytes == BigInt(1) << 32)
    assert(end.ownerLimit == BigInt(1) << 32)
  }

  it should "reject wrong tags, domains, flags, bytes, alignment and owner ranges" in {
    val invalid = Seq(
      commandWords(opcode = 14),
      commandWords(pattern = protocol.commandTag | (1 << 3)),
      commandWords(pattern = protocol.commandTag | (3 << 1)),
      commandWords(bytes = 511),
      commandWords(bytes = 513),
      commandWords(sourceAddress = 0x1001),
      commandWords(destinationAddress = 0x2000, widthBytes = 0),
      commandWords(destinationAddress = 0x2000, widthBytes = 0x1000),
      commandWords(waitForDma = true),
      commandWords(waitForEvent = true),
      commandWords(signalEvent = true),
      commandWords(rasterDiscard = true),
      commandWords(feedbackCapture = true),
      commandWords().copy(commandId = 4096)
    )
    invalid.foreach(command => assert(protocol.decode(command).isFailure))
  }

  it should "adapt a unified command into the independent geometry request" in {
    simulate(new GeometryHostCommandHarness) { harness =>
      val command = harness.io.command
      command.poke(0.U.asTypeOf(command))
      harness.reset.poke(true.B); harness.clock.step(); harness.reset.poke(false.B)
      command.opcode.poke(15.U)
      command.pattern.poke((protocol.commandTag | (2 << 1) | 1).U)
      command.sourceAddress.poke("hfffff000".U)
      command.destinationAddress.poke(0.U)
      command.widthBytes.poke(0.U)
      command.bytes.poke(512.U)
      command.height.poke(identity.context.U)
      command.sourceStride.poke(identity.pass.U)
      command.destinationStride.poke(identity.generation.U)
      harness.clock.step()
      harness.io.valid.expect(true.B)
      harness.io.request.view.limit.expect("hfffff200".U)
      harness.io.request.view.allocationLimit.expect("h100000000".U)
      harness.io.request.owner.limit.expect("h100000000".U)
      harness.io.request.domain.expect(2.U)
      command.bytes.poke(511.U)
      harness.clock.step()
      harness.io.request.view.limit.expect(0.U)
      command.bytes.poke(512.U)
      command.sourceAddress.poke("hfffff001".U)
      harness.clock.step()
      harness.io.request.view.limit.expect(0.U)
      command.sourceAddress.poke("hfffff000".U)
      command.waitForDma.poke(true.B)
      harness.clock.step()
      harness.io.request.view.limit.expect(0.U)
    }
  }

  it should "round-trip all 46 GNZ3 words at 158, 161, 97 and 65-bit boundaries including 2^64" in {
    val report = boundaryCompletion
    val words = protocol.pack(report)
    assert(words.size == 46)
    assert(words.zipWithIndex.forall { case (word, _) => word >= 0 && word.bitLength <= 32 })
    assert(protocol.completionDetails(words).get == report)
    assert((words(20) & ~BigInt("3fffffff", 16)) == 0)
    assert((words(26) & ~BigInt("7fffffff", 16)) == 0)
    assert((words(32) & ~BigInt("7fffffff", 16)) == 0)
    assert((words(36) & ~BigInt("7fffffff", 16)) == 0)
    assert((words(41) & ~BigInt("7fffffff", 16)) == 0)
    assert((words(45) & ~BigInt("7fffffff", 16)) == 0)
    assert(words.slice(39, 42).zipWithIndex.map { case (word, index) =>
      word << (32 * index)
    }.reduce(_ | _) == BigInt(1) << 64)
    assert(words(42) == 0 && words(43) == 0)
    val max161 = (BigInt(1) << 161) - 1
    val max65 = (BigInt(1) << 65) - 1
    val max64 = (BigInt(1) << 64) - 1
    val upper = completion(
      actualGsPublished = 0,
      received = max161,
      dropped = max161,
      admitted = 0,
      nextGsSequenceExclusive = max65,
      nextGsSequence = max64,
      nextBinSequence = 0
    )
    assert(protocol.completionDetails(protocol.pack(upper)).get == upper)
  }

  it should "reject high-bit forgery while preserving low64 and reject low64 mismatches" in {
    val words = protocol.pack(boundaryCompletion)
    rejected(words.updated(1, words(1) | (BigInt(1) << 8)))
    rejected(words.updated(20, words(20) | (BigInt(1) << 30)))
    for (index <- Seq(26, 32, 36, 41, 45)) {
      rejected(words.updated(index, words(index) | (BigInt(1) << 31)))
    }
    val forged = words.updated(41, words(41) | (BigInt(1) << 31))
    assert(forged(42) == words(42) && forged(43) == words(43))
    rejected(words.updated(42, words(42) ^ 1))
    rejected(words.updated(0, words(0) ^ 1))
    rejected(words.dropRight(1))
    rejected(words :+ 0)
  }

  it should "reject identity, status, report-valid, cross-field and fault inconsistencies" in {
    val report = completion()
    val words = protocol.pack(report)
    assert(protocol.completionDetails(words).get == report)
    intercept[IllegalArgumentException] {
      protocol.validateCompletion(report, identity.copy(context = identity.context + 1), 0)
    }
    intercept[IllegalArgumentException] {
      protocol.validateCompletion(report, identity, 1)
    }
    intercept[IllegalArgumentException] {
      protocol.pack(report.copy(geometryReportValid = false))
    }
    rejected(words.updated(1, words(1) & ~BigInt(128)))
    intercept[IllegalArgumentException] {
      protocol.pack(report.copy(received = report.received + 1))
    }
    intercept[IllegalArgumentException] {
      protocol.pack(report.copy(status = 3, faultStage = 0, faultAddress = 1))
    }
    intercept[IllegalArgumentException] {
      protocol.pack(report.copy(status = 0, faultStage = 1, faultAddress = 1))
    }
    intercept[IllegalArgumentException] {
      protocol.pack(report.copy(status = 3, faultStage = 1, faultAddress = 0,
        nextGsSequenceExclusive = report.nextGsSequenceExclusive + 1))
    }
  }

  it should "pack a Chisel geometry completion with the same little-endian wide words" in {
    simulate(new GeometryHostCompletionHarness) { harness =>
      val report = boundaryCompletion
      val input = harness.io.report
      input.poke(0.U.asTypeOf(input))
      input.base.status.poke(report.status.U)
      input.base.faultStage.poke(report.faultStage.U)
      input.base.clearOnly.poke(report.clearOnly.B)
      input.base.faultAddress.poke(report.faultAddress.U)
      input.base.identity.context.poke(identity.context.U)
      input.base.identity.pass.poke(identity.pass.U)
      input.base.identity.generation.poke(identity.generation.U)
      input.base.capturedEntries.poke(report.capturedEntries.U)
      input.base.retiredRanges.poke(report.retiredRanges.U)
      input.base.retiredPatches.poke(report.retiredPatches.U)
      input.base.published.poke(report.published.U)
      input.base.storedBytes.poke(report.storedBytes.U)
      input.base.resolvedBytes.poke(report.resolvedBytes.U)
      input.geometryReportValid.poke(report.geometryReportValid.B)
      input.actualGsPublished.poke(report.actualGsPublished.U)
      input.received.poke(report.received.U)
      input.dropped.poke(report.dropped.U)
      input.admitted.poke(report.admitted.U)
      input.geometryTasks.poke(report.geometryTasks.U)
      input.nextGsSequenceExclusive.poke(report.nextGsSequenceExclusive.U)
      input.nextGsSequence.poke(report.nextGsSequence.U)
      input.nextBinSequence.poke(report.nextBinSequence.U)
      harness.io.words.zip(protocol.pack(report)).zipWithIndex.foreach { case ((actual, expected), index) =>
        actual.expect(expected.U, s"GNZ3 word$index")
      }
    }
  }

  it should "parse only the exact four-word GJP2 protected geometry profile" in {
    assert(protocol.profileMagic == BigInt("474a5002", 16))
    assert(protocol.profileBase == 0x600 && protocol.profileEnd == 0x610)
    assert(protocol.profileWordCount == 4)
    assert(protocol.profileGcr1PresenceBit == 23)
    assert(protocol.profileProvider == "two-cu-vga60-owner-v3-five-stage")
    assert(protocol.profileWords == Vector(
      BigInt("474a5002", 16),
      BigInt("00803dff", 16),
      BigInt("08100207", 16),
      BigInt("8ce58b2c", 16)
    ))
    val parsed = protocol.parseProfile(protocol.profileWords).get
    assert(parsed == protocol.targetProfile)
    assert(parsed.computeUnits == 2 && parsed.lanes == 16 && parsed.warps == 8)
    assert(parsed.engines == 4 && parsed.payloadBytes == 524288 && parsed.maxPayloadBytes == 69632)
    assert(parsed.payloadOwners == 5 && parsed.payloadPorts == 12)
    assert(parsed.imageProducerPins == 34 && parsed.singleDomainTotalPins == 37)
    assert((0 until 4).map(protocol.profileAddress) == Seq(0x600, 0x604, 0x608, 0x60c))
    assert(protocol.parseProfile(protocol.profileWords.dropRight(1)).isFailure)
    assert(protocol.parseProfile(protocol.profileWords.updated(0, protocol.profileMagic - 1)).isFailure)
    assert(protocol.parseProfile(protocol.profileWords.updated(1, protocol.profileWords(1) & ~BigInt(1 << 23))).isFailure)
    assert(protocol.parseProfile(protocol.profileWords.updated(2, protocol.profileWords(2) ^ 1)).isFailure)
    assert(protocol.parseProfile(protocol.profileWords.updated(3, protocol.profileWords(3) ^ 1)).isFailure)
  }
}

class GeometryHostCommandHarness extends Module {
  val io = IO(new Bundle {
    val command = Flipped(new GpuCommand(GpuConfig(lanes = 4, warps = 2), 8))
    val request = Output(new TessellationJointGeometryJobRequest(8))
    val valid = Output(Bool())
  })
  TessellationJointGeometryHostProtocol.decode(io.command, io.request)
  io.valid := TessellationJointGeometryHostProtocol.commandValid(io.command)
}

class GeometryHostCompletionHarness extends Module {
  val io = IO(new Bundle {
    val report = Input(new TessellationJointGeometryNativeCompletion(8))
    val words = Output(Vec(TessellationJointGeometryHostProtocol.completionWords, UInt(32.W)))
  })
  io.words := TessellationJointGeometryHostProtocol.completionDetails(io.report)
}
