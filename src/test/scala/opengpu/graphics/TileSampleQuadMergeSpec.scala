package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileSampleQuadMergeSpec extends AnyFlatSpec {
  it should "retain already offered reads and writes across cancellation and release every reservation once" in {
    simulate(new TileSampleQuadMerge) { d=>
      d.io.begin.poke(false.B);d.io.sampleMode.poke(0.U);d.io.cancel.poke(false.B)
      d.io.in.valid.poke(false.B);d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits));d.io.out.ready.poke(false.B)
      for(p <- 0 until 4) {
        d.io.read(p).ready.poke(false.B);d.io.write(p).ready.poke(false.B)
        d.io.response(p).valid.poke(false.B);d.io.response(p).bits.poke(0.U.asTypeOf(d.io.response(p).bits))
      }
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      def begin(): Unit = {
        d.io.drained.expect(true.B);d.io.cancel.poke(false.B)
        d.io.begin.poke(true.B);d.clock.step();d.io.begin.poke(false.B)
      }
      def submit(ticket: Int): Unit = {
        val b=d.io.in.bits;b.mask.poke(15.U);b.ticket.poke(ticket.U)
        val indices=Seq(0,1,16,17)
        for(p <- 0 until 4){b.index(p).poke(indices(p).U);b.color(p).poke((0x12345670L+p).U);b.depth(p).poke((0x123450+p).U)}
        b.state.depthWriteEnable.poke(true.B);b.state.colorMask.poke(15.U)
        d.io.in.valid.poke(true.B);d.io.in.ready.expect(true.B);d.clock.step();d.io.in.valid.poke(false.B)
      }
      def returnReads(): Unit = {
        for(p <- 0 until 4) {
          val r=d.io.read(p);r.valid.expect(true.B)
          val b=d.io.response(p).bits;b.index.poke(r.bits.index.peek());b.ticket.poke(r.bits.ticket.peek())
          b.color.poke(0x8899aabbL.U);b.depthStencil.poke(0x7fabcdefL.U)
          r.ready.poke(true.B)
        }
        d.clock.step()
        for(p <- 0 until 4)d.io.response(p).valid.poke(true.B)
        for(p <- 0 until 4)d.io.response(p).ready.expect(true.B)
        d.clock.step()
        for(p <- 0 until 4)d.io.response(p).valid.poke(false.B)
      }
      def completion(ticket: Int,canceled: Int,writes: Int): Unit = {
        var wait=0
        while(!d.io.out.valid.peek().litToBoolean && wait<20){d.clock.step();wait+=1}
        d.io.out.valid.expect(true.B);val b=d.io.out.bits
        b.ticket.expect(ticket.U);b.mask.expect(15.U);b.canceled.expect(canceled.U)
        b.colorWrites.expect(writes.U);b.depthStencilWrites.expect(writes.U)
        val held=b.peek().litValue
        for(_ <- 0 until 4){d.clock.step();d.io.out.valid.expect(true.B);assert(b.peek().litValue==held)}
        d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.drained.expect(true.B)
      }
      begin();submit(31)
      val reads=(0 until 4).map(p=>d.io.read(p).bits.peek().litValue)
      d.clock.step() // Each stalled read is now an externally offered owner.
      d.io.cancel.poke(true.B)
      for(_ <- 0 until 4) {
        for(p <- 0 until 4){d.io.read(p).valid.expect(true.B);assert(d.io.read(p).bits.peek().litValue==reads(p))}
        d.clock.step()
      }
      returnReads();completion(31,15,0)
      begin();submit(32);returnReads()
      var wait=0
      while(!d.io.write(0).valid.peek().litToBoolean && wait<20){d.clock.step();wait+=1}
      for(p <- 0 until 4)d.io.write(p).valid.expect(true.B)
      val writes=(0 until 4).map(p=>d.io.write(p).bits.peek().litValue)
      d.clock.step() // Each stalled write must survive subsequent cancellation.
      d.io.cancel.poke(true.B)
      for(_ <- 0 until 4) {
        for(p <- 0 until 4){d.io.write(p).valid.expect(true.B);assert(d.io.write(p).bits.peek().litValue==writes(p))}
        d.clock.step()
      }
      for(p <- 0 until 4)d.io.write(p).ready.poke(true.B)
      d.io.committedSamples.expect(4.U);d.clock.step();completion(32,0,15)
      begin();submit(33) // Same addresses are available after both cancellation paths.
      returnReads();completion(33,0,15)
    }
  }
}
