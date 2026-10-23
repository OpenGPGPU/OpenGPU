package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PipelinedSignedProductSpec extends AnyFlatSpec {
  for ((a, b, registered) <- Seq((1, 1, false), (17, 33, false), (64, 32, false), (96, 33, false),
      (1, 1, true), (67, 67, true))) {
    it should s"stream exact signed${a}x$b products through stalls and reset${if(registered) " with registered final carries" else ""}" in {
      val random = new scala.util.Random(a * 193L + b)
      def signed(width: Int): BigInt = BigInt(width, random) - (BigInt(1) << (width - 1))
      def limits(width: Int) = Vector(-(BigInt(1) << (width - 1)), BigInt(-1), BigInt(0), (BigInt(1) << (width - 1)) - 1)
      val cases = limits(a).flatMap(x => limits(b).map(y => (x, y))) ++ Vector.fill(320)((signed(a), signed(b)))
      simulate(new PipelinedSignedProduct(a, b, registerFinalCarry=registered)) { d =>
        d.io.in.valid.poke(false.B); d.io.in.bits.a.poke(0.S); d.io.in.bits.b.poke(0.S)
        d.io.out.ready.poke(false.B); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val expected = mutable.Queue.empty[BigInt]
        var sent = 0; var received = 0; var cycle = 0
        var held: Option[BigInt] = None
        val accepted = mutable.ArrayBuffer.empty[Int]; val returned = mutable.ArrayBuffer.empty[Int]
        while (received < cases.size && cycle < 4000) {
          d.io.in.valid.poke((sent < cases.size).B)
          if (sent < cases.size) { d.io.in.bits.a.poke(cases(sent)._1.S(a.W)); d.io.in.bits.b.poke(cases(sent)._2.S(b.W)) }
          val ready = cycle < 160 || cycle % 13 >= 6
          d.io.out.ready.poke(ready.B)
          val put = sent < cases.size && d.io.in.ready.peek().litToBoolean
          val valid = d.io.out.valid.peek().litToBoolean
          if (put) { expected.enqueue(cases(sent)._1 * cases(sent)._2); accepted += cycle; sent += 1 }
          held.foreach(v => { assert(valid); d.io.out.bits.expect(v.S((a + b).W)) })
          if (valid) {
            d.io.out.bits.expect(expected.front.S((a + b).W))
            if (ready) { expected.dequeue(); received += 1; returned += cycle; held = None }
            else held = Some(d.io.out.bits.peek().litValue)
          }
          d.clock.step(); cycle += 1
        }
        assert(received == cases.size && expected.isEmpty)
        assert(accepted.take(128).toVector == (0 until 128).toVector)
        assert(returned.take(128).toVector == (d.latency until d.latency + 128).toVector)
        d.io.in.valid.poke(true.B); d.io.in.bits.a.poke((-1).S); d.io.in.bits.b.poke((-1).S)
        d.io.out.ready.poke(false.B); d.clock.step(d.latency + 4)
        d.io.in.valid.poke(false.B); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.out.ready.poke(true.B)
        for (_ <- 0 until d.latency + 4) { d.io.out.valid.expect(false.B); d.clock.step() }
        info(s"PIPELINED_SIGNED_PRODUCT a=$a b=$b checked=$received II1=true stallsStable=true resetDrained=true latency=${d.latency}")
      }
    }
  }
}
