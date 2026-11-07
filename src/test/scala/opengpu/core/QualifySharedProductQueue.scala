package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import java.nio.file.Paths
import opengpu.command.GpuCommandOpcode
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Opt-in product-path queueing qualification: two waves of shared commands
  * reuse the four CUs and write disjoint output pages exactly. */
@DoNotDiscover
class SharedProductQueueQualification extends AnyFlatSpec {
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

  it should "queue two waves of shared commands on the four product CUs" in {
    require(sys.props.get("opengpu.sharedProductQueueQualification").contains("1"),
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

      val codePages = Vector(BigInt(0x00100000), BigInt(0x01100000))
      val kernargPages = codePages.map(_ + 0x1000)
      final class Job(val index: Int, val seedIndex: Int) {
        val seed = BigInt(if (seedIndex == 0) "a1000000" else "a2000000", 16)
        val code = codePages(seedIndex)
        val kernarg = kernargPages(seedIndex)
        val table = BigInt(0x00200000) + index * 0x1000
        val output = BigInt(0x00300000) + index * 0x10000
        val context = index + 1
        val expectedWords: Map[BigInt, BigInt] =
          (for (group <- 0 until 2; local <- 0 until 128)
            yield (output + 4 * (group * 128 + local)) -> expected(seed, group, local)).toMap
      }
      val jobs = (0 until 8).map(i => new Job(i, i % programs.size))
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
      for (seedIndex <- programs.indices) {
        blank(codePages(seedIndex), 4096)
        programs(seedIndex).zipWithIndex.foreach { case (w, i) => put(codePages(seedIndex) + 4 * i, w) }
        Seq(2, 1, 1).zipWithIndex.foreach { case (n, i) => put(kernargPages(seedIndex) + 4 * i, BigInt(n)) }
        mapPage(codePages(seedIndex), codePages(seedIndex), 0x4b)
        mapPage(kernargPages(seedIndex), kernargPages(seedIndex), 0x43)
      }
      jobs.foreach { j =>
        blank(j.table, 256)
        Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
          3 -> j.context, 4 -> 7, 5 -> j.context, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 1,
          10 -> j.output, 11 -> (j.output + 1024), 13 -> j.output, 14 -> (j.output + 4096))
          .foreach { case (i, v) => put(j.table + 4 * i, v) }
        remember(j.output - 64, 4160)
        mapPage(j.table, j.table, 0x43)
        mapPage(j.output, j.output, 0xc7)
      }
      val expectedAll = jobs.flatMap(_.expectedWords).toMap
      require(expectedAll.size == 2048, "eight command outputs must be physically disjoint")
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
      val cuWarpCycles = Array.fill(4)(0)
      var cycles = 0
      var busyWaves = 0
      var sawLease = false

      def tick(): Unit = {
        assert(cycles < GalliumSharedArtifacts.cycleBudget, s"product queue bounded cycles $cycles")
        d.vmContextIdle.expect((completions.size == 8).B)
        if (d.storageLease.get.peek().litToBoolean) sawLease = true
        d.instructionSatp.expect(satp.U); d.vectorSatp.expect(satp.U)
        for (i <- 0 until 4) {
          d.trap(i).valid.expect(false.B); d.trap(i).ready.poke(false.B)
          d.fpu(i).valid.expect(false.B); d.vector(i).valid.expect(false.B)
          d.scalarMemory(i).valid.expect(false.B)
          val active = d.activeWarps(i).peek().litValue
          cuWarpCycles(i) += active.bitCount
        }
        if (d.busyComputeUnits.peek().litValue == 15) busyWaves += 1
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
                jobs.foreach { j => if (a >= j.table && a < j.table + 256) descriptorTables += j.table }
                pending += Reply(id, a, read(a, 64), cycles + 3, None)
              }
            }
          } else heldRequest = Some(bits)
        } else assert(heldRequest.isEmpty, "held lower request withdrawn")
        top.clock.step(); cycles += 1
      }
      def until(label: String)(p: => Boolean): Unit = while (!p) {
        assert(cycles < GalliumSharedArtifacts.cycleBudget, s"product queue $label"); tick()
      }
      def command(j: Job): Unit = {
        val cmd = d.gpuCommand.bits
        zero(cmd)
        cmd.commandId.poke(j.index.U)
        cmd.opcode.poke(GpuCommandOpcode.kernel)
        cmd.launch.kernelPc.poke(j.code.U)
        cmd.launch.kernargAddress.poke(j.kernarg.U)
        for (axis <- 0 until 3) {
          cmd.launch.gridSize(axis).poke((if (axis == 0) 2 else 1).U)
          cmd.launch.localSize(axis).poke((if (axis == 0) 128 else 1).U)
        }
        val task = cmd.storage.get
        task.enabled.poke(true.B); task.table.version.poke(1.U)
        task.table.base.poke(j.table.U); task.table.limit.poke((j.table + 256).U)
        task.table.identity.context.poke(j.context.U); task.table.identity.pass.poke(7.U)
        task.table.identity.generation.poke(j.context.U)
        d.gpuCommand.valid.poke(true.B)
        until("command admission")(d.gpuCommand.ready.peek().litToBoolean)
        tick(); d.gpuCommand.valid.poke(false.B)
      }

      jobs.foreach(command)
      zero(d.gpuCommand.bits)
      until("eight completions")(completions.size == 8)
      assert(completions.values.forall(identity), "a queued product command failed")
      until("lower drain")(pending.isEmpty && outstanding.isEmpty &&
        d.vmContextIdle.peek().litToBoolean)
      assert(busyWaves > 0, "the four product CUs never ran four commands together")
      assert(sawLease, "no queued command ever held a storage lease")
      assert(acknowledged == expectedAll.keySet && written == expectedAll.keySet)
      assert(descriptorTables == jobs.map(_.table).toSet)
      jobs.foreach { j =>
        val missing = Seq(j.table, j.code).map(_ & ~BigInt(4095)).filterNot(readPages.contains)
        assert(missing.isEmpty, s"command ${j.index} did not read every page: ${missing.mkString(",")}")
      }
      val golden = mutable.Map.empty[BigInt, Int] ++ frozen
      expectedAll.foreach { case (a, v) => for (i <- 0 until 4) golden(a + i) = ((v >> (8 * i)) & 255).toInt }
      assert(memory.toMap == golden.toMap, "queued shared output or guard words differ")
      assert(cuWarpCycles.forall(_ > 0), "a product CU never held warps")
      assert(d.busyComputeUnits.peek().litValue == 0)
      info(s"SHARED_PRODUCT_QUEUE PASS commands=8 waves=2 cus=4 outputWords=${acknowledged.size} " +
        s"allFourCycles=$busyWaves activeWarpCycles=${cuWarpCycles.mkString(",")} cycles=$cycles H7Complete=false")
    }
  }
}

object QualifySharedProductQueue {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.sharedProductQueueQualification").contains("1"),
      "explicit local qualification only")
    require(args.length == 6 && args(0) == "--artifacts" && args(2) == "--manifest-sha256" && args(4) == "--reports",
      "usage: --artifacts DIRECTORY --manifest-sha256 SHA256 --reports DIRECTORY")
    GalliumSharedArtifacts.load(Paths.get(args(1)), args(3)) // before elaboration/cache lookup
    sys.props("opengpu.galliumSharedArtifacts") = args(1)
    sys.props("opengpu.galliumSharedArtifactSha256") = args(3)
    require(org.scalatest.tools.Runner.run(Array("-s", classOf[SharedProductQueueQualification].getName,
      "-oD", "-u", args(5))), "shared product queue qualification failed")
  }
}
