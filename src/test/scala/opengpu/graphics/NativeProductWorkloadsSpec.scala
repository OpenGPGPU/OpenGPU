package opengpu.graphics

import org.scalatest.flatspec.AnyFlatSpec

class NativeProductWorkloadsSpec extends AnyFlatSpec {
  import NativeProductWorkloads._
  behavior of "the six native product workload inputs"

  it should "retain distinct geometry and state demands at development sizes" in {
    for ((w, h) <- Seq((32, 32), (64, 48))) {
      val scenes = suite(w, h)
      assert(scenes.map(_.id) == ids && scenes.map(_.id).distinct.size == 6)
      assert(scenes.map(_.primitiveCount) == Vector(8, 24, 16, 16,
        ((w + 7) / 8) * ((h + 7) / 8), 24))
      assert(scenes.forall(_.finalColorBytes == w.toLong * h * 4))
      assert(scenes.map(_.samples) == Vector(1, 1, 1, 1, 1, 4))
      assert(scenes.head.draws.count(_.blend) == 3)
      assert(scenes(1).draws == scenes(5).draws)
      assert(scenes(1).draws.forall(d => d.textured && d.depthTest && d.depthWrite))
      val ws = scenes(1).draws.flatMap(_.triangles).flatMap(t => Seq(t.a.w, t.b.w, t.c.w)).toSet
      assert(ws == Set(4f, 8f))
      assert(scenes(2).draws.forall(d => d.depthWrite && !d.blend))
      assert(scenes(2).draws.map(_.triangles.head.a.z).sliding(2).forall(v => v(0) > v(1)))
      assert(scenes(3).draws.forall(d => d.blend && !d.depthWrite && d.rgba(3) == 0.25f))
    }
  }

  it should "keep the small primitives at two pixels per edge as frame dimensions change" in {
    for ((w, h) <- Seq((32, 32), (64, 48), (17, 19))) {
      val triangles = scene("small-triangles", w, h).draws.head.triangles
      for (t <- triangles) {
        assert(math.abs((t.b.x - t.a.x) * w / 2 - 2) < 0.00001)
        assert(math.abs((t.c.y - t.a.y) * h / 2 - 2) < 0.00001)
      }
    }
  }

  it should "use a nonconstant opaque texture and reject malformed inputs" in {
    assert(textureRgba8.size == 64 && textureRgba8.distinct.size == 2)
    assert(textureRgba8.forall(x => (x >>> 24) == 255))
    intercept[IllegalArgumentException] { scene("unknown", 32, 32) }
    intercept[IllegalArgumentException] { scene(ids.head, 0, 32) }
    intercept[IllegalArgumentException] { Vertex(Float.NaN, 0, 0, 1) }
    intercept[IllegalArgumentException] { Vertex(0, 0, 0, 0) }
  }
}
