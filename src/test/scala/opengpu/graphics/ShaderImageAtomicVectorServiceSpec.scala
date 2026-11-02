package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.memory.AtomicMemoryOp
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class ShaderImageAtomicVectorServiceSpec extends AnyFlatSpec {
  behavior of "Image atomic vector-service composition"
  it should "retain task ownership through descriptor admission and acknowledged lane atomics" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new ShaderImageAtomicVectorService(config)) { d =>
      WindowRecordTest.zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val fmt = TextureImageFormat.id("R32UI"); val control = (fmt << 6) | (1 << 5) | (1 << 3)
      val task = d.io.task; task.enabled.poke(true.B); task.stage.poke(GraphicsStage.compute.U)
      task.table.version.poke(1.U); task.table.base.poke(0x8000.U); task.table.limit.poke(0x8400.U)
      task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U); task.table.identity.generation.poke(13.U)
      d.io.kernelStart.poke(true.B); d.clock.step(); d.io.kernelStart.poke(false.B)
      task.enabled.poke(false.B); task.stage.poke(GraphicsStage.vertex.U); task.table.base.poke(0xdead0000L.U)
      val words = Array.fill[BigInt](64)(0)
      for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
        12 -> fmt, 13 -> fmt, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x1000, 23 -> 0x1400,
        25 -> 0x1040, 26 -> 4, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) words(i) = v
      def issue(function: Int, subop: Int, rd: Int, lhs: Seq[Int], rhs: Seq[Int], effect: Int = 15): Unit = {
        d.io.in.ready.expect(true.B); val q = d.io.in.bits; WindowRecordTest.zero(q)
        val insn = (BigInt(function) << 26) | (BigInt(1) << 25) | (BigInt(subop) << 12) | (BigInt(rd) << 7) | 0x2b
        q.issued.decode.instruction.poke(insn.U); q.issued.decode.warpId.poke(1.U)
        q.issued.decode.pc.poke(0x200.U); q.issued.decode.activeMask.poke(15.U)
        q.executionMask.poke(15.U); q.sideEffectMask.poke(effect.U)
        q.issued.vs1Data.zip(lhs).foreach { case (v, x) => v.poke(x.U) }
        q.issued.vs2Data.zip(rhs).foreach { case (v, x) => v.poke(x.U) }
        q.issued.oldVdData.zipWithIndex.foreach { case (v, i) => v.poke((700 + i).U) }
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); WindowRecordTest.zero(q)
      }
      def finishParameter(): Unit = {
        var n = 0; while (!d.io.commit.valid.peek().litToBoolean && n < 100) { d.clock.step(); n += 1 }
        d.io.commit.valid.expect(true.B); d.io.status.expect(0.U); d.io.commit.bits.writesScalar.expect(true.B)
        d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      }
      val lanes = 0 until 4
      issue(8, 0, 0, Seq.fill(4)(0), Seq.fill(4)(control)); finishParameter()
      issue(8, 1, 0, Seq.fill(4)(0), Seq.fill(4)(0)); finishParameter()
      issue(8, 2, 0, lanes.map(_ + 10), lanes.map(_ + 20)); finishParameter()
      case class Reply(data: BigInt, due: Int)
      val replies = mutable.Queue.empty[Reply]
      var cycle = 0; var lines = 0; var atomicDue = -1; var atomicCount = 0; var atomicValue = 0
      var atomicFault = false
      def run(faultLast: Boolean, cancelFirst: Boolean = false): Unit = {
        issue(23, ShaderImageAtomicOperation.add, 5, lanes, Seq.fill(4)(0), effect = 10)
        var n = 0; var cancelSet = false
        while (!d.io.commit.valid.peek().litToBoolean && n < 10000) {
          d.io.imageMemoryRequest.valid.expect(false.B)
          d.io.descriptorMemoryRequest.ready.poke((cycle % 3 != 0).B)
          val reply = replies.headOption.filter(_.due <= cycle)
          d.io.descriptorMemoryResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach(r => { d.io.descriptorMemoryResponse.bits.transactionId.poke(0.U)
            d.io.descriptorMemoryResponse.bits.readData.poke(r.data.U) })
          if (reply.nonEmpty && d.io.descriptorMemoryResponse.ready.peek().litToBoolean) replies.dequeue()
          if (d.io.descriptorMemoryRequest.valid.peek().litToBoolean && d.io.descriptorMemoryRequest.ready.peek().litToBoolean) {
            val q = d.io.descriptorMemoryRequest.bits; q.transactionId.expect(0.U)
            q.address.expect((0x8000 + 64 * lines).U)
            val payload = words.slice(lines * 16, lines * 16 + 16).zipWithIndex.map { case (v, i) => v << (32 * i) }.sum
            lines += 1; replies.enqueue(Reply(payload, cycle + 3))
          }
          d.io.atomicRequest.ready.poke((cycle % 4 != 0).B)
          if (cancelFirst && !cancelSet && d.io.atomicRequest.valid.peek().litToBoolean) {
            d.io.cancel.poke(true.B); cancelSet = true
          }
          if (d.io.atomicRequest.valid.peek().litToBoolean && d.io.atomicRequest.ready.peek().litToBoolean) {
            assert(atomicDue < 0); val lane = if (atomicCount % 2 == 0) 1 else 3
            d.io.atomicRequest.bits.address.expect((0x1040 + 4 * lane).U)
            d.io.atomicRequest.bits.operand.expect((10 + lane).U); d.io.atomicRequest.bits.operation.expect(AtomicMemoryOp.add)
            d.io.atomicSatp.expect(0.U); atomicValue = 100 + lane; atomicFault = faultLast && lane == 3
            atomicCount += 1; atomicDue = cycle + 5
          }
          val response = atomicDue >= 0 && cycle >= atomicDue
          d.io.atomicResponse.valid.poke(response.B); d.io.atomicResponse.bits.warpId.poke(1.U)
          d.io.atomicResponse.bits.oldValue.poke(atomicValue.U); d.io.atomicResponse.bits.fault.poke(atomicFault.B)
          d.io.atomicResponse.bits.pageFault.poke(atomicFault.B)
          if (response && d.io.atomicResponse.ready.peek().litToBoolean) atomicDue = -1
          d.clock.step(); cycle += 1; n += 1
        }
        d.io.commit.valid.expect(true.B); assert(replies.isEmpty && atomicDue < 0)
        d.io.cancel.poke(false.B)
        val expected = if (faultLast) ShaderImageAccessStatus.memory else if (cancelFirst) ShaderImageAccessStatus.cancelled else ShaderImageAccessStatus.ok
        d.io.status.expect(expected.U)
        d.io.commit.bits.writesVd.expect((!faultLast && !cancelFirst).B)
        if (faultLast) { d.io.faultAddressValid.expect(true.B); d.io.faultAddress.expect(0x104c.U); d.io.atomicPageFault.expect(true.B) }
        else if (!cancelFirst) for ((v, x) <- d.io.commit.bits.writeback.data.zip(Seq(700, 101, 702, 103))) v.expect(x.U)
        val saved = d.io.commit.bits.peek().litValue; d.clock.step(3); assert(d.io.commit.bits.peek().litValue == saved)
        d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      }
      run(faultLast = false); run(faultLast = true); run(faultLast = false, cancelFirst = true)
      assert(lines == 4 && atomicCount == 5)
      d.io.kernelCompletion.poke(true.B); d.clock.step(); d.io.kernelCompletion.poke(false.B); d.io.busy.expect(false.B)
    }
  }
}
