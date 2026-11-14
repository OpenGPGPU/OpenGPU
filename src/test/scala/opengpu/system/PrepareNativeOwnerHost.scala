package opengpu.system

import chisel3._
import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

/** Emit the same SRAM models and complete target used by product recipes. */
private[system] class NativeOwnerProductRecipeHost extends NativeAlignmentHostSystemAxi(
    NativeOwnerHostProfile.target, useBlackBoxes=true, exposePerformance=true) {
  val macroModelsPresent=IO(Output(Bool()))
  withClockAndReset(io.s_axi_aclk,!io.s_axi_aresetn) {
    macroModelsPresent:=Module(new NativeFourEngineSramModels).io.present
  }
}

object PrepareNativeOwnerHost {
  def manifest(rtlEmitted: Boolean, workload: Option[(Boolean,Int,Int)] = None,
      workloadTileLog2: Int = 3): String = {
    val p=NativeOwnerHostProfile.target
    val render=p.native.renderGraphics(p.graphics)
    val words=p.requiredDiscovery.map { case (address,value) =>
      s"""{"address":$address,"value":$value}"""
    }.mkString(",")
    val fixtures=workload.map { case(product,width,height) =>
      val scenes=opengpu.graphics.NativeProductWorkloads.ids.map(s=>s"\"$s\"").mkString(",")
      require(workloadTileLog2==3 || workloadTileLog2==4)
      s""","workloadProduct":$product,"workloadWidth":$width,"workloadHeight":$height,"workloadTileLog2":$workloadTileLog2,"scenes":[$scenes]"""
    }.getOrElse("")
    s"""{"version":1,"profile":"${NativeOwnerHostProfile.id}","computeUnits":${p.computeUnits},"frameWidth":${p.graphics.screenWidth},"frameHeight":${p.graphics.screenHeight},"lanes":${p.execution.lanes},"warps":${p.execution.warps},"transactionsPerCu":${p.transactionsPerCu},"axiDataBytes":${p.axiDataBytes},"engines":${p.native.engines},"nativeMaxWidth":${render.screenWidth},"nativeMaxHeight":${render.screenHeight},"pagedCapacity":${p.native.pagedCapacity},"payloadBytes":${p.native.payload.get.capacityBytes},"resourceDiscoveryWords":${p.requiredDiscovery.size},"computeFeatureFlags":${p.requiredDiscovery(2)._2},"targetFps":60,"rtlEmitted":$rtlEmitted,"sourceQualificationRequired":true,"gpuExecution":false,"clockQualified":false,"fpsQualified":false$fixtures,"requiredDiscovery":[$words]}""" + "\n"
  }
  def main(args: Array[String]): Unit = {
    if (args.sameElements(Array("--describe"))) { print(manifest(rtlEmitted=false)); return }
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "complete owner host emission requires explicit full-host qualification")
    require(args.length==2 && args(0)=="--output", "usage: PrepareNativeOwnerHost --output NEW_DIRECTORY")
    val output=Paths.get(args(1)).toAbsolutePath.normalize
    require(!Files.exists(output), "owner host output must be a new directory")
    Files.createDirectories(output)
    val rtl=output.resolve("rtl")
    ChiselStage.emitSystemVerilogFile(new NativeOwnerProductRecipeHost,
      Array("--target-dir",rtl.toString),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
    Files.writeString(rtl.resolve("NativeFourEngineSramModels.sv"),NativeFourEngineSramModels.source)
    Files.writeString(output.resolve("owner-manifest.json"),manifest(rtlEmitted=true))
    println(s"NATIVE_OWNER_HOST_PREPARED profile=${NativeOwnerHostProfile.id} words=96 frame=640x480 clockQualified=false fpsQualified=false")
  }
}

/** Original GPU input recipes for the exact owner factory; emits no RTL or
  * precomputed GPU image. Reuse one separately prepared full factory DUT.
  */
object PrepareNativeOwnerFixtures {
  private[system] def mode(args: Array[String]): (Boolean,java.nio.file.Path,Int) = {
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "owner fixtures require explicit full-host qualification")
    require((args.length==3 || args.length==5) && Set("--smoke","--product")(args(0)) && args(1)=="--output",
      "usage: --smoke|--product --output OWNER_DIRECTORY [--tile-log2 3|4]")
    require(args.length==3 || args(3)=="--tile-log2","unknown owner fixture option")
    val tileLog2=if(args.length==5)args(4).toInt else 3
    require(tileLog2==3 || tileLog2==4,"owner fixtures require 8x8 or 16x16 tiles")
    val product=args(0)=="--product"
    val output=Paths.get(args(2)).toAbsolutePath.normalize
    (product,output,tileLog2)
  }
  def main(args: Array[String]): Unit = {
    val(product,output,tileLog2)=mode(args)
    require(Files.isDirectory(output),"prepare the complete owner DUT directory first")
    val fixtures=output.resolve("fixtures")
    require(!Files.exists(fixtures) && !Files.exists(output.resolve("owner-fixture-manifest.json")),
      "owner fixtures require a fresh fixture directory")
    Files.createDirectory(fixtures)
    val (width,height)=CompiledNativeProductFixtures.frameSize(product,twoCuVga=true)
    val scenes=opengpu.graphics.NativeProductWorkloads.suite(width,height)
    for((scene,index)<-scenes.zipWithIndex) {
      val input=opengpu.graphics.NativeProductFrameInputs.encodeApiExtentTiled(scene,tileLog2)
      opengpu.testutil.NativeFrameMemoryCodec.write(input.memory,fixtures.resolve(scene.id+".memory"))
      CompiledNativeProductFixtures.writeCommands(fixtures.resolve(scene.id+".commands"),scene,input,index+1,
        product,apiCapacity=true,twoCuVga=true,nativeOwner=true)
    }
    Files.writeString(fixtures.resolve("suite.txt"),scenes.map(_.id).mkString("","\n","\n"))
    Files.writeString(output.resolve("owner-fixture-manifest.json"),
      PrepareNativeOwnerHost.manifest(rtlEmitted=false,workload=Some((product,width,height)),
        workloadTileLog2=tileLog2))
    println(s"NATIVE_OWNER_FIXTURES scenes=6 frame=${width}x$height words=96 rtlEmitted=false gpuExecution=false")
  }
}

/** Read independently returned GPU storage; never emit or synthesize images. */
object VerifyNativeOwnerProduct {
  def main(args: Array[String]): Unit = {
    val(product,output,tileLog2)=PrepareNativeOwnerFixtures.mode(args)
    val(width,height)=CompiledNativeProductFixtures.frameSize(product,twoCuVga=true)
    for(scene<-opengpu.graphics.NativeProductWorkloads.suite(width,height)) {
      val input=opengpu.graphics.NativeProductFrameInputs.encodeApiExtentTiled(scene,tileLog2)
      val actual=opengpu.testutil.NativeFrameMemoryCodec.read(output.resolve(scene.id+".memory"))
      VerifyNativeTileComparison.verify(scene,input,actual)
      println(s"NATIVE_OWNER_IMAGE scene=${scene.id} tile=${1<<tileLog2} frame=${width}x$height exactImagesAndReadOnlyGuards=true")
    }
    println(s"NATIVE_OWNER_PRODUCT PASS profile=${NativeOwnerHostProfile.id} scenes=6 tile=${1<<tileLog2} frame=${width}x$height computeUnits=2 clockQualified=false fpsQualified=false")
  }
}
