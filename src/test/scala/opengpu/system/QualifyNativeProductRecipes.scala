package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.{AxiTrafficCounters, GraphicsTestResolution}
import opengpu.testutil.GpuSim._
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec

/** Full execution/storage profile; only frame dimensions differ between the
  * explicit development smoke and the explicit 720p product qualification.
  */
private class NativeProductRecipeHost extends GpuHostSystemAxi(
  GraphicsConfig(screenWidth=GraphicsTestResolution.width,screenHeight=GraphicsTestResolution.height),
  GpuConfig(l2Sets=512,l2StoreEntries=8),numComputeUnits=4,
  fragCore=true,sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,
  memoryAxiDataBytes=32,useBlackBoxes=true,exposePerformance=true,
  nativeBinnedGraphics=Some(NativeBinnedGraphicsConfig.fourEngine)) {
  val macroModelsPresent=IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk,!io.s_axi_aresetn){
    macroModelsPresent:=Module(new NativeFourEngineSramModels).io.present
  }
}

@DoNotDiscover
class NativeProductRecipesQualification extends AnyFlatSpec with GpuHostTestSupport {
  it should "execute all six native product recipes and verify complete stored images" in {
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "native recipe execution requires explicit full-host qualification")
    val product=GraphicsTestResolution.productQualification
    val width=if(product)1280 else 32;val height=if(product)720 else 32
    simulate(new NativeProductRecipeHost){d=>
      for((scene,index)<-NativeProductWorkloads.suite(width,height).zipWithIndex){
        // Each frame owns a new external memory image. Reset invalidates GPU
        // caches before replacing it; this does not test concurrent publication.
        initialize(d);d.macroModelsPresent.expect(true.B)
        val input=NativeProductFrameInputs.encode(scene)
        val resources=Vector[BigInt](BigInt("47435201",16),16,7,4,16,8,128,128,
          BigInt("ffffffff",16),16384,16,8,BigInt("10000000",16),32,131072,4)
        for(i<-resources.indices)assert(axiRead(d,0x360+i*4)==resources(i))
        axiWrite(d,RenderHostRegs.IRQ,1)
        axiWrite(d,GpuCommandMmioRegs.COMMAND_ID,index+1)
        axiWrite(d,GpuCommandMmioRegs.OPCODE,7)
        axiWrite(d,GpuCommandMmioRegs.SOURCE,input.jobBase.toInt)
        axiWrite(d,GpuCommandMmioRegs.DESTINATION,input.completionBase.toInt)
        axiWrite(d,GpuCommandMmioRegs.BYTES,512)
        axiWrite(d,GpuCommandMmioRegs.PATTERN,0)
        axiWrite(d,GpuCommandMmioRegs.SUBMIT,1)
        val traffic=new AxiTrafficCounters;val observer=new AxiTrafficObserver(d.io,traffic)
        var cycles=0L
        serviceMemoryMaster(d,a=>input.memory.readLine(a.toLong),readFault=a=> !input.containsLine(a),
          writeFault=(a,_,_)=> !input.containsLine(a),writeAckDelay=7,readResponseDelay=3,
          maxCycles=if(product)1000000000 else 10000000,
          onCycle=()=>{cycles+=1;observer.sample()})(
          (a,value,mask)=>input.memory.writeLine(a.toLong,value,mask)){
          d.io.m_irq.peek().litToBoolean
        }
        traffic.assertDrained()
        val result=axiRead(d,GpuCommandMmioRegs.COMPLETION)
        val details=(0 until 12).map(i=>axiRead(d,0x180+i*4))
        assert((result&255)==index+1 && ((result>>8)&7)==7 && ((result>>11)&15)==0 && result.testBit(15),
          s"${scene.id} completion=${result.toString(16)} GNX1=${details.mkString(",")}")
        assert(details.head==BigInt("474e5801",16) && details(1)==0 && details(11)==0)
        NativeProductFrameInputs.verify(scene,input)
        val counts=traffic.snapshot
        val perf=d.io.performance.get
        info(s"NATIVE_PRODUCT_RECIPE id=${scene.id} frame=${width}x$height samples=${scene.samples} draws=${scene.draws.size} primitives=${scene.primitiveCount} serviceCycles=$cycles gpuCycles=${perf.system.cycles.peek().litValue} readBusBytes=${counts.readBusBytes} writeBusBytes=${counts.writeBusBytes} enabledWriteBytes=${counts.enabledWriteBytes} phaseWorkerCycles=${perf.fragmentPhaseWorkerCycles.map(_.peek().litValue).mkString(",")} exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false")
        axiWrite(d,GpuCommandMmioRegs.COMPLETION_POP,1);axiWrite(d,RenderHostRegs.IRQ,3)
      }
    }
  }
}

object QualifyNativeProductRecipes {
  def main(args: Array[String]): Unit={
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "requires -Dopengpu.fullHostQualification=1")
    require(args.length==3 && Set("--smoke","--product")(args(0)) && args(1)=="--reports",
      "usage: --smoke|--product --reports DIR")
    require(GraphicsTestResolution.productQualification==(args(0)=="--product"),
      "--product requires productQualification=1; --smoke requires 0 or unset")
    require(new NativeProductRecipesQualification().testNames.size==1,"all six recipes must execute together")
    val passed=org.scalatest.tools.Runner.run(Array("-s",classOf[NativeProductRecipesQualification].getName,"-oD","-u",args(2)))
    require(passed,"native product recipe qualification failed")
    println("NATIVE_PRODUCT_RECIPES PASS scenes=6 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false")
  }
}
