package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VertexShaderClearLinesSpec extends AnyFlatSpec {
  behavior of "vertex output clear-line endpoints"

  it should "match full integer byte ranges across line carries and the exclusive 4 GiB end" in {
    simulate(new VertexShaderClearLines) { d =>
      val limit = BigInt(1) << 32
      var cases = 0
      def check(base: BigInt, start: BigInt, end: BigInt): Unit = {
        require(base % 64 == 0 && start >= 0 && start < end && base + end <= limit)
        d.io.base.poke(base.U); d.io.startBytes.poke(start.U); d.io.endBytes.poke(end.U)
        d.clock.step()
        d.io.first.expect(((base + start) & ~BigInt(63)).U)
        d.io.last.expect(((base + end - 1) & ~BigInt(63)).U)
        cases += 1
      }
      for (bit <- 7 to 32; end <- 1 to 193) {
        val base = (BigInt(1) << bit) - 64
        if (base + end <= limit) check(base, end / 2, end)
      }
      for (end <- Seq(608, 15872, 45056, 69120, 138720); low <- 0 until 64) {
        val base = limit - ((end + 63) / 64) * 64
        check(base, 64 + low, end)
      }
      for (bit <- 7 to 31; delta <- -1 to 1) {
        val end = (BigInt(1) << bit) + delta
        check(0, 0, end); check(0, end - 1, end)
      }
      check(0, limit - 2, limit - 1)
      val random = new scala.util.Random(0x434c4541L)
      for (_ <- 0 until 2000) {
        val base = BigInt(32, random) & ~BigInt(63)
        val maximum = (limit - base).min(BigInt(200000)).toInt
        val end = 1 + random.nextInt(maximum)
        check(base, random.nextInt(end), end)
      }
      info(s"CLEAR_LINES integerReferenceCases=$cases alignedBases=true allByteOffsets=true exclusive4GiB=true")
    }
  }
}
