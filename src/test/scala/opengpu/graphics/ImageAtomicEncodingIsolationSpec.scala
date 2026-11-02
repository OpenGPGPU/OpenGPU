package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.FullInstructionDecoder
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ImageAtomicEncodingProbe extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W)); val atomic = Output(Bool()); val legal = Output(Bool())
  })
  val decoder = Module(new FullInstructionDecoder(GpuConfig(), imageAvailable = true))
  decoder.io.instruction := io.instruction; decoder.io.imageEnabled.get := true.B
  io.atomic := ShaderImageOpcode.atomic(io.instruction); io.legal := decoder.io.decoded.legal
}

class ImageAtomicEncodingIsolationSpec extends AnyFlatSpec {
  behavior of "Internal image atomic encoding"
  it should "avoid existing quad derivatives and remain disabled in the production decoder" in {
    simulate(new ImageAtomicEncodingProbe) { d =>
      for (vm <- 0 to 1; vs1 <- Seq(0, 3); vs2 <- Seq(0, 7); rd <- Seq(0, 5)) {
        def instruction(function: Int, subop: Int): BigInt =
          (BigInt(function) << 26) | (BigInt(vm) << 25) | (BigInt(vs2) << 20) |
            (BigInt(vs1) << 15) | (BigInt(subop) << 12) | (BigInt(rd) << 7) | 0x2b
        for (quad <- 12 to 13) {
          d.io.instruction.poke(instruction(quad, 0).U)
          d.io.atomic.expect(false.B); d.io.legal.expect(true.B)
        }
        for (subop <- 0 to 7) {
          d.io.instruction.poke(instruction(23, subop).U)
          d.io.atomic.expect(true.B); d.io.legal.expect(false.B)
        }
      }
    }
  }
}
