package opengpu.testutil

import java.io.{BufferedInputStream, BufferedOutputStream, ByteArrayInputStream,
  ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.file.{Files, Path}

/** Lossless sparse interchange with the compiled native frame driver.
  * Uniform allocation fills and all modified pages are serialized separately;
  * the codec neither shades pixels nor changes modeled addresses or latency.
  */
object NativeFrameMemoryCodec {
  private val magic = "OGMEM001".getBytes("US-ASCII")
  private val pageBytes = 4096

  private def put32(out: DataOutputStream, value: Long): Unit =
    (0 until 4).foreach(i => out.writeByte((value >>> (i * 8)).toInt))
  private def put64(out: DataOutputStream, value: Long): Unit =
    (0 until 8).foreach(i => out.writeByte((value >>> (i * 8)).toInt))
  private def get32(in: DataInputStream): Long =
    (0 until 4).foldLeft(0L)((v, i) => v | (in.readUnsignedByte().toLong << (i * 8)))
  private def get64(in: DataInputStream): Long =
    (0 until 8).foldLeft(0L)((v, i) => v | (in.readUnsignedByte().toLong << (i * 8)))

  private def write(memory: PagedFrameMemory, out: DataOutputStream): Unit = {
    val ranges = memory.mappedRanges
    out.write(magic)
    put32(out, ranges.size)
    put32(out, memory.allocatedPageBytes / pageBytes)
    for ((start, end, value) <- ranges) {
      put64(out, start); put64(out, end); put32(out, value); put32(out, 0)
    }
    memory.visitPages { (address, bytes) => put64(out, address); out.write(bytes) }
  }

  private def read(in: DataInputStream, size: Long): PagedFrameMemory = {
    require(size >= 16, "short sparse memory header")
    val actual = new Array[Byte](magic.length); in.readFully(actual)
    require(actual.sameElements(magic), "unknown sparse memory format")
    val ranges = get32(in); val pages = get32(in)
    require(ranges <= Int.MaxValue && pages <= NativeFrameMemory.AddressLimit / pageBytes)
    require(size == 16L + ranges * 24 + pages * (pageBytes + 8L),
      "sparse memory length does not match its record counts")
    val memory = new PagedFrameMemory
    var previousEnd = 0L
    for (_ <- 0L until ranges) {
      val start = get64(in); val end = get64(in); val value = get32(in)
      require(get32(in) == 0, "nonzero sparse memory reserved field")
      require(start >= previousEnd, "unordered or overlapping snapshot ranges")
      memory.mapRange(start, end, value.toInt)
      previousEnd = end
    }
    var previousPage = -1L
    for (_ <- 0L until pages) {
      val address = get64(in)
      require(address > previousPage, "unordered or duplicate snapshot page")
      val bytes = new Array[Byte](pageBytes); in.readFully(bytes)
      memory.installPage(address, bytes)
      previousPage = address
    }
    require(in.read() == -1, "trailing sparse memory data")
    memory
  }

  def encode(memory: PagedFrameMemory): Array[Byte] = {
    val bytes = new ByteArrayOutputStream
    val out = new DataOutputStream(bytes)
    write(memory, out); out.flush(); bytes.toByteArray
  }

  def decode(bytes: Array[Byte]): PagedFrameMemory =
    read(new DataInputStream(new ByteArrayInputStream(bytes)), bytes.length)

  def write(memory: PagedFrameMemory, path: Path): Unit = {
    val out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))
    try write(memory, out) finally out.close()
  }

  def read(path: Path): PagedFrameMemory = {
    val in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))
    try read(in, Files.size(path)) finally in.close()
  }
}
