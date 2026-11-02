package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Actual shared-L2 owner, with descriptor and lower memory modeled externally. */
class ImageAtomicL2Harness(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool()); val task = Input(new ShaderImageTask)
    val in = Flipped(Decoupled(new ShaderImageExecuteRequest(config))); val commit = Decoupled(new opengpu.core.backend.VectorCommitRequest(config))
    val status = Output(UInt(3.W)); val imageOffer = Output(Bool())
    val descriptorRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val descriptorResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val other = Flipped(Decoupled(new SharedAtomicRequest(config))); val otherResult = Decoupled(new SharedAtomicResponse(config))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 8))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 8)))
    val drained = Output(Bool())
  })
  val image = Module(new ShaderImageAtomicVectorService(config))
  val translation = Module(new GlobalAtomicTranslation(config))
  val l2 = Module(new SharedL2Cache(config, sets = 4, ways = 1, banks = 1, numComputeUnits = 2, maxOutstanding = 8))
  image.io.kernelStart := io.kernelStart; image.io.kernelCompletion := false.B
  image.io.task := io.task; image.io.satp := 0.U; image.io.cancel := false.B
  image.io.warpStart.valid := false.B; image.io.warpStart.bits := 0.U
  image.io.flush.valid := false.B; image.io.flush.bits := 0.U.asTypeOf(image.io.flush.bits)
  image.io.in <> io.in; io.commit <> image.io.commit; io.status := image.io.status
  io.descriptorRequest <> image.io.descriptorMemoryRequest; image.io.descriptorMemoryResponse <> io.descriptorResponse
  image.io.imageMemoryRequest.ready := false.B; image.io.imageMemoryResponse.valid := false.B
  image.io.imageMemoryResponse.bits := 0.U.asTypeOf(image.io.imageMemoryResponse.bits)
  io.imageOffer := image.io.atomicRequest.valid
  translation.io.in <> image.io.atomicRequest; image.io.atomicResponse <> translation.io.out
  translation.io.satp := image.io.atomicSatp
  translation.io.pageWalkRequest.ready := false.B; translation.io.pageWalkResponse.valid := false.B
  translation.io.pageWalkResponse.bits := 0.U.asTypeOf(translation.io.pageWalkResponse.bits)
  l2.io.atomicRequest(0) <> translation.io.physicalRequest; translation.io.physicalResponse <> l2.io.atomicResponse(0)
  l2.io.atomicRequest(1) <> io.other; io.otherResult <> l2.io.atomicResponse(1)
  l2.io.request.valid := false.B; l2.io.request.bits := 0.U.asTypeOf(l2.io.request.bits); l2.io.response.ready := true.B
  l2.io.hostInvalidate.valid := false.B; l2.io.hostInvalidate.bits := 0.U.asTypeOf(l2.io.hostInvalidate.bits)
  l2.io.hostInvalidateDone.ready := true.B; l2.io.clearPerformanceCounters := false.B
  for (i <- 0 until 2) { l2.io.invalidate(i).ready := true.B; l2.io.invalidateDone(i).valid := false.B
    l2.io.invalidateDone(i).bits := 0.U.asTypeOf(l2.io.invalidateDone(i).bits) }
  io.memoryRequest <> l2.io.memoryRequest; l2.io.memoryResponse <> io.memoryResponse
  io.drained := l2.io.drained && translation.io.drained
}

class ShaderImageAtomicL2Spec extends AnyFlatSpec {
  behavior of "Image atomic shared L2"
  it should "serialize a descriptor-backed image atomic with a competing L2 atomic" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new ImageAtomicL2Harness(config)) { d =>
      WindowRecordTest.zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val fmt = TextureImageFormat.id("R32UI"); val control = (fmt << 6) | (1 << 5) | (1 << 3)
      val task = d.io.task; task.enabled.poke(true.B); task.stage.poke(GraphicsStage.compute.U)
      task.table.version.poke(1.U); task.table.base.poke(0x8000.U); task.table.limit.poke(0x8400.U)
      task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U); task.table.identity.generation.poke(13.U)
      d.io.kernelStart.poke(true.B); d.clock.step(); d.io.kernelStart.poke(false.B); task.enabled.poke(false.B)
      val words = Array.fill[BigInt](64)(0)
      for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
        3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
        12 -> fmt, 13 -> fmt, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x1000, 23 -> 0x1400,
        25 -> 0x1048, 26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) words(i) = v
      def issue(function: Int, subop: Int, rd: Int, lhs: Int, rhs: Int): Unit = {
        d.io.in.ready.expect(true.B); val q = d.io.in.bits; WindowRecordTest.zero(q)
        val insn = (BigInt(function) << 26) | (BigInt(1) << 25) | (BigInt(subop) << 12) | (BigInt(rd) << 7) | 0x2b
        q.issued.decode.instruction.poke(insn.U); q.issued.decode.warpId.poke(1.U); q.issued.decode.activeMask.poke(1.U)
        q.executionMask.poke(1.U); q.sideEffectMask.poke(1.U)
        q.issued.vs1Data(0).poke(lhs.U); q.issued.vs2Data(0).poke(rhs.U)
        d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); WindowRecordTest.zero(q)
      }
      def parameter(pair: Int, lhs: Int, rhs: Int): Unit = {
        issue(8, pair, 0, lhs, rhs); var n = 0
        while (!d.io.commit.valid.peek().litToBoolean && n < 100) { d.clock.step(); n += 1 }
        d.io.commit.valid.expect(true.B); d.io.status.expect(0.U)
        d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
      }
      parameter(0, 0, control); parameter(1, 0, 0); parameter(2, 3, 0)
      issue(12, ShaderImageAtomicOperation.add, 5, 0, 0)
      case class Descriptor(data: BigInt, due: Int)
      case class Lower(id: BigInt, data: BigInt, due: Int)
      val desc = mutable.Queue.empty[Descriptor]; val lower = mutable.Queue.empty[Lower]
      var memory = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(if (i == 8) 10 else if (i >= 8 && i < 12) 0 else 0x5a) << (8 * i)))
      val initial = memory
      var cycle = 0; var lines = 0; var armed = false; var sent = false; var otherDone = false
      var otherOld = BigInt(-1); var reads = 0; var writes = 0
      while (!(d.io.commit.valid.peek().litToBoolean && otherDone && d.io.drained.peek().litToBoolean) && cycle < 10000) {
        d.io.descriptorRequest.ready.poke((cycle % 3 != 0).B)
        val dr = desc.headOption.filter(_.due <= cycle); d.io.descriptorResponse.valid.poke(dr.nonEmpty.B)
        dr.foreach(r => { d.io.descriptorResponse.bits.transactionId.poke(0.U); d.io.descriptorResponse.bits.readData.poke(r.data.U) })
        if (dr.nonEmpty && d.io.descriptorResponse.ready.peek().litToBoolean) desc.dequeue()
        if (d.io.descriptorRequest.valid.peek().litToBoolean && d.io.descriptorRequest.ready.peek().litToBoolean) {
          d.io.descriptorRequest.bits.address.expect((0x8000 + lines * 64).U)
          val payload = words.slice(lines * 16, lines * 16 + 16).zipWithIndex.map { case (v, i) => v << (32 * i) }.sum
          lines += 1; desc.enqueue(Descriptor(payload, cycle + 3))
        }
        if (d.io.imageOffer.peek().litToBoolean) armed = true
        d.io.other.valid.poke((armed && !sent).B); d.io.other.bits.address.poke(0x1048.U); d.io.other.bits.warpId.poke(0.U)
        d.io.other.bits.operation.poke(AtomicMemoryOp.add); d.io.other.bits.operand.poke(2.U)
        if (armed && !sent && d.io.other.ready.peek().litToBoolean) sent = true
        d.io.otherResult.ready.poke((cycle % 3 != 0).B)
        if (d.io.otherResult.valid.peek().litToBoolean && d.io.otherResult.ready.peek().litToBoolean) {
          d.io.otherResult.bits.fault.expect(false.B); otherOld = d.io.otherResult.bits.oldValue.peek().litValue; otherDone = true
        }
        d.io.memoryRequest.ready.poke((cycle % 4 != 0).B)
        val lr = lower.headOption.filter(_.due <= cycle); d.io.memoryResponse.valid.poke(lr.nonEmpty.B)
        lr.foreach(r => { d.io.memoryResponse.bits.transactionId.poke(r.id.U); d.io.memoryResponse.bits.readData.poke(r.data.U) })
        if (lr.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean) lower.dequeue()
        if (d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean) {
          val m = d.io.memoryRequest.bits; m.address.expect(0x1040.U)
          val id = m.transactionId.peek().litValue; val data = memory
          if (m.isWrite.peek().litToBoolean) {
            val mask = m.byteMask.peek().litValue; val bits = m.writeData.peek().litValue
            for (i <- 0 until 64 if mask.testBit(i)) {
              val byteMask = BigInt(255) << (8 * i); memory = (memory & ~byteMask) | (bits & byteMask)
            }
            writes += 1
          } else reads += 1
          lower.enqueue(Lower(id, data, cycle + 5))
        }
        d.clock.step(); cycle += 1
      }
      d.io.commit.valid.expect(true.B); d.io.status.expect(0.U); d.io.commit.bits.writesVd.expect(true.B)
      val imageOld = d.io.commit.bits.writeback.data(0).peek().litValue
      assert((imageOld == 10 && otherOld == 13) || (imageOld == 12 && otherOld == 10))
      val word = (memory >> 64) & BigInt("ffffffff", 16); assert(word == 15)
      val mask = BigInt("ffffffff", 16) << 64; assert((memory & ~mask) == (initial & ~mask))
      assert(lines == 4 && sent && otherDone && reads >= 1 && writes == 2 && desc.isEmpty && lower.isEmpty)
      d.io.commit.ready.poke(true.B); d.clock.step(); d.io.commit.ready.poke(false.B)
    }
  }
}
