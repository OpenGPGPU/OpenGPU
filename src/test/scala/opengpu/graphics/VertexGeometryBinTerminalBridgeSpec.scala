package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VertexGeometryBinTerminalBridgeSpec extends AnyFlatSpec {
  import WindowRecordTest.zero
  it should "drain the vertex source when a held geometry draw cannot enter a terminal pass" in {
    simulate(new VertexGeometryBinTerminalBridge(GpuConfig(lanes=4,warps=2),VertexShaderStageConfig(VertexInputFetchConfig(1,1),2),4)){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      d.io.draw.bits.geometry.geometry.primitive.input.primitive.identity.context.poke(7.U);d.io.draw.valid.poke(true.B)
      d.io.targetDraw.ready.poke(false.B);for(_<-0 until 2){d.io.targetDraw.valid.expect(true.B);d.io.draw.ready.expect(false.B);d.clock.step()}
      d.io.terminal.poke(true.B);d.io.targetDraw.valid.expect(false.B);d.io.draw.ready.expect(true.B);d.clock.step();d.io.draw.valid.poke(false.B)
      d.io.primitive.valid.poke(true.B);d.io.inputEnd.valid.poke(true.B);d.io.primitive.ready.expect(true.B);d.io.inputEnd.ready.expect(false.B)
      d.clock.step();d.io.primitive.valid.poke(false.B);d.io.inputEnd.bits.status.poke(4.U);d.io.inputEnd.ready.expect(true.B);d.clock.step()
      d.io.inputEnd.valid.poke(false.B);d.io.completion.valid.expect(true.B);d.io.completion.bits.status.expect(4.U)
      d.io.completion.bits.identity.context.expect(7.U);d.io.completion.bits.inputPrimitives.expect(1.U)
      for(_<-0 until 3){d.clock.step();d.io.completion.valid.expect(true.B);d.io.draw.ready.expect(false.B)}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B);d.io.busy.expect(false.B)
      d.io.terminal.poke(false.B);d.io.draw.valid.poke(true.B);d.io.targetDraw.ready.poke(true.B)
      d.io.targetDraw.valid.expect(true.B);d.io.draw.ready.expect(true.B);d.clock.step();d.io.draw.valid.poke(false.B)
      d.io.targetCompletion.bits.status.poke(3.U);d.io.targetCompletion.valid.poke(true.B);d.io.completion.bits.status.expect(3.U)
      d.io.completion.ready.poke(true.B);d.clock.step()
    }
  }
}
