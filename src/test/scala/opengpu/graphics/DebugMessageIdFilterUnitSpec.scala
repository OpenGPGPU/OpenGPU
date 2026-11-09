package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class DebugMessageIdFilterUnitSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  behavior of "DebugMessageIdFilterUnit"
  it should "apply per-message-ID filter lists without blocking dropped records" in {
    simulate(new DebugMessageIdFilterUnit){d=>zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def poke(id: BigInt): Unit={val q=d.io.in.bits;zero(q);q.id.poke(id.U);q.severity.poke(2.U);q.kind.poke(5.U);q.source.poke(3.U);q.length.poke(1.U);q.words(0).poke(id.U)}
      def config(enable: Boolean,entries: Seq[(Int,BigInt)]): Unit={
        d.io.enable.poke(enable.B)
        val mask=entries.foldLeft(0){case(a,(i,_))=>a|(1<<i)}
        for(i<-0 until 8){
          val e=entries.find(_._1==i)
          d.io.ids(i).poke(e.map(_._2).getOrElse(BigInt(0)).U)
        }
        d.io.valid.poke(mask.U)
      }
      config(false,Seq(1->BigInt(0x10),3->BigInt(0x20)))
      poke(0x99);d.io.in.valid.poke(true.B);d.io.out.ready.poke(false.B)
      assert(d.io.out.valid.peek().litToBoolean)
      assert(!d.io.in.ready.peek().litToBoolean)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      config(true,Seq(1->BigInt(0x10),3->BigInt(0x20),5->BigInt(0)))
      d.io.out.ready.poke(false.B)
      poke(0x10);d.io.in.valid.poke(true.B)
      assert(d.io.out.valid.peek().litToBoolean && !d.io.in.ready.peek().litToBoolean)
      val held=d.io.out.bits.id.peek().litValue
      d.clock.step();assert(d.io.out.bits.id.peek().litValue==held)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      poke(0x20);d.io.in.valid.poke(true.B)
      assert(d.io.out.valid.peek().litToBoolean)
      d.io.out.ready.poke(true.B);d.clock.step();d.io.in.valid.poke(false.B);d.io.out.ready.poke(false.B)
      poke(0x30);d.io.in.valid.poke(true.B)
      assert(!d.io.out.valid.peek().litToBoolean)
      assert(d.io.in.ready.peek().litToBoolean,"dropped record must be consumed without downstream ready")
      d.io.out.ready.poke(false.B)
      poke(0);d.io.in.valid.poke(true.B)
      assert(!d.io.out.valid.peek().litToBoolean,"reserved ID zero never matches")
      assert(d.io.in.ready.peek().litToBoolean)
      d.io.in.valid.poke(false.B)
      config(true,Seq())
      poke(0x77);d.io.in.valid.poke(true.B)
      assert(!d.io.out.valid.peek().litToBoolean && d.io.in.ready.peek().litToBoolean)
      d.io.in.valid.poke(false.B)
      info("DEBUG_ID_FILTER passAll=true listMatch=true silentDrop=true backpressureHold=true zeroReserved=true emptyList=true")
    }
  }
}
