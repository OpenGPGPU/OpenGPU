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
    simulate(new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2))) { h=>
      initialize(h)
      h.io.command.valid.poke(true.B); val c=h.io.command.bits
      c.commandId.poke(37.U); c.opcode.poke(7.U); c.sourceAddress.poke(0xfffffe00L.U)
      c.destinationAddress.poke(0.U); c.pattern.poke(1.U); c.bytes.poke(512.U)
      h.io.submit.valid.expect(true.B); h.io.submit.bits.commandId.expect(37.U)
      h.io.submit.bits.limit.expect(0x100000000L.U); h.io.submit.bits.envelopeValid.expect(true.B)
      h.io.renderPending.expect(true.B); h.io.command.ready.expect(false.B)
      val bits=h.io.submit.bits.peek().litValue; h.clock.step(3)
      assert(h.io.submit.bits.peek().litValue==bits)
      for (bad <- 0 until 5) {
        c.bytes.poke((if (bad==0) 256 else 512).U); c.pattern.poke((if (bad==1) 2 else 1).U)
        c.waitForDma.poke((bad==2).B); c.waitForEvent.poke((bad==3).B); c.signalEvent.poke((bad==4).B)
        h.io.submit.bits.envelopeValid.expect(false.B)
      }
    }
  }
  it should "serialize memory commands with rendering while allowing compute in the pinned VM" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2))) { h=>
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
    simulate(new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2))) { h=>
      initialize(h)
      h.io.renderCompletion.valid.poke(true.B); h.io.renderCompletion.bits.commandId.poke(37.U)
      h.io.renderCompletion.bits.status.poke(10.U); h.io.renderCompletion.bits.appendReadBytes.poke(576.U)
      h.io.otherCompletion.valid.poke(true.B); h.io.otherCompletion.bits.commandId.poke(38.U)
      h.io.otherCompletion.bits.opcode.poke(0.U); h.io.otherCompletion.bits.success.poke(true.B)
      var root=true; var other=true
      for (_ <- 0 until 5) {
        h.io.renderCompletion.valid.poke(root.B); h.io.otherCompletion.valid.poke(other.B)
        val r=root && h.io.renderCompletion.ready.peek().litToBoolean
        val o=other && h.io.otherCompletion.ready.peek().litToBoolean
        h.clock.step(); if(r) root=false; if(o) other=false
      }
      assert(!root && !other); h.io.renderCompletion.valid.poke(false.B); h.io.otherCompletion.valid.poke(false.B)
      h.io.drained.expect(false.B); h.io.completion.valid.expect(true.B)
      val held=h.io.completion.bits.peek().litValue
      h.io.resetActive.poke(true.B); h.clock.step(5); assert(h.io.completion.bits.peek().litValue==held)
      h.io.completion.ready.poke(true.B); val seen=mutable.Set.empty[Int]
      for (_ <- 0 until 3) {
        if(h.io.completion.valid.peek().litToBoolean) {
          val b=h.io.completion.bits; val id=b.commandId.peek().litValue.toInt; seen+=id
          if(id==37) { b.status.expect(10.U); b.success.expect(false.B); b.bytesProcessed.expect(576.U); b.opcode.expect(7.U) }
          else b.success.expect(true.B)
        }
        h.clock.step()
      }
      assert(seen==Set(37,38)); h.io.drained.expect(true.B)
    }
  }
}
