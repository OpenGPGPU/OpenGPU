package opengpu.util

import chisel3._
import circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

object EmitIterativeSignedDivider {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new IterativeSignedDivider(68, 34),
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

class IterativeSignedDividerSpec extends AnyFlatSpec {
  behavior of "Exact iterative signed division"

  private def signed(value: BigInt, width: Int): BigInt = {
    val low = value & ((BigInt(1) << width) - 1)
    if (low.testBit(width - 1)) low - (BigInt(1) << width) else low
  }

  for ((n, d) <- Seq((1, 1), (4, 3), (32, 32), (68, 34))) {
    it should s"preserve signed quotient, stalls and order for $n by $d bits" in {
      simulate(new IterativeSignedDivider(n, d)) { dut =>
        dut.io.in.valid.poke(false.B); dut.io.out.ready.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        val random = new scala.util.Random(0xd171 + n)
        def limits(w: Int): Seq[BigInt] =
          Seq[BigInt](-(BigInt(1) << (w - 1)), -1, 0, 1, (BigInt(1) << (w - 1)) - 1)
            .map(signed(_, w)).distinct
        val cases = if (n <= 4) {
          for (a <- -(1 << (n - 1)) until (1 << (n - 1));
               b <- -(1 << (d - 1)) until (1 << (d - 1))) yield (BigInt(a), BigInt(b))
        } else {
          (for (a <- limits(n); b <- limits(d)) yield (a, b)) ++
            Seq.fill(160)((signed(BigInt(n, random), n), signed(BigInt(d, random), d)))
        }
        val expected = collection.mutable.Queue.empty[(BigInt, Boolean)]
        var offered = 0; var retired = 0; var cycles = 0
        var heldOutput = Option.empty[(BigInt, Boolean)]
        while (retired < cases.size && cycles < cases.size * (n + 15)) {
          val consume = cycles % 11 >= 4
          dut.io.out.ready.poke(consume.B)
          dut.io.in.valid.poke((offered < cases.size).B)
          if (offered < cases.size) {
            dut.io.in.bits.numerator.poke(cases(offered)._1.S)
            dut.io.in.bits.denominator.poke(cases(offered)._2.S)
          }
          if (dut.io.out.valid.peek().litToBoolean) {
            val result = (dut.io.out.bits.quotient.peek().litValue,
              dut.io.out.bits.divideByZero.peek().litToBoolean)
            assert(expected.nonEmpty && result == expected.front)
            heldOutput.foreach(previous => assert(result == previous))
            heldOutput = if (consume) None else Some(result)
            if (consume) { expected.dequeue(); retired += 1 }
          } else assert(heldOutput.isEmpty)
          if (offered < cases.size && dut.io.in.ready.peek().litToBoolean) {
            val (a, b) = cases(offered)
            expected.enqueue((if (b == 0) BigInt(0) else signed(a / b, n), b == 0))
            offered += 1
          }
          dut.clock.step(); cycles += 1
        }
        assert(retired == cases.size && expected.isEmpty)
        info(s"signedQuotients=$retired cycles=$cycles numeratorBits=$n denominatorBits=$d")
      }
    }
  }

  it should "cancel an in-flight divide or stalled result on reset and accept fresh work" in {
    simulate(new IterativeSignedDivider(68, 34)) { dut =>
      dut.io.in.valid.poke(false.B); dut.io.out.ready.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      for (resetAfter <- Seq(1, 35, 68, 72)) {
        dut.io.in.bits.numerator.poke((-123456789012345L).S)
        dut.io.in.bits.denominator.poke(37.S)
        dut.io.in.valid.poke(true.B); dut.io.in.ready.expect(true.B)
        dut.clock.step(); dut.io.in.valid.poke(false.B); dut.clock.step(resetAfter)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.out.valid.expect(false.B); dut.io.in.ready.expect(true.B)
        dut.io.in.bits.numerator.poke(9876543210L.S)
        dut.io.in.bits.denominator.poke((-19).S)
        dut.io.in.valid.poke(true.B); dut.clock.step(); dut.io.in.valid.poke(false.B)
        dut.io.out.ready.poke(true.B)
        var cycles = 0
        while (!dut.io.out.valid.peek().litToBoolean && cycles < 72) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 72)
        dut.io.out.bits.quotient.expect((BigInt(9876543210L) / -19).S)
        dut.io.out.bits.divideByZero.expect(false.B)
        dut.clock.step(); dut.io.out.ready.poke(false.B)
      }
    }
  }
}
