package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TypedReplayLayerSelectionSpec extends AnyFlatSpec {
  it should "select geometry layers and route legacy records to layer zero" in {
    simulate(new TypedReplayLayerSelection){d=>
      d.io.geometry.valid.poke(false.B);d.io.geometry.bits.inputPrimitiveId.poke(0.U)
      d.io.geometry.bits.invocationId.poke(0.U);d.io.geometry.bits.layer.poke("hffffffff".U)
      d.io.selected.poke(0.U);d.io.skip.expect(false.B);d.io.selected.poke(1.U);d.io.skip.expect(true.B)
      d.io.geometry.valid.poke(true.B);d.io.skip.expect(true.B)
      d.io.selected.poke("hffffffff".U);d.io.skip.expect(false.B)
      d.io.geometry.bits.layer.poke(9.U);d.io.selected.poke(9.U);d.io.skip.expect(false.B)
      d.io.selected.poke(4.U);d.io.skip.expect(true.B)
    }
  }
}
