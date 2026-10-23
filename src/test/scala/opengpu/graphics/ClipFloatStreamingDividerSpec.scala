package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable
import scala.util.Random

class ClipFloatStreamingDividerSpec extends AnyFlatSpec {
  private case class Value(negative: Boolean = false, exponent: Int = 0,
      significand: BigInt = 0, special: Int = 0)
  private case class Request(a: Value, b: Value, operation: Int = ClipFloatOperation.divide)
  private case class Expected(value: Value, flags: Int = 0, error: Int = 0)
  private val one = Value(significand = 0x800000)
  private val latency = ClipFloatStreamingDivider.latency

  private def legal(v: Value): Boolean = v.special match {
    case 0 => v.exponent >= -512 && v.exponent <= 511 &&
      (if (v.significand == 0) v.exponent == 0 else v.significand.testBit(23))
    case 1 => v.exponent == 0 && v.significand == 0
    case 2 => v.exponent == 0 && v.significand > 0 && v.significand < 0x800000
    case _ => false
  }
  private def round(n: BigInt, d: BigInt): (BigInt, Boolean) = {
    val (q, r) = n /% d
    (q + (if (2*r > d || (2*r == d && q.testBit(0))) 1 else 0), r != 0)
  }
  // Exact rational oracle: neither the RTL bit recurrence nor its packer is used.
  private def reference(r: Request): Expected = {
    val a = r.a; val b = r.b; val sign = a.negative ^ b.negative
    if (!legal(a) || !legal(b) || r.operation != ClipFloatOperation.divide)
      return Expected(Value(), error = 1)
    val az = a.special == 0 && a.significand == 0
    val bz = b.special == 0 && b.significand == 0
    if (a.special == 2 || b.special == 2 || (a.special == 1 && b.special == 1) || (az && bz))
      return Expected(Value(significand = 0x400000, special = 2), 16)
    if (a.special == 1 || bz)
      return Expected(Value(sign, special = 1), if (a.special != 1 && bz) 8 else 0)
    if (az || b.special == 1) return Expected(Value(sign))
    val shift = a.exponent - b.exponent
    val n = if (shift >= 0) a.significand << shift else a.significand
    val d = if (shift >= 0) b.significand else b.significand << -shift
    var exponent = n.bitLength - d.bitLength
    if (if (exponent >= 0) n < (d << exponent) else (n << -exponent) < d) exponent -= 1
    val scale = 23 - exponent
    val (q, inexact) = round(if (scale >= 0) n << scale else n,
      if (scale >= 0) d else d << -scale)
    val carry = q.bitLength > 24
    exponent += (if (carry) 1 else 0)
    val flags = if (inexact) 1 else 0
    if (exponent < -512 || exponent > 511) Expected(Value(), flags, 2)
    else Expected(Value(sign, exponent, if (carry) q >> 1 else q), flags)
  }
  private def pack(v: Value): (BigInt, Int) = {
    val sign = if (v.negative) BigInt(1) << 31 else BigInt(0)
    if (v.special != 0) return (sign | (BigInt(255) << 23) | (v.significand & 0x7fffff), 0)
    if (v.significand == 0) return (sign, 0)
    if (v.exponent > 127) return (sign | BigInt("7f800000", 16), 5)
    if (v.exponent >= -126)
      return (sign | (BigInt(v.exponent + 127) << 23) | (v.significand & 0x7fffff), 0)
    val (q, inexact) = round(v.significand, BigInt(1) << (-126 - v.exponent))
    (sign | q, (if (inexact) 1 else 0) | (if (inexact && q < 0x800000) 2 else 0))
  }
  private def poke(v: ClipFloat, value: Value): Unit = {
    v.negative.poke(value.negative.B); v.exponent.poke(value.exponent.S)
    v.significand.poke(value.significand.U); v.special.poke(value.special.U)
  }
  private def input(d: ClipFloatStreamingDivider, request: Request, tag: Int): Unit = {
    poke(d.io.in.bits.a, request.a); poke(d.io.in.bits.b, request.b)
    d.io.in.bits.operation.poke(request.operation.U); d.io.in.bits.tag.poke(tag.U)
  }
  private def check(d: ClipFloatStreamingDivider, request: Request, tag: Int,
      cancelled: Boolean = false): Unit = {
    val e = reference(request); val r = d.io.out.bits
    withClue(s"tag=$tag request=$request: ") {
      r.tag.expect(tag.U); r.cancelled.expect(cancelled.B)
      r.value.negative.expect(e.value.negative.B); r.value.exponent.expect(e.value.exponent.S)
      r.value.significand.expect(e.value.significand.U); r.value.special.expect(e.value.special.U)
      r.arithmeticStatus.expect(e.flags.U); r.error.expect(e.error.U)
      val (bits, flags) = pack(e.value)
      r.fp32.expect(bits.U); r.packingStatus.expect(flags.U)
    }
  }
  private def initialize(d: ClipFloatStreamingDivider): Unit = {
    d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B); d.io.cancel.poke(false.B)
    input(d, Request(one, one), 0)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  private def run(d: ClipFloatStreamingDivider, requests: Seq[Request], dense: Boolean,
      reset: Boolean = true): Unit = {
    if (reset) initialize(d)
    val random = new Random(917)
    val pending = mutable.Queue.empty[(Int, Int)]
    var sent = 0; var received = 0; var cycle = 0
    var held: Option[BigInt] = None
    while (received < requests.size && cycle < requests.size * 8 + latency * 4) {
      val offer = sent < requests.size && (dense || random.nextInt(4) != 0)
      val ready = dense || random.nextInt(3) != 0
      d.io.in.valid.poke(offer.B); d.io.out.ready.poke(ready.B)
      if (offer) input(d, requests(sent), sent)
      val outputValid = d.io.out.valid.peek().litToBoolean
      held.foreach(bits => { assert(outputValid); assert(d.io.out.bits.peek().litValue == bits) })
      held = if (outputValid && !ready) Some(d.io.out.bits.peek().litValue) else None
      if (outputValid && ready) {
        assert(pending.nonEmpty, s"unexpected output at cycle $cycle")
        val (tag, accepted) = pending.dequeue()
        if (dense) assert(cycle - accepted == latency, s"latency ${cycle-accepted} != $latency")
        check(d, requests(tag), tag); received += 1
      }
      if (dense) d.io.in.ready.expect(true.B)
      if (offer && d.io.in.ready.peek().litToBoolean) { pending.enqueue((sent, cycle)); sent += 1 }
      d.clock.step(); cycle += 1
    }
    assert(sent == requests.size && received == requests.size && pending.isEmpty)
    d.io.in.valid.poke(false.B); d.io.out.valid.expect(false.B); d.io.busy.expect(false.B)
    if (dense) assert(cycle == requests.size + latency)
  }

  behavior of "ClipFloatStreamingDivider"
  it should "accept and retire a dense full-quad component stream at one operation per cycle" in {
    simulate(new ClipFloatStreamingDivider(tagWidth = 16)) { d =>
      val requests = (0 until 512).map { i =>
        Request(Value((i&1) != 0, (i%301)-150, 0x800000 + (i*2971 & 0x7fffff)),
          Value((i&2) != 0, (i%127)-63, 0x800000 + (i*7919 & 0x7fffff)))
      }
      run(d, requests, dense = true)
      info(s"STREAMING_DIVIDE requests=${requests.size} initiationInterval=1 latency=$latency exactRationalRNE=true")
    }
  }
  it should "preserve extended division and independent FP32 packing under bubbles and backpressure" in {
    simulate(new ClipFloatStreamingDivider(tagWidth = 16)) { d =>
      val random = new Random(0x51d1)
      val boundaries = for {
        e <- Seq(-512, -151, -150, -149, -127, -126, 0, 127, 128, 511)
        a <- Seq(0x800000, 0x800001, 0x800003, 0xbfffff, 0xffffff)
        b <- Seq(0x800000, 0x800001, 0xc00000, 0xffffff)
      } yield Request(Value(exponent = e, significand = a), Value(significand = b))
      val requests = boundaries ++ (0 until 1024).map { _ =>
        def value = Value(random.nextBoolean(), random.nextInt(1024)-512,
          0x800000 | random.nextInt(0x800000))
        Request(value, value)
      }
      run(d, requests, dense = false)
    }
  }
  it should "order specials malformed operands and exponent range failures with normal traffic" in {
    simulate(new ClipFloatStreamingDivider(tagWidth = 16)) { d =>
      val values = for {
        sign <- Seq(false, true)
        v <- Seq(Value(), one, Value(significand = 0xc00000),
          Value(special = 1), Value(significand = 1, special = 2),
          Value(significand = 0x400000, special = 2))
      } yield v.copy(negative = sign)
      val malformed = Seq(Value(exponent = 512, significand = 0x800000),
        Value(exponent = -513, significand = 0x800000), Value(exponent = 1),
        Value(significand = 0x7fffff), Value(significand = 1, special = 1),
        Value(exponent = 1, special = 1), Value(special = 2),
        Value(significand = 0x800001, special = 2), Value(special = 3))
      val requests = (for (a <- values; b <- values) yield Request(a, b)) ++
        malformed.flatMap(v => Seq(Request(v, one), Request(one, v))) ++
        Seq(0, 1, 2, 4, 5, 6, 7).map(op => Request(one, one, op)) ++
        Seq(Request(one.copy(exponent = 511), one.copy(exponent = -512)),
          Request(one.copy(exponent = -512), one.copy(exponent = 511)), Request(one, one))
      run(d, requests, dense = true)
    }
  }
  it should "drain cancelled tokens without mutating an offered response and recover admission" in {
    simulate(new ClipFloatStreamingDivider(tagWidth = 16)) { d =>
      initialize(d)
      val request = Request(one, Value(exponent = 1, significand = 0xc00000))
      d.io.in.valid.poke(true.B)
      for (i <- 0 until latency) {
        input(d, request, i); d.io.in.ready.expect(true.B); d.clock.step()
      }
      d.io.out.valid.expect(true.B); d.io.in.ready.expect(false.B)
      val held = d.io.out.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 5) {
        d.clock.step(); d.io.in.ready.expect(false.B)
        assert(d.io.out.bits.peek().litValue == held)
      }
      d.io.in.valid.poke(false.B); d.io.cancel.poke(false.B); d.io.out.ready.poke(true.B)
      for (i <- 0 until latency) {
        d.io.out.valid.expect(true.B); check(d, request, i, cancelled = i != 0); d.clock.step()
      }
      d.io.busy.expect(false.B)
      // Cancel while the pipeline is advancing, before any output is offered.
      d.io.in.valid.poke(true.B)
      for (i <- 0 until 10) { input(d, request, i); d.clock.step() }
      d.io.cancel.poke(true.B); d.io.in.ready.expect(false.B); d.clock.step()
      d.io.in.valid.poke(false.B); d.io.cancel.poke(false.B)
      var seen = 0; var cycles = 0
      while (d.io.busy.peek().litToBoolean && cycles < latency * 2) {
        if (d.io.out.valid.peek().litToBoolean) { check(d, request, seen, cancelled = true); seen += 1 }
        d.clock.step(); cycles += 1
      }
      assert(seen == 10); d.io.busy.expect(false.B)
      run(d, Seq.fill(64)(request), dense = true, reset = false)
    }
  }
}
