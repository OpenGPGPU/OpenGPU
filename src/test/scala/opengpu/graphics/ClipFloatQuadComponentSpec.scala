package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class ClipFloatQuadComponentSpec extends AnyFlatSpec {
  import ClipFloatComponentReference._
  import Fp32ViewportReference.{E, calc, decode, fp, pack}
  private case class Row(weights: Vector[Vector[V]], values: Vector[V], denominators: Vector[V])
  private val latency = 65
  private def row(quad: Int, component: Int): Row = {
    val weights = Vector.tabulate(4) { lane =>
      val x = 1 + 2*(quad%3) + (lane&1); val y = 1 + 2*(quad/3) + (lane>>1)
      Vector(80.0-x-y, x/2.0, y/4.0).map(n => V(decode(fp(n))))
    }
    val values = Vector.tabulate(3)(vertex => V(decode(fp((component-23+vertex*13)/64.0))))
    val denominators = weights.map(w => V(calc(calc(w(0).e, w(1).e, 0), w(2).e, 0)))
    Row(weights, values, denominators)
  }
  private def poke(v: ClipFloat, value: V): Unit = {
    v.negative.poke(value.e.sign.B); v.exponent.poke(value.e.exponent.S)
    v.significand.poke(value.e.sig.U); v.special.poke(value.special.U)
  }
  private def input(d: ClipFloatQuadComponent, r: Row, tag: Int): Unit = {
    for (lane <- 0 until 4; vertex <- 0 until 3) poke(d.io.in.bits.weights(lane)(vertex), r.weights(lane)(vertex))
    for (vertex <- 0 until 3) poke(d.io.in.bits.values(vertex), r.values(vertex))
    for (lane <- 0 until 4) poke(d.io.in.bits.denominators(lane), r.denominators(lane))
    d.io.in.bits.tag.poke(tag.U)
  }
  private def check(d: ClipFloatQuadComponent, r: Row, tag: Int, cancelled: Boolean = false): Unit = {
    d.io.out.bits.tag.expect(tag.U); d.io.out.bits.cancelled.expect(cancelled.B)
    for (lane <- 0 until 4) {
      val expected = reference(Request(r.weights(lane), r.values, r.denominators(lane)))
      val out = d.io.out.bits.lanes(lane)
      withClue(s"quad row=$tag lane=$lane: ") {
        out.tag.expect(tag.U); out.cancelled.expect(cancelled.B)
        out.value.negative.expect(expected.value.e.sign.B); out.value.exponent.expect(expected.value.e.exponent.S)
        out.value.significand.expect(expected.value.e.sig.U); out.value.special.expect(expected.value.special.U)
        out.fp32.expect(pack(expected.value.e).U); out.arithmeticStatus.expect(expected.arithmetic.U)
        out.packingStatus.expect(expected.packing.U); out.finalPackingStatus.expect(expected.finalPacking.U)
        out.error.expect(expected.error.U); out.operations.expect(expected.operations.U)
      }
    }
  }
  private def initialize(d: ClipFloatQuadComponent): Unit = {
    d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
    input(d, row(0, 0), 0); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def run(d: ClipFloatQuadComponent, rows: Seq[Row], dense: Boolean): Unit = {
    val random = new Random(319); val pending = mutable.Queue.empty[(Int, Int)]
    var sent = 0; var seen = 0; var cycle = 0; var held: Option[BigInt] = None
    while (seen < rows.size && cycle < rows.size*16 + latency*4) {
      val offer = sent < rows.size && (dense || random.nextInt(4) != 0)
      val ready = dense || (cycle%37 < 22 && random.nextInt(4) != 0)
      d.io.in.valid.poke(offer.B); d.io.out.ready.poke(ready.B)
      if (offer) input(d, rows(sent), sent)
      val valid = d.io.out.valid.peek().litToBoolean
      held.foreach(value => { assert(valid); assert(d.io.out.bits.peek().litValue == value) })
      held = if (valid && !ready) Some(d.io.out.bits.peek().litValue) else None
      if (valid && ready) {
        assert(pending.nonEmpty)
        val (tag, accepted) = pending.dequeue()
        if (dense) assert(cycle-accepted == latency, s"quad row latency ${cycle-accepted} != $latency")
        check(d, rows(tag), tag); seen += 1
      }
      if (dense) d.io.in.ready.expect(true.B)
      if (offer && d.io.in.ready.peek().litToBoolean) { pending.enqueue((sent, cycle)); sent += 1 }
      d.clock.step(); cycle += 1
    }
    assert(sent == rows.size && seen == rows.size && pending.isEmpty)
    d.io.in.valid.poke(false.B); d.io.busy.expect(false.B); d.io.out.valid.expect(false.B)
    if (dense) assert(cycle == rows.size+latency)
  }

  behavior of "ClipFloatQuadComponent"
  it should "issue all sixty-four components of successive quads in sixty-four cycles per quad" in {
    simulate(new ClipFloatQuadComponent(tagWidth = 16)) { d =>
      initialize(d)
      run(d, for (q <- 0 until 4; component <- 0 until 64) yield row(q, component), dense = true)
      info(s"QUAD_COMPONENT_PIPELINES lanes=4 rows=256 components=1024 initiationInterval=1 cyclesPerFullQuad=64 latency=$latency separateRNE=true")
    }
  }
  it should "keep four lane results and faults aligned through long output stalls" in {
    simulate(new ClipFloatQuadComponent(tagWidth = 16)) { d =>
      initialize(d)
      val random = new Random(791)
      def value = V(E(random.nextBoolean(), random.nextInt(301)-150, 0x800000 | random.nextInt(0x800000)))
      val normal = (0 until 256).map(_ => Row(Vector.fill(4)(Vector.fill(3)(value)),
        Vector.fill(3)(value), Vector.fill(4)(value)))
      val basic = row(0, 1); val bad = V(E(exponent = 512, sig = 0x800000))
      val badWeight = basic.copy(weights = basic.weights.updated(2, basic.weights(2).updated(0, bad)))
      val badDenominator = basic.copy(denominators = basic.denominators.updated(1, bad))
      run(d, normal ++ Seq(badWeight, badDenominator, basic), dense = false)
    }
  }
  it should "drain cancelled rows while cancel stays high and preserve an offered four-lane result" in {
    simulate(new ClipFloatQuadComponent(tagWidth = 16)) { d =>
      initialize(d); val basic = row(0, 0); d.io.in.valid.poke(true.B)
      var accepted = 0
      while (d.io.in.ready.peek().litToBoolean && accepted < 256) {
        input(d, basic, accepted); d.clock.step(); accepted += 1
      }
      assert(accepted >= 64 && accepted <= 128); d.io.out.valid.expect(true.B)
      val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 7) { d.clock.step(); assert(d.io.out.bits.peek().litValue == held) }
      d.io.out.ready.poke(true.B)
      var seen = 0; var cycles = 0
      while (d.io.busy.peek().litToBoolean && cycles < accepted+latency*2) {
        d.io.in.ready.expect(false.B)
        if (d.io.out.valid.peek().litToBoolean) { check(d, basic, seen, cancelled = seen != 0); seen += 1 }
        d.clock.step(); cycles += 1
      }
      assert(seen == accepted); d.io.busy.expect(false.B)
      d.io.in.valid.poke(false.B); d.clock.step(); d.io.cancel.poke(false.B)
      run(d, (0 until 64).map(component => row(1, component)), dense = true)
    }
  }
}
