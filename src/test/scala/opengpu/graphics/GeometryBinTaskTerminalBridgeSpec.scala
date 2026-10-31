package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.system.GeometryBinTaskTerminalBridge
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryBinTaskTerminalBridgeSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "drain a held offer after pass termination and retain its local cancellation reply" in {
    simulate(new GeometryBinTaskTerminalBridge(GpuConfig(lanes=4,warps=2),VertexShaderStageConfig(VertexInputFetchConfig(1,1),2),4)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.task.bits.token.poke(91.U);d.io.task.valid.poke(true.B);d.io.targetTask.ready.poke(false.B)
      for(_<-0 until 3){d.io.targetTask.valid.expect(true.B);d.io.targetTask.bits.token.expect(91.U);d.io.task.ready.expect(false.B);d.clock.step()}
      d.io.terminal.poke(true.B);d.io.targetTask.valid.expect(false.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      d.io.terminal.poke(false.B);d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(4.U)
      for(_<-0 until 3){d.clock.step();d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(4.U);d.io.task.ready.expect(false.B)}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
      d.io.task.valid.poke(true.B);d.io.targetTask.ready.poke(true.B);d.io.targetTask.valid.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      d.io.targetCompletion.bits.status.poke(3.U);d.io.targetCompletion.valid.poke(true.B);d.io.completion.valid.expect(true.B)
      d.io.completion.bits.status.expect(3.U);d.io.completion.ready.poke(true.B);d.clock.step();d.io.targetCompletion.valid.poke(false.B)
    }
  }
}
