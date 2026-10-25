package opengpu.graphics

/** Streamed reference for the two complementary full-viewport triangles only.
  * It deliberately does not generalize to arbitrary geometry or raster state.
  */
private[graphics] final class NativeRectangleFrameReference(width: Int, height: Int,
  mode: Int, clear: Vector[BigInt], colors: Vector[Vector[BigInt]]) {
  require(width > 0 && width <= 65535 && height > 0 && height <= 65535)
  require(mode >= 0 && mode <= 2 && clear.size == 4)
  require(colors.size == 2 && colors.forall(_.size == 4))
  private val names = Vector("RGBA8", "RGB8", "RGBA16F", "SRGB8_ALPHA8")
  private val samples = 1 << mode
  private val offsets = mode match {
    case 0 => Vector((0, 0))
    case 1 => Vector((-1, -1), (1, 1))
    case 2 => Vector((-1, -1), (1, -1), (-1, 1), (1, 1))
  }
  private val stored = Vector.tabulate(5, 2) { (location, draw) =>
    if (location == 4) TypedColorReference.fp(0.5)
    else {
      val old = TypedColorReference.encode(names(location), clear)._1
      TypedColorReference.encode(names(location), TypedBlendReference.blend(
        names(location), colors(draw), old, TypedBlendReference.State(enable = draw == 1)))._1
    }
  }
  private val resolves = Vector.tabulate(5, 1 << samples) { (location, mask) =>
    TypedResolveReference.resolve(if (location == 4) "R32F" else names(location),
      Vector.tabulate(samples)(s => stored(location)(if ((mask & (1 << s)) == 0) 0 else 1)),
      mode, depth = location == 4)
  }

  def owner(x: Int, y: Int, sample: Int): Int = {
    require(x >= 0 && x < width && y >= 0 && y < height && sample >= 0 && sample < samples)
    val (dx, dy) = offsets(sample)
    val px = x.toLong * 256 + 128 + dx * 64
    val py = y.toLong * 256 + 128 + dy * 64
    // The first triangle owns equality on the shared diagonal (top-left
    // rule). All sample positions lie strictly within the outer rectangle.
    if (height.toLong * px + width.toLong * py <= width.toLong * height * 256) 0 else 1
  }

  def value(location: Int, resolve: Boolean, x: Int, y: Int, sample: Int): BigInt = {
    require(location >= 0 && location < 5)
    if (resolve) {
      require(sample == 0)
      val mask = (0 until samples).foldLeft(0)((m, s) => m | (owner(x, y, s) << s))
      resolves(location)(mask)
    } else stored(location)(owner(x, y, sample))
  }
}
