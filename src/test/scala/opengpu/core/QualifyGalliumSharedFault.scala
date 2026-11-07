package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import java.nio.file.Paths
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Opt-in shared-L2 fault qualification: one CU's store faults, the peers and
  * the fabric stay healthy, and the faulted CU runs the program again. */
@DoNotDiscover
class GalliumSharedFaultQualification extends AnyFlatSpec {
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

  it should "isolate a shared-L2 store fault and reuse the faulted CU" in {
    require(sys.props.get("opengpu.galliumSharedFaultQualification").contains("1"),
      "explicit local qualification only")
    val programs = GalliumSharedArtifacts.load(Paths.get(sys.props("opengpu.galliumSharedArtifacts")),
      sys.props("opengpu.galliumSharedArtifactSha256"))
    simulate(new GalliumSharedFourCuHarness) { top =>
      val ds = (0 until 4).map(i => top.io.compute(i))
      zero(top.io); top.reset.poke(true.B); top.clock.step(2); top.reset.poke(false.B)
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
        val pc = BigInt(0x01000000)
        val kernargV = pc + 0x1000
        val tableV = pc + 0x2000
        val outputV = BigInt(0x20000000)
        val codeP = BigInt(0x00100000) + index * 0x01000000
        val kernargP = codeP + 0x1000
        val tableP = codeP + 0x2000
        val outputP = codeP + 0x100000
        val iRoot = BigInt(0x10000) + index * 0x20000
        val vRoot = iRoot + 0x10000
        val context = index + 1
        val iSatp = BigInt("80000000", 16) | (BigInt(context) << 22) | (iRoot >> 12)
        val vSatp = BigInt("80000000", 16) | (BigInt(context) << 22) | (vRoot >> 12)
        val pageTables = mutable.Set.empty[BigInt]
        private val levels = mutable.Map.empty[(BigInt, BigInt), BigInt]
        def map(root: BigInt, virtual: BigInt, physical: BigInt, flags: Int): Unit = {
          val level = levels.getOrElseUpdate((root, virtual >> 22), {
            val a = root + 4096 * (1 + levels.keys.count(_._1 == root))
            blank(a, 4096); pageTables += a; a
          })
          put(root + (virtual >> 22) * 4, ((level >> 12) << 10) | 1)
          put(level + ((virtual >> 12) & 1023) * 4, ((physical >> 12) << 10) | flags)
        }
        def build(): Unit = {
          for (root <- Seq(iRoot, vRoot)) { blank(root, 4096); pageTables += root }
          blank(codeP, 4096); words.zipWithIndex.foreach { case (w, i) => put(codeP + 4 * i, w) }
          Seq(2, 1, 1).zipWithIndex.foreach { case (n, i) => put(kernargP + 4 * i, BigInt(n)) }
          blank(tableP, 256)
          Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
            3 -> context, 4 -> 7, 5 -> context, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 1,
            10 -> outputV, 11 -> (outputV + 1024), 13 -> outputV, 14 -> (outputV + 4096))
            .foreach { case (i, v) => put(tableP + 4 * i, v) }
          remember(outputP - 64, 4160)
          map(iRoot, pc, codeP, 0x4b); map(vRoot, kernargV, kernargP, 0x43)
          map(vRoot, tableV, tableP, 0x43); map(vRoot, outputV, outputP, 0xc7)
        }
        val expectedWords: Map[BigInt, BigInt] =
          (for (group <- 0 until 2; local <- 0 until 128)
            yield (outputP + 4 * (group * 128 + local)) -> expected(seed, group, local)).toMap
        var admission = false
        var warpStarts = 0
        var peakWarps = 0
        var priorActive = BigInt(0)
        var maxBarrierWaiting = 0
        var lastActive = BigInt(0)
      }
      val units = (0 until 4).map(i => new Cu(i, i % programs.size, programs(i % programs.size)))
      units.foreach(_.build())
      val frozen = memory.toMap
      val expectedAll = units.flatMap(_.expectedWords).toMap
      require(expectedAll.size == 1024, "four CU outputs must be physically disjoint")
      val knownPages = (units.flatMap(u => Seq(u.codeP, u.kernargP, u.tableP, u.outputP))
        .map(p => p & ~BigInt(4095)) ++ units.flatMap(_.pageTables)).toSet
      val faultCu = 2
      val faultKeys = units(faultCu).expectedWords.keySet

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
      val descriptorAddresses = mutable.Set.empty[BigInt]
      val readPages = mutable.Set.empty[BigInt]
      var totalCycles = 0
      var allActiveCycles = 0
      var phase = 1
      // Trap handshake for the faulted CU: 0 = not seen, 1 = held, 2 = accepted.
      val trapState = Array.fill(4)(0)
      val trapHold = Array.fill(4)(0)
      val trapBits = Array.fill[Option[BigInt]](4)(None)
      val trapCause = Array.fill[Option[BigInt]](4)(None)
      val trapTval = Array.fill[Option[BigInt]](4)(None)
      val trapLaneMask = Array.fill[Option[BigInt]](4)(None)
      var trapAcceptedAt = -1
      val acceptTrapNext = Array.fill(4)(false)
      var recoveryRequests = 0L

      def tick(): Unit = {
        assert(totalCycles < GalliumSharedArtifacts.cycleBudget,
          s"fault bounded cycles $totalCycles")
        for (i <- 0 until 4) {
          val d = ds(i); val u = units(i)
          if (u.admission) {
            d.instructionSatp.expect(u.iSatp.U); d.vectorSatp.expect(u.vSatp.U)
            d.storageLease.get.expect(true.B); d.computeUniformLease.get.expect(false.B)
          }
          if (phase == 1 && i == faultCu) {
            trapState(i) match {
              case 0 =>
                d.trap.ready.poke(false.B)
                if (d.trap.valid.peek().litToBoolean) {
                  trapState(i) = 1; trapHold(i) = 0
                  trapBits(i) = Some(d.trap.bits.peek().litValue)
                  trapCause(i) = Some(d.trap.bits.cause.peek().litValue)
                  trapTval(i) = Some(d.trap.bits.tval.peek().litValue)
                  trapLaneMask(i) = Some(d.trap.bits.laneFaultMask.peek().litValue)
                }
              case 1 =>
                d.trap.valid.expect(true.B)
                assert(d.trap.bits.peek().litValue == trapBits(i).get, "held trap changed")
                trapHold(i) += 1
                if (trapHold(i) > 5) { d.trap.ready.poke(true.B); acceptTrapNext(i) = true }
                else d.trap.ready.poke(false.B)
              case _ =>
                d.trap.valid.expect(false.B); d.trap.ready.poke(false.B)
            }
          } else {
            d.trap.valid.expect(false.B); d.trap.ready.poke(false.B)
          }
          if (phase == 2 && i != faultCu) d.active.expect(0.U)
          d.globalAtomicRequest.valid.expect(false.B)
          d.texSample.valid.expect(false.B); d.vectorTexSample.valid.expect(false.B)
          val active = d.active.peek().litValue
          u.peakWarps = u.peakWarps.max(active.bitCount)
          u.warpStarts += (active & ~u.priorActive).bitCount; u.priorActive = active
          u.lastActive = active
          u.maxBarrierWaiting = u.maxBarrierWaiting.max(d.barrierWaiting.peek().litValue.bitCount)
        }
        if (units.forall(_.lastActive != 0)) allActiveCycles += 1
        if (offered.isEmpty && totalCycles % 5 != 0) {
          val ready = pending.indices.filter(i => pending(i).due <= totalCycles)
          if (ready.nonEmpty) offered = Some(pending.remove(ready.maxBy(i => pending(i).id)))
        }
        top.io.lowerResponse.valid.poke(offered.nonEmpty.B)
        top.io.lowerResponse.bits.fault.poke(offered.exists(_.fault).B)
        offered.foreach { r =>
          top.io.lowerResponse.bits.transactionId.poke(r.id.U)
          top.io.lowerResponse.bits.readData.poke(r.value.U)
        }
        if (offered.nonEmpty) {
          if (top.io.lowerResponse.ready.peek().litToBoolean) {
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
        }
        top.io.lowerRequest.ready.poke((totalCycles % 7 != 0).B)
        if (top.io.lowerRequest.valid.peek().litToBoolean) {
          val q = top.io.lowerRequest.bits; val bits = q.peek().litValue
          heldRequest.foreach(v => assert(v == bits, "held lower request changed"))
          if (top.io.lowerRequest.ready.peek().litToBoolean) {
            heldRequest = None
            val id = q.transactionId.peek().litValue; val a = q.address.peek().litValue
            val size = q.sizeLog2.peek().litValue.toInt; val wr = q.isWrite.peek().litToBoolean
            assert(id >= 0 && id < 64 && !outstanding(id)); outstanding += id
            if (size == 2) {
              assert(!wr && (a & 3) == 0 && knownPages(a & ~BigInt(4095)))
              readPages += (a & ~BigInt(4095))
              pending += Reply(id, a, read(a, 4), totalCycles + 3, None)
            } else {
              assert(size == 6 && (a & 63) == 0 && knownPages(a & ~BigInt(4095)))
              if (wr) {
                q.cachePolicy.expect(CachePolicy.uncached)
                val m = q.byteMask.peek().litValue; val first = m.lowestSetBit; val at = a + first
                assert(first >= 0 && (first & 3) == 0 && m == (BigInt(15) << first) &&
                  expectedAll.contains(at) && (phase == 2 || !written(at)))
                assert(((q.writeData.peek().litValue >> (8 * first)) & mask32) == expectedAll(at))
                written += at
                if (phase == 2 && units(faultCu).admission) recoveryRequests += 1
                val inject = phase == 1 && a >= units(faultCu).outputP &&
                  a < units(faultCu).outputP + 4096 && faultedId.isEmpty
                if (inject) {
                  faultedId = Some(id); faultAddress = Some(at)
                  pending += Reply(id, a, BigInt(0), totalCycles + 17,
                    Some((a, q.writeData.peek().litValue, m)), fault = true)
                } else {
                  pending += Reply(id, a, BigInt(0), totalCycles + 17,
                    Some((a, q.writeData.peek().litValue, m)))
                }
              } else {
                readPages += (a & ~BigInt(4095))
                units.foreach { u =>
                  if (a >= u.tableP && a < u.tableP + 256) descriptorAddresses += u.tableP
                }
                pending += Reply(id, a, read(a, 64), totalCycles + 3, None)
              }
            }
          } else { heldRequest = Some(bits) }
        } else assert(heldRequest.isEmpty, "held lower request withdrawn")
        top.clock.step(); totalCycles += 1
        for (i <- 0 until 4) if (acceptTrapNext(i)) {
          acceptTrapNext(i) = false; trapState(i) = 2; trapAcceptedAt = totalCycles
        }
      }
      def until(label: String)(p: => Boolean): Unit = while (!p) {
        assert(totalCycles < GalliumSharedArtifacts.cycleBudget, s"fault $label"); tick()
      }

      def flushLaunch(i: Int): Unit = {
        val d = ds(i)
        d.invalidateInstructionCache.poke(true.B)
        d.instructionTlbFlush.valid.poke(true.B); d.vectorTlbFlush.valid.poke(true.B)
      }
      def configure(i: Int): Unit = {
        val d = ds(i); val u = units(i)
        d.invalidateInstructionCache.poke(false.B)
        d.instructionTlbFlush.valid.poke(false.B); d.vectorTlbFlush.valid.poke(false.B)
        d.instructionSatp.poke(u.iSatp.U); d.vectorSatp.poke(u.vSatp.U)
        val storage = d.storage.get
        storage.enabled.poke(true.B); storage.table.version.poke(1.U)
        storage.table.base.poke(u.tableV.U); storage.table.limit.poke((u.tableV + 256).U)
        storage.table.identity.context.poke(u.context.U); storage.table.identity.pass.poke(7.U)
        storage.table.identity.generation.poke(u.context.U)
        d.kernel.bits.kernelPc.poke(u.pc.U); d.kernel.bits.kernargAddress.poke(u.kernargV.U)
        for (a <- 0 until 3) {
          d.kernel.bits.gridSize(a).poke((if (a == 0) 2 else 1).U)
          d.kernel.bits.localSize(a).poke((if (a == 0) 128 else 1).U)
        }
        d.kernel.valid.poke(true.B)
      }

      for (i <- 0 until 4) flushLaunch(i)
      tick()
      for (i <- 0 until 4) configure(i)
      val claimed = Array.fill(4)(false)
      while (!claimed.forall(identity)) {
        for (i <- 0 until 4 if !claimed(i) && ds(i).kernel.ready.peek().litToBoolean) claimed(i) = true
        tick()
        for (i <- 0 until 4 if claimed(i) && !units(i).admission) {
          ds(i).kernel.valid.poke(false.B); zero(ds(i).storage.get)
          units(i).admission = true
        }
      }
      until("completion")((0 until 4).forall(i =>
        ds(i).completion.valid.peek().litToBoolean))
      assert(faultsDelivered == 1 && trapState(faultCu) == 2 && trapAcceptedAt > 0,
        "the injected store fault was not delivered and trapped")
      assert(trapBits(faultCu).isDefined)
      assert(trapCause(faultCu).contains(BigInt(7)), "the store fault did not raise cause 7")
      assert(faultAddress.isDefined && trapTval(faultCu).contains(
        units(faultCu).outputV + (faultAddress.get - units(faultCu).outputP)),
        "the trap did not report the faulted store's virtual address")
      assert(trapLaneMask(faultCu).exists(_.bitCount == 1),
        "the store fault did not identify exactly one lane")
      assert(faultKeys(faultAddress.get) && !acknowledged(faultAddress.get))
      for (i <- 0 until 4) {
        ds(i).completion.bits.success.expect((i != faultCu).B)
        top.io.cuBusy(i).expect(false.B)
        if (i == faultCu) {
          assert(units(i).peakWarps > 0 && units(i).warpStarts > 0)
          assert((acknowledged intersect faultKeys).size < faultKeys.size,
            "the faulted CU completed its whole output")
        } else {
          assert(units(i).peakWarps == 8 && units(i).warpStarts == 16 &&
            units(i).maxBarrierWaiting > 0)
          assert(units(i).expectedWords.keySet.subsetOf(acknowledged))
        }
      }
      assert((acknowledged intersect expectedAll.keySet).size < expectedAll.size)
      top.io.drained.expect(true.B)
      assert(allActiveCycles > 0, "the four CUs never held warps simultaneously")
      assert((0 until 4).forall(i =>
        top.io.cuRequests(i).peek().litValue > 0 && top.io.cuResponses(i).peek().litValue > 0))
      assert(written.toSet.subsetOf(expectedAll.keySet))
      assert(descriptorAddresses == units.map(_.tableP).toSet)
      units.foreach { u =>
        val pages = Seq(u.codeP, u.tableP).map(_ & ~BigInt(4095)) ++ u.pageTables
        val missing = pages.filterNot(readPages.contains)
        assert(missing.isEmpty, s"CU${u.index} did not read every page: ${missing.mkString(",")}")
      }
      val golden = mutable.Map.empty[BigInt, Int] ++ frozen
      acknowledged.foreach { a => val v = expectedAll(a); for (i <- 0 until 4) golden(a + i) = ((v >> (8 * i)) & 255).toInt }
      assert(memory.toMap == golden.toMap, "faulted run wrote an unexpected word")

      val held = (0 until 4).map(i => ds(i).completion.bits.peek().litValue)
      for (i <- 0 until 4) ds(i).kernel.valid.poke(true.B)
      for (_ <- 0 until 13) {
        for (i <- 0 until 4) {
          ds(i).completion.valid.expect(true.B); ds(i).kernel.ready.expect(false.B)
        }
        tick()
        for (i <- 0 until 4) {
          ds(i).completion.valid.expect(true.B)
          assert(ds(i).completion.bits.peek().litValue == held(i))
        }
        top.io.drained.expect(true.B)
      }
      for (i <- 0 until 4) { ds(i).kernel.valid.poke(false.B); ds(i).completion.ready.poke(true.B) }
      tick()
      for (i <- 0 until 4) {
        units(i).admission = false; ds(i).completion.ready.poke(false.B)
        ds(i).storageLease.get.expect(false.B); ds(i).computeUniformLease.get.expect(false.B)
      }
      for (_ <- 0 until 16) {
        tick()
        for (i <- 0 until 4) { ds(i).active.expect(0.U); ds(i).barrierWaiting.expect(0.U) }
        top.io.drained.expect(true.B)
      }
      assert(memory.toMap == golden.toMap)

      // The faulted CU must accept a fresh program after its trap retired.
      phase = 2
      written.clear(); acknowledged.clear()
      val requestsBefore = (0 until 4).map(i => top.io.cuRequests(i).peek().litValue)
      val startsBefore = units(faultCu).warpStarts
      flushLaunch(faultCu)
      tick()
      configure(faultCu)
      val reclaimed = Array.fill(4)(false)
      while (!reclaimed(faultCu)) {
        if (ds(faultCu).kernel.ready.peek().litToBoolean) reclaimed(faultCu) = true
        tick()
        if (reclaimed(faultCu) && !units(faultCu).admission) {
          ds(faultCu).kernel.valid.poke(false.B); zero(ds(faultCu).storage.get)
          units(faultCu).admission = true
        }
      }
      until("recovery completion")(ds(faultCu).completion.valid.peek().litToBoolean)
      ds(faultCu).completion.bits.success.expect(true.B)
      assert(units(faultCu).warpStarts == startsBefore + 16,
        "the recovered launch did not start both groups")
      assert(recoveryRequests == faultKeys.size,
        "the recovered launch did not issue one store per output word")
      assert((0 until 4).forall(i => i == faultCu ||
        top.io.cuRequests(i).peek().litValue == requestsBefore(i)),
        "an idle CU issued memory traffic during recovery")
      val golden2 = mutable.Map.empty[BigInt, Int] ++ frozen
      expectedAll.foreach { case (a, v) => for (i <- 0 until 4) golden2(a + i) = ((v >> (8 * i)) & 255).toInt }
      assert(memory.toMap == golden2.toMap, "the recovered launch did not produce the exact output")
      assert(written.toSet == faultKeys && acknowledged == faultKeys,
        "the recovered launch did not cover exactly its own outputs")
      ds(faultCu).completion.ready.poke(true.B); tick()
      ds(faultCu).completion.ready.poke(false.B)
      units(faultCu).admission = false
      for (_ <- 0 until 16) {
        tick()
        for (i <- 0 until 4) { ds(i).active.expect(0.U); ds(i).barrierWaiting.expect(0.U) }
        top.io.drained.expect(true.B)
      }
      val trap = trapBits(faultCu).get
      info(s"GALLIUM_SHARED_FAULT PASS cus=4 faultCu=$faultCu faultsDelivered=$faultsDelivered " +
        s"faultAddress=0x${faultAddress.get.toString(16)} trap=0x${trap.toString(16)} " +
        s"trapAcceptedCycle=$trapAcceptedAt " +
        s"recoveryRequests=$recoveryRequests recoveredWarpStarts=${units(faultCu).warpStarts - startsBefore} " +
        s"healthyOutputs=768 recoveredOutputs=256 allActiveCycles=$allActiveCycles cycles=$totalCycles H7Complete=false")
    }
  }
}

object QualifyGalliumSharedFault {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.galliumSharedFaultQualification").contains("1"),
      "explicit local qualification only")
    require(args.length == 6 && args(0) == "--artifacts" && args(2) == "--manifest-sha256" && args(4) == "--reports",
      "usage: --artifacts DIRECTORY --manifest-sha256 SHA256 --reports DIRECTORY")
    GalliumSharedArtifacts.load(Paths.get(args(1)), args(3)) // before elaboration/cache lookup
    sys.props("opengpu.galliumSharedArtifacts") = args(1)
    sys.props("opengpu.galliumSharedArtifactSha256") = args(3)
    require(org.scalatest.tools.Runner.run(Array("-s", classOf[GalliumSharedFaultQualification].getName,
      "-oD", "-u", args(5))), "Gallium shared fault qualification failed")
  }
}
