package opengpu.util

import chisel3._
import circt.stage.ChiselStage
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class SignedProduct32Harness extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(32.W))
    val b = Input(SInt(32.W))
    val result = Output(SInt(64.W))
  })
  io.result := SignedProduct32.join(SignedProduct32.split(io.a, io.b))
}

class SignedProduct32JoinProofHarness extends Module {
  val io = IO(new Bundle {
    val parts = Input(new SignedProduct32Parts)
    val equivalent = Output(Bool())
  })
  val reference = (io.parts.high.pad(64) << 32) +&
    (io.parts.midA.pad(64) << 16) +& (io.parts.midB.pad(64) << 16) +&
    io.parts.low.zext.pad(64)
  io.equivalent := SignedProduct32.join(io.parts) === reference(63, 0).asSInt
}

class SignedProduct32FullProofHarness extends Module {
  val io = IO(new Bundle {
    val a = Input(SInt(32.W))
    val b = Input(SInt(32.W))
    val equivalent = Output(Bool())
  })
  io.equivalent := SignedProduct32.join(SignedProduct32.split(io.a, io.b)) === (io.a * io.b)
}

object EmitSignedProduct32JoinProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new SignedProduct32JoinProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

object EmitSignedProduct32FullProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new SignedProduct32FullProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}

class SignedProduct32Spec extends AnyFlatSpec {
  behavior of "Exact split signed32 multiplication"

  it should "match full signed products across half-word sign and carry boundaries" in {
    simulate(new SignedProduct32Harness) { dut =>
      val random = new scala.util.Random(0x3216)
      val halves = Seq(0, 1, 0x7fff, 0x8000, 0xffff)
      val words = for (hi <- halves; lo <- halves) yield {
        val raw = (BigInt(hi) << 16) | lo
        if (raw.testBit(31)) raw - (BigInt(1) << 32) else raw
      }
      val boundaries = for (a <- words; b <- words) yield (a, b)
      val cases = boundaries ++ Seq.fill(2048)((BigInt(random.nextInt()), BigInt(random.nextInt())))
      for ((a, b) <- cases) {
        dut.io.a.poke(a.S); dut.io.b.poke(b.S)
        dut.io.result.expect((a * b).S)
      }
      info(s"signed32Products=${cases.size}")
    }
  }
}
