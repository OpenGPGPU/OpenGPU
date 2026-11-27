package opengpu.system

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.memory.CacheLineInvalidate
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationJointGeometryRangeInvalidationGateSpec extends AnyFlatSpec {
  private val config = GpuConfig(lanes = 16, warps = 8)
  private val allLines = BigInt("ffffffff", 16)

  it should "invalidate the owning L1 before beginning a stable typed range" in {
    simulate(new TessellationJointGeometryRangeInvalidationGate(config, UInt(32.W))) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.cancel.poke(false.B); d.io.peerInvalidate.ready.poke(false.B)
      d.io.peerInvalidateDone.valid.poke(false.B); d.io.externalInvalidate.valid.poke(false.B)
      d.io.externalInvalidateDone.ready.poke(true.B); d.io.begin.ready.poke(false.B)
      d.io.request.valid.poke(true.B); d.io.request.bits.poke(0x1234.U)
      d.io.request.ready.expect(true.B); d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.peerInvalidate.valid.expect(true.B)
      d.io.peerInvalidate.bits.lineAddress.expect(allLines.U)
      d.io.begin.valid.expect(false.B); d.io.drained.expect(false.B)
      d.io.peerInvalidate.ready.poke(true.B); d.clock.step(); d.io.peerInvalidate.ready.poke(false.B)
      for (_ <- 0 until 4) { d.clock.step(); d.io.begin.valid.expect(false.B); d.io.drained.expect(false.B) }
      d.io.peerInvalidateDone.valid.poke(true.B)
      d.io.peerInvalidateDone.bits.lineAddress.poke(allLines.U)
      d.io.peerInvalidateDone.ready.expect(true.B); d.io.begin.valid.expect(false.B)
      d.clock.step(); d.io.peerInvalidateDone.valid.poke(false.B)
      d.io.begin.valid.expect(true.B); d.io.begin.bits.expect(0x1234.U)
      d.io.cancelChild.expect(false.B); d.io.drained.expect(false.B)
      d.io.begin.ready.poke(true.B); d.clock.step(); d.io.begin.ready.poke(false.B)
      d.io.drained.expect(true.B); d.io.busy.expect(false.B)
    }
  }

  it should "withdraw an unaccepted range invalidate on cancel and still begin the cancelled range" in {
    simulate(new TessellationJointGeometryRangeInvalidationGate(config, UInt(32.W))) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.cancel.poke(false.B); d.io.peerInvalidate.ready.poke(false.B)
      d.io.peerInvalidateDone.valid.poke(false.B); d.io.externalInvalidate.valid.poke(false.B)
      d.io.externalInvalidateDone.ready.poke(true.B); d.io.begin.ready.poke(true.B)
      d.io.request.valid.poke(true.B); d.io.request.bits.poke(0x2345.U)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.peerInvalidate.valid.expect(true.B); d.io.cancel.poke(true.B)
      d.clock.step(); d.io.peerInvalidate.valid.expect(false.B)
      d.io.begin.valid.expect(true.B); d.io.begin.bits.expect(0x2345.U)
      d.clock.step(); d.io.begin.ready.poke(false.B); d.io.cancel.poke(false.B)
      d.io.cancelChild.expect(true.B); d.io.drained.expect(true.B)
    }
  }

  it should "drain an accepted invalidate before begin and arbitrate the external peer" in {
    simulate(new TessellationJointGeometryRangeInvalidationGate(config, UInt(32.W))) { d =>
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.cancel.poke(false.B); d.io.peerInvalidate.ready.poke(true.B)
      d.io.peerInvalidateDone.valid.poke(false.B); d.io.externalInvalidate.valid.poke(false.B)
      d.io.externalInvalidateDone.ready.poke(true.B); d.io.begin.ready.poke(true.B)
      d.io.request.valid.poke(true.B); d.io.request.bits.poke(0x3456.U)
      d.clock.step(); d.io.request.valid.poke(false.B)
      d.io.peerInvalidate.ready.poke(true.B); d.clock.step(); d.io.peerInvalidate.ready.poke(false.B)
      d.io.externalInvalidate.valid.poke(true.B); d.io.externalInvalidate.bits.lineAddress.poke(0x8000.U)
      d.io.externalInvalidate.ready.expect(false.B); d.io.cancel.poke(true.B)
      for (_ <- 0 until 4) { d.clock.step(); d.io.begin.valid.expect(false.B); d.io.drained.expect(false.B) }
      d.io.peerInvalidateDone.valid.poke(true.B)
      d.io.peerInvalidateDone.bits.lineAddress.poke(allLines.U)
      d.clock.step(); d.io.peerInvalidateDone.valid.poke(false.B)
      d.io.begin.valid.expect(true.B); d.io.externalInvalidateDone.valid.expect(false.B)
      d.clock.step(); d.io.begin.ready.poke(false.B)
      d.io.cancelChild.expect(true.B); d.io.externalInvalidate.ready.expect(true.B)
      d.io.peerInvalidate.valid.expect(false.B)
      d.io.peerInvalidate.ready.poke(true.B); d.clock.step()
      d.io.externalInvalidate.valid.poke(false.B); d.io.peerInvalidate.ready.poke(false.B)
      d.io.peerInvalidate.valid.expect(true.B); d.io.peerInvalidate.bits.lineAddress.expect(0x8000.U)
      d.io.peerInvalidate.ready.poke(true.B); d.clock.step(); d.io.peerInvalidate.ready.poke(false.B)
      d.io.peerInvalidateDone.valid.poke(true.B)
      d.io.peerInvalidateDone.bits.lineAddress.poke(0x8000.U)
      d.io.externalInvalidateDone.valid.expect(false.B); d.clock.step()
      d.io.peerInvalidateDone.valid.poke(false.B)
      d.io.externalInvalidateDone.valid.expect(true.B)
      d.io.externalInvalidateDone.bits.lineAddress.expect(0x8000.U)
      d.io.externalInvalidateDone.ready.poke(true.B); d.clock.step()
      d.io.externalInvalidateDone.ready.poke(false.B)
      d.io.cancel.poke(false.B); d.io.drained.expect(true.B)
    }
  }
}
