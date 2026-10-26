package opengpu.graphics

/** Input recipes for the six product workloads. These are not qualification
  * results: the native host runner must execute every draw and verify its image.
  * Positions are homogeneous clip coordinates, before viewport and binning.
  */
object NativeProductWorkloads {
  final case class Vertex(x: Float, y: Float, z: Float, w: Float,
    u: Float = 0, v: Float = 0) {
    require(Seq(x, y, z, w, u, v).forall(java.lang.Float.isFinite) && w > 0)
  }
  final case class Triangle(a: Vertex, b: Vertex, c: Vertex)
  final case class Draw(triangles: Vector[Triangle], rgba: Vector[Float],
    textured: Boolean = false, blend: Boolean = false,
    depthTest: Boolean = false, depthWrite: Boolean = false) {
    require(triangles.nonEmpty && rgba.size == 4 && rgba.forall(x => x >= 0 && x <= 1))
    require(!depthWrite || depthTest)
  }
  final case class Scene(id: String, width: Int, height: Int, samples: Int,
    draws: Vector[Draw]) {
    require(width >= 16 && height >= 16 && Set(1, 4)(samples) && draws.nonEmpty)
    val primitiveCount: Int = draws.map(_.triangles.size).sum
    val finalColorBytes: Long = width.toLong * height * 4
    // All scenes clear RGBA8 to transparent black and D24S8 to depth 1/stencil 0.
    // Source-over is SRC_ALPHA/ONE_MINUS_SRC_ALPHA for RGB and ONE/
    // ONE_MINUS_SRC_ALPHA for alpha; depth uses LESS. MSAA resolves linear RGBA8.
  }
  val ids: Vector[String] = Vector("desktop-composition", "textured-3d",
    "opaque-overdraw", "transparent-layers", "small-triangles", "msaa-4x")
  val textureWidth: Int = 8
  val textureHeight: Int = 8
  /** Opaque nearest/repeat checkerboard, row-major RGBA8. No host-side shading. */
  val textureRgba8: Vector[Int] = (for (y <- 0 until 8; x <- 0 until 8)
    yield if (((x / 2) ^ (y / 2)) % 2 == 0) 0xff4080ffL.toInt else 0xffe0c020L.toInt).toVector

  private val white = Vector(1f, 1f, 1f, 1f)
  private def rectangle(x0: Float, y0: Float, x1: Float, y1: Float,
    depth: Float = 0.5f): Vector[Triangle] = {
    val a = Vertex(x0, y0, depth, 1, 0, 0)
    val b = Vertex(x1, y0, depth, 1, 1, 0)
    val c = Vertex(x0, y1, depth, 1, 0, 1)
    val d = Vertex(x1, y1, depth, 1, 1, 1)
    Vector(Triangle(a, b, c), Triangle(d, c, b))
  }
  private def cube(offsetX: Float, offsetY: Float): Vector[Triangle] = {
    // Slanted projected faces have W=3/5/7/9, including the visible front.
    // The projection uses z=w-1, within the clip volume for all eight vertices.
    val vertices = (for (back <- 0 to 1; y <- 0 to 1; x <- 0 to 1) yield {
      val lx = x * 2 - 1; val ly = y * 2 - 1; val lz = back * 2 - 1
      val w = 6f + lx + 2 * lz
      Vertex(offsetX + lx * 1.5f - lz * 0.75f + ly * 0.25f,
        offsetY + ly * 1.5f + lx * 0.25f, w - 1, w, x.toFloat, y.toFloat)
    }).toVector
    val faces = Vector((0, 1, 2, 3), (5, 4, 7, 6), (4, 0, 6, 2),
      (1, 5, 3, 7), (2, 3, 6, 7), (4, 5, 0, 1))
    faces.flatMap { case (a, b, c, d) =>
      Vector(Triangle(vertices(a), vertices(b), vertices(c)),
        Triangle(vertices(d), vertices(c), vertices(b)))
    }
  }

  /** Geometry construction only. The executing runner must separately enforce
    * the explicit 720p flag and bind the full product execution/storage profile.
    */
  def scene(id: String, width: Int, height: Int): Scene = {
    require(ids.contains(id) && width >= 16 && height >= 16)
    val full = rectangle(-1, -1, 1, 1)
    val draws = id match {
      case "desktop-composition" =>
        Vector(Draw(full, white, textured = true)) ++ Vector(
          (-0.875f, -0.75f, 0.25f, 0.5f), (-0.25f, -0.5f, 0.875f, 0.75f),
          (-0.75f, -0.25f, 0.5f, 0.875f)).map { case (x0, y0, x1, y1) =>
          Draw(rectangle(x0, y0, x1, y1), Vector(1f, 0.75f, 0.5f, 0.75f),
            textured = true, blend = true)
        }
      case "textured-3d" | "msaa-4x" => Vector(-0.75f, 0.75f).map { x =>
        Draw(cube(x, if (x < 0) -0.25f else 0.25f), white,
          textured = true, depthTest = true, depthWrite = true)
      }
      case "opaque-overdraw" => (0 until 8).map { layer =>
        // Back-to-front submission keeps all eight layers visible to late tests.
        Draw(rectangle(-1, -1, 1, 1, 0.875f - layer * 0.0625f),
          Vector(layer / 8f, 0.5f, 1 - layer / 8f, 1f),
          depthTest = true, depthWrite = true)
      }.toVector
      case "transparent-layers" => (0 until 8).map { layer =>
        Draw(full, Vector(layer / 8f, 1 - layer / 8f, 0.5f, 0.25f), blend = true)
      }.toVector
      case "small-triangles" =>
        def vertex(x: Int, y: Int, u: Float, v: Float): Vertex =
          Vertex(2f * x / width - 1, 2f * y / height - 1, 0.5f, 1, u, v)
        val triangles = (for (y <- 0 until height by 8; x <- 0 until width by 8)
          yield Triangle(vertex(x, y, 0, 0), vertex(x + 2, y, 1, 0),
            vertex(x, y + 2, 0, 1))).toVector
        Vector(Draw(triangles, white, textured = true))

    }
    Scene(id, width, height, if (id == "msaa-4x") 4 else 1, draws)
  }
  def suite(width: Int, height: Int): Vector[Scene] = ids.map(scene(_, width, height))
}
