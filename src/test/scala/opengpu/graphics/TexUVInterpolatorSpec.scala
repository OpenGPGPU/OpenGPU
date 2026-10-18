package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TexUVInterpolatorSpec extends AnyFlatSpec {
  behavior of "TexUVInterpolator"

  it should "interpolate the full unsigned UV range with signed helper weights" in {
    val random = new scala.util.Random(0x75a031L)
    val limit = (BigInt(1) << 32) - 1
    type Input = (Vector[BigInt], Vector[BigInt], Vector[BigInt], Vector[BigInt])
    val boundaries: Vector[Input] = Vector(
      (Vector.fill(3)(BigInt(1)), Vector.fill(3)(BigInt(65536)),
        Vector(BigInt("80000000", 16), BigInt(0), BigInt(0)),
        Vector(limit, BigInt(0), BigInt(0))),
      (Vector(BigInt(1), BigInt(2), BigInt(3)), Vector(BigInt(7), BigInt(11), BigInt(13)),
        Vector(limit, BigInt("7fffffff", 16), BigInt("80000000", 16)),
        Vector(BigInt(65536), BigInt(0), limit)),
      (Vector(BigInt(-1), BigInt(2), BigInt(1)), Vector.fill(3)(BigInt(65536)),
        Vector(limit, BigInt(0), BigInt(1)), Vector(BigInt(0), limit, BigInt(1))),
      (Vector.fill(3)((BigInt(1) << 48) - 1), Vector.fill(3)(BigInt(1) << 26),
        Vector(limit, limit, BigInt(0)), Vector(BigInt(0), BigInt("80000000", 16), limit)))
    val cases = boundaries ++ Vector.tabulate(256) { index =>
      val edges = Vector.tabulate(3) { lane =>
        val value = BigInt(1 + random.nextInt(1000000))
        if (index % 3 == 0 && lane == 0) -value else value
      }
      val inverseW = Vector.fill(3)(BigInt(1 + random.nextInt(1 << 26)))
      val u = Vector.fill(3)(BigInt(random.nextInt().toLong & 0xffffffffL))
      val v = Vector.fill(3)(BigInt(random.nextInt().toLong & 0xffffffffL))
      (edges, inverseW, u, v)
    }
    simulate(new TexUVInterpolator(GraphicsConfig())) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      var checked = 0
      for (((edges, inverseW, u, v), index) <- cases.zipWithIndex) {
        val weights = edges.zip(inverseW).map { case (e, w) => e * w }
        val denominator = weights.sum
        assert(denominator != 0)
        Seq(dut.io.e0, dut.io.e1, dut.io.e2).zip(edges).foreach { case (port, value) =>
          port.poke(value.S(64.W))
        }
        Seq(dut.io.invW0, dut.io.invW1, dut.io.invW2).zip(inverseW).foreach { case (port, value) =>
          port.poke(value.S(32.W))
        }
        for ((port, lane) <- Seq(dut.io.uv0, dut.io.uv1, dut.io.uv2).zipWithIndex) {
          port.u.poke(u(lane).U(32.W)); port.v.poke(v(lane).U(32.W))
        }
        dut.clock.step()
        def reference(values: Vector[BigInt]): BigInt =
          (weights.zip(values).map { case (weight, value) => weight * value }.sum / denominator) & limit
        for ((port, values, channel) <- Seq((dut.io.uvPx.u, u, "u"), (dut.io.uvPx.v, v, "v"))) {
          val actual = port.peek().litValue
          val expected = reference(values)
          assert(actual == expected,
            s"$channel mismatch case=$index got=0x${actual.toString(16)} expected=0x${expected.toString(16)}")
          checked += 1
        }
      }
      info(s"cases=${cases.size} exactUnsignedCoordinates=$checked helperWeights=true largeEdges=true")
    }
  }
}
