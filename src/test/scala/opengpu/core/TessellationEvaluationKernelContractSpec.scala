package opengpu.core

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationEvaluationKernelContractSpec extends AnyFlatSpec {
  it should "guard accepted bound TES launches while leaving unbound and unaccepted work alone" in {
    simulate(new TessellationEvaluationKernelContract) { d =>
      d.io.accepted.poke(false.B); d.io.bound.poke(false.B)
      d.io.geometry.poke(false.B); d.io.single.poke(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (
        (accepted, bound, geometry, single) <- Seq(
          (false, true, false, false),
          (true, false, false, false),
          (true, true, true, true),
          (false, false, false, false)
        )
      ) {
        d.io.accepted.poke(accepted.B); d.io.bound.poke(bound.B)
        d.io.geometry.poke(geometry.B); d.io.single.poke(single.B); d.clock.step()
      }
    }
  }
  it should "retain actual RTL assertions for missing geometry and multiple invocations" in {
    for ((geometry, single) <- Seq((false, true), (true, false))) {
      intercept[chisel3.simulator.Exceptions.AssertionFailed] {
        simulate(new TessellationEvaluationKernelContract) { d =>
          d.io.accepted.poke(false.B); d.io.bound.poke(true.B)
          d.io.geometry.poke(geometry.B); d.io.single.poke(single.B)
          d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
          d.io.accepted.poke(true.B); d.clock.step()
        }
      }
    }
  }
}
