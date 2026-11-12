package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class ClipDepthStageSpec extends AnyFlatSpec {
  behavior of "ClipDepthStage"

  it should "snapshot clipped vertices and round Z over W into the selected D24 range" in {
    val random = new scala.util.Random(0x71c1d3L)
    val edges = Vector((0L, 65536L), (-65536L, 65536L), (65536L, 65536L),
      (-32768L, 65536L), (32768L, 65536L), (-65L, 64L), (65L, 64L),
      (Int.MaxValue.toLong, Int.MaxValue.toLong), (Int.MinValue.toLong, Int.MaxValue.toLong))
    val samples = edges ++ Vector.fill(45) {
      val w = 64L + (random.nextInt(Int.MaxValue - 64).toLong)
      val z = (random.nextLong().abs % (2 * w + 1)) - w
      (z, w)
    }
    val triangles = samples.grouped(3).map(_.toVector).toVector
    val ranges = Vector((0, 0xffffff), (0x400000, 0xbfffff), (0xffffff, 0),
      (0x200000, 0x200000), (1, 0xfffffe), (0, 0), (0xffffff, 0xffffff))
    val jobs = triangles.flatMap(triangle => ranges.map(range => (triangle, range))) ++
      Vector.fill(30)((triangles(random.nextInt(triangles.size)),
        (random.nextInt(1 << 24), random.nextInt(1 << 24))))
    def expected(z: Long, w: Long, near: Int, far: Int): BigInt = {
      val bounded = (BigInt(z) max -BigInt(w)) min BigInt(w)
      ((BigInt(w) - bounded) * near + (bounded + w) * far + w) / (2 * BigInt(w))
    }
    simulate(new ClipDepthStage) { dut =>
      dut.io.start.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      jobs.foreach { case (triangle, (near, far)) =>
        for (((z, w), lane) <- triangle.zipWithIndex) {
          dut.io.clip(lane).x.poke(0.S); dut.io.clip(lane).y.poke(0.S)
          dut.io.clip(lane).z.poke(z.S(32.W)); dut.io.clip(lane).w.poke(w.S(32.W))
        }
        dut.io.near.poke(near.U); dut.io.far.poke(far.U)
        dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
        dut.io.done.expect(false.B)
        dut.io.near.poke((0xffffff - near).U); dut.io.far.poke((0xffffff - far).U)
        for (lane <- 0 until 3) {
          dut.io.clip(lane).z.poke(0.S); dut.io.clip(lane).w.poke(65536.S)
        }
        var cycles = 0
        while (dut.io.busy.peek().litToBoolean && cycles < 150) {
          dut.io.start.poke((cycles % 11 == 0).B)
          dut.clock.step(); cycles += 1
        }
        dut.io.start.poke(false.B)
        assert(cycles < 150)
        dut.io.done.expect(true.B)
        for (((z, w), lane) <- triangle.zipWithIndex)
          dut.io.depth(lane).expect(expected(z, w, near, far).U)
        dut.clock.step(3); dut.io.done.expect(true.B)
        for (((z, w), lane) <- triangle.zipWithIndex)
          dut.io.depth(lane).expect(expected(z, w, near, far).U)
      }
    }
  }
}
