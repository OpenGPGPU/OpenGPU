package opengpu.system

import java.nio.file.Paths
import opengpu.graphics.{NativeProductFrameInputs, NativeProductWorkloads}
import opengpu.testutil.{GraphicsTestResolution, NativeFrameMemoryCodec}

/** Diagnostic verification of one completed scene; never a six-scene qualification. */
object VerifyOneNativeScene {
  def main(args: Array[String]): Unit = {
    require(args.length == 3 && Set("--smoke", "--product")(args(0)),
      "usage: --smoke|--product SCENE MEMORY")
    val product = args(0) == "--product"
    require(sys.props.get("opengpu.fullHostQualification").contains("1"))
    require(GraphicsTestResolution.productQualification == product)
    val width = if (product) 1280 else 32
    val height = if (product) 720 else 32
    val scene = NativeProductWorkloads.suite(width, height).find(_.id == args(1))
      .getOrElse(throw new IllegalArgumentException("unknown product scene"))
    val input = NativeProductFrameInputs.encode(scene)
    val actual = NativeFrameMemoryCodec.read(Paths.get(args(2)))
    require(NativeFrameMemoryCodec.sameAllocations(input.memory, actual),
      s"${scene.id} allocation metadata changed")
    NativeProductFrameInputs.verify(scene, input.copy(memory = actual))
    println(s"ONE_NATIVE_SCENE PASS id=${scene.id} frame=${width}x$height exactStoredImagesAndGuards=true fullSuiteQualified=false")
  }
}
