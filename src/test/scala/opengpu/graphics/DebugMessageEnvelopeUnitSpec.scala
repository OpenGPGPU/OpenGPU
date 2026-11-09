package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class DebugMessageEnvelopeUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "DebugMessageEnvelopeUnit"
  it should "attach timestamps and valid group labels to debug records" in {
    simulate(new DebugMessageEnvelopeUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val q=d.io.in.bits;zero(q);q.id.poke("h12345678".U);q.source.poke(4.U);q.kind.poke(9.U);q.severity.poke(3.U)
      q.length.poke(9.U);for(i<-0 until 8)q.words(i).poke((BigInt(0x1000)+i).U)
      d.io.timestamp.poke(BigInt("123456789abcdef0",16).U)
      d.io.label.objectType.poke(7.U);d.io.label.objectId.poke("hdeadbeef".U);d.io.labelValid.poke(true.B)
      d.io.in.valid.poke(true.B);d.io.out.ready.poke(false.B)
      assert(d.io.out.valid.peek().litToBoolean && !d.io.in.ready.peek().litToBoolean)
      val o=d.io.out.bits
      assert(o.labelValid.peek().litToBoolean)
      assert(o.timestamp.peek().litValue==BigInt("123456789abcdef0",16))
      assert(o.label.objectType.peek().litValue==7 && o.label.objectId.peek().litValue==BigInt("deadbeef",16))
      assert(o.message.id.peek().litValue==BigInt("12345678",16))
      assert(o.message.length.peek().litValue==9)
      val held=o.timestamp.peek().litValue;d.clock.step();assert(o.timestamp.peek().litValue==held)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.io.labelValid.poke(false.B);d.io.label.objectId.poke("hffffffff".U)
      d.io.in.valid.poke(true.B)
      assert(!d.io.out.bits.labelValid.peek().litToBoolean)
      assert(d.io.out.bits.label.objectId.peek().litValue==0)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      d.io.labelValid.poke(true.B);d.io.label.objectType.poke(0.U);d.io.label.objectId.poke(5.U)
      d.io.in.valid.poke(true.B)
      assert(!d.io.out.bits.labelValid.peek().litToBoolean,"zero object type must not attach a label")
      d.io.in.valid.poke(false.B)
      info("DEBUG_ENVELOPE timestamp=true labelAttach=true labelCleared=true zeroTypeRejected=true backpressure=true")
    }
  }
}
