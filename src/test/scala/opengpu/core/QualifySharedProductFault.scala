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

/** Opt-in product-path fault qualification: one shared command's store fails,
  * its peers still complete and the product accepts a fresh command. */
@DoNotDiscover
class SharedProductFaultQualification extends AnyFlatSpec {
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

  it should "fail one product shared command, keep its peers exact and relaunch" in {
    require(sys.props.get("opengpu.sharedProductFaultQualification").contains("1"),
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
      val faultKeys = units(2).expectedWords.keySet
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
                       write: Option[(BigInt, BigInt, BigInt)], fault: Boolean = false)
      val pending = mutable.ArrayBuffer.empty[Reply]
      var offered = Option.empty[Reply]
      val outstanding = mutable.Set.empty[BigInt]
      var heldRequest = Option.empty[BigInt]
      var faultedId = Option.empty[BigInt]
      var faultAddress = Option.empty[BigInt]
      var faultsDelivered = 0
      val written = mutable.Set.empty[BigInt]
      val acknowledged = mutable.Set.empty[BigInt]
      val descriptorTables = mutable.Set.empty[BigInt]
      val readPages = mutable.Set.empty[BigInt]
      val completions = mutable.Map.empty[Int, Boolean]
      var cycles = 0
      var allFourActive = 0
      var sawStorageLease = false
      var phase = 1
      // Trap handshake on whichever CU ran the faulted command.
      var trapCu = Option.empty[Int]
      val trapState = Array.fill(4)(0)
      val trapHold = Array.fill(4)(0)
      val trapBits = Array.fill[Option[BigInt]](4)(None)
      val trapCause = Array.fill[Option[BigInt]](4)(None)
      val trapTval = Array.fill[Option[BigInt]](4)(None)
      val trapLaneMask = Array.fill[Option[BigInt]](4)(None)
      val acceptTrapNext = Array.fill(4)(false)
      var trapsAccepted = 0
      var recoveryRequests = 0L

      def tick(): Unit = {
        assert(cycles < GalliumSharedArtifacts.cycleBudget, s"product fault bounded cycles $cycles")
        if (phase == 1) d.vmContextIdle.expect((completions.size == 4).B)
        if (d.storageLease.get.peek().litToBoolean) sawStorageLease = true
        d.instructionSatp.expect(satp.U); d.vectorSatp.expect(satp.U)
        for (i <- 0 until 4) {
          val trap = d.trap(i)
          if (phase == 1 && trapCu.forall(_ == i)) {
            trapState(i) match {
              case 0 =>
                trap.ready.poke(false.B)
                if (trap.valid.peek().litToBoolean) {
                  trapCu = Some(i); trapState(i) = 1; trapHold(i) = 0
                  trapBits(i) = Some(trap.bits.peek().litValue)
                  trapCause(i) = Some(trap.bits.cause.peek().litValue)
                  trapTval(i) = Some(trap.bits.tval.peek().litValue)
                  trapLaneMask(i) = Some(trap.bits.laneFaultMask.peek().litValue)
                }
              case 1 =>
                trap.valid.expect(true.B)
                assert(trap.bits.peek().litValue == trapBits(i).get, "held product trap changed")
                trapHold(i) += 1
                if (trapHold(i) > 5) { trap.ready.poke(true.B); acceptTrapNext(i) = true }
                else trap.ready.poke(false.B)
              case _ =>
                trap.valid.expect(false.B); trap.ready.poke(false.B)
            }
          } else {
            trap.valid.expect(false.B); trap.ready.poke(false.B)
          }
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
        d.memoryResponse.bits.fault.poke(offered.exists(_.fault).B)
        offered.foreach { r =>
          d.memoryResponse.bits.transactionId.poke(r.id.U)
          d.memoryResponse.bits.readData.poke(r.value.U)
        }
        if (offered.nonEmpty && d.memoryResponse.ready.peek().litToBoolean) {
          val r = offered.get; assert(outstanding.remove(r.id))
          if (r.fault) {
            assert(faultedId.contains(r.id) && phase == 1 && r.write.isDefined)
            faultsDelivered += 1
          } else r.write.foreach { case (a, v, m) =>
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
                  expectedAll.contains(at) && (phase == 2 || !written(at)))
                assert(((q.writeData.peek().litValue >> (8 * first)) & mask32) == expectedAll(at))
                written += at
                if (phase == 2) recoveryRequests += 1
                val inject = phase == 1 && a >= units(2).output &&
                  a < units(2).output + 4096 && faultedId.isEmpty
                if (inject) {
                  faultedId = Some(id); faultAddress = Some(at)
                  pending += Reply(id, a, BigInt(0), cycles + 17,
                    Some((a, q.writeData.peek().litValue, m)), fault = true)
                } else {
                  pending += Reply(id, a, BigInt(0), cycles + 17,
                    Some((a, q.writeData.peek().litValue, m)))
                }
              } else {
                readPages += (a & ~BigInt(4095))
                units.foreach { u => if (a >= u.table && a < u.table + 256) descriptorTables += u.table }
                pending += Reply(id, a, read(a, 64), cycles + 3, None)
              }
            }
          } else heldRequest = Some(bits)
        } else assert(heldRequest.isEmpty, "held lower request withdrawn")
        top.clock.step(); cycles += 1
        for (i <- 0 until 4) if (acceptTrapNext(i)) {
          acceptTrapNext(i) = false; trapState(i) = 2; trapsAccepted += 1
        }
      }
      def until(label: String)(p: => Boolean): Unit = while (!p) {
        assert(cycles < GalliumSharedArtifacts.cycleBudget, s"product fault $label"); tick()
      }
      def command(id: Int, u: Cu): Unit = {
        val cmd = d.gpuCommand.bits
        zero(cmd)
        cmd.commandId.poke(id.U)
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

      for (i <- 0 until 4) command(i, units(i))
      zero(d.gpuCommand.bits)
      until("four commands dispatched")(d.busyComputeUnits.peek().litValue == 15)
      until("four completions")(completions.size == 4)
      assert(faultsDelivered == 1 && trapsAccepted == 1 && trapCu.isDefined)
      assert(trapCause(trapCu.get).contains(BigInt(7)), "the product store fault did not raise cause 7")
      assert(faultAddress.isDefined && trapTval(trapCu.get).contains(faultAddress.get),
        "the product trap did not report the faulted store address")
      assert(trapLaneMask(trapCu.get).exists(_.bitCount == 1),
        "the product store fault did not identify exactly one lane")
      assert(completions(0) && completions(1) && !completions(2) && completions(3),
        "product completions did not attribute the failure to command 2")
      assert(faultKeys(faultAddress.get) && !acknowledged(faultAddress.get))
      units.foreach { u =>
        if (u.index == 2) assert((acknowledged intersect faultKeys).size < faultKeys.size,
          "the faulted product command completed its whole output")
        else assert(u.expectedWords.keySet.subsetOf(acknowledged))
      }
      assert(units.count(u => u.warpStarts == 16 && u.peakWarps == 8 && u.maxBarrierWaiting > 0) >= 3,
        "the healthy product commands did not run both shared groups")
      assert(trapCu.exists(i => units(i).warpStarts > 0))
      until("lower drain")(pending.isEmpty && outstanding.isEmpty &&
        d.vmContextIdle.peek().litToBoolean)
      assert(allFourActive > 0, "the four product CUs never held warps together")
      assert(descriptorTables == units.map(_.table).toSet)
      assert(sawStorageLease, "no product command ever held a storage lease")
      val golden = mutable.Map.empty[BigInt, Int] ++ frozen
      acknowledged.foreach { a => val v = expectedAll(a); for (i <- 0 until 4) golden(a + i) = ((v >> (8 * i)) & 255).toInt }
      assert(memory.toMap == golden.toMap, "the faulted product run wrote an unexpected word")

      // The product must accept a fresh command after the failed one retired.
      phase = 2
      written.clear(); acknowledged.clear()
      val startsBefore = units.map(_.warpStarts)
      command(5, units(2))
      until("recovery completion")(completions.contains(5))
      assert(completions(5), "the relaunched product command failed")
      assert(trapsAccepted == 1, "the relaunch raised another trap")
      assert(recoveryRequests == faultKeys.size,
        "the relaunched command did not issue one store per output word")
      assert(written.toSet == faultKeys && acknowledged == faultKeys,
        "the relaunched command did not cover exactly its own outputs")
      val golden2 = mutable.Map.empty[BigInt, Int] ++ frozen
      expectedAll.foreach { case (a, v) => for (i <- 0 until 4) golden2(a + i) = ((v >> (8 * i)) & 255).toInt }
      assert(memory.toMap == golden2.toMap, "the relaunched command did not produce the exact output")
      // The relaunch may reuse warp slots without a sampled gap, so require the
      // exact per-word output (both groups) rather than a warp-start count.
      val recovered = units.indices.filter(i => units(i).warpStarts > startsBefore(i))
      assert(recovered.nonEmpty, "no product CU carried the relaunched command")
      until("final drain")(pending.isEmpty && outstanding.isEmpty &&
        d.vmContextIdle.peek().litToBoolean)
      assert(d.busyComputeUnits.peek().litValue == 0)
      info(s"SHARED_PRODUCT_FAULT PASS cus=4 faultCommand=2 trapCu=${trapCu.get} " +
        s"faultsDelivered=$faultsDelivered faultAddress=0x${faultAddress.get.toString(16)} " +
        s"trapsAccepted=$trapsAccepted recoveryRequests=$recoveryRequests " +
        s"recoveredWarpStarts=${recovered.map(i => units(i).warpStarts - startsBefore(i)).mkString(",")} " +
        s"healthyOutputs=768 recoveredOutputs=256 allActiveCycles=$allFourActive cycles=$cycles H7Complete=false")
    }
  }
}

object QualifySharedProductFault {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.sharedProductFaultQualification").contains("1"),
      "explicit local qualification only")
    require(args.length == 6 && args(0) == "--artifacts" && args(2) == "--manifest-sha256" && args(4) == "--reports",
      "usage: --artifacts DIRECTORY --manifest-sha256 SHA256 --reports DIRECTORY")
    GalliumSharedArtifacts.load(Paths.get(args(1)), args(3)) // before elaboration/cache lookup
    sys.props("opengpu.galliumSharedArtifacts") = args(1)
    sys.props("opengpu.galliumSharedArtifactSha256") = args(3)
    require(org.scalatest.tools.Runner.run(Array("-s", classOf[SharedProductFaultQualification].getName,
      "-oD", "-u", args(5))), "shared product fault qualification failed")
  }
}
