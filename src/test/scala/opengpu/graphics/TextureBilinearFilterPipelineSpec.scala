package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TextureBilinearFilterPipelineSpec extends AnyFlatSpec {
  import TextureFilterReference._
  private case class Row(colors: Seq[Seq[BigInt]], weights: Seq[BigInt], tag: BigInt,
                         axes: Int = 2) {
    val bad: Boolean = axes != 2 || weights.exists(w =>
      ((w & 0x80000000L) != 0 && (w & 0x7fffffffL) != 0) || (w & 0x7fffffffL) > 0x3f800000L)
    def expected: Seq[BigInt] = filter(colors, weights, 2)
  }
  private class Driver(val d: TextureBilinearFilterPipeline) {
    WindowRecordTest.zero(d.io)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    var cycle = 0
    def tick(): Unit = { d.clock.step(); cycle += 1 }
    def offer(row: Row): Unit = {
      d.io.in.valid.poke(true.B)
      row.colors.zipWithIndex.foreach { case (rgba, i) =>
        rgba.zipWithIndex.foreach { case (v, c) => d.io.in.bits.values(i)(c).poke(v.U) }
      }
      row.weights.zipWithIndex.foreach { case (v, i) => d.io.in.bits.weights(i).poke(v.U) }
      d.io.in.bits.axes.poke(row.axes.U); d.io.in.bits.tag.poke(row.tag.U)
    }
    def idle(): Unit = { d.io.in.valid.poke(false.B); WindowRecordTest.zero(d.io.in.bits) }
    def check(row: Row, status: Int = 0): Unit = {
      d.io.out.bits.tag.expect(row.tag.U)
      val effective = if (row.bad) 6 else status
      d.io.out.bits.status.expect(effective.U)
      val expected = if (effective == 0) row.expected else Seq.fill(4)(BigInt(0))
      d.io.out.bits.rgba.zip(expected).zipWithIndex.foreach { case ((v, e), c) =>
        assert(v.peek().litValue == e,
          s"tag=${row.tag} channel=$c observed=${v.peek().litValue.toString(16)} expected=${e.toString(16)} colors=${row.colors.map(_.map(_.toString(16)))} weights=${row.weights.map(_.toString(16))}")
      }
    }
    def stream(rows: Seq[Row], backpressure: Boolean): (Vector[Int], Vector[Int]) = {
      var sent = 0; var retired = 0
      val accepted = Vector.newBuilder[Int]; val completed = Vector.newBuilder[Int]
      var held = Option.empty[BigInt]
      val limit = cycle + rows.size * 40 + 1000
      while (retired < rows.size && cycle < limit) {
        if (sent < rows.size) offer(rows(sent)) else idle()
        val ready = !backpressure || (cycle % 29 >= 13 && cycle % 7 != 0)
        d.io.out.ready.poke(ready.B)
        held.foreach { v =>
          d.io.out.valid.expect(true.B); assert(d.io.out.bits.peek().litValue == v, "held result changed")
        }
        val valid = d.io.out.valid.peek().litToBoolean
        if (valid) { assert(retired < sent); check(rows(retired)) }
        held = if (valid && !ready) Some(d.io.out.bits.peek().litValue) else None
        if (sent < rows.size && d.io.in.ready.peek().litToBoolean) { accepted += cycle; sent += 1 }
        if (valid && ready) { completed += cycle; retired += 1 }
        tick()
      }
      assert(retired == rows.size, s"bilinear timeout sent=$sent retired=$retired")
      idle(); d.io.busy.expect(false.B)
      (accepted.result(), completed.result())
    }
  }
  private def row(index: Int): Row = {
    val colors = Vector.tabulate(4)(p => Vector.tabulate(4)(c => bits(((index * 31 + p * 67 + c * 53) & 255).toFloat / 255.0f)))
    val weights = Vector(bits(((index * 127) & 65535).toFloat / 65535.0f),
      bits(((index * 3511 + 199) & 65535).toFloat / 65535.0f))
    Row(colors, weights, BigInt(0xf0000000L) + index)
  }

  it should "preserve unquantized native FP32 filtering across finite special and invalid inputs" in {
    simulate(new TextureBilinearFilterPipeline()) { d =>
      val t = new Driver(d); val rng = new scala.util.Random(0x42494c49)
      val weights = Vector[BigInt](0, 0x80000000L, 1, 0x007fffffL, 0x00800000L,
        0x33800000L, 0x3eaaaaabL, 0x3f000001L, 0x3f7fffffL, 0x3f800000L)
      val random = Vector.tabulate(512) { i =>
        val colors = if (i < 256) row(i).colors else Vector.fill(4)(Vector.fill(4)(BigInt(rng.nextLong() & 0xffffffffL)))
        Row(colors, Vector(weights(i % weights.size), bits(rng.nextFloat())), i)
      }
      val specials = Vector[BigInt](0, 0x80000000L, 1, 0x80000001L, 0x7f800000L,
        0xff800000L, 0x7fc12345L, 0xffc54321L)
      val endpoints = for (a <- specials; b <- specials; w <- Vector[BigInt](0, 0x80000000L, 0x3f800000L, 0x3f000000L)) yield {
        Row(Vector.fill(2)(Vector.fill(4)(a)) ++ Vector.fill(2)(Vector.fill(4)(b)), Vector(w, w), 1000 + specials.indexOf(a) * 64 + specials.indexOf(b) * 4 + w % 4)
      }
      val duplicates = specials.map(v => Row(Vector.fill(4)(Vector.fill(4)(v)), Vector(bits(0.37f), bits(0.61f)), 2000 + specials.indexOf(v)))
      val invalid = for (axis <- 0 until 2; bad <- Vector[BigInt](0xbf000000L, 0x3f800001L, 0x7f800000L, 0x7fc12345L))
        yield row(3000 + axis).copy(weights = row(0).weights.updated(axis, bad))
      val badAxes = Vector(0, 1, 3, 7).map(a => row(4000 + a).copy(axes = a))
      val all = random ++ endpoints ++ duplicates ++ invalid ++ badAxes
      t.stream(all, backpressure = true)
      info(s"BILINEAR_FP32 numericRows=${all.size} exactNativeInverseProductFMA=true subnormalWeights=true endpointsAndNaNPayloads=true invalidWeightsAndAxes=true heldOutputStable=true")
    }
  }

  it should "sustain complete group II1 and retain order through saturated backpressure" in {
    simulate(new TextureBilinearFilterPipeline()) { d =>
      val t = new Driver(d)
      val (accepted, completed) = t.stream(Vector.tabulate(192)(row), backpressure = false)
      assert(accepted.sliding(2).forall(p => p(1) - p(0) == 1), "input group rate is below II1")
      assert(completed.sliding(2).forall(p => p(1) - p(0) == 1), "output group rate is below II1")
      t.stream(Vector.tabulate(384)(i => row(i + 192)), backpressure = true)
      info("BILINEAR_FP32_RATE continuousGroups192=true inputII1=true outputII1=true backpressuredGroups384=true allChannelsAndTagsExact=true")
    }
  }

  it should "poison hidden rows across arithmetic and held-output cuts then drain and reuse" in {
    simulate(new TextureBilinearFilterPipeline()) { d =>
      val t = new Driver(d); var hidden = 0; var public = 0
      for (cut <- Vector(0, 1, 3, 7, 8, 9, 16, 24, 32, 48, 64, 96, 192)) {
        val rows = Vector.tabulate(if (cut < 96) 8 else 256)(i => row(cut * 1000 + i).copy(
          weights = if (i % 17 == 4) Vector(BigInt(0x7fc12345L), bits(0.5f)) else row(cut * 1000 + i).weights))
        d.io.out.ready.poke(false.B)
        var sent = 0
        while (sent < rows.size && d.io.in.ready.peek().litToBoolean) {
          t.offer(rows(sent)); t.tick(); sent += 1
        }
        assert(sent > 0)
        if (cut >= 96) { assert(sent < rows.size && sent <= 128); d.io.in.ready.expect(false.B) }
        t.idle()
        for (_ <- 0 until cut) t.tick()
        val offered = d.io.out.valid.peek().litToBoolean
        val held = if (offered) Some(d.io.out.bits.peek().litValue) else None
        d.io.cancel.poke(true.B); d.io.in.ready.expect(false.B); t.tick(); d.io.cancel.poke(false.B)
        for (_ <- 0 until 7) {
          held.foreach(v => assert(d.io.out.bits.peek().litValue == v))
          d.io.in.ready.expect(false.B); t.tick()
        }
        d.io.out.ready.poke(true.B)
        var retired = 0; val limit = t.cycle + 2000
        while (retired < sent && t.cycle < limit) {
          d.io.in.ready.expect(false.B)
          if (d.io.out.valid.peek().litToBoolean) {
            val status = if (offered && retired == 0) 0 else 4
            t.check(rows(retired), status)
            if (status == 0) public += 1 else hidden += 1
            retired += 1
          }
          t.tick()
        }
        assert(retired == sent); d.io.busy.expect(false.B)
        t.tick() // Reopen the fully drained epoch.
        t.stream(Vector(row(cut + 500000)), backpressure = false)
      }
      assert(hidden > 0 && public > 0)
      info(s"BILINEAR_FP32_CANCEL cuts13=true saturatedUntilBackpressured2=true hiddenRows=$hidden immutablePublic=$public primaryFormatFaultPreserved=true arithmeticNeverFlushed=true healthyReuse=true")
    }
  }
}
