package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class PreparedFetchWindowEpochSpec extends AnyFlatSpec {
  private val formats = Seq(0, 1, 2, 3, 4, 7)
  private val weights = Seq(
    Seq(65536, 0, 0, 0), Seq.fill(4)(16384),
    Seq(32768, 16384, 8192, 8192), Seq(0, 0, 32768, 32768))
  private def raw(tap: Int, epoch: Int): BigInt =
    BigInt("10203040", 16) + BigInt("11111111", 16) * tap + epoch * 0x07050301L
  private def decode(word: BigInt, format: Int): BigInt = {
    val bytes = (0 until 4).map(i => (word >> (i * 8)) & 255)
    val channels = format match {
      case 1 => Seq(bytes(0), bytes(1), bytes(2), bytes(3))
      case 2 => Seq(bytes(2), bytes(1), bytes(0), bytes(3))
      case 3 => Seq(bytes(0), bytes(1), bytes(2), BigInt(255))
      case 4 => Seq(bytes(2), bytes(1), bytes(0), BigInt(255))
      case _ => Seq(bytes(3), bytes(2), bytes(1), bytes(0))
    }
    channels.foldLeft(BigInt(0))((result, channel) => (result << 8) | channel)
  }
  private def expected(sample: Int): BigInt = {
    val taps = (0 until 4).map(tap => decode(raw(tap, if (sample < 16) 0 else 1),
      formats(sample % formats.size)))
    (0 until 4).foldLeft(BigInt(0)) { (result, channel) =>
      val value = taps.zip(weights(sample % weights.size))
        .map { case (word, weight) => ((word >> (24 - 8 * channel)) & 255) * weight }.sum >> 16
      (result << 8) | value
    }
  }

  private def exercise(stallOutput: Boolean, pulseOnly: Boolean = false): Unit = {
    simulate(new TextureUnit(wordCacheEntries = 8, allowPrepared = true,
      overlapPrepared = true)) { d =>
      d.io.texBase.poke(0x3000.U); d.io.texWidth.poke(16.U); d.io.texHeight.poke(16.U)
      d.io.texFormat.poke(0.U); d.io.wrapMode.poke(true.B); d.io.texMaxLevel.poke(0.U)
      d.io.invalidateCache.get.poke(false.B); d.io.sample.valid.poke(false.B)
      d.io.sample.bits.poke(0.U.asTypeOf(d.io.sample.bits))
      d.io.prepared.get.valid.poke(false.B)
      d.io.prepared.get.bits.poke(0.U.asTypeOf(d.io.prepared.get.bits))
      d.io.result.ready.poke(false.B); d.io.mem.req.ready.poke(false.B)
      d.io.mem.resp.valid.poke(false.B)
      d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

      val pending = mutable.ArrayBuffer.empty[(Long, Int, BigInt)]
      val accepted = mutable.ArrayBuffer.empty[Int]
      var presenting = Option.empty[(Long, Int, BigInt)]
      var previousHeld = Option.empty[BigInt]
      var sent = 0; var received = 0; var cycle = 0; var requests = 0; var epoch = 0
      var held = 0; var returned = 0; var warmAt = -1; var invalidatePulsed = false
      while (received < 24 && cycle < 1200) {
        d.io.sample.valid.poke((sent < 24).B)
        d.io.prepared.get.valid.poke((sent < 24).B)
        val invalidate = sent == 16 && (!pulseOnly || !invalidatePulsed)
        d.io.invalidateCache.get.poke(invalidate.B)
        if (invalidate) invalidatePulsed = true
        d.io.texFormat.poke(formats(sent % formats.size).U)
        for (tap <- 0 until 4) {
          d.io.prepared.get.bits.addresses(tap).poke((0x3000 + tap * 4).U)
          d.io.prepared.get.bits.weights(tap).poke(weights(sent % weights.size)(tap).U)
        }
        val ready = !stallOutput || cycle % 9 >= 4
        d.io.result.ready.poke(ready.B)
        d.io.mem.req.ready.poke((cycle % 7 != 1).B)
        if (presenting.isEmpty) {
          pending.indices.filter(i => pending(i)._2 <= cycle).lastOption
            .foreach(i => presenting = Some(pending(i)))
        }
        d.io.mem.resp.valid.poke(presenting.nonEmpty.B)
        presenting.foreach { case (address, _, data) =>
          d.io.mem.resp.bits.addr.poke(address.U)
          d.io.mem.resp.bits.data.poke(data.U)
          d.io.mem.resp.bits.write.poke(false.B)
        }
        if (d.io.result.valid.peek().litToBoolean) {
          val value = d.io.result.bits.peek().litValue
          assert(value == expected(received), s"sample $received format or cache epoch mismatch")
          previousHeld.foreach(old => assert(old == value, "held output changed"))
          if (ready) previousHeld = None else { previousHeld = Some(value); held += 1 }
        }
        if (presenting.nonEmpty && d.io.mem.resp.ready.peek().litToBoolean) {
          pending.remove(pending.indexWhere(_._1 == presenting.get._1))
          presenting = None
          returned += 1
          if (returned == 4) warmAt = cycle
        }
        if (d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean) {
          val address = d.io.mem.req.bits.addr.peek().litValue.toLong
          val tap = ((address - 0x3000) / 4).toInt
          assert(tap >= 0 && tap < 4)
          pending += ((address, cycle + 4 + requests % 3, raw(tap, epoch)))
          requests += 1
          assert(pending.size <= 4, "physical read budget exceeded")
        }
        if (d.io.sample.valid.peek().litToBoolean && d.io.sample.ready.peek().litToBoolean) {
          accepted += cycle
          if (sent == 16) epoch = 1
          sent += 1
        }
        if (d.io.result.valid.peek().litToBoolean && ready) received += 1
        d.clock.step(); cycle += 1
      }
      assert(sent == 24 && received == 24 && pending.isEmpty && requests == 8)
      if (stallOutput) assert(held > 0)
      else {
        val warmed = accepted.take(16).filter(_ > warmAt)
        assert(warmAt >= 0 && warmed.size >= 10)
        warmed.sliding(2).foreach { pair =>
        assert(pair(1) - pair(0) == 1, "warmed prepared window retained a lookup bubble")
        }
      }
      d.io.sample.valid.poke(false.B); d.io.prepared.get.valid.poke(false.B)
      d.io.invalidateCache.get.poke(false.B); d.io.mem.resp.valid.poke(false.B)
      d.clock.step(3); d.io.result.valid.expect(false.B)
      println(s"PREPARED_CACHE_ADMISSION cycles=$cycle requests=$requests held=$held")
    }
  }

  it should "accept warmed samples every cycle across packed formats and cache invalidation" in {
    exercise(stallOutput = false)
  }
  it should "retain a one-cycle invalidation pulse while older samples and the consumer stall" in {
    exercise(stallOutput = true, pulseOnly = true)
  }
}
