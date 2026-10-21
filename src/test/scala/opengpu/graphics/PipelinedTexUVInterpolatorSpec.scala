package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PipelinedTexUVInterpolatorSpec extends AnyFlatSpec {
  it should "retain exact full-width UVs and metadata at II1 through full-pipeline stalls and reset" in {
    val config = GraphicsConfig()
    val random = new scala.util.Random(0x72a615L)
    val mask = (BigInt(1) << 32) - 1
    def signed(value: BigInt, width: Int): BigInt = {
      val bits = value & ((BigInt(1) << width) - 1)
      if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
    }
    case class Input(edges: Vector[BigInt], inverse: Vector[BigInt], u: Vector[BigInt], v: Vector[BigInt]) {
      val weights = edges.zip(inverse).map { case (e, w) => e * w }
      val denominator = signed(weights.sum, 96)
      def result(values: Vector[BigInt]): BigInt = if (denominator == 0) BigInt(0)
        else (signed(weights.zip(values).map { case (e, v) => e * v }.sum, 129) / denominator) & mask
    }
    val boundary = Vector(
      Input(Vector.fill(3)(-(BigInt(1) << 63)), Vector.fill(3)(-(BigInt(1) << 31)), Vector(mask, BigInt(0), mask), Vector(BigInt(0), mask, BigInt(1))),
      Input(Vector(BigInt(-1), BigInt(2), BigInt(1)), Vector.fill(3)(BigInt(65536)), Vector(mask, BigInt(0), BigInt(1)), Vector(BigInt(0), mask, BigInt(1))),
      Input(Vector.fill(3)(BigInt(0)), Vector.fill(3)(BigInt(0)), Vector.fill(3)(mask), Vector.fill(3)(mask)))
    val cases = boundary ++ Vector.fill(384) {
      Input(Vector.fill(3)(signed(BigInt(64, random), 64)), Vector.fill(3)(signed(BigInt(32, random), 32)),
        Vector.fill(3)(BigInt(32, random)), Vector.fill(3)(BigInt(32, random)))
    }
    simulate(new PipelinedTexUVInterpolator(config, 32)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits)); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val expected = mutable.Queue.empty[(Int, Input)]
      var sent = 0; var received = 0; var cycle = 0
      var held: Option[(BigInt, BigInt, BigInt, Boolean)] = None
      val accepts = mutable.ArrayBuffer.empty[Int]; val returns = mutable.ArrayBuffer.empty[Int]
      while (received < cases.size && cycle < 6000) {
        d.io.in.valid.poke((sent < cases.size).B)
        if (sent < cases.size) {
          val in = cases(sent)
          for (i <- 0 until 3) {
            d.io.in.bits.edges(i).poke(in.edges(i).S(64.W)); d.io.in.bits.inverseW(i).poke(in.inverse(i).S(32.W))
            d.io.in.bits.coordinates(i).u.poke(in.u(i).U); d.io.in.bits.coordinates(i).v.poke(in.v(i).U)
          }
          d.io.in.bits.payload.poke(sent.U)
        }
        val ready = cycle < 300 || (cycle >= 550 && cycle % 17 >= 9)
        d.io.out.ready.poke(ready.B)
        val put = sent < cases.size && d.io.in.ready.peek().litToBoolean
        val valid = d.io.out.valid.peek().litToBoolean
        if (put) { expected.enqueue((sent, cases(sent))); accepts += cycle; sent += 1 }
        held.foreach { case (tag, u, v, zero) =>
          assert(valid); d.io.out.bits.payload.expect(tag.U)
          d.io.out.bits.coordinates.u.expect(u.U); d.io.out.bits.coordinates.v.expect(v.U)
          d.io.out.bits.divideByZero.expect(zero.B)
        }
        if (valid) {
          val (tag, in) = expected.front
          d.io.out.bits.payload.expect(tag.U)
          d.io.out.bits.coordinates.u.expect(in.result(in.u).U); d.io.out.bits.coordinates.v.expect(in.result(in.v).U)
          d.io.out.bits.divideByZero.expect((in.denominator == 0).B)
          if (ready) { expected.dequeue(); received += 1; returns += cycle; held = None }
          else held = Some((d.io.out.bits.payload.peek().litValue, d.io.out.bits.coordinates.u.peek().litValue, d.io.out.bits.coordinates.v.peek().litValue, d.io.out.bits.divideByZero.peek().litToBoolean))
        }
        d.clock.step(); cycle += 1
      }
      assert(received == cases.size && expected.isEmpty)
      assert(accepts.take(128).toVector == (0 until 128).toVector)
      assert(returns.take(128).sliding(2).forall(p => p(1) == p(0) + 1))
      d.io.out.ready.poke(false.B); d.io.in.valid.poke(true.B); d.clock.step(320)
      d.io.in.valid.poke(false.B); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.io.out.ready.poke(true.B)
      for (_ <- 0 until 320) { d.io.out.valid.expect(false.B); d.clock.step() }
      info(s"PIPELINED_TEX_UV checked=$received firstResult=${returns.head} fullSignedEdges=true unsignedUV32=true modularDenominator=true quotientWrap=true payloadOrdered=true II1=true stallsStable=true resetDrained=true")
    }
  }
}
