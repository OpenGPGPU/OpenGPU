package opengpu.graphics

import chisel3._
import opengpu.config.GpuConfig
import opengpu.graphics.WindowRecordTest._
import opengpu.system._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Independent little-endian record words, not the reader's Field encoder. */
class TessellationVertexSourceReaderSpec extends AnyFlatSpec {
  private class Driver(val d: TessellationVertexSourceReader) {
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    def until(done: => Boolean): Unit = {
      var n = 0; while (!done && n < 30) { d.clock.step(); n += 1 }; assert(done, "GVS reader stalled")
    }
    def record(): Array[BigInt] = {
      val w = Array.fill(128)(BigInt(0))
      val header = Seq(
        BigInt("47565301", 16),
        BigInt(512),
        BigInt(1),
        BigInt(16),
        BigInt(16),
        BigInt(16),
        BigInt(64),
        BigInt(32),
        BigInt(16),
        BigInt(8),
        BigInt(2)
      )
      header.zipWithIndex.foreach { case (v, i) => w(i) = v }
      Seq(
        16 -> BigInt(7),
        17 -> BigInt(11),
        18 -> BigInt(9),
        19 -> BigInt(3),
        20 -> BigInt(4),
        21 -> BigInt(55),
        22 -> BigInt(6),
        24 -> BigInt(0x4000),
        25 -> BigInt(0x4800),
        27 -> BigInt(0x5000),
        28 -> BigInt(0x5100),
        30 -> BigInt(0x5010),
        32 -> BigInt(1),
        33 -> BigInt(5),
        34 -> BigInt(3),
        35 -> BigInt(3),
        36 -> BigInt(2),
        40 -> BigInt(32),
        41 -> BigInt(64),
        42 -> BigInt(32),
        43 -> BigInt(60),
        44 -> BigInt(120),
        45 -> BigInt(2)
      )
        .foreach { case (i, v) => w(i) = v }
      for (i <- 0 until 64) w(64 + i) = BigInt(1 | (i << 2))
      w
    }
    def request(w: Array[BigInt], base: BigInt = 0x2000, privateLimit: BigInt = 0x10000): Unit = {
      val q = d.io.submit.bits; zero(q)
      q.view.base.poke(base.U); q.view.limit.poke((base + 512).U)
      q.view.allocationBase.poke((base & ~BigInt(63)).U); q.view.allocationLimit.poke(((base + 575) & ~BigInt(63)).U)
      q.privateBase.poke(0x1000.U); q.privateLimit.poke(privateLimit.U)
      q.identity.context.poke(w(16).U); q.identity.pass.poke(w(17).U); q.identity.generation.poke(w(18).U)
      q.sequenceIndex.poke(w(19).U); q.drawIndex.poke(w(20).U); q.taskIdBase.poke(w(21).U); q.patchCount.poke(w(22).U)
      q.layout.inputVertices.poke((w(40) & 63).U); q.layout.inputComponents.poke((w(41) & 127).U)
      q.layout.outputVertices.poke((w(42) & 63).U); q.layout.outputComponents.poke((w(43) & 127).U)
      q.layout.patchComponents.poke((w(44) & 127).U)
    }
    def begin(): Unit = {
      d.io.submit.valid.poke(true.B); until(d.io.submit.ready.peek().litToBoolean)
      d.clock.step(); d.io.submit.valid.poke(false.B); zero(d.io.submit.bits)
    }
    def offer(address: BigInt): Unit = {
      until(d.io.memoryRequest.valid.peek().litToBoolean)
      d.io.memoryRequest.bits.address.expect(address.U); d.io.memoryRequest.bits.transactionId.expect(0.U)
      d.io.memoryRequest.bits.isWrite.expect(false.B); d.io.memoryRequest.bits.sizeLog2.expect(6.U)
      d.io.memoryRequest.bits.byteMask.expect(((BigInt(1) << 64) - 1).U)
    }
    def accept(): Unit = {
      d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
    }
    def reply(w: Array[BigInt], line: Int, fault: Boolean = false): Unit = {
      val data = (0 until 16).foldLeft(BigInt(0))((v, i) => v | (w(16 * line + i) << (32 * i)))
      d.io.memoryResponse.bits.readData.poke(data.U); d.io.memoryResponse.bits.fault.poke(fault.B)
      d.io.memoryResponse.valid.poke(true.B); until(d.io.memoryResponse.ready.peek().litToBoolean)
      d.clock.step(); d.io.memoryResponse.valid.poke(false.B); zero(d.io.memoryResponse.bits)
    }
    def read(w: Array[BigInt], base: BigInt = 0x2000): Unit = for (line <- 0 until 8) {
      offer(base + 64 * line); accept(); reply(w, line)
    }
    def complete(status: Int, bytes: Int = 512): Unit = {
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.status.expect(status.U); d.io.completion.bits.readBytes.expect(bytes.U)
      if (status != 0) assert(d.io.completion.bits.root.peek().litValue == 0)
      val held = d.io.completion.bits.peek().litValue
      d.clock.step(3); assert(d.io.completion.bits.peek().litValue == held); d.io.completion.valid.expect(true.B)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.completion.ready.poke(false.B); d.io.busy.expect(false.B)
    }
  }
  "GVS1 root reader" should "capture all 64 linked maps and exact wide bounds from eight immutable lines" in {
    simulate(new TessellationVertexSourceReader(GpuConfig())) { d =>
      val x = new Driver(d); val w = x.record(); x.request(w); x.begin(); x.read(w)
      x.until(d.io.completion.valid.peek().litToBoolean); val r = d.io.completion.bits.root
      r.identity.context.expect(7.U); r.identity.pass.expect(11.U); r.identity.generation.expect(9.U)
      r.sequenceIndex.expect(3.U); r.drawIndex.expect(4.U); r.taskIdBase.expect(55.U); r.patchCount.expect(6.U)
      r.drawBase.expect(0x4000.U); r.drawLimit.expect(0x4800.U); r.codeBase.expect(0x5000.U)
      r.codeLimit.expect(0x5100.U); r.entry.expect(0x5010.U); r.sliceVersion.expect(1.U)
      r.firstOrdinal.expect(5.U); r.patchesPerInstance.expect(3.U); r.firstInstance.expect(3.U);
      r.instanceCount.expect(2.U)
      r.layout.inputVertices.expect(32.U); r.layout.inputComponents.expect(64.U)
      r.layout.outputVertices.expect(32.U); r.layout.outputComponents.expect(60.U);
      r.layout.patchComponents.expect(120.U)
      r.vertexAbi.expect(2.U)
      for (i <- 0 until 64) { r.components(i).kind.expect(1.U); r.components(i).component.expect(i.U) }
      x.complete(0)
      // An end exactly at 2^32 must retain its carry, not wrap to zero.
      val high = x.record(); high(24) = BigInt("fffff800", 16); high(25) = 0; high(26) = 1
      x.request(high, privateLimit = BigInt(1) << 32); x.begin(); x.read(high)
      x.until(d.io.completion.valid.peek().litToBoolean); r.drawLimit.expect((BigInt(1) << 32).U); x.complete(0)
    }
  }
  it should "reject reserved bits, stale scope, invalid maps and coupled/wide overflow before exposing a root" in {
    simulate(new TessellationVertexSourceReader(GpuConfig())) { d =>
      val x = new Driver(d)
      val mutations = Seq[Array[BigInt] => Unit](
        w => w(0) = 0,
        w => w(10) = 1,
        w => w(15) = 1,
        w => w(26) = 2,
        w => w(40) = 96,
        w => w(64) = BigInt(1) << 9,
        w => w(127) = 3,
        w => w(127) = 1 | (64 << 2),
        w => w(43) = 61,
        w => { w(34) = 65536; w(36) = 65536; w(22) = 0 },
        w => w(21) = BigInt("ffffffff", 16),
        w => w(33) = BigInt("ffffffff", 16),
        w => w(35) = BigInt("ffffffff", 16),
        w => w(25) = 0x47fc,
        w => w(30) = 0x5100,
        w => w(27) = 0x800,
        w => w(28) = 0x10004,
        w => { w(41) = 63; w(127) = 1 },
        w => { w(45) = 1; w(64) = 2 }
      )
      for (mutate <- mutations) {
        val original = x.record(); val changed = original.clone(); mutate(changed)
        // Scope follows the mutated record except for the separate stale test.
        x.request(changed); x.begin(); x.read(changed); x.complete(1)
      }
      val stale = x.record(); x.request(stale); d.io.submit.bits.identity.generation.poke(10.U)
      x.begin(); x.read(stale); x.complete(1)
      val badView = x.record(); x.request(badView, base = 0x2004); x.begin(); x.complete(1, 0)
      x.request(badView); d.io.submit.bits.view.limit.poke(0x2240.U)
      d.io.submit.bits.view.allocationLimit.poke(0x2240.U)
      x.begin(); x.complete(1, 0) // Logical GVS view must be exactly 512 bytes.
      x.request(badView); d.io.submit.bits.view.allocationLimit.poke(0x2240.U)
      x.begin(); x.read(badView); x.complete(0) // Its allocation envelope may be larger.
      val empty = x.record(); Seq(22, 33, 34, 35, 36).foreach(i => empty(i) = 0)
      x.request(empty); x.begin(); x.read(empty); x.complete(0)
    }
  }
  it should "retain held reads and real late responses on cancellation, preserve memory faults, and reuse without reset" in {
    simulate(new TessellationVertexSourceReader(GpuConfig())) { d =>
      val x = new Driver(d); val w = x.record(); x.request(w); x.begin(); x.offer(0x2000)
      val held = d.io.memoryRequest.bits.peek().litValue
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B); d.clock.step(3)
      d.io.memoryRequest.valid.expect(true.B); assert(d.io.memoryRequest.bits.peek().litValue == held)
      d.io.completion.valid.expect(false.B); x.accept(); d.clock.step(3); d.io.completion.valid.expect(false.B)
      x.reply(w, 0); x.complete(4, 64)
      x.request(w); x.begin(); x.offer(0x2000); x.accept()
      d.io.cancel.poke(true.B); d.clock.step(); d.io.cancel.poke(false.B)
      x.reply(w, 0, fault = true); x.until(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.faultAddress.expect(0x2000.U); x.complete(2, 64)
      x.request(w); x.begin(); x.read(w); x.complete(0)
    }
  }
}
