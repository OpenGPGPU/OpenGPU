package opengpu.graphics

import opengpu.testutil.{GraphicsTestResolution, NativeFrameMemory, PagedFrameMemory}
import scala.collection.mutable

/** GPU-memory inputs for one native binned frame. Addresses use an identity
  * Sv32 superpage map. Uploading these bytes is not hardware execution.
  */
object NativeProductSubmission {
  final case class Region(name: String, base: Long, limit: Long)
}

final class NativeProductSubmission(val scene: NativeProductWorkloads.Scene,
  val capacity: Int = 128, val generation: Int = 9) {
  require(scene.width <= GraphicsTestResolution.width && scene.height <= GraphicsTestResolution.height)
  require(capacity > 0 && capacity <= 128 && generation >= 0)
  import Fp32ViewportReference.fp
  import NativeProductSubmission.Region
  val memory = new PagedFrameMemory
  private val ranges = mutable.ArrayBuffer.empty[Region]
  private var cursor = 0x20000000L // Above the CU shared-memory aperture.
  private def align(n: Long): Long = (n + 63) & ~63L
  private def allocate(name: String, bytes: Long, fill: Int = 0): Region = {
    require(bytes > 0)
    val r = Region(name, cursor, cursor + align(bytes))
    require(r.limit <= NativeFrameMemory.AddressLimit)
    memory.mapRange(r.base, r.limit, fill); ranges += r; cursor = (r.limit + 4095) & ~4095L; r
  }
  private def put(a: BigInt, v: BigInt): Unit = memory.writeWord(a.toLong, v & 0xffffffffL)
  private val identity = Seq[BigInt](7, 8, generation)
  private def id(prefix: String): Map[String, BigInt] =
    Vector("context", "pass", "generation").zip(identity).map { case (k, v) => prefix + k -> v }.toMap
  private val header = Map[String, BigInt]("header.attributes" -> 16, "header.bindings" -> 16,
    "header.varyings" -> 16, "header.colors" -> 4, "header.engineViews" -> 4)
  private def record(base: Long, words: Int, magic: BigInt,
    fields: Seq[NativeTypedGraphicsLayout.Field], values: Map[String, BigInt],
    extraHeader: Seq[BigInt] = Seq.empty): Unit = {
    require(values.keySet.subsetOf(fields.map(_.name).toSet),
      "unknown descriptor fields: " + (values.keySet -- fields.map(_.name)))
    for (i <- 0 until words) put(base + i * 4, 0)
    (Seq(magic, BigInt(words * 4), BigInt(1)) ++ extraHeader).zipWithIndex
      .foreach { case (v, i) => put(base + i * 4, v) }
    for (f <- fields) {
      val v = values.getOrElse(f.name, BigInt(0))
      require(v >= 0 && v.bitLength <= f.value.getWidth, "descriptor value out of range: " + f.name)
      for (i <- 0 until f.words) put(base + (f.word + i) * 4, v >> (i * 32))
    }
  }
  private def program(name: String, code: Vector[BigInt]): Region = {
    val r = allocate(name, code.size * 4L)
    code.zipWithIndex.foreach { case (v, i) => put(r.base + i * 4, v) }; r
  }
  val vertexCode = program("vertex-code", NativeProductShaderPrograms.vertex(capacity))
  val texturedCode = program("textured-fragment-code", NativeProductShaderPrograms.fragment(capacity, true))
  val flatCode = program("flat-fragment-code", NativeProductShaderPrograms.fragment(capacity, false))
  val job = allocate("job", NativeBinnedGraphicsLayout.jobBytes)
  val completion = allocate("completion", 512)
  val frame = allocate("frame", NativeBinnedGraphicsLayout.frameBytes)
  val draws = allocate("draws", scene.draws.size * NativeBinnedGraphicsLayout.drawBytes.toLong)
  val states = allocate("states", scene.draws.size * TypedFragmentStateLayout.bytes.toLong)
  val vertexScratch = allocate("vertex-scratch", NativeProductShaderPrograms.vertexPrefixBytes(capacity))
  val fragmentStride: Int = align(NativeProductShaderPrograms.fragmentPrefixBytes(capacity) + 16).toInt
  val fragmentScratch: Vector[Region] = Vector.tabulate(4)(e =>
    allocate(s"fragment-scratch-$e", fragmentStride.toLong * scene.draws.size))

  private val columns = (scene.width + 15) / 16
  private val rows = (scene.height + 15) / 16
  val tileCount: Int = columns * rows
  val recordBytes: Int = WindowPrimitiveRecordLayout.lineCount(new WindowPrimitiveRecord(VertexShaderStageConfig())) * 64
  // Eight parts/input conservatively covers a triangle clipped by six planes.
  val primitiveArena = allocate("window-records", scene.primitiveCount.toLong * 8 * recordBytes)
  private val tileReferences = Array.fill(tileCount)(0L)
  for (draw <- scene.draws; t <- draw.triangles) {
    val xy = Vector(t.a, t.b, t.c).map(v => Fp32ViewportReference.project(
      Vector(v.x, v.y, v.z, v.w).map(x => fp(x.toDouble)),
      Fp32ViewportReference.View(width = scene.width, height = scene.height), Fp32ViewportConfig()))
    // Include a pixel of slack for clipped-coordinate rounding before binning.
    val x0 = ((xy.map(_.x).min / 256).toInt - 1).max(0).min(scene.width - 1)
    val y0 = ((xy.map(_.y).min / 256).toInt - 1).max(0).min(scene.height - 1)
    val x1 = ((xy.map(_.x).max / 256).toInt + 1).max(0).min(scene.width - 1)
    val y1 = ((xy.map(_.y).max / 256).toInt + 1).max(0).min(scene.height - 1)
    for (ty <- y0 / 16 to y1 / 16; tx <- x0 / 16 to x1 / 16) tileReferences(ty * columns + tx) += 8
  }
  private val perPage = PrimitiveBinPageLayout.recordsPerPage(4096)
  val binPool = allocate("bin-pages", tileReferences.map(n => (n + perPage - 1) / perPage).sum.max(1) * 4096)
  val directory = allocate("bin-directory", tileCount * 64L)
  val texture = allocate("texture-resources", 0x40000)
  NativeTextureFetchTestData.roots(put, stage = 1, id = identity, offset = texture.base, count = 1)
  NativeTextureFetchTestData.install(put, (a, b) => memory.writeByte(a.toLong, b), 0,
    stage = 1, id = identity, raw = 0, offset = texture.base)
  val textureData: Long = texture.base + 0x20000
  for ((offset, value) <- Seq(11 -> 8, 12 -> 8))
    put(texture.base + NativeTextureFetchTestData.images + offset * 4, value)
  for ((offset, value) <- Seq(12 -> BigInt(textureData), 13 -> BigInt(8), 14 -> BigInt(8), 17 -> BigInt(32)))
    put(texture.base + NativeTextureFetchTestData.levels + offset * 4, value)
  NativeProductWorkloads.textureRgba8.zipWithIndex.foreach { case (v, i) => put(textureData + i * 4, BigInt(v)) }

  private val imageStart = cursor
  val planes: Vector[NativeFrameMemory.Plane] = NativeFrameMemory.layout(scene.width, scene.height,
    scene.samples, Vector(4, 4), imageStart).map(p => p.copy(location = if (p.location == 1) 4 else 0))
  val images = allocate("images-and-guards", planes.last.allocationLimit - imageStart, 0xa7)
  def active(p: NativeFrameMemory.Plane): Boolean = !p.resolve || (scene.samples == 4 && p.location == 0)
  val vertices: Vector[Region] = scene.draws.zipWithIndex.map { case (d, h) =>
    val r = allocate(s"vertices-$h", d.triangles.size * 3L * 32)
    d.triangles.flatMap(t => Vector(t.a, t.b, t.c)).zipWithIndex.foreach { case (v, n) =>
      Vector(v.x, v.y, v.z, v.w, v.u, v.v, 0f, 0f).zipWithIndex.foreach { case (f, c) =>
        put(r.base + n * 32 + c * 4, fp(f.toDouble))
      }
    }; r
  }
  private val depthBytes = 16 * 16 * scene.samples * 4
  private var fv = Map[String, BigInt]("frame.version" -> 1, "frame.bins.producer.version" -> 1,
    "frame.bins.producer.arena.version" -> 1, "frame.bins.producer.arena.base" -> primitiveArena.base,
    "frame.bins.producer.arena.limit" -> primitiveArena.limit, "frame.bins.producer.screenWidth" -> scene.width,
    "frame.bins.producer.screenHeight" -> scene.height, "frame.bins.producer.tileLog2" -> 4,
    "frame.bins.bins.version" -> 3, "frame.bins.bins.tileCount" -> tileCount,
    "frame.bins.bins.directoryBase" -> directory.base, "frame.bins.bins.directoryLimit" -> directory.limit,
    "frame.bins.bins.pool.version" -> 1, "frame.bins.bins.pool.base" -> binPool.base,
    "frame.bins.bins.pool.limit" -> binPool.limit, "frame.bins.bins.pool.pageSizeLog2" -> 12,
    "frame.states.version" -> 1, "frame.states.base" -> states.base, "frame.states.limit" -> states.limit,
    "frame.scratchStride" -> fragmentStride, "frame.image.version" -> 1,
    "frame.image.pixels.version" -> 1, "frame.image.pixels.colors.version" -> 1,
    "frame.image.pixels.colors.tileLog2" -> 4, "frame.image.pixels.colors.tileWidth" -> 16,
    "frame.image.pixels.colors.tileHeight" -> 16, "frame.image.pixels.colors.sampleMode" -> (if (scene.samples == 4) 2 else 0),
    "frame.image.pixels.colors.reservedBytes" -> depthBytes,
    "frame.image.pixels.depth.format" -> TypedDepthFormat.d24s8, "frame.image.pixels.depth.loadOp" -> 1,
    "frame.image.pixels.depth.clearDepth" -> fp(1), "frame.image.pixels.colors.colors[0].enabled" -> 1,
    "frame.image.pixels.colors.colors[0].format" -> TypedColorFormat.id("RGBA8"),
    "frame.image.pixels.colors.colors[0].offset" -> depthBytes,
    "frame.image.pixels.colors.colors[0].channelMask" -> 15, "frame.image.pixels.colors.colors[0].loadOp" -> 1) ++
    id("frame.bins.producer.arena.identity.") ++ id("frame.bins.bins.pool.identity.") ++
    id("frame.states.identity.") ++ id("frame.image.pixels.colors.identity.")
  for ((r, e) <- fragmentScratch.zipWithIndex) fv ++= Map(s"frame.scratch[$e].base" -> BigInt(r.base), s"frame.scratch[$e].limit" -> BigInt(r.limit))
  for (p <- planes if active(p)) {
    val k = if (p.resolve) s"frame.image.resolves[${p.location}].destination." else s"frame.image.views[${p.location}]."
    if (p.resolve) fv += s"frame.image.resolves[${p.location}].enable" -> BigInt(1)
    fv ++= Map[String, BigInt](k + "base" -> p.base, k + "limit" -> p.limit,
      k + "allocationBase" -> p.allocationBase, k + "allocationLimit" -> p.allocationLimit,
      k + "imageWidth" -> scene.width, k + "imageHeight" -> scene.height, k + "rowPitch" -> p.rowPitch,
      k + "readable" -> (if (p.resolve) 0 else 1), k + "writable" -> 1, k + "store" -> 1)
  }
  record(frame.base, NativeBinnedGraphicsLayout.frameWords, NativeBinnedGraphicsLayout.frameMagic,
    NativeBinnedGraphicsLayout.frameFields(new NativeBinnedGraphicsFrameRecord), header ++ fv)
  for ((d, h) <- scene.draws.zipWithIndex) {
    val mode = if (scene.samples == 4) 2 else 0
    val view = vertices(h)
    val v = "draw.vertex.geometry.vertex."
    val i = v + "input."
    val dv = Map[String, BigInt]("draw.version" -> 1, "draw.state" -> h, "draw.token" -> (0xa0000000L + h),
      "draw.vertex.viewport.version" -> 1, "draw.vertex.viewport.extentX" -> scene.width,
      "draw.vertex.viewport.extentY" -> scene.height, "draw.vertex.viewport.farDepth" -> fp(1),
      "draw.vertex.geometry.topology" -> 4, v + "version" -> 1, v + "shaderPc" -> vertexCode.base,
      v + "attributeSlots" -> 2, v + "varyingSlots" -> 1, v + "varyingMask" -> 3,
      v + "sampler.texWidth" -> 1, v + "sampler.texHeight" -> 1,
      v + "scratch.base" -> vertexScratch.base, v + "scratch.limit" -> vertexScratch.limit,
      v + "scratch.allocationBase" -> vertexScratch.base, v + "scratch.allocationLimit" -> vertexScratch.limit,
      i + "version" -> 1, i + "count" -> (d.triangles.size * 3), i + "instances" -> 1,
      i + "bindings[0].base" -> view.base, i + "bindings[0].limit" -> (view.base + d.triangles.size * 3L * 32),
      i + "bindings[0].allocationBase" -> view.base, i + "bindings[0].allocationLimit" -> view.limit,
      i + "bindings[0].stride" -> 32, i + "attributes[0].enabled" -> 1, i + "attributes[0].components" -> 4,
      i + "attributes[1].enabled" -> 1, i + "attributes[1].components" -> 4, i + "attributes[1].offset" -> 16,
      "draw.raster.version" -> 1, "draw.raster.screenW" -> scene.width,
      "draw.raster.screenH" -> scene.height, "draw.raster.sampleMode" -> mode, "draw.raster.frontFaceCCW" -> 1) ++ id(i + "identity.")
    record(draws.base + h * NativeBinnedGraphicsLayout.drawBytes, NativeBinnedGraphicsLayout.drawWords,
      NativeBinnedGraphicsLayout.drawMagic, NativeBinnedGraphicsLayout.drawFields(new NativeBinnedGraphicsDrawRecord), header ++ dv)
    var sv = Map[String, BigInt]("version" -> 1, "engineCount" -> 4, "fragment.version" -> 1,
      "fragment.shaderPc" -> (if (d.textured) texturedCode.base else flatCode.base),
      "fragment.varyingSlots" -> 1, "fragment.colorSlots" -> 1, "fragment.colorMask" -> 1,
      "fragment.sampleMode" -> mode, "fragment.sampler.texWidth" -> 1, "fragment.sampler.texHeight" -> 1,
      "fragment.resources.version" -> (if (d.textured) 1 else 0),
      "fragment.resources.address" -> (if (d.textured) BigInt(texture.base) + NativeTextureFetchTestData.root else BigInt(0)),
      "tests.version" -> 1, "tests.depthEnable" -> (if (d.depthTest) 1 else 0),
      "tests.depthWrite" -> (if (d.depthWrite) 1 else 0), "tests.front.function" -> 6, "tests.back.function" -> 6,
      "blend.version" -> 1, "blend.enable" -> (if (d.blend) 1 else 0), "blend.srcRGB" -> 4,
      "blend.dstRGB" -> 5, "blend.srcAlpha" -> 1, "blend.dstAlpha" -> 5) ++ id("fragment.identity.")
    for ((r, e) <- fragmentScratch.zipWithIndex) {
      val b = r.base + h.toLong * fragmentStride
      val k = if (e == 0) "fragment.scratch." else s"scratch[${e - 1}]."
      sv ++= Map[String, BigInt](k + "base" -> b, k + "limit" -> (b + NativeProductShaderPrograms.fragmentPrefixBytes(capacity) + 16),
        k + "allocationBase" -> b, k + "allocationLimit" -> (b + fragmentStride))
      d.rgba.zipWithIndex.foreach { case (f, c) => put(b + NativeProductShaderPrograms.fragmentPrefixBytes(capacity) + c * 4, fp(f.toDouble)) }
    }
    record(states.base + h * 512, 128, TypedFragmentStateLayout.magic,
      TypedFragmentStateLayout.fields(new TypedFragmentState(FragmentShaderConfig())), sv,
      Seq[BigInt](4, 7, 8, generation, h))
  }
  record(job.base, NativeBinnedGraphicsLayout.jobWords, NativeBinnedGraphicsLayout.jobMagic,
    NativeBinnedGraphicsLayout.jobFields(new NativeBinnedGraphicsJobRecord), header ++ id("job.identity.") ++
      Map[String, BigInt]("job.frameBase" -> frame.base, "job.frameLimit" -> frame.limit,
        "job.drawBase" -> draws.base, "job.drawLimit" -> draws.limit, "job.drawCount" -> scene.draws.size,
        "job.drawStride" -> NativeBinnedGraphicsLayout.drawBytes))

  memory.mapRange(0x30000, 0x31000, 0)
  for (r <- ranges; base <- (r.base & ~0x3fffffL) until r.limit by 0x400000L)
    put(0x30000 + (base >> 22) * 4, ((BigInt(base) >> 12) << 10) | 0xcf)
  def regions: Vector[Region] = ranges.toVector
  private def readable(base: Long): Boolean =
    (base >= 0x30000 && base + 64 <= 0x31000) || ranges.exists(r => base >= r.base && base + 64 <= r.limit)
  private def writable(base: Long): Boolean = ranges.exists { r =>
    val role = Set("completion", "vertex-scratch", "window-records", "bin-pages", "bin-directory", "images-and-guards")(r.name) ||
      r.name.startsWith("fragment-scratch-")
    role && base >= r.base && base + 64 <= r.limit
  }
  def readLine(address: BigInt): BigInt = {
    require(address >= 0 && address < NativeFrameMemory.AddressLimit)
    val base = address.toLong & ~63L
    require(readable(base), "GPU read outside uploaded allocations")
    memory.readLine(base)
  }
  def writeLine(address: BigInt, data: BigInt, mask: BigInt): Unit = {
    require(address >= 0 && address <= NativeFrameMemory.AddressLimit - 64)
    require(writable(address.toLong), "GPU write outside mutable frame allocations")
    memory.writeLine(address.toLong, data, mask)
  }
  /** Check every stored sample, requested resolve and guard; unused resolve
    * allocations must retain their guard bytes. No result comes from DUT data.
    */
  def verifyImages(ref: NativeProductFrameReference): Long = {
    require(ref.scene == scene)
    var checked = 0L
    def byte(address: Long, expected: Int): Unit = {
      val actual = memory.readByte(address)
      assert(actual == expected, f"frame byte0x$address%x actual0x$actual%02x expected0x$expected%02x")
      checked += 1
    }
    def guards(start: Long, end: Long): Unit = {
      var a = start
      while (a < end) { byte(a, 0xa7); a += 1 }
    }
    for (p <- planes) {
      if (!active(p)) guards(p.allocationBase, p.allocationLimit)
      else {
        guards(p.allocationBase, p.base)
        for (y <- 0 until scene.height) {
          for (x <- 0 until scene.width; sample <- 0 until p.samples) {
            val raw = if (p.resolve) ref.resolvedColor(x, y)
              else if (p.location == 0) ref.storedColor(x, y, sample) else ref.storedDepthStencil(x, y, sample)
            for (b <- 0 until 4) byte(p.address(x, y, sample) + b, ((raw >> (8 * b)) & 255).toInt)
          }
          guards(p.base + y * p.rowPitch + scene.width.toLong * p.samples * 4, p.base + (y + 1) * p.rowPitch)
        }
        guards(p.base + scene.height * p.rowPitch, p.allocationLimit)
      }
    }
    checked
  }
}
