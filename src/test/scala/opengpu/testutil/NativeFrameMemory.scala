package opengpu.testutil

import scala.collection.mutable

/** Disjoint, pitched stored/resolve images for explicit frame qualification.
  * Layout construction allocates no image bytes and never wraps a 32-bit VA.
  */
object NativeFrameMemory {
  val AddressLimit: Long = 1L << 32
  private def align64(value: Long): Long = (value + 63) & ~63L

  case class Plane(location: Int, resolve: Boolean, width: Int, height: Int,
    samples: Int, bytesPerSample: Int, allocationBase: Long, base: Long,
    rowPitch: Long, limit: Long, allocationLimit: Long) {
    def address(x: Int, y: Int, sample: Int = 0): Long = {
      require(x >= 0 && x < width && y >= 0 && y < height)
      require(sample >= 0 && sample < samples)
      base + y * rowPitch + (x.toLong * samples + sample) * bytesPerSample
    }
  }

  def layout(width: Int, height: Int, samples: Int, bytesPerSample: Seq[Int],
    start: Long, end: Long = AddressLimit): Vector[Plane] = {
    require(width > 0 && width <= 65535 && height > 0 && height <= 65535)
    require(Set(1, 2, 4)(samples))
    require(bytesPerSample.nonEmpty && bytesPerSample.forall(n => n > 0 && n <= 16))
    require(start >= 0 && start < end && end <= AddressLimit)
    var cursor = align64(start)
    (for (resolve <- Vector(false, true); (bytes, location) <- bytesPerSample.zipWithIndex) yield {
      val count = if (resolve) 1 else samples
      val allocationBase = cursor
      // Keep unaligned bases, row padding and whole guard lines. Adjacent
      // attachments get separate ranges, including their final row padding.
      val base = allocationBase + 64 + (if (resolve) 59 else 61)
      val pitch = width.toLong * count * bytes + 7
      val limit = base + (height - 1L) * pitch + width.toLong * count * bytes
      val allocationLimit = align64(base + height.toLong * pitch) + 64
      require(allocationLimit <= end, "frame backing does not fit the address range")
      cursor = allocationLimit
      Plane(location, resolve, width, height, count, bytes, allocationBase,
        base, pitch, limit, allocationLimit)
    }).toVector
  }
}

/** Sparse byte-addressed test memory. Uniform image guards occupy range records;
  * only pages modified by a command or the DUT allocate byte arrays. This is
  * simulation storage, not an approximation of GPU memory or its timing.
  */
final class PagedFrameMemory {
  import NativeFrameMemory.AddressLimit
  private val pageBytes = 4096
  private case class Fill(start: Long, end: Long, value: Int)
  private var fills = Vector.empty[Fill]
  private val pages = mutable.LongMap.empty[Array[Byte]]

  private def checked(address: Long): Unit = require(address >= 0 && address < AddressLimit)
  private def default(address: Long): Int =
    fills.find(f => address >= f.start && address < f.end).fold(0)(_.value)

  def mapRange(start: Long, end: Long, value: Int): Unit = {
    require(start >= 0 && start < end && end <= AddressLimit)
    require(value >= 0 && value <= 255)
    require(!fills.exists(f => start < f.end && f.start < end), "mapped ranges overlap")
    require(!pages.keysIterator.exists(p => p < end && start < p + pageBytes),
      "map ranges before writing their pages")
    fills :+= Fill(start, end, value)
  }

  def readByte(address: Long): Int = {
    checked(address)
    pages.get(address & ~(pageBytes - 1L)) match {
      case Some(page) => page((address & (pageBytes - 1)).toInt) & 255
      case None => default(address)
    }
  }

  def writeByte(address: Long, value: Int): Unit = {
    checked(address)
    require(value >= 0 && value <= 255)
    val start = address & ~(pageBytes - 1L)
    val page = pages.getOrElseUpdate(start,
      Array.tabulate[Byte](pageBytes)(i => default(start + i).toByte))
    page((address - start).toInt) = value.toByte
  }

  def readWord(address: Long): BigInt = {
    require(address >= 0 && address <= AddressLimit - 4)
    (0 until 4).foldLeft(BigInt(0))((v, b) => v | (BigInt(readByte(address + b)) << (8 * b)))
  }

  def writeWord(address: Long, value: BigInt): Unit = {
    require(address >= 0 && address <= AddressLimit - 4)
    require(value >= 0 && value.bitLength <= 32)
    for (b <- 0 until 4) writeByte(address + b, ((value >> (8 * b)) & 255).toInt)
  }

  def readLine(address: Long): BigInt = {
    require(address >= 0 && address <= AddressLimit - 64 && (address & 63) == 0)
    (0 until 64).foldLeft(BigInt(0))((v, b) => v | (BigInt(readByte(address + b)) << (8 * b)))
  }

  def writeLine(address: Long, data: BigInt, mask: BigInt): Unit = {
    require(address >= 0 && address <= AddressLimit - 64 && (address & 63) == 0)
    require(data >= 0 && data.bitLength <= 512 && mask >= 0 && mask.bitLength <= 64)
    for (b <- 0 until 64 if mask.testBit(b))
      writeByte(address + b, ((data >> (8 * b)) & 255).toInt)
  }

  /** Byte-array payload only; excludes JVM object and map overhead. */
  def allocatedPageBytes: Long = pages.size.toLong * pageBytes

  def snapshot(): PagedFrameMemory = {
    val result = new PagedFrameMemory
    fills.foreach(f => result.mapRange(f.start, f.end, f.value))
    pages.foreach { case (address, page) => result.pages(address) = page.clone() }
    result
  }
}

/** Image backing plus a streamed oracle. The caller independently supplies
  * stored sample and resolved pixel values; neither is inferred from DUT data.
  */
final class NativeFrameImage(width: Int, height: Int, samples: Int,
  formats: Seq[Int], start: Long, end: Long = NativeFrameMemory.AddressLimit,
  guardByte: Int = 0xa7) {
  require(guardByte >= 0 && guardByte <= 255)
  val planes: Vector[NativeFrameMemory.Plane] =
    NativeFrameMemory.layout(width, height, samples, formats, start, end)
  val memory = new PagedFrameMemory
  planes.foreach(p => memory.mapRange(p.allocationBase, p.allocationLimit, guardByte))

  /** Visits each active value and every guard/padding byte once, using bounded
    * temporary storage regardless of frame dimensions. Returns checked bytes.
    */
  def verify(expected: (Int, Boolean, Int, Int, Int) => BigInt): Long = {
    var checkedBytes = 0L
    def check(address: Long, value: Int): Unit = {
      val actual = memory.readByte(address)
      assert(actual == value,
        f"frame byte 0x$address%x differs: actual=0x$actual%02x expected=0x$value%02x")
      checkedBytes += 1
    }
    def guards(start: Long, end: Long): Unit = {
      var address = start
      while (address < end) { check(address, guardByte); address += 1 }
    }
    for (p <- planes) {
      guards(p.allocationBase, p.base)
      for (y <- 0 until height) {
        for (x <- 0 until width; sample <- 0 until p.samples) {
          val raw = expected(p.location, p.resolve, x, y, sample)
          require(raw >= 0 && raw.bitLength <= p.bytesPerSample * 8,
            "reference value does not fit the image format")
          val address = p.address(x, y, sample)
          for (byte <- 0 until p.bytesPerSample)
            check(address + byte, ((raw >> (byte * 8)) & 255).toInt)
        }
        val rowStart = p.base + y * p.rowPitch
        guards(rowStart + width.toLong * p.samples * p.bytesPerSample, rowStart + p.rowPitch)
      }
      guards(p.base + height.toLong * p.rowPitch, p.allocationLimit)
    }
    checkedBytes
  }
}
