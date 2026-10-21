package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PipelinedSignedDividerSpec extends AnyFlatSpec {
  private def signed(value: BigInt, width: Int): BigInt = {
    val low = value & ((BigInt(1) << width) - 1)
    if (low.testBit(width - 1)) low - (BigInt(1) << width) else low
  }
  for ((n, d, q, group) <- Seq((1, 1, 1, 1), (5, 3, 3, 1), (32, 32, 32, 8), (129, 96, 32, 8))) {
    it should s"retain exact wrapped signed division and stream every cycle at $n/$d/$q group$group" in {
      simulate(new PipelinedSignedDivider(n, d, q, group)) { dut =>
        dut.io.in.valid.poke(false.B); dut.io.in.bits.numerator.poke(0.S); dut.io.in.bits.denominator.poke((-1).S)
        dut.io.out.ready.poke(true.B); dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val random = new scala.util.Random(0xd1f131 + n)
        def limits(w: Int): Seq[BigInt] = Seq(-(BigInt(1) << (w - 1)), BigInt(-1), BigInt(0),
          BigInt(1), (BigInt(1) << (w - 1)) - 1).map(signed(_, w)).distinct
        val boundaries = if (n <= 5) {
          for (a <- -(1 << (n - 1)) until (1 << (n - 1)); b <- -(1 << (d - 1)) until (1 << (d - 1)))
            yield (BigInt(a), BigInt(b))
        } else for (a <- limits(n); b <- limits(d)) yield (a, b)
        val cases = boundaries ++ Seq.fill(384)((signed(BigInt(n, random), n), signed(BigInt(d, random), d))) ++
          (if (n > q) Seq((BigInt(1) << (n - 2), BigInt(1)), (-(BigInt(1) << (n - 2)), BigInt(1))) else Seq.empty)
        val expected = mutable.Queue.empty[(BigInt, Boolean, Boolean)]
        var offered = 0; var retired = 0; var cycle = 0; var held = Option.empty[BigInt]
        val accepts = mutable.ArrayBuffer.empty[Int]; val outputs = mutable.ArrayBuffer.empty[Int]
        val warmEnd = n + 2 + 256
        while (retired < cases.size && cycle < cases.size * 5 + 4 * (n + 2)) {
          val consume = cycle < warmEnd || cycle % 13 < 7
          dut.io.out.ready.poke(consume.B); dut.io.in.valid.poke((offered < cases.size).B)
          if (offered < cases.size) {
            dut.io.in.bits.numerator.poke(cases(offered)._1.S(n.W)); dut.io.in.bits.denominator.poke(cases(offered)._2.S(d.W))
          }
          if (dut.io.out.valid.peek().litToBoolean) {
            assert(expected.nonEmpty)
            val result = (dut.io.out.bits.quotient.peek().litValue, dut.io.out.bits.divideByZero.peek().litToBoolean,
              dut.io.out.bits.magnitudeOverflow.peek().litToBoolean)
            assert(result == expected.front, s"division mismatch $n/$d/$q cycle=$cycle observed=$result expected=${expected.front}")
            val bits = dut.io.out.bits.peek().litValue; held.foreach(old => assert(old == bits))
            held = if (consume) None else Some(bits)
            if (consume) { expected.dequeue(); retired += 1; outputs += cycle }
          } else assert(held.isEmpty)
          if (offered < cases.size && dut.io.in.ready.peek().litToBoolean) {
            val (a, b) = cases(offered); val quotient = if (b == 0) BigInt(0) else a / b
            expected.enqueue((signed(quotient, q), b == 0, b != 0 && quotient.abs >= (BigInt(1) << q)))
            offered += 1; accepts += cycle
          }
          dut.clock.step(); cycle += 1
        }
        assert(offered == cases.size && retired == cases.size && expected.isEmpty)
        assert(accepts.take(128) == (0 until 128))
        assert(outputs.take(128) == (n + 2 until n + 2 + 128))
        info(s"PIPELINED_SIGNED_DIVIDE n=$n d=$d q=$q group=$group checked=$retired inputII1=true outputII1=true lowBitsWrap=true stallsStable=true latency=${n+2}")
        // Reset a full pipeline with a blocked public result, then check that
        // none of its old packets leak into a fresh stream.
        dut.io.out.ready.poke(false.B)
        dut.io.in.valid.poke(true.B); dut.io.in.bits.numerator.poke((-1).S(n.W)); dut.io.in.bits.denominator.poke((-1).S(d.W))
        dut.clock.step(n + 4); dut.io.out.valid.expect(true.B)
        val old = dut.io.out.bits.peek().litValue
        for (_ <- 0 until 9) { assert(dut.io.out.bits.peek().litValue == old); dut.clock.step() }
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.io.in.valid.poke(false.B)
        dut.io.out.ready.poke(true.B)
        for (_ <- 0 until n + 5) { dut.io.out.valid.expect(false.B); dut.clock.step() }
        dut.io.in.valid.poke(true.B); dut.io.in.ready.expect(true.B)
        dut.io.in.bits.numerator.poke(0.S); dut.clock.step(); dut.io.in.valid.poke(false.B)
        dut.clock.step(n + 1); dut.io.out.valid.expect(true.B); dut.io.out.bits.quotient.expect(0.S)
        dut.clock.step(); dut.io.out.valid.expect(false.B)
      }
    }
  }
}
