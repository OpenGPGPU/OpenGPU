package opengpu.testutil

import org.scalatest.flatspec.AnyFlatSpec

class AxiTrafficCountersSpec extends AnyFlatSpec {
  behavior of "accepted AXI traffic accounting"

  it should "separate occupied write bus bytes from enabled write bytes" in {
    val counts = new AxiTrafficCounters
    counts.writeAddress()
    counts.writeBeat(32, BigInt("80000001", 16), false)
    counts.writeBeat(32, 0, true)
    counts.writeReply()
    counts.assertDrained()
    val result = counts.snapshot
    assert(result.writeBursts == 1 && result.writeBeats == 2 && result.writeReplies == 1)
    assert(result.writeBusBytes == 64 && result.enabledWriteBytes == 2)
    assert(result.readBusBytes == 0)
  }

  it should "retain independent address, data and response counts when W precedes AW" in {
    val counts = new AxiTrafficCounters
    counts.writeBeat(8, 255, true)
    intercept[AssertionError] { counts.assertDrained() }
    counts.writeAddress()
    intercept[AssertionError] { counts.assertDrained() }
    counts.writeReply()
    counts.assertDrained()
    assert(counts.snapshot.enabledWriteBytes == 8)
  }

  it should "count each accepted read beat and require every scheduled burst to finish" in {
    val counts = new AxiTrafficCounters
    counts.readAddress(2); counts.readAddress(1)
    counts.readBeat(32, false)
    intercept[AssertionError] { counts.assertDrained() }
    counts.readBeat(32, true); counts.readBeat(32, true)
    counts.assertDrained()
    val result = counts.snapshot
    assert(result.readBursts == 2 && result.readBeats == 3 && result.scheduledReadBeats == 3)
    assert(result.readBusBytes == 96 && result.completedReadBursts == 2)
  }

  it should "keep immutable snapshots independent of subsequent accepted transfers" in {
    val counts = new AxiTrafficCounters
    val before = counts.snapshot
    counts.writeAddress(); counts.writeBeat(64, (BigInt(1) << 64) - 1, true); counts.writeReply()
    counts.assertDrained()
    assert(before.writeBusBytes == 0 && before.enabledWriteBytes == 0)
    assert(counts.snapshot.writeBusBytes == 64 && counts.snapshot.enabledWriteBytes == 64)
  }

  it should "reject malformed widths, burst lengths and masks without changing the count" in {
    val counts = new AxiTrafficCounters
    val before = counts.snapshot
    for (beats <- Seq(0, 257)) intercept[IllegalArgumentException] { counts.readAddress(beats) }
    for (bytes <- Seq(0, 3, 128)) {
      intercept[IllegalArgumentException] { counts.readBeat(bytes, true) }
      intercept[IllegalArgumentException] { counts.writeBeat(bytes, 0, true) }
    }
    for (mask <- Seq(BigInt(-1), BigInt(1) << 8))
      intercept[IllegalArgumentException] { counts.writeBeat(8, mask, true) }
    assert(counts.snapshot == before)
  }
}
