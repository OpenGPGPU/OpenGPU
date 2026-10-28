package opengpu.graphics

import opengpu.testutil.{GraphicsTestResolution, NativeFrameMemory, PagedFrameMemory}

/** Complete GPU input images for the six product recipes. No shaded outputs
  * are uploaded. A native host must execute the job and verify stored images.
  */
object NativeProductFrameInputs {
  import NativeBinnedGraphicsLayout._
  import NativeTypedGraphicsLayout.Field
  private val identity = Vector[BigInt](7, 8, 9)
  private def ids(prefix: String): Map[String, BigInt] =
    Vector("context", "pass", "generation").zip(identity).map { case (k, v) => (prefix + k) -> v }.toMap
  private val header = Map[String, BigInt]("header.attributes" -> 16, "header.bindings" -> 16,
    "header.varyings" -> 16, "header.colors" -> 4, "header.engineViews" -> 4)
  final case class Region(name: String, base: Long, limit: Long)
  final case class Inputs(memory: PagedFrameMemory, jobBase: Long, completionBase: Long,
    job: Map[String, BigInt], frame: Map[String, BigInt], draws: Vector[Map[String, BigInt]],
    states: Vector[Map[String, BigInt]], planes: Vector[NativeFrameMemory.Plane], regions: Vector[Region]) {
    /** AXI backing exists only for complete aligned lines inside an allocation. */
    def containsLine(address: BigInt): Boolean = address >= 0 && (address & 63) == 0 &&
      regions.exists(r => address >= r.base && address + 64 <= r.limit)
  }

  def encode(scene: NativeProductWorkloads.Scene): Inputs =
    encodeWith(scene, tileLog2=4, frameVersion=1,
      maximumWidth=GraphicsTestResolution.width, maximumHeight=GraphicsTestResolution.height,
      poolBase=0x04000000L, poolLimit=0x08000000L, directoryCapacity=16384)

  /** Input encoding only; rendering still requires an explicit local full-host
    * qualification with the wide native profile and real SRAM models.
    * The complete bin arena avoids the system's fixed shared-memory aperture.
    */
  def encodeApiExtent(scene: NativeProductWorkloads.Scene): Inputs =
    encodeWith(scene, tileLog2=3, frameVersion=2, maximumWidth=2048, maximumHeight=2048,
      poolBase=0x40000000L, poolLimit=0x60000000L, directoryCapacity=65536)

  private def encodeWith(scene: NativeProductWorkloads.Scene, tileLog2: Int, frameVersion: Int,
    maximumWidth: Int, maximumHeight: Int, poolBase: Long, poolLimit: Long, directoryCapacity: Int): Inputs = {
    require(scene.width <= maximumWidth && scene.height <= maximumHeight,
      "native frame inputs exceed the selected qualification extent")
    val tileSide=1<<tileLog2
    val tileCount=((scene.width+tileSide-1)/tileSide)*((scene.height+tileSide-1)/tileSide)
    require(tileCount<=directoryCapacity, "native tile grid exceeds the selected directory")
    require(scene.draws.size <= 16, "per-engine scratch holds at most sixteen draw states")
    val memory = new PagedFrameMemory
    val jobBase = 0x01000000L; val completionBase = jobBase + 512
    val frameBase = jobBase + 4096; val drawBase = jobBase + 8192
    val stateBase = 0x01200000L; val shaderBase = 0x01400000L
    val vertexBase = 0x01800000L; val vertexScratch = 0x02000000L
    val scratchBase = 0x02100000L; val scratchStride = 16384
    val textureOffset = BigInt(0x09000000)
    val planes = NativeFrameMemory.layout(scene.width, scene.height, scene.samples, Vector(4, 4), 0x20000000L)
      .map(p => p.copy(location = if (p.location == 1) 4 else 0))
    val regions = Vector(Region("records", jobBase, stateBase), Region("states", stateBase, shaderBase),
      Region("programs", shaderBase, shaderBase + 0x10000), Region("vertices", vertexBase, vertexScratch),
      Region("vertex-scratch", vertexScratch, vertexScratch + 0x10000),
      Region("fragment-scratch", scratchBase, scratchBase + 4 * 0x40000),
      Region("primitive-arena", 0x03000000L, 0x04000000L), Region("bin-pool", poolBase, poolLimit),
      Region("bin-directory", 0x08000000L, 0x08000000L+directoryCapacity*64L), Region("texture", 0x09000000L, 0x09100000L)) ++
      planes.map(p => Region(s"image-${p.location}-${p.resolve}", p.allocationBase, p.allocationLimit))
    for ((a, i) <- regions.zipWithIndex; b <- regions.drop(i + 1))
      require(a.limit <= b.base || b.limit <= a.base, s"overlapping ${a.name}/${b.name}")
    for (r <- regions) {
      require(r.limit <= 0x10000000L || r.base >= 0x10020000L, s"${r.name} aliases shared memory")
      memory.mapRange(r.base, r.limit, if (r.name.startsWith("image-")) 0xa7 else 0)
    }
    def word(a: BigInt, v: BigInt): Unit = memory.writeWord(a.toLong, v)
    def record(base: Long, words: Int, magic: BigInt, fields: Seq[Field], values: Map[String, BigInt]): Unit = {
      require(values.keySet.subsetOf(fields.map(_.name).toSet), s"unknown record fields: ${values.keySet -- fields.map(_.name)}")
      word(base, magic); word(base + 4, words * 4); word(base + 8, 1)
      for (f <- fields) {
        val v = values.getOrElse(f.name, BigInt(0))
        require(v >= 0 && v.bitLength <= f.width, s"${f.name} exceeds its ABI field")
        for (i <- 0 until f.words) word(base + (f.word + i) * 4, (v >> (i * 32)) & 0xffffffffL)
      }
    }
    val vertexPc = shaderBase; val solidPc = shaderBase + 4096; val texturedPc = shaderBase + 8192
    for ((pc, code) <- Vector(vertexPc -> NativeProductShaderPrograms.vertex(16, 8),
      solidPc -> NativeProductShaderPrograms.fragment(16, 8, false),
      texturedPc -> NativeProductShaderPrograms.fragment(16, 8, true)); (w, i) <- code.zipWithIndex) word(pc + i * 4, w)
    val texture = NativeTextureFetchTestData
    texture.roots(word, stage = 1, id = identity, offset = textureOffset)
    texture.install(word, (a, b) => memory.writeByte(a.toLong, b), 0,
      format = ImageFormatReference.names.indexOf("RGBA8"), stage = 1, id = identity, offset = textureOffset)
    word(textureOffset + texture.images + 11 * 4, 8); word(textureOffset + texture.images + 12 * 4, 8)
    word(textureOffset + texture.levels + 13 * 4, 8); word(textureOffset + texture.levels + 14 * 4, 8)
    word(textureOffset + texture.levels + 17 * 4, 32)
    for ((w, i) <- NativeProductWorkloads.textureRgba8.zipWithIndex)
      word(textureOffset + 0x20000 + 60 + i * 4, BigInt(w.toLong & 0xffffffffL))
    val mode = if (scene.samples == 4) 2 else 0
    val vertexBytes = VertexShaderStageConfig().prefixBytes(128, 3, 2)
    val fragmentBytes = FragmentShaderConfig().prefixBytes(128, 2, 1)
    def view(prefix: String, base: Long, bytes: Int): Map[String, BigInt] =
      Map(prefix + "base" -> BigInt(base), prefix + "limit" -> BigInt(base + bytes),
        prefix + "allocationBase" -> BigInt(base), prefix + "allocationLimit" -> BigInt(base + ((bytes + 63) & ~63)))
    val states = scene.draws.zipWithIndex.map { case (draw, h) =>
      val scratch = Vector.tabulate(4)(e => scratchBase + e * 0x40000 + h * scratchStride)
      val values = Map[String, BigInt]("version" -> 1, "engineCount" -> 4, "fragment.version" -> 1,
        "fragment.shaderPc" -> (if (draw.textured) texturedPc else solidPc), "fragment.varyingSlots" -> 2,
        "fragment.colorSlots" -> 1, "fragment.colorMask" -> 1, "fragment.sampleMode" -> mode,
        "fragment.sampler.texWidth" -> 1, "fragment.sampler.texHeight" -> 1,
        "fragment.resources.version" -> (if (draw.textured) 1 else 0),
        "fragment.resources.address" -> (if (draw.textured) textureOffset + texture.root else BigInt(0)),
        "tests.version" -> 1, "tests.depthEnable" -> (if (draw.depthTest) 1 else 0),
        "tests.depthWrite" -> (if (draw.depthWrite) 1 else 0), "tests.depthFunction" -> 1,
        "blend.version" -> 1, "blend.enable" -> (if (draw.blend) 1 else 0),
        "blend.srcRGB" -> 4, "blend.dstRGB" -> 5, "blend.srcAlpha" -> 1, "blend.dstAlpha" -> 5) ++
        ids("fragment.identity.") ++ view("fragment.scratch.", scratch.head, fragmentBytes) ++
        (1 until 4).flatMap(e => view(s"scratch[${e - 1}].", scratch(e), fragmentBytes))
      val base = stateBase + h * TypedFragmentStateLayout.bytes
      record(base, TypedFragmentStateLayout.words, TypedFragmentStateLayout.magic,
        TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig())), values)
      Vector[BigInt](4, 7, 8, 9, h).zipWithIndex.foreach { case (w, i) => word(base + (i + 3) * 4, w) }
      values
    }
    val frame = header ++ ids("frame.bins.producer.arena.identity.") ++ ids("frame.bins.bins.pool.identity.") ++
      ids("frame.states.identity.") ++ ids("frame.image.pixels.colors.identity.") ++ Map[String, BigInt](
        "frame.version" -> frameVersion, "frame.bins.producer.version" -> 1, "frame.bins.producer.arena.version" -> 1,
        "frame.bins.producer.arena.base" -> 0x03000000, "frame.bins.producer.arena.limit" -> 0x04000000,
        "frame.bins.producer.screenWidth" -> scene.width, "frame.bins.producer.screenHeight" -> scene.height,
        "frame.bins.producer.tileLog2" -> tileLog2, "frame.bins.bins.version" -> 3,
        "frame.bins.bins.tileCount" -> tileCount,
        "frame.bins.bins.directoryBase" -> 0x08000000, "frame.bins.bins.directoryLimit" ->
          (0x08000000L + tileCount*64L),
        "frame.bins.bins.pool.version" -> 1, "frame.bins.bins.pool.base" -> poolBase,
        "frame.bins.bins.pool.limit" -> poolLimit, "frame.bins.bins.pool.pageSizeLog2" -> 12,
        "frame.states.version" -> 1, "frame.states.base" -> stateBase,
        "frame.states.limit" -> (stateBase + states.size * 512), "frame.scratchStride" -> scratchStride,
        "frame.image.version" -> 1, "frame.image.pixels.version" -> 1, "frame.image.pixels.colors.version" -> 1,
        "frame.image.pixels.colors.tileLog2" -> tileLog2, "frame.image.pixels.colors.tileWidth" -> tileSide,
        "frame.image.pixels.colors.tileHeight" -> tileSide, "frame.image.pixels.colors.sampleMode" -> mode,
        "frame.image.pixels.colors.reservedBytes" -> (tileSide * tileSide * scene.samples * 4),
        "frame.image.pixels.depth.format" -> TypedDepthFormat.d24s8, "frame.image.pixels.depth.loadOp" -> 1,
        "frame.image.pixels.depth.clearDepth" -> BigInt(0x3f800000),
        "frame.image.pixels.colors.colors[0].enabled" -> 1,
        "frame.image.pixels.colors.colors[0].format" -> TypedColorFormat.id("RGBA8"),
        "frame.image.pixels.colors.colors[0].offset" -> (tileSide * tileSide * scene.samples * 4),
        "frame.image.pixels.colors.colors[0].channelMask" -> 15, "frame.image.pixels.colors.colors[0].loadOp" -> 1) ++
      (0 until 4).flatMap(e => Map(s"frame.scratch[$e].base" -> BigInt(scratchBase + e * 0x40000),
        s"frame.scratch[$e].limit" -> BigInt(scratchBase + e * 0x40000 + states.size * scratchStride))) ++
      planes.filter(p => !p.resolve || p.location == 0 && scene.samples == 4).flatMap { p =>
        val prefix = if (p.resolve) s"frame.image.resolves[${p.location}].destination." else s"frame.image.views[${p.location}]."
        val values = view(prefix, p.base, (p.limit - p.base).toInt) ++ Map[String, BigInt](
          prefix + "allocationBase" -> p.allocationBase, prefix + "allocationLimit" -> p.allocationLimit,
          prefix + "imageWidth" -> scene.width, prefix + "imageHeight" -> scene.height,
          prefix + "rowPitch" -> p.rowPitch, prefix + "readable" -> (if (p.resolve) 0 else 1),
          prefix + "writable" -> 1, prefix + "store" -> 1)
        if (p.resolve) values + (s"frame.image.resolves[${p.location}].enable" -> BigInt(1)) else values
      }
    record(frameBase, frameWords, frameMagic, frameFields(new NativeBinnedGraphicsFrameRecord), frame)
    var cursor = vertexBase
    val draws = scene.draws.zipWithIndex.map { case (draw, h) =>
      val upload = NativeProductVertexInputs.encode(draw, cursor)
      upload.bytes.zipWithIndex.foreach { case (b, i) => memory.writeByte(cursor + i, b & 255) }
      cursor += upload.bytes.size
      require(cursor <= vertexScratch, "vertex uploads exceed their reserved range")
      val values = header ++ upload.fields ++ ids("draw.vertex.geometry.vertex.input.identity.") ++
        Map[String, BigInt]("draw.version" -> 1, "draw.state" -> h, "draw.token" -> h,
          "draw.vertex.viewport.version" -> 1, "draw.vertex.viewport.extentX" -> scene.width,
          "draw.vertex.viewport.extentY" -> scene.height, "draw.vertex.viewport.farDepth" -> BigInt(0x3f800000),
          "draw.vertex.geometry.topology" -> 4, "draw.vertex.geometry.vertex.version" -> 1,
          "draw.vertex.geometry.vertex.shaderPc" -> vertexPc, "draw.vertex.geometry.vertex.attributeSlots" -> 3,
          "draw.vertex.geometry.vertex.varyingSlots" -> 2, "draw.vertex.geometry.vertex.varyingMask" -> 255,
          "draw.vertex.geometry.vertex.sampler.texWidth" -> 1, "draw.vertex.geometry.vertex.sampler.texHeight" -> 1,
          "draw.raster.version" -> 1, "draw.raster.screenW" -> scene.width, "draw.raster.screenH" -> scene.height,
          "draw.raster.sampleMode" -> mode, "draw.raster.frontFaceCCW" -> 1) ++
        view("draw.vertex.geometry.vertex.scratch.", vertexScratch, vertexBytes)
      record(drawBase + h * drawBytes, drawWords, drawMagic, drawFields(new NativeBinnedGraphicsDrawRecord), values)
      values
    }
    val job = header ++ ids("job.identity.") ++ Map[String, BigInt]("job.frameBase" -> frameBase,
      "job.frameLimit" -> (frameBase + frameBytes), "job.drawBase" -> drawBase,
      "job.drawLimit" -> (drawBase + draws.size * drawBytes), "job.drawCount" -> draws.size, "job.drawStride" -> drawBytes)
    record(jobBase, jobWords, jobMagic, jobFields(new NativeBinnedGraphicsJobRecord), job)
    Inputs(memory, jobBase, completionBase, job, frame, draws, states, planes, regions)
  }

  /** Verify samples/resolves and every pitched allocation guard against the
    * independent streamed image oracle. Unbound resolve planes stay untouched.
    */
  def verify(scene: NativeProductWorkloads.Scene, input: Inputs): Unit = {
    val reference = new NativeProductFrameReference(scene)
    for (p <- input.planes) {
      val active = !p.resolve || p.location == 0 && scene.samples == 4
      if (active) for (y <- 0 until scene.height; x <- 0 until scene.width; s <- 0 until p.samples) {
        val expected = if (p.resolve) reference.resolvedColor(x, y)
          else if (p.location == 0) reference.storedColor(x, y, s) else reference.storedDepthStencil(x, y, s)
        val address = p.address(x, y, s)
        assert(input.memory.readWord(address) == expected,
          s"${scene.id} location=${p.location} resolve=${p.resolve} x=$x y=$y sample=$s differs")
      }
      var address = p.allocationBase
      while (address < p.allocationLimit) {
        val relative = address - p.base
        val row = relative / p.rowPitch; val column = relative % p.rowPitch
        val payload = active && relative >= 0 && row < p.height && column < p.width.toLong * p.samples * 4
        if (!payload) assert(input.memory.readByte(address) == 0xa7,
          s"${scene.id} image guard changed at 0x${address.toHexString}")
        address += 1
      }
    }
  }
}
