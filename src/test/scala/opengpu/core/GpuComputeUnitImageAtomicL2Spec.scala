package opengpu.core

import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import opengpu.config.{CachePolicy, GpuConfig}
import opengpu.core.memory._
import opengpu.graphics.{GraphicsStage, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** One real CU and real shared L2. Descriptor/instruction and lower-memory
  * responders are independent test models; the competing atomic uses L2 port 1.
  */
private class ImageAtomicCuL2Harness(config: GpuConfig) extends Module {
  val cu = Module(new GpuComputeUnit(config, finishOnTrap = true, textureAvailable = false,
    nativeResourcesAvailable = false, imageAvailable = true, imageAtomicAvailable = true,
    nativeExecutionProfile = true, instructionCacheSets = 4, instructionCacheWays = 1,
    vectorCacheSets = 4, vectorCacheWays = 1))
  val l2 = Module(new SharedL2Cache(config, sets = 4, ways = 1, banks = 1, numComputeUnits = 2, maxOutstanding = 8))
  val io = IO(new Bundle {
    val system = chiselTypeOf(cu.io)
    val other = Flipped(Decoupled(new SharedAtomicRequest(config)))
    val otherResult = Decoupled(new SharedAtomicResponse(config))
    val lowerRequest = Decoupled(new ComputeMemoryRequest(config, 64, 8))
    val lowerResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 8)))
    val drained = Output(Bool())
  })
  io.system <> cu.io
  l2.io.atomicRequest(0) <> cu.io.globalAtomicRequest; cu.io.globalAtomicResponse <> l2.io.atomicResponse(0)
  l2.io.atomicRequest(1) <> io.other; io.otherResult <> l2.io.atomicResponse(1)
  cu.io.l1Invalidate <> l2.io.invalidate(0); l2.io.invalidateDone(0) <> cu.io.l1InvalidateDone
  l2.io.invalidate(1).ready := true.B; l2.io.invalidateDone(1).valid := false.B
  l2.io.invalidateDone(1).bits := 0.U.asTypeOf(l2.io.invalidateDone(1).bits)
  l2.io.request.valid := false.B; l2.io.request.bits := 0.U.asTypeOf(l2.io.request.bits); l2.io.response.ready := true.B
  l2.io.hostInvalidate.valid := false.B; l2.io.hostInvalidate.bits := 0.U.asTypeOf(l2.io.hostInvalidate.bits)
  l2.io.hostInvalidateDone.ready := true.B; l2.io.clearPerformanceCounters := false.B
  io.lowerRequest <> l2.io.memoryRequest; l2.io.memoryResponse <> io.lowerResponse
  io.drained := l2.io.drained
}

class GpuComputeUnitImageAtomicL2Spec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  behavior of "CU image atomic and shared L2"
  for ((mode, order, translated, denied, faultPhase) <- (Seq(("add", 0), ("compare", 0), ("compare", 1), ("compare", 2),
    ("smin", 0), ("umin", 0), ("smax", 0), ("umax", 0),
    ("smin", 1), ("umin", 1), ("smax", 1), ("umax", 1),
    ("smin", 2), ("umin", 2), ("smax", 2), ("umax", 2),
    ("swap", 0), ("xor", 0), ("or", 0), ("and", 0),
    ("swap", 1), ("xor", 1), ("or", 1), ("and", 1),
    ("swap", 2), ("xor", 2), ("or", 2), ("and", 2)).map { case (mode, order) => (mode, order, false, false, "none") } ++
    Seq("add", "compare", "smin", "umin", "smax", "umax", "swap", "xor", "or", "and")
      .map(mode => (mode, 0, true, false, "none")) ++ Seq(("add", 0, true, true, "none"),
        ("add", 0, false, false, "read"), ("add", 0, false, false, "write")))) {
  val logic = Set("swap", "xor", "or", "and")(mode)
  it should s"serialize an executed image atomic against another L2 client mode=$mode order=$order logic=$logic translated=$translated denied=$denied faultPhase=$faultPhase" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new ImageAtomicCuL2Harness(config)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val cu = d.io.system; val compare = mode == "compare"
      val minmax = Set("smin", "umin", "smax", "umax")(mode); val signed = mode == "smin" || mode == "smax"
      val fmt = TextureImageFormat.id(if (signed) "R32I" else "R32UI")
      val control = (fmt << 6) | (if (signed) 48 else 40)
      def image(fn: Int, pair: Int = 0, rd: Int = 0, vs1: Int = 0, vs2: Int = 0): BigInt =
        (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
          (BigInt(pair) << 12) | (BigInt(rd) << 7) | 0x2b
      def vector(fn: Int, form: Int, rd: Int, vs1: Int, vs2: Int = 0): BigInt =
        (BigInt(fn) << 26) | (BigInt(1) << 25) | (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
          (BigInt(form) << 12) | (BigInt(rd) << 7) | 0x57
      val program = Vector(BigInt("0104f057", 16), vector(23, 3, 2, 0),
        BigInt(((control + 0x800) & ~0xfff) | (10 << 7) | 0x37),
        BigInt(((control & 0xfff).toLong << 20) | (10 << 15) | (10 << 7) | 0x13),
        vector(23, 4, 3, 10), image(8, vs1 = 2, vs2 = 3), image(8, pair = 1, vs1 = 2, vs2 = 2),
        vector(23, 3, 4, if (minmax || logic) 31 else 3), vector(23, 3, 5, 10), image(8, pair = 2, vs1 = 4, vs2 = if (compare) 5 else 2),
        image(23, pair = if (compare) 7 else if (mode.endsWith("min")) 5 else if (mode.endsWith("max")) 6 else if (mode == "swap") 0 else if (mode == "xor") 2 else if (mode == "or") 3 else if (mode == "and") 4 else 1, rd = 10, vs1 = 2, vs2 = 2), BigInt("30500073", 16))
      val bytes = mutable.Map.empty[BigInt, Int]
      def put(address: BigInt, value: BigInt): Unit = for (i <- 0 until 4) bytes(address + i) = ((value >> (8 * i)) & 255).toInt
      def get(address: BigInt): BigInt = (0 until 64).map(i => BigInt(bytes.getOrElse(address + i, 0)) << (8 * i)).sum
      program.zipWithIndex.foreach { case (v, i) => put(0x1000 + i * 4, v) }
      val descriptor = Array.fill[BigInt](64)(0)
      for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
        12 -> fmt, 13 -> fmt, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x9000, 23 -> 0xa000,
        25 -> 0x9048, 26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) descriptor(i) = v
      val descriptorAddress = if (translated) 0xa000 else 0x8000
      val atomicAddress = if (translated) 0xb048 else 0x9048
      descriptor.zipWithIndex.foreach { case (v, i) => put(descriptorAddress + i * 4, v) }
      if (translated) {
        put(0x30000, (BigInt(0x31) << 10) | 1)
        put(0x31020, (BigInt(0xa) << 10) | 0x243)
        put(0x31024, (BigInt(0xb) << 10) | (if (denied) 0x243 else 0x2c7))
        cu.vectorSatp.poke("h80000030".U)
      }
      cu.trap.ready.poke((denied || faultPhase != "none").B)
      cu.images.get.enabled.poke(true.B); cu.images.get.stage.poke(GraphicsStage.compute.U)
      val table = cu.images.get.table; table.version.poke(1.U); table.base.poke(0x8000.U); table.limit.poke(0x8400.U)
      table.identity.context.poke(7.U); table.identity.pass.poke(11.U); table.identity.generation.poke(13.U)
      for (i <- 0 until 3) { cu.kernel.bits.gridSize(i).poke(1.U); cu.kernel.bits.localSize(i).poke((if (i == 0) 4 else 1).U) }
      cu.kernel.bits.kernelPc.poke(0x1000.U); cu.kernel.bits.invocations.helpers.poke(14.U)
      cu.kernel.valid.poke(true.B); cu.kernel.ready.expect(true.B); d.clock.step(); cu.kernel.valid.poke(false.B)
      if (translated) cu.vectorSatp.poke("h80000040".U)
      cu.images.get.enabled.poke(false.B); cu.images.get.table.base.poke(0xdead0000L.U)
      case class Reply(id: Int, data: BigInt, due: Int, fault: Boolean = false)
      val memoryReplies = mutable.Queue.empty[Reply]; val lowerReplies = mutable.Queue.empty[Reply]
      var lowerData = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(if (i == 8) 10 else if (i >= 8 && i < 12) 0 else 0x5a) << (8 * i)))
      val initial = lowerData
      var cycle = 0; var descriptors = 0; var walks = 0; var armed = false; var sent = false; var otherDone = false; var contended = false
      var otherOld = BigInt(-1); var imageOld = BigInt(-1); var lowerReads = 0; var lowerWrites = 0; var traps = 0; var faultSent = false
      while (!(cu.completion.valid.peek().litToBoolean && otherDone && d.io.drained.peek().litToBoolean) && cycle < 20000) {
        cu.memoryRequest.ready.poke((cycle % 3 != 0).B)
        val reply = memoryReplies.headOption.filter(_.due <= cycle); cu.memoryResponse.valid.poke(reply.nonEmpty.B)
        reply.foreach(r => { cu.memoryResponse.bits.transactionId.poke(r.id.U); cu.memoryResponse.bits.readData.poke(r.data.U) })
        if (reply.nonEmpty && cu.memoryResponse.ready.peek().litToBoolean) memoryReplies.dequeue()
        if (cu.memoryRequest.valid.peek().litToBoolean && cu.memoryRequest.ready.peek().litToBoolean) {
          val m = cu.memoryRequest.bits; val id = m.transactionId.peek().litValue.toInt; val a = m.address.peek().litValue
          m.isWrite.expect(false.B); assert(!memoryReplies.exists(_.id == id))
          if (m.sizeLog2.peek().litValue == 2) {
            assert(translated && Set[BigInt](0x30000, 0x31020, 0x31024)(a)); walks += 1
          } else if (id == 7) { assert(a == descriptorAddress + descriptors * 64); descriptors += 1 }
          else assert(id < 4 && a >= 0x1000 && a < 0x1100)
          memoryReplies.enqueue(Reply(id, get(a), cycle + 3))
        }
        if ((faultPhase != "none" && traps > 0) ||
          (faultPhase == "none" && ((denied && descriptors == 4) || order == 1 ||
            (order == 2 && lowerWrites > 0) || (order == 0 && cu.globalAtomicRequest.valid.peek().litToBoolean)))) armed = true
        d.io.other.valid.poke((armed && !sent).B); d.io.other.bits.address.poke(atomicAddress.U); d.io.other.bits.warpId.poke(0.U)
        d.io.other.bits.operation.poke(AtomicMemoryOp.add); d.io.other.bits.operand.poke(2.U)
        d.io.other.bits.cachePolicy.poke(CachePolicy.cached)
        if (cu.globalAtomicRequest.valid.peek().litToBoolean && d.io.other.valid.peek().litToBoolean) contended = true
        if (armed && !sent && d.io.other.ready.peek().litToBoolean) sent = true
        d.io.otherResult.ready.poke((cycle % 3 != 0).B)
        if (d.io.otherResult.valid.peek().litToBoolean && d.io.otherResult.ready.peek().litToBoolean) {
          d.io.otherResult.bits.fault.expect(false.B); otherOld = d.io.otherResult.bits.oldValue.peek().litValue; otherDone = true
        }
        d.io.lowerRequest.ready.poke((cycle % 4 != 0).B)
        val lr = lowerReplies.headOption.filter(_.due <= cycle); d.io.lowerResponse.valid.poke(lr.nonEmpty.B)
        lr.foreach(r => { d.io.lowerResponse.bits.transactionId.poke(r.id.U); d.io.lowerResponse.bits.readData.poke(r.data.U); d.io.lowerResponse.bits.fault.poke(r.fault.B) })
        if (lr.nonEmpty && d.io.lowerResponse.ready.peek().litToBoolean) lowerReplies.dequeue()
        if (d.io.lowerRequest.valid.peek().litToBoolean && d.io.lowerRequest.ready.peek().litToBoolean) {
          val q = d.io.lowerRequest.bits; q.address.expect((atomicAddress & ~63).U)
          val id = q.transactionId.peek().litValue.toInt; val before = lowerData
          if (q.isWrite.peek().litToBoolean) {
            val mask = q.byteMask.peek().litValue; val data = q.writeData.peek().litValue
            for (i <- 0 until 64 if mask.testBit(i)) {
              val byteMask = BigInt(255) << (8 * i); lowerData = (lowerData & ~byteMask) | (data & byteMask)
            }
            lowerWrites += 1
          } else lowerReads += 1
          val error = !faultSent && ((faultPhase == "read" && !q.isWrite.peek().litToBoolean) ||
            (faultPhase == "write" && q.isWrite.peek().litToBoolean))
          if (error) faultSent = true
          lowerReplies.enqueue(Reply(id, before, cycle + 5, error))
        }
        if (cu.committedVectorWriteback.valid.peek().litToBoolean && cu.committedVectorWriteback.bits.vd.peek().litValue == 10)
          imageOld = cu.committedVectorWriteback.bits.data(0).peek().litValue
        if (denied || faultPhase != "none") {
          if (denied) cu.globalAtomicRequest.valid.expect(false.B)
          if (cu.trap.valid.peek().litToBoolean) {
            cu.trap.bits.pc.expect(0x1028.U); cu.trap.bits.cause.expect((if (denied) 15 else 7).U)
            cu.trap.bits.tval.expect(0x9048.U); cu.trap.bits.laneFaultMask.expect(1.U); traps += 1
          }
        } else cu.trap.valid.expect(false.B)
        d.clock.step(); cycle += 1
      }
      cu.completion.valid.expect(true.B); cu.completion.bits.success.expect((!denied && faultPhase == "none").B); cu.imageLease.get.expect(true.B)
      val finalWord = (lowerData >> 64) & BigInt("ffffffff", 16)
      if (denied || faultPhase != "none") {
        val peerOld = if (faultPhase == "write") 13 else 10
        assert(traps == 1 && imageOld == -1 && otherOld == peerOld && finalWord == peerOld + 2)
        assert(denied || faultSent)
      } else if (compare) {
        if (order == 1) assert(imageOld == 12 && otherOld == 10 && finalWord == 12)
        else if (order == 2) assert(imageOld == 10 && otherOld == 3 && finalWord == 5)
        else assert((imageOld == 10 && otherOld == 3 && finalWord == 5) ||
          (imageOld == 12 && otherOld == 10 && finalWord == 12))
      } else if (minmax || logic) {
        val minusOne = BigInt("ffffffff", 16)
        if (mode == "xor") {
          val imageFirst = imageOld == 10 && otherOld == BigInt("fffffff5", 16) && finalWord == BigInt("fffffff7", 16)
          val otherFirst = imageOld == 12 && otherOld == 10 && finalWord == BigInt("fffffff3", 16)
          if (order == 1) assert(otherFirst) else if (order == 2) assert(imageFirst) else assert(imageFirst || otherFirst)
        } else if (mode == "smin" || mode == "umax" || mode == "swap" || mode == "or") {
          if (order == 1) assert(imageOld == 12 && otherOld == 10 && finalWord == minusOne)
          else if (order == 2) assert(imageOld == 10 && otherOld == minusOne && finalWord == 1)
          else assert((imageOld == 10 && otherOld == minusOne && finalWord == 1) ||
            (imageOld == 12 && otherOld == 10 && finalWord == minusOne))
        } else {
          if (order == 1) assert(imageOld == 12 && otherOld == 10 && finalWord == 12)
          else if (order == 2) assert(imageOld == 10 && otherOld == 10 && finalWord == 12)
          else assert((imageOld == 10 || imageOld == 12) && otherOld == 10 && finalWord == 12)
        }
      } else {
        assert((imageOld == 10 && otherOld == 13) || (imageOld == 12 && otherOld == 10))
        assert(finalWord == 15)
      }
      val wordMask = BigInt("ffffffff", 16) << 64; assert((lowerData & ~wordMask) == (initial & ~wordMask))
      assert((if (translated) walks > 0 else walks == 0) && descriptors == 4 && sent && otherDone && (denied || faultPhase != "none" || order != 0 || contended) && lowerReads >= 1 &&
        lowerWrites == (if (denied || faultPhase == "read") 1 else 2) && memoryReplies.isEmpty && lowerReplies.isEmpty)
      cu.completion.ready.poke(true.B); d.clock.step(); cu.completion.ready.poke(false.B); cu.imageLease.get.expect(false.B)
    }
  }
  }
}
