package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationHostCommandPortSpec extends AnyFlatSpec {
  it should "keep tessellation and existing completion sources distinct with all optional controls enabled" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2), nativeCompletionInfo=true,
        visibilityQueries=true, transformFeedback=true, primitiveQueries=true, tessellationAvailable=true)) { d =>
      val io=d.io
      io.command.valid.poke(false.B);io.command.bits.poke(0.U.asTypeOf(io.command.bits))
      io.otherCommand.ready.poke(true.B);io.submit.ready.poke(true.B);io.tessellationSubmit.get.ready.poke(true.B)
      io.renderCompletion.valid.poke(false.B);io.renderCompletion.bits.poke(0.U.asTypeOf(io.renderCompletion.bits))
      io.renderCompletion.bits.commandId.poke(21.U);io.renderCompletion.bits.appendReadBytes.poke(128.U)
      io.tessellationCompletion.get.valid.poke(false.B);io.tessellationCompletion.get.bits.poke(0.U.asTypeOf(io.tessellationCompletion.get.bits))
      io.tessellationCompletion.get.bits.commandId.poke(22.U);io.tessellationCompletion.get.bits.appendReadBytes.poke(256.U)
      io.renderCompletionDetails.get.foreach(_.poke(1.U));io.tessellationCompletionDetails.get.foreach(_.poke(2.U))
      io.otherCompletion.valid.poke(false.B);io.otherCompletion.bits.poke(0.U.asTypeOf(io.otherCompletion.bits))
      io.otherCompletion.bits.commandId.poke(23.U);io.otherCompletion.bits.success.poke(true.B)
      io.queryCommand.get.ready.poke(true.B);io.queryReply.get.valid.poke(false.B);io.queryReply.get.bits.poke(0.U.asTypeOf(io.queryReply.get.bits))
      io.feedbackCommand.get.ready.poke(true.B);io.feedbackReply.get.valid.poke(false.B);io.feedbackReply.get.bits.poke(0.U.asTypeOf(io.feedbackReply.get.bits));io.feedbackSatp.get.poke(0.U)
      io.primitiveQueryCommand.get.ready.poke(true.B);io.primitiveQueryReply.get.valid.poke(false.B);io.primitiveQueryReply.get.bits.poke(0.U.asTypeOf(io.primitiveQueryReply.get.bits))
      io.completion.ready.poke(false.B);io.dependencyIdle.poke(true.B);io.renderIdle.poke(true.B);io.resetActive.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.command.bits.commandId.poke(22.U);io.command.bits.opcode.poke(15.U);io.command.bits.bytes.poke(512.U)
      io.command.bits.widthBytes.poke("h71000007".U)
      io.command.valid.poke(true.B);io.command.ready.expect(true.B);io.renderQueryContext.get.expect("h71000007".U)
      d.clock.step();io.command.valid.poke(false.B);io.command.bits.widthBytes.poke(0.U)
      io.renderQueryContext.get.expect("h71000007".U)
      io.command.bits.opcode.poke(12.U);io.command.valid.poke(true.B)
      io.feedbackCommand.get.valid.expect(false.B);io.command.ready.expect(false.B);io.command.valid.poke(false.B)
      var render=true;var tessellation=true;var other=true;val seen=scala.collection.mutable.Set.empty[Int]
      var cycle=0
      while((render || tessellation || other || seen.size<3) && cycle<40){
        io.renderCompletion.valid.poke(render.B);io.tessellationCompletion.get.valid.poke(tessellation.B);io.otherCompletion.valid.poke(other.B)
        io.completion.ready.poke((cycle>=5).B)
        val r=render && io.renderCompletion.ready.peek().litToBoolean
        val t=tessellation && io.tessellationCompletion.get.ready.peek().litToBoolean
        val o=other && io.otherCompletion.ready.peek().litToBoolean
        if(io.completion.valid.peek().litToBoolean && cycle>=5){
          val id=io.completion.bits.commandId.peek().litValue.toInt;assert(!seen(id));seen+=id
          if(id==21){io.completion.bits.opcode.expect(7.U);io.completion.bits.bytesProcessed.expect(128.U);io.completionDetails.get.head.expect(1.U)}
          else if(id==22){io.completion.bits.opcode.expect(15.U);io.completion.bits.bytesProcessed.expect(256.U);io.completionDetails.get.head.expect(2.U)}
          else {assert(id==23);io.completionDetails.get.head.expect(0.U)}
        }
        d.clock.step();if(r)render=false;if(t)tessellation=false;if(o)other=false;cycle+=1
      }
      assert(cycle<40 && seen==Set(21,22,23));io.drained.expect(true.B)
    }
  }

  it should "route an opt-in tessellation job and retain its completion through reset" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2), nativeCompletionInfo=true, tessellationAvailable=true)) { d =>
      val io=d.io
      io.command.valid.poke(false.B);io.command.bits.poke(0.U.asTypeOf(io.command.bits))
      io.otherCommand.ready.poke(true.B);io.submit.ready.poke(true.B);io.tessellationSubmit.get.ready.poke(false.B)
      io.renderCompletion.valid.poke(false.B);io.renderCompletion.bits.poke(0.U.asTypeOf(io.renderCompletion.bits))
      io.tessellationCompletion.get.valid.poke(false.B);io.tessellationCompletion.get.bits.poke(0.U.asTypeOf(io.tessellationCompletion.get.bits))
      io.renderCompletionDetails.get.foreach(_.poke(0.U));io.tessellationCompletionDetails.get.zipWithIndex.foreach{case(w,i)=>w.poke((i+1).U)}
      io.otherCompletion.valid.poke(false.B);io.otherCompletion.bits.poke(0.U.asTypeOf(io.otherCompletion.bits))
      io.completion.ready.poke(false.B);io.dependencyIdle.poke(true.B);io.renderIdle.poke(false.B);io.resetActive.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val c=io.command.bits;c.commandId.poke(41.U);c.opcode.poke(15.U);c.sourceAddress.poke("hfffffe00".U)
      c.destinationAddress.poke(0.U);c.pattern.poke(1.U);c.bytes.poke(512.U);io.command.valid.poke(true.B)
      io.tessellationSubmit.get.valid.expect(false.B);io.otherCommand.valid.expect(false.B);io.command.ready.expect(false.B)
      io.renderIdle.poke(true.B);io.dependencyIdle.poke(false.B);io.tessellationSubmit.get.valid.expect(false.B)
      io.dependencyIdle.poke(true.B);io.tessellationSubmit.get.valid.expect(true.B)
      io.tessellationSubmit.get.bits.commandId.expect(41.U);io.tessellationSubmit.get.bits.limit.expect("h100000000".U)
      io.tessellationSubmit.get.bits.envelopeValid.expect(true.B);io.submit.valid.expect(false.B)
      val held=io.tessellationSubmit.get.bits.peek().litValue;d.clock.step(3)
      assert(io.tessellationSubmit.get.bits.peek().litValue==held)
      c.signalEvent.poke(true.B);io.tessellationSubmit.get.bits.envelopeValid.expect(false.B);c.signalEvent.poke(false.B)
      io.tessellationSubmit.get.ready.poke(true.B);io.command.ready.expect(true.B);d.clock.step()
      // The captured owner fences conflicting work even if the enclosing idle
      // signal is late to reflect the just-accepted job.
      io.command.valid.poke(false.B);io.renderIdle.poke(true.B)
      io.renderPending.expect(true.B);io.drained.expect(false.B)
      io.command.valid.poke(true.B);c.opcode.poke(7.U);io.submit.valid.expect(false.B);io.command.ready.expect(false.B)
      c.opcode.poke(1.U);io.otherCommand.valid.expect(false.B)
      c.opcode.poke(15.U);io.tessellationSubmit.get.valid.expect(false.B);io.command.ready.expect(false.B)
      c.opcode.poke(0.U);io.otherCommand.valid.expect(true.B)
      io.command.valid.poke(false.B)
      val completion=io.tessellationCompletion.get
      completion.bits.commandId.poke(41.U);completion.bits.status.poke(3.U);completion.bits.appendReadBytes.poke(704.U)
      completion.valid.poke(true.B);completion.ready.expect(true.B);d.clock.step();completion.valid.poke(false.B)
      io.completion.valid.expect(true.B);io.completion.bits.commandId.expect(41.U);io.completion.bits.opcode.expect(15.U)
      io.completion.bits.status.expect(3.U);io.completion.bits.success.expect(false.B);io.completion.bits.bytesProcessed.expect(704.U)
      io.completionDetails.get.zipWithIndex.foreach{case(w,i)=>w.expect((i+1).U)}
      val result=io.completion.bits.peek().litValue;io.resetActive.poke(true.B);io.cancel.expect(true.B);d.clock.step(3)
      assert(io.completion.bits.peek().litValue==result);io.drained.expect(false.B)
      io.completion.ready.poke(true.B);d.clock.step();io.drained.expect(true.B)
    }
  }

  it should "leave opcode 15 on the existing unsupported path when tessellation is disabled" in {
    simulate(new TileBinHostCommandPort(GpuConfig(lanes=4,warps=2))) { d =>
      val io=d.io;io.command.valid.poke(true.B);io.command.bits.poke(0.U.asTypeOf(io.command.bits));io.command.bits.opcode.poke(15.U)
      io.otherCommand.ready.poke(true.B);io.submit.ready.poke(true.B)
      io.renderCompletion.valid.poke(false.B);io.renderCompletion.bits.poke(0.U.asTypeOf(io.renderCompletion.bits))
      io.otherCompletion.valid.poke(false.B);io.otherCompletion.bits.poke(0.U.asTypeOf(io.otherCompletion.bits))
      io.completion.ready.poke(true.B);io.dependencyIdle.poke(true.B);io.renderIdle.poke(true.B);io.resetActive.poke(false.B)
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.otherCommand.valid.expect(true.B);io.otherCommand.bits.opcode.expect(15.U);io.command.ready.expect(true.B);io.submit.valid.expect(false.B)
    }
  }
}
