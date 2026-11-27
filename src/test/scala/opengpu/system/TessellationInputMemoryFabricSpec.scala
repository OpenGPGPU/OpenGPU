package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics.tessellation.{TessellationContextReply, TessellationPatchAccess}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import opengpu.graphics.WindowRecordTest.zero
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationInputTranslatedHarness extends Module {
  private val config = GpuConfig(lanes = 4, warps = 2);
  private val slots = TessellationInputMemoryFabric.transactions(8)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationPatchInputDmaTask));
    val completion = Decoupled(new TessellationPatchInputDmaCompletion)
    val load = Decoupled(new TessellationPatchAccess); val reply = Flipped(Decoupled(new TessellationContextReply))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, slots));
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, slots)))
    val satp = Input(UInt(32.W)); val drained = Output(Bool())
  })
  private val dma = Module(new TessellationPatchInputDma(config));
  private val fabric = Module(new TessellationInputMemoryFabric(config, 8))
  dma.io.task <> io.task; io.completion <> dma.io.completion; io.load <> dma.io.load; dma.io.reply <> io.reply;
  dma.io.cancel := false.B
  fabric.io.inputRequest <> dma.io.memoryRequest; dma.io.memoryResponse <> fabric.io.inputResponse
  fabric.io.renderRequest.valid := false.B; fabric.io.renderRequest.bits := 0.U.asTypeOf(fabric.io.renderRequest.bits)
  fabric.io.renderResponse.ready := true.B; fabric.io.satp := io.satp
  fabric.io.flush.valid := false.B; fabric.io.flush.bits := 0.U.asTypeOf(fabric.io.flush.bits)
  io.memoryRequest <> fabric.io.memoryRequest; fabric.io.memoryResponse <> io.memoryResponse;
  io.drained := fabric.io.drained
}

class TessellationInputMemoryFabricSpec extends AnyFlatSpec {
  it should "grant and drain the source port independently of a held render fault response" in {
    simulate(new TessellationInputMemoryFabric(GpuConfig(lanes = 16, warps = 8), 8, ownedVertexSource = true)) { d =>
      val io = d.io; zero(io); io.satp.poke("h80000030".U)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def until(p: => Boolean, why: String): Unit = {
        var n = 0; while (!p && n < 100) { d.clock.step(); n += 1 }; assert(p, why)
      }
      def external(id: Int, address: Int, data: BigInt, hold: Int = 0): Unit = {
        until(io.memoryRequest.valid.peek().litToBoolean, s"external request $id")
        io.memoryRequest.bits.transactionId.expect(id.U); io.memoryRequest.bits.address.expect(address.U)
        val bits = io.memoryRequest.bits.peek().litValue
        for (_ <- 0 until hold) {
          assert(io.memoryRequest.bits.peek().litValue == bits)
          io.sourceSelected.get.expect(true.B); io.sourceDrained.get.expect(false.B); d.clock.step()
        }
        io.memoryRequest.ready.poke(true.B); d.clock.step(); io.memoryRequest.ready.poke(false.B)
        io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(id.U)
        io.memoryResponse.bits.readData.poke(data.U)
        until(io.memoryResponse.ready.peek().litToBoolean, s"external reply $id")
        d.clock.step(); io.memoryResponse.valid.poke(false.B)
      }
      // A real failed page walk creates a local renderer reply. Hold that reply
      // across both VS and legacy traffic, without violating the external
      // response channel's stable-offer contract or hiding a busy FS client.
      io.renderRequest.valid.poke(true.B); io.renderRequest.bits.transactionId.poke(2.U)
      io.renderRequest.bits.address.poke(0x400080.U); io.renderRequest.bits.sizeLog2.poke(6.U)
      until(io.renderRequest.ready.peek().litToBoolean, "render ingress")
      d.clock.step(); io.renderRequest.valid.poke(false.B)
      external(17, 0x30004, 0)
      until(io.renderResponse.valid.peek().litToBoolean, "held local render fault")
      io.renderResponse.bits.transactionId.expect(2.U); io.renderResponse.bits.fault.expect(true.B)
      val renderBits = io.renderResponse.bits.peek().litValue
      def renderHeld(): Unit = {
        io.renderResponse.valid.expect(true.B); assert(io.renderResponse.bits.peek().litValue == renderBits)
        io.drained.expect(false.B)
      }
      val s = io.sourceRequest.get
      s.valid.poke(true.B); s.bits.address.poke(0x400040.U); s.bits.sizeLog2.poke(6.U)
      s.bits.transactionId.poke(3.U); io.sourceLive.get.poke(true.B)
      // This fixed grant deadline is the old-global-drain negative control's
      // failure oracle; it is independent of the simulator/process timeout.
      for (_ <- 0 until 8) { renderHeld(); if (!s.ready.peek().litToBoolean) d.clock.step() }
      assert(s.ready.peek().litToBoolean, "source grant must not wait for held render response")
      d.clock.step(); s.valid.poke(false.B)
      external(18, 0x30004, (BigInt(0x800) << 10) | 0x43, 4)
      external(12, 0x800040, 0xabc, 4)
      until(io.sourceResponse.get.valid.peek().litToBoolean, "first VS delivery")
      io.sourceResponse.get.bits.transactionId.expect(3.U); io.sourceResponse.get.bits.readData.expect(0xabc.U)
      for (_ <- 0 until 4) { renderHeld(); io.sourceDrained.get.expect(false.B); d.clock.step() }
      io.sourceResponse.get.ready.poke(true.B); d.clock.step(); io.sourceResponse.get.ready.poke(false.B)
      io.sourceDrained.get.expect(true.B); renderHeld()
      // The second local tag's request and reply retain selection even when
      // legacy handback is already requested; render stays independently held.
      s.valid.poke(true.B); s.bits.transactionId.poke(2.U); s.bits.address.poke(0x400080.U)
      s.ready.expect(true.B); d.clock.step(); s.valid.poke(false.B)
      io.sourceLive.get.poke(false.B)
      io.inputRequest.valid.poke(true.B); io.inputRequest.bits.address.poke(0x4000c0.U)
      io.inputRequest.bits.sizeLog2.poke(6.U)
      external(12, 0x800080, 0xdef, 4)
      until(io.sourceResponse.get.valid.peek().litToBoolean, "second VS delivery")
      io.sourceResponse.get.bits.transactionId.expect(2.U); io.sourceResponse.get.bits.readData.expect(0xdef.U)
      for (_ <- 0 until 4) {
        renderHeld(); io.sourceSelected.get.expect(true.B); io.inputRequest.ready.expect(false.B)
        io.sourceDrained.get.expect(false.B); d.clock.step()
      }
      io.sourceResponse.get.ready.poke(true.B); d.clock.step(); io.sourceResponse.get.ready.poke(false.B)
      until(io.inputRequest.ready.peek().litToBoolean, "legacy handback must not wait for render")
      io.sourceSelected.get.expect(false.B); d.clock.step(); io.inputRequest.valid.poke(false.B)
      until(io.memoryRequest.valid.peek().litToBoolean, "legacy external request")
      io.memoryRequest.bits.transactionId.expect(12.U); io.memoryRequest.bits.address.expect(0x8000c0.U)
      io.memoryRequest.ready.poke(true.B); d.clock.step(); io.memoryRequest.ready.poke(false.B)
      io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(12.U)
      io.memoryResponse.bits.readData.poke(0x123.U)
      until(io.inputResponse.valid.peek().litToBoolean, "legacy delivery")
      io.inputResponse.bits.readData.expect(0x123.U)
      for (_ <- 0 until 4) {
        renderHeld(); io.sourceDrained.get.expect(false.B); io.memoryResponse.ready.expect(false.B); d.clock.step()
      }
      io.inputResponse.ready.poke(true.B); io.memoryResponse.ready.expect(true.B); d.clock.step()
      io.inputResponse.ready.poke(false.B); io.memoryResponse.valid.poke(false.B)
      io.sourceDrained.get.expect(true.B); renderHeld()
      // A global flush deliberately still waits for the renderer, including
      // its local fault response, rather than using the new per-port signal.
      io.flush.valid.poke(true.B); d.clock.step(); io.flush.valid.poke(false.B)
      for (_ <- 0 until 4) { renderHeld(); io.sourceDrained.get.expect(false.B); d.clock.step() }
      io.renderResponse.ready.poke(true.B); d.clock.step(); io.renderResponse.ready.poke(false.B)
      until(io.drained.peek().litToBoolean, "global render/flush retirement")
      io.sourceDrained.get.expect(true.B)
    }
  }

  it should "serialize four original VS tags through held requests and held replies" in {
    simulate(new TessellationVertexMemoryAdapter(GpuConfig(lanes = 16, warps = 8))) { d =>
      zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val q = d.io.sourceRequest
      def request(tag: Int): Unit = {
        q.bits.address.poke((0x1040 + 64 * tag).U); q.bits.transactionId.poke(tag.U)
        q.bits.writeData.poke((BigInt(1) << 500 | tag).U); q.bits.byteMask.poke("h8000000000000001".U)
        q.bits.isWrite.poke((tag == 1).B); q.bits.sizeLog2.poke(6.U)
        q.bits.cacheClient.poke(true.B); q.bits.cacheResident.poke((tag == 1).B); q.bits.cachePolicy.poke(1.U)
        q.valid.poke(true.B)
      }
      request(0)
      for (tag <- 0 until 4) {
        q.ready.expect(true.B); d.clock.step()
        // The entire accepted request survives a downstream stall. A queued
        // next local tag cannot acquire this single credit before delivery.
        if (tag < 3) request(tag + 1) else q.valid.poke(false.B)
        val next = if (tag < 3) Some(q.bits.peek().litValue) else None
        val held = d.io.memoryRequest.bits.peek().litValue
        for (_ <- 0 until 4) {
          q.ready.expect(false.B); d.io.memoryRequest.valid.expect(true.B)
          assert(d.io.memoryRequest.bits.peek().litValue == held)
          next.foreach(n => assert(q.bits.peek().litValue == n))
          d.io.drained.expect(false.B); d.clock.step()
        }
        d.io.memoryRequest.bits.transactionId.expect(0.U); d.io.memoryRequest.bits.address.expect((0x1040 + 64 * tag).U)
        d.io.memoryRequest.bits.writeData.expect((BigInt(1) << 500 | tag).U)
        d.io.memoryRequest.bits.byteMask.expect("h8000000000000001".U)
        d.io.memoryRequest.bits.sizeLog2.expect(6.U)
        d.io.memoryRequest.bits.isWrite.expect((tag == 1).B); d.io.memoryRequest.bits.cachePolicy.expect(1.U)
        d.io.memoryRequest.bits.cacheClient.expect(true.B); d.io.memoryRequest.bits.cacheResident.expect((tag == 1).B)
        d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
        for (_ <- 0 until 3) {
          d.io.memoryRequest.valid.expect(false.B); q.ready.expect(false.B); d.io.drained.expect(false.B);
          d.clock.step()
        }
        val data = (BigInt(1) << 499) | BigInt(0xa0 + tag)
        d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.transactionId.poke(0.U)
        d.io.memoryResponse.bits.readData.poke(data.U); d.io.memoryResponse.bits.fault.poke((tag == 3).B)
        d.io.memoryResponse.ready.expect(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
        val reply = d.io.sourceResponse.bits.peek().litValue
        for (_ <- 0 until 4) {
          d.io.sourceResponse.valid.expect(true.B); d.io.sourceResponse.bits.transactionId.expect(tag.U)
          d.io.sourceResponse.bits.readData.expect(data.U); d.io.sourceResponse.bits.fault.expect((tag == 3).B)
          assert(d.io.sourceResponse.bits.peek().litValue == reply); q.ready.expect(false.B);
          d.io.drained.expect(false.B); d.clock.step()
        }
        // This is the real local-client delivery, not merely memory ACK.
        d.io.sourceResponse.ready.poke(true.B); d.clock.step()
        d.io.sourceResponse.ready.poke(false.B); d.io.drained.expect((tag == 3).B)
      }
    }
  }

  it should "retain the granted VS owner for an offered next request when sourceLive withdraws" in {
    simulate(new TessellationInputMemoryFabric(GpuConfig(lanes = 4, warps = 2), 8, ownedVertexSource = true)) { d =>
      val io = d.io; zero(io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 100) { d.clock.step(); n += 1 }; assert(p) }
      val s = io.sourceRequest.get
      s.valid.poke(true.B); s.bits.address.poke(0x4000.U); s.bits.sizeLog2.poke(6.U)
      io.sourceLive.get.poke(true.B); until(s.ready.peek().litToBoolean); d.clock.step()
      // Offer the next packet in exactly the cycle relinquishment is asked.
      // It belongs to the still-granted source and must transfer before drain.
      s.bits.address.poke(0x4040.U); s.bits.transactionId.poke(1.U); s.bits.isWrite.poke(true.B)
      s.bits.writeData.poke(0x55.U); s.bits.byteMask.poke(15.U); io.sourceLive.get.poke(false.B)
      for (tag <- 0 until 2) {
        until(io.memoryRequest.valid.peek().litToBoolean)
        io.memoryRequest.bits.transactionId.expect(12.U); io.memoryRequest.bits.address.expect((0x4000 + 64 * tag).U)
        io.memoryRequest.bits.isWrite.expect((tag == 1).B)
        for (_ <- 0 until 3) {
          io.sourceSelected.get.expect(true.B); s.ready.expect(false.B); io.drained.expect(false.B); d.clock.step()
        }
        io.memoryRequest.ready.poke(true.B); d.clock.step(); io.memoryRequest.ready.poke(false.B)
        io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(12.U)
        io.memoryResponse.bits.readData.poke((0xaa + tag).U)
        until(io.memoryResponse.ready.peek().litToBoolean); d.clock.step(); io.memoryResponse.valid.poke(false.B)
        until(io.sourceResponse.get.valid.peek().litToBoolean)
        io.sourceResponse.get.bits.transactionId.expect(tag.U);
        io.sourceResponse.get.bits.readData.expect((0xaa + tag).U)
        for (_ <- 0 until 3) {
          io.sourceSelected.get.expect(true.B); s.ready.expect(false.B); io.drained.expect(false.B); d.clock.step()
        }
        io.sourceResponse.get.ready.poke(true.B); d.clock.step(); io.sourceResponse.get.ready.poke(false.B)
        if (tag == 0) {
          io.sourceSelected.get.expect(true.B); s.ready.expect(true.B)
          d.clock.step(); s.valid.poke(false.B)
        }
      }
      until(io.drained.peek().litToBoolean); io.sourceSelected.get.expect(false.B)
    }
  }

  it should "drain an already offered ungranted source after selection intent is revoked" in {
    simulate(new TessellationInputMemoryFabric(GpuConfig(lanes = 4, warps = 2), 8, ownedVertexSource = true)) { d =>
      val io = d.io; zero(io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      var waitIndex = 0
      def until(p: => Boolean): Unit = {
        waitIndex += 1; var n = 0; while (!p && n < 100) { d.clock.step(); n += 1 };
        assert(p, s"grant/drain wait $waitIndex")
      }
      def offerMemory(address: Int): Unit = {
        until(io.memoryRequest.valid.peek().litToBoolean)
        io.memoryRequest.bits.transactionId.expect(12.U); io.memoryRequest.bits.address.expect(address.U)
        io.memoryRequest.ready.poke(true.B); d.clock.step(); io.memoryRequest.ready.poke(false.B)
        io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(12.U)
        io.memoryResponse.bits.readData.poke(address.U)
      }
      def finishMemory(): Unit = {
        until(io.memoryResponse.ready.peek().litToBoolean); d.clock.step(); io.memoryResponse.valid.poke(false.B)
      }
      io.inputRequest.valid.poke(true.B); io.inputRequest.bits.address.poke(0x4000.U);
      io.inputRequest.bits.sizeLog2.poke(6.U)
      until(io.inputRequest.ready.peek().litToBoolean); d.clock.step(); io.inputRequest.valid.poke(false.B)
      // The current legacy owner has not received its reply. Recognize an
      // offered VS packet, then revoke the request before it can be granted.
      val s = io.sourceRequest.get
      s.valid.poke(true.B); s.bits.address.poke(0x4040.U); s.bits.sizeLog2.poke(6.U); s.bits.transactionId.poke(2.U)
      io.sourceLive.get.poke(true.B); d.clock.step(); io.sourceLive.get.poke(false.B)
      offerMemory(0x4000); until(io.inputResponse.valid.peek().litToBoolean)
      s.ready.expect(false.B); io.sourceSelected.get.expect(false.B)
      io.inputResponse.ready.poke(true.B); finishMemory(); io.inputResponse.ready.poke(false.B)
      until(s.ready.peek().litToBoolean); io.sourceSelected.get.expect(true.B)
      d.clock.step(); s.valid.poke(false.B)
      // Symmetric case: a legacy packet was offered for handback, then the
      // caller requests VS again before legacy can acquire the port.
      io.inputRequest.valid.poke(true.B); io.inputRequest.bits.address.poke(0x4080.U)
      d.clock.step(); io.sourceLive.get.poke(true.B)
      offerMemory(0x4040); finishMemory(); until(io.sourceResponse.get.valid.peek().litToBoolean)
      io.sourceResponse.get.bits.transactionId.expect(2.U); io.sourceResponse.get.bits.readData.expect(0x4040.U)
      io.sourceResponse.get.ready.poke(true.B); d.clock.step(); io.sourceResponse.get.ready.poke(false.B)
      until(io.inputRequest.ready.peek().litToBoolean); io.sourceSelected.get.expect(false.B)
      d.clock.step(); io.inputRequest.valid.poke(false.B)
      offerMemory(0x4080); until(io.inputResponse.valid.peek().litToBoolean)
      io.inputResponse.bits.readData.expect(0x4080.U)
      io.inputResponse.ready.poke(true.B); finishMemory(); io.inputResponse.ready.poke(false.B)
      until(io.drained.peek().litToBoolean); io.sourceSelected.get.expect(true.B)
      io.sourceLive.get.poke(false.B); until(io.drained.peek().litToBoolean); io.sourceSelected.get.expect(false.B)
    }
  }

  for (writable <- Seq(false, true))
    it should s"retain owned VS memory through cancel intent, flush and Sv32 writable=$writable" in {
      simulate(new TessellationInputMemoryFabric(GpuConfig(lanes = 16, warps = 8), 8, ownedVertexSource = true)) { d =>
        val io = d.io; zero(io); io.satp.poke("h80000030".U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        val pending = mutable.Queue.empty[(Int, BigInt)]
        var offered: Option[(Int, BigInt)] = None; var walks = 0; var writes = 0; var reads = 0
        def tick(): Unit = {
          if (offered.isEmpty && pending.nonEmpty) offered = Some(pending.dequeue())
          io.memoryResponse.valid.poke(offered.nonEmpty.B)
          offered.foreach { case (id, data) =>
            io.memoryResponse.bits.transactionId.poke(id.U); io.memoryResponse.bits.readData.poke(data.U)
          }
          if (io.memoryRequest.valid.peek().litToBoolean && io.memoryRequest.ready.peek().litToBoolean) {
            val id = io.memoryRequest.bits.transactionId.peek().litValue.toInt
            if (id == 18) {
              io.memoryRequest.bits.address.expect(0x30004.U); io.memoryRequest.bits.isWrite.expect(false.B); walks += 1
              pending.enqueue((id, (BigInt(0x800) << 10) | (if (writable) 0xc7 else 0x43)))
            } else {
              assert(id == 12); io.memoryRequest.bits.address.expect(0x800040.U)
              if (io.memoryRequest.bits.isWrite.peek().litToBoolean) {
                assert(writable); writes += 1; io.memoryRequest.bits.writeData.expect("h12345678".U)
                io.memoryRequest.bits.byteMask.expect(15.U)
              } else reads += 1
              pending.enqueue((id, BigInt(0xcafe)))
            }
          }
          val returned = offered.nonEmpty && io.memoryResponse.ready.peek().litToBoolean
          d.clock.step(); if (returned) offered = None
        }
        def until(p: => Boolean): Unit = { var n = 0; while (!p && n < 1000) { tick(); n += 1 }; assert(p) }
        val s = io.sourceRequest.get
        s.bits.address.poke(0x400040.U); s.bits.sizeLog2.poke(6.U); s.bits.transactionId.poke(1.U)
        s.bits.isWrite.poke(true.B); s.bits.writeData.poke("h12345678".U); s.bits.byteMask.poke(15.U)
        s.valid.poke(true.B); io.sourceLive.get.poke(true.B)
        // Early source valid must not deadlock the grant of its first credit.
        s.ready.expect(false.B); io.sourceSelected.get.expect(false.B)
        until(s.ready.peek().litToBoolean); io.sourceSelected.get.expect(true.B); tick(); s.valid.poke(false.B)
        until(io.memoryRequest.valid.peek().litToBoolean)
        val held = io.memoryRequest.bits.peek().litValue
        // Request cancellation/relinquishment before the held page walk moves.
        // The real VS cancellation must keep its already offered request alive.
        io.sourceLive.get.poke(false.B)
        io.flush.valid.poke(true.B); io.flush.bits.asidValid.poke(true.B); io.flush.bits.asid.poke(1.U)
        tick(); io.flush.bits.asidValid.poke(false.B); io.flush.bits.virtualPageNumberValid.poke(true.B)
        io.flush.bits.virtualPageNumber.poke(0.U); tick(); io.flush.valid.poke(false.B)
        for (_ <- 0 until 3) {
          io.sourceSelected.get.expect(true.B); io.memoryRequest.valid.expect(true.B)
          assert(io.memoryRequest.bits.peek().litValue == held); io.drained.expect(false.B); tick()
        }
        // An early legacy offer is still unselected and cannot block releasing
        // the old source after its response is actually delivered.
        io.inputRequest.valid.poke(true.B); io.inputRequest.bits.address.poke(0x400040.U)
        io.inputRequest.bits.sizeLog2.poke(6.U); io.inputRequest.bits.transactionId.poke(0.U)
        io.memoryRequest.ready.poke(true.B)
        until(io.sourceResponse.get.valid.peek().litToBoolean)
        io.sourceResponse.get.bits.transactionId.expect(1.U); io.sourceResponse.get.bits.fault.expect((!writable).B)
        for (_ <- 0 until 5) {
          io.inputRequest.ready.expect(false.B); io.sourceSelected.get.expect(true.B); io.drained.expect(false.B);
          tick()
        }
        io.sourceResponse.get.ready.poke(true.B); tick(); io.sourceResponse.get.ready.poke(false.B)
        until(io.inputRequest.ready.peek().litToBoolean); io.sourceSelected.get.expect(false.B)
        tick(); io.inputRequest.valid.poke(false.B)
        until(io.inputResponse.valid.peek().litToBoolean)
        io.inputResponse.bits.transactionId.expect(0.U); io.inputResponse.bits.fault.expect(false.B)
        io.inputResponse.bits.readData.expect(0xcafe.U)
        // Request VS again while a held legacy response still owns the fabric.
        s.valid.poke(true.B); s.bits.isWrite.poke(false.B); s.bits.transactionId.poke(2.U)
        io.sourceLive.get.poke(true.B)
        for (_ <- 0 until 4) {
          s.ready.expect(false.B); io.sourceSelected.get.expect(false.B); io.drained.expect(false.B); tick()
        }
        io.inputResponse.ready.poke(true.B); tick(); io.inputResponse.ready.poke(false.B)
        until(s.ready.peek().litToBoolean); io.sourceSelected.get.expect(true.B); tick(); s.valid.poke(false.B)
        until(io.sourceResponse.get.valid.peek().litToBoolean)
        io.sourceResponse.get.bits.transactionId.expect(2.U); io.sourceResponse.get.bits.readData.expect(0xcafe.U)
        io.sourceResponse.get.bits.fault.expect(false.B); io.sourceResponse.get.ready.poke(true.B); tick()
        io.sourceResponse.get.ready.poke(false.B); io.sourceLive.get.poke(false.B)
        until(io.drained.peek().litToBoolean)
        assert(writes == (if (writable) 1 else 0) && reads == 2 && walks == 2)
        assert(pending.isEmpty && offered.isEmpty)
      }
    }

  it should "keep both legacy DMA entry contracts read-only" in {
    for (owned <- Seq(false, true)) intercept[chisel3.simulator.Exceptions.AssertionFailed] {
      simulate(new TessellationInputMemoryFabric(GpuConfig(lanes = 4, warps = 2), 8, ownedVertexSource = owned)) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.io.inputRequest.valid.poke(true.B); d.io.inputRequest.bits.isWrite.poke(true.B); d.clock.step()
      }
    }
  }

  it should "reject unowned or nonzero serialized replies without delivering a VS result" in {
    for (late <- Seq(false, true)) intercept[chisel3.simulator.Exceptions.AssertionFailed] {
      simulate(new TessellationVertexMemoryAdapter(GpuConfig(lanes = 4, warps = 2))) { d =>
        zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        if (!late) {
          d.io.sourceRequest.valid.poke(true.B); d.io.sourceRequest.ready.expect(true.B); d.clock.step()
          d.io.sourceRequest.valid.poke(false.B); d.io.memoryRequest.ready.poke(true.B); d.clock.step()
          d.io.memoryRequest.ready.poke(false.B)
        }
        d.io.memoryResponse.valid.poke(true.B); d.io.memoryResponse.bits.transactionId.poke((if (late) 0 else 1).U)
        d.io.memoryResponse.ready.expect(false.B); d.io.sourceResponse.valid.expect(false.B)
        d.io.drained.expect(false.B); d.clock.step()
      }
    }
  }

  it should "reject high staging reply tags before narrowing to serialized zero" in {
    intercept[chisel3.simulator.Exceptions.AssertionFailed] {
      simulate(new TessellationInputMemoryFabric(GpuConfig(lanes = 4, warps = 2), 8, ownedVertexSource = true)) { d =>
        val io = d.io; zero(io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        io.sourceLive.get.poke(true.B); io.sourceRequest.get.valid.poke(true.B)
        var cycles = 0
        while (!io.sourceRequest.get.ready.peek().litToBoolean && cycles < 20) { d.clock.step(); cycles += 1 }
        assert(cycles < 20); d.clock.step(); io.sourceRequest.get.valid.poke(false.B)
        io.memoryRequest.ready.poke(true.B); cycles = 0
        while (!io.memoryRequest.valid.peek().litToBoolean && cycles < 100) { d.clock.step(); cycles += 1 }
        assert(cycles < 100); io.memoryRequest.bits.transactionId.expect(12.U); d.clock.step()
        io.memoryResponse.valid.poke(true.B); io.memoryResponse.bits.transactionId.poke(14.U)
        io.sourceResponse.get.ready.poke(true.B); io.inputResponse.ready.poke(true.B)
        io.memoryResponse.ready.expect(false.B); io.sourceResponse.get.valid.expect(false.B);
        io.inputResponse.valid.expect(false.B)
        io.drained.expect(false.B); d.clock.step()
      }
    }
  }

  for (badInput <- Seq(false, true))
    it should s"translate and drain render and patch-input requests with input fault=$badInput" in {
      simulate(new TessellationInputMemoryFabric(GpuConfig(lanes = 4, warps = 2), 8)) { d =>
        val io = d.io
        io.satp.poke("h80000030".U); io.flush.valid.poke(false.B); io.flush.bits.poke(0.U.asTypeOf(io.flush.bits))
        io.renderRequest.valid.poke(false.B); io.renderRequest.bits.poke(0.U.asTypeOf(io.renderRequest.bits))
        io.inputRequest.valid.poke(false.B); io.inputRequest.bits.poke(0.U.asTypeOf(io.inputRequest.bits))
        io.renderResponse.ready.poke(true.B); io.inputResponse.ready.poke(false.B)
        io.memoryRequest.ready.poke(true.B); io.memoryResponse.valid.poke(false.B);
        io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        io.renderRequest.bits.address.poke(0x400080.U); io.renderRequest.bits.transactionId.poke(2.U);
        io.renderRequest.bits.sizeLog2.poke(6.U)
        io.inputRequest.bits.address.poke(0x400040.U); io.inputRequest.bits.transactionId.poke(0.U);
        io.inputRequest.bits.sizeLog2.poke(6.U)
        var sendRender = true; var sendInput = true; var gotRender = false; var gotInput = false; var held = 0;
        var cycles = 0
        var heldBits: Option[BigInt] = None; var offered: Option[(Int, BigInt)] = None
        val pending = mutable.ArrayBuffer.empty[(Int, BigInt)]; val requests = mutable.ArrayBuffer.empty[(Int, BigInt)]
        while ((!gotRender || !gotInput || pending.nonEmpty || offered.nonEmpty) && cycles < 3000) {
          io.renderRequest.valid.poke(sendRender.B); io.inputRequest.valid.poke(sendInput.B)
          if (offered.isEmpty && pending.nonEmpty) offered = Some(pending.remove(pending.size - 1))
          io.memoryResponse.valid.poke(offered.nonEmpty.B)
          offered.foreach { case (id, data) =>
            io.memoryResponse.bits.transactionId.poke(id.U); io.memoryResponse.bits.readData.poke(data.U);
            io.memoryResponse.bits.fault.poke(false.B)
          }
          val hold = io.inputResponse.valid.peek().litToBoolean && held < 8
          io.inputResponse.ready.poke((!hold).B)
          if (io.inputResponse.valid.peek().litToBoolean) {
            val bits = io.inputResponse.bits.peek().litValue
            heldBits.foreach(x => assert(x == bits)); heldBits = if (hold) Some(bits) else None
            io.inputResponse.bits.transactionId.expect(0.U); io.inputResponse.bits.fault.expect(badInput.B)
            if (hold) { held += 1; io.drained.expect(false.B) }
            else {
              assert(!gotInput); gotInput = true
              if (!badInput) io.inputResponse.bits.readData.expect(0x900c.U)
            }
          } else assert(heldBits.isEmpty)
          if (io.renderResponse.valid.peek().litToBoolean) {
            assert(!gotRender); gotRender = true
            io.renderResponse.bits.transactionId.expect(2.U); io.renderResponse.bits.fault.expect(false.B);
            io.renderResponse.bits.readData.expect(0x9006.U)
          }
          val r = sendRender && io.renderRequest.ready.peek().litToBoolean
          val i = sendInput && io.inputRequest.ready.peek().litToBoolean
          if (io.memoryRequest.valid.peek().litToBoolean) {
            val id = io.memoryRequest.bits.transactionId.peek().litValue.toInt
            val addr = io.memoryRequest.bits.address.peek().litValue; requests += ((id, addr))
            if (id >= 16) {
              assert(Set(17, 18)(id) && addr == 0x30004, s"unexpected walk id=$id addr=$addr")
              pending += ((id, if (id == 18 && badInput) BigInt(0) else (BigInt(0x800) << 10) | 0x43))
            } else {
              assert(
                (id == 6 && addr == 0x800080) || (id == 12 && addr == 0x800040),
                s"wrong routing id=$id addr=$addr"
              )
              pending += ((id, BigInt(0x9000 + id)))
            }
          }
          if (offered.nonEmpty && io.memoryResponse.ready.peek().litToBoolean) offered = None
          d.clock.step(); if (r) sendRender = false; if (i) sendInput = false; cycles += 1
        }
        assert(cycles < 3000 && gotRender && gotInput && held == 8)
        io.renderRequest.valid.poke(false.B); io.inputRequest.valid.poke(false.B); io.memoryResponse.valid.poke(false.B)
        d.clock.step(3); io.drained.expect(true.B)
        assert(requests.exists(_._1 == 17) && requests.exists(_._1 == 18))
        assert(requests.exists(_._1 == 6) && (requests.exists(_._1 == 12) != badInput))
      }
    }
  it should "load a patch word through the translated fabric before reporting completion" in {
    simulate(new TessellationInputTranslatedHarness) { d =>
      d.io.satp.poke("h80000030".U); d.io.task.valid.poke(false.B); d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.completion.ready.poke(false.B); d.io.load.ready.poke(true.B); d.io.reply.valid.poke(false.B);
      d.io.reply.bits.poke(0.U.asTypeOf(d.io.reply.bits))
      d.io.memoryRequest.ready.poke(true.B); d.io.memoryResponse.valid.poke(false.B);
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val t = d.io.task.bits; t.taskId.poke(9.U); t.vertices.poke(1.U); t.components.poke(1.U); t.stride.poke(4.U)
      t.view.base.poke(0x400040.U); t.view.limit.poke(0x400044.U); t.view.allocationBase.poke(0x400040.U);
      t.view.allocationLimit.poke(0x400080.U)
      d.io.task.valid.poke(true.B); d.io.task.ready.expect(true.B); d.clock.step(); d.io.task.valid.poke(false.B)
      val replies = mutable.Queue.empty[(Int, BigInt)]; var offered: Option[(Int, BigInt)] = None;
      var patchReply = false; var loaded = false; var cycles = 0
      while (!d.io.completion.valid.peek().litToBoolean && cycles < 1000) {
        if (offered.isEmpty && replies.nonEmpty) offered = Some(replies.dequeue())
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach { case (id, data) =>
          d.io.memoryResponse.bits.transactionId.poke(id.U); d.io.memoryResponse.bits.readData.poke(data.U)
        }
        d.io.reply.valid.poke(patchReply.B)
        if (d.io.memoryRequest.valid.peek().litToBoolean) {
          val id = d.io.memoryRequest.bits.transactionId.peek().litValue.toInt
          val addr = d.io.memoryRequest.bits.address.peek().litValue
          if (id == 18) { assert(addr == 0x30004); replies.enqueue((id, (BigInt(0x800) << 10) | 0x43)) }
          else { assert(id == 12 && addr == 0x800040); replies.enqueue((id, BigInt("3f800000", 16))) }
        }
        if (offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) offered = None
        val load = d.io.load.valid.peek().litToBoolean && d.io.load.ready.peek().litToBoolean
        if (load) {
          assert(!loaded); loaded = true; d.io.load.bits.data.expect("h3f800000".U); d.io.load.bits.vertex.expect(0.U)
        }
        val reply = patchReply && d.io.reply.ready.peek().litToBoolean
        d.clock.step(); if (reply) patchReply = false; if (load) patchReply = true; cycles += 1
      }
      assert(cycles < 1000 && loaded && !patchReply && replies.isEmpty && offered.isEmpty)
      d.io.completion.bits.status.expect(0.U); d.io.completion.bits.words.expect(1.U)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.drained.expect(true.B)
    }
  }

}
