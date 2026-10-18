package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

private class Unorm8ProductHarness extends Module {
  val io=IO(new Bundle {
    val a=Input(UInt(8.W)); val b=Input(UInt(8.W)); val product=Output(UInt(8.W))
  })
  io.product:=Unorm8Product(io.a,io.b)
}

class Unorm8ProductSpec extends AnyFlatSpec {
  it should "match rounded division by 255 for every input pair, including both identity endpoints" in {
    simulate(new Unorm8ProductHarness) { d =>
      for (a <- 0 until 256; b <- 0 until 256) {
        d.io.a.poke(a.U); d.io.b.poke(b.U)
        d.io.product.expect(((a*b+127)/255).U)
      }
    }
  }
}
