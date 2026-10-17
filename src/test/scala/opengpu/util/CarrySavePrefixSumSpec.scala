package opengpu.util

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class CarrySavePrefixSumSpec extends AnyFlatSpec {
  behavior of "exact carry-save/prefix three-term addition"

  for (width <- 1 to 4) {
    it should s"match the complete three-input truth table at width $width" in {
      simulate(new CarrySavePrefixSum(width)) { dut =>
        for (a <- 0 until (1 << width); b <- 0 until (1 << width); c <- 0 until (1 << width)) {
          Seq(a, b, c).zipWithIndex.foreach { case (value, i) => dut.io.terms(i).poke(value.U) }
          dut.io.sum.expect((a + b + c).U)
        }
      }
    }
  }

  for (width <- Seq(16, 32, 64, 66)) {
    it should s"retain overflow bits and signed modular results at width $width" in {
      simulate(new CarrySavePrefixSum(width)) { dut =>
        val mask = (BigInt(1) << width) - 1
        val sign = BigInt(1) << (width - 1)
        val edge = Seq(BigInt(0), BigInt(1), mask, mask - 1, sign, sign - 1)
        val rng = new scala.util.Random(0x3add000L + width)
        val triples = (for (a <- edge; b <- edge; c <- edge) yield Seq(a, b, c)) ++
          (0 until 256).map(_ => Seq.fill(3)(BigInt(width, rng)))
        for (values <- triples) {
          values.zipWithIndex.foreach { case (value, i) => dut.io.terms(i).poke(value.U) }
          dut.io.sum.expect(values.sum.U)
          val signed = values.map(v => if (v.testBit(width - 1)) v - (BigInt(1) << width) else v).sum
          assert((dut.io.sum.peek().litValue & mask) == (signed & mask))
        }
      }
    }
  }
}
