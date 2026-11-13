package opengpu.system

import java.nio.file.{Files,Path}
import opengpu.graphics.{NativeProductFrameInputs,NativeProductWorkloads}
import opengpu.testutil.NativeFrameMemoryCodec

/** Alternate decomposition of the same six workloads, never a new hardware
  * profile or a replacement for the frozen 8x8 product qualification.
  */
object PrepareNativeTileComparison {
  private[system] def mode(args:Array[String]):(Boolean,Path) = {
    val explicit=if(args.length==2 && args(0)=="--output")Array("--smoke",args(0),args(1)) else args
    val result=CompiledNativeProductFixtures.mode(explicit)
    require(CompiledNativeProductFixtures.twoCuVga,"requires the complete two-CU API profile")
    result
  }
  def main(args:Array[String]):Unit = {
    // Check every existing full-host/API/profile guard before producing inputs.
    val(product,root)=mode(args)
    val alignment=CompiledNativeProductFixtures.alignmentV2
    val(width,height)=CompiledNativeProductFixtures.frameSize(product,true)
    val scenes=NativeProductWorkloads.suite(width,height)
    for(log2<-Seq(3,4))require(!Files.exists(root.resolve(s"tile${1<<log2}")),"comparison output already exists")
    for(log2<-Seq(3,4)) {
      val directory=root.resolve(s"tile${1<<log2}").resolve("fixtures")
      Files.createDirectories(directory)
      for((scene,index)<-scenes.zipWithIndex) {
        val input=NativeProductFrameInputs.encodeApiExtentTiled(scene,log2)
        NativeFrameMemoryCodec.write(input.memory,directory.resolve(scene.id+".memory"))
        CompiledNativeProductFixtures.writeCommands(directory.resolve(scene.id+".commands"),scene,
          input,index+1,product=product,apiCapacity=true,twoCuVga=true,alignmentV2=alignment)
      }
      Files.writeString(directory.resolve("suite.txt"),scenes.map(_.id).mkString("","\n","\n"))
    }
    println(s"NATIVE_TILE_COMPARISON_INPUTS scenes=6 frame=${width}x$height tiles=8,16 computeUnits=2 alignmentV2=$alignment rtlEmitted=false fpsQualified=false")
  }
}

object VerifyNativeTileComparison {
  private[system] def verify(scene:NativeProductWorkloads.Scene,
      input:NativeProductFrameInputs.Inputs,actual:opengpu.testutil.PagedFrameMemory):Unit = {
    val mutableNames=Set("vertex-scratch","fragment-scratch","primitive-arena","bin-pool","bin-directory")
    val writable=input.regions.filter(r=>mutableNames(r.name) || r.name.startsWith("image-"))
      .map(r=>(r.base,r.limit))
    require(NativeFrameMemoryCodec.unchangedOutside(input.memory,actual,writable),
      s"${scene.id} changed a shader, descriptor, vertex, texture, allocation or read-only guard")
    NativeProductFrameInputs.verify(scene,input.copy(memory=actual))
  }
  def main(args:Array[String]):Unit = {
    val(product,root)=PrepareNativeTileComparison.mode(args)
    val(width,height)=CompiledNativeProductFixtures.frameSize(product,true)
    for(log2<-Seq(3,4);scene<-NativeProductWorkloads.suite(width,height)) {
      val input=NativeProductFrameInputs.encodeApiExtentTiled(scene,log2)
      val actual=NativeFrameMemoryCodec.read(root.resolve(s"tile${1<<log2}").resolve("output").resolve(scene.id+".memory"))
      verify(scene,input,actual)
      println(s"NATIVE_TILE_COMPARISON_IMAGE scene=${scene.id} tile=${1<<log2} frame=${width}x$height exactImagesAndReadOnlyGuards=true")
    }
    println(s"NATIVE_TILE_COMPARISON PASS scenes=6 variants=2 frame=${width}x$height computeUnits=2 clockQualified=false fpsQualified=false")
  }
}
