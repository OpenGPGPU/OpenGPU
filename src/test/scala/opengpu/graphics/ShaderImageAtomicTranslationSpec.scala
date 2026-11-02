package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

/** Test-only composition of the service with the existing shared owner and
  * translator. The physical atomic responder is still a test model, not L2.
  */
class ImageAtomicTranslationHarness(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool()); val task = Input(new ShaderImageTask); val satp = Input(UInt(32.W))
    val in = Flipped(Decoupled(new ShaderImageAtomicInstructionRequest(config)))
    val out = Decoupled(new ShaderImageAtomicResourceResult)
    val descriptorRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val descriptorResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val other = Flipped(Decoupled(new SharedAtomicRequest(config)))
    val otherResult = Decoupled(new SharedAtomicResponse(config))
    val imageOffer = Output(Bool())
    val walk = Decoupled(new VectorPageWalkRequest(config)); val walkRoot = Output(UInt(20.W))
    val walkResult = Flipped(Decoupled(new VectorPageWalkResponse(config)))
    val physical = Decoupled(new SharedAtomicRequest(config))
    val physicalResult = Flipped(Decoupled(new SharedAtomicResponse(config)))
  })
  val service = Module(new ShaderImageAtomicCuService(config))
  val arbiter = Module(new CounterAtomicArbiter(config))
  val translator = Module(new GlobalAtomicTranslation(config))
  service.io.kernelStart := io.kernelStart; service.io.kernelCompletion := false.B
  service.io.task := io.task; service.io.satp := io.satp
  service.io.flush.valid := false.B; service.io.flush.bits := 0.U.asTypeOf(service.io.flush.bits)
  service.io.cancel := false.B; service.io.in <> io.in; io.out <> service.io.out
  io.descriptorRequest <> service.io.memoryRequest; service.io.memoryResponse <> io.descriptorResponse
  io.imageOffer := service.io.atomicRequest.valid
  arbiter.io.in(0) <> io.other; io.otherResult <> arbiter.io.out(0); arbiter.io.satp(0) := 0.U
  arbiter.io.in(1) <> service.io.atomicRequest; service.io.atomicResponse <> arbiter.io.out(1)
  arbiter.io.satp(1) := service.io.atomicSatp
  translator.io.in <> arbiter.io.request; arbiter.io.response <> translator.io.out
  translator.io.satp := arbiter.io.requestSatp
  io.walk <> translator.io.pageWalkRequest; translator.io.pageWalkResponse <> io.walkResult
  io.walkRoot := translator.io.pageWalkRootPpn
  io.physical <> translator.io.physicalRequest; translator.io.physicalResponse <> io.physicalResult
}

class ShaderImageAtomicTranslationSpec extends AnyFlatSpec {
  behavior of "Image atomic shared translation"
  for (deny <- Seq(false, true)) {
    it should s"retain image and competing atomic ownership with denied=$deny" in {
      val config = GpuConfig(lanes = 4, warps = 2)
      simulate(new ImageAtomicTranslationHarness(config)) { d =>
        WindowRecordTest.zero(d.io); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        val fmt = TextureImageFormat.id("R32UI")
        val task = d.io.task; task.enabled.poke(true.B); task.stage.poke(GraphicsStage.compute.U)
        task.table.version.poke(1.U); task.table.base.poke(0x8000.U); task.table.limit.poke(0x8400.U)
        task.table.identity.context.poke(7.U); task.table.identity.pass.poke(11.U); task.table.identity.generation.poke(13.U)
        d.io.satp.poke(BigInt("80000090", 16).U); d.io.kernelStart.poke(true.B); d.clock.step(); d.io.kernelStart.poke(false.B)
        task.enabled.poke(false.B); d.io.satp.poke(0.U)
        val q = d.io.in.bits; q.tag.poke(44.U); q.unitIndex.poke(0.U); q.shaderTarget.poke(0.U)
        q.colorType.poke(1.U); q.hasFormatQualifier.poke(true.B); q.shaderFormat.poke(fmt.U)
        q.warpId.poke(1.U); q.operation.poke(ShaderImageAtomicOperation.add.U); q.operand.poke(3.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
        val words = Array.fill[BigInt](64)(0)
        for ((i, v) <- Seq[(Int, BigInt)](0 -> BigInt("47495501", 16), 1 -> 256, 2 -> 1,
          3 -> 7, 4 -> 11, 5 -> 13, 6 -> 0, 7 -> 1, 8 -> 1, 9 -> 4, 10 -> 1, 11 -> 1,
          12 -> fmt, 13 -> fmt, 14 -> 1, 17 -> 15, 18 -> 1, 22 -> 0x1000, 23 -> 0x1400,
          25 -> 0x1048, 26 -> 1, 27 -> 1, 28 -> 1, 29 -> 1, 30 -> 64, 37 -> 4)) words(i) = v
        case class Reply(id: BigInt, data: BigInt, due: Int)
        val replies = mutable.Queue.empty[Reply]
        var cycle = 0; var descriptorLines = 0; var otherArmed = false; var otherSent = false; var otherDone = false
        var physicalImage = 0; var physicalOther = 0; var walkDue = -1; var physicalDue = -1
        var responseWarp = 0; var imageWalks = 0
        while (!(d.io.out.valid.peek().litToBoolean && otherDone) && cycle < 10000) {
          d.io.descriptorRequest.ready.poke((cycle % 3 != 0).B)
          val reply = replies.headOption.filter(_.due <= cycle)
          d.io.descriptorResponse.valid.poke(reply.nonEmpty.B)
          reply.foreach { r => d.io.descriptorResponse.bits.transactionId.poke(r.id.U)
            d.io.descriptorResponse.bits.readData.poke(r.data.U) }
          if (reply.nonEmpty && d.io.descriptorResponse.ready.peek().litToBoolean) replies.dequeue()
          if (d.io.descriptorRequest.valid.peek().litToBoolean && d.io.descriptorRequest.ready.peek().litToBoolean) {
            val m = d.io.descriptorRequest.bits; val id = m.transactionId.peek().litValue; val a = m.address.peek().litValue
            val data = if (id == 1) { assert(a == 0x90000); (BigInt(0x400) << 10) | 0xc3 }
            else { assert(id == 0 && a == 0x408000 + descriptorLines * 64); val n = descriptorLines; descriptorLines += 1
              words.slice(n * 16, n * 16 + 16).zipWithIndex.map { case (v, i) => v << (32 * i) }.sum }
            replies.enqueue(Reply(id, data, cycle + 3))
          }
          if (d.io.imageOffer.peek().litToBoolean) otherArmed = true
          val issueOther = otherArmed && !otherSent
          d.io.other.valid.poke(issueOther.B); d.io.other.bits.warpId.poke(0.U); d.io.other.bits.address.poke(0x3000.U)
          d.io.other.bits.operation.poke(AtomicMemoryOp.add); d.io.other.bits.operand.poke(5.U)
          if (issueOther && d.io.other.ready.peek().litToBoolean) otherSent = true
          d.io.otherResult.ready.poke((cycle % 3 != 0).B)
          if (d.io.otherResult.valid.peek().litToBoolean && d.io.otherResult.ready.peek().litToBoolean) {
            d.io.otherResult.bits.warpId.expect(0.U); d.io.otherResult.bits.oldValue.expect(20.U); otherDone = true
          }
          d.io.walk.ready.poke((cycle % 2 == 0).B)
          if (d.io.walk.valid.peek().litToBoolean && d.io.walk.ready.peek().litToBoolean) {
            d.io.walk.bits.virtualPageNumber.expect(1.U); d.io.walk.bits.isStore.expect(true.B)
            d.io.walkRoot.expect(0x90.U); imageWalks += 1; walkDue = cycle + 4
          }
          val walkReply = walkDue >= 0 && cycle >= walkDue
          d.io.walkResult.valid.poke(walkReply.B); d.io.walkResult.bits.physicalPageNumber.poke(0x501.U)
          d.io.walkResult.bits.readable.poke(true.B); d.io.walkResult.bits.writable.poke((!deny).B)
          if (walkReply && d.io.walkResult.ready.peek().litToBoolean) walkDue = -1
          d.io.physical.ready.poke((cycle % 4 != 0).B)
          if (d.io.physical.valid.peek().litToBoolean && d.io.physical.ready.peek().litToBoolean) {
            assert(physicalDue < 0); val warp = d.io.physical.bits.warpId.peek().litValue.toInt
            if (warp == 1) { d.io.physical.bits.address.expect(0x501048.U); d.io.physical.bits.operand.expect(3.U); physicalImage += 1 }
            else { assert(warp == 0); d.io.physical.bits.address.expect(0x3000.U); physicalOther += 1 }
            responseWarp = warp; physicalDue = cycle + 5
          }
          val response = physicalDue >= 0 && cycle >= physicalDue
          d.io.physicalResult.valid.poke(response.B); d.io.physicalResult.bits.warpId.poke(responseWarp.U)
          d.io.physicalResult.bits.oldValue.poke((if (responseWarp == 1) 41 else 20).U)
          if (response && d.io.physicalResult.ready.peek().litToBoolean) physicalDue = -1
          d.clock.step(); cycle += 1
        }
        d.io.out.valid.expect(true.B); d.io.out.bits.tag.expect(44.U)
        d.io.out.bits.status.expect((if (deny) ShaderImageAccessStatus.memory else ShaderImageAccessStatus.ok).U)
        if (deny) { d.io.out.bits.pageFault.expect(true.B); d.io.out.bits.faultAddress.expect(0x1048.U) }
        else d.io.out.bits.value.expect(41.U)
        assert(descriptorLines == 4 && imageWalks == 1 && otherDone && physicalOther == 1 && physicalImage == (if (deny) 0 else 1))
        assert(replies.isEmpty && walkDue < 0 && physicalDue < 0)
      }
    }
  }
}
