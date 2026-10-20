package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TextureRgba8GroupPlannerSpec extends AnyFlatSpec {
  import TextureFilterReference.bits
  private case class View(target: Int = 0, width: Int = 7, height: Int = 5, depth: Int = 1,
      layers: Int = 1, base: BigInt = 0x104000, row: BigInt = 32, slice: BigInt = 0,
      plane: BigInt = 0, limit: BigInt = 0x200000, level: Int = 0, bytes: Int = 4, block: Boolean = false)
  private case class Query(tag: Int, view: View = View(), u: BigInt = bits(0.4f), v: BigInt = bits(0.6f),
      linear: Boolean = true, wraps: Vector[Int] = Vector(0, 1), offsets: Vector[Int] = Vector(0, 0),
      layer: BigInt = 0, face: BigInt = 0, format: Int = 3, shadow: Boolean = false,
      handle: BigInt = 3, tableBase: BigInt = 0x2000, tableLimit: BigInt = 0x3000,
      version: Int = 1, selectedLevel: Int = 0, generation: Int = 9)
  private case class Plan(q: Query, code: Int, source: Int, eligible: Boolean,
      addresses: Vector[BigInt] = Vector.empty, rows: Vector[Boolean] = Vector.empty,
      weights: Vector[BigInt] = Vector.empty, unique: Int = 0, stopped: Boolean = false)
  private def reference(q: Query): Plan = {
    val v = q.view; val cube = v.target == 2; val array = v.target == 1
    def fail(code: Int, source: Int): Plan = Plan(q, code, source, false)
    // Directed malformed cases pass an explicit expected error; numeric cases
    // use exact rational tap selection and independent cube vector projection.
    val supported = q.format == 3 && !q.shadow && v.target <= 2
    if (!supported) return Plan(q, 0, 0, false)
    val ax = TextureSamplingReference.axis(q.u, v.width, if (cube) { if (q.linear) 3 else 1 } else q.wraps(0), q.linear, q.offsets(0))
    val ay = TextureSamplingReference.axis(q.v, v.height, if (cube) { if (q.linear) 3 else 1 } else q.wraps(1), q.linear, q.offsets(1))
    val weights = Vector(ax.weight, ay.weight)
    def active(t: Int): Boolean = (0 until 2).forall { a =>
      if ((weights(a) & 0x7fffffffL) == 0) (t & (1 << a)) == 0
      else if (weights(a) == 0x3f800000L) (t & (1 << a)) != 0 else true
    }
    val locations = Vector.tabulate(4) { t =>
      val x = if (t % 2 == 0) ax.first else ax.second; val y = if (t / 2 == 0) ay.first else ay.second
      if (cube) TextureSamplingReference.cube(q.face.toInt, x, y, v.width) else (0, x, y)
    }
    val first = (0 until 4).find(active).get
    val used = Vector.tabulate(4)(t => locations(if (active(t)) t else first))
    val addresses = used.map { case (f, x, y) => v.base + x * 4 + y * v.row + (if (cube) BigInt(f) else if (array) q.layer else BigInt(0)) * v.plane }
    val eligible = addresses.forall(a => (a & 3) == 0)
    Plan(q, 0, 0, eligible, addresses, used.map(_._3 % 2 == 1), weights,
      (0 until 4).filter(active).map(locations).distinct.size)
  }
  private class Driver(val d: TextureRgba8GroupPlanner) {
    WindowRecordTest.zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    val pending = mutable.ArrayBuffer.empty[Plan]
    val accepted = mutable.ArrayBuffer.empty[Int]; val retired = mutable.ArrayBuffer.empty[Int]
    var cycle = 0; var held: Option[BigInt] = None
    def poke(q: Query): Unit = {
      val b = d.io.in.bits; val v = b.view; val x = q.view; val table = b.lookup.table
      table.version.poke(q.version.U); table.base.poke(q.tableBase.U); table.limit.poke(q.tableLimit.U)
      table.identity.context.poke(7.U); table.identity.pass.poke(8.U); table.identity.generation.poke(q.generation.U)
      b.lookup.handle.poke(q.handle.U); b.lookup.level.poke(q.selectedLevel.U)
      v.target.poke(x.target.U); v.level.poke(x.level.U); v.allocationBase.poke(0x100000.U)
      v.allocationLimit.poke(x.limit.U); v.base.poke(x.base.U); v.texWidth.poke(x.width.U)
      v.texHeight.poke(x.height.U); v.texDepth.poke(x.depth.U); v.layers.poke(x.layers.U)
      v.rowPitch.poke(x.row.U); v.slicePitch.poke(x.slice.U); v.layerPitch.poke(x.plane.U)
      v.block4x4.poke(x.block.B); v.blockBytes.poke(x.bytes.U)
      b.coordinates(0).poke(q.u.U); b.coordinates(1).poke(q.v.U)
      for (a <- 0 until 2) { b.offsets(a).poke(q.offsets(a).S); b.wraps(a).poke(q.wraps(a).U) }
      b.format.poke(q.format.U); b.layer.poke(q.layer.U); b.face.poke(q.face.U)
      b.linear.poke(q.linear.B); b.shadow.poke(q.shadow.B); b.tag.poke(q.tag.U)
    }
    def check(e: Plan): Unit = {
      val b = d.io.out.bits
      val code = if (e.code != 0) e.code else if (e.stopped) 4 else 0
      val eligible = e.eligible && !e.stopped
      withClue(s"tag=${e.q.tag} cycle=$cycle ") {
        b.tag.expect(e.q.tag.U); b.status.expect(code.U); b.faultSource.expect(e.source.U)
        b.cancelled.expect(e.stopped.B); b.eligible.expect(eligible.B); b.fallback.expect((code == 0 && !eligible).B)
        if (eligible) {
          b.group.tag.expect(e.q.tag.U); b.group.identity.context.expect(7.U); b.group.identity.pass.expect(8.U)
          b.group.identity.generation.expect(e.q.generation.U)
          b.group.textureKey.expect(((e.q.tableBase << 32) | e.q.handle).U)
          b.uniqueTaps.expect(e.unique.U)
          for (t <- 0 until 4) { b.group.addresses(t).expect(e.addresses(t).U); b.group.rowParity(t).expect(e.rows(t).B) }
          for (a <- 0 until 2) b.group.axisWeights(a).expect(e.weights(a).U)
        } else { assert(b.group.peek().litValue == 0); b.uniqueTaps.expect(0.U) }
      }
    }
    def tick(query: Option[Query] = None, ready: Boolean = true, cancel: Boolean = false,
        scope: Option[(Int, Int)] = None, expected: Option[Plan] = None): Boolean = {
      d.io.in.valid.poke(query.nonEmpty.B); query.foreach(poke)
      d.io.out.ready.poke(ready.B); d.io.cancel.poke(cancel.B); d.io.cancelGroup.valid.poke(scope.nonEmpty.B)
      scope.foreach { case (generation, tag) =>
        d.io.cancelGroup.bits.identity.context.poke(7.U); d.io.cancelGroup.bits.identity.pass.poke(8.U)
        d.io.cancelGroup.bits.identity.generation.poke(generation.U); d.io.cancelGroup.bits.tag.poke(tag.U)
      }
      val out = d.io.out.valid.peek().litToBoolean
      for (i <- pending.indices if !(i == 0 && out)) {
        val e = pending(i)
        if (cancel || scope.contains((e.q.generation, e.q.tag))) pending(i) = e.copy(stopped = true)
      }
      held.foreach { value => assert(out); assert(d.io.out.bits.peek().litValue == value, "held group plan changed") }
      if (out) { assert(pending.nonEmpty); check(pending.head); if (ready) { pending.remove(0); retired += cycle } }
      held = if (out && !ready) Some(d.io.out.bits.peek().litValue) else None
      val fire = query.nonEmpty && d.io.in.ready.peek().litToBoolean
      if (fire) { val e = expected.getOrElse(reference(query.get)); pending += e.copy(stopped = scope.contains((e.q.generation, e.q.tag))); accepted += cycle }
      d.clock.step(); cycle += 1
      fire
    }
    def drain(): Unit = { var n = 0; while (pending.nonEmpty && n < 3000) { tick(); n += 1 }; assert(pending.isEmpty); tick(); d.io.busy.expect(false.B) }
    def stream(qs: Seq[Query], stalls: Boolean = false): Unit = {
      var index = 0; var n = 0
      while (index < qs.size || pending.nonEmpty) {
        val ready = !stalls || (n % 89 >= 31 && n % 7 != 2)
        if (tick(if (index < qs.size) Some(qs(index)) else None, ready)) index += 1
        n += 1; assert(n < qs.size * 8 + 1000, "group planner did not drain")
      }
      tick(); d.io.busy.expect(false.B)
    }
  }
  it should "plan exact NPOT wrapped offset and FP32 endpoint groups at one complete group per cycle" in {
    simulate(new TextureRgba8GroupPlanner()) { d =>
      val t = new Driver(d)
      val values = Vector[BigInt](0, 0x80000000L, 1, 0x80000001L, 0x007fffffL, 0x3f000000L,
        0x3f7fffffL, 0x3f800000L, 0xbf000000L, 0x7f7fffffL, 0xff7fffffL)
      val qs = (for (n <- Vector(1, 3, 53, 16383); mode <- 0 to 2; linear <- Vector(false, true); i <- values.indices) yield {
        val v = View(width = n, height = 3, row = BigInt(n * 4 + 12), limit = 0x40000000)
        Query(0, view = v, u = values(i), v = values((i + 3) % values.size), linear = linear,
          wraps = Vector(mode, (mode + 1) % 3), offsets = Vector((i % 16) - 8, 7 - (i % 16)))
      }).zipWithIndex.map { case (q, i) => q.copy(tag = i) }
      t.stream(qs)
      assert(t.accepted.sliding(2).forall(p => p(1) - p(0) == 1))
      assert(t.retired.sliding(2).forall(p => p(1) - p(0) == 1))
      info(s"RGBA8_GROUP_PLAN groups=${qs.size} exactRationalCoordinates=true NPOTAnd16383=true allWraps=true offsets=true completeII1=true")
    }
  }
  it should "map all cube faces edges and corners and preserve array layers under backpressure" in {
    simulate(new TextureRgba8GroupPlanner()) { d =>
      val t = new Driver(d); val edges = Vector(-0.25f, 0f, 0.0625f, 0.5f, 0.9375f, 1f, 1.25f)
      val cube = for (n <- Vector(1, 3, 8); face <- 0 until 6; u <- edges; v <- edges; linear <- Vector(false, true)) yield
        Query(0, view = View(target = 2, width = n, height = n, row = n * 4 + 12, plane = 1024),
          face = face, u = bits(u), v = bits(v), linear = linear)
      val array = (0 until 256).map(layer => Query(0, view = View(target = 1, layers = 256, plane = 1024), layer = layer))
      t.stream((cube ++ array).zipWithIndex.map { case (q, i) => q.copy(tag = i) }, stalls = true)
      info("RGBA8_GROUP_CUBE faces6=true edgesAndCorners=true independentVectorProjection=true arrayLayers256=true heldOutputImmutable=true ringWrap=true")
    }
  }
  it should "reject malformed metadata coordinates selectors and full allocation spans before publishing a group" in {
    simulate(new TextureRgba8GroupPlanner()) { d =>
      val t = new Driver(d); val q = Query(0)
      val cases = Vector(
        (q.copy(u = 0x7fc12345L), 5, 4), (q.copy(v = 0xff800000L), 5, 4),
        (q.copy(offsets = Vector(-9, 0)), 1, 3), (q.copy(offsets = Vector(0, 8)), 1, 3),
        (q.copy(wraps = Vector(3, 0)), 6, 3), (q.copy(layer = BigInt(1) << 31), 5, 4),
        (q.copy(face = 0x10000000L), 5, 4),
        (q.copy(view = View(target = 1, layers = 3, plane = 256), layer = 0x10000002L), 5, 4),
        (q.copy(view = View(target = 2, width = 5, height = 5, plane = 256), face = 8), 5, 4),
        (q.copy(view = View(row = 27)), 2, 1), (q.copy(view = View(limit = 0x104090)), 2, 1),
        (q.copy(view = View(base = 0xfffffff0L, limit = BigInt(1) << 32)), 2, 1),
        (q.copy(view = View(target = 1, layers = 3, plane = 128)), 2, 1),
        (q.copy(view = View(bytes = 8)), 6, 3), (q.copy(view = View(block = true)), 6, 3),
        (q.copy(version = 0), 1, 1), (q.copy(tableBase = 0x2001), 1, 1),
        (q.copy(tableLimit = 0x2040), 1, 1), (q.copy(handle = 0xffffffffL), 1, 1),
        (q.copy(view = View(level = 2)), 7, 1),
        (q.copy(u = 0x7f800000L, version = 0), 5, 4),
        (q.copy(view = View(bytes = 8), u = 0x7f800000L), 6, 3))
      for (((raw, code, source), i) <- cases.zipWithIndex) {
        val request = raw.copy(tag = i)
        assert(t.tick(Some(request), expected = Some(Plan(request, code, source, false))))
      }
      t.drain()
    }
  }
  it should "request the general fallback for legal formats volume shadow and unaligned packed words" in {
    simulate(new TextureRgba8GroupPlanner()) { d =>
      val t = new Driver(d)
      val qs = Vector(Query(0, format = TextureImageFormat.id("RGB8"), view = View(bytes = 3)),
        Query(1, view = View(target = 3, depth = 3, slice = 256)),
        Query(2, shadow = true), Query(3, view = View(base = 0x104001)),
        Query(4, view = View(row = 33), v = bits(0.5f)))
      t.stream(qs)
    }
  }
  it should "drain a saturated owner ring after global cancellation while preserving its public result and primary faults" in {
    simulate(new TextureRgba8GroupPlanner()) { d =>
      val t = new Driver(d)
      for (i <- 0 until 64) {
        val q = Query(i, u = if (i == 31) BigInt(0x7fc12345L) else bits(0.4f))
        val e = if (i == 31) Some(Plan(q, 5, 4, false)) else None
        assert(t.tick(Some(q), ready = false, expected = e))
      }
      d.io.busy.expect(true.B)
      assert(!t.tick(Some(Query(64)), ready = false))
      t.tick(ready = false, cancel = true)
      for (_ <- 0 until 16) { assert(!t.tick(Some(Query(64)), ready = false)); d.io.in.ready.expect(false.B) }
      t.drain(); t.stream((100 until 292).map(Query(_)))
      info("RGBA8_GROUP_CANCEL owners64=true actualInputBackpressure=true immutablePublicResult=true primaryFaultRetained=true allMathDrained=true healthyReuse192=true")
    }
  }
  it should "cancel only matching identity tags including concurrent admission and permit later tag reuse" in {
    simulate(new TextureRgba8GroupPlanner()) { d =>
      val t = new Driver(d)
      for (i <- 0 until 8) assert(t.tick(Some(Query(if (i < 2) 77 else i, generation = if (i == 1) 10 else 9)), ready = false))
      assert(t.tick(Some(Query(77)), ready = false, scope = Some((9, 77))))
      t.drain(); t.stream(Vector(Query(77), Query(78, generation = 10)))
    }
  }
}
