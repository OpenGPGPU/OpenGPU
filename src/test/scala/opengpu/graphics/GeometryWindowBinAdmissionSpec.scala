package opengpu.graphics

import opengpu.system._

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryWindowBinAdmissionSpec extends AnyFlatSpec {
  it should "reject mismatched identities and stop before the bin sequence wraps" in {
    simulate(new GeometryWindowBinAdmission){d=>
      d.io.pass.context.poke(7.U);d.io.pass.pass.poke(11.U);d.io.pass.generation.poke(13.U)
      d.io.primitive.context.poke(7.U);d.io.primitive.pass.poke(11.U);d.io.primitive.generation.poke(13.U)
      d.io.sequence.poke("hffffffff".U);d.io.sameIdentity.expect(true.B);d.io.exhausted.expect(false.B)
      d.io.sequence.poke("h100000000".U);d.io.exhausted.expect(true.B)
      d.io.primitive.context.poke(8.U);d.io.sameIdentity.expect(false.B);d.io.primitive.context.poke(7.U)
      d.io.primitive.pass.poke(12.U);d.io.sameIdentity.expect(false.B);d.io.primitive.pass.poke(11.U)
      d.io.primitive.generation.poke(14.U);d.io.sameIdentity.expect(false.B)
    }
  }
}
