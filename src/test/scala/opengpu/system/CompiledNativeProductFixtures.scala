package opengpu.system

import circt.stage.ChiselStage
import java.io.{BufferedOutputStream, DataOutputStream}
import java.nio.file.{Files, Path, Paths}
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, NativeBinnedGraphicsCapabilities, NativeProductFrameInputs, NativeProductWorkloads, RenderHostRegs, TileBinHostRegs}
import opengpu.testutil.{GraphicsTestResolution, NativeFrameMemoryCodec}

/** The compiled driver and Scala runner use the same host class, input recipes
  * and independent stored-image oracle. These entry points are explicit local
  * qualification commands, never ordinary discovered simulation cases.
  */
private[opengpu] object CompiledNativeProductFixtures {
  def twoCuVga: Boolean = {
    val requested = sys.props.get("opengpu.productTwoCuVga")
    require(requested.forall(Set("0", "1")), "opengpu.productTwoCuVga must be 0 or 1")
    val enabled = requested.contains("1")
    require(!enabled || apiCapacity, "two-CU VGA requires the full API-capacity profile")
    enabled
  }

  def frameSize(product: Boolean, twoCuVga: Boolean): (Int, Int) =
    if (!product) (32, 32) else if (twoCuVga) (640, 480) else (1280, 720)

  def input(scene: NativeProductWorkloads.Scene, twoCuVga: Boolean): NativeProductFrameInputs.Inputs =
    if (twoCuVga) NativeProductFrameInputs.encodeApiExtent(scene) else NativeProductFrameInputs.encode(scene)

  def apiCapacity: Boolean = {
    val requested = sys.props.get("opengpu.productApiCapacity")
    require(requested.forall(Set("0", "1")), "opengpu.productApiCapacity must be 0 or 1")
    val enabled = requested.contains("1")
    require(!enabled || sys.props.get("opengpu.apiExtentQualification").contains("1"),
      "full API capacity requires opengpu.apiExtentQualification=1")
    enabled
  }

  def mode(args: Array[String]): (Boolean, Path) = {
    require(args.length == 3 && Set("--smoke", "--product")(args(0)) && args(1) == "--output",
      "usage: --smoke|--product --output DIR")
    require(sys.props.get("opengpu.fullHostQualification").contains("1"),
      "requires explicit full-host qualification")
    val product = args(0) == "--product"
    require(GraphicsTestResolution.productQualification == (product && !twoCuVga),
      "legacy --product requires productQualification=1; two-CU VGA and smoke require 0 or unset")
    apiCapacity // Check the capacity qualification flag for both preparation and verification.
    (product, Paths.get(args(2)))
  }

  def put32(out: DataOutputStream, value: Long): Unit =
    (0 until 4).foreach(i => out.writeByte((value >>> (i * 8)).toInt))
  def put64(out: DataOutputStream, value: Long): Unit =
    (0 until 8).foreach(i => out.writeByte((value >>> (i * 8)).toInt))

  def writeCommands(path: Path, scene: NativeProductWorkloads.Scene,
    input: NativeProductFrameInputs.Inputs, id: Int, product: Boolean, apiCapacity: Boolean = false,
    twoCuVga: Boolean = false): Unit = {
    require(!twoCuVga || apiCapacity, "two-CU VGA requires the full API-capacity profile")
    val computeUnits = if (twoCuVga) 2 else 4
    opengpu.graphics.NativeProductPrimitiveArena.validate(scene.primitiveCount,
      input.frame("frame.bins.producer.arena.base"), input.frame("frame.bins.producer.arena.limit"))
    val out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))
    try {
      out.write("OGNAT001".getBytes("US-ASCII"))
      Seq(scene.width, scene.height, scene.samples, id).foreach(v => put32(out, v))
      put64(out, if (product) 1000000000L else 10000000L)
      val computeResources = Vector[Long](0x47435201L,16,15,computeUnits,16,8,128,128,
        0xffffffffL,16384,16,8,0x10000000L,32,131072,4)
      val nativeResources = if (apiCapacity) {
        val display = GraphicsConfig(screenWidth=GraphicsTestResolution.width,
          screenHeight=GraphicsTestResolution.height)
        NativeBinnedGraphicsCapabilities.words(NativeProductRecipeHost.native(true),computeUnits,display)
          .zipWithIndex.map { case (v, i) => (TileBinHostRegs.CAPABILITIES + i * 4, v.toLong) }
      } else Seq.empty[(Int, Long)]
      val resources = computeResources.zipWithIndex.map { case (v, i) => (0x360 + i * 4, v) } ++ nativeResources
      put32(out, resources.size)
      resources.foreach { case (address, value) => put32(out, address); put32(out, value) }
      val writes = Vector[(Int, Long)](
        RenderHostRegs.IRQ -> 1L, GpuCommandMmioRegs.COMMAND_ID -> id.toLong,
        GpuCommandMmioRegs.OPCODE -> 7L, GpuCommandMmioRegs.SOURCE -> input.jobBase,
        GpuCommandMmioRegs.DESTINATION -> input.completionBase,
        GpuCommandMmioRegs.BYTES -> 512L, GpuCommandMmioRegs.PATTERN -> 0L,
        GpuCommandMmioRegs.SUBMIT -> 1L)
      put32(out, writes.size)
      writes.foreach { case (a, v) => put32(out, a); put32(out, v) }
      // Every detail read is retained, including those with no value mask, so
      // performance counters are sampled at the Scala runner's same cycle.
      val checks = Vector[(Int, Long, Long)](
        (GpuCommandMmioRegs.COMPLETION, 0xffffL, 0x8700L | id)) ++
        Vector.tabulate(12) { i =>
          (0x180 + i * 4, if (Set(0,1,11)(i)) 0xffffffffL else 0L,
            if (i == 0) 0x474e5801L else 0L)
        }
      put32(out, checks.size)
      checks.foreach { case (a, m, v) => put32(out, a); put32(out, m); put32(out, v) }
      val finish = Vector(GpuCommandMmioRegs.COMPLETION_POP -> 1, RenderHostRegs.IRQ -> 3)
      put32(out, finish.size)
      finish.foreach { case (a, v) => put32(out, a); put32(out, v) }
    } finally out.close()
  }
}

object PrepareCompiledNativeProduct {
  def main(args: Array[String]): Unit = {
    val fixturesOnly = args.lastOption.contains("--fixtures-only")
    val (product, directory) = CompiledNativeProductFixtures.mode(if (fixturesOnly) args.dropRight(1) else args)
    val apiCapacity = CompiledNativeProductFixtures.apiCapacity
    val twoCuVga = CompiledNativeProductFixtures.twoCuVga
    val native = NativeProductRecipeHost.native(apiCapacity)
    val display = GraphicsConfig(screenWidth=GraphicsTestResolution.width,screenHeight=GraphicsTestResolution.height)
    val render = native.renderGraphics(display)
    val (width, height) = CompiledNativeProductFixtures.frameSize(product, twoCuVga)
    val fixtures = directory.resolve("fixtures"); Files.createDirectories(fixtures)
    val scenes = NativeProductWorkloads.suite(width, height)
    require(scenes.map(_.id) == NativeProductWorkloads.ids)
    for ((scene, index) <- scenes.zipWithIndex) {
      val input = CompiledNativeProductFixtures.input(scene, twoCuVga)
      NativeFrameMemoryCodec.write(input.memory, fixtures.resolve(scene.id + ".memory"))
      CompiledNativeProductFixtures.writeCommands(fixtures.resolve(scene.id + ".commands"), scene, input, index + 1, product, apiCapacity, twoCuVga)
    }
    Files.writeString(fixtures.resolve("suite.txt"), scenes.map(_.id).mkString("", "\n", "\n"))
    if (!fixturesOnly) {
      ChiselStage.emitSystemVerilogFile(new NativeProductRecipeHost(apiCapacity, twoCuVga),
        Array("--target-dir", directory.resolve("rtl").toString),
        Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
      Files.writeString(directory.resolve("rtl/NativeFourEngineSramModels.sv"),
        NativeFourEngineSramModels.source)
    }
    val ids = scenes.map(s => "\"" + s.id + "\"").mkString(",")
    val capacity = if (apiCapacity)
      s""","apiCapacity":true,"nativeMaxWidth":${render.screenWidth},"nativeMaxHeight":${render.screenHeight},"pagedCapacity":${native.pagedCapacity},"engines":${native.engines},"payloadBytes":${native.payload.map(_.capacityBytes).getOrElse(0)}"""
      else ""
    val profile = if (twoCuVga) ""","profile":"two-cu-vga60-v1","targetFps":60""" else ""
    val computeUnits = if (twoCuVga) 2 else 4
    Files.writeString(directory.resolve("fixture-manifest.json"),
      s"""{"version":1,"product":$product$capacity$profile,"frameWidth":$width,"frameHeight":$height,"screenWidth":${display.screenWidth},"screenHeight":${display.screenHeight},"computeUnits":$computeUnits,"lanes":16,"warps":8,"axiDataBytes":32,"rtlEmitted":${!fixturesOnly},"scenes":[$ids]}""" + "\n")
    println(s"NATIVE_COMPILED_INPUTS scenes=6 frame=${width}x$height rtlEmitted=${!fixturesOnly}")
  }
}

object VerifyCompiledNativeProduct {
  def main(args: Array[String]): Unit = {
    val (product, directory) = CompiledNativeProductFixtures.mode(args)
    val twoCuVga = CompiledNativeProductFixtures.twoCuVga
    val (width, height) = CompiledNativeProductFixtures.frameSize(product, twoCuVga)
    for (scene <- NativeProductWorkloads.suite(width, height)) {
      val input = CompiledNativeProductFixtures.input(scene, twoCuVga)
      val actual = NativeFrameMemoryCodec.read(directory.resolve(scene.id + ".memory"))
      require(NativeFrameMemoryCodec.sameAllocations(input.memory, actual),
        s"${scene.id} allocation metadata changed")
      NativeProductFrameInputs.verify(scene, input.copy(memory = actual))
      println(s"NATIVE_COMPILED_IMAGE id=${scene.id} frame=${width}x$height exactStoredImagesAndGuards=true")
    }
    println("NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false")
    if (twoCuVga) println("NATIVE_COMPILED_PROFILE profile=two-cu-vga60-v1 computeUnits=2 engines=4 targetFps=60 clockQualified=false fpsQualified=false")
  }
}
