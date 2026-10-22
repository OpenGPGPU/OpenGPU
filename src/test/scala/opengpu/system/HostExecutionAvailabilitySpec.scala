package opengpu.system

import chisel3._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import opengpu.dispatch.KernelCommandStatus
import opengpu.graphics.{GpuCapabilities, GpuCommandMmioRegs => R, GraphicsConfig, RenderHostRegs}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** The AXI product wrapper has no service for an external scalar-FP handoff. */
class HostExecutionAvailabilitySpec extends AnyFlatSpec with GpuHostTestSupport {
  private val cease = BigInt("30500073", 16)
  private val scalarRead = (BigInt(1) << 25) | (BigInt(10) << 20) | (1 << 15) | (5 << 12) | (10 << 7) | 0x57
  private val scalarWrite = (BigInt(0x10) << 26) | (BigInt(1) << 25) | (BigInt(10) << 20) | (1 << 12) | (10 << 7) | 0x57
  private val unavailable = Seq(
    "scalar-add" -> BigInt("00000553", 16),
    "scalar-load" -> BigInt("0000a507", 16),
    "scalar-store" -> BigInt("00a0a027", 16),
    "scalar-classify" -> BigInt("e0001553", 16),
    "vector-scalar-read" -> scalarRead,
    "vector-scalar-write" -> scalarWrite)
  private val profiles = Seq(
    ("absent-small", false, true, false, false, false),
    ("F-disabled-shared", true, false, true, true, false),
    ("serviced-shared", true, true, true, true, false),
    ("absent-default", false, true, false, false, true))

  for ((name, backend, enabled, shared, buffered, full) <- profiles) {
    it should s"retire actual AXI host FP availability and healthy reuse $name" in {
      val gpu = if (full) GpuConfig() else GpuConfig(lanes = 4, warps = 2, enableFpu = enabled, l2Sets = 8, l2Ways = 2)
      val capacity = gpu.lanes * gpu.warps
      val cus = if (full) 4 else 2
      val supported = backend && enabled
      simulate(new GpuHostSystemAxi(GraphicsConfig(screenWidth = 16, screenHeight = 16), gpu,
        numComputeUnits = cus, enableFpuBackend = backend, fragCore = shared, sharedGraphics = shared,
        bufferCommandReads = buffered, memoryAxiDataBytes = 32,
        instructionCacheSets = if (full) 64 else 4, instructionCacheWays = if (full) 2 else 1,
        vectorCacheSets = if (full) 64 else 4, vectorCacheWays = if (full) 2 else 1)) { d =>
        initialize(d); axiWrite(d, RenderHostRegs.IRQ, 1)
        assert((axiRead(d, RenderHostRegs.IRQ) & 1) == 1)
        val caps = axiRead(d, RenderHostRegs.CAPABILITIES)
        assert(caps.testBit(GpuCapabilities.ComputeScalarFpu) == supported)
        assert(axiRead(d, 0x36c) == cus && axiRead(d, 0x370) == gpu.lanes &&
          axiRead(d, 0x374) == gpu.warps && axiRead(d, 0x378) == capacity && axiRead(d, 0x394) == 32)

        val bytes = mutable.Map.empty[BigInt, Int]
        def put(at: BigInt, value: BigInt): Unit = for (i <- 0 until 4) bytes(at + i) = ((value >> (8 * i)) & 255).toInt
        def program(at: Int, words: Seq[BigInt]): Unit = words.zipWithIndex.foreach { case (v, i) => put(at + 4 * i, v) }
        def readWord(at: BigInt): BigInt = (0 until 4).foldLeft(BigInt(0))((v, i) => v | (BigInt(bytes(at + i)) << (8 * i)))
        var allowedReads = Set.empty[BigInt]
        var allowedWrites = Set.empty[BigInt]
        val readAddresses = mutable.ArrayBuffer.empty[BigInt]
        val writeAddresses = mutable.ArrayBuffer.empty[BigInt]
        def readLine(at: BigInt): BigInt = {
          val base = at & ~BigInt(63)
          assert(allowedReads(base), s"$name unexpected physical data read at $at; allowed=$allowedReads")
          readAddresses += base
          (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(bytes.getOrElse(base + i, 0)) << (8 * i)))
        }
        def write(at: BigInt, value: BigInt, mask: BigInt): Unit = {
          for (i <- 0 until 64 if mask.testBit(i)) {
            assert(allowedWrites(at + i), s"$name unowned write at ${at + i}")
            bytes(at + i) = ((value >> (8 * i)) & 255).toInt; writeAddresses += at + i
          }
        }
        val vectorSetup = (BigInt("c1027057", 16) & ~(BigInt(31) << 15)) | (BigInt(gpu.lanes) << 15)
        val moveOne = (BigInt(0x17) << 26) | (BigInt(1) << 25) | (1 << 15) | (3 << 12) | (10 << 7) | 0x57
        val convert = (BigInt(0x12) << 26) | (BigInt(1) << 25) | (BigInt(10) << 20) | (3 << 15) | (1 << 12) | (10 << 7) | 0x57
        val addVector = (BigInt(1) << 25) | (BigInt(10) << 20) | (10 << 15) | (1 << 12) | (10 << 7) | 0x57
        val store = Seq(BigInt("40008293", 16), BigInt("00241313", 16), BigInt("006282b3", 16),
          (BigInt(1) << 25) | (5 << 15) | (6 << 12) | (10 << 7) | 0x27, cease)
        val healthy = Seq(vectorSetup, moveOne, convert, addVector) ++ store
        val bridge = Seq(BigInt("0000a087", 16), vectorSetup, moveOne, convert, scalarRead) ++ store
        var commands = 0
        def submit(pc: Int, kernarg: Int): Unit = {
          // Reuse the same public ID for every fault and healthy successor.
          for ((at, value) <- Seq(R.COMMAND_ID -> 7, R.OPCODE -> GpuCommandOpcode.kernel.litValue.toInt,
            R.KERNEL_PC -> pc, R.KERNARG -> kernarg, R.FLAGS -> 0,
            R.WAIT_EVENT -> 0, R.SIGNAL_EVENT -> 0,
            R.GRID_X -> 1, R.GRID_Y -> 1, R.GRID_Z -> 1,
            R.LOCAL_X -> capacity, R.LOCAL_Y -> 1, R.LOCAL_Z -> 1)) axiWrite(d, at, value)
          axiWrite(d, R.SUBMIT, 1); commands += 1
        }
        def service(): Unit = serviceMemoryMaster(d, readLine, writeAckDelay = 19,
          readResponseDelay = 11, maxCycles = if (full) 20000 else 6000)(write) { d.io.m_irq.peek().litToBoolean }
        def completion(success: Boolean): Unit = {
          val value = axiRead(d, R.COMPLETION)
          val opcode = ((value >> 8) & 7) | (((value >> 16) & 1) << 3)
          assert((value & 255) == 7 && opcode == GpuCommandOpcode.kernel.litValue)
          assert(((value >> 11) & 15) == (if (success) BigInt(0) else KernelCommandStatus.executionFailed.litValue), s"$name completion=$value")
          assert(value.testBit(15) == success)
          for (_ <- 0 until 5) assert(axiRead(d, R.COMPLETION) == value)
          axiWrite(d, R.COMPLETION_POP, 1); axiWrite(d, RenderHostRegs.IRQ, 3)
        }

        val cases = if (supported) Seq("enabled-bridge" -> BigInt(0)) else unavailable
        var healthyValues = 0
        for (((family, word), index) <- cases.zipWithIndex) {
          val pc = 0x1000 + index * 0x200
          val healthyPc = pc + 0x80
          val kernarg = 0x8000 + index * 0x1000
          val output = kernarg + 1024
          put(kernarg, BigInt("3f800000", 16))
          for (at <- output - 16 until output + capacity * 4 + 16) bytes(BigInt(at)) = 0xa5
          val outputBytes = (0 until capacity * 4).map(i => BigInt(output + i)).toSet
          val outputLines = outputBytes.map(_ & ~BigInt(63))
          val oldReads = readAddresses.size; val oldWrites = writeAddresses.size
          if (!supported) {
            program(pc, Seq(word, cease)); allowedReads = Set(BigInt(pc)); allowedWrites = Set.empty
            submit(pc, kernarg); service(); completion(false)
            assert(writeAddresses.size == oldWrites && readAddresses.drop(oldReads).forall(_ == pc), s"$name unavailable $family performed a data access")
          }
          program(healthyPc, if (supported) bridge else healthy)
          allowedReads = Set(BigInt(healthyPc)) ++ outputLines ++ (if (supported) Set(BigInt(kernarg)) else Set.empty[BigInt])
          allowedWrites = outputBytes
          submit(healthyPc, kernarg); service(); completion(true)
          assert(writeAddresses.drop(oldWrites).toSet == outputBytes)
          for (i <- 0 until capacity) assert(readWord(output + 4 * i) == BigInt("40000000", 16), s"$name healthy $family lane=$i")
          for (at <- (output - 16 until output) ++ (output + capacity * 4 until output + capacity * 4 + 16)) assert(bytes(BigInt(at)) == 0xa5)
          if (supported) assert(readAddresses.drop(oldReads).contains(BigInt(kernarg)))
          healthyValues += capacity
        }
        assert(commands == (if (supported) 1 else 12))
        info(s"HOST_EXECUTION_AVAILABILITY profile=$name backend=$backend F=$enabled sharedGraphics=$shared buffered=$buffered CUs=$cus lanes=${gpu.lanes} warps=${gpu.warps} threads=$capacity commands=$commands families=${cases.size} exactHealthyValues=$healthyValues AXI256=true delayedR=true delayedB=true heldCompletion=true samePublicIDReuse=true noUnavailableDataAccess=true outputGuards=true nativeQuotasAbsent=true")
      }
    }
  }
}
