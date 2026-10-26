package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeProductFrameReferenceSpec extends AnyFlatSpec {
  import NativeProductWorkloads._
  behavior of "the streamed native product sample/image reference"
  private val white = Vector.fill(4)(1f)
  private def pack(v: Seq[Int]): Int = v.zipWithIndex.foldLeft(0) { case (a, (b, c)) => a | (b << (8 * c)) }

  it should "execute eight full-frame opaque layers with exact color and quantized depth" in {
    val ref = new NativeProductFrameReference(scene("opaque-overdraw", 17, 19))
    for (y <- 0 until 19; x <- 0 until 17) {
      val p = ref.pixel(x, y)
      assert(p.coveredSamples == 8 && p.passedSamples == 8)
      assert(p.color == Vector(pack(Vector(223, 128, 32, 255))))
      assert(p.depthStencil == Vector(0xb7ffff)) // RNE(((1+.4375)/2)*(2^24-1)).
      assert(p.resolvedColor == p.color.head)
    }
  }

  it should "preserve source-over ordering and quantize the destination after every layer" in {
    // Separate host float/byte recurrence; no blend/format reference helpers.
    var old = Vector.fill(4)(0)
    for (layer <- 0 until 8) {
      val source = Vector(layer / 8f, 1 - layer / 8f, 0.5f, 0.25f)
      old = Vector.tabulate(4) { c =>
        val src = if (c == 3) source(c) else (source(c) * 0.25f).toFloat
        val dst = ((old(c) / 255f) * 0.75f).toFloat
        math.rint((src + dst).toFloat.toDouble * 255).toInt
      }
    }
    val ref = new NativeProductFrameReference(scene("transparent-layers", 17, 19))
    for (y <- 0 until 19; x <- 0 until 17) {
      val p = ref.pixel(x, y)
      assert(p.coveredSamples == 8 && p.passedSamples == 8)
      assert(p.color == Vector(pack(old)) && p.depthStencil == Vector(0xffffff))
    }
  }

  it should "select perspective UVs rather than affine UVs on the same screen triangle" in {
    val perspective = Triangle(Vertex(-1, -1, 0, 1), Vertex(2, -2, 0, 2, 1, 0),
      Vertex(-4, 4, 0, 4))
    val affine = Triangle(Vertex(-1, -1, 0, 1), Vertex(1, -1, 0, 1, 1, 0),
      Vertex(-1, 1, 0, 1))
    def reference(t: Triangle) = new NativeProductFrameReference(Scene("uv-probe", 16, 16, 1,
      Vector(Draw(Vector(t), white, textured = true))))
    // At (4.5,4.5), barycentrics are 7/16,9/32,9/32. Perspective U=18/83,
    // selecting texel1; affine U=9/32 selects texel2. V is identically zero.
    assert(reference(perspective).pixel(4, 4).color == Vector(textureRgba8(1)))
    assert(reference(affine).pixel(4, 4).color == Vector(textureRgba8(2)))
    assert(textureRgba8(1) != textureRgba8(2))
  }

  it should "keep distinct sample depths and resolve partially covered 4x edges" in {
    val triangle = Triangle(Vertex(-1, -1, -0.5f, 1), Vertex(1, -1, 0.5f, 1),
      Vertex(-1, 1, 0.5f, 1))
    val ref = new NativeProductFrameReference(Scene("sloped-edge", 16, 16, 4,
      Vector(Draw(Vector(triangle), white, depthTest = true, depthWrite = true))))
    val p = ref.pixel(7, 8)
    assert(p.coveredSamples == 3 && p.passedSamples == 3)
    assert(p.color == Vector(-1, -1, -1, 0))
    val expected = Vector(15.5, 16.0, 16.0).map(sum => math.rint((0.25 + sum / 32) * 0xffffff).toInt)
    assert(p.depthStencil == expected :+ 0xffffff)
    assert(p.resolvedColor == 0xbfbfbfbf)
  }

  it should "retain clear pixels and clipped frame-edge small primitives" in {
    val ref = new NativeProductFrameReference(scene("small-triangles", 17, 19))
    assert(ref.pixel(0, 0).passedSamples == 1)
    assert(ref.pixel(4, 4).color == Vector(0))
    assert(ref.pixel(4, 4).depthStencil == Vector(0xffffff))
    assert(ref.pixel(16, 16).passedSamples == 1)
    intercept[IllegalArgumentException] { ref.pixel(17, 0) }
    intercept[IllegalArgumentException] { ref.storedColor(0, 0, 1) }
  }

  it should "retain the first equal-depth owner and wrap negative texture coordinates" in {
    val t = Triangle(Vertex(-1, -1, 0, 1, -0.125f, 0),
      Vertex(1, -1, 0, 1, -0.125f, 0), Vertex(-1, 1, 0, 1, -0.125f, 0))
    val first = Draw(Vector(t), white, textured = true, depthTest = true, depthWrite = true)
    val second = Draw(Vector(t), Vector(1f, 0f, 0f, 1f), depthTest = true, depthWrite = true)
    val ref = new NativeProductFrameReference(Scene("equal-depth", 16, 16, 1, Vector(first, second)))
    val p = ref.pixel(1, 1)
    assert(p.coveredSamples == 2 && p.passedSamples == 1)
    assert(p.color == Vector(textureRgba8(7)))
    assert(ref.storedDepthStencil(1, 1, 0) == 0x800000)
  }

  it should "check pitched source and resolved images against an independent constant frame and retain guards" in {
    val ref = new NativeProductFrameReference(scene("opaque-overdraw", 17, 19))
    val image = new opengpu.testutil.NativeFrameImage(17, 19, 1, Vector(4), 0x100000)
    for (plane <- image.planes; y <- 0 until 19; x <- 0 until 17)
      image.memory.writeWord(plane.address(x, y), BigInt("ff2080df", 16))
    def verify(): Unit = {
      val checked = image.verify { (location, resolve, x, y, sample) =>
        assert(location == 0)
        if (resolve) ref.resolvedColor(x, y) else ref.storedColor(x, y, sample)
      }
      assert(checked == image.planes.map(p => p.allocationLimit - p.allocationBase).sum)
    }
    verify()
    val source = image.planes.find(!_.resolve).get
    image.memory.writeByte(source.address(0, 0), 222)
    intercept[AssertionError] { verify() }
    image.memory.writeByte(source.address(0, 0), 223)
    val resolve = image.planes.find(_.resolve).get
    image.memory.writeByte(resolve.address(0, 0), 222)
    intercept[AssertionError] { verify() }
    image.memory.writeByte(resolve.address(0, 0), 223)
    image.memory.writeByte(source.base + source.width * 4, 0)
    intercept[AssertionError] { verify() }
  }

  it should "produce all six small images with nontrivial texture depth blend and sample coverage" in {
    for (s <- suite(32, 32)) {
      val ref = new NativeProductFrameReference(s)
      val pixels = (for (y <- 0 until 32; x <- 0 until 32) yield ref.pixel(x, y)).toVector
      assert(pixels.exists(_.passedSamples > 0), s.id)
      assert(pixels.flatMap(_.color).exists(_ != 0), s.id)
      if (s.id == "textured-3d" || s.id == "msaa-4x") {
        assert(pixels.exists(p => p.coveredSamples > p.passedSamples), "depth occlusion missing")
        assert(pixels.flatMap(_.depthStencil).exists(_ != 0xffffff))
        assert(pixels.flatMap(_.color).distinct.size >= 3)
      }
      if (s.samples == 4) assert(pixels.exists(p => p.color.distinct.size > 1))
    }
  }
}
