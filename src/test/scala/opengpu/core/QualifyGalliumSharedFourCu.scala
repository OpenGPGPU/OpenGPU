package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Paths}
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.{SharedComputeMemoryInterconnect, SharedL2Cache, StorageDescriptorLayout}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class GalliumSharedFourCuSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("GalliumSharedFourCuSramModels.sv",
    "module GalliumSharedFourCuSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(w => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** Four stock native CUs sharing one real L2, interconnect and lower port.
  * CU0/CU2 run the first CPU-qualified word program and CU1/CU3 the second. */
private[core] class GalliumSharedFourCuHarness extends Module {
  private val c = GpuConfig()
  require(c.lanes == 16 && c.warps == 8 && c.simtStackDepth == 8 &&
    c.sharedMemoryBytes == 16384 && c.sharedMemoryBanks == 16 &&
    c.sharedMemoryBase == 0x10000000L && c.enableFpu && c.enableVector)
  val cu = Seq.fill(4)(Module(new GpuComputeUnit(c, useBlackBoxes = true,
    enableFpuBackend = true, finishOnTrap = true, textureAvailable = false,
    taskTextureAvailability = true, nativeResourcesAvailable = false,
    storageAvailable = true, computeUniformAvailable = true,
    nativeExecutionProfile = true)))
  val interconnect = Module(new SharedComputeMemoryInterconnect(c,
    numComputeUnits = 4, lineBytes = 64, transactionsPerCu = 16))
  val l2 = Module(new SharedL2Cache(c, sets = c.l2Sets, ways = c.l2Ways,
    banks = c.l2Banks, requestQueueDepth = c.l2RequestQueueDepth,
    maxOutstanding = 64, numComputeUnits = 4, transactionsPerCu = 16,
    useSramBlackBoxes = true))
  val io = IO(new Bundle {
    // The compute mirrors expose per-CU request/response activity, but the
    // interconnect/L2, not these unused input fields, drive the CU.
    val compute = Vec(4, chiselTypeOf(cu.head.io))
    val lowerRequest = chiselTypeOf(l2.io.memoryRequest)
    val lowerResponse = chiselTypeOf(l2.io.memoryResponse)
    val cuRequests = Output(Vec(4, UInt(64.W)))
    val cuResponses = Output(Vec(4, UInt(64.W)))
    val simultaneousRequests = Output(UInt(64.W))
    val pairwiseRequests = Output(UInt(64.W))
    val cuBusy = Output(Vec(4, Bool()))
    val modelsPresent = Output(Bool())
    val drained = Output(Bool())
  })
  for (i <- 0 until 4) {
    io.compute(i) <> cu(i).io
    interconnect.io.cuRequest(i) <> cu(i).io.memoryRequest
    cu(i).io.memoryResponse <> interconnect.io.cuResponse(i)
    l2.io.atomicRequest(i) <> cu(i).io.globalAtomicRequest
    cu(i).io.globalAtomicResponse <> l2.io.atomicResponse(i)
    cu(i).io.l1Invalidate <> l2.io.invalidate(i)
    l2.io.invalidateDone(i) <> cu(i).io.l1InvalidateDone
  }
  l2.io.request <> interconnect.io.memoryRequest
  interconnect.io.memoryResponse <> l2.io.response
  l2.io.hostInvalidate.valid := false.B
  l2.io.hostInvalidate.bits := 0.U.asTypeOf(l2.io.hostInvalidate.bits)
  l2.io.hostInvalidateDone.ready := true.B
  l2.io.clearPerformanceCounters := false.B
  io.lowerRequest <> l2.io.memoryRequest
  l2.io.memoryResponse <> io.lowerResponse
  private val requests = RegInit(VecInit(Seq.fill(4)(0.U(64.W))))
  private val responses = RegInit(VecInit(Seq.fill(4)(0.U(64.W))))
  private val simultaneous = RegInit(0.U(64.W))
  private val pairwise = RegInit(0.U(64.W))
  for (i <- 0 until 4) {
    when(interconnect.io.cuRequest(i).fire) { requests(i) := requests(i) + 1.U }
    when(interconnect.io.cuResponse(i).fire) { responses(i) := responses(i) + 1.U }
    io.cuBusy(i) := interconnect.io.cuRequest(i).valid ||
      interconnect.io.cuResponse(i).valid || cu(i).io.l1Invalidate.valid
  }
  private val requestValid = (0 until 4).map(i => interconnect.io.cuRequest(i).valid)
  when(requestValid.reduce(_ && _)) { simultaneous := simultaneous + 1.U }
  when(requestValid.map(_.asUInt).reduce(_ +& _) > 1.U) { pairwise := pairwise + 1.U }
  io.cuRequests := requests
  io.cuResponses := responses
  io.simultaneousRequests := simultaneous
  io.pairwiseRequests := pairwise
  io.modelsPresent := Module(new GalliumSharedFourCuSramModels).io.present
  io.drained := l2.io.drained
}

/** Explicit local qualification, absent from default CI discovery. */
@DoNotDiscover
class GalliumSharedFourCuQualification extends AnyFlatSpec {
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

  it should "run both shared programs on four CUs through one shared L2" in {
    require(sys.props.get("opengpu.galliumSharedFourCuQualification").contains("1"),
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

      case class Reply(id: BigInt, address: BigInt, value: BigInt, due: Int,
                       write: Option[(BigInt, BigInt, BigInt)])
      val pending = mutable.ArrayBuffer.empty[Reply]
      var offered = Option.empty[Reply]
      val outstanding = mutable.Set.empty[BigInt]
      var heldRequest = Option.empty[BigInt]
      val written = mutable.Set.empty[BigInt]
      val acknowledged = mutable.Set.empty[BigInt]
      val descriptorAddresses = mutable.Set.empty[BigInt]
      val readPages = mutable.Set.empty[BigInt]
      var totalCycles = 0
      var allActiveCycles = 0

      def tick(): Unit = {
        assert(totalCycles < GalliumSharedArtifacts.cycleBudget,
          s"four-CU bounded cycles $totalCycles")
        for (i <- 0 until 4) {
          val d = ds(i); val u = units(i)
          if (u.admission) {
            d.instructionSatp.expect(u.iSatp.U); d.vectorSatp.expect(u.vSatp.U)
            d.storageLease.get.expect(true.B); d.computeUniformLease.get.expect(false.B)
          }
          d.trap.valid.expect(false.B); d.trap.ready.poke(false.B)
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
        top.io.lowerResponse.bits.fault.poke(false.B)
        offered.foreach { r =>
          top.io.lowerResponse.bits.transactionId.poke(r.id.U)
          top.io.lowerResponse.bits.readData.poke(r.value.U)
        }
        if (offered.nonEmpty) {
          if (top.io.lowerResponse.ready.peek().litToBoolean) {
            val r = offered.get; assert(outstanding.remove(r.id))
            r.write.foreach { case (a, v, m) =>
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
            } else {
              assert(size == 6 && (a & 63) == 0 && knownPages(a & ~BigInt(4095)))
              if (wr) {
                q.cachePolicy.expect(CachePolicy.uncached)
                val m = q.byteMask.peek().litValue; val first = m.lowestSetBit; val at = a + first
                assert(first >= 0 && (first & 3) == 0 && m == (BigInt(15) << first) &&
                  expectedAll.contains(at) && !written(at))
                assert(((q.writeData.peek().litValue >> (8 * first)) & mask32) == expectedAll(at))
                written += at
              } else {
                readPages += (a & ~BigInt(4095))
                units.foreach { u =>
                  if (a >= u.tableP && a < u.tableP + 256) descriptorAddresses += u.tableP
                }
              }
            }
            pending += Reply(id, a, if (wr) BigInt(0) else read(a, if (size == 2) 4 else 64),
              totalCycles + (if (wr) 17 else 3),
              if (wr) Some((a, q.writeData.peek().litValue, q.byteMask.peek().litValue)) else None)
          } else { heldRequest = Some(bits) }
        } else assert(heldRequest.isEmpty, "held lower request withdrawn")
        top.clock.step(); totalCycles += 1
      }
      def until(label: String)(p: => Boolean): Unit = while (!p) {
        assert(totalCycles < GalliumSharedArtifacts.cycleBudget, s"four-CU $label"); tick()
      }

      for (i <- 0 until 4) {
        ds(i).invalidateInstructionCache.poke(true.B)
        ds(i).instructionTlbFlush.valid.poke(true.B); ds(i).vectorTlbFlush.valid.poke(true.B)
      }
      tick()
      for (i <- 0 until 4) {
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
      for (i <- 0 until 4) {
        ds(i).completion.bits.success.expect(true.B)
        top.io.cuBusy(i).expect(false.B)
        assert(units(i).peakWarps == 8 && units(i).warpStarts == 16 &&
          units(i).maxBarrierWaiting > 0)
      }
      top.io.drained.expect(true.B)
      assert(allActiveCycles > 0, "the four CUs never held warps simultaneously")
      assert((0 until 4).forall(i =>
        top.io.cuRequests(i).peek().litValue > 0 && top.io.cuResponses(i).peek().litValue > 0))
      assert(top.io.simultaneousRequests.peek().litValue > 0,
        "the four CUs never offered memory requests in the same cycle")
      assert(top.io.pairwiseRequests.peek().litValue > 0,
        "no two CUs ever offered memory requests in the same cycle")
      assert(written.toSet == expectedAll.keySet && acknowledged.toSet == expectedAll.keySet)
      assert(descriptorAddresses == units.map(_.tableP).toSet)
      units.foreach { u =>
        // The shader never dereferences its kernarg page (matching the one-CU
        // qualification); require the code, descriptor and every page-table page.
        val pages = Seq(u.codeP, u.tableP).map(_ & ~BigInt(4095)) ++ u.pageTables
        val missing = pages.filterNot(readPages.contains)
        assert(missing.isEmpty, s"CU${u.index} did not read every page: ${missing.mkString(",")}")
      }
      val golden = mutable.Map.empty[BigInt, Int] ++ frozen
      expectedAll.foreach { case (a, v) => for (i <- 0 until 4) golden(a + i) = ((v >> (8 * i)) & 255).toInt }
      assert(memory.toMap == golden.toMap, "output, allocation guard, descriptor or code changed")

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
      info(s"GALLIUM_SHARED_FOUR_CU PASS cus=4 groups=8 nativeWarpStarts=64 outputWords=${written.size} " +
        s"sharedL2=true banks=${GpuConfig().l2Banks} interconnect=true allActiveCycles=$allActiveCycles " +
        s"simultaneousRequests=${top.io.simultaneousRequests.peek().litValue} " +
        s"pairwiseRequests=${top.io.pairwiseRequests.peek().litValue} " +
        s"cuRequests=${(0 until 4).map(i => top.io.cuRequests(i).peek().litValue).mkString(",")} " +
        s"cuResponses=${(0 until 4).map(i => top.io.cuResponses(i).peek().litValue).mkString(",")} " +
        s"heldCompletion=13 quietTail=16 cycles=$totalCycles H7Complete=false")
    }
  }
}

object QualifyGalliumSharedFourCu {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.galliumSharedFourCuQualification").contains("1"),
      "explicit local qualification only")
    require(args.length == 6 && args(0) == "--artifacts" && args(2) == "--manifest-sha256" && args(4) == "--reports",
      "usage: --artifacts DIRECTORY --manifest-sha256 SHA256 --reports DIRECTORY")
    GalliumSharedArtifacts.load(Paths.get(args(1)), args(3)) // before elaboration/cache lookup
    sys.props("opengpu.galliumSharedArtifacts") = args(1)
    sys.props("opengpu.galliumSharedArtifactSha256") = args(3)
    require(org.scalatest.tools.Runner.run(Array("-s", classOf[GalliumSharedFourCuQualification].getName,
      "-oD", "-u", args(5))), "Gallium shared four-CU qualification failed")
  }
}
