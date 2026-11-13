package opengpu.system

import opengpu.graphics.NativeProductFrameInputs
import opengpu.testutil.NativeFrameMemoryCodec

/** Inspect one completed API frame without qualifying the two-frame suite. */
object VerifyOneNativeApiScene {
  def main(args: Array[String]): Unit = {
    require(args.length == 3,
      "usage: --smoke|--api-extent-product SCENE MEMORY")
    val (product, memory) = CompiledNativeApiFixtures.output(
      Array(args(0), "--output", args(2)))
    val frame = CompiledNativeApiFixtures.frames(product).find(_.scene.id == args(1))
      .getOrElse(throw new IllegalArgumentException("unknown native API scene"))
    val input = NativeProductFrameInputs.encodeApiExtent(frame.scene, frame.scissors)
    val actual = NativeFrameMemoryCodec.read(memory)
    require(NativeFrameMemoryCodec.sameAllocations(input.memory, actual),
      s"${frame.scene.id} allocation metadata changed")
    NativeProductFrameInputs.verifyApiExtent(frame.scene, input.copy(memory = actual), frame.scissors)
    println(s"ONE_NATIVE_API_SCENE PASS id=${frame.scene.id} frame=${frame.scene.width}x${frame.scene.height} samples=${frame.scene.samples} exactStoredImagesAndGuards=true fullSuiteQualified=false")
  }
}
