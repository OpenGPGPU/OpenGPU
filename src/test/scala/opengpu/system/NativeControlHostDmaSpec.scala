package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.{ComputeResourceProfile, GpuConfig}
import opengpu.graphics.{GpuCommandMmioRegs => M, RenderHostRegs => R}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

class NativeControlHostDmaSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _ => ()
  }
  it should "retain native ownership, all legacy DMA engines and independent scanout" in {
    val gpu = GpuConfig()
    simulate(new GpuHostAxi(gpuConfig = gpu, unifiedCommandMmio = true,
      protectVmState = true, externalBinnedRender = true, nativeCompletionInfo = true,
      nativeVertexInput = true, visibilityQueries = true, transformFeedback = true,
      primitiveQueries = true, shaderExecutionLimits = true, enableIndirectCompute = true,
      computeResources = Some(ComputeResourceProfile(gpu, 4, 32)))) { d =>
      zero(d.io)
      d.io.s_axi_aresetn.poke(false.B); d.clock.step(3)
      d.io.s_axi_aresetn.poke(true.B); d.clock.step()
      d.io.vmContextIdle.get.poke(true.B)
      d.io.graphicsDependencyIdle.get.poke(true.B)
      d.io.binDrained.get.poke(true.B)
      def until(condition: => Boolean): Unit = {
        var cycles = 0
        while (!condition && cycles < 128) { d.clock.step(); cycles += 1 }
        assert(condition, "control transaction stalled")
      }
      def write(address: Int, data: BigInt, response: Int = 0): Unit = {
        d.io.s_axi_awaddr.poke(address.U); d.io.s_axi_awlen.poke(0.U)
        d.io.s_axi_awsize.poke(2.U); d.io.s_axi_awburst.poke(1.U)
        d.io.s_axi_awvalid.poke(true.B)
        until(d.io.s_axi_awready.peek().litToBoolean); d.clock.step()
        d.io.s_axi_awvalid.poke(false.B)
        d.io.s_axi_wdata.poke(data.U); d.io.s_axi_wstrb.poke(15.U)
        d.io.s_axi_wlast.poke(true.B); d.io.s_axi_wvalid.poke(true.B)
        until(d.io.s_axi_wready.peek().litToBoolean); d.clock.step()
        d.io.s_axi_wvalid.poke(false.B)
        until(d.io.s_axi_bvalid.peek().litToBoolean)
        for (_ <- 0 until 3) { d.io.s_axi_bresp.expect(response.U); d.clock.step() }
        d.io.s_axi_bready.poke(true.B); d.clock.step(); d.io.s_axi_bready.poke(false.B)
      }
      def read(address: Int): BigInt = {
        d.io.s_axi_araddr.poke(address.U); d.io.s_axi_arlen.poke(0.U)
        d.io.s_axi_arsize.poke(2.U); d.io.s_axi_arburst.poke(1.U)
        d.io.s_axi_arvalid.poke(true.B)
        until(d.io.s_axi_arready.peek().litToBoolean); d.clock.step()
        d.io.s_axi_arvalid.poke(false.B)
        until(d.io.s_axi_rvalid.peek().litToBoolean)
        val value = d.io.s_axi_rdata.peek().litValue
        for (_ <- 0 until 3) { d.io.s_axi_rdata.expect(value.U); d.io.s_axi_rresp.expect(0.U); d.clock.step() }
        d.io.s_axi_rready.poke(true.B); d.clock.step(); d.io.s_axi_rready.poke(false.B)
        value
      }
      def idleLegacy(): Unit = {
        d.io.cbMem.req.valid.expect(false.B); d.io.fbMem.req.valid.expect(false.B)
        d.io.texMem.req.valid.expect(false.B); d.io.kernelMemReq.valid.expect(false.B)
        d.io.kernelGlobalAtomicRequest.valid.expect(false.B)
      }
      write(R.CMD_BASE, 0x8000); write(R.CMD_COUNT, 17); write(R.CONTROL, 1)
      d.clock.step(8); idleLegacy(); assert((read(R.STATUS) & 1) == 0)
      write(R.CLEAR_BASE, 0x2000); write(R.CLEAR_BYTES, 128); write(R.CLEAR_PATTERN, 0x12345678)
      write(M.COMMAND_ID, 23); write(M.OPCODE, 7)
      write(M.SOURCE, 0x10000); write(M.DESTINATION, 0x10200)
      write(M.BYTES, 512); write(M.SUBMIT, 1)
      until(d.io.binSubmit.get.valid.peek().litToBoolean)
      val native = d.io.binSubmit.get
      for (_ <- 0 until 7) {
        native.valid.expect(true.B); native.bits.commandId.expect(23.U)
        native.bits.base.expect(0x10000.U); native.bits.limit.expect(0x10200.U)
        native.bits.envelopeValid.expect(true.B); d.clock.step()
      }
      write(R.CLEAR_START, 1, 2)
      d.io.kernelWordMemReq.valid.expect(false.B)
      native.ready.poke(true.B); d.clock.step(); native.ready.poke(false.B)
      d.io.binDrained.get.poke(false.B)
      write(R.BLIT_START, 1, 2); write(R.STRIDED_START, 1, 2)
      write(M.VECTOR_SATP, 0, 2); idleLegacy()
      d.io.binDrained.get.poke(true.B)
      d.io.binCompletion.get.bits.commandId.poke(23.U)
      d.io.binCompletion.get.valid.poke(true.B)
      until(d.io.binCompletion.get.ready.peek().litToBoolean)
      d.clock.step(); d.io.binCompletion.get.valid.poke(false.B)
      var completion = read(M.COMPLETION)
      var polls = 0
      while (!completion.testBit(15) && polls < 16) { completion = read(M.COMPLETION); polls += 1 }
      assert((completion & 255) == 23 && completion.testBit(15))
      write(M.VECTOR_SATP, 0, 2)
      write(M.COMPLETION_POP, 1); write(R.IRQ, 3)
      write(M.VECTOR_SATP, 0)

      val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0xa7)
      val expected = mutable.Map.empty[BigInt, Int].withDefaultValue(0xa7)
      for (a <- 0x2000 until 0x3100) { memory(BigInt(a)) = 0xa7; expected(BigInt(a)) = 0xa7 }
      for (a <- 0x4000 until 0x5300) {
        val value = if (a < 0x4400) (a * 17 + 31) & 255 else 0xa7
        memory(BigInt(a)) = value; expected(BigInt(a)) = value
      }
      var transactions = 0
      def exactMemory(): Unit = {
        assert(memory.keySet == expected.keySet, "DMA wrote outside every guarded allocation")
        for ((a, value) <- expected)
          assert(memory(a) == value,
            s"DMA byte ${a.toString(16)} actual=${memory(a)} expected=$value")
      }
      def dma(): Unit = {
        case class Reply(id: BigInt, data: BigInt, due: Int)
        val replies = mutable.Queue.empty[Reply]
        var cycles = 0; var quiet = 0
        while (cycles < 4000 && quiet < 20) {
          d.io.kernelWordMemReq.ready.poke((cycles % 5 != 1).B)
          val head = replies.headOption.filter(_.due <= cycles)
          d.io.kernelWordMemResp.valid.poke(head.nonEmpty.B)
          d.io.kernelWordMemResp.bits.fault.poke(false.B)
          d.io.kernelWordMemResp.bits.transactionId.poke(head.map(_.id).getOrElse(BigInt(0)).U)
          d.io.kernelWordMemResp.bits.readData.poke(head.map(_.data).getOrElse(BigInt(0)).U)
          val request = d.io.kernelWordMemReq
          var progress = false
          if (request.valid.peek().litToBoolean && request.ready.peek().litToBoolean) {
            progress = true; transactions += 1
            val q = request.bits
            val address = q.address.peek().litValue
            q.sizeLog2.expect(6.U)
            val line = (0 until 64).foldLeft(BigInt(0))((v, b) => v | (BigInt(memory(address + b)) << (8 * b)))
            if (q.isWrite.peek().litToBoolean) {
              val mask = q.byteMask.peek().litValue; val value = q.writeData.peek().litValue
              for (b <- 0 until 64 if mask.testBit(b)) memory(address + b) = ((value >> (8 * b)) & 255).toInt
            }
            replies.enqueue(Reply(q.transactionId.peek().litValue, line, cycles + 11))
          }
          if (head.nonEmpty && d.io.kernelWordMemResp.ready.peek().litToBoolean) {
            replies.dequeue(); progress = true
          }
          quiet = if (progress || replies.nonEmpty || request.valid.peek().litToBoolean) 0 else quiet + 1
          idleLegacy(); d.clock.step(); cycles += 1
        }
        d.io.kernelWordMemReq.ready.poke(false.B); d.io.kernelWordMemResp.valid.poke(false.B)
        assert(cycles < 4000 && replies.isEmpty)
        assert((read(R.STATUS) & 0x39) == 0, "DMA/render owner did not retire")
      }
      write(R.CLEAR_START, 1); dma()
      for (b <- 0 until 128) expected(BigInt(0x2000 + b)) = ((BigInt(0x12345678) >> ((b % 4) * 8)) & 255).toInt
      exactMemory()
      write(R.BLIT_SRC_BASE, 0x2000); write(R.BLIT_DST_BASE, 0x3000)
      write(R.BLIT_BYTES, 128); write(R.BLIT_START, 1); dma()
      for (b <- 0 until 128) expected(BigInt(0x3000 + b)) = expected(BigInt(0x2000 + b))
      exactMemory()
      val beforeInvalid = transactions
      write(R.STRIDED_SRC_BASE, 0x4005); write(R.STRIDED_DST_BASE, 0x5007)
      write(R.STRIDED_WIDTH, 20); write(R.STRIDED_HEIGHT, 3)
      write(R.STRIDED_SRC_STRIDE, 40); write(R.STRIDED_DST_STRIDE, 48)
      write(R.STRIDED_START, 1); dma()
      assert(transactions == beforeInvalid && (read(R.STATUS) & 4) != 0,
        "unaligned strided descriptor must fail without issuing memory traffic")
      exactMemory(); write(R.STATUS, 4)
      write(R.STRIDED_SRC_BASE, 0x4000); write(R.STRIDED_DST_BASE, 0x5000)
      write(R.STRIDED_WIDTH, 64); write(R.STRIDED_HEIGHT, 3)
      write(R.STRIDED_SRC_STRIDE, 128); write(R.STRIDED_DST_STRIDE, 192)
      write(R.STRIDED_START, 1); dma()
      for (y <- 0 until 3; b <- 0 until 64)
        expected(BigInt(0x5000 + y * 192 + b)) = expected(BigInt(0x4000 + y * 128 + b))
      exactMemory(); assert((read(R.STATUS) & 4) == 0)
      assert(transactions >= 8)
      write(R.SCANOUT_BASE, 0x100000); write(R.SCANOUT_STRIDE, 76)
      write(R.SCANOUT_WIDTH, 19); write(R.SCANOUT_HEIGHT, 11)
      write(R.SCANOUT_PERIOD, 17); write(R.IRQ, 3); write(R.SCANOUT_CONTROL, 3)
      d.clock.step(40)
      assert(read(R.SCANOUT_STATUS) == 1 && (read(R.IRQ) & 4) != 0)
      d.io.m_irq.expect(true.B)
      write(R.SCANOUT_CONTROL, 1); write(R.IRQ, 5)
      assert((read(R.IRQ) & 4) == 0)
      idleLegacy()
      info(s"NATIVE_CONTROL_DMA CUs=4 lanes=16 warps=8 heldNativeSubmit=true " +
        s"busyDMARejected=true completionPinsVM=true fillCopyStrided=true transactions=$transactions " +
        "invalidStridedRejected=true delayedRealReplies=true exactGuards=true scanoutVblank=true inertLegacyStart=true")
    }
  }
}
