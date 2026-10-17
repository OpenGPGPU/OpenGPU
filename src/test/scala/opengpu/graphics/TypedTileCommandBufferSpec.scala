package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class TypedTileCommandBufferSpec extends AnyFlatSpec {
  behavior of "versioned ordered tile commands"
  private class Driver(val d: CommandBufferStage) {
    d.io.start.poke(false.B); d.io.cancel.get.poke(false.B)
    d.io.typedCommands.get.poke(true.B); d.io.clearComplete.get.poke(false.B)
    d.io.draw.ready.poke(false.B); d.io.clear.get.ready.poke(false.B)
    d.io.mem.req.ready.poke(false.B); d.io.mem.resp.valid.poke(false.B)
    d.io.mem.resp.bits.poke(0.U.asTypeOf(d.io.mem.resp.bits))
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    var data = Seq.empty[Long]; var pending: Option[(BigInt, Long)] = None
    var reads = 0; var faults = 0; var holdResponse = false
    var base = 0x4000L
    val draws = mutable.ArrayBuffer.empty[Long]
    def start(records: Seq[Seq[Long]], typed: Boolean = true, address: Long = 0x4000L): Unit = {
      assert(pending.isEmpty); data = records.flatten; reads = 0; faults = 0; draws.clear()
      base = address; d.io.base.poke(base.U); d.io.count.poke(records.size.U)
      d.io.typedCommands.get.poke(typed.B); d.io.start.poke(true.B); tick(); d.io.start.poke(false.B)
      // Later input changes must not change this stream's word stride.
      d.io.typedCommands.get.poke((!typed).B); d.io.base.poke(0.U); d.io.count.poke(0.U)
    }
    def tick(): Unit = {
      d.io.mem.req.ready.poke(true.B)
      d.io.mem.resp.valid.poke((pending.nonEmpty && !holdResponse).B)
      pending.foreach { case (a, v) =>
        d.io.mem.resp.bits.addr.poke(a.U); d.io.mem.resp.bits.write.poke(false.B)
        d.io.mem.resp.bits.data.poke((v & 0xffffffffL).U)
      }
      val reply = d.io.mem.resp.valid.peek().litToBoolean && d.io.mem.resp.ready.peek().litToBoolean
      val request = d.io.mem.req.valid.peek().litToBoolean && d.io.mem.req.ready.peek().litToBoolean
      val accepted = if (request) {
        val a = d.io.mem.req.bits.addr.peek().litValue
        assert(a == BigInt(base) + reads*4 && reads < data.size)
        reads += 1; Some((a, data(reads-1)))
      } else None
      if (d.io.encodingFault.get.peek().litToBoolean) faults += 1
      if (d.io.draw.valid.peek().litToBoolean && d.io.draw.ready.peek().litToBoolean) {
        val triangle = d.io.draw.bits.asInstanceOf[SceneTriangle]
        draws += triangle.shaderPc.peek().litValue.toLong
        triangle.scissorEnable.expect(true.B); triangle.scissorMinX.expect(1.U)
        triangle.scissorMinY.expect(2.U); triangle.scissorMaxX.expect(7.U); triangle.scissorMaxY.expect(8.U)
      }
      d.clock.step()
      if (reply) pending = None
      accepted.foreach { p => assert(pending.isEmpty); pending = Some(p) }
    }
    def until(p: => Boolean): Unit = {
      var n=0; while (!p && n<1200) { tick(); n+=1 }; assert(p, "tile command phase timed out")
    }
  }
  private def draw(pc: Long): Seq[Long] = {
    val w=Array.fill[Long](40)(0); w(24)=pc
    w(32)=1L<<18; w(38)=0x00020001; w(39)=0x00080007
    Seq(0L)++w
  }
  private def clear: Seq[Long] = Seq(1L,0x07060201L,0xff00ff00L,0xff000000L,
    0x12345678L,0x9abcdef0L)++Seq.fill(35)(0L)
  private def module = new CommandBufferStage(GraphicsConfig(drawFifoDepth=4),
    cancellable=true,tileCommands=true)

  it should "fetch exactly forty-one words at the four-GiB allocation boundary" in {
    simulate(module) { d =>
      val h = new Driver(d); h.start(Seq(draw(0x3000)), address = 0xffffff5cL)
      d.io.draw.ready.poke(true.B); h.until(d.io.done.peek().litToBoolean)
      assert(h.reads == 41 && h.draws.toSeq == Seq(0x3000L))
      for (_ <- 0 until 5) { h.tick(); d.io.mem.req.valid.expect(false.B) }
    }
  }

  it should "keep draw/clear/draw ordering and hold younger draws until the clear completes" in {
    simulate(module) { d =>
      val h=new Driver(d); h.start(Seq(draw(0x3000),clear,draw(0x3200)))
      d.io.draw.ready.poke(true.B)
      h.until(d.io.clear.get.valid.peek().litToBoolean)
      assert(h.draws.toSeq==Seq(0x3000L))
      val c=d.io.clear.get.bits; c.minX.expect(1.U); c.minY.expect(2.U)
      c.maxX.expect(6.U); c.maxY.expect(7.U); c.colorMask.expect(0xff00ff00L.U)
      c.depthMask.expect(0xff000000L.U); c.colorValue.expect(0x12345678L.U); c.depthValue.expect(0x9abcdef0L.U)
      val snapshot=c.peek().litValue
      for (_ <- 0 until 5) { h.tick(); assert(c.peek().litValue==snapshot); d.io.draw.valid.expect(false.B) }
      d.io.clear.get.ready.poke(true.B); h.tick(); d.io.clear.get.ready.poke(false.B)
      h.until(h.reads==123 && h.pending.isEmpty)
      for (_ <- 0 until 5) { h.tick(); d.io.done.expect(false.B); d.io.draw.valid.expect(false.B) }
      d.io.clearComplete.get.poke(true.B); h.tick(); d.io.clearComplete.get.poke(false.B)
      h.until(d.io.done.peek().litToBoolean); assert(h.draws.toSeq==Seq(0x3000L,0x3200L)); assert(h.faults==0)
    }
  }

  it should "reject unknown types and reserved clear bits, then reuse the legacy draw layout including scissor" in {
    simulate(module) { d =>
      val h=new Driver(d)
      for ((index,value) <- Seq((0,2L),(1,0x07060221L),(6,1L),(40,1L))) {
        val w=clear.toArray; w(index)=value
        h.start(Seq(w.toSeq)); h.until(d.io.done.peek().litToBoolean)
        assert(h.faults==1 && h.reads==41 && h.draws.isEmpty)
        d.io.clear.get.valid.expect(false.B)
      }
      h.start(Seq(draw(0x3400).tail),typed=false)
      h.until(d.io.draw.valid.peek().litToBoolean)
      val x=d.io.draw.bits.asInstanceOf[SceneTriangle]
      x.scissorEnable.expect(true.B); x.scissorMinX.expect(1.U); x.scissorMinY.expect(2.U)
      x.scissorMaxX.expect(7.U); x.scissorMaxY.expect(8.U)
      d.io.draw.ready.poke(true.B); h.until(d.io.done.peek().litToBoolean)
      assert(h.reads==40 && h.faults==0 && h.draws.toSeq==Seq(0x3400L))
    }
  }

  it should "cancel an accepted clear barrier while preserving a pending command response" in {
    simulate(module) { d =>
      val h=new Driver(d); h.start(Seq(clear,draw(0x3600)))
      h.until(d.io.clear.get.valid.peek().litToBoolean)
      d.io.clear.get.ready.poke(true.B); h.tick(); d.io.clear.get.ready.poke(false.B)
      h.holdResponse=true; h.until(h.pending.nonEmpty)
      d.io.cancel.get.poke(true.B); h.tick(); d.io.cancel.get.poke(false.B)
      for (_ <- 0 until 5) { h.tick(); d.io.done.expect(false.B); d.io.draw.valid.expect(false.B) }
      h.holdResponse=false; h.until(d.io.done.peek().litToBoolean)
      assert(h.draws.isEmpty && h.reads<82)
      h.start(Seq(draw(0x3800))); d.io.draw.ready.poke(true.B)
      h.until(d.io.done.peek().litToBoolean); assert(h.draws.toSeq==Seq(0x3800L))
    }
  }
}
