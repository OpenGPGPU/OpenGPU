package opengpu.system

import chisel3._
import opengpu.graphics.NativeFourEngineDefaultHostAxiSpec
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover

/** Explicit qualification of the full development host, using the original
  * 32x32, four-full-CU, mixed-MRT/D32F and repeated-texture frame driver.
  * The hardware remains the same model used by the SRAM compute qualification.
  */
@DoNotDiscover
class NativeFourEngineSramRenderingQualification extends NativeFourEngineDefaultHostAxiSpec {
  override protected def payloadModes: Seq[Boolean]=Seq(true)
  override protected def binPageBytes: Int=4096
  override protected def host(local: Boolean): GpuHostSystemAxi={
    require(local,"the production host enables the native payload pool")
    new NativeFourEngineSramHost
  }
  override protected def initializeHost(d: GpuHostSystemAxi): Unit={
    initialize(d)
    d.asInstanceOf[NativeFourEngineSramHost].macroModelsPresent.expect(true.B)
    val expected=Vector[BigInt](BigInt("47435201",16),16,7,4,16,8,128,128,
      BigInt("ffffffff",16),16384,16,8,BigInt("10000000",16),32,131072,4)
    for(i<-expected.indices)
      assert(axiRead(d,0x360+4*i)==expected(i),s"full-host resource word $i differs")
    info("FULL_NATIVE_SRAM_RENDER_HOST viewportCapacity=640x480 frame=32x32 CUs=4 lanes=16 warps=8 scalarFpu=true L2bytes=131072 L2stores=8 AXIbits=256 payloadBytes=524288 engines=4 pageBytes=4096 writeCredits=8 tileSlots=8 pagedCapacity=16384 pinnedBothSramModels=true")
  }
}

/** Outside routine PR/nightly discovery; report success only after the complete
  * ScalaTest suite passes. This is bounded full-profile functional evidence,
  * not a product-resolution frame, timing or FPS measurement.
  */
object QualifyNativeFourEngineSramRendering {
  def main(args: Array[String]): Unit={
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "explicit full-host qualification requires -Dopengpu.fullHostQualification=1")
    require(args.length==2 && args(0)=="--reports","usage: --reports DIR")
    require(new NativeFourEngineSramRenderingQualification().testNames.size==1,
      "the complete qualification must contain exactly one case covering 1x and 4x")
    val passed=org.scalatest.tools.Runner.run(Array("-s",
      classOf[NativeFourEngineSramRenderingQualification].getName,"-oD","-u",args(1)))
    require(passed,"full native SRAM rendering qualification failed")
    println("FULL_NATIVE_SRAM_RENDERING PASS actualCases=1 sampleModes=1x,4x frame=32x32 fullNativeExecutionAndStorage=true exactMrtDepthResolve=true originalOneMillionCycleGuard=true product720pQualified=false clockQualified=false")
  }
}
