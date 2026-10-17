package opengpu.graphics

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Fixed ABI fixture; checks below assert decoded fields independently. */
object TileDescriptorTestData {
  def words(count: Int = 2): Array[Long] = {
    val w = Array.fill[Long](64)(0)
    w(0) = 0x47545001L; w(1) = 64; w(2) = 33; w(3) = 44; w(4) = 55
    w(5) = 0x4000; w(6) = count; w(7) = 0x4000 + count * 160L
    w(9) = 16; w(10) = 16
    w(12) = 3 | (8 << 8) | (8 << 16)
    w(13) = 0x10000; w(14) = 0x20000; w(16) = 512; w(17) = 5; w(18) = 0x10203040L
    w(19) = 0x20000; w(20) = 0x30000; w(22) = 512; w(23) = 5; w(24) = 0x5affffffL
    w(25) = 0x30000; w(26) = 0x40000; w(28) = 512
    w(29) = 0x81
    w
  }
}

class TileJobDescriptorReaderSpec extends AnyFlatSpec {
  behavior of "bounded tile descriptor ownership"
  private class Driver(val d: TileJobDescriptorReader) {
    def zero(data: Data): Unit = data match {
      case r: Record => r.elements.values.foreach(zero)
      case v: Vec[_] => v.foreach(zero)
      case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
      case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
      case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
      case _ => ()
    }
    zero(d.io); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
    var id = 0
    def submit(base: Long = 0x1000, limit: Long = 0x1100, envelope: Boolean = true): Unit = {
      id += 1
      d.io.submit.bits.commandId.poke(id.U); d.io.submit.bits.base.poke(base.U)
      d.io.submit.bits.limit.poke(limit.U); d.io.submit.bits.envelopeValid.poke(envelope.B)
      d.io.submit.valid.poke(true.B); d.io.submit.ready.expect(true.B); d.clock.step()
      d.io.submit.valid.poke(false.B)
      d.io.submit.bits.poke(0.U.asTypeOf(d.io.submit.bits))
    }
    def waitFor(p: => Boolean): Unit = {
      var cycles = 0
      while (!p && cycles < 40) { d.clock.step(); cycles += 1 }
      assert(p, "descriptor phase timed out")
    }
    def returnLine(w: Array[Long], index: Int, fault: Boolean = false): Unit = {
      d.io.memoryResponse.bits.transactionId.poke(3.U)
      val line = (0 until 16).map(i => BigInt(w(index * 16 + i) & 0xffffffffL) << (32 * i)).foldLeft(BigInt(0))(_ | _)
      d.io.memoryResponse.bits.readData.poke(line.U); d.io.memoryResponse.bits.fault.poke(fault.B)
      d.io.memoryResponse.valid.poke(true.B)
      d.io.memoryResponse.ready.expect(true.B); d.clock.step(); d.io.memoryResponse.valid.poke(false.B)
    }
    def fetch(w: Array[Long] = TileDescriptorTestData.words(), base: Long = 0x1000, fault: Int = -1): Unit = {
      for (index <- 0 to (if (fault < 0) 3 else fault)) {
        waitFor(d.io.memoryRequest.valid.peek().litToBoolean)
        d.io.memoryRequest.bits.address.expect((base + index * 64).U)
        d.io.memoryRequest.bits.transactionId.expect(3.U)
        d.io.memoryRequest.bits.isWrite.expect(false.B)
        d.io.memoryRequest.bits.sizeLog2.expect(6.U)
        d.io.memoryRequest.bits.cachePolicy.expect(2.U)
        val offered = d.io.memoryRequest.bits.peek().litValue
        for (_ <- 0 until 3) { d.clock.step(); assert(d.io.memoryRequest.bits.peek().litValue == offered) }
        d.io.memoryRequest.ready.poke(true.B); d.clock.step(); d.io.memoryRequest.ready.poke(false.B)
        for (_ <- 0 until 2) { d.clock.step(); d.io.completion.valid.expect(false.B) }
        returnLine(w, index, fault == index)
      }
    }
    def coreResult(status: Int, reads: Int = 0): Unit = {
      d.io.job.valid.expect(true.B); d.io.job.ready.poke(true.B); d.clock.step(); d.io.job.ready.poke(false.B)
      for (_ <- 0 until reads) { d.io.commandReadAccepted.poke(true.B); d.clock.step() }
      d.io.commandReadAccepted.poke(false.B)
      d.io.passCompletion.bits.identity.context.poke(33.U)
      d.io.passCompletion.bits.identity.pass.poke(44.U)
      d.io.passCompletion.bits.identity.generation.poke(55.U)
      d.io.passCompletion.bits.status.poke(status.U)
      d.io.passCompletion.valid.poke(true.B); d.io.passCompletion.ready.expect(true.B)
      d.clock.step(); d.io.passCompletion.valid.poke(false.B)
    }
    def complete(status: Int, bytes: Int = 0): Unit = {
      waitFor(d.io.completion.valid.peek().litToBoolean)
      d.io.completion.bits.commandId.expect(id.U); d.io.completion.bits.pass.status.expect(status.U)
      d.io.completion.bits.commandBytesFetched.expect(bytes.U)
      val snapshot = d.io.completion.bits.peek().litValue
      d.io.cancel.poke(true.B)
      for (_ <- 0 until 5) {
        d.clock.step(); d.io.submit.ready.expect(false.B)
        assert(d.io.completion.bits.peek().litValue == snapshot)
      }
      d.io.cancel.poke(false.B); d.io.completion.ready.poke(true.B); d.clock.step()
      d.io.completion.ready.poke(false.B); d.io.submit.ready.expect(true.B)
    }
  }
  private def reader: TileJobDescriptorReader = new TileJobDescriptorReader(
    GpuConfig(lanes = 4, warps = 2), GraphicsConfig(screenWidth = 64, screenHeight = 64),
    memoryTransactions = 8, transactionId = 3)

  it should "snapshot four bounded lines, decode every state group, and retain the job and result" in {
    simulate(reader) { dut =>
      val h = new Driver(dut); val w = TileDescriptorTestData.words()
      w(8) = 1; w(7) = 0 // Exact 4 GiB command allocation limit.
      w(29) |= (1L << 16) | (1L << 17) | (2L << 8) | (6L << 4)
      w(30) = 1 | (2 << 3) | (3 << 6) | (4 << 9)
      w(31) = 0x563412; w(32) = 1 | (4 << 4) | (5 << 8) | (2 << 12)
      w(33) = 0x60000; w(34) = 127 | (61 << 16)
      w(35) = 1 | (9 << 2) | 256 | (3 << 12) | (29 << 16); w(36) = 2
      h.submit(); h.fetch(w); h.waitFor(dut.io.job.valid.peek().litToBoolean)
      val c = dut.io.job.bits.config; val p = dut.io.job.bits.passDescriptor
      dut.io.job.bits.version.expect(1.U); dut.io.job.bits.commandLimit.expect((BigInt(1) << 32).U)
      c.cmdBase.expect(0x4000.U); c.cmdCount.expect(2.U)
      p.identity.context.expect(33.U); p.identity.pass.expect(44.U); p.identity.generation.expect(55.U)
      p.color.base.expect(0x10000.U); p.color.limit.expect(0x20000.U); p.color.clearValue.expect(0x10203040L.U)
      p.depth.base.expect(0x20000.U); p.depth.limit.expect(0x30000.U); p.depth.clearValue.expect(0x5affffffL.U)
      p.color.loadOp.expect(1.U); p.color.store.expect(true.B); p.tileWidth.expect(8.U); p.tileLog2.expect(3.U)
      c.screenW.expect(16.U); c.screenH.expect(16.U); c.stride.expect(512.U)
      c.depthTestEnable.expect(true.B); c.depthWriteEnable.expect(true.B); c.depthFunc.expect(6.U)
      c.cullMode.expect(2.U); c.blendEnable.expect(true.B); c.stencilTestEnable.expect(true.B)
      c.stencilFunc.expect(1.U); c.stencilFailOp.expect(2.U); c.stencilZFailOp.expect(3.U); c.stencilZPassOp.expect(4.U)
      c.stencilRef.expect(0x12.U); c.stencilReadMask.expect(0x34.U); c.stencilWriteMask.expect(0x56.U)
      c.blendCfgEnable.expect(true.B); c.blendSrcFactor.expect(4.U); c.blendDstFactor.expect(5.U); c.blendEquation.expect(2.U)
      c.texBase.expect(0x60000.U); c.texWidth.expect(127.U); c.texHeight.expect(61.U)
      c.texEnable.expect(true.B); c.texWrapClamp.expect(true.B); c.texMaxLevel.expect(9.U)
      c.texMinLevel.expect(3.U); c.texLodBias.expect((-3).S); c.texFormat.expect(2.U)
      val snapshot = dut.io.job.bits.peek().litValue
      dut.io.memoryResponse.bits.readData.poke(0.U)
      for (_ <- 0 until 5) { dut.clock.step(); assert(dut.io.job.bits.peek().litValue == snapshot) }
      h.coreResult(TilePassStatus.success, reads = 7); h.complete(TilePassStatus.success, 28)
      h.submit(base = 0xffffff00L, limit = 1L << 32)
      h.fetch(base = 0xffffff00L); h.waitFor(dut.io.job.valid.peek().litToBoolean)
      h.coreResult(TilePassStatus.memoryFault); h.complete(TilePassStatus.memoryFault)
    }
  }

  it should "reject allocation, encoding and future-version errors without launching a partial job" in {
    simulate(reader) { dut =>
      val h = new Driver(dut)
      for ((base, limit, envelope) <- Seq((0x1004L,0x1200L,true),(0x1000L,0x10ffL,true),
          (0xffffffc0L,1L<<32,true),(0x1000L,(1L<<32)+1,true),(0x1000L,0x1100L,false))) {
        h.submit(base,limit,envelope); dut.clock.step(); dut.io.memoryRequest.valid.expect(false.B)
        h.complete(TilePassStatus.invalidDescriptor)
      }
      for ((index, bit) <- Seq((0,0),(1,0),(6,16),(8,1),(9,16),(10,16),(12,3),
          (15,1),(17,3),(21,1),(23,3),(27,1),(29,2),(30,12),(31,24),
          (32,1),(34,14),(35,1),(36,3),(37,0),(63,31))) {
        val w = TileDescriptorTestData.words(); w(index) ^= 1L << bit
        h.submit(); h.fetch(w); h.complete(TilePassStatus.invalidDescriptor)
        dut.io.job.valid.expect(false.B)
      }
      h.submit(); h.fetch(); h.waitFor(dut.io.job.valid.peek().litToBoolean)
      h.coreResult(0); h.complete(0)
    }
  }

  it should "attribute every line fault and drain cancellation through offered reads and jobs" in {
    simulate(reader) { dut =>
      val h = new Driver(dut)
      for (line <- 0 until 4) { h.submit(); h.fetch(fault = line); h.complete(TilePassStatus.commandFault) }
      h.submit(); dut.io.cancel.poke(true.B); dut.clock.step(); dut.io.cancel.poke(false.B)
      h.complete(TilePassStatus.cancelled)
      h.submit(); h.waitFor(dut.io.memoryRequest.valid.peek().litToBoolean)
      val offered = dut.io.memoryRequest.bits.peek().litValue
      dut.io.cancel.poke(true.B)
      for (_ <- 0 until 4) {
        dut.clock.step(); dut.io.memoryRequest.valid.expect(true.B)
        assert(dut.io.memoryRequest.bits.peek().litValue == offered); dut.io.completion.valid.expect(false.B)
      }
      dut.io.cancel.poke(false.B); dut.io.memoryRequest.ready.poke(true.B); dut.clock.step()
      dut.io.memoryRequest.ready.poke(false.B)
      for (_ <- 0 until 5) { dut.clock.step(); dut.io.completion.valid.expect(false.B) }
      h.returnLine(TileDescriptorTestData.words(),0); h.complete(TilePassStatus.cancelled)
      h.submit(); h.fetch(); h.waitFor(dut.io.job.valid.peek().litToBoolean)
      dut.io.cancel.poke(true.B); dut.clock.step(); dut.io.cancel.poke(false.B)
      for (_ <- 0 until 4) {
        dut.clock.step(); dut.io.job.valid.expect(true.B); dut.io.cancelCore.expect(true.B)
        dut.io.completion.valid.expect(false.B)
      }
      h.coreResult(TilePassStatus.cancelled); h.complete(TilePassStatus.cancelled)
    }
  }
}
