package opengpu.graphics

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.sys.process._

/** The same byte images prepared by the production C scheduler/MMU helpers.
  * The C command boundary is mocked; its actual SATP helper writes are captured.
  * RTL execution and retirement remain the host test's responsibility.
  */
private[graphics] object TessellationRootedHostFixture {
  private def evidenceDirectory(prefix: String): Option[Path] = sys.props.get("opengpu.rootedHostEvidence").map { path =>
    Files.createTempDirectory(Files.createDirectories(Paths.get(path)), prefix)
  }

  def isolineCoverage(graphics: GraphicsConfig): Set[(Int, Int)] = {
    // Both TCS outer levels are1 from patch input1.0: one v=0 segment u=0..1.
    // TES exports (u,v,0,1); the4x4 viewport maps it to (2,2)->(4,2).
    // Basic width1 uses perturbed diamond exit, including its half-open end.
    val unit = BigInt(1) << graphics.subPixelBits
    val coverage = PointLineReference.samples(Vector((2 * unit, 2 * unit), (4 * unit, 2 * unit)), 1,
      TriangleRasterReference.Settings(width=4, height=4), graphics).flatten
      .filter(_.mask != 0).map(p => (p.x, p.y)).toSet
    assert(coverage == Set((2, 1), (3, 1)), s"fixture isoline geometry changed: $coverage")
    coverage
  }

  final case class Region(name: String, va: BigInt, dma: BigInt, bytes: Array[Byte]) {
    def contains(address: BigInt, count: Int): Boolean =
      address >= dma && address + count <= dma + bytes.length
  }

  final class Prepared(val descriptor: ujson.Value, val regions: Vector[Region],
      val textureBits: BigInt, val uniformBits: BigInt) {
    private val byName = regions.map(r => r.name -> r).toMap
    val jobVa: BigInt = BigInt(descriptor("commandArguments")("source").num.toLong)
    val jobDma: BigInt = byName("job").dma
    val rootDma: BigInt = byName("root-data").dma
    val colorDma: BigInt = byName("frame-data").dma + 0x27000
    def regionDma(name: String): BigInt = byName(name).dma
    val satpWrites: Vector[(Int, Int)] = descriptor("satpWrites").arr.toVector.map { pair =>
      pair(0).num.toInt -> pair(1).num.toLong.toInt
    }
    val tlbWrites: Vector[(Int, Int)] = descriptor("tlbWrites").arr.toVector.map { pair =>
      pair(0).num.toInt -> pair(1).num.toLong.toInt
    }
    val snapshotInvalidations: Vector[(BigInt, BigInt)] = descriptor("snapshotInvalidations").arr.toVector.map { pair =>
      BigInt(pair(0).num.toLong) -> BigInt(pair(1).num.toLong)
    }
    // The fixture CPU also replaces these retained backing bytes between runs.
    // Publish them explicitly, separately from the C-observed snapshot calls.
    val backingPublications: Vector[(BigInt, BigInt)] = Vector("frame-data", "patch-data").map { name =>
      byName(name).dma -> BigInt(byName(name).bytes.length)
    }
    private val commandFields = Vector(
      GpuCommandMmioRegs.OPCODE -> "opcode", GpuCommandMmioRegs.SOURCE -> "source",
      GpuCommandMmioRegs.DESTINATION -> "destination", GpuCommandMmioRegs.BYTES -> "bytes",
      GpuCommandMmioRegs.PATTERN -> "pattern", GpuCommandMmioRegs.SAMPLE_MODE -> "sampleMode",
      GpuCommandMmioRegs.WIDTH -> "width", GpuCommandMmioRegs.HEIGHT -> "height",
      GpuCommandMmioRegs.SOURCE_STRIDE -> "sourceStride", GpuCommandMmioRegs.DESTINATION_STRIDE -> "destinationStride",
      GpuCommandMmioRegs.FLAGS -> "flags")
    val commandWrites: Vector[(Int, Int)] = commandFields.map { case (register, name) =>
      register -> descriptor("commandArguments")(name).num.toLong.toInt
    }
    val expectedPixel: Vector[Int] = {
      val packed = TypedColorReference.encode("RGBA8", Vector(textureBits, uniformBits, BigInt(0), BigInt(0x3f800000L)))._1
      Vector.tabulate(4)(i => ((packed >> (8 * i)) & 255).toInt)
    }
    val clearPixel: Vector[Int] = {
      val packed = TypedColorReference.encode("RGBA8", Vector(0.1, 0.2, 0.3, 0.4).map(TypedColorReference.fp))._1
      Vector.tabulate(4)(i => ((packed >> (8 * i)) & 255).toInt)
    }

    def mappedLine(address: BigInt): Boolean = regions.exists(_.contains(address & ~BigInt(63), 64))
    def readLine(address: BigInt): BigInt = {
      val base = address & ~BigInt(63)
      regions.find(_.contains(base, 64)).map { r =>
        val offset = (base - r.dma).toInt
        (0 until 64).foldLeft(BigInt(0))((value, i) => value | (BigInt(r.bytes(offset + i) & 255) << (8 * i)))
      }.getOrElse(BigInt(0)) // The host responder separately faults unmapped reads.
    }
    def write(address: BigInt, data: BigInt, mask: BigInt): Unit = {
      val r = regions.find(_.contains(address, 64)).getOrElse(
        throw new AssertionError(s"GPU store outside C-exported backing at $address"))
      assert(r.name == "frame-data", s"GPU store changed retained ${r.name} at $address")
      val offset = (address - r.dma).toInt
      for (i <- 0 until 64 if mask.testBit(i)) r.bytes(offset + i) = ((data >> (8 * i)) & 255).toByte
    }
    def pixels: Vector[Vector[Int]] = {
      val r = byName("frame-data"); val offset = (colorDma - r.dma).toInt
      Vector.tabulate(16)(i => Vector.tabulate(4)(c => r.bytes(offset + i * 4 + c) & 255))
    }

    def recordStart(id: Int, domain: String, uniformFault: Boolean): Option[Path] =
      evidenceDirectory(s"run-$id-$domain-").map { directory =>
        val entries = regions.map { r =>
          Files.write(directory.resolve(r.name + ".bin"), r.bytes)
          val sha = MessageDigest.getInstance("SHA-256").digest(r.bytes).map(b => f"${b & 255}%02x").mkString
          ujson.Obj("name" -> r.name, "va" -> r.va.toLong.toDouble, "dma" -> r.dma.toLong.toDouble,
            "bytes" -> r.bytes.length, "sha256" -> sha)
        }
        Files.writeString(directory.resolve("start.json"), ujson.write(ujson.Obj(
          "id" -> id, "domain" -> domain, "uniformFault" -> uniformFault,
          "textureBits" -> textureBits.toLong.toDouble, "uniformBits" -> uniformBits.toLong.toDouble,
          "prepared" -> descriptor, "regions" -> ujson.Arr.from(entries)), indent=2))
        directory
      }

    def recordResult(evidence: Option[Path], reads: Seq[BigInt], completion: BigInt, flags: BigInt): Unit =
      evidence.foreach { directory =>
        Files.writeString(directory.resolve("result.json"), ujson.write(ujson.Obj(
          "completion" -> completion.toLong.toDouble, "flags" -> flags.toLong.toDouble,
          "acceptedPhysicalReads" -> ujson.Arr.from(reads.distinct.sorted.map(a => ujson.Num(a.toLong.toDouble))),
          "pixels" -> ujson.Arr.from(pixels.map(p => ujson.Arr.from(p)))), indent=2))
      }
  }

  def prepare(bytes: mutable.Map[BigInt, Int], textureBits: BigInt, uniformBits: BigInt): Prepared = {
    val directory = Files.createTempDirectory("opengpu-rooted-tessellation-")
    val evidence = evidenceDirectory("prepared-")
    val input = Files.createDirectory(directory.resolve("input"))
    val output = Files.createDirectory(directory.resolve("output"))
    def save(name: String, base: BigInt, count: Int): Unit =
      Files.write(input.resolve(name), Array.tabulate[Byte](count)(i => bytes.getOrElse(base + i, 0).toByte))
    try {
      save("job.bin", 0x8d0000, 512); save("frame.bin", 0x8d1000, 2048)
      save("draws.bin", 0x8e0000, 2048); save("states.bin", 0x8000, 1024)
      save("code.bin", 0x1000, 0x3000); save("patch.bin", 0x8f7000, 4096)
      val prepare = Seq("python3", "scripts/tessellation_rooted_host_fixture.py", "prepare", input.toString,
        "--texture-bits", textureBits.toString, "--uniform-bits", uniformBits.toString)
      assert(prepare.! == 0, "rooted resource/shader adapter failed")
      assert(Seq("python3", "scripts/test_tessellation_schedule.py", "--export-rooted", input.toString, output.toString).! == 0,
        "C scheduler/MMU did not export the rooted job")
      assert(Seq("python3", "scripts/tessellation_rooted_host_fixture.py", "validate", output.toString).! == 0,
        "C-exported Sv32/snapshot roundtrip failed")
      val descriptor = ujson.read(Files.readString(output.resolve("prepared.json")))
      val regions = descriptor("regions").arr.toVector.map { r =>
        Region(r("name").str, BigInt(r("va").num.toLong), BigInt(r("dma").num.toLong),
          Files.readAllBytes(output.resolve(r("file").str)))
      }
      new Prepared(descriptor, regions, textureBits, uniformBits)
    } finally {
      val paths = Files.walk(directory)
      try {
        val all = paths.iterator().asScala.toVector.sortBy(_.getNameCount)
        evidence.foreach { destination =>
          Files.writeString(destination.resolve("values.json"),
            s"{\"textureBits\":$textureBits,\"uniformBits\":$uniformBits}\n")
          for (source <- all if Files.isRegularFile(source)) {
            val target = destination.resolve(directory.relativize(source))
            Files.createDirectories(target.getParent)
            Files.copy(source, target)
          }
        }
        all.reverse.foreach(Files.delete)
      }
      finally paths.close()
    }
  }
}
