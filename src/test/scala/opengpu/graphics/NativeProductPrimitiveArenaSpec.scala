package opengpu.graphics

import java.nio.file.Files
import opengpu.system.CompiledNativeProductFixtures
import org.scalatest.flatspec.AnyFlatSpec

class NativeProductPrimitiveArenaSpec extends AnyFlatSpec {
  it should "retain small fixture addresses and derive complete native record storage" in {
    assert(NativeProductPrimitiveArena.recordBytes == 1472)
    val arena = NativeProductPrimitiveArena.allocate(16)
    assert(arena.base == 0x03000000L && arena.limit == 0x04000000L)
    NativeProductPrimitiveArena.validate(16, arena.base, arena.limit)
  }

  it should "budget the actual product triangle count and clipping fan without rendering a frame" in {
    val triangles = BigInt(14400)
    val bytes = NativeProductPrimitiveArena.requiredBytes(triangles)
    assert(bytes == BigInt(14400) * 7 * 1472)
    val arena = NativeProductPrimitiveArena.allocate(triangles)
    assert(arena.limit - arena.base == bytes)
    assert(arena.base == 0x60000000L && arena.limit <= (BigInt(1) << 32))
    assert(arena.base >= 0x60000000L) // Beyond both ordinary and API bin pools.
    NativeProductPrimitiveArena.validate(triangles, arena.base, arena.limit)
    val error = intercept[IllegalArgumentException] {
      NativeProductPrimitiveArena.validate(triangles, 0x03000000L, 0x04000000L)
    }
    assert(error.getMessage.contains("primitive arena"))
  }

  it should "reject negative, overflowing, unaligned and reversed bounds before execution" in {
    intercept[IllegalArgumentException] { NativeProductPrimitiveArena.allocate(-1) }
    intercept[IllegalArgumentException] { NativeProductPrimitiveArena.allocate(BigInt(1) << 64) }
    intercept[IllegalArgumentException] { NativeProductPrimitiveArena.validate(1, 1, 0x10000) }
    intercept[IllegalArgumentException] { NativeProductPrimitiveArena.validate(1, 0x10000, 0) }
    intercept[IllegalArgumentException] { NativeProductPrimitiveArena.validate(1, 0, (BigInt(1) << 32) + 64) }
  }

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
