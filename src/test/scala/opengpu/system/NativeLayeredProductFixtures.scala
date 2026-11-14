package opengpu.system

import java.io.{BufferedOutputStream, DataOutputStream}
import java.nio.file.{Files, Path}
import opengpu.graphics.{GpuCommandMmioRegs, NativeLayeredGraphicsLayout, NativeLayeredProductFrameInputs, NativeProductFrameInputs, NativeProductPrimitiveArena, NativeProductWorkloads, RenderHostRegs}
import opengpu.testutil.NativeFrameMemoryCodec

/** Data-only inputs for the complete owner factory. No RTL or GPU output is produced. */
private[system] object NativeLayeredProductFixtures {
  def manifest(product: Boolean, width: Int, height: Int, tileLog2: Int): String = {
    require(tileLog2 == 3 || tileLog2 == 4)
    require((width, height) == CompiledNativeProductFixtures.frameSize(product, twoCuVga=true))
    val base = PrepareOwnerResourceFullHost.manifest(rtlEmitted=false).trim
    val scenes = NativeProductWorkloads.ids.map(s => s"\"$s\"").mkString(",")
    base.dropRight(1) + s""","workloadProduct":$product,"workloadWidth":$width,"workloadHeight":$height,"workloadTileLog2":$tileLog2,"scenes":[$scenes]}""" + "\n"
  }

  def writeCommands(path: Path, scene: NativeProductWorkloads.Scene,
      input: NativeProductFrameInputs.Inputs, id: Int, product: Boolean): Unit = {
    require(id >= 1 && id <= NativeProductWorkloads.ids.size)
    require(NativeProductWorkloads.ids(id - 1) == scene.id, "scene command ID differs from suite order")
    require(input.memory.readWord(input.jobBase) == NativeLayeredGraphicsLayout.jobMagic &&
      input.memory.readWord(input.jobBase + 8) == 2 && input.frame("frame.version") == 3,
      "layered fixtures require GNJ2/GNF3 inputs")
    require(input.job("completion.base") == input.completionBase &&
      input.completionBase != input.jobBase + 512, "GNY2 backing must be separate from the command job view")
    NativeProductPrimitiveArena.validate(scene.primitiveCount,
      input.frame("frame.bins.producer.arena.base"), input.frame("frame.bins.producer.arena.limit"))
    val resources = OwnerResourceProfile.target.requiredDiscovery
    require(resources.size == 104, "layered fixtures require the complete owner factory")
    val out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))
    def put32(value: Long): Unit = CompiledNativeProductFixtures.put32(out, value)
    try {
      out.write("OGLAY001".getBytes("US-ASCII"))
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
}

object PrepareNativeLayeredProductFixtures {
  def main(args: Array[String]): Unit = {
    val (product, output, tileLog2) = PrepareNativeOwnerFixtures.mode(args)
    require(Files.isDirectory(output), "prepare the complete owner DUT directory first")
    val fixtures = output.resolve("fixtures")
    val manifest = output.resolve("layer-product-fixture-manifest.json")
    require(!Files.exists(fixtures) && !Files.exists(manifest), "layered fixtures require a fresh fixture directory")
    val (width, height) = CompiledNativeProductFixtures.frameSize(product, twoCuVga=true)
    val scenes = NativeProductWorkloads.suite(width, height)
    require(scenes.map(_.id) == NativeProductWorkloads.ids)
    Files.createDirectory(fixtures)
    for ((scene, index) <- scenes.zipWithIndex) {
      val input = NativeLayeredProductFrameInputs.encode(scene, tileLog2)
      NativeFrameMemoryCodec.write(input.memory, fixtures.resolve(scene.id + ".memory"))
      NativeLayeredProductFixtures.writeCommands(fixtures.resolve(scene.id + ".commands"), scene,
        input, index + 1, product)
    }
    Files.writeString(fixtures.resolve("suite.txt"), scenes.map(_.id).mkString("", "\n", "\n"))
    Files.writeString(manifest, NativeLayeredProductFixtures.manifest(product, width, height, tileLog2))
    println(s"NATIVE_LAYERED_PRODUCT_FIXTURES scenes=6 frame=${width}x$height words=104 rtlEmitted=false gpuExecution=false")
  }
}

/** Verify independently returned memory, including samples, resolves, guards and GNY2. */
object VerifyNativeLayeredProduct {
  def main(args: Array[String]): Unit = {
    val (product, output, tileLog2) = PrepareNativeOwnerFixtures.mode(args)
    val (width, height) = CompiledNativeProductFixtures.frameSize(product, twoCuVga=true)
    for ((scene, index) <- NativeProductWorkloads.suite(width, height).zipWithIndex) {
      val input = NativeLayeredProductFrameInputs.encode(scene, tileLog2)
      val actual = NativeFrameMemoryCodec.read(output.resolve(scene.id + ".memory"))
      require(NativeFrameMemoryCodec.sameAllocations(input.memory, actual), s"${scene.id} allocation metadata changed")
      NativeLayeredProductFrameInputs.verify(scene, input, actual, index + 1)
      println(s"NATIVE_LAYERED_PRODUCT_IMAGE scene=${scene.id} tile=${1 << tileLog2} frame=${width}x$height exactImagesSamplesResolvesGuardsAndGNY2=true")
    }
    println(s"NATIVE_LAYERED_PRODUCT PASS profile=${OwnerResourceProfile.id} scenes=6 tile=${1 << tileLog2} frame=${width}x$height computeUnits=2 clockQualified=false fpsQualified=false")
  }
}
