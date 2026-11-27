package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TileBinHostCommandPortSpec extends AnyFlatSpec {
  private def initialize(h: TileBinHostCommandPort): Unit = {
    h.io.command.valid.poke(false.B); h.io.command.bits.poke(0.U.asTypeOf(h.io.command.bits))
    h.io.otherCommand.ready.poke(true.B); h.io.submit.ready.poke(false.B)
    h.io.renderCompletion.valid.poke(false.B); h.io.renderCompletion.bits.poke(0.U.asTypeOf(h.io.renderCompletion.bits))
    h.io.otherCompletion.valid.poke(false.B); h.io.otherCompletion.bits.poke(0.U.asTypeOf(h.io.otherCompletion.bits))
    h.io.completion.ready.poke(false.B); h.io.dependencyIdle.poke(true.B)
    h.io.renderIdle.poke(true.B); h.io.resetActive.poke(false.B)
    h.reset.poke(true.B); h.clock.step(); h.reset.poke(false.B)
  }
  it should "decode the GBJ1 envelope without reinterpreting old sizes or unsupported dependency flags" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes = 4, warps = 2))) { h =>
      initialize(h)
      h.io.command.valid.poke(true.B); val c = h.io.command.bits
      c.commandId.poke(37.U); c.opcode.poke(7.U); c.sourceAddress.poke(0xfffffe00L.U)
      c.destinationAddress.poke(0.U); c.pattern.poke(1.U); c.bytes.poke(512.U)
      h.io.submit.valid.expect(true.B); h.io.submit.bits.commandId.expect(37.U)
      h.io.submit.bits.limit.expect(0x100000000L.U); h.io.submit.bits.envelopeValid.expect(true.B)
      h.io.renderPending.expect(true.B); h.io.command.ready.expect(false.B)
      val bits = h.io.submit.bits.peek().litValue; h.clock.step(3)
      assert(h.io.submit.bits.peek().litValue == bits)
      for (bad <- 0 until 5) {
        c.bytes.poke((if (bad == 0) 256 else 512).U); c.pattern.poke((if (bad == 1) 2 else 1).U)
        c.waitForDma.poke((bad == 2).B); c.waitForEvent.poke((bad == 3).B); c.signalEvent.poke((bad == 4).B)
        h.io.submit.bits.envelopeValid.expect(false.B)
      }
    }
  }
  it should "dispatch GNZ2 and GNZ3 explicitly and hold fail-closed 46-word geometry reports" in {
    simulate(
      new TileBinHostCommandPort(
        GpuConfig(lanes = 16, warps = 8),
        nativeCompletionInfo = true,
        transformFeedback = true,
        tessellationAvailable = true,
        jointTessellationAvailable = true,
        jointTessellationGeometryAvailable = true
      )
    ) { h =>
      initialize(h)
      h.io.jointTessellationSubmit.get.ready.poke(true.B)
      h.io.jointTessellationGeometrySubmit.get.ready.poke(true.B)
      h.io.tessellationSubmit.get.ready.poke(true.B)
      h.io.command.valid.poke(true.B)
      val c = h.io.command.bits
      c.commandId.poke(51.U); c.opcode.poke(15.U); c.sourceAddress.poke(0x4000.U)
      c.destinationAddress.poke(0x8000.U); c.pattern.poke(0x474a0204.U); c.bytes.poke(512.U)
      c.widthBytes.poke(0x10000.U); c.height.poke(7.U); c.sourceStride.poke(8.U)
      c.destinationStride.poke(9.U); c.feedbackCapture.poke(false.B)
      h.io.jointTessellationGeometrySubmit.get.valid.expect(true.B)
      h.io.jointTessellationSubmit.get.valid.expect(false.B)
      h.io.tessellationSubmit.get.valid.expect(false.B)
      h.io.jointTessellationGeometrySubmit.get.bits.domain.expect(2.U)
      h.io.jointTessellationGeometrySubmit.get.bits.owner.base.expect(0x8000.U)
      h.io.jointTessellationGeometrySubmit.get.bits.owner.limit.expect(0x10000.U)
      h.io.jointTessellationGeometrySubmit.get.bits.identity.context.expect(7.U)
      h.clock.step(); h.io.command.valid.poke(false.B)

      val reply = h.io.jointTessellationGeometryCompletion.get
      reply.bits.base.commandId.poke(51.U); reply.bits.base.identity.context.poke(7.U)
      reply.bits.base.identity.pass.poke(8.U); reply.bits.base.identity.generation.poke(9.U)
      reply.bits.base.status.poke(0.U); reply.bits.base.readBytes.poke(123.U)
      reply.bits.geometryReportValid.poke(true.B); reply.valid.poke(true.B)
      h.io.completion.ready.poke(true.B); h.clock.step(); reply.valid.poke(false.B)
      h.io.completion.ready.poke(false.B)
      h.io.completion.valid.expect(true.B); h.io.completion.bits.commandId.expect(51.U)
      h.io.completion.bits.status.expect(0.U); h.io.completion.bits.bytesProcessed.expect(123.U)
      val details = h.io.completionDetails.get
      details(0).expect(0x474e5a03.U); details(1).expect(0x80.U)
      details(3).expect(7.U); details(4).expect(8.U); details(5).expect(9.U)
      for (index <- 16 until 46) details(index).expect(0.U)
      val held = details.map(_.peek().litValue)
      h.clock.step(4); assert(details.map(_.peek().litValue) == held)
      h.io.completion.ready.poke(true.B); h.clock.step(); h.io.completion.ready.poke(false.B)

      c.commandId.poke(52.U); c.pattern.poke(0x474a0201.U); c.height.poke(17.U)
      c.sourceStride.poke(18.U); c.destinationStride.poke(19.U); c.feedbackCapture.poke(true.B)
      h.io.command.valid.poke(true.B)
      h.io.jointTessellationGeometrySubmit.get.bits.view.limit.expect(0.U)
      h.clock.step(); h.io.command.valid.poke(false.B)
      reply.bits.base.commandId.poke(99.U); reply.bits.base.identity.context.poke(99.U)
      reply.bits.base.status.poke(0.U); reply.bits.geometryReportValid.poke(true.B)
      reply.valid.poke(true.B); h.io.completion.ready.poke(true.B); h.clock.step()
      reply.valid.poke(false.B); h.io.completion.ready.poke(false.B)
      h.io.completion.bits.commandId.expect(52.U); h.io.completion.bits.status.expect(3.U)
      details(0).expect(0x474e5a03.U); details(1).expect(59.U)
      details(3).expect(17.U); details(4).expect(18.U); details(5).expect(19.U)
      h.io.completion.ready.poke(true.B); h.clock.step(); h.io.completion.ready.poke(false.B)

      c.commandId.poke(53.U); c.pattern.poke(0x474a0100.U); c.bytes.poke(256.U)
      c.widthBytes.poke(0.U); c.height.poke(27.U); c.sourceStride.poke(28.U)
      c.destinationStride.poke(29.U); c.rasterDiscard.poke(true.B); c.feedbackCapture.poke(true.B)
      h.io.command.valid.poke(true.B)
      h.io.jointTessellationSubmit.get.valid.expect(true.B)
      h.io.jointTessellationGeometrySubmit.get.valid.expect(false.B)
      h.clock.step(); h.io.command.valid.poke(false.B)
      val oldReply = h.io.jointTessellationCompletion.get
      oldReply.bits.commandId.poke(53.U); oldReply.bits.identity.context.poke(27.U)
      oldReply.bits.identity.pass.poke(28.U); oldReply.bits.identity.generation.poke(29.U)
      oldReply.bits.status.poke(0.U); oldReply.valid.poke(true.B)
      h.io.completion.ready.poke(true.B); h.clock.step(); oldReply.valid.poke(false.B)
      h.io.completion.ready.poke(false.B)
      details(0).expect(0x474e5a02.U)
      for (index <- 16 until 46) details(index).expect(0.U)
      h.io.completion.ready.poke(true.B); h.clock.step(); h.io.completion.ready.poke(false.B)
      h.io.drained.expect(true.B)
    }
  }
  it should "serialize memory commands with rendering while allowing compute in the pinned VM" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes = 4, warps = 2))) { h =>
      initialize(h); h.io.command.valid.poke(true.B); h.io.command.bits.opcode.poke(7.U)
      h.io.submit.ready.poke(true.B); h.io.dependencyIdle.poke(false.B)
      h.io.submit.valid.expect(false.B); h.io.command.ready.expect(false.B)
      h.io.dependencyIdle.poke(true.B); h.io.command.ready.expect(true.B)
      h.io.renderIdle.poke(false.B)
      h.io.command.bits.opcode.poke(1.U); h.io.otherCommand.valid.expect(false.B); h.io.command.ready.expect(false.B)
      h.io.command.bits.opcode.poke(0.U); h.io.otherCommand.valid.expect(true.B); h.io.command.ready.expect(true.B)
      h.io.resetActive.poke(true.B); h.io.cancel.expect(true.B)
      h.io.otherCommand.valid.expect(false.B); h.io.command.ready.expect(false.B)
      h.io.command.bits.opcode.poke(7.U); h.io.submit.valid.expect(false.B)
    }
  }
  it should "retain stable unified completions under backpressure and drain both sources during reset" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes = 4, warps = 2))) { h =>
      initialize(h)
      h.io.renderCompletion.valid.poke(true.B); h.io.renderCompletion.bits.commandId.poke(37.U)
      h.io.renderCompletion.bits.status.poke(10.U); h.io.renderCompletion.bits.appendReadBytes.poke(576.U)
      h.io.otherCompletion.valid.poke(true.B); h.io.otherCompletion.bits.commandId.poke(38.U)
      h.io.otherCompletion.bits.opcode.poke(0.U); h.io.otherCompletion.bits.success.poke(true.B)
      var root = true; var other = true
      for (_ <- 0 until 5) {
        h.io.renderCompletion.valid.poke(root.B); h.io.otherCompletion.valid.poke(other.B)
        val r = root && h.io.renderCompletion.ready.peek().litToBoolean
        val o = other && h.io.otherCompletion.ready.peek().litToBoolean
        h.clock.step(); if (r) root = false; if (o) other = false
      }
      assert(!root && !other); h.io.renderCompletion.valid.poke(false.B); h.io.otherCompletion.valid.poke(false.B)
      h.io.drained.expect(false.B); h.io.completion.valid.expect(true.B)
      val held = h.io.completion.bits.peek().litValue
      h.io.resetActive.poke(true.B); h.clock.step(5); assert(h.io.completion.bits.peek().litValue == held)
      h.io.completion.ready.poke(true.B); val seen = mutable.Set.empty[Int]
      for (_ <- 0 until 3) {
        if (h.io.completion.valid.peek().litToBoolean) {
          val b = h.io.completion.bits; val id = b.commandId.peek().litValue.toInt; seen += id
          if (id == 37) {
            b.status.expect(10.U); b.success.expect(false.B); b.bytesProcessed.expect(576.U); b.opcode.expect(7.U)
          } else b.success.expect(true.B)
        }
        h.clock.step()
      }
      assert(seen == Set(37, 38)); h.io.drained.expect(true.B)
    }
  }
}
