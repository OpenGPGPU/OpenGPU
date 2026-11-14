package opengpu.system

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path, Paths}
import opengpu.graphics.{GpuCommandMmioRegs, NativeLayeredGraphicsLayout, NativeLayeredProductFrameInputs, NativeProductFrameInputs, NativeProductWorkloads, RenderHostRegs}
import opengpu.testutil.{GraphicsTestResolution, NativeFrameMemoryCodec}
import org.scalatest.flatspec.AnyFlatSpec
import scala.jdk.CollectionConverters._

/** Exercises serialized inputs only; never supplies fabricated GPU execution results. */
class NativeLayeredProductFixturesSpec extends AnyFlatSpec {
  private def temporary(body: Path => Unit): Unit = {
    val parent = Paths.get(".artifacts")
    Files.createDirectories(parent)
    val directory = Files.createTempDirectory(parent, "layered-fixtures-")
    try body(directory) finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.delete)
      finally paths.close()
    }
  }
  private def qualified(body: => Unit): Unit = {
    val previous = sys.props.get("opengpu.fullHostQualification")
    sys.props("opengpu.fullHostQualification") = "1"
    try body finally previous match {
      case Some(value) => sys.props("opengpu.fullHostQualification") = value
      case None => sys.props.remove("opengpu.fullHostQualification")
    }
  }
  private def validateCommands(path: Path, scene: NativeProductWorkloads.Scene,
      input: NativeProductFrameInputs.Inputs, id: Int, product: Boolean): Unit = {
    val bytes = Files.readAllBytes(path)
    assert(new String(bytes.take(8), "US-ASCII") == "OGLAY001")
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    data.position(8)
    def word(): Long = data.getInt().toLong & 0xffffffffL
    assert(Vector.fill(4)(word()) == Vector(scene.width, scene.height, scene.samples, id).map(_.toLong))
    assert(data.getLong() == (if (product) 1000000000L else 10000000L))
    assert(word() == 104)
    val discovery = Vector.fill(104)((word(), word()))
    assert(discovery == OwnerResourceProfile.target.requiredDiscovery.map { case (a, v) => (a.toLong, v.toLong) })
    assert(discovery(2)._2 == 0xfffff && discovery.takeRight(8).head._1 == 0x500)
    assert(word() == 8)
    assert(Vector.fill(8)((word(), word())) == Vector[(Long, Long)](
      RenderHostRegs.IRQ.toLong -> 1L, GpuCommandMmioRegs.COMMAND_ID.toLong -> id.toLong,
      GpuCommandMmioRegs.OPCODE.toLong -> 7L, GpuCommandMmioRegs.SOURCE.toLong -> input.jobBase,
      GpuCommandMmioRegs.DESTINATION.toLong -> (input.jobBase + 512), GpuCommandMmioRegs.BYTES.toLong -> 512L,
      GpuCommandMmioRegs.PATTERN.toLong -> 0L, GpuCommandMmioRegs.SUBMIT.toLong -> 1L))
    assert(word() == 13)
    assert((word(), word(), word()) == (GpuCommandMmioRegs.COMPLETION.toLong, 0xffffL, 0x8700L | id))
    for (i <- 0 until 12)
      assert((word(), word(), word()) == (0x180L + 4 * i,
        if (Set(0, 1, 11)(i)) 0xffffffffL else 0L, if (i == 0) 0x474e5801L else 0L))
    assert(word() == 2)
    assert(Vector.fill(2)((word(), word())) == Vector[(Long, Long)](
      GpuCommandMmioRegs.COMPLETION_POP.toLong -> 1L, RenderHostRegs.IRQ.toLong -> 3L))
    assert(!data.hasRemaining)
  }

  it should "serialize all six GNJ2 recipes with exact full discovery and a separate command job view" in temporary { directory =>
    for (tileLog2 <- Seq(3, 4);
        (scene, index) <- NativeProductWorkloads.suite(GraphicsTestResolution.width.min(16),
          GraphicsTestResolution.height.min(16)).zipWithIndex) {
      val input = NativeLayeredProductFrameInputs.encode(scene, tileLog2)
      val path = directory.resolve(scene.id + ".commands")
      for (product <- Seq(false, true)) {
        NativeLayeredProductFixtures.writeCommands(path, scene, input, index + 1, product)
        validateCommands(path, scene, input, index + 1, product)
      }
      assert(input.completionBase != input.jobBase + 512)
      assert(input.memory.readWord(input.jobBase + 48 * 4) == input.completionBase)
      assert(input.job("completion.limit") - input.completionBase == NativeLayeredGraphicsLayout.completionBytes)
      intercept[IllegalArgumentException] {
        NativeLayeredProductFixtures.writeCommands(path, scene,
          NativeProductFrameInputs.encodeApiExtentTiled(scene, tileLog2), index + 1, product=false)
      }
    }
  }

  it should "prepare six fresh data-only fixtures and reject original input as a returned GPU result" in qualified {
    for (tileLog2 <- Seq(3, 4)) temporary { directory =>
      val args = Array("--smoke", "--output", directory.toString, "--tile-log2", tileLog2.toString)
      PrepareNativeLayeredProductFixtures.main(args)
      val fixtures = directory.resolve("fixtures")
      assert(Files.readString(fixtures.resolve("suite.txt")) == NativeProductWorkloads.ids.mkString("", "\n", "\n"))
      val stream = Files.list(fixtures)
      try assert(stream.count() == 13) finally stream.close()
      val manifest = Files.readString(directory.resolve("layer-product-fixture-manifest.json"))
      assert(manifest == NativeLayeredProductFixtures.manifest(false, 32, 32, tileLog2))
      assert(manifest.startsWith(PrepareOwnerResourceFullHost.manifest(false).trim.dropRight(1)))
      for ((scene, index) <- NativeProductWorkloads.suite(32, 32).zipWithIndex) {
        val input = NativeLayeredProductFrameInputs.encode(scene, tileLog2)
        validateCommands(fixtures.resolve(scene.id + ".commands"), scene, input, index + 1, product=false)
        val actual = NativeFrameMemoryCodec.read(fixtures.resolve(scene.id + ".memory"))
        assert(NativeFrameMemoryCodec.sameAllocations(input.memory, actual))
        assert(NativeFrameMemoryCodec.unchangedOutside(input.memory, actual, Seq.empty))
        assert(actual.readWord(input.jobBase) == NativeLayeredGraphicsLayout.jobMagic)
      }
      assert(!Files.exists(directory.resolve("rtl")))
      intercept[IllegalArgumentException](PrepareNativeLayeredProductFixtures.main(args))
      intercept[AssertionError] {
        VerifyNativeLayeredProduct.main(Array("--smoke", "--output", fixtures.toString,
          "--tile-log2", tileLog2.toString))
      }
    }
    assert(CompiledNativeProductFixtures.frameSize(true, twoCuVga=true) == (640, 480))
    assert(NativeLayeredProductFixtures.manifest(true, 640, 480, 4).contains("\"workloadProduct\":true"))
    intercept[IllegalArgumentException](NativeLayeredProductFixtures.manifest(true, 1280, 720, 3))
    intercept[IllegalArgumentException](NativeLayeredProductFixtures.manifest(false, 32, 32, 5))
  }
}
