package opengpu.system

import java.nio.file.{Files,Paths}
import opengpu.graphics.{NativeProductFrameInputs,NativeProductWorkloads}
import opengpu.testutil.NativeFrameMemoryCodec

/** Alternate decomposition of the same six workloads, never a new hardware
  * profile or a replacement for the frozen 8x8 product qualification.
  */
object PrepareNativeTileComparison {
  def main(args:Array[String]):Unit = {
    require(args.length==2 && args(0)=="--output","usage: --output DIR")
    // Check every existing full-host/API/profile guard before producing inputs.
    CompiledNativeProductFixtures.mode(Array("--smoke","--output",args(1)))
    require(CompiledNativeProductFixtures.twoCuVga,"requires the complete two-CU API profile")
    val alignment=CompiledNativeProductFixtures.alignmentV2
    val root=Paths.get(args(1));val scenes=NativeProductWorkloads.suite(32,32)
    for(log2<-Seq(3,4)) {
      val directory=root.resolve(s"tile${1<<log2}").resolve("fixtures")
      Files.createDirectories(directory)
      for((scene,index)<-scenes.zipWithIndex) {
        val input=NativeProductFrameInputs.encodeApiExtentTiled(scene,log2)
        NativeFrameMemoryCodec.write(input.memory,directory.resolve(scene.id+".memory"))
        CompiledNativeProductFixtures.writeCommands(directory.resolve(scene.id+".commands"),scene,
          input,index+1,product=false,apiCapacity=true,twoCuVga=true,alignmentV2=alignment)
      }
      Files.writeString(directory.resolve("suite.txt"),scenes.map(_.id).mkString("","\n","\n"))
    }
    println(s"NATIVE_TILE_COMPARISON_INPUTS scenes=6 frame=32x32 tiles=8,16 computeUnits=2 alignmentV2=$alignment rtlEmitted=false fpsQualified=false")
  }
}
