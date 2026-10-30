package opengpu.system

import chisel3._
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryRasterAdmissionSpec extends AnyFlatSpec {
  it should "validate raster state, varying qualifiers and depth format" in {
    val p=VertexShaderStageConfig(varyings=1);val g=GraphicsConfig(screenWidth=4,screenHeight=4,maxSampleCount=1)
    simulate(new GeometryRasterAdmission(p,g)){d=>
      d.io.raster.poke(0.U.asTypeOf(d.io.raster));d.io.raster.version.poke(1.U)
      d.io.varyingSlots.poke(1.U);d.io.centroidMask.poke(8.U);d.io.depthFormat.poke(TypedDepthFormat.d24.U)
      d.io.legal.expect(true.B)
      d.io.raster.version.poke(0.U);d.io.legal.expect(false.B);d.io.raster.version.poke(1.U)
      d.io.raster.sampleMode.poke(1.U);d.io.legal.expect(false.B);d.io.raster.sampleMode.poke(0.U)
      d.io.varyingSlots.poke(0.U);d.io.legal.expect(false.B);d.io.varyingSlots.poke(2.U);d.io.legal.expect(false.B)
      d.io.varyingSlots.poke(1.U);d.io.depthFormat.poke(7.U);d.io.legal.expect(false.B)
      d.io.depthFormat.poke(TypedDepthFormat.d24.U);d.io.legal.expect(true.B)
    }
  }
}
