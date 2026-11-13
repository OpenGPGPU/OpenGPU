package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util.HasBlackBoxInline
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory.StorageDescriptorLayout
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

private class GalliumSharedAtomicSramModels extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val present = Output(Bool()) })
  setInline("GalliumSharedAtomicSramModels.sv",
    "module GalliumSharedAtomicSramModels(output present); assign present=1'b1; endmodule\n" +
      Seq(32, 64).map(w => Files.readString(Paths.get(
        s"depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${w}_6t122.v"))).mkString("\n"))
}

/** The stock full native CU factory, including FPU and both checked services. */
private class GalliumSharedAtomicComputeHarness extends Module {
  private val c = GpuConfig(enableSharedVectorAtomics = true)
  require(c.lanes == 16 && c.warps == 8 && c.simtStackDepth == 8 &&
    c.sharedMemoryBytes == 16384 && c.sharedMemoryBanks == 16 &&
    c.sharedMemoryBase == 0x10000000L && c.enableFpu && c.enableVector)
  val cu = Module(new GpuComputeUnit(c, useBlackBoxes = true, enableFpuBackend = true,
    finishOnTrap = true, textureAvailable = false, taskTextureAvailability = true,
    nativeResourcesAvailable = false, storageAvailable = true, computeUniformAvailable = true,
    nativeExecutionProfile = true))
  val io = IO(new Bundle { val compute = chiselTypeOf(cu.io); val modelsPresent = Output(Bool()) })
  io.compute <> cu.io
  io.modelsPresent := Module(new GalliumSharedAtomicSramModels).io.present
}

private[core] object GalliumSharedAtomicArtifacts {
  val names = (0 until 10).map(op => (s"shared-atomic-$op", op)).toVector ++
    Vector(0, 1, 2, 3, 4, 9).map(op => (s"shared-atomic-i-$op", op))
  val codeWords = 188
  val cycleBudget = 524288
  def digest(directory: Path): String = {
    val hash = MessageDigest.getInstance("SHA-256")
    names.foreach { case (name, _) =>
      hash.update((name + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))
      hash.update(Files.readAllBytes(directory.resolve(name + ".native.words")))
    }
    hash.digest().map(b => f"${b & 255}%02x").mkString
  }
  def load(directory: Path, expected: String): Vector[(Vector[BigInt], Int, String)] = {
    require(expected.matches("[a-f0-9]{64}") && digest(directory) == expected,
      "actual CPU-qualified shared atomic words changed or are incomplete")
    names.map { case (name, operation) =>
      val text = Files.readAllLines(directory.resolve(name + ".native.words")).asScala.toVector
      require(text.size == 512 && text.forall(_.matches("[a-f0-9]{8}")), name)
      val words = text.map(BigInt(_, 16))
      require(words.take(2) == Vector(BigInt("01007557", 16), BigInt("c1087057", 16)), name)
      require(words(codeWords - 1) == BigInt("30500073", 16) && words.drop(codeWords).forall(_ == 0), name)
      require(words.count(_ == BigInt("0000000b", 16)) == 1, name)
      require(words.count(w => (w & 127) == 0x27) == 32, name)
      require(words.count(w => (w & 127) == 0x2b && (w >> 26) == 30) == 2, name)
      val atomics = words.filter(w => (w & 127) == 0x2b && Set(BigInt(31), BigInt(32))(w >> 26))
      require(atomics.size == 2 && atomics.forall(w =>
        (if ((w >> 26) == 31) ((w >> 12) & 7).toInt else 8 + ((w >> 12) & 7).toInt) == operation), name)
      (words, operation, name)
    }
  }
}

/** Manual compiler-to-CU qualification; never discovered in regular CI. */
@DoNotDiscover
class GalliumComputeSharedAtomicQualification extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  private val mask32 = (BigInt(1) << 32) - 1
  // Derived only from the source-level seed/operand/CAS comparison. No native
  // instruction decoding or simulated output supplies the expected old values.
  private def oldWords(operation: Int): Vector[BigInt] = {
    var current = BigInt("80000000", 16)
    Vector.tabulate(128) { _ =>
      val old = current
      current = (operation match {
        case 0 => mask32
        case 1 => old - 1
        case 2 => old ^ mask32
        case 3 => old | mask32
        case 4 => old & mask32
        case 5 => old // min(INT_MIN, -1)
        case 6 => mask32 // max(INT_MIN, -1)
        case 7 => old // min(0x80000000, UINT_MAX)
        case 8 => mask32
        case 9 => if (old == BigInt("80000000", 16)) mask32 else old
      }) & mask32
      old
    }.sorted
  }

  it should "execute all sixteen actual ESSL shared atomics with eight warps, checked storage and held completion" in {
    require(sys.props.get("opengpu.galliumComputeSharedAtomicQualification").contains("1"),
      "explicit local qualification only")
    val programs = GalliumSharedAtomicArtifacts.load(Paths.get(sys.props("opengpu.galliumSharedAtomicInputs")),
      sys.props("opengpu.galliumSharedAtomicInputSha256"))
    simulate(new GalliumSharedAtomicComputeHarness) { top =>
      val d = top.io.compute
      zero(top.io); top.reset.poke(true.B); top.clock.step(2); top.reset.poke(false.B)
      top.io.modelsPresent.expect(true.B); d.l1InvalidateDone.ready.poke(true.B)
      var totalCycles = 0
      var totalWarpStarts = 0
      for (((words, operation, name), epoch) <- programs.zipWithIndex) {
        val memory = mutable.Map.empty[BigInt, Int]
        def put(a: BigInt, v: BigInt): Unit =
          for (i <- 0 until 4) memory(a + i) = ((v >> (8 * i)) & 255).toInt
        def blank(a: BigInt, bytes: Int): Unit = for (i <- 0 until bytes) memory(a + i) = 0
        def read(a: BigInt, bytes: Int): BigInt =
          (0 until bytes).map(i => BigInt(memory.getOrElse(a + i, 0xa5)) << (8 * i)).sum
        val pc = BigInt(0x01000000)
        val kernargV = pc + 0x1000
        val tableV = pc + 0x2000
        val outputV = BigInt(0x20000000) // outside the real 16 KiB shared window
        val codeP = BigInt(0x00100000) + epoch * 0x01000000
        val kernargP = codeP + 0x1000
        val tableP = codeP + 0x2000
        val outputP = codeP + 0x100000
        val iRoot = BigInt(0x10000) + epoch * 0x20000
        val vRoot = iRoot + 0x10000
        val iSatp = BigInt("80000000", 16) | (BigInt(epoch + 1) << 22) | (iRoot >> 12)
        val vSatp = BigInt("80000000", 16) | (BigInt(epoch + 1) << 22) | (vRoot >> 12)
        val pageTables = mutable.Set.empty[BigInt]
        val levels = mutable.Map.empty[(BigInt, BigInt), BigInt]
        for (root <- Seq(iRoot, vRoot)) { blank(root, 4096); pageTables += root }
        def map(root: BigInt, virtual: BigInt, physical: BigInt, flags: Int): Unit = {
          val level = levels.getOrElseUpdate((root, virtual >> 22), {
            val a = root + 4096 * (1 + levels.keys.count(_._1 == root))
            blank(a, 4096); pageTables += a; a
          })
          put(root + (virtual >> 22) * 4, ((level >> 12) << 10) | 1)
          put(level + ((virtual >> 12) & 1023) * 4, ((physical >> 12) << 10) | flags)
        }
        blank(codeP, 4096); words.zipWithIndex.foreach { case (w, i) => put(codeP + 4 * i, w) }
        Seq(2, 1, 1).zipWithIndex.foreach { case (n, i) => put(kernargP + 4 * i, BigInt(n)) }
        blank(tableP, 256)
        Seq[(Int, BigInt)](0 -> StorageDescriptorLayout.magic, 1 -> 64, 2 -> 1,
          3 -> (epoch + 1), 4 -> 7, 5 -> (epoch + 1), 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 1,
          10 -> outputV, 11 -> (outputV + 2048), 13 -> outputV, 14 -> (outputV + 4096))
          .foreach { case (i, v) => put(tableP + 4 * i, v) }
        for (i <- -64 until 4160) memory(outputP + i) = 0xa5
        map(iRoot, pc, codeP, 0x4b); map(vRoot, kernargV, kernargP, 0x43)
        map(vRoot, tableV, tableP, 0x43); map(vRoot, outputV, outputP, 0xc7)
        val expectedAddresses = (for (group <- 0 until 2; plane <- 0 until 2; local <- 0 until 128)
          yield outputP + 4 * (group * 256 + plane * 128 + local)).toSet
        val returned = mutable.Map.empty[BigInt, BigInt]
        val frozen = memory.toMap
        case class Reply(id: BigInt, address: BigInt, value: BigInt, due: Int,
                         write: Option[(BigInt, BigInt, BigInt)])
        val pending = mutable.ArrayBuffer.empty[Reply]
        var offered = Option.empty[Reply]
        val outstanding = mutable.Set.empty[BigInt]
        var heldRequest = Option.empty[BigInt]
        val written = mutable.Set.empty[BigInt]
        val acknowledged = mutable.Set.empty[BigInt]
        val descriptorAddresses = mutable.Set.empty[BigInt]
        var cycles = 0; var warpStarts = 0; var peakWarps = 0; var priorActive = BigInt(0)
        var maxBarrierWaiting = 0; var admission = false
        var requestStalls = 0; var responseStalls = 0; var walks = 0; var codeReads = 0
        def drained: Boolean = pending.isEmpty && offered.isEmpty && outstanding.isEmpty &&
          heldRequest.isEmpty && !d.memoryRequest.valid.peek().litToBoolean &&
          !d.memoryResponse.valid.peek().litToBoolean
        def tick(): Unit = {
          assert(cycles < GalliumSharedAtomicArtifacts.cycleBudget && totalCycles < GalliumSharedAtomicArtifacts.cycleBudget * programs.size,
            s"shared epoch=$epoch bounded cycles $cycles/$totalCycles")
          if (admission) {
            d.instructionSatp.expect(iSatp.U); d.vectorSatp.expect(vSatp.U)
            d.storageLease.get.expect(true.B); d.computeUniformLease.get.expect(false.B)
          }
          d.trap.valid.expect(false.B); d.trap.ready.poke(false.B)
          d.globalAtomicRequest.valid.expect(false.B)
          d.texSample.valid.expect(false.B); d.vectorTexSample.valid.expect(false.B)
          val active = d.active.peek().litValue
          peakWarps = peakWarps.max(active.bitCount)
          warpStarts += (active & ~priorActive).bitCount; priorActive = active
          maxBarrierWaiting = maxBarrierWaiting.max(d.barrierWaiting.peek().litValue.bitCount)
          d.memoryRequest.ready.poke((totalCycles % 7 != 0).B)
          if (offered.isEmpty && totalCycles % 5 != 0) {
            val ready = pending.indices.filter(i => pending(i).due <= totalCycles)
            if (ready.nonEmpty) offered = Some(pending.remove(ready.maxBy(i => pending(i).id)))
          }
          d.memoryResponse.valid.poke(offered.nonEmpty.B)
          d.memoryResponse.bits.fault.poke(false.B)
          offered.foreach { r =>
            d.memoryResponse.bits.transactionId.poke(r.id.U)
            d.memoryResponse.bits.readData.poke(r.value.U)
          }
          if (d.completion.valid.peek().litToBoolean) {
            assert(admission && drained, "completion escaped accepted/offered memory drain")
            d.active.expect(0.U); d.barrierWaiting.expect(0.U)
          }
          if (offered.nonEmpty) {
            if (d.memoryResponse.ready.peek().litToBoolean) {
              val r = offered.get; assert(outstanding.remove(r.id))
              r.write.foreach { case (a, v, m) =>
                val first = m.lowestSetBit; val at = a + first
                assert(first >= 0 && m == (BigInt(15) << first) && written(at) && !acknowledged(at))
                acknowledged += at
                for (i <- 0 until 64 if m.testBit(i)) memory(a + i) = ((v >> (8 * i)) & 255).toInt
              }
              offered = None
            } else responseStalls += 1
          }
          if (d.memoryRequest.valid.peek().litToBoolean) {
            val q = d.memoryRequest.bits; val bits = q.peek().litValue
            heldRequest.foreach(v => assert(v == bits, "held request changed"))
            if (d.memoryRequest.ready.peek().litToBoolean) {
              heldRequest = None
              val id = q.transactionId.peek().litValue; val a = q.address.peek().litValue
              val size = q.sizeLog2.peek().litValue.toInt; val wr = q.isWrite.peek().litToBoolean
              assert(!outstanding(id) && (id < 4 || (id >= 8 && id <= 12))); outstanding += id
              if (size == 2) {
                assert(!wr && (id < 4 || id == 10 || id == 12) && (a & 3) == 0 &&
                  pageTables(a & ~BigInt(4095))); walks += 1
              } else {
                assert(size == 6 && (a & 63) == 0)
                if (id == 8) {
                  assert(!wr && a == tableP); descriptorAddresses += a
                } else if (id == 9) {
                  q.cachePolicy.expect(CachePolicy.uncached); assert(wr, "unexpected storage read")
                  val m = q.byteMask.peek().litValue; val first = m.lowestSetBit; val at = a + first
                  assert(first >= 0 && (first & 3) == 0 && m == (BigInt(15) << first) &&
                    expectedAddresses(at) && !written(at))
                  returned(at) = (q.writeData.peek().litValue >> (8 * first)) & mask32
                  written += at
                } else {
                  assert(id < 4 && !wr && a >= codeP && a < codeP + 4096,
                    "unexpected uniform, kernarg, shared or other external access")
                  codeReads += 1
                }
              }
              pending += Reply(id, a, if (wr) BigInt(0) else read(a, if (size == 2) 4 else 64),
                totalCycles + (if (wr) 17 else 3),
                if (wr) Some((a, q.writeData.peek().litValue, q.byteMask.peek().litValue)) else None)
            } else { heldRequest = Some(bits); requestStalls += 1 }
          } else assert(heldRequest.isEmpty, "held request withdrawn")
          top.clock.step(); cycles += 1; totalCycles += 1
        }
        def until(label: String)(p: => Boolean): Unit = while (!p) {
          assert(cycles < GalliumSharedAtomicArtifacts.cycleBudget, s"shared epoch=$epoch $label"); tick()
        }
        d.invalidateInstructionCache.poke(true.B)
        d.instructionTlbFlush.valid.poke(true.B); d.vectorTlbFlush.valid.poke(true.B); tick()
        d.invalidateInstructionCache.poke(false.B)
        d.instructionTlbFlush.valid.poke(false.B); d.vectorTlbFlush.valid.poke(false.B)
        d.instructionSatp.poke(iSatp.U); d.vectorSatp.poke(vSatp.U)
        val storage = d.storage.get
        storage.enabled.poke(true.B); storage.table.version.poke(1.U)
        storage.table.base.poke(tableV.U); storage.table.limit.poke((tableV + 256).U)
        storage.table.identity.context.poke((epoch + 1).U); storage.table.identity.pass.poke(7.U)
        storage.table.identity.generation.poke((epoch + 1).U)
        d.kernel.bits.kernelPc.poke(pc.U); d.kernel.bits.kernargAddress.poke(kernargV.U)
        for (a <- 0 until 3) {
          d.kernel.bits.gridSize(a).poke((if (a == 0) 2 else 1).U)
          d.kernel.bits.localSize(a).poke((if (a == 0) 128 else 1).U)
        }
        d.kernel.valid.poke(true.B); until("admission")(d.kernel.ready.peek().litToBoolean); tick()
        admission = true; d.kernel.valid.poke(false.B); zero(storage)
        until("completion")(d.completion.valid.peek().litToBoolean)
        d.completion.bits.success.expect(true.B); tick(); assert(drained)
        assert(peakWarps == 8 && warpStarts == 16 && maxBarrierWaiting > 0 &&
          walks > 0 && codeReads > 0 && requestStalls > 0)
        assert(written.toSet == expectedAddresses && acknowledged.toSet == expectedAddresses &&
          descriptorAddresses.toSet == Set(tableP))
        for (group <- 0 until 2; plane <- 0 until 2) {
          val actual = (0 until 128).map(local => returned(outputP + 4 * (group * 256 + plane * 128 + local))).sorted
          assert(actual == oldWords(operation), s"$name group=$group plane=$plane returned old-word multiset")
        }
        val golden = mutable.Map.empty[BigInt, Int] ++ frozen
        returned.foreach { case (a, v) => for (i <- 0 until 4) golden(a + i) = ((v >> (8 * i)) & 255).toInt }
        assert(memory.toMap == golden.toMap, "output, allocation guard, descriptor or code changed")
        val held = d.completion.bits.peek().litValue; d.kernel.valid.poke(true.B)
        for (_ <- 0 until 13) {
          d.completion.valid.expect(true.B); d.kernel.ready.expect(false.B); tick()
          d.completion.valid.expect(true.B)
          assert(d.completion.bits.peek().litValue == held && drained)
        }
        d.kernel.valid.poke(false.B); d.completion.ready.poke(true.B); tick()
        admission = false; d.completion.ready.poke(false.B)
        d.storageLease.get.expect(false.B); d.computeUniformLease.get.expect(false.B)
        for (_ <- 0 until 16) { tick(); d.active.expect(0.U); d.barrierWaiting.expect(0.U); assert(drained) }
        assert(memory.toMap == golden.toMap)
        totalWarpStarts += warpStarts
        info(s"GALLIUM_SHARED_ATOMIC_CU case=$name PASS codeWords=188 groups=2 local=128 warpStarts=$warpStarts peakWarps=$peakWarps sharedBytes=16384 outputWords=${written.size} acks=${acknowledged.size} walks=$walks codeReads=$codeReads maxBarrierWaiting=$maxBarrierWaiting requestStalls=$requestStalls responseStalls=$responseStalls heldCompletion=13 quietTail=16 cycles=$cycles")
      }
      assert(totalWarpStarts == 256)
      info(s"GALLIUM_SHARED_ATOMIC_CU PASS parents=16 groups=32 nativeWarpStarts=256 outputWords=8192 lanes=16 warps=8 sharedBytes=16384 realSram=true fpuRetained=true cycles=$totalCycles hostLinuxAPIQualified=false H7Complete=false")
    }
  }
}

object QualifyGalliumComputeSharedAtomic {
  def main(args: Array[String]): Unit = {
    require(sys.props.get("opengpu.galliumComputeSharedAtomicQualification").contains("1"),
      "explicit local qualification only")
    require(args.length == 6 && args(0) == "--inputs" && args(2) == "--input-sha256" && args(4) == "--reports",
      "usage: --inputs DIRECTORY --input-sha256 SHA256 --reports DIRECTORY")
    GalliumSharedAtomicArtifacts.load(Paths.get(args(1)), args(3)) // before elaboration/cache lookup
    sys.props("opengpu.galliumSharedAtomicInputs") = args(1)
    sys.props("opengpu.galliumSharedAtomicInputSha256") = args(3)
    require(org.scalatest.tools.Runner.run(Array("-s", classOf[GalliumComputeSharedAtomicQualification].getName,
      "-oD", "-u", args(5))), "Gallium shared atomic qualification failed")
    GalliumSharedAtomicArtifacts.load(Paths.get(args(1)), args(3)) // reject drift during execution
  }
}
