package opengpu.graphics

import chisel3._
import opengpu.core.memory.AtomicMemoryOp
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImageAtomicAccessSpec extends AnyFlatSpec {
  import WindowRecordTest.{zero, identity}
  behavior of "ShaderImageAtomicAccess"
  it should "check image permissions and retain shared atomic ownership through faults and cancellation" in {
    simulate(new ShaderImageAtomicAccess()) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      var tag = 0
      def until(label: String)(condition: => Boolean): Unit = {
        var n = 0; while (!condition && n < 100) { d.clock.step(); n += 1 }
        assert(condition, label)
      }
      def begin(format: String = "R32UI", operation: Int = 1,
          readable: Boolean = true, writable: Boolean = true, x: BigInt = 2,
          qualifier: Boolean = true, bound: Boolean = true, base: Int = 0x1000): Unit = {
        d.io.in.ready.expect(true.B); val q = d.io.in.bits; zero(q); tag += 1
        val a = q.access; identity(a.identity); identity(a.imageBinding.identity)
        a.tag.poke(tag.U); a.unitIndex.poke(1.U); a.stage.poke(GraphicsStage.compute.U)
        a.isWrite.poke(false.B); a.requireReadWrite.poke(false.B)
        a.shaderTarget.poke(0.U); a.colorType.poke((if (format == "R32I") 2 else 1).U)
        a.hasFormatQualifier.poke(qualifier.B); a.shaderFormat.poke(TextureImageFormat.id(format).U)
        a.x.poke(x.U); a.y.poke(1.U)
        val b = a.imageBinding; b.unitIndex.poke(1.U); b.bound.poke(bound.B); b.complete.poke(true.B)
        b.stageMask.poke(4.U); b.readable.poke(readable.B); b.writable.poke(writable.B)
        b.storageFormat.poke(TextureImageFormat.id(format).U); b.imageFormat.poke(TextureImageFormat.id(format).U)
        b.compatibleBySize.poke(true.B); b.maxLevel.poke(15.U); b.layered.poke(true.B)
        val v = b.view; v.target.poke(0.U); v.allocationBase.poke(0x1000.U); v.allocationLimit.poke(0x2000.U)
        v.base.poke(base.U); v.texWidth.poke(3.U); v.texHeight.poke(2.U); v.texDepth.poke(1.U)
        v.layers.poke(1.U); v.rowPitch.poke(64.U); v.blockBytes.poke((if (format == "RGBA32UI") 16 else 4).U)
        q.warpId.poke(1.U); q.operation.poke(operation.U); q.operand.poke(7.U); q.compareValue.poke(11.U)
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); zero(q)
      }
      def result(status: Int, value: BigInt = 0, fault: Boolean = false): Unit = {
        until("result") { d.io.out.valid.peek().litToBoolean }
        val r = d.io.out.bits; r.tag.expect(tag.U); r.unitIndex.expect(1.U); r.status.expect(status.U)
        r.value.expect(value.U); r.faultAddressValid.expect(fault.B)
        if (fault) { r.faultAddress.expect(0x1048.U); r.pageFault.expect(true.B) }
        val held = r.peek().litValue; d.clock.step(2); assert(r.peek().litValue == held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      def atomic(expected: BigInt, cancel: Boolean = false, fault: Boolean = false): Unit = {
        until("atomic request") { d.io.atomicRequest.valid.peek().litToBoolean }
        val q = d.io.atomicRequest.bits; q.address.expect(0x1048.U); q.warpId.expect(1.U)
        q.operation.expect(expected.U); q.operand.expect(7.U); q.compareValue.expect(11.U)
        val held = q.peek().litValue
        if (cancel) d.io.cancel.poke(true.B)
        d.clock.step(2); d.io.atomicRequest.valid.expect(true.B); assert(q.peek().litValue == held)
        d.io.atomicRequest.ready.poke(true.B); d.clock.step(); d.io.atomicRequest.ready.poke(false.B)
        d.io.atomicResponse.bits.warpId.poke(0.U); d.io.atomicResponse.valid.poke(true.B)
        d.io.atomicResponse.ready.expect(false.B)
        d.io.atomicResponse.bits.warpId.poke(1.U); d.io.atomicResponse.bits.oldValue.poke(123.U)
        d.io.atomicResponse.bits.fault.poke(fault.B); d.io.atomicResponse.bits.pageFault.poke(fault.B)
        until("atomic response") { d.io.atomicResponse.ready.peek().litToBoolean }
        d.clock.step(); d.io.atomicResponse.valid.poke(false.B); d.io.cancel.poke(false.B)
      }
      val ops = Seq(AtomicMemoryOp.swap, AtomicMemoryOp.add, AtomicMemoryOp.xor, AtomicMemoryOp.or,
        AtomicMemoryOp.and, AtomicMemoryOp.minu, AtomicMemoryOp.maxu, AtomicMemoryOp.compareSwap)
      for ((op, n) <- ops.zipWithIndex) { begin(operation = n); atomic(op.litValue); result(0, 123) }
      for ((op, n) <- Seq(AtomicMemoryOp.min -> 5, AtomicMemoryOp.max -> 6)) {
        begin(format = "R32I", operation = n); atomic(op.litValue); result(0, 123)
      }
      for ((readable, writable) <- Seq(false -> true, true -> false, false -> false)) {
        begin(readable = readable, writable = writable); result(ShaderImageAccessStatus.permission)
        d.io.atomicRequest.valid.expect(false.B)
      }
      begin(format = "RGBA32UI"); result(ShaderImageAccessStatus.shaderType)
      begin(qualifier = false); result(ShaderImageAccessStatus.shaderType)
      begin(format = "RGBA32UI", bound = false); result(0); d.io.atomicRequest.valid.expect(false.B)
      begin(base = 0x1001); result(ShaderImageAccessStatus.bounds); d.io.atomicRequest.valid.expect(false.B)
      begin(x = BigInt("ffffffff", 16)); result(0); d.io.atomicRequest.valid.expect(false.B)
      begin(); atomic(AtomicMemoryOp.add.litValue, cancel = true); result(ShaderImageAccessStatus.cancelled)
      begin(); atomic(AtomicMemoryOp.add.litValue, cancel = true, fault = true); result(ShaderImageAccessStatus.memory, fault = true)
      begin(); atomic(AtomicMemoryOp.add.litValue); result(0, 123)
    }
  }
}
