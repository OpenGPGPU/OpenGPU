package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

object ClipFloatComponentReference {
  import Fp32ViewportReference.{E, calc, pack, rational}
  case class V(e: E = E(), special: Int = 0)
  case class Request(weights: Vector[V], values: Vector[V], denominator: V)
  case class Expected(value: V, arithmetic: Int, packing: Int, finalPacking: Int,
      error: Int = 0, operations: Int = 6)
  val one = V(E(sig = 0x800000))
  val baseline = Request(Vector.fill(3)(one), Vector.fill(3)(one), V(E(exponent = 1, sig = 0xc00000)))
  private def legal(v: V): Boolean = v.special == 0 && v.e.exponent >= -512 && v.e.exponent <= 511 &&
    (if (v.e.sig == 0) v.e.exponent == 0 else v.e.sig.testBit(23))
  private def packing(e: E): Int = {
    if (e.sig == 0 || e.exponent >= -126 && e.exponent <= 127) 0
    else if (e.exponent > 127) 5
    else {
      val bits = pack(e); val (n, d) = rational(e)
      val (pn, pd) = rational(Fp32ViewportReference.decode(bits))
      val lost = n*pd != pn*d
      (if (lost) 1 else 0) | (if (lost && ((bits >> 23)&255) == 0) 2 else 0)
    }
  }
  // Independent rational rounding at each specified operation, not fused math.
  def reference(r: Request): Expected = {
    var flags = 0; var packedFlags = 0; var error = 0; var operations = 0
    var finalPacking = 0
    def operation(a: V, b: V, op: Int): V = {
      if (error != 0) return V()
      operations += 1
      if (!legal(a) || !legal(b)) { error = 1; return V() }
      val (an, ad) = rational(a.e); val (bn, bd) = rational(b.e)
      val (n, d) = op match {
        case 0 => (an*bd + bn*ad, ad*bd)
        case 2 => (an*bn, ad*bd)
        case 3 => (an*bd, ad*bn)
      }
      require(d != 0)
      val value = calc(a.e, b.e, op); val (vn, vd) = rational(value)
      if (n*vd != vn*d) flags |= 1
      if (value.sig != 0 && (value.exponent < -512 || value.exponent > 511)) {
        error = 2; finalPacking = 0; return V()
      }
      finalPacking = packing(value); packedFlags |= finalPacking
      V(value)
    }
    val p0 = operation(r.weights(0), r.values(0), 2)
    val p1 = operation(r.weights(1), r.values(1), 2)
    val s01 = operation(p0, p1, 0)
    val p2 = operation(r.weights(2), r.values(2), 2)
    val sum = operation(s01, p2, 0)
    val value = operation(sum, r.denominator, 3)
    Expected(if (error == 0) value else V(), flags, packedFlags,
      if (error == 0) finalPacking else 0, error, operations)
  }
}

class ClipFloatWeightedComponentSpec extends AnyFlatSpec {
  import Fp32ViewportReference.{E, pack}
  import ClipFloatComponentReference._
  private val latency = 64
  private def poke(v: ClipFloat, value: V): Unit = {
    v.negative.poke(value.e.sign.B); v.exponent.poke(value.e.exponent.S)
    v.significand.poke(value.e.sig.U); v.special.poke(value.special.U)
  }
  private def input(d: ClipFloatWeightedComponent, r: Request, tag: Int): Unit = {
    for (i <- 0 until 3) { poke(d.io.in.bits.weights(i), r.weights(i)); poke(d.io.in.bits.values(i), r.values(i)) }
    poke(d.io.in.bits.denominator, r.denominator); d.io.in.bits.tag.poke(tag.U)
  }
  private def check(d: ClipFloatWeightedComponent, e: Expected, tag: Int, cancelled: Boolean = false): Unit = {
    val out = d.io.out.bits
    withClue(s"component tag=$tag: ") {
      out.tag.expect(tag.U); out.cancelled.expect(cancelled.B)
      out.value.negative.expect(e.value.e.sign.B); out.value.exponent.expect(e.value.e.exponent.S)
      out.value.significand.expect(e.value.e.sig.U); out.value.special.expect(e.value.special.U)
      val bits = if (e.value.special == 0) pack(e.value.e) else
        (if (e.value.e.sign) BigInt(1) << 31 else BigInt(0)) | (BigInt(255) << 23) | e.value.e.sig
      out.fp32.expect(bits.U); out.arithmeticStatus.expect(e.arithmetic.U)
      out.packingStatus.expect(e.packing.U); out.finalPackingStatus.expect(e.finalPacking.U)
      out.error.expect(e.error.U); out.operations.expect(e.operations.U)
    }
  }
  private def initialize(d: ClipFloatWeightedComponent): Unit = {
    d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
    input(d, baseline, 0); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def run(d: ClipFloatWeightedComponent, requests: Seq[(Request, Expected)], dense: Boolean): Unit = {
    val random = new Random(71); val pending = mutable.Queue.empty[(Int, Int)]
    var sent = 0; var seen = 0; var cycle = 0; var held: Option[BigInt] = None
    while (seen < requests.size && cycle < requests.size*8 + latency*4) {
      val offer = sent < requests.size && (dense || random.nextInt(4) != 0)
      val ready = dense || random.nextInt(3) != 0
      d.io.in.valid.poke(offer.B); d.io.out.ready.poke(ready.B)
      if (offer) input(d, requests(sent)._1, sent)
      val valid = d.io.out.valid.peek().litToBoolean
      held.foreach(value => { assert(valid); assert(d.io.out.bits.peek().litValue == value) })
      held = if (valid && !ready) Some(d.io.out.bits.peek().litValue) else None
      if (valid && ready) {
        assert(pending.nonEmpty)
        val (tag, accepted) = pending.dequeue()
        if (dense) assert(cycle-accepted == latency, s"component latency ${cycle-accepted} != $latency")
        check(d, requests(tag)._2, tag); seen += 1
      }
      if (dense) d.io.in.ready.expect(true.B)
      if (offer && d.io.in.ready.peek().litToBoolean) { pending.enqueue((sent, cycle)); sent += 1 }
      d.clock.step(); cycle += 1
    }
    assert(sent == requests.size && seen == requests.size && pending.isEmpty)
    d.io.in.valid.poke(false.B); d.io.busy.expect(false.B); d.io.out.valid.expect(false.B)
    if (dense) assert(cycle == requests.size+latency)
  }
  behavior of "ClipFloatWeightedComponent"
  it should "sustain one independently rounded weighted component per cycle" in {
    simulate(new ClipFloatWeightedComponent(tagWidth = 16)) { d =>
      initialize(d)
      val random = new Random(312)
      def value = V(E(random.nextBoolean(), random.nextInt(161)-80, 0x800000 | random.nextInt(0x800000)))
      val requests = (0 until 512).map { _ =>
        val r = Request(Vector.fill(3)(value), Vector.fill(3)(value), value)
        (r, reference(r))
      }
      run(d, requests, dense = true)
      info(s"WEIGHTED_COMPONENT components=512 scalarOperations=3072 initiationInterval=1 latency=$latency exactSeparateRNE=true")
    }
  }
  it should "preserve flags first-error order and ownership under mixed traffic and stalls" in {
    simulate(new ClipFloatWeightedComponent(tagWidth = 16)) { d =>
      initialize(d)
      val random = new Random(193)
      def value = V(E(random.nextBoolean(), random.nextInt(1024)-512, 0x800000 | random.nextInt(0x800000)))
      val bad = V(E(exponent = 512, sig = 0x800000))
      val malformed = (0 until 3).map(i => baseline.copy(values = baseline.values.updated(i, bad))) :+
        baseline.copy(denominator = bad)
      val overflow = baseline.copy(weights = baseline.weights.updated(0, one.copy(e = one.e.copy(exponent = 511))),
        values = baseline.values.updated(0, one.copy(e = one.e.copy(exponent = 511))))
      val normal = (malformed :+ overflow) ++ (0 until 768).map { _ =>
        Request(Vector.fill(3)(value), Vector.fill(3)(value), value)
      }
      val nan = V(E(sig = 0x400000), 2); val inf = V(E(), 1)
      val specials = Seq(
        baseline.copy(values = baseline.values.updated(2, nan)) -> Expected(nan, 16, 0, 0),
        baseline.copy(values = baseline.values.updated(0, inf)) -> Expected(inf, 0, 0, 0),
        baseline.copy(denominator = V()) -> Expected(inf, 8, 0, 0),
        baseline.copy(values = Vector.fill(3)(V()), denominator = V()) -> Expected(nan, 16, 0, 0),
        baseline.copy(values = Vector(bad, one, nan)) -> Expected(V(), 0, 0, 0, error = 1, operations = 1))
      run(d, normal.map(r => r -> reference(r)) ++ specials, dense = false)
    }
  }
  it should "drain all accepted components while cancel stays high and retain an offered result" in {
    simulate(new ClipFloatWeightedComponent(tagWidth = 16)) { d =>
      initialize(d); d.io.in.valid.poke(true.B)
      var accepted = 0
      while (d.io.in.ready.peek().litToBoolean && accepted < 256) {
        input(d, baseline, accepted); d.clock.step(); accepted += 1
      }
      assert(accepted >= 64 && accepted <= 128); d.io.out.valid.expect(true.B)
      val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 5) { d.clock.step(); assert(d.io.out.bits.peek().litValue == held) }
      d.io.out.ready.poke(true.B)
      var seen = 0; var cycles = 0
      while (d.io.busy.peek().litToBoolean && cycles < accepted+latency*2) {
        d.io.in.ready.expect(false.B)
        if (d.io.out.valid.peek().litToBoolean) {
          check(d, reference(baseline), seen, cancelled = seen != 0); seen += 1
        }
        d.clock.step(); cycles += 1
      }
      assert(seen == accepted); d.io.busy.expect(false.B); d.io.in.ready.expect(false.B)
      d.io.in.valid.poke(false.B); d.clock.step(); d.io.cancel.poke(false.B)
      run(d, Seq.fill(128)(baseline -> reference(baseline)), dense = true)
      // Cancel before any result is offered; every accepted token must drain.
      d.io.in.valid.poke(true.B)
      for (i <- 0 until 10) { input(d, baseline, i); d.clock.step() }
      d.io.cancel.poke(true.B); seen = 0; cycles = 0
      while (d.io.busy.peek().litToBoolean && cycles < latency+20) {
        d.io.in.ready.expect(false.B)
        if (d.io.out.valid.peek().litToBoolean) {
          check(d, reference(baseline), seen, cancelled = true); seen += 1
        }
        d.clock.step(); cycles += 1
      }
      assert(seen == 10); d.io.busy.expect(false.B)
      d.io.in.valid.poke(false.B); d.clock.step(); d.io.cancel.poke(false.B)
      run(d, Seq.fill(64)(baseline -> reference(baseline)), dense = true)
    }
  }
}
