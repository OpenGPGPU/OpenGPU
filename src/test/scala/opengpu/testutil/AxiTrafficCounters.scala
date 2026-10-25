package opengpu.testutil

/** Accepted AXI transfers only. Bus bytes count occupied full-width data beats;
  * enabled write bytes count WSTRB bits. Neither is a DRAM timing/power model.
  * The stimulus calls these methods from handshakes it already observes.
  */
final class AxiTrafficCounters {
  case class Snapshot(readBursts: Long, scheduledReadBeats: Long, readBeats: Long,
    completedReadBursts: Long, readBusBytes: Long, writeBursts: Long,
    writeBeats: Long, completedWriteDataBursts: Long, writeBusBytes: Long,
    enabledWriteBytes: Long, writeReplies: Long)
  private var readBursts = 0L
  private var scheduledReadBeats = 0L
  private var readBeats = 0L
  private var completedReadBursts = 0L
  private var readBusBytes = 0L
  private var writeBursts = 0L
  private var writeBeats = 0L
  private var completedWriteDataBursts = 0L
  private var writeBusBytes = 0L
  private var enabledWriteBytes = 0L
  private var writeReplies = 0L
  private def width(bytes: Int): Unit =
    require(bytes > 0 && bytes <= 64 && (bytes & (bytes - 1)) == 0)

  def readAddress(beats: Int): Unit = {
    require(beats >= 1 && beats <= 256)
    readBursts += 1; scheduledReadBeats += beats
  }
  def readBeat(busBytes: Int, last: Boolean): Unit = {
    width(busBytes)
    readBeats += 1; readBusBytes += busBytes
    if (last) completedReadBursts += 1
  }
  def writeAddress(): Unit = { writeBursts += 1 }
  def writeBeat(busBytes: Int, mask: BigInt, last: Boolean): Unit = {
    width(busBytes)
    require(mask >= 0 && mask.bitLength <= busBytes)
    writeBeats += 1; writeBusBytes += busBytes; enabledWriteBytes += mask.bitCount
    if (last) completedWriteDataBursts += 1
  }
  def writeReply(): Unit = { writeReplies += 1 }
  def snapshot: Snapshot = Snapshot(readBursts, scheduledReadBeats, readBeats,
    completedReadBursts, readBusBytes, writeBursts, writeBeats,
    completedWriteDataBursts, writeBusBytes, enabledWriteBytes, writeReplies)
  def assertDrained(): Unit = {
    assert(scheduledReadBeats == readBeats && readBursts == completedReadBursts,
      s"read traffic is not drained: $snapshot")
    assert(writeBursts == completedWriteDataBursts && writeBursts == writeReplies,
      s"write traffic is not drained: $snapshot")
  }
}
