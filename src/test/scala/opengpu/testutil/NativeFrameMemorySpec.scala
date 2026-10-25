package opengpu.testutil

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class NativeFrameMemorySpec extends AnyFlatSpec {
  behavior of "native frame backing memory"

  it should "separate every stored and resolved image at bounded and product dimensions" in {
    // These are allocation/address boundary checks, not large rendering tests.
    for ((width, height) <- Seq((32, 32), (640, 480), (1280, 720)); samples <- Seq(1, 2, 4)) {
      val planes = NativeFrameMemory.layout(width, height, samples, Seq(4, 3, 8, 4, 4), 0x4000000)
      assert(planes.size == 10)
      for (pair <- planes.sliding(2)) assert(pair.head.allocationLimit <= pair.last.allocationBase)
      for (p <- planes) {
        assert(p.base - p.allocationBase >= 64)
        assert(p.allocationLimit - p.limit >= 64)
        assert(p.address(width - 1, height - 1, p.samples - 1) + p.bytesPerSample == p.limit)
        assert(p.samples == (if (p.resolve) 1 else samples))
      }
      val pixels = planes.map(p => width.toLong * height * p.samples * p.bytesPerSample).sum
      assert(pixels == width.toLong * height * 23 * (samples + 1))
    }
  }

  it should "reject address wrap and invalid layouts before touching image memory" in {
    intercept[IllegalArgumentException] {
      NativeFrameMemory.layout(1280, 720, 4, Seq(8), NativeFrameMemory.AddressLimit - 65536)
    }
    intercept[IllegalArgumentException] { NativeFrameMemory.layout(0, 1, 1, Seq(4), 0) }
    intercept[IllegalArgumentException] { NativeFrameMemory.layout(1, 1, 3, Seq(4), 0) }
    intercept[IllegalArgumentException] { NativeFrameMemory.layout(1, 1, 1, Seq(0), 0) }
    val p = NativeFrameMemory.layout(1, 1, 1, Seq(4), 0).head
    intercept[IllegalArgumentException] { p.address(1, 0) }
    intercept[IllegalArgumentException] { p.address(0, 0, 1) }
  }

  it should "keep full product image guards sparse and isolate neighboring image bytes" in {
    val memory = new PagedFrameMemory
    val planes = NativeFrameMemory.layout(1280, 720, 4, Seq(4, 3, 8, 4, 4), 0x4000000)
    planes.foreach(p => memory.mapRange(p.allocationBase, p.allocationLimit, 0xa7))
    assert(memory.allocatedPageBytes == 0)
    for ((p, index) <- planes.zipWithIndex) {
      val address = p.address(p.width - 1, p.height - 1, p.samples - 1)
      memory.writeByte(address, index)
      assert(memory.readByte(address) == index)
      assert(memory.readByte(p.base) == 0xa7)
      assert(memory.readByte(p.allocationBase) == 0xa7)
      assert(memory.readByte(p.limit) == 0xa7)
      assert(memory.readByte(p.allocationLimit - 1) == 0xa7)
    }
    assert(memory.allocatedPageBytes == planes.size * 4096L)
  }

  it should "match independent byte writes under randomized masked AXI lines" in {
    val random = new Random(0x51a7)
    val memory = new PagedFrameMemory
    val expected = mutable.Map.empty[Long, Int]
    memory.mapRange(0x103d, 0x905b, 0xa7)
    def initial(a: Long): Int = if (a >= 0x103d && a < 0x905b) 0xa7 else 0
    for (_ <- 0 until 300) {
      val address = random.nextInt(640) * 64L
      val data = BigInt(512, random)
      val mask = BigInt(64, random)
      memory.writeLine(address, data, mask)
      for (b <- 0 until 64 if mask.testBit(b))
        expected(address + b) = ((data >> (8 * b)) & 255).toInt
      val raw = memory.readLine(address)
      for (b <- 0 until 64)
        assert(((raw >> (8 * b)) & 255).toInt == expected.getOrElse(address + b, initial(address + b)))
    }
    for (a <- 0L until 40960L) assert(memory.readByte(a) == expected.getOrElse(a, initial(a)))
  }

  it should "snapshot touched pages independently and preserve cross-page little-endian words" in {
    val memory = new PagedFrameMemory
    memory.mapRange(0, 16384, 0xa7)
    memory.writeWord(4094, BigInt("1234ff80", 16))
    assert(memory.readWord(4094) == BigInt("1234ff80", 16))
    assert(memory.readByte(4093) == 0xa7 && memory.readByte(4098) == 0xa7)
    val snapshot = memory.snapshot()
    memory.writeWord(4094, 0)
    snapshot.writeByte(8192, 3)
    assert(snapshot.readWord(4094) == BigInt("1234ff80", 16))
    assert(memory.readWord(4094) == 0 && memory.readByte(8192) == 0xa7)
    assert(snapshot.allocatedPageBytes == 3 * 4096L && memory.allocatedPageBytes == 2 * 4096L)
  }

  it should "retain zero-strobe memory and validate boundaries without partial writes" in {
    val memory = new PagedFrameMemory
    memory.writeLine(0, (BigInt(1) << 512) - 1, 0)
    assert(memory.allocatedPageBytes == 0 && memory.readLine(0) == 0)
    val last = NativeFrameMemory.AddressLimit - 4
    memory.writeWord(last, BigInt("fedcba98", 16))
    assert(memory.readWord(last) == BigInt("fedcba98", 16))
    intercept[IllegalArgumentException] { memory.writeWord(last + 1, 1) }
    intercept[IllegalArgumentException] { memory.writeLine(1, 0, 1) }
    intercept[IllegalArgumentException] { memory.writeByte(-1, 0) }
    intercept[IllegalArgumentException] { memory.writeByte(0, 256) }
    memory.mapRange(0x1000, 0x2000, 1)
    intercept[IllegalArgumentException] { memory.mapRange(0x1fff, 0x3000, 2) }
    intercept[IllegalArgumentException] { memory.mapRange(last, last + 4, 2) }
    assert(memory.readWord(last) == BigInt("fedcba98", 16))
  }

  private def reference(location: Int, resolve: Boolean, x: Int, y: Int, sample: Int): BigInt =
    BigInt((location << 16) | ((if (resolve) 1 else 0) << 15) | (y << 9) | (x << 3) | sample)

  private def populated(): NativeFrameImage = {
    val image = new NativeFrameImage(32, 32, 4, Seq(4, 3, 8, 4, 4), 0x10003)
    for (p <- image.planes; y <- 0 until p.height; x <- 0 until p.width; sample <- 0 until p.samples) {
      val raw = reference(p.location, p.resolve, x, y, sample)
      for (byte <- 0 until p.bytesPerSample)
        image.memory.writeByte(p.address(x, y, sample) + byte, ((raw >> (8 * byte)) & 255).toInt)
    }
    image
  }

  it should "stream all stored samples and resolve values including every padding and guard byte" in {
    val image = populated()
    val checked = image.verify(reference)
    assert(checked == image.planes.map(p => p.allocationLimit - p.allocationBase).sum)
    assert(checked > 32L * 32 * 23 * 5)
  }

  it should "detect corrupt stored samples, resolves, row padding and both guard ends" in {
    val image = populated()
    val p = image.planes.head
    val resolve = image.planes.find(_.resolve).get
    val locations = Seq(p.address(31, 31, 3) + 3, resolve.address(31, 31),
      p.base + 32L * p.samples * p.bytesPerSample, p.allocationBase, p.allocationLimit - 1)
    for (address <- locations) {
      val before = image.memory.readByte(address)
      image.memory.writeByte(address, before ^ 1)
      val error = intercept[AssertionError] { image.verify(reference) }
      assert(error.getMessage.contains(java.lang.Long.toHexString(address)))
      image.memory.writeByte(address, before)
    }
    image.verify(reference)
  }

  it should "reject an oracle value that exceeds its format without truncating it" in {
    val image = new NativeFrameImage(1, 1, 1, Seq(3), 0)
    intercept[IllegalArgumentException] { image.verify((_, _, _, _, _) => BigInt(1) << 24) }
  }
}
