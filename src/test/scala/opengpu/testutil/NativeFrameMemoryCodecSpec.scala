package opengpu.testutil

import java.nio.{ByteBuffer, ByteOrder}
import opengpu.graphics.{NativeProductFrameInputs, NativeProductWorkloads}
import org.scalatest.flatspec.AnyFlatSpec

class NativeFrameMemoryCodecSpec extends AnyFlatSpec {
  behavior of "sparse native frame interchange"

  it should "retain sparse guards, modified pages and the last RV32 byte without expanding fills" in {
    val memory = new PagedFrameMemory
    memory.mapRange(0x20000000L, 0x21000000L, 0xa7)
    memory.mapRange(0xfffff000L, 1L << 32, 0x83)
    memory.writeWord(0x20000ffeL, BigInt("fedcba98", 16))
    memory.writeByte(0xffffffffL, 0x5e)
    val encoded = NativeFrameMemoryCodec.encode(memory)
    assert(encoded.length == 16 + 2 * 24 + 3 * 4104)
    val decoded = NativeFrameMemoryCodec.decode(encoded)
    assert(decoded.readWord(0x20000ffeL) == BigInt("fedcba98", 16))
    assert(decoded.readByte(0x20800000L) == 0xa7)
    assert(decoded.readByte(0xfffff010L) == 0x83)
    assert(decoded.readByte(0xffffffffL) == 0x5e)
    assert(decoded.readByte(12345) == 0)
    decoded.writeByte(0x20000ffeL, 0)
    assert(memory.readByte(0x20000ffeL) == 0x98)
  }

  it should "write one deterministic byte stream regardless of range and page insertion order" in {
    def image(reverse: Boolean): PagedFrameMemory = {
      val memory = new PagedFrameMemory
      val ranges = Vector((0x10000L, 0x11000L, 31), (0x20000L, 0x21000L, 79))
      (if (reverse) ranges.reverse else ranges).foreach { case (s, e, v) => memory.mapRange(s, e, v) }
      (if (reverse) Vector(0x20001L, 0x10007L) else Vector(0x10007L, 0x20001L))
        .foreach(a => memory.writeByte(a, (a & 255).toInt))
      memory
    }
    val bytes = NativeFrameMemoryCodec.encode(image(false))
    assert(bytes.sameElements(NativeFrameMemoryCodec.encode(image(true))))
    assert(bytes.sameElements(NativeFrameMemoryCodec.encode(NativeFrameMemoryCodec.decode(bytes))))
  }

  it should "reject corrupt counts, range overlaps, duplicate pages, reserved fields and truncation" in {
    val memory = new PagedFrameMemory
    memory.mapRange(0x1000, 0x2000, 0xa7); memory.mapRange(0x3000, 0x4000, 0)
    memory.writeByte(0x1000, 1); memory.writeByte(0x3000, 2)
    val good = NativeFrameMemoryCodec.encode(memory)
    def changed(offset: Int, value: Long, wide: Boolean = true): Array[Byte] = {
      val bytes = good.clone(); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      if (wide) buffer.putLong(offset, value) else buffer.putInt(offset, value.toInt)
      bytes
    }
    for (bad <- Vector(good.take(15), good.dropRight(1), good ++ Array[Byte](0),
      changed(8, 0xffffffffL, false), changed(40, 0x1800), changed(36, 1, false),
      changed(64, 0x1001), changed(64 + 4104, 0x1000))) {
      intercept[IllegalArgumentException] { NativeFrameMemoryCodec.decode(bad) }
    }
  }

  it should "preserve the complete input backing of all six development workloads" in {
    for (scene <- NativeProductWorkloads.suite(32, 32)) {
      val input = NativeProductFrameInputs.encode(scene)
      val bytes = NativeFrameMemoryCodec.encode(input.memory)
      val decoded = NativeFrameMemoryCodec.decode(bytes)
      assert(bytes.sameElements(NativeFrameMemoryCodec.encode(decoded)), scene.id)
      for (region <- input.regions; address <- Vector(region.base, region.limit - 1))
        assert(decoded.readByte(address) == input.memory.readByte(address), (scene.id, region.name))
    }
  }
}
