package opengpu.testutil

import chisel3._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import GpuSim._
import scala.collection.immutable.ListMap

/** Guards the shared simulation harness: a model restored from the cache has to
  * behave exactly like a freshly compiled one, and a failing expectation has to
  * keep failing.
  */
class GpuSimSpec extends AnyFlatSpec {
  behavior of "GpuSim"

  private val longPortPrefix = "retained_ordinary_submission_completion_frame_source_bins_producer_restart_primitive_vertices_coordinate_conversion_full_precision_result_"
  private class LongPortIO extends Record {
    override val elements: ListMap[String, Data] = ListMap(
      (longPortPrefix + "a") -> Input(UInt(130.W)),
      (longPortPrefix + "b") -> Input(UInt(37.W)),
      (longPortPrefix + "out") -> Output(UInt(130.W)))
    def a: UInt = elements(longPortPrefix + "a").asInstanceOf[UInt]
    def b: UInt = elements(longPortPrefix + "b").asInstanceOf[UInt]
    def out: UInt = elements(longPortPrefix + "out").asInstanceOf[UInt]
  }
  private class LongPortSentinel extends Module {
    val io = IO(new LongPortIO)
    io.out := RegNext(io.a ^ io.b.pad(130), 0.U(130.W))
  }

  it should "preserve distinct long DPI port names and multiword values" in {
    assert(longPortPrefix.length > 128)
    simulate(new LongPortSentinel) { dut =>
      dut.io.a.poke(0.U); dut.io.b.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.out.expect(0.U)
      for ((a, b) <- Seq(
        ((BigInt(1) << 129) | (BigInt(1) << 64) | 0x12345678, BigInt(1) << 36),
        ((BigInt(1) << 130) - 1, (BigInt(1) << 37) - 1),
        (BigInt(0), BigInt(7)))) {
        dut.io.a.poke(a.U); dut.io.b.poke(b.U); dut.clock.step()
        dut.io.out.expect((a ^ b).U)
      }
    }
  }

  private class Counter(width: Int) extends Module {
    val io = IO(new Bundle {
      val en = Input(Bool())
      val out = Output(UInt(width.W))
    })
    val reg = RegInit(0.U(width.W))
    when(io.en) { reg := reg + 1.U }
    io.out := reg
  }

  private def start(dut: Counter): Unit = {
    dut.reset.poke(true.B)
    dut.io.en.poke(false.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.io.out.expect(0.U)
  }

  it should "count up from a cached model" in {
    simulate(new Counter(8)) { dut =>
      start(dut)
      dut.io.en.poke(true.B)
      for (cycle <- 1 to 4) {
        dut.clock.step()
        dut.io.out.expect(cycle.U)
      }
    }
  }

  it should "report a failing expectation" in {
    intercept[chisel3.simulator.FailedExpectationException[_]] {
      simulate(new Counter(8)) { dut =>
        start(dut)
        dut.io.out.expect(1.U)
      }
    }
  }

  it should "keep separate runs of one model independent" in {
    def run(): BigInt = {
      var out = BigInt(0)
      simulate(new Counter(8)) { dut =>
        start(dut)
        dut.io.en.poke(true.B)
        for (_ <- 1 to 3) { dut.clock.step() }
        out = dut.io.out.peek().litValue
      }
      out
    }
    assert(run() == 3 && run() == 3, s"counter restarted wrong: ${run()}, ${run()}")
  }

  it should "keep an RTL width change from restoring the preceding narrower counter" in {
    simulate(new Counter(9)) { dut =>
      start(dut)
      dut.io.en.poke(true.B)
      dut.clock.step(257)
      dut.io.out.expect(257.U)
    }
  }

  private class AssertionSentinel extends Module {
    val io = IO(new Bundle {
      val trip = Input(Bool())
      val alive = Output(UInt(8.W))
    })
    io.alive := 42.U
    chisel3.assert(!io.trip, "GpuSim compiler-cache assertion sentinel")
  }

  it should "retain RTL assertions when the same model is restored from compiler objects" in {
    def run(trip: Boolean): Unit = simulate(new AssertionSentinel) { dut =>
      dut.io.trip.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.alive.expect(42.U)
      dut.io.trip.poke(trip.B)
      dut.clock.step()
    }
    run(false)
    intercept[chisel3.simulator.Exceptions.AssertionFailed] { run(true) }
  }
}
