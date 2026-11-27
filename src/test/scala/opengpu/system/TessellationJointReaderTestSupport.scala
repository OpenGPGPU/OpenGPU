package opengpu.system

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics.GraphicsWorkIdentity
import opengpu.testutil.GpuSim._
import scala.collection.mutable

/** Literal LE memory fixtures; no shader execution or produced patch data. */
private[system] object TessellationJointReaderTestSupport {
  val config = GpuConfig(lanes = 16, warps = 8)
  val top = BigInt(1) << 32
  def zero(x: Data): Unit = x match {
    case r: Record => r.elements.values.foreach(zero); case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case _                                                             => ()
  }
  def identity(x: GraphicsWorkIdentity): Unit = { x.context.poke(7.U); x.pass.poke(8.U); x.generation.poke(9.U) }
  def view(x: TessellationJointView, base: BigInt, bytes: BigInt): Unit = {
    x.base.poke(base.U); x.limit.poke((base + bytes).U)
  }
  def request(x: TessellationJointJobRequest, domain: Int = 0, base: BigInt = 0x1000): Unit = {
    x.commandId.poke(5.U); x.domain.poke(domain.U); view(x.view, base, 512)
    x.view.allocationBase.poke(base.U); x.view.allocationLimit.poke((base + 1024).U)
    x.owner.base.poke(0.U); x.owner.limit.poke(top.U); identity(x.identity)
  }
  def job(
      domain: Int = 0,
      count: Int = 1,
      jobBase: BigInt = 0x1000,
      frameBase: BigInt = 0x2000,
      sequenceBase: BigInt = 0x4000,
      directoryBase: BigInt = 0x8000
  ): Array[BigInt] = {
    val w = Array.fill[BigInt](128)(0)
    w(0) = TessellationJointJobLayout.jobMagics(domain)
    TessellationJointJobLayout.header.zipWithIndex.foreach { case (v, i) => w(i + 1) = v }
    def wide(i: Int, v: BigInt): Unit = { w(i) = v & 0xffffffffL; w(i + 1) = v >> 32 }
    w(21) = frameBase; wide(22, frameBase + 2048); w(24) = 7; w(25) = 9; w(26) = 8
    if (count > 0) {
      w(16) = sequenceBase; w(17) = count; wide(18, sequenceBase + (count - 1) * 4096 + 2048); w(20) = 4096
      w(27) = 2; w(28) = directoryBase; wide(29, directoryBase + 64 + 64 * count)
      w(31) = count; w(32) = 64 + 64 * count; w(33) = 64; w(34) = 3; w(35) = 1
    }
    w
  }
  def captured(x: TessellationJointJobCapture, domain: Int = 0, count: Int = 1): Unit = {
    request(x.request, domain); val j = x.job
    identity(j.identity); view(j.frame, 0x2000, 2048)
    j.sequenceCount.poke(count.U)
    if (count > 0) {
      view(j.sequence, 0x4000, (count - 1) * 4096 + 2048); j.sequenceStride.poke(4096.U)
      j.directoryVersion.poke(2.U); view(j.directory, 0x8000, 64 + 64 * count); j.directoryCount.poke(count.U)
      j.directoryBytes.poke((64 + 64 * count).U); j.directoryEntryBytes.poke(64.U); j.drawCount.poke(3.U);
      j.invocationCount.poke(1.U)
    } else {
      j.sequence.base.poke(0.U); j.sequence.limit.poke(0.U); j.sequenceStride.poke(0.U)
      j.directoryVersion.poke(0.U); j.directory.base.poke(0.U); j.directory.limit.poke(0.U); j.directoryCount.poke(0.U)
      j.directoryBytes.poke(0.U); j.directoryEntryBytes.poke(0.U); j.drawCount.poke(0.U); j.invocationCount.poke(0.U)
    }
  }
  def directoryHeader(count: Int = 1, invocations: Int = 1): Array[BigInt] =
    Array[BigInt](
      TessellationJointJobLayout.directoryMagic,
      64 + 64 * count,
      2,
      64,
      3,
      invocations,
      count,
      7,
      8,
      9,
      16,
      16,
      16,
      8,
      2,
      0
    )
  def entry(
      index: Int = 0,
      id: BigInt = 0,
      flags: Int = 3,
      ordinal: Int = 0,
      firstInstance: Int = 0,
      instances: Int = 1,
      firstPatch: Int = 0,
      per: Int = 2,
      task: BigInt = 42,
      source: BigInt = 0xa000
  ): Array[BigInt] =
    Array[BigInt](
      index,
      2,
      id & 0xffffffffL,
      id >> 32,
      flags,
      ordinal,
      firstInstance,
      instances,
      firstPatch,
      per,
      task,
      instances * per,
      source,
      (source + 512) & 0xffffffffL,
      (source + 512) >> 32,
      0
    )
  class Memory(clock: Clock, req: DecoupledIO[ComputeMemoryRequest], resp: DecoupledIO[ComputeMemoryResponse]) {
    val lines = mutable.Map.empty[BigInt, BigInt]; val reads = mutable.ArrayBuffer.empty[BigInt]
    var holdRequest = false; var holdReply = false; var faultAt = Set.empty[BigInt]; var cycle = 0
    private var pending: Option[(BigInt, Int)] = None; private var held: Option[BigInt] = None
    var check: () => Unit = () => ()
    def put(base: BigInt, words: Array[BigInt]): Unit = {
      require(words.length % 16 == 0)
      for (line <- 0 until words.length / 16)
        lines(base + line * 64) = (0 until 16).foldLeft(BigInt(0))((v, i) => v | (words(line * 16 + i) << (32 * i)))
    }
    def clearReads(): Unit = { assert(pending.isEmpty && held.isEmpty); reads.clear() }
    def tick(): Unit = {
      req.ready.poke((!holdRequest && cycle % 3 != 0).B)
      val reply = pending.filter(p => !holdReply && p._2 <= cycle)
      resp.valid.poke(reply.nonEmpty.B)
      reply.foreach { case (address, _) =>
        resp.bits.transactionId.poke(0.U); resp.bits.fault.poke(faultAt(address).B)
        resp.bits.readData.poke(lines.getOrElse(address, BigInt(0)).U)
      }
      val valid = req.valid.peek().litToBoolean; val ready = req.ready.peek().litToBoolean
      held.foreach(v => assert(valid && req.bits.peek().litValue == v, "held memory offer changed"))
      held = if (valid && !ready) Some(req.bits.peek().litValue) else None
      val accepted = valid && ready
      if (accepted) {
        assert(pending.isEmpty, "tag zero reused before reply")
        req.bits.transactionId.expect(0.U); req.bits.isWrite.expect(false.B); req.bits.sizeLog2.expect(6.U)
        val address = req.bits.address.peek().litValue; assert((address & 63) == 0); reads += address
        pending = Some((address, cycle + 3))
      }
      val consumed = reply.nonEmpty && resp.ready.peek().litToBoolean
      check(); clock.step(); if (consumed) pending = None; cycle += 1
    }
    def until(label: String)(condition: => Boolean): Unit = {
      var n = 0; while (!condition && n < 4000) { tick(); n += 1 }; assert(condition, s"$label timed out")
    }
    def empty: Boolean = pending.isEmpty && held.isEmpty
  }
}
