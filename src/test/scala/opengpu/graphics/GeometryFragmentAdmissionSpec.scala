package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.system.GeometryFragmentAdmission
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryFragmentAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "reject mismatched identities, varyings, sample state and centroid qualifiers" in {
    simulate(new GeometryFragmentAdmission(GpuConfig(),FragmentShaderConfig(1,1),4)){d=>
      zero(d.io);val f=d.io.draw.fragment;val r=d.io.draw.raster;val task=r.geometry.geometry.primitive
      f.version.poke(FragmentInputAbi.geometry.U);f.varyingSlots.poke(1.U);task.varyingSlots.poke(1.U)
      d.io.legal.expect(true.B)
      f.version.poke(4.U);d.io.legal.expect(false.B);f.version.poke(FragmentInputAbi.geometry.U)
      f.identity.context.poke(1.U);d.io.legal.expect(false.B);task.input.primitive.identity.context.poke(1.U);d.io.legal.expect(true.B)
      f.varyingSlots.poke(0.U);d.io.legal.expect(false.B);f.varyingSlots.poke(1.U)
      f.sampleMode.poke(1.U);d.io.legal.expect(false.B);r.raster.sampleMode.poke(1.U);d.io.legal.expect(true.B)
      f.centroidMask.poke(1.U);d.io.legal.expect(false.B);r.centroidMask.poke(1.U);d.io.legal.expect(true.B)
    }
  }
}
