package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.command.GpuCommandOpcode
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.system.GpuSystem
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class SharedProductSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("SharedProductSramModels.sv",
    "module SharedProductSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(w => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** The real four-CU product system, its unified command path and shared L2. */
private class SharedProductHarness extends Module {
  private val c = GpuConfig()
  require(c.lanes == 16 && c.warps == 8 && c.simtStackDepth == 8 &&
    c.sharedMemoryBytes == 16384 && c.sharedMemoryBanks == 16 &&
    c.sharedMemoryBase == 0x10000000L && c.enableFpu && c.enableVector)
  val system = Module(new GpuSystem(c, numComputeUnits = 4, commandIdWidth = 4,
    transactionsPerCu = 16, useBlackBoxes = true, enableFpuBackend = true,
    enableUnifiedCommands = true, enableIndirectCompute = true,
    nativeExecutionProfile = true, storageAvailable = true,
    computeUniformAvailable = true))
  val io = IO(new Bundle {
    val gpu = chiselTypeOf(system.io)
    val modelsPresent = Output(Bool())
  })
  io.gpu <> system.io
  io.modelsPresent := Module(new SharedProductSramModels).io.present
}

/** Opt-in product-path qualification of the CPU-qualified shared programs. */
@DoNotDiscover
class SharedProductFourCuQualification extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private val mask32 = (BigInt(1) << 32) - 1
  private def expected(seed: BigInt, group: Int, local: Int): BigInt =
    (0 until 32).map(r => seed + (group << 16) + (local ^ 16) + r * 128).sum & mask32

  it should "run both shared programs on four product CUs through unified commands" in {
    require(sys.props.get("opengpu.sharedProductFourCuQualification").contains("1"),
      "explicit local qualification only")
    val programs = GalliumSharedArtifacts.load(Paths.get(sys.props("opengpu.galliumSharedArtifacts")),
      sys.props("opengpu.galliumSharedArtifactSha256"))
    simulate(new SharedProductHarness) { top =>
      val d = top.io.gpu
      zero(top.io)
      d.graphicsDrained.poke(true.B)
      d.graphicsHostResponse.ready.poke(true.B)
      d.graphicsShaderResponse.ready.poke(true.B)
      d.graphicsShaderL1Invalidate.ready.poke(true.B)
      d.graphicsShaderAtomicResponse.ready.poke(true.B)
      top.reset.poke(true.B); top.clock.step(2); top.reset.poke(false.B)
      top.io.modelsPresent.expect(true.B)
      val memory = mutable.Map.empty[BigInt, Int]
      def put(a: BigInt, v: BigInt): Unit =
        for (i <- 0 until 4) memory(a + i) = ((v >> (8 * i)) & 255).toInt
      def blank(a: BigInt, bytes: Int): Unit = for (i <- 0 until bytes) memory(a + i) = 0
      def remember(a: BigInt, bytes: Int): Unit = for (i <- 0 until bytes) memory(a + i) = 0xa5
      def read(a: BigInt, bytes: Int): BigInt =
        (0 until bytes).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum

      final class Cu(val index: Int, val seedIndex: Int, val words: Vector[BigInt]) {
        val seed = BigInt(if (seedIndex == 0) "a1000000" else "a2000000", 16)
        val code = BigInt(0x00100000) + index * 0x01000000
        val kernarg = code + 0x1000
        val table = code + 0x2000
        val output = code + 0x100000
        val context = index + 1
        var warpStarts = 0
        var peakWarps = 0
        var priorActive = BigInt(0)
        var maxBarrierWaiting = 0
        var lastActive = BigInt(0)
        val expectedWords: Map[BigInt, BigInt] =
          (for (group <- 0 until 2; local <- 0 until 128)
            yield (output + 4 * (group * 128 + local)) -> expected(seed, group, local)).toMap
      }
      val units = (0 until 4).map(i => new Cu(i, i % programs.size, programs(i % programs.size)))
      val root = BigInt(0x10000)
      blank(root, 4096)
      var nextLevel = root + 4096
      val levels = mutable.Map.empty[BigInt, BigInt]
      def mapPage(virtual: BigInt, physical: BigInt, flags: Int): Unit = {
        val level = levels.getOrElseUpdate((virtual >> 22) << 22, {
          val a = nextLevel; nextLevel += 4096; blank(a, 4096); a
        })
        put(root + (virtual >> 22) * 4, ((level >> 12) << 10) | 1)
        put(level + ((virtual >> 12) & 1023) * 4, ((physical >> 12) << 10) | flags)
      }
      units.foreach { u =>
        blank(u.code, 4096); u.words.zipWithIndex.foreach { case (w, i) => put(u.code + 4 * i, w) }
        Seq(2, 1, 1).zipWithIndex.foreach { case (n, i) => put(u.kernarg + 4 * i, BigInt(n)) }
        blank(u.table, 256)
        Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
          3 -> u.context, 4 -> 7, 5 -> u.context, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 1,
          10 -> u.output, 11 -> (u.output + 1024), 13 -> u.output, 14 -> (u.output + 4096))
          .foreach { case (i, v) => put(u.table + 4 * i, v) }
        remember(u.output - 64, 4160)
        mapPage(u.code, u.code, 0x4b)
        mapPage(u.kernarg, u.kernarg, 0x43)
        mapPage(u.table, u.table, 0x43)
        mapPage(u.output, u.output, 0xc7)
      }
      val expectedAll = units.flatMap(_.expectedWords).toMap
      require(expectedAll.size == 1024, "four CU outputs must be physically disjoint")
      val frozen = memory.toMap
      val satp = BigInt("80000000", 16) | (BigInt(1) << 22) | (root >> 12)
      d.vmContextIdle.expect(true.B)
      d.instructionSatp.poke(satp.U); d.vectorSatp.poke(satp.U)
      zero(d.instructionTlbFlush.bits); d.instructionTlbFlush.valid.poke(true.B)
      zero(d.vectorTlbFlush.bits); d.vectorTlbFlush.valid.poke(true.B)
      d.invalidateInstructionCache.poke(true.B)
      top.clock.step()
      d.instructionTlbFlush.valid.poke(false.B); d.vectorTlbFlush.valid.poke(false.B)
      d.invalidateInstructionCache.poke(false.B)

      case class Reply(id: BigInt, address: BigInt, value: BigInt, due: Int,
                       write: Option[(BigInt, BigInt, BigInt)])
      val pending = mutable.ArrayBuffer.empty[Reply]
      var offered = Option.empty[Reply]
      val outstanding = mutable.Set.empty[BigInt]
      var heldRequest = Option.empty[BigInt]
      val written = mutable.Set.empty[BigInt]
      val acknowledged = mutable.Set.empty[BigInt]
      val descriptorTables = mutable.Set.empty[BigInt]
      val readPages = mutable.Set.empty[BigInt]
      val completions = mutable.Map.empty[Int, Boolean]
      var cycles = 0
      var allFourActive = 0
      var sawStorageLease = false

      def tick(): Unit = {
        assert(cycles < GalliumSharedArtifacts.cycleBudget, s"product shared bounded cycles $cycles")
        d.vmContextIdle.expect((completions.size == 4).B)
        if (d.storageLease.get.peek().litToBoolean) sawStorageLease = true
        d.instructionSatp.expect(satp.U); d.vectorSatp.expect(satp.U)
        for (i <- 0 until 4) {
          d.trap(i).valid.expect(false.B); d.trap(i).ready.poke(false.B)
          d.fpu(i).valid.expect(false.B); d.vector(i).valid.expect(false.B)
          d.scalarMemory(i).valid.expect(false.B)
          val active = d.activeWarps(i).peek().litValue
          val u = units(i)
          u.peakWarps = u.peakWarps.max(active.bitCount)
          u.warpStarts += (active & ~u.priorActive).bitCount; u.priorActive = active
          u.lastActive = active
          u.maxBarrierWaiting = u.maxBarrierWaiting.max(d.barrierWaiting(i).peek().litValue.bitCount)
        }
        if (units.forall(_.lastActive != 0)) allFourActive += 1
        if (d.gpuCompletion.valid.peek().litToBoolean) {
          val id = d.gpuCompletion.bits.commandId.peek().litValue.toInt
          assert(!completions.contains(id), "duplicate product completion")
          completions(id) = d.gpuCompletion.bits.success.peek().litToBoolean
          d.gpuCompletion.ready.poke(true.B)
        } else d.gpuCompletion.ready.poke(false.B)
        if (offered.isEmpty && cycles % 5 != 0) {
          val ready = pending.indices.filter(i => pending(i).due <= cycles)
          if (ready.nonEmpty) offered = Some(pending.remove(ready.maxBy(i => pending(i).id)))
        }
        d.memoryResponse.valid.poke(offered.nonEmpty.B)
        d.memoryResponse.bits.fault.poke(false.B)
        offered.foreach { r =>
          d.memoryResponse.bits.transactionId.poke(r.id.U)
          d.memoryResponse.bits.readData.poke(r.value.U)
        }
        if (offered.nonEmpty && d.memoryResponse.ready.peek().litToBoolean) {
          val r = offered.get; assert(outstanding.remove(r.id))
          r.write.foreach { case (a, v, m) =>
            val first = m.lowestSetBit; val at = a + first
            assert(written(at) && !acknowledged(at))
            acknowledged += at
            for (i <- 0 until 64 if m.testBit(i)) memory(a + i) = ((v >> (8 * i)) & 255).toInt
          }
          offered = None
        }
        d.memoryRequest.ready.poke((cycles % 7 != 0).B)
        if (d.memoryRequest.valid.peek().litToBoolean) {
          val q = d.memoryRequest.bits; val bits = q.peek().litValue
          heldRequest.foreach(v => assert(v == bits, "held lower request changed"))
          if (d.memoryRequest.ready.peek().litToBoolean) {
            heldRequest = None
            val id = q.transactionId.peek().litValue; val a = q.address.peek().litValue
            val size = q.sizeLog2.peek().litValue.toInt; val wr = q.isWrite.peek().litToBoolean
            assert(!outstanding(id)); outstanding += id
            if (size == 2) {
              assert(!wr && (a & 3) == 0)
              pending += Reply(id, a, read(a, 4), cycles + 3, None)
            } else {
              assert(size == 6 && (a & 63) == 0)
              if (wr) {
                q.cachePolicy.expect(CachePolicy.uncached)
                val m = q.byteMask.peek().litValue; val first = m.lowestSetBit; val at = a + first
                assert(first >= 0 && (first & 3) == 0 && m == (BigInt(15) << first) &&
                  expectedAll.contains(at) && !written(at))
                assert(((q.writeData.peek().litValue >> (8 * first)) & mask32) == expectedAll(at))
                written += at
                pending += Reply(id, a, BigInt(0), cycles + 17, Some((a, q.writeData.peek().litValue, m)))
              } else {
                readPages += (a & ~BigInt(4095))
                units.foreach { u => if (a >= u.table && a < u.table + 256) descriptorTables += u.table }
                pending += Reply(id, a, read(a, 64), cycles + 3, None)
              }
            }
          } else heldRequest = Some(bits)
        } else assert(heldRequest.isEmpty, "held lower request withdrawn")
        top.clock.step(); cycles += 1
      }
      def until(label: String)(p: => Boolean): Unit = while (!p) {
        assert(cycles < GalliumSharedArtifacts.cycleBudget, s"product shared $label"); tick()
      }

      for (i <- 0 until 4) {
        val u = units(i)
        val cmd = d.gpuCommand.bits
        zero(cmd)
        cmd.commandId.poke(i.U)
        cmd.opcode.poke(GpuCommandOpcode.kernel)
        cmd.launch.kernelPc.poke(u.code.U)
        cmd.launch.kernargAddress.poke(u.kernarg.U)
        for (axis <- 0 until 3) {
          cmd.launch.gridSize(axis).poke((if (axis == 0) 2 else 1).U)
          cmd.launch.localSize(axis).poke((if (axis == 0) 128 else 1).U)
        }
        val task = cmd.storage.get
        task.enabled.poke(true.B); task.table.version.poke(1.U)
        task.table.base.poke(u.table.U); task.table.limit.poke((u.table + 256).U)
        task.table.identity.context.poke(u.context.U); task.table.identity.pass.poke(7.U)
        task.table.identity.generation.poke(u.context.U)
        d.gpuCommand.valid.poke(true.B)
        until("command admission")(d.gpuCommand.ready.peek().litToBoolean)
        tick(); d.gpuCommand.valid.poke(false.B)
      }
      zero(d.gpuCommand.bits)
      until("four commands dispatched")(d.busyComputeUnits.peek().litValue == 15)
      until("four completions")(completions.size == 4)
      assert(completions.values.forall(identity), "a product command failed")
      until("lower drain")(pending.isEmpty && outstanding.isEmpty &&
        d.vmContextIdle.peek().litToBoolean)
      assert(allFourActive > 0, "the four product CUs never held warps together")
      assert(units.forall(u => u.peakWarps == 8 && u.warpStarts == 16 && u.maxBarrierWaiting > 0),
        "a product CU did not run both shared groups")
      assert(acknowledged == expectedAll.keySet && written == expectedAll.keySet)
      assert(sawStorageLease, "no command ever held a storage lease")
      assert(descriptorTables == units.map(_.table).toSet)
      units.foreach { u =>
        val pages = Seq(u.code, u.table).map(_ & ~BigInt(4095))
        val missing = pages.filterNot(readPages.contains)
        assert(missing.isEmpty, s"CU${u.index} did not read every page: ${missing.mkString(",")}")
      }
      val golden = mutable.Map.empty[BigInt, Int] ++ frozen
      expectedAll.foreach { case (a, v) => for (i <- 0 until 4) golden(a + i) = ((v >> (8 * i)) & 255).toInt }
      assert(memory.toMap == golden.toMap, "product shared output or guard words differ")
      assert(d.busyComputeUnits.peek().litValue == 0)
      info(s"SHARED_PRODUCT_FOUR_CU PASS cus=4 groups=8 nativeWarpStarts=64 outputWords=${acknowledged.size} " +
        s"unifiedCommands=true sharedL2=true banks=${GpuConfig().l2Banks} allActiveCycles=$allFourActive " +
        s"completions=${completions.keys.toSeq.sorted.mkString(",")} cycles=$cycles H7Complete=false")
    }
  }
}

object QualifySharedProductFourCu {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.sharedProductFourCuQualification").contains("1"),
      "explicit local qualification only")
    require(args.length == 6 && args(0) == "--artifacts" && args(2) == "--manifest-sha256" && args(4) == "--reports",
      "usage: --artifacts DIRECTORY --manifest-sha256 SHA256 --reports DIRECTORY")
    GalliumSharedArtifacts.load(Paths.get(args(1)), args(3)) // before elaboration/cache lookup
    sys.props("opengpu.galliumSharedArtifacts") = args(1)
    sys.props("opengpu.galliumSharedArtifactSha256") = args(3)
    require(org.scalatest.tools.Runner.run(Array("-s", classOf[SharedProductFourCuQualification].getName,
      "-oD", "-u", args(5))), "shared product four-CU qualification failed")
  }
}
