package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PipelinedQuadTexUVSpec extends AnyFlatSpec {
  it should "own full quads and sample depths across exact helper UV arithmetic, stalls and reset" in {
    val config = GraphicsConfig()
    val random = new scala.util.Random(0x4a16b5L)
    val mask = (BigInt(1) << 32) - 1
    def signed(value: BigInt, width: Int): BigInt = {
      val bits = value & ((BigInt(1) << width) - 1)
      if (bits.testBit(width - 1)) bits - (BigInt(1) << width) else bits
    }
    case class Input(edges: Vector[Vector[BigInt]], inverse: Vector[BigInt], u: Vector[BigInt], v: Vector[BigInt]) {
      def denominator(k: Int): BigInt = signed(edges(k).zip(inverse).map { case (e, w) => e * w }.sum, 96)
      def result(k: Int, values: Vector[BigInt]): BigInt = {
        val num = signed(edges(k).zip(inverse).zip(values).map { case ((e, w), uv) => e * w * uv }.sum, 129)
        if (denominator(k) == 0) BigInt(0) else (num / denominator(k)) & mask
      }
    }
    val inputs = Vector.tabulate(384) { i =>
      val edges = Vector.tabulate(4) { k =>
        if ((i + k) % 19 == 0) Vector.fill(3)(BigInt(0))
        else if (i % 17 == 0) Vector.fill(3)(-(BigInt(1) << 63))
        else Vector.fill(3)(signed(BigInt(64, random), 64))
      }
      val inverse = if (i % 17 == 0) Vector.fill(3)(-(BigInt(1) << 31))
        else Vector.fill(3)(signed(BigInt(32, random), 32))
      Input(edges, inverse, Vector(mask, BigInt(32, random), BigInt(0)),
        Vector(BigInt(0), mask, BigInt(32, random)))
    }
    simulate(new PipelinedQuadTexUV(config)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits)); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.io.drained.expect(true.B)
      val fragments = inputs.map(_ => BigInt(d.io.in.bits.fragment.getWidth, random))
      val depths = inputs.map(_ => BigInt(d.io.in.bits.depths.getWidth, random))
      val expected = mutable.Queue.empty[(Int, BigInt, BigInt)]
      val accepts = mutable.ArrayBuffer.empty[Int]; val returns = mutable.ArrayBuffer.empty[Int]
      var sent = 0; var received = 0; var cycle = 0
      var held = Option.empty[BigInt]
      def drive(i: Int): Unit = {
        val in = inputs(i)
        d.io.in.bits.fragment.poke(fragments(i).U.asTypeOf(d.io.in.bits.fragment))
        d.io.in.bits.depths.poke(depths(i).U.asTypeOf(d.io.in.bits.depths))
        for (k <- 0 until 4) {
          val f = d.io.in.bits.fragment.lanes(k)
          f.e0.poke(in.edges(k)(0).S); f.e1.poke(in.edges(k)(1).S); f.e2.poke(in.edges(k)(2).S)
          // Include uncovered helper lanes with independent extrapolated UVs.
          f.covered.poke(((i + k) % 3 != 0).B)
        }
        for (v <- 0 until 3) {
          d.io.in.bits.inverseW(v).poke(in.inverse(v).S)
          d.io.in.bits.coordinates(v).u.poke(in.u(v).U)
          d.io.in.bits.coordinates(v).v.poke(in.v(v).U)
        }
      }
      while (received < inputs.size && cycle < 6000) {
        d.io.drained.expect((sent == received).B)
        d.io.in.valid.poke((sent < inputs.size).B)
        if (sent < inputs.size) drive(sent)
        val ready = cycle < 300 || (cycle >= 650 && cycle % 17 >= 9)
        d.io.out.ready.poke(ready.B)
        val valid = d.io.out.valid.peek().litToBoolean
        if (sent < inputs.size && d.io.in.ready.peek().litToBoolean) {
          expected.enqueue((sent, d.io.in.bits.fragment.peek().litValue, d.io.in.bits.depths.peek().litValue))
          sent += 1; accepts += cycle
        }
        held.foreach { bits => assert(valid); assert(d.io.out.bits.peek().litValue == bits) }
        if (valid) {
          val (tag, fragment, depth) = expected.front
          assert(d.io.out.bits.fragment.peek().litValue == fragment)
          assert(d.io.out.bits.depths.peek().litValue == depth)
          for (k <- 0 until 4) {
            d.io.out.bits.coordinates(k).u.expect(inputs(tag).result(k, inputs(tag).u).U)
            d.io.out.bits.coordinates(k).v.expect(inputs(tag).result(k, inputs(tag).v).U)
            d.io.out.bits.divideByZero(k).expect((inputs(tag).denominator(k) == 0).B)
          }
          if (ready) { expected.dequeue(); received += 1; returns += cycle; held = None }
          else held = Some(d.io.out.bits.peek().litValue)
        }
        d.clock.step(); cycle += 1
      }
      assert(received == inputs.size && expected.isEmpty)
      d.io.drained.expect(true.B)
      assert(accepts.take(128).toVector == (0 until 128).toVector)
      assert(returns.take(128).toVector == (d.latency until d.latency + 128).toVector)
      // Reset a full, output-stalled pipeline, then prove healthy reuse with
      // a distinct packet rather than merely observing an empty reset state.
      d.io.out.ready.poke(false.B); d.io.in.valid.poke(true.B); drive(17); d.clock.step(400)
      d.io.in.valid.poke(false.B); d.io.drained.expect(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.io.drained.expect(true.B)
      d.io.out.ready.poke(true.B)
      for (_ <- 0 until 180) { d.io.out.valid.expect(false.B); d.clock.step() }
      drive(23); d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B)
      val reusedFragment = d.io.in.bits.fragment.peek().litValue
      d.clock.step(); d.io.in.valid.poke(false.B)
      var waited = 1
      while (!d.io.out.valid.peek().litToBoolean && waited < 180) { d.clock.step(); waited += 1 }
      assert(waited == d.latency)
      assert(d.io.out.bits.fragment.peek().litValue == reusedFragment)
      for (k <- 0 until 4) {
        d.io.out.bits.coordinates(k).u.expect(inputs(23).result(k, inputs(23).u).U)
        d.io.out.bits.coordinates(k).v.expect(inputs(23).result(k, inputs(23).v).U)
      }
      d.clock.step(); d.io.out.valid.expect(false.B); d.io.drained.expect(true.B)
      info(s"PIPELINED_QUAD_UV packets=$received lanes=${received * 4} latency=${d.latency} II1=true helperUV=true signedEdges64=true unsignedUV32=true modularArithmetic=true completeFragmentAndSampleDepthOwnership=true fullStall=true resetHealthyReuse=true")
    }
  }

  it should "resolve clamped and mirrored signed axes after interpolation while retaining each quad's modes" in {
    val config = GraphicsConfig()
    val random = new scala.util.Random(0x215ab4L)
    val bias = BigInt(1) << 31
    val boundaries = Vector(-bias, BigInt(-131073), BigInt(-131072), BigInt(-65537), BigInt(-65536),
      BigInt(-1), BigInt(0), BigInt(1), BigInt(65535), BigInt(65536), BigInt(65537),
      BigInt(131071), BigInt(131072), bias - 1)
    case class Packet(edges: Vector[Vector[BigInt]], inverse: Vector[BigInt],
                      u: Vector[BigInt], v: Vector[BigInt], modeU: Int, modeV: Int) {
      def expected(lane: Int, values: Vector[BigInt], mode: Int): BigInt = {
        val weights = edges(lane).zip(inverse).map { case (e, w) => e * w }
        val numerator = weights.zip(values).map { case (w, value) => w * value }.sum
        val denominator = weights.sum
        val quotient = numerator / denominator
        if (mode == 1) quotient.max(BigInt(0))
        else if (mode == 2) {
          val floor = if (numerator % denominator != 0 && numerator.signum != denominator.signum)
            quotient - 1 else quotient
          val phase = ((floor % 131072) + 131072) % 131072
          if (phase >= 65536) BigInt(131072) - phase else phase
        } else quotient
      }
    }
    val packets = Vector.tabulate(192) { i =>
      val modeU = if (i < boundaries.size) 2 else i % 3
      val modeV = if (i < boundaries.size) 2 else (i / 3) % 3
      val winding = if ((i / 9) % 2 != 0) -1 else 1
      def axis(mode: Int): Vector[BigInt] = Vector.tabulate(3) { j =>
        if (i < boundaries.size) boundaries(i)
        else if (mode != 0 && (i + j) % 3 == 0) boundaries((i + 2*j) % boundaries.size)
        else if (mode != 0) BigInt(32, random) - bias
        else BigInt(32, random)
      }
      Packet(Vector.fill(4)(Vector(BigInt(random.nextInt(10000) + 1),
        BigInt(random.nextInt(10000)), BigInt(random.nextInt(10000))).map(_ * winding)),
        Vector.fill(3)(BigInt(random.nextInt(262144) + 1)),
        axis(modeU), axis(modeV), modeU, modeV)
    }
    simulate(new PipelinedQuadTexUV(config, biasedClamp = true, mirroredRepeat = true)) { d =>
      d.io.in.valid.poke(false.B); d.io.in.bits.poke(0.U.asTypeOf(d.io.in.bits)); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val expected = mutable.Queue.empty[Int]
      var sent = 0; var received = 0; var cycle = 0
      var held = Option.empty[BigInt]
      while (received < packets.size && cycle < 5000) {
        d.io.in.valid.poke((sent < packets.size).B)
        if (sent < packets.size) {
          val p = packets(sent)
          d.io.in.bits.clampBiasedU.get.poke((p.modeU == 1).B)
          d.io.in.bits.clampBiasedV.get.poke((p.modeV == 1).B)
          d.io.in.bits.mirrorU.get.poke((p.modeU == 2).B)
          d.io.in.bits.mirrorV.get.poke((p.modeV == 2).B)
          d.io.in.bits.depths(0)(0).poke(sent.U)
          for (lane <- 0 until 4) {
            val f = d.io.in.bits.fragment.lanes(lane)
            f.e0.poke(p.edges(lane)(0).S); f.e1.poke(p.edges(lane)(1).S); f.e2.poke(p.edges(lane)(2).S)
            f.covered.poke(true.B)
          }
          for (vertex <- 0 until 3) {
            d.io.in.bits.inverseW(vertex).poke(p.inverse(vertex).S)
            d.io.in.bits.coordinates(vertex).u.poke((p.u(vertex) + (if (p.modeU != 0) bias else 0)).U)
            d.io.in.bits.coordinates(vertex).v.poke((p.v(vertex) + (if (p.modeV != 0) bias else 0)).U)
          }
        }
        val ready = cycle < 250 || (cycle >= 450 && cycle % 13 >= 6)
        d.io.out.ready.poke(ready.B)
        if (sent < packets.size && d.io.in.ready.peek().litToBoolean) { expected.enqueue(sent); sent += 1 }
        val valid = d.io.out.valid.peek().litToBoolean
        held.foreach { bits => assert(valid && d.io.out.bits.peek().litValue == bits) }
        if (valid) {
          val tag = expected.front
          val p = packets(tag)
          d.io.out.bits.depths(0)(0).expect(tag.U)
          for (lane <- 0 until 4) {
            d.io.out.bits.coordinates(lane).u.expect(p.expected(lane, p.u, p.modeU).U)
            d.io.out.bits.coordinates(lane).v.expect(p.expected(lane, p.v, p.modeV).U)
            d.io.out.bits.divideByZero(lane).expect(false.B)
          }
          if (ready) { expected.dequeue(); received += 1; held = None }
          else held = Some(d.io.out.bits.peek().litValue)
        }
        d.clock.step(); cycle += 1
      }
      assert(received == packets.size && expected.isEmpty)
      d.io.drained.expect(true.B)
    }
  }
}
