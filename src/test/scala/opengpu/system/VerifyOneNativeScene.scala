package opengpu.system

import opengpu.graphics.{NativeProductFrameInputs, NativeProductWorkloads}
import opengpu.testutil.NativeFrameMemoryCodec

/** Diagnostic verification of one completed scene; never a six-scene qualification. */
object VerifyOneNativeScene {
  def main(args: Array[String]): Unit = {
    require(args.length == 3 && Set("--smoke", "--product")(args(0)),
      "usage: --smoke|--product SCENE MEMORY")
    val (product, memory) = CompiledNativeProductFixtures.mode(
      Array(args(0), "--output", args(2)))
    val twoCuVga = CompiledNativeProductFixtures.twoCuVga
    val (width, height) = CompiledNativeProductFixtures.frameSize(product, twoCuVga)
    val scene = NativeProductWorkloads.suite(width, height).find(_.id == args(1))
      .getOrElse(throw new IllegalArgumentException("unknown product scene"))
    val input = CompiledNativeProductFixtures.input(scene, twoCuVga)
    val actual = NativeFrameMemoryCodec.read(memory)
    require(NativeFrameMemoryCodec.sameAllocations(input.memory, actual),
      s"${scene.id} allocation metadata changed")
    NativeProductFrameInputs.verify(scene, input.copy(memory = actual))
    println(s"ONE_NATIVE_SCENE PASS id=${scene.id} frame=${width}x$height exactStoredImagesAndGuards=true fullSuiteQualified=false")
  }
}
