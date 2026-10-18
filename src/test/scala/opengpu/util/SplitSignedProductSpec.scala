package opengpu.util

import chisel3._
import circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class SplitSignedProductHarness(width: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(width.W))
    val b = Input(SInt(width.W))
    val result = Output(SInt((2 * width).W))
  })
  io.result := SplitSignedProduct.join(SplitSignedProduct.split(io.a, io.b))
}

class SplitSignedProductJoinProofHarness extends Module {
  val io = IO(new Bundle {
    val parts = Input(new SignedProductParts(34))
    val equivalent = Output(Bool())
  })
  val reference = (io.parts.high.pad(68) << 34) +&
    (io.parts.midA.pad(68) << 17) +& (io.parts.midB.pad(68) << 17) +&
    io.parts.low.zext.pad(68)
  io.equivalent := SplitSignedProduct.join(io.parts) === reference(67, 0).asSInt
}

object EmitSplitSignedProductJoinProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new SplitSignedProductJoinProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

class SplitSignedProductSpec extends AnyFlatSpec {
  behavior of "Equal-width signed split multiplication"
  for (width <- Seq(2, 17, 34)) {
    it should s"preserve full products across signed half boundaries at width$width" in {
      simulate(new SplitSignedProductHarness(width)) { dut =>
        val random = new scala.util.Random(0x5f17 + width)
        def halves(bits: Int): Seq[BigInt] = Seq[BigInt](0, 1,
          (BigInt(1) << (bits - 1)) - 1, BigInt(1) << (bits - 1),
          (BigInt(1) << bits) - 1).distinct
        def signed(raw: BigInt): BigInt =
          if (raw.testBit(width - 1)) raw - (BigInt(1) << width) else raw
        val half = width / 2
        val words = for (hi <- halves(width - half); lo <- halves(half))
          yield signed((hi << half) | lo)
        val cases = (for (a <- words; b <- words) yield (a, b)) ++
          Seq.fill(1024)((signed(BigInt(width, random)), signed(BigInt(width, random))))
        for ((a, b) <- cases) {
          dut.io.a.poke(a.S); dut.io.b.poke(b.S); dut.io.result.expect((a * b).S)
        }
        info(s"operandBits=$width exactProducts=${cases.size}")
      }
    }
  }
}
