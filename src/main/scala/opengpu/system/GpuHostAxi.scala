package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.command.{GpuCommand, GpuCommandOpcode, GpuCommandResult, RenderCompletion, RenderDescriptor}
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse,
  VectorTlbFlush
}
import opengpu.graphics.{
  GpuCommandMmio,
  GpuCommandMmioRegs,
  GraphicsConfig,
  OmMemoryRequest,
  OmMemoryResponse,
  RenderHost,
  RenderHostRegs,
  TileBinHostCommandPort, TileBinHostRegs, TileBinJobCompletion, TileJobDescriptorRequest, NativeBinnedCompletionInfo, NativeVertexInputCapabilities, NativeFragmentInputCapabilities, NativeRasterCapabilities
}

/** AXI4 slave control interface for the graphics renderer.
  *
  * This is the M6 host-facing port that ARTI (the RTL-to-QEMU integration
  * framework) can auto-bridge: it exposes the standard AXI4 memory-mapped
  * write and read channels (s_axi_aw*, s_axi_w*, s_axi_b*, s_axi_ar*,
  * s_axi_r*), plus the single-clock / active-low-reset infrastructure and an
  * interrupt output (m_irq), so ARTI's naming-based inferencer detects an
  * AXI4 slave, maps the registers, and generates the embedded QEMU device
  * model and device-tree node.  Internally it forwards every access to
  * `RenderHost`'s register file and forwards the renderer's shared-memory
  * ports unchanged.
  *
  * AXI4 bursts are handled word-at-a-time (32-bit data, size = 2): a write
  * burst issues one register write per beat at awaddr + beat*4 (INCR) or at
  * the base address (FIXED), and a read burst returns one word per beat.
  * Unsupported burst types/sizes and 4 KiB crossings return SLVERR.
  * Unaligned or out-of-map accesses are acknowledged with SLVERR (RRESP/BRESP = 2'b10); successful accesses
  * return `OKAY`.  The register file is single-ported, so at most one AXI
  * transaction (read or write) is in flight at a time; the handshakes
  * backpressure the host accordingly.
  */
class GpuHostAxi(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  vertCore: Boolean = false,
  deviceId: Int = 0x4755,
  version: Int = 0x0001,
  externalCompletionIrq: Boolean = false,
  unifiedCommandMmio: Boolean = false,
  commandIdWidth: Int = 8,
  textureFaultReporting: Boolean = false,
  computeScalarFpu: Boolean = false,
  graphicsScalarFpu: Boolean = true,
  sharedShader: Option[SharedCuShaderConfig] = None,
  protectVmState: Boolean = false,
  persistentAttachments: Boolean = false,
  tileStoreCredits: Int = opengpu.config.MobileGpuConfig().attachmentStoreCredits,
  useTileSramBlackBoxes: Boolean = false,
  externalBinnedRender: Boolean = false,
  nativeCompletionInfo: Boolean = false,
  nativeVertexInput: Boolean = false,
  visibilityQueries: Boolean = false,
  transformFeedback: Boolean = false,transformFeedbackSourceWords: Int = 69,
  primitiveQueries: Boolean = false,
  shaderExecutionLimits: Boolean = false,
  enableIndirectCompute: Boolean = false,
  computeResources: Option[opengpu.config.ComputeResourceProfile] = None,
  nativeVertexReuseEntries: Int = 0,
  nativeIndirectDraw: Boolean = false,
  nativeUniformResources: Boolean = false,
  imageAvailable: Boolean = false, storageAvailable: Boolean = false
) extends Module {
  require(!protectVmState || unifiedCommandMmio)
  require(!persistentAttachments || (unifiedCommandMmio && protectVmState))
  require(!externalBinnedRender || (unifiedCommandMmio && protectVmState && !persistentAttachments))
  require(!nativeCompletionInfo || externalBinnedRender)
  require(!nativeVertexInput || externalBinnedRender)
  require(!nativeUniformResources || nativeVertexInput)
  require(nativeVertexInput || (nativeVertexReuseEntries==0 && !nativeIndirectDraw))
  private val nativeVertexWords=NativeVertexInputCapabilities.wordsFor(nativeVertexReuseEntries,nativeIndirectDraw)
  require(!visibilityQueries || nativeVertexInput)
  require(!transformFeedback || nativeVertexInput)
  require(!primitiveQueries || transformFeedback)
  require(!shaderExecutionLimits || (nativeVertexInput && protectVmState))
  require(!enableIndirectCompute || (unifiedCommandMmio && protectVmState))
  require(!imageAvailable || (unifiedCommandMmio && protectVmState))
  require(!imageAvailable || computeResources.nonEmpty,
    "image command transport requires GCR1 feature discovery")
  require(!imageAvailable || version >= 2,
    "image command discovery requires the GCR1 host ABI version")
  require(!storageAvailable || (unifiedCommandMmio && protectVmState && computeResources.nonEmpty && version >= 2),
    "storage command transport requires protected VM ownership and GCR1 discovery")
  require(computeResources.isEmpty || (unifiedCommandMmio && protectVmState))
  require(version >= 0 && version <= 0xffff, "host ABI version must fit its 16-bit field")
  require(version < 2 || computeResources.nonEmpty,
    "host ABI v2 and later require the read-only GCR1 compute discovery block")
  private val computeConfig = computeResources.map(_.gpu).getOrElse(gpuConfig)
  override def desiredName: String = "GpuHostAxi"

  val io = IO(new Bundle {
    val shaderInstructionLimit = if (shaderExecutionLimits) Some(Output(UInt(32.W))) else None
    val shared = sharedShader.filter(_ => !externalBinnedRender).map(pool => Flipped(new SharedCuGraphicsPort(
      gpuConfig, pool.taskIdWidth, pool.numComputeUnits)))
    val s_axi_aclk = Input(Clock())
    val s_axi_aresetn = Input(Bool())

    val s_axi_awaddr = Input(UInt(32.W))
    val s_axi_awlen = Input(UInt(8.W))
    val s_axi_awsize = Input(UInt(3.W))
    val s_axi_awburst = Input(UInt(2.W))
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())

    val s_axi_wdata = Input(UInt(32.W))
    val s_axi_wstrb = Input(UInt(4.W))
    val s_axi_wlast = Input(Bool())
    val s_axi_wvalid = Input(Bool())
    val s_axi_wready = Output(Bool())

    val s_axi_bresp = Output(UInt(2.W))
    val s_axi_bvalid = Output(Bool())
    val s_axi_bready = Input(Bool())

    val s_axi_araddr = Input(UInt(32.W))
    val s_axi_arlen = Input(UInt(8.W))
    val s_axi_arsize = Input(UInt(3.W))
    val s_axi_arburst = Input(UInt(2.W))
    val s_axi_arvalid = Input(Bool())
    val s_axi_arready = Output(Bool())

    val s_axi_rdata = Output(UInt(32.W))
    val s_axi_rresp = Output(UInt(2.W))
    val s_axi_rlast = Output(Bool())
    val s_axi_rvalid = Output(Bool())
    val s_axi_rready = Input(Bool())

    /** Graphics/unified completion IRQ (IRQ.PENDING && IRQ.ENABLE). */
    val m_irq = Output(Bool())
    val externalCompletion = if (externalCompletionIrq) {
      Some(Input(Bool()))
    } else None
    val gpuCommand = if (unifiedCommandMmio) {
      Some(Decoupled(new GpuCommand(gpuConfig, commandIdWidth, imageAvailable, storageAvailable)))
    } else None
    val gpuCompletion = if (unifiedCommandMmio) {
      Some(Flipped(Decoupled(new GpuCommandResult(commandIdWidth))))
    } else None
    /** Safe unified-command reset handshake to the system: the bridge holds
      * `commandResetActive` from the RESET register write until the system
      * pulses `commandResetDone` (drain complete, command path reset). */
    val commandResetActive = if (unifiedCommandMmio) {
      Some(Output(Bool()))
    } else None
    val commandResetDone = if (unifiedCommandMmio) {
      Some(Input(Bool()))
    } else None
    /** Sv32 page-table state for the vector and instruction MMUs, plus a
      * one-cycle scoped TLB flush, programmed through the unified bridge. */
    val graphicsDrained = Output(Bool())
    val performance = Output(new opengpu.graphics.GraphicsPerformanceEvents)
    val vectorSatp = Output(UInt(32.W))
    val instructionSatp = Output(UInt(32.W))
    val tlbFlush = Output(Valid(new VectorTlbFlush(gpuConfig)))
    val textureFault = if (textureFaultReporting) Some(Input(Bool())) else None
    val vmContextIdle = if (protectVmState) Some(Input(Bool())) else None
    val commandFault = if (persistentAttachments) Some(Input(Bool())) else None
    val attachmentFault = if (persistentAttachments) Some(Input(Bool())) else None
    val tileTextureFault = if (persistentAttachments) Some(Input(Bool())) else None
    val graphicsDependencyIdle = if (persistentAttachments || externalBinnedRender) Some(Input(Bool())) else None

    val binSubmit = if (externalBinnedRender) Some(Decoupled(new TileJobDescriptorRequest(commandIdWidth))) else None
    val binCompletion = if (externalBinnedRender) Some(Flipped(Decoupled(new TileBinJobCompletion(commandIdWidth)))) else None
    val binCompletionDetails=if(nativeCompletionInfo)Some(Input(Vec(NativeBinnedCompletionInfo.wordCount,UInt(32.W))))else None
    val binCapabilityWords = if (externalBinnedRender) Some(Input(Vec(8,UInt(32.W)))) else None
    val binDrained = if (externalBinnedRender) Some(Input(Bool())) else None
    val binCancel = if (externalBinnedRender) Some(Output(Bool())) else None
    val queryCommand=if(visibilityQueries)Some(Decoupled(new opengpu.graphics.VisibilityQueryCommand))else None
    val queryReply=if(visibilityQueries)Some(Flipped(Decoupled(new opengpu.graphics.VisibilityQueryReply)))else None
    val renderQueryContext=if(visibilityQueries)Some(Output(UInt(32.W)))else None
    val feedbackCommand=if(transformFeedback)Some(Decoupled(new opengpu.graphics.NativeTransformFeedbackRequest))else None
    val feedbackReply=if(transformFeedback)Some(Flipped(Decoupled(new opengpu.graphics.NativeTransformFeedbackReply)))else None
    val renderFeedbackScope=if(transformFeedback)Some(Output(new opengpu.graphics.TransformFeedbackScope))else None

    val primitiveQueryCommand=if(primitiveQueries)Some(Decoupled(new opengpu.graphics.PrimitiveQueryCommand))else None
    val primitiveQueryReply=if(primitiveQueries)Some(Flipped(Decoupled(new opengpu.graphics.PrimitiveQueryReply)))else None

    // Renderer shared-memory ports (command buffer + framebuffer words, and the
    // core-backed shader kernel's line/coherence side ports) pass straight
    // through so the SoC attaches them to the shared off-chip hierarchy.
    val commandReadsIdle = Output(Bool())
    val cbMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val fbMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val kernelMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig, 64, SharedCuShaderConfig.kernelWordTransactions(sharedShader)))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse(64, SharedCuShaderConfig.kernelWordTransactions(sharedShader))))
    val kernelL1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(gpuConfig)))
    val kernelL1InvalidateDone = Decoupled(new CacheLineInvalidate(gpuConfig))
    val kernelGlobalAtomicRequest = Decoupled(new SharedAtomicRequest(gpuConfig))
    val kernelGlobalAtomicResponse =
      Flipped(Decoupled(new SharedAtomicResponse(gpuConfig)))
    val texMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  withClockAndReset(clock, !io.s_axi_aresetn) {
    val host = Module(new RenderHost(
      config, gpuConfig, fragCore, vertCore, deviceId, version,
      unifiedCommands = unifiedCommandMmio,
      textureFaultReporting = textureFaultReporting,
      commandIdWidth = commandIdWidth,
      computeScalarFpu = computeScalarFpu,
      graphicsScalarFpu = graphicsScalarFpu,
      sharedShader = sharedShader,
      protectedVmState = protectVmState,
      persistentAttachments = persistentAttachments, tileStoreCredits = tileStoreCredits,
      useTileSramBlackBoxes = useTileSramBlackBoxes, externalBinnedRender = externalBinnedRender))
    sharedShader.filter(_ => !externalBinnedRender).foreach(_ => host.io.shared.get <> io.shared.get)
    host.io.textureFault.foreach(_ := io.textureFault.get)
    if (persistentAttachments) {
      host.io.commandFault.get := io.commandFault.get
      host.io.attachmentFault.get := io.attachmentFault.get
      host.io.tileTextureFault.get := io.tileTextureFault.get
      host.io.tileCancel.get := io.commandResetActive.get
    }
    val binCompletionDrained=WireDefault(true.B)
    val shaderQuota = if (shaderExecutionLimits) Some(RegInit(NativeShaderExecutionCapabilities.defaultQuota.U(32.W))) else None
    io.shaderInstructionLimit.foreach(_ := shaderQuota.get)
    val shaderQuotaIdle = WireDefault(false.B)
    io.commandReadsIdle := host.io.commandReadsIdle
    io.graphicsDrained := host.io.drained && io.binDrained.getOrElse(true.B) && binCompletionDrained
    io.performance := host.io.performance
    val unified = if (unifiedCommandMmio) {
      Some(Module(new GpuCommandMmio(
        gpuConfig, commandIdWidth, gpuConfig.commandQueueDepth,
        shaderComputeUnits = sharedShader.map(_.numComputeUnits).getOrElse(1),
        shaderKernargBanks = sharedShader.map(_.fragmentBanks).getOrElse(2),
        protectVmState = protectVmState,nativeCompletionInfo=nativeCompletionInfo,imageAvailable=imageAvailable,storageAvailable=storageAvailable)))
    } else None

    // Command distribution lives here, next to the graphics engine: a render
    // command drives RenderHost directly, while every other unified command
    // goes to the system command router. This keeps a draw from round-tripping
    // across the host/system seam.
    host.io.renderCommand.valid := false.B
    host.io.renderCommand.bits := 0.U.asTypeOf(host.io.renderCommand.bits)
    host.io.renderCompletion.ready := false.B
    unified.foreach { bridge =>
      if (externalBinnedRender) {
        val port=Module(new TileBinHostCommandPort(gpuConfig,commandIdWidth,nativeCompletionInfo,visibilityQueries,transformFeedback,primitiveQueries,imageAvailable,storageAvailable))
        port.io.command <> bridge.io.command
        io.binSubmit.get <> port.io.submit
        port.io.renderCompletion <> io.binCompletion.get
        io.gpuCommand.get <> port.io.otherCommand
        port.io.otherCompletion <> io.gpuCompletion.get
        bridge.io.completion <> port.io.completion
        if(nativeCompletionInfo){port.io.renderCompletionDetails.get:=io.binCompletionDetails.get
          bridge.io.completionDetails.get:=port.io.completionDetails.get}
        port.io.dependencyIdle:=io.graphicsDependencyIdle.get && host.io.drained
        port.io.renderIdle:=io.binDrained.get
        if(visibilityQueries){
          io.queryCommand.get <> port.io.queryCommand.get
          port.io.queryReply.get <> io.queryReply.get
          io.renderQueryContext.get:=port.io.renderQueryContext.get
        }
        if(transformFeedback){
          io.feedbackCommand.get<>port.io.feedbackCommand.get;port.io.feedbackReply.get<>io.feedbackReply.get
          port.io.feedbackSatp.get:=bridge.io.vectorSatp;io.renderFeedbackScope.get:=port.io.renderFeedbackScope.get
        }
        if(primitiveQueries){
          io.primitiveQueryCommand.get<>port.io.primitiveQueryCommand.get
          port.io.primitiveQueryReply.get<>io.primitiveQueryReply.get
        }
        port.io.resetActive:=bridge.io.resetActive
        io.binCancel.get:=port.io.cancel
        host.io.externalRenderBusy.get:=port.io.renderPending
        binCompletionDrained:=port.io.drained
      } else {
        val renderOpcode = if (persistentAttachments) GpuCommandOpcode.tileRender else GpuCommandOpcode.render
        val isRender = bridge.io.command.bits.opcode === renderOpcode
        val dependencyReady = io.graphicsDependencyIdle.getOrElse(true.B)
        val nonRenderAllowed = if (persistentAttachments)
          bridge.io.command.bits.opcode === GpuCommandOpcode.kernel || host.io.drained else true.B
        host.io.renderCommand.valid := bridge.io.command.valid && isRender && dependencyReady
        host.io.renderCommand.bits.descriptorId :=
          bridge.io.command.bits.commandId
        host.io.renderCommand.bits.descriptorAddress :=
          bridge.io.command.bits.sourceAddress
        host.io.renderCommand.bits.bytes := bridge.io.command.bits.bytes
        if (persistentAttachments) {
          host.io.renderCommand.bits.limit.get := Cat(bridge.io.command.bits.pattern(0),
            bridge.io.command.bits.destinationAddress)
          host.io.renderCommand.bits.envelopeValid.get := bridge.io.command.bits.pattern(31, 1) === 0.U &&
            !bridge.io.command.bits.waitForDma && !bridge.io.command.bits.waitForEvent &&
            !bridge.io.command.bits.signalEvent
        }
        io.gpuCommand.get.valid := bridge.io.command.valid && !isRender && nonRenderAllowed
        io.gpuCommand.get.bits := bridge.io.command.bits
        bridge.io.command.ready := Mux(isRender,
          host.io.renderCommand.ready && dependencyReady, io.gpuCommand.get.ready && nonRenderAllowed)

        // One unified completion slot: the local render completion or the
        // system's command completion.
        val renderResult =
          Wire(new GpuCommandResult(commandIdWidth))
        renderResult.commandId := host.io.renderCompletion.bits.descriptorId
        renderResult.opcode := renderOpcode
        renderResult.status := host.io.renderCompletion.bits.status
        renderResult.success := host.io.renderCompletion.bits.success
        renderResult.bytesProcessed :=
          host.io.renderCompletion.bits.bytesProcessed
        val completionArb = Module(new RRArbiter(
          new GpuCommandResult(commandIdWidth), 2))
        completionArb.io.in(0).valid := host.io.renderCompletion.valid
        completionArb.io.in(0).bits := renderResult
        host.io.renderCompletion.ready := completionArb.io.in(0).ready
        completionArb.io.in(1).valid := io.gpuCompletion.get.valid
        completionArb.io.in(1).bits := io.gpuCompletion.get.bits
        io.gpuCompletion.get.ready := completionArb.io.in(1).ready
        bridge.io.completion <> completionArb.io.out

      }
      io.commandResetActive.get := bridge.io.resetActive
      bridge.io.resetDone := io.commandResetDone.get
      bridge.io.vmContextIdle.foreach(_ := io.vmContextIdle.get && host.io.drained && io.binDrained.getOrElse(true.B) && binCompletionDrained)
      if (shaderExecutionLimits) shaderQuotaIdle := bridge.io.vmStateWriteAllowed.get
    }
    io.vectorSatp := unified.map(_.io.vectorSatp).getOrElse(0.U)
    io.instructionSatp := unified.map(_.io.instructionSatp).getOrElse(0.U)
    io.tlbFlush := unified.map(_.io.tlbFlush).getOrElse(
      0.U.asTypeOf(io.tlbFlush))
    host.io.instructionSatp := io.instructionSatp
    host.io.instructionTlbFlush := io.tlbFlush
    host.io.vectorSatp := io.vectorSatp
    host.io.vectorTlbFlush := io.tlbFlush
    host.io.externalCompletion := io.externalCompletion.getOrElse(false.B) ||
      unified.map(_.io.completionEvent).getOrElse(false.B)
    io.m_irq := host.io.irq
    host.io.cbMem.req <> io.cbMem.req
    io.cbMem.resp <> host.io.cbMem.resp
    host.io.fbMem.req <> io.fbMem.req
    io.fbMem.resp <> host.io.fbMem.resp
    io.kernelMemReq <> host.io.kernelMemReq
    host.io.kernelMemResp <> io.kernelMemResp
    io.kernelWordMemReq <> host.io.kernelWordMemReq
    host.io.kernelWordMemResp <> io.kernelWordMemResp
    host.io.kernelL1Invalidate <> io.kernelL1Invalidate
    io.kernelL1InvalidateDone <> host.io.kernelL1InvalidateDone
    io.kernelGlobalAtomicRequest <> host.io.kernelGlobalAtomicRequest
    host.io.kernelGlobalAtomicResponse <> io.kernelGlobalAtomicResponse
    host.io.texMem <> io.texMem

    val reg = host.io.reg
    reg.resp.ready := true.B
    reg.req.bits.isWrite := false.B
    reg.req.bits.data := 0.U
    reg.req.bits.strb := 0xf.U
    reg.req.bits.addr := 0.U
    reg.req.valid := false.B
    unified.foreach { bridge =>
      bridge.io.reg.resp.ready := true.B
      bridge.io.reg.req.bits := 0.U.asTypeOf(bridge.io.reg.req.bits)
      bridge.io.reg.req.valid := false.B
    }

    // -------------------------------------------------------------------------
    // One in-flight transaction.  The single-ported register file cannot serve
    // a read and a write simultaneously, so the read and write channel
    // addresses each require the other idle. Resolve simultaneous offers
    // before acknowledging either channel, alternating priority under contention.
    // -------------------------------------------------------------------------
    val writeActive = RegInit(false.B)
    val readActive = RegInit(false.B)
    val addrReg = RegInit(0.U(32.W))
    val lenReg = RegInit(0.U(8.W))
    val transferValid = RegInit(false.B)
    val beatAddress = RegInit(0.U(33.W))
    val burstReg = RegInit(0.U(2.W))
    val beat = RegInit(0.U(8.W))
    val bPending = RegInit(false.B)
    val writeError = RegInit(false.B)
    val lastAddressWasWrite = RegInit(false.B)
    val addressIdle = !writeActive && !readActive
    val grantRead = addressIdle && (!io.s_axi_awvalid || lastAddressWasWrite)
    val grantWrite = addressIdle && (!io.s_axi_arvalid || !lastAddressWasWrite)
    val readHeld = RegInit(false.B)
    val heldReadData = Reg(UInt(32.W))
    assert(!(writeActive && readActive), "AXI register transactions require one owner")

    // AXI FIXED=0 retains the address; INCR=1 advances by one control word.
    // Reject unsupported sizes/WRAP/reserved bursts and 4 KiB crossings before
    // offering any register mutation. Retain the carry to prevent high-address
    // bursts from wrapping into the low register map.
    // Register each beat's complete address and validate the burst at its
    // address handshake. Neither beat*4+base nor burst-end arithmetic belongs
    // ahead of the public register-read mux. Preserve the 33rd carry bit so
    // high-address increments cannot alias the low MMIO map.
    def validTransfer(address: UInt, length: UInt, size: UInt, burst: UInt): Bool = {
      val end = address(11,0) +& Mux(burst===1.U, (length+&1.U)<<2, 4.U)
      size===2.U && burst<=1.U && end<=4096.U && (burst=/=0.U || length<16.U)
    }
    val beatAddrWide=beatAddress
    val beatAddr=beatAddress(31,0)
    val transferOk=transferValid
    def advanceBeatAddress(): Unit = {
      when(burstReg===1.U) {
        // Retain the previous eight-bit beat counter's wrap behavior even
        // for a malformed write stream extending beyond its declared length.
        beatAddress := Mux(beat.andR, Cat(0.U(1.W),addrReg), beatAddress+4.U)
      }
    }
    val lastBeat = beat === lenReg
    // RenderHost owns 0x000..0xC4, MSAA_CONFIG at 0x134, and the
    // stencil/blend registers at 0x13C..0x144; the unified block owns
    // 0xC4..0x130, the RESET register at 0x138 and the SAMPLE_MODE / satp /
    // TLB_FLUSH registers at 0x148..0x154, plus shader resources at 0x158.
    // The overall map ends at GpuCommandMmioRegs.END (0x15c); non-unified
    // builds read the unified range as reserved zero. Binned profiles additionally
    // expose the read-only discovery pointer at 0x68 and GBC1 at 0x160..0x17c;
    // 0x15c remains unmapped, and legacy profiles retain their original map.
    val mappedEnd = GpuCommandMmioRegs.END.U
    val targetBinDiscovery = if (externalBinnedRender) beatAddr===TileBinHostRegs.DISCOVERY.U else false.B
    val targetBinCapabilities = if (externalBinnedRender)
      beatAddr>=TileBinHostRegs.CAPABILITIES.U && beatAddr<TileBinHostRegs.END.U else false.B
    val targetNativeDetails=if(nativeCompletionInfo)beatAddr>=NativeBinnedCompletionInfo.base.U && beatAddr<NativeBinnedCompletionInfo.end.U else false.B
    val targetNativeInput=if(nativeVertexInput)beatAddr>=NativeVertexInputCapabilities.base.U && beatAddr<NativeVertexInputCapabilities.end.U else false.B
    val targetFragmentInput=if(nativeVertexInput)beatAddr>=NativeFragmentInputCapabilities.base.U && beatAddr<NativeFragmentInputCapabilities.end.U else false.B
    val targetRaster=if(nativeVertexInput)beatAddr>=NativeRasterCapabilities.base.U && beatAddr<NativeRasterCapabilities.end.U else false.B
    val targetTexturePolicy=if(nativeVertexInput)beatAddr>=opengpu.graphics.NativeTextureCapabilities.base.U && beatAddr<opengpu.graphics.NativeTextureCapabilities.end.U else false.B
    val targetTextureFormats=if(nativeVertexInput)beatAddr>=opengpu.graphics.NativeTextureFormatCapabilities.base.U && beatAddr<opengpu.graphics.NativeTextureFormatCapabilities.end.U else false.B
    val targetTextureOperations=if(nativeVertexInput)beatAddr>=opengpu.graphics.NativeTextureOperationCapabilities.base.U && beatAddr<opengpu.graphics.NativeTextureOperationCapabilities.end.U else false.B
    val targetVisibilityQueries=if(visibilityQueries)beatAddr>=opengpu.graphics.NativeVisibilityQueryCapabilities.base.U &&
      beatAddr<opengpu.graphics.NativeVisibilityQueryCapabilities.end.U else false.B
    val targetFeedback=if(transformFeedback)beatAddr>=opengpu.graphics.NativeTransformFeedbackCapabilities.base.U &&
      beatAddr<opengpu.graphics.NativeTransformFeedbackCapabilities.end.U else false.B
    val targetPrimitiveQuery=if(primitiveQueries)beatAddr>=opengpu.graphics.NativePrimitiveQueryCapabilities.base.U &&
      beatAddr<opengpu.graphics.NativePrimitiveQueryCapabilities.end.U else false.B
    val targetShaderCaps = if (shaderExecutionLimits) beatAddr >= NativeShaderExecutionCapabilities.base.U &&
      beatAddr < NativeShaderExecutionCapabilities.quotaRegister.U else false.B
    val targetShaderQuota = if (shaderExecutionLimits) beatAddr === NativeShaderExecutionCapabilities.quotaRegister.U else false.B
    val targetShaderExecution = targetShaderCaps || targetShaderQuota
    val targetIndirectCompute = if(enableIndirectCompute)
      beatAddr >= opengpu.command.ComputeIndirectCapabilities.base.U &&
        beatAddr < opengpu.command.ComputeIndirectCapabilities.end.U else false.B
    val targetComputeResources = if(computeResources.nonEmpty)
      beatAddr >= ComputeResourceCapabilities.base.U && beatAddr < ComputeResourceCapabilities.end.U else false.B
    val targetUniformResources = if(nativeUniformResources)
      beatAddr >= opengpu.graphics.NativeUniformCapabilities.base.U && beatAddr < opengpu.graphics.NativeUniformCapabilities.end.U else false.B
    val targetBlendResources = if(nativeVertexInput)
      beatAddr >= opengpu.graphics.NativeDrawBufferBlendCapabilities.base.U &&
        beatAddr < opengpu.graphics.NativeDrawBufferBlendCapabilities.end.U else false.B
    val targetImages = if(imageAvailable)
      beatAddr >= opengpu.graphics.ShaderImageCommandMmioRegs.base.U &&
        beatAddr < opengpu.graphics.ShaderImageCommandMmioRegs.end.U else false.B
    val targetStorage = if(storageAvailable)
      beatAddr >= opengpu.graphics.StorageCommandMmioRegs.base.U &&
        beatAddr < opengpu.graphics.StorageCommandMmioRegs.end.U else false.B
    val beatOk = transferOk && !beatAddrWide(32) && (beatAddr & 0x3.U) === 0.U &&
      (beatAddr < mappedEnd || targetBinCapabilities || targetNativeDetails || targetNativeInput || targetFragmentInput || targetTexturePolicy || targetTextureFormats || targetRaster || targetVisibilityQueries || targetFeedback || targetPrimitiveQuery || targetShaderExecution || targetTextureOperations || targetIndirectCompute || targetComputeResources || targetUniformResources || targetBlendResources || targetImages || targetStorage)

    val writeBeat = writeActive && !bPending
    val wBeatFire = writeBeat && io.s_axi_wvalid && io.s_axi_wready
    val rBeatFire = readActive && io.s_axi_rvalid && io.s_axi_rready

    // Multiplex the single-ported register file between the read and write
    // channel (mutually exclusive by construction).
    val busRegValid = Mux(
      readActive, !readHeld, Mux(writeBeat, io.s_axi_wvalid, false.B))
    val targetUnified =
      (beatAddr >= GpuCommandMmioRegs.COMMAND_ID.U &&
        beatAddr < RenderHostRegs.MSAA_CONFIG.U) ||
        beatAddr === GpuCommandMmioRegs.RESET.U ||
        (beatAddr >= GpuCommandMmioRegs.SAMPLE_MODE.U &&
          beatAddr < GpuCommandMmioRegs.END.U) || targetNativeDetails || targetImages || targetStorage
    reg.req.valid := busRegValid && beatOk && !targetUnified && !targetBinCapabilities && !targetBinDiscovery && !targetNativeInput && !targetFragmentInput && !targetTexturePolicy && !targetTextureFormats && !targetRaster && !targetVisibilityQueries && !targetFeedback && !targetShaderExecution && !targetPrimitiveQuery && !targetTextureOperations && !targetIndirectCompute && !targetComputeResources && !targetUniformResources && !targetBlendResources
    reg.req.bits.isWrite := writeActive
    reg.req.bits.addr := beatAddr(9, 0)
    reg.req.bits.data := io.s_axi_wdata
    reg.req.bits.strb := io.s_axi_wstrb
    unified.foreach { bridge =>
      bridge.io.reg.req.valid := busRegValid && beatOk && targetUnified && !targetBinCapabilities && !targetBinDiscovery && !targetNativeInput && !targetFragmentInput && !targetTexturePolicy && !targetTextureFormats && !targetRaster && !targetVisibilityQueries && !targetFeedback && !targetShaderExecution && !targetPrimitiveQuery && !targetTextureOperations && !targetIndirectCompute && !targetComputeResources && !targetUniformResources && !targetBlendResources
      bridge.io.reg.req.bits.isWrite := writeActive
      bridge.io.reg.req.bits.addr := beatAddr(10, 0)
      bridge.io.reg.req.bits.data := io.s_axi_wdata
      bridge.io.reg.req.bits.strb := io.s_axi_wstrb
    }

    // ---------------------------------------------------------------------
    // Read channel.
    // ---------------------------------------------------------------------
    io.s_axi_arready := grantRead
    when(io.s_axi_arvalid && io.s_axi_arready) {
      lastAddressWasWrite := false.B
      readActive := true.B
      addrReg := io.s_axi_araddr
      lenReg := io.s_axi_arlen
      transferValid := validTransfer(io.s_axi_araddr,io.s_axi_arlen,io.s_axi_arsize,io.s_axi_arburst)
      beatAddress := Cat(0.U(1.W),io.s_axi_araddr)
      burstReg := io.s_axi_arburst
      beat := 0.U
    }

    io.s_axi_rvalid := readActive
    io.s_axi_rlast := readActive && lastBeat
    io.s_axi_rresp := Mux(beatOk, 0.U, 2.U)
    val rdata = WireDefault(0.U(32.W))
    when(readActive) {
      rdata := unified.map { bridge =>
        Mux(targetUnified, bridge.io.reg.resp.bits.data, reg.resp.bits.data)
      }.getOrElse(reg.resp.bits.data)
      if (externalBinnedRender) {
        when(targetBinDiscovery) { rdata:=TileBinHostRegs.CAPABILITIES.U }
          .elsewhen(targetBinCapabilities) { rdata:=io.binCapabilityWords.get(((beatAddr-TileBinHostRegs.CAPABILITIES.U)>>2)(2,0)) }
      }
      if(nativeVertexInput)when(targetNativeInput){
        rdata:=VecInit(nativeVertexWords.map(_.U(32.W)))(((beatAddr-NativeVertexInputCapabilities.base.U)>>2)(1,0))
      }
      if(nativeVertexInput)when(targetTexturePolicy){
        rdata:=VecInit(opengpu.graphics.NativeTextureCapabilities.words.map(_.U(32.W)))(((beatAddr-opengpu.graphics.NativeTextureCapabilities.base.U)>>2)(2,0))
      }
      if(nativeVertexInput)when(targetTextureFormats){
        rdata:=VecInit(opengpu.graphics.NativeTextureFormatCapabilities.words.map(_.U(32.W)))(((beatAddr-opengpu.graphics.NativeTextureFormatCapabilities.base.U)>>2)(2,0))
      }
      if(nativeVertexInput)when(targetTextureOperations){
        rdata:=VecInit(opengpu.graphics.NativeTextureOperationCapabilities.words.map(_.U(32.W)))(((beatAddr-opengpu.graphics.NativeTextureOperationCapabilities.base.U)>>2)(3,0))
      }
      if(nativeVertexInput)when(targetFragmentInput){
        rdata:=VecInit(NativeFragmentInputCapabilities.words(config.maxSampleCount).map(_.U(32.W)))(((beatAddr-NativeFragmentInputCapabilities.base.U)>>2)(1,0))
      }
      if(nativeVertexInput)when(targetRaster){
        rdata:=VecInit(NativeRasterCapabilities.words(config.maxSampleCount).map(_.U(32.W)))(((beatAddr-NativeRasterCapabilities.base.U)>>2)(2,0))
      }
      if(visibilityQueries)when(targetVisibilityQueries){
        rdata:=VecInit(opengpu.graphics.NativeVisibilityQueryCapabilities.words.map(_.U(32.W)))(
          ((beatAddr-opengpu.graphics.NativeVisibilityQueryCapabilities.base.U)>>2)(2,0))
      }
      if(transformFeedback)when(targetFeedback){
        rdata:=VecInit(opengpu.graphics.NativeTransformFeedbackCapabilities.words(transformFeedbackSourceWords).map(_.U(32.W)))(
          ((beatAddr-opengpu.graphics.NativeTransformFeedbackCapabilities.base.U)>>2)(2,0))
      }
      if(primitiveQueries)when(targetPrimitiveQuery){
        rdata:=VecInit(opengpu.graphics.NativePrimitiveQueryCapabilities.words.map(_.U(32.W)))(
          ((beatAddr-opengpu.graphics.NativePrimitiveQueryCapabilities.base.U)>>2)(2,0))
      }
      if (shaderExecutionLimits) {
        when(targetShaderCaps) {
          rdata := VecInit(NativeShaderExecutionCapabilities.words.map(_.U(32.W)))(
            ((beatAddr - NativeShaderExecutionCapabilities.base.U) >> 2)(2, 0))
        }
        when(targetShaderQuota) { rdata := shaderQuota.get }
      }
      if(enableIndirectCompute)when(targetIndirectCompute) {
        rdata := VecInit(opengpu.command.ComputeIndirectCapabilities.words(computeConfig).map(_.U(32.W)))(
          ((beatAddr-opengpu.command.ComputeIndirectCapabilities.base.U)>>2)(2,0))
      }
      if(computeResources.nonEmpty)when(targetComputeResources) {
        rdata := VecInit(ComputeResourceCapabilities.words(computeResources.get,imageAvailable,storageAvailable).map(_.U(32.W)))(
          ((beatAddr-ComputeResourceCapabilities.base.U)>>2)(3,0))
      }
      if(nativeVertexInput)when(targetBlendResources) {
        rdata := VecInit(opengpu.graphics.NativeDrawBufferBlendCapabilities.words.map(_.U(32.W)))(
          ((beatAddr-opengpu.graphics.NativeDrawBufferBlendCapabilities.base.U)>>2)(2,0))
      }
      if(nativeUniformResources)when(targetUniformResources) {
        rdata := VecInit(opengpu.graphics.NativeUniformCapabilities.words.map(_.U(32.W)))(
          ((beatAddr-opengpu.graphics.NativeUniformCapabilities.base.U)>>2)(3,0))
      }
      when(rBeatFire) {
        when(lastBeat) {
          readActive := false.B
        }.otherwise {
          beat := beat + 1.U
          advanceBeatAddress()
        }
      }
    }
    val offeredReadData = Mux(beatOk, rdata, 0.U)
    io.s_axi_rdata := Mux(readHeld, heldReadData, offeredReadData)
    // The first offered beat can bypass the holding register. Once stalled,
    // retain that exact value even if completion/status changes underneath it.
    // Capturing only on a stall preserves the existing unstalled beat rate.
    when(readActive && !readHeld && !io.s_axi_rready) {
      readHeld := true.B
      heldReadData := offeredReadData
    }
    when(rBeatFire) { readHeld := false.B }

    // ---------------------------------------------------------------------
    // Write channel.
    // ---------------------------------------------------------------------
    io.s_axi_awready := grantWrite
    when(io.s_axi_awvalid && io.s_axi_awready) {
      lastAddressWasWrite := true.B
      writeActive := true.B
      addrReg := io.s_axi_awaddr
      lenReg := io.s_axi_awlen
      transferValid := validTransfer(io.s_axi_awaddr,io.s_axi_awlen,io.s_axi_awsize,io.s_axi_awburst)
      beatAddress := Cat(0.U(1.W),io.s_axi_awaddr)
      burstReg := io.s_axi_awburst
      beat := 0.U
      bPending := false.B
      writeError := false.B
    }
    io.s_axi_wready := writeBeat
    val shaderQuotaWriteOk = WireDefault(false.B)
    if (shaderExecutionLimits) {
      val mask = Cat((0 until 4).reverse.map(i => Fill(8, io.s_axi_wstrb(i))))
      val merged = (shaderQuota.get & ~mask) | (io.s_axi_wdata & mask)
      shaderQuotaWriteOk := shaderQuotaIdle && merged =/= 0.U
      when(wBeatFire && beatOk && targetShaderQuota && shaderQuotaWriteOk) { shaderQuota.get := merged }
    }
    when(wBeatFire) {
      val normalRegisterOk = unified.map(bridge =>
        Mux(targetUnified, bridge.io.reg.resp.bits.ok, reg.resp.bits.ok))
        .getOrElse(reg.resp.bits.ok)
      val legacyRegisterOk = !targetBinCapabilities && !targetBinDiscovery && !targetNativeInput && !targetFragmentInput && !targetTexturePolicy && !targetTextureFormats && !targetRaster && !targetVisibilityQueries && !targetFeedback && !targetPrimitiveQuery && !targetTextureOperations && !targetIndirectCompute && !targetComputeResources && !targetUniformResources && !targetBlendResources && normalRegisterOk
      val registerOk = Mux(targetShaderExecution, targetShaderQuota && shaderQuotaWriteOk, legacyRegisterOk)
      when(!beatOk || !registerOk) { writeError := true.B }
      when(io.s_axi_wlast) {
        bPending := true.B
      }.otherwise {
        beat := beat + 1.U
        advanceBeatAddress()
      }
    }

    io.s_axi_bvalid := bPending
    io.s_axi_bresp := Mux(writeError, 2.U, 0.U)
    when(io.s_axi_bvalid && io.s_axi_bready) {
      bPending := false.B
      writeActive := false.B
    }
  }
}
