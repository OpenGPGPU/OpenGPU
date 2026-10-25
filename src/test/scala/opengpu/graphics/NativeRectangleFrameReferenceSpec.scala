package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeRectangleFrameReferenceSpec extends AnyFlatSpec {
  behavior of "the complementary-triangle streamed frame reference"

  it should "match every sample owner against independent edge rasterization" in {
    for ((width, height) <- Seq((1, 1), (17, 9), (32, 32)); mode <- 0 to 2) {
      val graphics = GraphicsConfig(screenWidth = width, screenHeight = height)
      val coordinates = Vector(
        Vector((0, 0), (width * 256, 0), (0, height * 256)),
        Vector((width * 256, height * 256), (0, height * 256), (width * 256, 0)))
      val coverage = coordinates.map { coords =>
        val triangle = TriangleRasterReference.base.copy(coords = coords.map { case (x, y) => (BigInt(x), BigInt(y)) })
        TriangleRasterReference.raster(triangle, TriangleRasterReference.Settings(width, height, mode), graphics)
          .flatMap(_.samples.flatMap(p => (0 until (1 << mode)).filter(s => (p.mask & (1 << s)) != 0)
            .map(s => (p.x, p.y, s)))).toSet
      }
      val reference = new NativeRectangleFrameReference(width, height, mode,
        Vector.fill(4)(TypedColorReference.fp(0.1)), Vector.fill(2)(Vector.fill(4)(TypedColorReference.fp(0.5))))
      assert((coverage(0) intersect coverage(1)).isEmpty)
      for (y <- 0 until height; x <- 0 until width; s <- 0 until (1 << mode)) {
        val owners = coverage.indices.filter(h => coverage(h)((x, y, s)))
        assert(owners.size == 1)
        assert(reference.owner(x, y, s) == owners.head)
      }
    }
  }

  it should "retain mixed-format blend and resolve values across diagonal sample splits" in {
    import TypedColorReference.{fp, encode}
    val names = Vector("RGBA8", "RGB8", "RGBA16F", "SRGB8_ALPHA8")
    val clear = Vector(0.1, 0.2, 0.3, 0.4).map(fp)
    for (mode <- 0 to 2; red <- Seq(0.125, 0.75)) {
      val colors = Vector(Vector(red, 0.5, 0.75, 1.0), Vector(red, 0.25, 0.5, 0.5)).map(_.map(fp))
      val ref = new NativeRectangleFrameReference(32, 32, mode, clear, colors)
      val positions = mode match {
        case 0 => Vector((0, 0))
        case 1 => Vector((-1, -1), (1, 1))
        case 2 => Vector((-1, -1), (1, -1), (-1, 1), (1, 1))
      }
      for (y <- 0 until 32; x <- 0 until 32; location <- 0 until 5) {
        val values = positions.map { case (dx, dy) =>
          // Independent BigInt edge equation for the shared diagonal.
          val edge = BigInt(32 * 256) * (32 * 256) -
            BigInt(32 * 256) * (x * 256 + 128 + dx * 64) -
            BigInt(32 * 256) * (y * 256 + 128 + dy * 64)
          val h = if (edge >= 0) 0 else 1
          if (location == 4) fp(0.5) else {
            val old = encode(names(location), clear)._1
            encode(names(location), TypedBlendReference.blend(names(location), colors(h), old,
              TypedBlendReference.State(enable = h == 1)))._1
          }
        }
        values.zipWithIndex.foreach { case (v, s) => assert(ref.value(location, false, x, y, s) == v) }
        val resolved = TypedResolveReference.resolve(if (location == 4) "R32F" else names(location),
          values, mode, depth = location == 4)
        assert(ref.value(location, true, x, y, 0) == resolved)
      }
    }
  }
}
