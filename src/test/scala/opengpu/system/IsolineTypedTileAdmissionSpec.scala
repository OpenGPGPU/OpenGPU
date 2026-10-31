package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class IsolineTypedTileAdmissionSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero)
    case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "reject mismatched tile, draw and shader descriptors before launch" in {
    simulate(new IsolineTypedTileAdmission(GpuConfig(),FragmentShaderConfig(1,4),4,GraphicsConfig(),TypedTileColorConfig())){d=>
      zero(d.io);val t=d.io.task;val tile=t.pixels.colors;val r=t.draw.raster;val f=t.draw.fragment
      t.version.poke(1.U);t.tests.version.poke(1.U);t.pixels.version.poke(1.U)
      tile.version.poke(1.U);tile.tileLog2.poke(3.U);tile.tileWidth.poke(4.U);tile.tileHeight.poke(4.U)
      r.raster.version.poke(1.U);r.raster.screenW.poke(4.U);r.raster.screenH.poke(4.U)
      r.source.viewport.version.poke(1.U);r.source.viewport.extentX.poke(4.U);r.source.viewport.extentY.poke(4.U)
      r.source.viewport.farDepth.poke("h3f800000".U)
      f.version.poke(FragmentInputAbi.center.U);f.shaderPc.poke(0x2000.U);f.scratch.base.poke(0x100000.U)
      f.scratch.limit.poke(0x200000.U);f.scratch.allocationBase.poke(0x100000.U);f.scratch.allocationLimit.poke(0x200000.U)
      f.sampler.texWidth.poke(1.U);f.sampler.texHeight.poke(1.U)
      d.io.legal.expect(true.B)
      f.identity.context.poke(1.U);d.io.legal.expect(false.B);f.identity.context.poke(0.U)
      r.raster.screenW.poke(5.U);d.io.legal.expect(false.B);r.raster.screenW.poke(4.U)
      t.tests.version.poke(0.U);d.io.legal.expect(false.B);t.tests.version.poke(1.U)
      t.pixels.version.poke(0.U);d.io.legal.expect(false.B);t.pixels.version.poke(1.U)
      r.depthFormat.poke(TypedDepthFormat.d24.U);d.io.legal.expect(false.B);r.depthFormat.poke(0.U)
      f.shaderPc.poke(0.U);d.io.legal.expect(false.B);f.shaderPc.poke(0x2000.U)
      d.io.legal.expect(true.B)
    }
  }
}
