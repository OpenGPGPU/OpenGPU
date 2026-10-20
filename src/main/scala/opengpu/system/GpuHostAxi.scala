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
  nativeVertexInput: Boolean = false
) extends Module {
  require(!protectVmState || unifiedCommandMmio)
  require(!persistentAttachments || (unifiedCommandMmio && protectVmState))
  require(!externalBinnedRender || (unifiedCommandMmio && protectVmState && !persistentAttachments))
  require(!nativeCompletionInfo || externalBinnedRender)
  require(!nativeVertexInput || externalBinnedRender)
  override def desiredName: String = "GpuHostAxi"

  val io = IO(new Bundle {
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
      Some(Decoupled(new GpuCommand(gpuConfig, commandIdWidth)))
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
    io.commandReadsIdle := host.io.commandReadsIdle
    io.graphicsDrained := host.io.drained && io.binDrained.getOrElse(true.B) && binCompletionDrained
    io.performance := host.io.performance
    val unified = if (unifiedCommandMmio) {
      Some(Module(new GpuCommandMmio(
        gpuConfig, commandIdWidth, gpuConfig.commandQueueDepth,
        shaderComputeUnits = sharedShader.map(_.numComputeUnits).getOrElse(1),
        shaderKernargBanks = sharedShader.map(_.fragmentBanks).getOrElse(2),
        protectVmState = protectVmState,nativeCompletionInfo=nativeCompletionInfo)))
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
        val port=Module(new TileBinHostCommandPort(gpuConfig,commandIdWidth,nativeCompletionInfo))
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
    val sizeReg = RegInit(0.U(3.W))
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
    val beatAddrWide=Mux(burstReg===1.U,addrReg+&(beat<<2),Cat(0.U(1.W),addrReg))
    val beatAddr=beatAddrWide(31,0)
    val burstEnd=addrReg(11,0)+&Mux(burstReg===1.U,(lenReg+&1.U)<<2,4.U)
    val transferOk=sizeReg===2.U && burstReg<=1.U && burstEnd<=4096.U &&
      (burstReg=/=0.U || lenReg<16.U)
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
    val beatOk = transferOk && !beatAddrWide(32) && (beatAddr & 0x3.U) === 0.U &&
      (beatAddr < mappedEnd || targetBinCapabilities || targetNativeDetails || targetNativeInput || targetFragmentInput || targetTexturePolicy || targetTextureFormats || targetTextureOperations || targetRaster)

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
          beatAddr < GpuCommandMmioRegs.END.U) || targetNativeDetails
    reg.req.valid := busRegValid && beatOk && !targetUnified && !targetBinCapabilities && !targetBinDiscovery && !targetNativeInput && !targetFragmentInput && !targetTexturePolicy && !targetTextureFormats && !targetTextureOperations && !targetRaster
    reg.req.bits.isWrite := writeActive
    reg.req.bits.addr := beatAddr(9, 0)
    reg.req.bits.data := io.s_axi_wdata
    reg.req.bits.strb := io.s_axi_wstrb
    unified.foreach { bridge =>
      bridge.io.reg.req.valid := busRegValid && beatOk && targetUnified && !targetBinCapabilities && !targetBinDiscovery && !targetNativeInput && !targetFragmentInput && !targetTexturePolicy && !targetTextureFormats && !targetTextureOperations && !targetRaster
      bridge.io.reg.req.bits.isWrite := writeActive
      bridge.io.reg.req.bits.addr := beatAddr(9, 0)
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
      sizeReg := io.s_axi_arsize
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
        rdata:=VecInit(NativeVertexInputCapabilities.words.map(_.U(32.W)))(((beatAddr-NativeVertexInputCapabilities.base.U)>>2)(1,0))
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
      when(rBeatFire) {
        when(lastBeat) {
          readActive := false.B
        }.otherwise {
          beat := beat + 1.U
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
      sizeReg := io.s_axi_awsize
      burstReg := io.s_axi_awburst
      beat := 0.U
      bPending := false.B
      writeError := false.B
    }
    io.s_axi_wready := writeBeat
    when(wBeatFire) {
      val normalRegisterOk = unified.map(bridge =>
        Mux(targetUnified, bridge.io.reg.resp.bits.ok, reg.resp.bits.ok))
        .getOrElse(reg.resp.bits.ok)
      val registerOk = !targetBinCapabilities && !targetBinDiscovery && !targetNativeInput && !targetFragmentInput && !targetTexturePolicy && !targetTextureFormats && !targetTextureOperations && !targetRaster && normalRegisterOk
      when(!beatOk || !registerOk) { writeError := true.B }
      when(io.s_axi_wlast) {
        bPending := true.B
      }.otherwise {
        beat := beat + 1.U
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
