package opengpu.graphics

import chisel3._
import opengpu.core.memory.AtomicMemoryOp
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImageAtomicResourceAccessSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "ShaderImageAtomicResourceAccess"
  it should "drain descriptor and atomic owners with permission checks and fault precedence" in {
    simulate(new ShaderImageAtomicResourceAccess()) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val fmt = TextureImageFormat.id("R32UI")
      def words(readable: Int = 1, writable: Int = 1, bad: Boolean = false): Vector[BigInt] = {
        val w = Array.fill[BigInt](64)(0)
        for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4,
          10 -> readable, 11 -> writable, 12 -> fmt, 13 -> fmt, 14 -> 1,
          17 -> 15, 18 -> 1, 22 -> 0x1000, 23 -> 0x1400, 25 -> 0x1048,
          26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) w(i) = v
        if (bad) w(63) = 1
        w.toVector
      }
      var tag = 0
      def until(label: String)(condition: => Boolean): Unit = {
        var n = 0; while (!condition && n < 300) { d.clock.step(); n += 1 }
        assert(condition, label)
      }
      def begin(target: Int=0,index: BigInt=0): Unit = {
        d.io.in.ready.expect(true.B); val q = d.io.in.bits; zero(q); tag += 1
        q.table.version.poke(1.U); q.table.base.poke(0x2000.U); q.table.limit.poke(0x2400.U)
        q.table.identity.context.poke(7.U); q.table.identity.pass.poke(11.U); q.table.identity.generation.poke(13.U)
        q.unitIndex.poke(0.U); q.tag.poke(tag.U); q.stage.poke(GraphicsStage.compute.U)
        q.colorType.poke(1.U); q.hasFormatQualifier.poke(true.B); q.shaderFormat.poke(fmt.U)
        q.shaderTarget.poke(target.U);q.x.poke(index.U)
        q.warpId.poke(1.U); q.operation.poke(ShaderImageAtomicOperation.add.U); q.operand.poke(3.U)
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); zero(q)
      }
      def invalidate(): Unit = { d.io.invalidate.poke(true.B); d.clock.step(); d.io.invalidate.poke(false.B) }
      def descriptor(data: Vector[BigInt], faultLine: Int = -1): Unit = {
        for (line <- 0 until (if (faultLine < 0) 4 else faultLine + 1)) {
          until("descriptor offer") { d.io.descriptorRequest.valid.peek().litToBoolean }
          d.io.descriptorRequest.bits.address.expect((0x2000 + 64 * line).U)
          d.io.descriptorRequest.ready.poke(true.B); d.clock.step(); d.io.descriptorRequest.ready.poke(false.B)
          val payload = data.slice(line * 16, line * 16 + 16).zipWithIndex.map { case (x, i) => x << (32 * i) }.sum
          d.io.descriptorResponse.bits.readData.poke(payload.U)
          d.io.descriptorResponse.bits.fault.poke((line == faultLine).B)
          d.io.descriptorResponse.valid.poke(true.B)
          until("descriptor response") { d.io.descriptorResponse.ready.peek().litToBoolean }
          d.clock.step(); d.io.descriptorResponse.valid.poke(false.B); d.io.descriptorResponse.bits.fault.poke(false.B)
        }
      }
      def atomic(cancel: Boolean = false, fault: Boolean = false,address: BigInt=0x1048): Unit = {
        until("atomic offer") { d.io.atomicRequest.valid.peek().litToBoolean }
        d.io.atomicRequest.bits.address.expect(address.U); d.io.atomicRequest.bits.operation.expect(AtomicMemoryOp.add)
        d.io.atomicRequest.bits.operand.expect(3.U); val saved = d.io.atomicRequest.bits.peek().litValue
        if (cancel) d.io.cancel.poke(true.B)
        d.clock.step(2); d.io.atomicRequest.valid.expect(true.B); assert(d.io.atomicRequest.bits.peek().litValue == saved)
        d.io.atomicRequest.ready.poke(true.B); d.clock.step(); d.io.atomicRequest.ready.poke(false.B)
        d.io.atomicResponse.bits.warpId.poke(1.U); d.io.atomicResponse.bits.oldValue.poke(77.U)
        d.io.atomicResponse.bits.fault.poke(fault.B); d.io.atomicResponse.bits.pageFault.poke(fault.B)
        d.io.atomicResponse.valid.poke(true.B); until("atomic reply") { d.io.atomicResponse.ready.peek().litToBoolean }
        d.clock.step(); d.io.atomicResponse.valid.poke(false.B); d.io.cancel.poke(false.B)
      }
      def finish(status: Int, value: Int = 0, faultAddress: Option[Int] = None, hit: Boolean = false): Unit = {
        until("result") { d.io.out.valid.peek().litToBoolean }
        val r = d.io.out.bits; r.tag.expect(tag.U); r.status.expect(status.U); r.value.expect(value.U)
        r.descriptorHit.expect(hit.B); r.faultAddressValid.expect(faultAddress.nonEmpty.B)
        faultAddress.foreach(a => r.faultAddress.expect(a.U))
        val saved = r.peek().litValue; d.clock.step(2); assert(r.peek().litValue == saved)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      begin(); descriptor(words()); atomic(); finish(0, 77)
      begin(); atomic(); finish(0, 77, hit = true)
      val buffer=words().updated(17,BigInt(0)).updated(18,BigInt(0)).updated(20,BigInt(5)).updated(23,BigInt(0x100000)).updated(30,BigInt(0))
        .updated(25,BigInt(0x10000)).updated(38,BigInt(65536)).updated(39,BigInt(262144))
      val selected=BigInt(0x10000)+65535*4
      invalidate();begin(target=5,index=65535);descriptor(buffer);atomic(address=selected);finish(0,77)
      begin(target=5,index=65536);finish(0,hit=true);d.io.atomicRequest.valid.expect(false.B)
      invalidate();begin(target=5,index=65535);descriptor(buffer.updated(39,BigInt(262143)))
      finish(ShaderImageAccessStatus.descriptor);d.io.atomicRequest.valid.expect(false.B)
      invalidate();begin(target=5,index=65535);descriptor(buffer);atomic(address=selected);finish(0,77)
      info("IMAGE_BUFFER_ATOMIC count65536=true checkedWordAddress=true r32PermissionRules=true invalidTexelNoOp=true malformedRangeRejected=true healthyReuse=true componentOnly=true")
      invalidate(); begin(); descriptor(words(readable = 0)); finish(ShaderImageAccessStatus.permission)
      d.io.atomicRequest.valid.expect(false.B)
      invalidate(); begin(); descriptor(words(bad = true)); finish(ShaderImageAccessStatus.descriptor)
      d.io.atomicRequest.valid.expect(false.B)
      invalidate(); begin(); descriptor(words(), faultLine = 1); finish(ShaderImageAccessStatus.memory, faultAddress = Some(0x2040))
      d.io.atomicRequest.valid.expect(false.B)
      invalidate(); begin()
      until("held descriptor") { d.io.descriptorRequest.valid.peek().litToBoolean }
      val held = d.io.descriptorRequest.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(2)
      d.io.descriptorRequest.valid.expect(true.B); assert(d.io.descriptorRequest.bits.peek().litValue == held)
      d.io.descriptorRequest.ready.poke(true.B); d.clock.step(); d.io.descriptorRequest.ready.poke(false.B)
      d.io.descriptorResponse.bits.fault.poke(true.B); d.io.descriptorResponse.valid.poke(true.B)
      until("held descriptor fault") { d.io.descriptorResponse.ready.peek().litToBoolean }
      d.clock.step(); d.io.descriptorResponse.valid.poke(false.B); d.io.descriptorResponse.bits.fault.poke(false.B)
      d.io.cancel.poke(false.B); finish(ShaderImageAccessStatus.memory, faultAddress = Some(0x2000))
      d.io.atomicRequest.valid.expect(false.B)
      invalidate(); begin(); descriptor(words()); atomic(cancel = true, fault = true)
      finish(ShaderImageAccessStatus.memory, faultAddress = Some(0x1048))
      begin(); atomic(); finish(0, 77, hit = true)
    }
  }
}
