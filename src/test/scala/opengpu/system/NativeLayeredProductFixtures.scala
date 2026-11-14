package opengpu.system

import java.io.{BufferedOutputStream, DataOutputStream}
import java.nio.file.{Files, Path}
import opengpu.graphics.{GpuCommandMmioRegs, NativeLayeredGraphicsLayout, NativeLayeredProductFrameInputs, NativeProductFrameInputs, NativeProductPrimitiveArena, NativeProductWorkloads, RenderHostRegs}
import opengpu.testutil.NativeFrameMemoryCodec

/** Data-only inputs for the complete owner factory. No RTL or GPU output is produced. */
private[system] object NativeLayeredProductFixtures {
  sealed trait Factory {
    def profile: NativeAlignmentHostConfig
    def manifest: String
    def magic: String
    def marker: String
    def id: String
    def words: Int
    def fixtureManifest: String
  }
  case object Full extends Factory {
    def profile=OwnerResourceProfile.target
    def manifest=PrepareOwnerResourceFullHost.manifest(rtlEmitted=false).trim
    val magic="OGLAY001";val marker="NATIVE_LAYERED_PRODUCT";val words=104
    def id=OwnerResourceProfile.id
    val fixtureManifest="layer-product-fixture-manifest.json"
  }
  case object Sample extends Factory {
    def profile=OwnerResourceProfile.sampleStage
    def manifest=PrepareOwnerResourceSampleHost.manifest(rtlEmitted=false).trim
    val magic="OGLAY002";val marker="NATIVE_SAMPLE_LAYERED_PRODUCT";val words=112
    def id=OwnerResourceProfile.sampleStageId
    val fixtureManifest="sample-layer-product-fixture-manifest.json"
  }
  def manifest(product: Boolean, width: Int, height: Int, tileLog2: Int, factory: Factory=Full): String = {
    require(tileLog2 == 3 || tileLog2 == 4)
    require((width, height) == CompiledNativeProductFixtures.frameSize(product, twoCuVga=true))
    val base = factory.manifest
    val scenes = NativeProductWorkloads.ids.map(s => s"\"$s\"").mkString(",")
    val scope=if(factory==Sample)
      ""","workloadContainerMagic":"OGLAY002","workloadFragmentVersion":1,"sampleBuiltinsQualified":false""" else ""
    base.dropRight(1) + s""","workloadProduct":$product,"workloadWidth":$width,"workloadHeight":$height,"workloadTileLog2":$tileLog2,"scenes":[$scenes]$scope}""" + "\n"
  }

  def writeCommands(path: Path, scene: NativeProductWorkloads.Scene,
      input: NativeProductFrameInputs.Inputs, id: Int, product: Boolean, factory: Factory=Full): Unit = {
    require(id >= 1 && id <= NativeProductWorkloads.ids.size)
    require(NativeProductWorkloads.ids(id - 1) == scene.id, "scene command ID differs from suite order")
    require(input.memory.readWord(input.jobBase) == NativeLayeredGraphicsLayout.jobMagic &&
      input.memory.readWord(input.jobBase + 8) == 2 && input.frame("frame.version") == 3,
      "layered fixtures require GNJ2/GNF3 inputs")
    require(input.job("completion.base") == input.completionBase &&
      input.completionBase != input.jobBase + 512, "GNY2 backing must be separate from the command job view")
    require(input.states.forall(_("fragment.version")==1), "original product recipes retain fragment ABI1")
    NativeProductPrimitiveArena.validate(scene.primitiveCount,
      input.frame("frame.bins.producer.arena.base"), input.frame("frame.bins.producer.arena.limit"))
    val resources = factory.profile.requiredDiscovery
    require(resources.size == factory.words, "layered fixtures require the complete owner factory")
    val out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))
    def put32(value: Long): Unit = CompiledNativeProductFixtures.put32(out, value)
    try {
      out.write(factory.magic.getBytes("US-ASCII"))
      Seq(scene.width, scene.height, scene.samples, id).foreach(v => put32(v.toLong))
      CompiledNativeProductFixtures.put64(out, if (product) 1000000000L else 10000000L)
      put32(resources.size)
      resources.foreach { case (address, value) => put32(address); put32(value.toLong) }
      val writes = Vector[(Int, Long)](
        RenderHostRegs.IRQ -> 1L, GpuCommandMmioRegs.COMMAND_ID -> id.toLong,
        GpuCommandMmioRegs.OPCODE -> 7L, GpuCommandMmioRegs.SOURCE -> input.jobBase,
        GpuCommandMmioRegs.DESTINATION -> (input.jobBase + 512),
        GpuCommandMmioRegs.BYTES -> 512L, GpuCommandMmioRegs.PATTERN -> 0L,
        GpuCommandMmioRegs.SUBMIT -> 1L)
      put32(writes.size)
      writes.foreach { case (address, value) => put32(address); put32(value) }
      // Read all twelve GNX1 observation registers at the retirement cycle.
      // The separate GNY2 backing, not these narrow counters, is the wide result.
      val checks = Vector[(Int, Long, Long)](
        (GpuCommandMmioRegs.COMPLETION, 0xffffL, 0x8700L | id)) ++
        Vector.tabulate(12) { i =>
          (0x180 + i * 4, if (Set(0, 1, 11)(i)) 0xffffffffL else 0L,
            if (i == 0) 0x474e5801L else 0L)
        }
      put32(checks.size)
      checks.foreach { case (address, mask, value) => put32(address); put32(mask); put32(value) }
      val finish = Vector(GpuCommandMmioRegs.COMPLETION_POP -> 1, RenderHostRegs.IRQ -> 3)
      put32(finish.size)
      finish.foreach { case (address, value) => put32(address); put32(value) }
    } finally out.close()
  }

  def prepare(args: Array[String],factory: Factory): Unit = {
    val (product, output, tileLog2) = PrepareNativeOwnerFixtures.mode(args)
    require(Files.isDirectory(output), "prepare the complete owner DUT directory first")
    val fixtures = output.resolve("fixtures")
    val manifest = output.resolve(factory.fixtureManifest)
    require(!Files.exists(fixtures) && !Files.exists(manifest), "layered fixtures require a fresh fixture directory")
    val (width, height) = CompiledNativeProductFixtures.frameSize(product, twoCuVga=true)
    val scenes = NativeProductWorkloads.suite(width, height)
    require(scenes.map(_.id) == NativeProductWorkloads.ids)
    Files.createDirectory(fixtures)
    for ((scene, index) <- scenes.zipWithIndex) {
      val input = NativeLayeredProductFrameInputs.encode(scene, tileLog2)
      NativeFrameMemoryCodec.write(input.memory, fixtures.resolve(scene.id + ".memory"))
      NativeLayeredProductFixtures.writeCommands(fixtures.resolve(scene.id + ".commands"), scene,
        input, index + 1, product, factory)
    }
    Files.writeString(fixtures.resolve("suite.txt"), scenes.map(_.id).mkString("", "\n", "\n"))
    Files.writeString(manifest, NativeLayeredProductFixtures.manifest(product, width, height, tileLog2, factory))
    println(s"${factory.marker}_FIXTURES scenes=6 frame=${width}x$height words=${factory.words} rtlEmitted=false gpuExecution=false")
  }

  /** Both fixed factories retain the original shaders and independent oracle. */
  def verify(args: Array[String],factory: Factory): Unit = {
    val (product, output, tileLog2) = PrepareNativeOwnerFixtures.mode(args)
    val (width, height) = CompiledNativeProductFixtures.frameSize(product, twoCuVga=true)
    for ((scene, index) <- NativeProductWorkloads.suite(width, height).zipWithIndex) {
      val input = NativeLayeredProductFrameInputs.encode(scene, tileLog2)
      val actual = NativeFrameMemoryCodec.read(output.resolve(scene.id + ".memory"))
      require(NativeFrameMemoryCodec.sameAllocations(input.memory, actual), s"${scene.id} allocation metadata changed")
      NativeLayeredProductFrameInputs.verify(scene, input, actual, index + 1)
      println(s"${factory.marker}_IMAGE scene=${scene.id} tile=${1 << tileLog2} frame=${width}x$height exactImagesSamplesResolvesGuardsAndGNY2=true")
    }
    println(s"${factory.marker} PASS profile=${factory.id} scenes=6 tile=${1 << tileLog2} frame=${width}x$height computeUnits=2 clockQualified=false fpsQualified=false")
  }
}

object PrepareNativeLayeredProductFixtures {
  def main(args: Array[String]): Unit=NativeLayeredProductFixtures.prepare(args,NativeLayeredProductFixtures.Full)
}
object VerifyNativeLayeredProduct {
  def main(args: Array[String]): Unit=NativeLayeredProductFixtures.verify(args,NativeLayeredProductFixtures.Full)
}
object PrepareNativeSampleLayeredProductFixtures {
  def main(args: Array[String]): Unit=NativeLayeredProductFixtures.prepare(args,NativeLayeredProductFixtures.Sample)
}
object VerifyNativeSampleLayeredProduct {
  def main(args: Array[String]): Unit=NativeLayeredProductFixtures.verify(args,NativeLayeredProductFixtures.Sample)
}
