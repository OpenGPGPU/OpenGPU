package opengpu.testutil

import chisel3._
import chisel3.simulator.Exceptions
import org.scalatest.flatspec.AnyFlatSpec
import GpuSim._

class GpuSimHierarchyLeaf extends Module {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val fail = Input(Bool())
    val count = Output(UInt(8.W))
  })
  private val count = RegInit(0.U(8.W))
  when(io.en) { count := count + 1.U }
  when(io.fail) { assert(false.B, "GPU_SIM_HIERARCHY_ASSERTION_SENTINEL") }
  io.count := count
}

class GpuSimHierarchyHarness extends Module {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val fail = Input(Bool())
    val a = Output(UInt(8.W))
    val b = Output(UInt(8.W))
  })
  private val a = Module(new GpuSimHierarchyLeaf)
  private val b = Module(new GpuSimHierarchyLeaf)
  a.io.en := io.en
  b.io.en := true.B
  a.io.fail := io.fail
  b.io.fail := false.B
  io.a := a.io.count
  io.b := b.io.count
}

class GpuSimHierarchySpec extends AnyFlatSpec {
  private def run(fail: Boolean): Unit = {
    simulate(new GpuSimHierarchyHarness) { d =>
      d.io.en.poke(false.B)
      d.io.fail.poke(false.B)
      d.reset.poke(true.B)
      d.clock.step()
      d.reset.poke(false.B)
      d.clock.step(3)
      d.io.a.expect(0.U)
      d.io.b.expect(3.U)
      d.io.en.poke(true.B)
      d.clock.step(2)
      d.io.a.expect(2.U)
      d.io.b.expect(5.U)
      if (fail) {
        d.io.fail.poke(true.B)
        d.clock.step()
      }
    }
  }

  it should "keep separate hierarchy instances independent on fresh and cached runs" in {
    run(false)
    run(false)
  }

  it should "propagate hardware assertions from a cached model" in {
    val error = intercept[Exceptions.AssertionFailed] { run(true) }
    assert(error.simulatorOutput.contains("GPU_SIM_HIERARCHY_ASSERTION_SENTINEL"))
  }
}
