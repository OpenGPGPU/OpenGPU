package opengpu.system

import chisel3._
import chisel3.reflect.DataMirror
import opengpu.config.{ComputeResourceProfile, GpuConfig}
import opengpu.graphics.{
  GpuCommandMmioRegs,
  NativeVertexInputCapabilities,
  RenderHostRegs,
  TessellationJointHostProtocol
}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TessellationHostAxiCommandSpec extends AnyFlatSpec {
  private def zero(data: Data): Unit = data match {
    case r: Record                                                     => r.elements.values.foreach(zero)
    case v: Vec[_]                                                     => v.foreach(zero)
    case b: Bool if DataMirror.directionOf(b) == ActualDirection.Input => b.poke(false.B)
    case u: UInt if DataMirror.directionOf(u) == ActualDirection.Input => u.poke(0.U)
    case s: SInt if DataMirror.directionOf(s) == ActualDirection.Input => s.poke(0.S)
    case _                                                             => ()
  }
  private def until(d: GpuHostAxi)(condition: => Boolean): Unit = {
    var n = 0; while (!condition && n < 128) { d.clock.step(); n += 1 }
    assert(
      condition,
      "AXI operation timed out: " + Thread.currentThread.getStackTrace
        .filter(_.getClassName.contains("TessellationHostAxiCommandSpec"))
        .mkString(" ")
    )
  }
  private def write(d: GpuHostAxi, address: Int, value: Int, response: Int = 0): Unit = {
    d.io.s_axi_awaddr.poke(address.U); d.io.s_axi_awvalid.poke(true.B)
    until(d)(d.io.s_axi_awready.peek().litToBoolean); d.clock.step(); d.io.s_axi_awvalid.poke(false.B)
    d.io.s_axi_wdata.poke((value.toLong & 0xffffffffL).U); d.io.s_axi_wstrb.poke(15.U)
    d.io.s_axi_wlast.poke(true.B); d.io.s_axi_wvalid.poke(true.B)
    until(d)(d.io.s_axi_wready.peek().litToBoolean); d.clock.step(); d.io.s_axi_wvalid.poke(false.B)
    until(d)(d.io.s_axi_bvalid.peek().litToBoolean); d.io.s_axi_bresp.expect(response.U)
    d.io.s_axi_bready.poke(true.B); d.clock.step(); d.io.s_axi_bready.poke(false.B)
  }
  private def read(d: GpuHostAxi, address: Int, response: Int = 0): BigInt = {
    d.io.s_axi_araddr.poke(address.U); d.io.s_axi_arvalid.poke(true.B)
    until(d)(d.io.s_axi_arready.peek().litToBoolean); d.clock.step(); d.io.s_axi_arvalid.poke(false.B)
    until(d)(d.io.s_axi_rvalid.peek().litToBoolean); val value = d.io.s_axi_rdata.peek().litValue
    d.io.s_axi_rresp.expect(response.U); d.io.s_axi_rready.poke(true.B); d.clock.step();
    d.io.s_axi_rready.poke(false.B); value
  }
  it should "route a tessellation job through protected host AXI and pin VM state through completion" in {
    val gpu = GpuConfig(lanes = 4, warps = 2)
    simulate(
      new GpuHostAxi(
        gpuConfig = gpu,
        version = 2,
        unifiedCommandMmio = true,
        protectVmState = true,
        externalBinnedRender = true,
        nativeCompletionInfo = true,
        tessellationHost = true,
        tessellationDiscovery = true,
        computeResources = Some(ComputeResourceProfile(gpu, 1, 8))
      )
    ) { d =>
      zero(d.io); d.io.s_axi_aresetn.poke(false.B); d.clock.step(3); d.io.s_axi_aresetn.poke(true.B); d.clock.step()
      d.io.s_axi_awsize.poke(2.U); d.io.s_axi_awburst.poke(1.U); d.io.s_axi_arsize.poke(2.U);
      d.io.s_axi_arburst.poke(1.U)
      d.io.vmContextIdle.get.poke(true.B); d.io.graphicsDependencyIdle.get.poke(true.B)
      d.io.binDrained.get.poke(true.B); d.io.tessellationDrained.get.poke(true.B)
      d.io.gpuCommand.get.ready.poke(true.B); d.io.binSubmit.get.ready.poke(true.B)
      val r = GpuCommandMmioRegs
      val flags = read(d, ComputeResourceCapabilities.base + 8)
      assert((flags & ComputeResourceCapabilities.tessellationCommands) != 0)
      assert((flags & ComputeResourceCapabilities.tessellationFeedback) == 0)
      assert((flags & ComputeResourceCapabilities.jointTessellation) == 0)
      assert(read(d, TessellationJointHostProtocol.profileBase, response = 2) == 0)
      write(d, RenderHostRegs.IRQ, 1)
      write(d, r.COMMAND_ID, 41); write(d, r.OPCODE, 15); write(d, r.SOURCE, 0x4000); write(d, r.DESTINATION, 0x4200)
      write(d, r.PATTERN, 0); write(d, r.BYTES, 512); write(d, r.SUBMIT, 1)
      until(d)(d.io.tessellationSubmit.get.valid.peek().litToBoolean)
      d.io.tessellationSubmit.get.bits.commandId.expect(41.U); d.io.tessellationSubmit.get.bits.base.expect(0x4000.U)
      d.io.tessellationSubmit.get.bits.limit.expect(0x4200.U);
      d.io.tessellationSubmit.get.bits.envelopeValid.expect(true.B)
      d.io.binSubmit.get.valid.expect(false.B); write(d, r.VECTOR_SATP, 0, response = 2)
      d.io.tessellationSubmit.get.ready.poke(true.B); d.clock.step(); d.io.tessellationSubmit.get.ready.poke(false.B)
      d.io.tessellationDrained.get.poke(false.B); d.io.graphicsDrained.expect(false.B)
      write(d, r.TLB_FLUSH, 1, response = 2)
      val reply = d.io.tessellationCompletion.get
      reply.bits.commandId.poke(41.U); reply.bits.status.poke(0.U); reply.bits.appendReadBytes.poke(704.U)
      reply.valid.poke(true.B); until(d)(reply.ready.peek().litToBoolean); d.clock.step(); reply.valid.poke(false.B)
      d.io.tessellationDrained.get.poke(true.B)
      until(d)(d.io.m_irq.peek().litToBoolean)
      val meta = read(d, r.COMPLETION);
      assert((meta & 255) == 41 && (((meta >> 8) & 7) | (((meta >> 16) & 1) << 3)) == 15)
      assert(read(d, r.COMPLETION_BYTES_LO) == 704)
      write(d, r.TLB_FLUSH, 1, response = 2); write(d, r.COMPLETION_POP, 1); write(d, r.TLB_FLUSH, 1)
      write(d, r.RESET, 1); d.io.tessellationCancel.get.expect(true.B)
    }
  }
  it should "advertise feedback only for a discovered feedback-enabled tessellation host" in {
    val gpu = GpuConfig(lanes = 4, warps = 2); val profile = ComputeResourceProfile(gpu, 1, 8)
    val noTess = ComputeResourceCapabilities.words(profile)(2)
    assert(
      (noTess & (ComputeResourceCapabilities.tessellationCommands | ComputeResourceCapabilities.tessellationFeedback)) == 0
    )
    intercept[IllegalArgumentException] {
      ComputeResourceCapabilities.words(profile, tessellationFeedbackAvailable = true)
    }
    simulate(
      new GpuHostAxi(
        gpuConfig = gpu,
        version = 2,
        unifiedCommandMmio = true,
        protectVmState = true,
        externalBinnedRender = true,
        nativeCompletionInfo = true,
        nativeVertexInput = true,
        tessellationHost = true,
        visibilityQueries = true,
        transformFeedback = true,
        tessellationDiscovery = true,
        computeResources = Some(profile)
      )
    ) { d =>
      zero(d.io); d.io.s_axi_aresetn.poke(false.B); d.clock.step(3); d.io.s_axi_aresetn.poke(true.B); d.clock.step()
      d.io.s_axi_awsize.poke(2.U); d.io.s_axi_awburst.poke(1.U); d.io.s_axi_arsize.poke(2.U);
      d.io.s_axi_arburst.poke(1.U)
      val address = ComputeResourceCapabilities.base + 8
      val flags = read(d, address)
      assert((flags & ComputeResourceCapabilities.tessellationCommands) != 0)
      assert((flags & ComputeResourceCapabilities.tessellationFeedback) != 0)
      write(d, address, 0, response = 2)
      assert(read(d, address) == flags)
      d.io.vmContextIdle.get.poke(true.B); d.io.graphicsDependencyIdle.get.poke(true.B)
      d.io.binDrained.get.poke(true.B); d.io.tessellationDrained.get.poke(true.B)
      d.io.gpuCommand.get.ready.poke(true.B); d.io.tessellationSubmit.get.ready.poke(false.B)
      val r = GpuCommandMmioRegs
      write(d, RenderHostRegs.IRQ, 1); write(d, r.COMMAND_ID, 41); write(d, r.OPCODE, 15)
      write(d, r.SOURCE, 0xfffffe00L.toInt); write(d, r.DESTINATION, 0); write(d, r.PATTERN, 1); write(d, r.BYTES, 512)
      write(d, r.WIDTH, 0x71000007); write(d, r.HEIGHT, 9); write(d, r.SOURCE_STRIDE, 11); write(d, r.FLAGS, 24)
      write(d, r.SUBMIT, 1); until(d)(d.io.tessellationSubmit.get.valid.peek().litToBoolean)
      d.io.tessellationSubmit.get.ready.poke(true.B)
      d.io.tessellationSubmit.get.bits.limit.expect("h100000000".U)
      d.io.tessellationSubmit.get.bits.envelopeValid.expect(true.B)
      d.io.renderQueryContext.get.expect("h71000007".U)
      d.io.renderFeedbackScope.get.context.expect("h71000007".U)
      d.io.renderFeedbackScope.get.program.expect(9.U); d.io.renderFeedbackScope.get.cookie.expect(11.U)
      d.io.renderFeedbackScope.get.rasterDiscard.expect(true.B); d.io.renderFeedbackScope.get.mode.expect(1.U)
      d.clock.step(); d.io.tessellationSubmit.get.ready.poke(false.B)
      val reply = d.io.tessellationCompletion.get
      reply.bits.commandId.poke(41.U); reply.bits.status.poke(0.U); reply.valid.poke(true.B)
      until(d)(reply.ready.peek().litToBoolean); d.clock.step(); reply.valid.poke(false.B)
      until(d)(d.io.m_irq.peek().litToBoolean)
      val meta = read(d, r.COMPLETION);
      assert((meta & 255) == 41 && (((meta >> 8) & 7) | (((meta >> 16) & 1) << 3)) == 15)
      assert(read(d, r.COMPLETION_BYTES_LO) == 0)
      write(d, r.COMPLETION_POP, 1)
    }
  }

  it should "route private joint tessellation and hold its complete GNZ2 report through AXI" in {
    val gpu = GpuConfig(lanes = 16, warps = 8)
    val profile = TessellationJointHostProtocol.Profile(
      4,
      scalarFpu = false,
      advancedBlend = false,
      sampleInputs = false,
      feedback = true
    )
    simulate(
      new GpuHostAxi(
        gpuConfig = gpu,
        version = 2,
        unifiedCommandMmio = true,
        protectVmState = true,
        externalBinnedRender = true,
        nativeCompletionInfo = true,
        nativeVertexInput = true,
        tessellationHost = true,
        jointTessellationHost = true,
        visibilityQueries = true,
        transformFeedback = true,
        nativeUniformResources = true,
        shaderExecutionLimits = true,
        nativeDescriptorPreflight = true,
        nativeProtectedSources = true,
        jointTessellationProfile = Some(profile),
        computeResources = Some(ComputeResourceProfile(gpu, 2, 32))
      )
    ) { d =>
      zero(d.io)
      d.io.s_axi_aresetn.poke(false.B); d.clock.step(3)
      d.io.s_axi_aresetn.poke(true.B); d.clock.step()
      d.io.s_axi_awsize.poke(2.U); d.io.s_axi_awburst.poke(1.U)
      d.io.s_axi_arsize.poke(2.U); d.io.s_axi_arburst.poke(1.U)
      d.io.vmContextIdle.get.poke(true.B); d.io.graphicsDependencyIdle.get.poke(true.B)
      d.io.binDrained.get.poke(true.B); d.io.tessellationDrained.get.poke(true.B)
      d.io.gpuCommand.get.ready.poke(true.B)
      val r = GpuCommandMmioRegs
      val vertexCapability = read(d, NativeVertexInputCapabilities.base)
      assert(vertexCapability != 0)
      val flags = read(d, ComputeResourceCapabilities.base + 8)
      assert((flags & ComputeResourceCapabilities.jointTessellation) != 0)
      assert((flags & ComputeResourceCapabilities.tessellationCommands) == 0)
      val profileAddress = TessellationJointHostProtocol.profileBase
      val expectedProfile = Seq[BigInt](0x474a5001, 0x47f, 0x08100207, 4)
      for (i <- expectedProfile.indices) assert(read(d, profileAddress + 4 * i) == expectedProfile(i))
      write(d, profileAddress + 12, 1, response = 2)
      assert(read(d, profileAddress + 12) == 4)
      write(d, RenderHostRegs.IRQ, 1)
      write(d, r.VECTOR_SATP, 0x80000030)
      write(d, r.COMMAND_ID, 42); write(d, r.OPCODE, 15)
      write(d, r.SOURCE, 0xfffff000); write(d, r.DESTINATION, 0xffff0000)
      write(d, r.PATTERN, 0x474a0105); write(d, r.BYTES, 512)
      write(d, r.WIDTH, 0); write(d, r.HEIGHT, 7)
      write(d, r.SOURCE_STRIDE, 8); write(d, r.DESTINATION_STRIDE, 9)
      write(d, r.GRID_X, 10); write(d, r.GRID_Y, 11); write(d, r.FLAGS, 24)
      write(d, r.SUBMIT, 1)
      val submit = d.io.jointTessellationSubmit.get
      until(d)(submit.valid.peek().litToBoolean)
      d.io.tessellationSubmit.get.valid.expect(false.B); d.io.binSubmit.get.valid.expect(false.B)
      val saved = submit.bits.peek().litValue
      write(d, r.HEIGHT, 99); write(d, r.WIDTH, 0x12345678)
      assert(submit.bits.peek().litValue == saved)
      write(d, r.TLB_FLUSH, 1, response = 2)
      submit.bits.commandId.expect(42.U); submit.bits.domain.expect(2.U)
      submit.bits.view.base.expect("hfffff000".U); submit.bits.view.limit.expect("hfffff200".U)
      submit.bits.view.allocationBase.expect("hfffff000".U)
      submit.bits.view.allocationLimit.expect("h100000000".U)
      submit.bits.owner.base.expect("hffff0000".U); submit.bits.owner.limit.expect("h100000000".U)
      submit.bits.identity.context.expect(7.U); submit.bits.identity.pass.expect(8.U)
      submit.bits.identity.generation.expect(9.U)

      submit.ready.poke(true.B)
      d.io.renderQueryContext.get.expect(7.U)
      val feedback = d.io.renderFeedbackScope.get
      feedback.context.expect(7.U); feedback.root.expect("h80000030".U)
      feedback.program.expect(10.U); feedback.cookie.expect(11.U)
      feedback.rasterDiscard.expect(true.B); feedback.mode.expect(1.U)
      d.clock.step(); submit.ready.poke(false.B); d.io.tessellationDrained.get.poke(false.B)
      write(d, r.VECTOR_SATP, 0, response = 2)

      val reply = d.io.jointTessellationCompletion.get
      reply.bits.commandId.poke(42.U)
      reply.bits.identity.context.poke(7.U); reply.bits.identity.pass.poke(8.U)
      reply.bits.identity.generation.poke(9.U)
      reply.bits.status.poke(3.U); reply.bits.faultStage.poke(6.U)
      reply.bits.faultAddress.poke("habc00140".U)
      reply.bits.readBytes.poke("h100000a00".U)
      reply.bits.capturedEntries.poke(19.U); reply.bits.retiredRanges.poke(18.U)
      reply.bits.retiredPatches.poke("h200000011".U); reply.bits.published.poke("h300000012".U)
      reply.bits.storedBytes.poke("h400000013".U); reply.bits.resolvedBytes.poke("h500000014".U)
      reply.valid.poke(true.B); until(d)(reply.ready.peek().litToBoolean)
      d.clock.step(); reply.valid.poke(false.B); d.io.tessellationDrained.get.poke(true.B)
      until(d)(d.io.m_irq.peek().litToBoolean)
      val meta = read(d, r.COMPLETION)
      assert((meta & 255) == 42 && (((meta >> 8) & 7) | (((meta >> 16) & 1) << 3)) == 15)
      assert(((meta >> 11) & 15) == 3 && (meta & 0x8000) == 0)
      assert(read(d, r.COMPLETION_BYTES_LO) == 0xa00 && read(d, r.COMPLETION_BYTES_HI) == 1)
      val words = Seq(0x474e5a02L, 51L, 0xabc00140L, 7L, 8L, 9L, 19L, 18L, 17L, 2L, 18L, 3L, 19L, 4L, 20L, 5L)
      for ((word, index) <- words.zipWithIndex) {
        val address = if (index < 12) 0x180 + 4 * index else 0x540 + 4 * (index - 12)
        assert(read(d, address) == word)
      }
      assert(read(d, NativeVertexInputCapabilities.base) == vertexCapability)
      write(d, 0x540, 0, response = 2); assert(read(d, 0x540) == 19)
      write(d, r.TLB_FLUSH, 1, response = 2); write(d, r.COMPLETION_POP, 1)
      assert(read(d, 0x180) == 0 && read(d, 0x540) == 0)
      write(d, r.TLB_FLUSH, 1)

      write(d, r.SOURCE, 0x4000); write(d, r.DESTINATION, 0x4200)
      write(d, r.PATTERN, 0); write(d, r.FLAGS, 0); write(d, r.SUBMIT, 1)
      val legacy = d.io.tessellationSubmit.get
      until(d)(legacy.valid.peek().litToBoolean)
      submit.valid.expect(false.B); legacy.bits.envelopeValid.expect(true.B)
      legacy.ready.poke(true.B); d.clock.step(); legacy.ready.poke(false.B)
      val oldReply = d.io.tessellationCompletion.get
      oldReply.bits.commandId.poke(42.U); oldReply.valid.poke(true.B)
      until(d)(oldReply.ready.peek().litToBoolean); d.clock.step(); oldReply.valid.poke(false.B)
      assert((read(d, r.COMPLETION) & 0x8000) != 0)
      assert(read(d, 0x540) == 0 && read(d, 0x54c) == 0)
      write(d, r.COMPLETION_POP, 1)

      write(d, r.PATTERN, 0x474a0100); write(d, r.BYTES, 256); write(d, r.SUBMIT, 1)
      until(d)(submit.valid.peek().litToBoolean)
      legacy.valid.expect(false.B); submit.bits.view.limit.expect(0.U)
      submit.bits.identity.context.expect(99.U)
      submit.ready.poke(true.B); d.clock.step(); submit.ready.poke(false.B)
      reply.bits.identity.context.poke(123.U)
      reply.valid.poke(true.B); until(d)(reply.ready.peek().litToBoolean)
      d.clock.step(); reply.valid.poke(false.B)
      assert(read(d, 0x180) == 0x474e5a02 && read(d, 0x184) == 59)
      assert(read(d, 0x188) == 0 && read(d, 0x18c) == 99)
      assert(read(d, r.COMPLETION_BYTES_LO) == 0 && read(d, r.COMPLETION_BYTES_HI) == 0)
      assert(read(d, 0x540) == 0 && read(d, 0x54c) == 0)
      write(d, r.COMPLETION_POP, 1)
    }
  }
}
