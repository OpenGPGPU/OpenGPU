package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.{GraphicsConfig,NativeBinnedGraphicsConfig}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover

/** Match the explicit product emitter, retaining all execution/storage knobs. */
private class NativeProductSramHost extends GpuHostSystemAxi(
  GraphicsConfig(screenWidth=1280,screenHeight=720),
  GpuConfig(l2Sets=512,l2StoreEntries=8),numComputeUnits=4,
  fragCore=true,sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,
  memoryAxiDataBytes=32,useBlackBoxes=true,exposePerformance=true,
  nativeBinnedGraphics=Some(NativeBinnedGraphicsConfig.fourEngine)) {
  val macroModelsPresent=IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk,!io.s_axi_aresetn) {
    macroModelsPresent:=Module(new NativeFourEngineSramModels).io.present
  }
}

/** Exercise the new memory/VM/oracle path on the complete development host. */
@DoNotDiscover
class NativeSparseFrameSmokeQualification extends NativeFourEngineSramRenderingQualification {
  override protected def sparseFrameBacking: Boolean=true
}

/** One explicit product workload: textured complementary triangles, mixed MRTs
  * and D32F, with both 1x and 4x MSAA. This is not the full six-workload suite.
  */
@DoNotDiscover
class NativeProductFrame720Qualification extends NativeFourEngineSramRenderingQualification {
  override protected def sparseFrameBacking: Boolean=true
  override protected def frameWidth: Int=1280
  override protected def frameHeight: Int=720
  override protected def frameServiceCycleLimit: Int=1000000000
  override protected def host(local: Boolean): GpuHostSystemAxi={
    require(local && sys.props.get("opengpu.productQualification").contains("1"),
      "product frames require explicit product qualification")
    new NativeProductSramHost
  }
  override protected def initializeHost(d: GpuHostSystemAxi): Unit={
    initialize(d)
    d.asInstanceOf[NativeProductSramHost].macroModelsPresent.expect(true.B)
    val expected=Vector[BigInt](BigInt("47435201",16),16,15,4,16,8,128,128,
      BigInt("ffffffff",16),16384,16,8,BigInt("10000000",16),32,131072,4)
    for(i<-expected.indices)
      assert(axiRead(d,0x360+4*i)==expected(i),s"product resource word $i differs")
    info("NATIVE_PRODUCT_SRAM_HOST viewportCapacity=1280x720 frame=1280x720 fullExecutionAndStorage=true pinnedBothSramModels=true")
  }
}

object QualifyNativeProductFrames {
  private[opengpu] def productMode(args: Array[String],fullHost: Option[String],
    productFlag: Option[String]): Boolean={
    require(fullHost.contains("1"),
      "requires -Dopengpu.fullHostQualification=1")
    require(args.length==3 && Set("--smoke","--product")(args(0)) && args(1)=="--reports",
      "usage: --smoke|--product --reports DIR")
    val product=args(0)=="--product"
    require(if(product)productFlag.contains("1") else productFlag.forall(_=="0"),
      "--product requires productQualification=1; --smoke requires 0 or unset")
    product
  }
  def main(args: Array[String]): Unit={
    val product=productMode(args,sys.props.get("opengpu.fullHostQualification"),
      sys.props.get("opengpu.productQualification"))
    val suite=if(product)classOf[NativeProductFrame720Qualification] else classOf[NativeSparseFrameSmokeQualification]
    val names=if(product)new NativeProductFrame720Qualification().testNames
      else new NativeSparseFrameSmokeQualification().testNames
    require(names.size==1,"the complete workload must contain one case with both MSAA modes")
    val passed=org.scalatest.tools.Runner.run(Array("-s",suite.getName,"-oD","-u",args(2)))
    require(passed,"native sparse frame qualification failed")
    val dimensions=if(product)"1280x720" else "32x32"
    println(s"NATIVE_SPARSE_FRAMES PASS actualCases=1 sampleModes=1x,4x frame=$dimensions fullNativeExecutionAndStorage=true exactMrtDepthResolveAndGuards=true productFrameExecuted=$product clockQualified=false fpsQualified=false completeWorkloadSuite=false")
  }
}
