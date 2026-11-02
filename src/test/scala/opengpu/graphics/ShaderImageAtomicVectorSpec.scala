package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImageAtomicVectorSpec extends AnyFlatSpec {
  behavior of "Opt-in vector image atomics"
  it should "retain per-lane parameters, suppress helpers and drain atomic faults" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new ShaderImageExecuteUnit(config, atomicAvailable = true)) { d =>
      WindowRecordTest.zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.enabled.poke(true.B)
      val format = TextureImageFormat.id("R32UI")
      val control = (format << 6) | (1 << 5) | (1 << 3)
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0; while (!p && n < 100) { d.clock.step(); n += 1 }; assert(p, label)
      }
      def issue(function: Int, subop: Int, rd: Int, lhs: Seq[Int], rhs: Seq[Int], effect: Int = 15): Unit = {
        d.io.in.ready.expect(true.B); val q = d.io.in.bits; WindowRecordTest.zero(q)
        val instruction = (BigInt(function) << 26) | (BigInt(1) << 25) | (BigInt(subop) << 12) | (BigInt(rd) << 7) | 0x2b
        q.issued.decode.instruction.poke(instruction.U); q.issued.decode.warpId.poke(1.U)
        q.issued.decode.pc.poke(0x200.U); q.issued.decode.activeMask.poke(15.U)
        q.executionMask.poke(15.U); q.sideEffectMask.poke(effect.U)
        q.issued.vs1Data.zip(lhs).foreach { case (v, x) => v.poke(x.U) }
        q.issued.vs2Data.zip(rhs).foreach { case (v, x) => v.poke(x.U) }
        q.issued.oldVdData.zipWithIndex.foreach { case (v, i) => v.poke((700 + i).U) }
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); WindowRecordTest.zero(q)
      }
      def parameter(pair: Int, lhs: Seq[Int], rhs: Seq[Int]): Unit = {
        issue(8, pair, 0, lhs, rhs); until("parameter commit") { d.io.commit.valid.peek().litToBoolean }
        d.io.status.expect(0.U); d.io.commit.bits.writesScalar.expect(true.B)
        d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      }
      def response(lane: Int, fault: Boolean = false): Unit = {
        until("atomic offer") { d.io.atomicRequest.get.valid.peek().litToBoolean }
        val q = d.io.atomicRequest.get.bits; q.tag.expect(lane.U); q.unitIndex.expect(lane.U)
        q.warpId.expect(1.U); q.operation.expect(7.U); q.x.expect((10 + lane).U); q.y.expect((20 + lane).U)
        q.operand.expect((100 + lane).U); q.compareValue.expect((200 + lane).U)
        val held = q.peek().litValue; d.clock.step(2); assert(q.peek().litValue == held)
        d.io.atomicRequest.get.ready.poke(true.B); d.clock.step(); d.io.atomicRequest.get.ready.poke(false.B)
        val r = d.io.atomicResponse.get.bits; WindowRecordTest.zero(r)
        r.tag.poke(lane.U); r.unitIndex.poke(lane.U); r.value.poke((900 + lane).U)
        if (fault) { r.status.poke(ShaderImageAccessStatus.memory.U); r.faultAddressValid.poke(true.B)
          r.faultAddress.poke(0x4560.U); r.pageFault.poke(true.B) }
        d.io.atomicResponse.get.valid.poke(true.B); d.io.atomicResponse.get.ready.expect(true.B)
        d.clock.step(); d.io.atomicResponse.get.valid.poke(false.B)
      }
      val lanes = 0 until 4
      parameter(0, lanes, Seq.fill(4)(control)); parameter(1, Seq.fill(4)(0), Seq.fill(4)(0))
      parameter(2, lanes.map(_ + 100), lanes.map(_ + 200))
      issue(23, 7, 5, lanes.map(_ + 10), lanes.map(_ + 20), effect = 10)
      response(1); response(3)
      until("atomic commit") { d.io.commit.valid.peek().litToBoolean }
      d.io.status.expect(0.U); d.io.commit.bits.writesVd.expect(true.B); d.io.commit.bits.writesScalar.expect(false.B)
      for ((v, expected) <- d.io.commit.bits.writeback.data.zip(Seq(700, 901, 702, 903))) v.expect(expected.U)
      val held = d.io.commit.bits.peek().litValue; d.clock.step(2); assert(d.io.commit.bits.peek().litValue == held)
      d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      issue(23, 7, 5, lanes.map(_ + 10), lanes.map(_ + 20), effect = 10)
      response(1, fault = true)
      until("fault commit") { d.io.commit.valid.peek().litToBoolean }
      d.io.status.expect(ShaderImageAccessStatus.memory.U); d.io.faultAddressValid.expect(true.B)
      d.io.faultAddress.expect(0x4560.U); d.io.atomicPageFault.get.expect(true.B)
      d.io.atomicFaultMask.get.expect(2.U)
      d.io.commit.bits.writesVd.expect(false.B); d.io.atomicRequest.get.valid.expect(false.B)
      d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      issue(23, 7, 5, lanes.map(_ + 10), lanes.map(_ + 20), effect = 0)
      until("helper-only commit") { d.io.commit.valid.peek().litToBoolean }
      d.io.atomicRequest.get.valid.expect(false.B); d.io.status.expect(0.U)
      for ((v, expected) <- d.io.commit.bits.writeback.data.zip(Seq(700, 701, 702, 703))) v.expect(expected.U)
      d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      issue(23, 7, 5, lanes.map(_ + 10), lanes.map(_ + 20), effect = 2)
      until("cancelled atomic offer") { d.io.atomicRequest.get.valid.peek().litToBoolean }
      val saved = d.io.atomicRequest.get.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(2)
      d.io.atomicRequest.get.valid.expect(true.B); assert(d.io.atomicRequest.get.bits.peek().litValue == saved)
      d.io.atomicRequest.get.ready.poke(true.B); d.clock.step(); d.io.atomicRequest.get.ready.poke(false.B)
      val cancelled = d.io.atomicResponse.get.bits; WindowRecordTest.zero(cancelled)
      cancelled.tag.poke(1.U); cancelled.unitIndex.poke(1.U); cancelled.status.poke(ShaderImageAccessStatus.cancelled.U)
      d.io.atomicResponse.get.valid.poke(true.B); d.clock.step(); d.io.atomicResponse.get.valid.poke(false.B)
      d.io.cancel.poke(false.B)
      until("cancelled commit") { d.io.commit.valid.peek().litToBoolean }
      d.io.status.expect(ShaderImageAccessStatus.cancelled.U); d.io.commit.bits.writesVd.expect(false.B)
      d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      d.io.clearParameters.poke(true.B); d.clock.step(); d.io.clearParameters.poke(false.B)
      issue(23, 7, 5, lanes.map(_ + 10), lanes.map(_ + 20), effect = 2)
      until("missing tuple commit") { d.io.commit.valid.peek().litToBoolean }
      d.io.status.expect(ShaderImageAccessStatus.shaderType.U); d.io.atomicRequest.get.valid.expect(false.B)
      d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
    }
  }
}
