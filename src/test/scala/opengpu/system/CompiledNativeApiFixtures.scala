package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import java.io.{BufferedOutputStream, DataOutputStream}
import java.nio.file.{Files, Path, Paths}
import opengpu.config.{ComputeResourceProfile,GpuConfig}
import opengpu.graphics._
import opengpu.testutil.{GraphicsTestResolution, NativeFrameMemoryCodec}

private[opengpu] object CompiledNativeApiFixtures {
  val display=GraphicsConfig(screenWidth=math.min(640,GraphicsTestResolution.width),
    screenHeight=math.min(480,GraphicsTestResolution.height))
  val gpu=GpuConfig(l2Sets=512,l2StoreEntries=8)
  val native=NativeBinnedGraphicsConfig.fourEngine.copy(
    renderTargetExtent=Some((2048,2048)),pagedCapacity=65536)
  def frames(product: Boolean)=Vector(1,4).map { samples=>
    val width=if(product)2048 else math.min(32,GraphicsTestResolution.width)
    val height=if(product)2048 else math.min(32,GraphicsTestResolution.height)
    val input=NativeApiBoundaryFrame(samples,width,height)
    input.copy(scene=input.scene.copy(id=s"native-api-extent-${samples}x"))
  }

  def output(args: Array[String]): (Boolean,Path) = {
    require(args.length==3 && Set("--smoke","--api-extent-product")(args(0)) && args(1)=="--output",
      "usage: --smoke|--api-extent-product --output DIR [--fixtures-only for preparation]")
    for(flag<-Seq("fullHostQualification","apiExtentQualification"))
      require(sys.props.get("opengpu."+flag).contains("1"),s"requires explicit opengpu.$flag=1")
    val product=args(0)=="--api-extent-product"
    require(GraphicsTestResolution.productQualification==product,
      "API product frames require productQualification=1; smoke requires 0 or unset")
    (product,Paths.get(args(2)))
  }

  def writeCommands(path: Path,scene: NativeProductWorkloads.Scene,
    input: NativeProductFrameInputs.Inputs,id: Int,product: Boolean): Unit = {
    import CompiledNativeProductFixtures.{put32,put64}
    val out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))
    try {
      out.write("OGAPI001".getBytes("US-ASCII"))
      Seq(scene.width,scene.height,scene.samples,id).foreach(v=>put32(out,v))
      put64(out,if(product)1000000000L else 10000000L)
      val compute=Vector[Long](0x47435201L,16,15,4,16,8,128,128,
        0xffffffffL,16384,16,8,0x10000000L,32,131072,4)
      require(compute==ComputeResourceCapabilities.words(ComputeResourceProfile(gpu,4,32)).map(_.toLong),
        "native API fixture GCR1 contract differs from the current host profile")
      val resources=compute.zipWithIndex.map{case(v,i)=>(0x360+4*i,v)} ++
        NativeBinnedGraphicsCapabilities.words(native,4,display).zipWithIndex
          .map{case(v,i)=>(TileBinHostRegs.CAPABILITIES+4*i,v.toLong)}
      put32(out,resources.size)
      resources.foreach{case(a,v)=>put32(out,a);put32(out,v)}
      val writes=Vector[(Int,Long)](
        RenderHostRegs.IRQ->1L,GpuCommandMmioRegs.COMMAND_ID->id.toLong,
        GpuCommandMmioRegs.OPCODE->7L,GpuCommandMmioRegs.SOURCE->input.jobBase,
        GpuCommandMmioRegs.DESTINATION->input.completionBase,GpuCommandMmioRegs.BYTES->512L,
        GpuCommandMmioRegs.PATTERN->0L,GpuCommandMmioRegs.SUBMIT->1L)
      put32(out,writes.size);writes.foreach{case(a,v)=>put32(out,a);put32(out,v)}
      val checks=Vector[(Int,Long,Long)]((GpuCommandMmioRegs.COMPLETION,0xffffL,0x8700L|id)) ++
        Vector.tabulate(12)(i=>(0x180+4*i,if(Set(0,1,11)(i))0xffffffffL else 0L,
          if(i==0)0x474e5801L else 0L))
      put32(out,checks.size);checks.foreach{case(a,m,v)=>put32(out,a);put32(out,m);put32(out,v)}
      val finish=Vector(GpuCommandMmioRegs.COMPLETION_POP->1,RenderHostRegs.IRQ->3)
      put32(out,finish.size);finish.foreach{case(a,v)=>put32(out,a);put32(out,v)}
    } finally out.close()
  }
}

/** Same four-CU execution/storage capacities as the product host. Only native
  * target/directory bounds change; display storage remains at 640x480.
  */
private[system] class NativeApiBoundaryHost extends GpuHostSystemAxi(
  CompiledNativeApiFixtures.display,CompiledNativeApiFixtures.gpu,numComputeUnits=4,
  fragCore=true,sharedGraphics=true,parallelGraphics=true,enableFpuBackend=true,
  memoryAxiDataBytes=32,useBlackBoxes=true,exposePerformance=true,
  nativeBinnedGraphics=Some(CompiledNativeApiFixtures.native)) {
  val macroModelsPresent=IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk,!io.s_axi_aresetn){
    macroModelsPresent:=Module(new NativeFourEngineSramModels).io.present
  }
}

object PrepareCompiledNativeApi {
  def main(args: Array[String]): Unit = {
    val fixturesOnly=args.lastOption.contains("--fixtures-only")
    val (product,directory)=CompiledNativeApiFixtures.output(if(fixturesOnly)args.dropRight(1)else args)
    val frames=CompiledNativeApiFixtures.frames(product)
    val fixtures=directory.resolve("fixtures");Files.createDirectories(fixtures)
    for((frame,index)<-frames.zipWithIndex){
      val input=NativeProductFrameInputs.encodeApiExtent(frame.scene,frame.scissors)
      NativeFrameMemoryCodec.write(input.memory,fixtures.resolve(frame.scene.id+".memory"))
      CompiledNativeApiFixtures.writeCommands(fixtures.resolve(frame.scene.id+".commands"),frame.scene,input,index+1,product)
    }
    Files.writeString(fixtures.resolve("suite.txt"),frames.map(_.scene.id).mkString("","\n","\n"))
    if(!fixturesOnly){
      ChiselStage.emitSystemVerilogFile(new NativeApiBoundaryHost,
        Array("--target-dir",directory.resolve("rtl").toString),
        Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
      Files.writeString(directory.resolve("rtl/NativeFourEngineSramModels.sv"),NativeFourEngineSramModels.source)
    }
    val scene=frames.head.scene
    Files.writeString(directory.resolve("fixture-manifest.json"),
      s"""{"version":1,"product":$product,"apiExtent":true,"frameWidth":${scene.width},"frameHeight":${scene.height},"screenWidth":${CompiledNativeApiFixtures.display.screenWidth},"screenHeight":${CompiledNativeApiFixtures.display.screenHeight},"computeUnits":4,"lanes":16,"warps":8,"engines":4,"axiDataBytes":32,"pagedCapacity":65536,"payloadBytes":524288,"realSramModels":true,"rtlEmitted":${!fixturesOnly},"scenes":["native-api-extent-1x","native-api-extent-4x"]}"""+"\n")
    println(s"NATIVE_API_COMPILED_INPUTS frames=2 frame=${scene.width}x${scene.height} rtlEmitted=${!fixturesOnly}")
  }
}

object VerifyCompiledNativeApi {
  def main(args: Array[String]): Unit = {
    val (product,directory)=CompiledNativeApiFixtures.output(args)
    for(frame<-CompiledNativeApiFixtures.frames(product)){
      val input=NativeProductFrameInputs.encodeApiExtent(frame.scene,frame.scissors)
      val actual=NativeFrameMemoryCodec.read(directory.resolve(frame.scene.id+".memory"))
      require(NativeFrameMemoryCodec.sameAllocations(input.memory,actual),s"${frame.scene.id} allocation metadata changed")
      NativeProductFrameInputs.verifyApiExtent(frame.scene,input.copy(memory=actual),frame.scissors)
      println(s"NATIVE_API_COMPILED_IMAGE id=${frame.scene.id} frame=${frame.scene.width}x${frame.scene.height} exactStoredImagesAndGuards=true")
    }
    println("NATIVE_COMPILED_API PASS frames=2 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false")
  }
}
