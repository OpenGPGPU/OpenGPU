package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class IsolineFragmentAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "reject mismatched identity, varyings, samples, centroid and ABI" in {
    simulate(new IsolineFragmentAdmission(GpuConfig(),FragmentShaderConfig(1,1),4)){d=>
      zero(d.io);val f=d.io.draw.fragment;val r=d.io.draw.raster;val task=r.source.source.renderer
      f.version.poke(FragmentInputAbi.center.U);f.varyingSlots.poke(1.U);task.varyingSlots.poke(1.U)
      f.shaderPc.poke(0x2000.U);f.scratch.base.poke(0x100000.U);f.scratch.limit.poke(0x200000.U)
      f.scratch.allocationBase.poke(0x100000.U);f.scratch.allocationLimit.poke(0x200000.U)
      f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      r.raster.version.poke(1.U);r.raster.screenW.poke(4.U);r.raster.screenH.poke(4.U)
      r.depthFormat.poke(TypedDepthFormat.d24.U);r.source.viewport.version.poke(1.U)
      r.source.viewport.extentX.poke(4.U);r.source.viewport.extentY.poke(4.U);r.source.viewport.farDepth.poke("h3f800000".U)
      d.io.legal.expect(true.B);f.version.poke(FragmentInputAbi.geometry.U);d.io.legal.expect(false.B)
      f.version.poke(FragmentInputAbi.center.U);f.identity.context.poke(1.U);d.io.legal.expect(false.B)
      task.identity.context.poke(1.U);d.io.legal.expect(true.B);f.varyingSlots.poke(0.U);d.io.legal.expect(false.B)
      f.varyingSlots.poke(1.U);f.sampleMode.poke(1.U);d.io.legal.expect(false.B)
      r.raster.sampleMode.poke(1.U);d.io.legal.expect(true.B);f.version.poke(FragmentInputAbi.centroid.U)
      f.centroidMask.poke(1.U);d.io.legal.expect(false.B)
      r.centroidMask.poke(1.U);d.io.legal.expect(true.B)
      f.shaderPc.poke(0.U);d.io.legal.expect(false.B);f.shaderPc.poke(0x2000.U)
      r.raster.version.poke(0.U);d.io.legal.expect(false.B);r.raster.version.poke(1.U)
      r.source.viewport.version.poke(0.U);d.io.legal.expect(false.B);r.source.viewport.version.poke(1.U)
      f.scratch.limit.poke(0x100040.U);d.io.legal.expect(false.B)
    }
  }
}
