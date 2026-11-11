package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureAxisWrapSpec extends AnyFlatSpec {
  it should "preserve independent repeat and clamp axes across stalls" in {
    case class Sample(u: Long, v: Long, clamp: Boolean, flip: Boolean, nearest: Boolean,
                      addresses: Seq[Int], weights: Seq[Int])
    val cases = Seq(
      Sample(65536,65536,true,true,true,Seq.fill(4)(1),Seq(65536,0,0,0)),
      Sample(65536,65536,false,true,true,Seq.fill(4)(2),Seq(65536,0,0,0)),
      Sample(65536,65536,true,false,true,Seq.fill(4)(3),Seq(65536,0,0,0)),
      Sample(65536,65536,false,false,true,Seq.fill(4)(0),Seq(65536,0,0,0)),
      Sample(0,0,true,true,false,Seq(2,3,0,1),Seq(32768,0,32768,0)),
      Sample(0,0,false,true,false,Seq(1,0,3,2),Seq(32768,32768,0,0)))
    simulate(new TextureCoordinatePipeline(2, false, true, axisWrapInput = true)) { dut =>
      dut.io.in.valid.poke(false.B); dut.io.out.ready.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      for (sample <- cases) {
        dut.io.in.bits.u.poke(sample.u.U); dut.io.in.bits.v.poke(sample.v.U)
        dut.io.in.bits.base.poke(0x1000.U)
        dut.io.in.bits.texWidth.poke(2.U); dut.io.in.bits.texHeight.poke(2.U)
        dut.io.in.bits.clamp.poke(sample.clamp.B)
        dut.io.in.bits.wrapVFlip.get.poke(sample.flip.B)
        dut.io.in.bits.nearest.get.poke(sample.nearest.B)
        dut.io.in.valid.poke(true.B)
        var guard = 0
        while (!dut.io.in.ready.peek().litToBoolean && guard < 100) { dut.clock.step(); guard += 1 }
        assert(guard < 100)
        dut.clock.step(); dut.io.in.valid.poke(false.B)
        dut.io.in.bits.wrapVFlip.get.poke((!sample.flip).B)
        dut.io.in.bits.clamp.poke((!sample.clamp).B)
        guard = 0
        while (!dut.io.out.valid.peek().litToBoolean && guard < 100) { dut.clock.step(); guard += 1 }
        assert(guard < 100)
        val held = dut.io.out.bits.peek().litValue
        dut.clock.step(3); dut.io.out.valid.expect(true.B)
        assert(dut.io.out.bits.peek().litValue == held)
        for (tap <- 0 until 4) {
          dut.io.out.bits.sample.addresses(tap).expect((0x1000 + sample.addresses(tap)*4).U)
          dut.io.out.bits.sample.weights(tap).expect(sample.weights(tap).U)
        }
        dut.io.out.ready.poke(true.B); dut.clock.step(); dut.io.out.ready.poke(false.B)
      }
    }
  }
}
