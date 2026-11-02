package opengpu.core

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.AtomicMemoryOp
import opengpu.graphics.{GraphicsStage, TextureImageFormat}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class GpuComputeUnitImageAtomicMinMaxSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  behavior of "Opt-in CU image atomics"
  for ((translated, signed, minimum) <- Seq(
    (false, false, true), (false, false, false), (false, true, true), (false, true, false),
    (true, false, true), (true, false, false), (true, true, true), (true, true, false))) {
  it should s"execute image atomics with helper suppression and recovery translated=$translated signed=$signed minimum=$minimum" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuComputeUnit(config, finishOnTrap = true, textureAvailable = false,
        nativeResourcesAvailable = false, imageAvailable = true, imageAtomicAvailable = true, atomicCounterAvailable = false,
        nativeExecutionProfile = true, instructionCacheSets = 4, instructionCacheWays = 1,
        vectorCacheSets = 4, vectorCacheWays = 1)) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.l1InvalidateDone.ready.poke(true.B)
      val fmt = TextureImageFormat.id(if (signed) "R32I" else "R32UI"); val control = (fmt << 6) | (if (signed) 48 else 40)
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
        vector(23, 3, 4, 31), image(8, pair = 2, vs1 = 4, vs2 = 2),
        image(23, pair = if (minimum) 5 else 6, rd = 10, vs1 = 2, vs2 = 2), BigInt("30500073", 16))
      val memory = mutable.Map.empty[BigInt, Int]
      def put(address: BigInt, value: BigInt): Unit = for (i <- 0 until 4) memory(address + i) = ((value >> (8 * i)) & 255).toInt
      def get(address: BigInt, bytes: Int): BigInt = (0 until bytes).map(i => BigInt(memory.getOrElse(address + i, 0)) << (8 * i)).sum
      program.zipWithIndex.foreach { case (v, i) => put(0x1000 + i * 4, v) }
      val descriptor = Array.fill[BigInt](64)(0)
      for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
        12 -> fmt, 13 -> fmt, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x9000, 23 -> 0xa000,
        25 -> 0x9048, 26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) descriptor(i) = v
      val descriptorPhysical = if (translated) 0xa000 else 0x8000
      descriptor.zipWithIndex.foreach { case (v, i) => put(descriptorPhysical + i * 4, v) }
      if (translated) {
        put(0x30000, (BigInt(0x31) << 10) | 1)
        put(0x31020, (BigInt(0xa) << 10) | 0x243)
        put(0x31024, (BigInt(0xb) << 10) | 0x2c7)
      }
      case class Reply(id: Int, data: BigInt, due: Int)
      val pending = mutable.Queue.empty[Reply]
      val operand = BigInt("ffffffff", 16)
      def next(v: BigInt): BigInt = if (minimum == signed) operand else v
      var old = BigInt(10)
      def run(fail: Boolean, denied: Boolean = false, deniedPage: Boolean = false): Unit = {
        put(descriptorPhysical + 10 * 4, if (denied) 0 else 1)
        if (translated) put(0x31024, (BigInt(0xb) << 10) | (if (deniedPage) 0x243 else 0x2c7))
        d.io.images.get.enabled.poke(true.B); d.io.images.get.stage.poke(GraphicsStage.compute.U)
        val table = d.io.images.get.table; zero(table)
        table.version.poke(1.U); table.base.poke(0x8000.U); table.limit.poke(0x8400.U)
        table.identity.context.poke(7.U); table.identity.pass.poke(11.U); table.identity.generation.poke(13.U)
        for (i <- 0 until 3) { d.io.kernel.bits.gridSize(i).poke(1.U); d.io.kernel.bits.localSize(i).poke((if (i == 0) 4 else 1).U) }
        d.io.kernel.bits.kernelPc.poke(0x1000.U); d.io.kernel.bits.invocations.helpers.poke(10.U)
        d.io.vectorSatp.poke((if (translated) BigInt("80000030", 16) else BigInt(0)).U)
        d.io.kernel.valid.poke(true.B); d.io.kernel.ready.expect(true.B); d.clock.step(); d.io.kernel.valid.poke(false.B)
        // Live pins are no longer authoritative after acceptance.
        d.io.images.get.enabled.poke(false.B); d.io.images.get.table.base.poke(0xdead0000L.U)
        // The image atomic must retain its accepted root, not this later input.
        if (translated) d.io.vectorSatp.poke(BigInt("80000040", 16).U)
        val initialOld = old
        var cycle = 0; var atomics = 0; var atomicDue = -1; var atomicOld = BigInt(0); var traps = 0
        var writes = Vector.empty[Vector[BigInt]]; var descriptors = 0
        val expectFailure = fail || denied || deniedPage
        d.io.trap.ready.poke(expectFailure.B)
        while (!d.io.completion.valid.peek().litToBoolean && cycle < 20000) {
          d.io.memoryRequest.ready.poke((cycle % 3 != 0).B)
          val reply = pending.headOption.filter(_.due <= cycle); d.io.memoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r => d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U) }
          if (reply.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) pending.dequeue()
          if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
            val q = d.io.memoryRequest.bits; val id = q.transactionId.peek().litValue.toInt; val address = q.address.peek().litValue
            assert(!pending.exists(_.id == id), s"live memory ID $id was reused")
            q.isWrite.expect(false.B)
            val walk = q.sizeLog2.peek().litValue == 2
            if (walk) assert(translated && Set[BigInt](0x30000, 0x31020, 0x31024)(address), s"unexpected page walk id=$id address=$address")
            else if (id == 7) { assert(address == descriptorPhysical + descriptors * 64); descriptors += 1 }
            else assert(id < 4 && address >= 0x1000 && address < 0x1100, s"unexpected memory id=$id address=$address")
            pending.enqueue(Reply(id, get(address, if (walk) 4 else 64), cycle + 3))
          }
          d.io.globalAtomicRequest.ready.poke((cycle % 4 != 0).B)
          if (d.io.globalAtomicRequest.valid.peek().litToBoolean && d.io.globalAtomicRequest.ready.peek().litToBoolean) {
            assert(atomicDue < 0); val q = d.io.globalAtomicRequest.bits
            q.address.expect((if (translated) 0xb048 else 0x9048).U); q.operand.expect(operand.U)
            q.operation.expect(if (minimum) { if (signed) AtomicMemoryOp.min else AtomicMemoryOp.minu }
              else { if (signed) AtomicMemoryOp.max else AtomicMemoryOp.maxu })
            atomicOld = old; if (!fail) old = next(old); atomics += 1; atomicDue = cycle + 5
          }
          val response = atomicDue >= 0 && cycle >= atomicDue
          d.io.globalAtomicResponse.valid.poke(response.B); d.io.globalAtomicResponse.bits.warpId.poke(0.U)
          d.io.globalAtomicResponse.bits.oldValue.poke(atomicOld.U)
          d.io.globalAtomicResponse.bits.fault.poke((response && fail).B)
          d.io.globalAtomicResponse.bits.pageFault.poke((response && fail).B)
          if (response && d.io.globalAtomicResponse.ready.peek().litToBoolean) atomicDue = -1
          if (d.io.committedVectorWriteback.valid.peek().litToBoolean && d.io.committedVectorWriteback.bits.vd.peek().litValue == 10)
            writes :+= d.io.committedVectorWriteback.bits.data.map(_.peek().litValue).toVector
          if (d.io.trap.valid.peek().litToBoolean) {
            assert(expectFailure); d.io.trap.bits.pc.expect(0x1024.U)
            d.io.trap.bits.cause.expect((if (fail || deniedPage) 15 else 7).U)
            d.io.trap.bits.tval.expect((if (fail || deniedPage) 0x9048 else 0).U); traps += 1
          }
          d.clock.step(); cycle += 1
        }
        d.io.completion.valid.expect(true.B); d.io.completion.bits.success.expect((!expectFailure).B)
        assert(atomics == (if (denied || deniedPage) 0 else if (fail) 1 else 2) && descriptors == 4 && pending.isEmpty && atomicDue < 0)
        if (expectFailure) assert(writes.isEmpty && traps == 1)
        else assert(writes.size == 1 && writes.head(0) == initialOld && writes.head(2) == next(initialOld) && traps == 0)
        d.io.imageLease.get.expect(true.B); d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B)
        d.io.trap.ready.poke(false.B); d.io.imageLease.get.expect(false.B)
      }
      run(fail = false); run(fail = true); run(fail = false, denied = true)
      if (translated) run(fail = false, deniedPage = true)
      run(fail = false)
      assert(old == next(BigInt(10)))

    }
  }
  }
}
