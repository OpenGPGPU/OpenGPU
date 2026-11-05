package opengpu.system

import java.nio.file.Files
import opengpu.graphics.{NativeProductFrameInputs, NativeProductWorkloads}
import org.scalatest.flatspec.AnyFlatSpec

class CompiledNativeProductFixturesSpec extends AnyFlatSpec {
  it should "reject insufficient primitive storage before publishing executable commands" in {
    val scene = NativeProductWorkloads.scene("small-triangles", 16, 16)
    val input = NativeProductFrameInputs.encode(scene)
    val base = input.frame("frame.bins.producer.arena.base")
    val invalid = input.copy(frame = input.frame.updated("frame.bins.producer.arena.limit", base + 64))
    val directory = Files.createTempDirectory("native-product-arena-")
    val commands = directory.resolve("invalid.commands")
    try {
      val error = intercept[IllegalArgumentException] {
        CompiledNativeProductFixtures.writeCommands(commands, scene, invalid, 5, product = false)
      }
      assert(error.getMessage.contains("primitive arena"))
      assert(!Files.exists(commands))
    } finally {
      Files.deleteIfExists(commands)
      Files.delete(directory)
    }
  }
}
