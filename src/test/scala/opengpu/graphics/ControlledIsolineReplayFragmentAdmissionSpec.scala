package opengpu.graphics

import opengpu.system._

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ControlledIsolineReplayFragmentAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "match the replay identity, public fragment ABI and raster qualifiers" in {
    simulate(new ControlledIsolineReplayFragmentAdmission(FragmentShaderConfig(2,1))){d=>
      zero(d.io);val r=d.io.draw.raster;val f=d.io.draw.fragment;val primitive=r.replay.record.primitive.clipped.primitive
      f.version.poke(FragmentInputAbi.center.U);f.varyingSlots.poke(2.U);primitive.varyingSlots.poke(2.U)
      d.io.legal.expect(true.B)
      f.version.poke(FragmentInputAbi.geometry.U);d.io.legal.expect(false.B);f.version.poke(FragmentInputAbi.centroid.U)
      d.io.legal.expect(true.B);f.version.poke(FragmentInputAbi.center.U)
      f.identity.context.poke(1.U);d.io.legal.expect(false.B);primitive.identity.context.poke(1.U);d.io.legal.expect(true.B)
      f.varyingSlots.poke(1.U);d.io.legal.expect(false.B);f.varyingSlots.poke(2.U)
      f.sampleMode.poke(1.U);d.io.legal.expect(false.B);r.replay.record.raster.sampleMode.poke(1.U);d.io.legal.expect(true.B)
      f.centroidMask.poke(1.U);d.io.legal.expect(false.B);r.centroidMask.poke(1.U);d.io.legal.expect(true.B)
    }
  }
}
