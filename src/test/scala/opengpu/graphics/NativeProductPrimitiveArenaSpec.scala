package opengpu.graphics

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
}
