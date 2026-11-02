package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderImagePublicationReaderSpec extends AnyFlatSpec {
  private def zero(d: Data): Unit = d match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "publish only complete, owned and canonical fragment image roots" in {
    simulate(new ShaderImagePublicationReader) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def record(context: Int = 7, stage: Int = 1, base: BigInt = 0x2000,
          limit: BigInt = 0x2400, padding: BigInt = 0): BigInt = {
        val words = Array.fill[BigInt](16)(0)
        words(0) = ShaderImagePublicationLayout.magic; words(1) = 64; words(2) = 1
        words(3) = context; words(4) = 11; words(5) = 13; words(6) = stage
        words(7) = 1; words(8) = base; words(9) = limit & 0xffffffffL; words(10) = limit >> 32
        words(15) = padding
        words.zipWithIndex.map { case (w, i) => w << (32 * i) }.sum
      }
      def begin(version: Int = 1, address: BigInt = 0x1000): Unit = {
        d.io.in.bits.publication.version.poke(version.U); d.io.in.bits.publication.address.poke(address.U)
        d.io.in.bits.identity.context.poke(7.U); d.io.in.bits.identity.pass.poke(11.U)
        d.io.in.bits.identity.generation.poke(13.U); d.io.in.valid.poke(true.B)
        d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 30) { d.clock.step(); n += 1 }; assert(p) }
      def reply(data: BigInt, fault: Boolean = false, cancel: Boolean = false): Unit = {
        until(d.io.memoryRequest.valid.peek().litToBoolean)
        d.io.memoryRequest.bits.address.expect("h1000".U)
        val held = d.io.memoryRequest.bits.peek().litValue
        for (_ <- 0 until 3) { d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue == held); d.clock.step() }
        if (cancel) {
          d.io.cancel.poke(true.B)
          d.clock.step(2); d.io.memoryRequest.valid.expect(true.B)
          assert(d.io.memoryRequest.bits.peek().litValue == held)
        }
        d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
        d.io.memoryResponse.bits.transactionId.poke(1.U); d.io.memoryResponse.bits.readData.poke(data.U)
        d.io.memoryResponse.bits.fault.poke(fault.B); d.io.memoryResponse.valid.poke(true.B)
        d.io.memoryResponse.ready.expect(false.B); d.clock.step()
        d.io.memoryResponse.bits.transactionId.poke(0.U)
        d.io.memoryResponse.ready.expect(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
        d.io.cancel.poke(false.B)
      }
      def result(status: Int, enabled: Boolean, base: BigInt = 0x2000, limit: BigInt = 0x2400): Unit = {
        until(d.io.out.valid.peek().litToBoolean); d.io.out.bits.status.expect(status.U)
        d.io.out.bits.task.enabled.expect(enabled.B); d.io.out.bits.task.stage.expect(GraphicsStage.fragment.U)
        if (enabled) {
          d.io.out.bits.task.table.base.expect(base.U); d.io.out.bits.task.table.limit.expect(limit.U)
          d.io.out.bits.task.table.identity.context.expect(7.U); d.io.out.bits.task.table.identity.pass.expect(11.U)
          d.io.out.bits.task.table.identity.generation.expect(13.U)
        }
        val held = d.io.out.bits.peek().litValue
        d.clock.step(3); assert(d.io.out.bits.peek().litValue == held)
        d.io.out.ready.poke(true.B); d.clock.step(); d.io.out.ready.poke(false.B)
      }
      begin(); reply(record()); result(0, true)
      begin(version = 0, address = 0); result(0, false)
      begin(version = 0, address = 64); result(1, false)
      begin(address = 0x1001); result(1, false)
      begin(); reply(record(base = BigInt("fffffc00", 16), limit = BigInt(1) << 32))
      result(0, true, BigInt("fffffc00", 16), BigInt(1) << 32)
      begin(); reply(record(context = 8)); result(5, false)
      begin(); reply(record(stage = GraphicsStage.compute)); result(5, false)
      begin(); reply(record(limit = 0x2300 + 64)); result(2, false)
      begin(); reply(record(padding = 1)); result(1, false)
      begin(); reply(record(), fault = true); result(3, false)
      begin(); reply(record(), cancel = true); result(4, false)
    }
  }
}
