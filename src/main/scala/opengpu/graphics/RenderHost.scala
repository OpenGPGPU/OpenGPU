package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.{SharedCuGraphicsPort, SharedCuShaderConfig}
import opengpu.command.{RenderCompletion, RenderDescriptor, RenderStatus}
import opengpu.dma.{CopyEngine, FillEngine, StridedCopyEngine}
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** Render status, legacy configuration shadows, DMA and scanout registers.
  * Unified descriptors are the sole source of render launch configuration. */
object RenderHostRegs {
  /** First invalid byte offset (exclusive end of the register file). */
  val END               = 0x148
  val ID                = 0x00
  val CONTROL           = 0x04
  val STATUS            = 0x08
  val IRQ               = 0x0C
  val CMD_BASE          = 0x10
  val CMD_COUNT         = 0x14
  val COLOR_BASE        = 0x18
  val DEPTH_BASE        = 0x1C
  val STRIDE            = 0x20
  val DEPTH_TEST_ENABLE = 0x24
  val DEPTH_FUNC        = 0x28
  val DEPTH_WRITE_ENABLE= 0x2C
  val CULL_MODE         = 0x30
  val TEX_BASE          = 0x34
  val TEX_WIDTH         = 0x38
  val TEX_HEIGHT        = 0x3C
  /** bit0: CLAMP; bits[5:2]: max mip level; bit8: sampling enable. */
  val TEX_CONFIG        = 0x40
  /** Display registers are independent of the execution COLOR_BASE/STRIDE. */
  val SCANOUT_BASE      = 0x44
  val SCANOUT_STRIDE    = 0x48
  val SCANOUT_WIDTH     = 0x4C
  val SCANOUT_HEIGHT    = 0x50
  /** 0: packed RGBA8888 (0xRRGGBBAA), 1: DRM XRGB8888. */
  val SCANOUT_FORMAT    = 0x54
  /** bit0: scanout enable; bit1: hardware vblank IRQ enable. */
  val SCANOUT_CONTROL   = 0x58
  /** bit0: active (enable && non-zero base/stride/size). */
  val SCANOUT_STATUS    = 0x5C
  /** bit0: fragment core; bit2: vertex core;
    * bits 15:8: fragment batch capacity; bit21: hardware vblank. */
  val CAPABILITIES      = 0x60
  /** AXI clocks between vblank IRQs while scanout is active and CONTROL.bit1
    * is set. Zero disables the counter. */
  val SCANOUT_PERIOD    = 0x64
  /* 0x68..0x84 remain reserved (retired job ring / IH). */
  /** Hardware clear (FillEngine): destination base, byte count (multiple of
    * the 64-byte line) and the 32-bit fill pattern. */
  val CLEAR_BASE        = 0x88
  val CLEAR_BYTES       = 0x8C
  val CLEAR_PATTERN     = 0x90
  /** Write 1 to start one clear of the programmed range. */
  val CLEAR_START       = 0x94
  /** Hardware colour blit (CopyEngine): 64-byte aligned, non-overlapping
    * source/destination regions and a byte count that is a multiple of 64. */
  val BLIT_SRC_BASE     = 0x98
  val BLIT_DST_BASE     = 0x9C
  val BLIT_BYTES        = 0xA0
  /** Write 1 to start one blit of the programmed range. */
  val BLIT_START        = 0xA4
  /** Hardware two-dimensional copy (StridedCopyEngine). */
  val STRIDED_SRC_BASE  = 0xA8
  val STRIDED_DST_BASE  = 0xAC
  val STRIDED_WIDTH     = 0xB0
  val STRIDED_HEIGHT    = 0xB4
  val STRIDED_SRC_STRIDE= 0xB8
  val STRIDED_DST_STRIDE= 0xBC
  val STRIDED_START     = 0xC0
  /** Legacy sample-mode shadow; unified renders use descriptor word 9. */
  val MSAA_CONFIG       = 0x134
  /** The unified-command block owns 0xC4..0x130 plus RESET at 0x138, so the
    * stencil/blend registers live immediately after it.  Layouts mirror the
    * per-draw record words 36/37/35 (see CommandBufferStage). */
  /** bit0 stencil-test enable; bits[6:4] func; bits[9:7] fail op;
    * bits[12:10] z-fail op; bits[15:13] z-pass op. */
  val STENCIL_CONFIG    = 0x13c
  /** bits[7:0] ref, bits[15:8] read mask, bits[23:16] write mask. */
  val STENCIL_REF_MASKS = 0x140
  /** bit0 present; bits[7:4] src factor; bits[11:8] dst factor;
    * bits[14:12] equation.  Present overrides legacy source-over. */
  val BLEND_CONFIG      = 0x144
}

/** A host memory-mapped register access (read or write of one 32-bit word). */
class RenderHostRegRequest(val addressWidth: Int = 10) extends Bundle {
  /** Byte address within the register file. Only 4-byte-aligned offsets are
    * valid; `ok` goes low for unaligned or unmapped addresses. */
  val addr = UInt(addressWidth.W)
  val data = UInt(32.W)
  /** Byte lane write-enable; for reads this is ignored. */
  val strb = UInt(4.W)
  val isWrite = Bool()
}

/** Response to a register read. Writes are fire-and-forget (acknowledged by
  * the request channel's `ready`), so the response channel only carries reads. */
class RenderHostRegResponse extends Bundle {
  val data = UInt(32.W)
  /** Combinational disposition of the current request. The AXI adapter also
    * samples this on accepted writes; response.valid qualifies read data. */
  val ok = Bool()
}

/** Unified render descriptor fetch, launch, retirement and host register bank.
  * Command/framebuffer/shader ports attach to the shared GPU memory hierarchy.
  * Legacy render START is inert; dedicated DMA and scanout registers remain.
  */
class RenderHost(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  vertCore: Boolean = false,
  deviceId: Int = 0x4755, // 'GU'
  version: Int = 0x0001,
  unifiedCommands: Boolean = false,
  textureFaultReporting: Boolean = false,
  commandIdWidth: Int = 8,
  computeScalarFpu: Boolean = false,
  graphicsScalarFpu: Boolean = true,
  sharedShader: Option[SharedCuShaderConfig] = None,
  protectedVmState: Boolean = false,
  persistentAttachments: Boolean = false,
  tileStoreCredits: Int = opengpu.config.MobileGpuConfig().attachmentStoreCredits,
  useTileSramBlackBoxes: Boolean = false,
  externalBinnedRender: Boolean = false
) extends Module {
  require(!persistentAttachments || (unifiedCommands && config.tileAttachments && !vertCore && !config.tileBinning))
  require(!externalBinnedRender || (unifiedCommands && !persistentAttachments))
  override def desiredName: String = "RenderHost"
  private val maxSampleMode = log2Ceil(config.maxSampleCount)
  private val stagingTransactions = SharedCuShaderConfig.stagingTransactions(sharedShader)
  private val kernelWordTransactions = SharedCuShaderConfig.kernelWordTransactions(sharedShader)

  val io = IO(new Bundle {
    val shared = sharedShader.filter(_ => !externalBinnedRender).map(pool => Flipped(new SharedCuGraphicsPort(
      gpuConfig, pool.taskIdWidth, pool.numComputeUnits)))
    val reg = new Bundle {
      val req = Flipped(Decoupled(new RenderHostRegRequest))
      val resp = Decoupled(new RenderHostRegResponse)
    }
    /** Graphics/unified completion interrupt; IRQ.PENDING && IRQ.ENABLE. */
    val irq = Output(Bool())
    /** Completion event from an integrated compute/command subsystem. */
    val externalCompletion = Input(Bool())
    val instructionSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(gpuConfig)))
    val vectorSatp = Input(UInt(32.W))
    val vectorTlbFlush = Flipped(Valid(new opengpu.core.memory.VectorTlbFlush(gpuConfig)))
    /** Pulse when a failed texture response is consumed by the renderer. */
    val textureFault = if (textureFaultReporting) Some(Input(Bool())) else None
    val commandFault = if (persistentAttachments) Some(Input(Bool())) else None
    val attachmentFault = if (persistentAttachments) Some(Input(Bool())) else None
    val tileTextureFault = if (persistentAttachments) Some(Input(Bool())) else None
    val tileCancel = if (persistentAttachments) Some(Input(Bool())) else None

    /** Discard command-line reuse outside a render job, independently of DMA. */
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
    /** VM descriptor binding: legacy word fetch or bounded persistent line
      * fetch, selected explicitly by the elaborated host profile. */
    val renderCommand = Flipped(Decoupled(
      new RenderDescriptor(gpuConfig, commandIdWidth, persistentAttachments)))
    val renderCompletion = Decoupled(new RenderCompletion(commandIdWidth))
    /** Includes descriptor fetch, launch, execution and unconsumed completion. */
    val externalRenderBusy = if (externalBinnedRender) Some(Input(Bool())) else None
    val drained = Output(Bool())
    val performance = Output(new GraphicsPerformanceEvents)
  })

  private val core = if (externalBinnedRender) None else Some(Module(new RenderCore(
    config, gpuConfig, fragCore, vertCore, graphicsScalarFpu, sharedShader,
    persistentAttachments, tileStoreCredits, useTileSramBlackBoxes)))
  private val coreIO = core.map(_.io).getOrElse {
    // External native rendering owns its own vertex/raster/fragment pipeline.
    // There is no legal legacy launch or persistent-tile submission here.
    // Retain the host-facing ports with no request or response ownership;
    // Fill/Copy/StridedCopy, scanout and native command ownership stay live.
    val idle = Wire(new RenderCoreIO(gpuConfig, None, false, false))
    idle.cbMem.req.valid := false.B
    idle.cbMem.req.bits := 0.U.asTypeOf(idle.cbMem.req.bits)
    idle.cbMem.resp.ready := false.B
    idle.fbMem.req.valid := false.B
    idle.fbMem.req.bits := 0.U.asTypeOf(idle.fbMem.req.bits)
    idle.fbMem.resp.ready := false.B
    idle.texMem.req.valid := false.B
    idle.texMem.req.bits := 0.U.asTypeOf(idle.texMem.req.bits)
    idle.texMem.resp.ready := false.B
    idle.kernelMemReq.valid := false.B
    idle.kernelMemReq.bits := 0.U.asTypeOf(idle.kernelMemReq.bits)
    idle.kernelMemResp.ready := false.B
    idle.kernelWordMemReq.valid := false.B
    idle.kernelWordMemReq.bits := 0.U.asTypeOf(idle.kernelWordMemReq.bits)
    idle.kernelWordMemResp.ready := false.B
    idle.kernelL1Invalidate.ready := false.B
    idle.kernelL1InvalidateDone.valid := false.B
    idle.kernelL1InvalidateDone.bits := 0.U.asTypeOf(idle.kernelL1InvalidateDone.bits)
    idle.kernelGlobalAtomicRequest.valid := false.B
    idle.kernelGlobalAtomicRequest.bits := 0.U.asTypeOf(idle.kernelGlobalAtomicRequest.bits)
    idle.kernelGlobalAtomicResponse.ready := false.B
    idle.shaderFault := false.B
    idle.performance := 0.U.asTypeOf(idle.performance)
    idle.done := true.B
    idle
  }
  private val tileHost = if (persistentAttachments) Some(Module(new TileJobDescriptorReader(
    gpuConfig, config, commandIdWidth, kernelWordTransactions, stagingTransactions))) else None
  sharedShader.filter(_ => !externalBinnedRender).foreach(_ => coreIO.shared.get <> io.shared.get)
  coreIO.instructionSatp := io.instructionSatp
  coreIO.instructionTlbFlush := io.instructionTlbFlush
  coreIO.vectorSatp := io.vectorSatp
  coreIO.vectorTlbFlush := io.vectorTlbFlush
  io.performance := coreIO.performance
  tileHost.foreach { host =>
    coreIO.tileJob.get <> host.io.job
    host.io.passCompletion <> coreIO.tileCompletion.get
    coreIO.tileCancel.get := host.io.cancelCore
    coreIO.commandMemoryFault.get := io.commandFault.get
    coreIO.attachmentMemoryFault.get := io.attachmentFault.get
    coreIO.textureMemoryFault.get := io.tileTextureFault.get
    host.io.cancel := io.tileCancel.get
    host.io.commandReadAccepted := coreIO.cbMem.req.fire
  }

  // The DMA engines share the kernelWordMem line port with the core's
  // staging/sampler bridge. Responses are routed by transaction-ID range:
  // The core owns the CU-scaled staging range; the DMA engines share the
  // following four IDs and descriptor admission makes them mutually exclusive.
  private val fill = Module(new FillEngine(gpuConfig, maxOutstanding = kernelWordTransactions,
    transactionIdBase = stagingTransactions))
  private val blit = Module(new CopyEngine(gpuConfig, maxOutstanding = kernelWordTransactions,
    lineSlots = 2, transactionIdBase = stagingTransactions))
  private val strided = Module(new StridedCopyEngine(gpuConfig,
    maxOutstanding = kernelWordTransactions, transactionIdBase = stagingTransactions))
  private val dmaIdle :: dmaFill :: dmaBlit :: dmaStrided :: Nil = Enum(4)
  private val dmaOwner = RegInit(dmaIdle)
  // Hardware clear (FillEngine) programming.
  private val clearBaseReg = RegInit(0.U(32.W))
  private val clearBytesReg = RegInit(0.U(32.W))
  private val clearPatternReg = RegInit(0.U(32.W))
  private val blitSrcBaseReg = RegInit(0.U(32.W))
  private val blitDstBaseReg = RegInit(0.U(32.W))
  private val blitBytesReg = RegInit(0.U(32.W))
  private val stridedSrcBaseReg = RegInit(0.U(32.W))
  private val stridedDstBaseReg = RegInit(0.U(32.W))
  private val stridedWidthReg = RegInit(0.U(32.W))
  private val stridedHeightReg = RegInit(0.U(32.W))
  private val stridedSrcStrideReg = RegInit(0.U(32.W))
  private val stridedDstStrideReg = RegInit(0.U(32.W))
  // Descriptor fetch, legacy DMA and core staging use disjoint lifetimes.
  // The descriptor reuses four DMA IDs before any shader task is launched.
  private val descriptorRequest = tileHost.map(_.io.memoryRequest)
  io.kernelWordMemReq.valid := descriptorRequest.map(_.valid).getOrElse(false.B) ||
    fill.io.memoryRequest.valid || blit.io.memoryRequest.valid ||
      strided.io.memoryRequest.valid ||
      coreIO.kernelWordMemReq.valid
  private val coreWordRequest=Wire(new ComputeMemoryRequest(gpuConfig,64,kernelWordTransactions))
  coreWordRequest:=coreIO.kernelWordMemReq.bits
  private val regularWordRequest = Mux(fill.io.memoryRequest.valid,
    fill.io.memoryRequest.bits, Mux(blit.io.memoryRequest.valid,
      blit.io.memoryRequest.bits, Mux(strided.io.memoryRequest.valid,
        strided.io.memoryRequest.bits, coreWordRequest)))
  io.kernelWordMemReq.bits := descriptorRequest.map(r => Mux(r.valid, r.bits, regularWordRequest))
    .getOrElse(regularWordRequest)
  descriptorRequest.foreach(_.ready := io.kernelWordMemReq.ready)
  private val descriptorOffered = descriptorRequest.map(_.valid).getOrElse(false.B)
  fill.io.memoryRequest.ready :=
    io.kernelWordMemReq.ready && !descriptorOffered && fill.io.memoryRequest.valid
  blit.io.memoryRequest.ready :=
    io.kernelWordMemReq.ready && !descriptorOffered && !fill.io.memoryRequest.valid &&
      blit.io.memoryRequest.valid
  strided.io.memoryRequest.ready :=
    io.kernelWordMemReq.ready && !descriptorOffered && !fill.io.memoryRequest.valid &&
      !blit.io.memoryRequest.valid && strided.io.memoryRequest.valid
  coreIO.kernelWordMemReq.ready :=
    io.kernelWordMemReq.ready && !descriptorOffered && !fill.io.memoryRequest.valid &&
      !blit.io.memoryRequest.valid && !strided.io.memoryRequest.valid

  private val wordRespIsDma = io.kernelWordMemResp.bits.transactionId >= stagingTransactions.U
  private val wordRespIsTile = tileHost.map(_.io.fetching).getOrElse(false.B) && wordRespIsDma
  tileHost.foreach { host =>
    host.io.memoryResponse.valid := io.kernelWordMemResp.valid && wordRespIsTile
    host.io.memoryResponse.bits := io.kernelWordMemResp.bits
  }
  private val wordRespIsBlit = wordRespIsDma && dmaOwner === dmaBlit
  private val wordRespIsFill = wordRespIsDma && dmaOwner === dmaFill
  private val wordRespIsStrided = wordRespIsDma && dmaOwner === dmaStrided
  // Responses keep the global transaction ID; each DMA engine subtracts its
  // transactionIdBase to recover its local slot.
  fill.io.memoryResponse.valid := io.kernelWordMemResp.valid && wordRespIsFill
  fill.io.memoryResponse.bits := io.kernelWordMemResp.bits
  blit.io.memoryResponse.valid := io.kernelWordMemResp.valid && wordRespIsBlit
  blit.io.memoryResponse.bits := io.kernelWordMemResp.bits
  strided.io.memoryResponse.valid :=
    io.kernelWordMemResp.valid && wordRespIsStrided
  strided.io.memoryResponse.bits := io.kernelWordMemResp.bits
  coreIO.kernelWordMemResp.valid :=
    io.kernelWordMemResp.valid && !wordRespIsFill && !wordRespIsBlit &&
      !wordRespIsStrided && !wordRespIsTile
  coreIO.kernelWordMemResp.bits := io.kernelWordMemResp.bits
  private val regularWordResponseReady =
    Mux(wordRespIsStrided, strided.io.memoryResponse.ready,
      Mux(wordRespIsBlit, blit.io.memoryResponse.ready,
      Mux(wordRespIsFill, fill.io.memoryResponse.ready,
        coreIO.kernelWordMemResp.ready)))
  io.kernelWordMemResp.ready := tileHost.map(h =>
    Mux(wordRespIsTile, h.io.memoryResponse.ready, regularWordResponseReady))
    .getOrElse(regularWordResponseReady)

  fill.io.descriptor.bits.descriptorId := 0.U
  fill.io.descriptor.bits.destinationAddress := clearBaseReg
  fill.io.descriptor.bits.bytes := clearBytesReg
  fill.io.descriptor.bits.pattern := clearPatternReg
  fill.io.completion.ready := true.B
  blit.io.descriptor.bits.descriptorId := 0.U
  blit.io.descriptor.bits.sourceAddress := blitSrcBaseReg
  blit.io.descriptor.bits.destinationAddress := blitDstBaseReg
  blit.io.descriptor.bits.bytes := blitBytesReg
  blit.io.completion.ready := true.B
  strided.io.descriptor.bits.descriptorId := 0.U
  strided.io.descriptor.bits.sourceAddress := stridedSrcBaseReg
  strided.io.descriptor.bits.destinationAddress := stridedDstBaseReg
  strided.io.descriptor.bits.widthBytes := stridedWidthReg
  strided.io.descriptor.bits.height := stridedHeightReg
  strided.io.descriptor.bits.sourceStride := stridedSrcStrideReg
  strided.io.descriptor.bits.destinationStride := stridedDstStrideReg
  strided.io.completion.ready := true.B

  // ---------------------------------------------------------------------------
  // Registers (host-visible) and the shadow the engine runs from.
  // ---------------------------------------------------------------------------
  private val cmdBaseReg = RegInit(0.U(32.W))
  private val cmdCountReg = RegInit(0.U(32.W))
  private val colorBaseReg = RegInit(0.U(32.W))
  private val depthBaseReg = RegInit(0.U(32.W))
  private val strideReg = RegInit(0.U(32.W))
  private val depthTestEnableReg = RegInit(0.U(32.W))
  private val depthFuncReg = RegInit(0.U(32.W))
  private val depthWriteEnableReg = RegInit(0.U(32.W))
  private val cullModeReg = RegInit(0.U(32.W))
  private val texBaseReg = RegInit(0.U(32.W))
  private val texWidthReg = RegInit(0.U(32.W))
  private val texHeightReg = RegInit(0.U(32.W))
  private val texConfigReg = RegInit(0.U(32.W))
  private val msaaConfigReg = RegInit(0.U(32.W))
  private val stencilConfigReg = RegInit(0.U(32.W))
  private val stencilRefMasksReg = RegInit(0.U(32.W))
  private val blendConfigReg = RegInit(0.U(32.W))
  private val scanoutBaseReg = RegInit(0.U(32.W))
  private val scanoutStrideReg = RegInit(0.U(32.W))
  private val scanoutWidthReg = RegInit(0.U(32.W))
  private val scanoutHeightReg = RegInit(0.U(32.W))
  /** Render viewport. A non-zero scanout size latches it; clearing scanout
    * (disable) leaves the last mode in place. Reset matches the elaborated
    * maximum, so an unprogrammed device keeps the previous contract. */
  private val viewportW = RegInit(config.screenWidth.U(16.W))
  private val viewportH = RegInit(config.screenHeight.U(16.W))
  private val scanoutFormatReg = RegInit(0.U(32.W))
  private val scanoutControlReg = RegInit(0.U(32.W))
  private val scanoutPeriodReg = RegInit(0.U(32.W))
  private val scanoutCounter = RegInit(0.U(32.W))

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val error = RegInit(false.B)
  // Per-job state is separate from STATUS.ERROR, which software can clear.
  private val memoryFaulted = RegInit(false.B)
  private val irqEnable = RegInit(false.B)
  private val irqPending = RegInit(false.B)
  private val vblankPending = RegInit(false.B)
  private val sawBusy = RegInit(false.B)
  /** True while the running job was launched by a unified render command. */
  private val ownerUnified = RegInit(false.B)

  // Unified render command: fetch the 16-word render descriptor through the
  // translated command port, then launch from a packed register snapshot.
  private val renderFetching = RegInit(false.B)
  private val descOutstanding = RegInit(false.B)
  private val renderWord = RegInit(0.U(4.W))
  private val renderWords = Reg(Vec(16, UInt(32.W)))
  private val renderId = RegInit(0.U(commandIdWidth.W))
  private val renderBytes = RegInit(0.U(64.W))
  private val renderDonePending = RegInit(false.B)
  private val renderLaunch = RegInit(false.B)
  private val renderAddr = RegInit(0.U(gpuConfig.xLen.W))
  private val invalidSampleMode = RegInit(false.B)
  private val memoryFaultNow = (io.textureFault.getOrElse(false.B) || coreIO.shaderFault) &&
    (busy || renderFetching || renderLaunch)
  private val validRenderMode = Msaa.validModeWord(renderWords(9), config.maxSampleCount)
  private val tileHostBusy = tileHost.map(_.io.busy).getOrElse(false.B)
  io.commandReadsIdle := !renderFetching && !renderLaunch && !busy && !tileHostBusy
  io.drained := !renderFetching && !renderLaunch && !busy &&
    !renderDonePending && dmaOwner === dmaIdle &&
    !fill.io.busy && !blit.io.busy && !strided.io.busy && !tileHostBusy

  private val id = ((deviceId & 0xffff) << 16 | (version & 0xffff)).U

  // ---------------------------------------------------------------------------
  // User draws and the probe self-test both submit unified render commands.
  // ---------------------------------------------------------------------------
  /** Per-job configuration decoded from a unified render descriptor. */
  private val unifiedCfg = RegInit(0.U.asTypeOf(new JobConfig()))

  // ---------------------------------------------------------------------------
  // Register read (combinational) and write (registered) access.
  // ---------------------------------------------------------------------------
  private val rAddr = io.reg.req.bits.addr
  private val wFire = io.reg.req.fire && io.reg.req.bits.isWrite

  private val clearStartWrite =
    wFire && rAddr === RenderHostRegs.CLEAR_START.U && io.reg.req.bits.data(0)
  private val blitStartWrite =
    wFire && rAddr === RenderHostRegs.BLIT_START.U && io.reg.req.bits.data(0)
  private val stridedStartWrite =
    wFire && rAddr === RenderHostRegs.STRIDED_START.U && io.reg.req.bits.data(0)
  // DMA clears/copies can address the same attachments. While a tile is
  // resident, those words are newer than external memory, so serialize these
  // operations with render jobs in the opt-in tile-store configuration.
  private val tileDmaAllowed = if (config.tileAttachments)
    !busy && !renderFetching && !renderLaunch && !renderDonePending && !tileHostBusy && !io.externalRenderBusy.getOrElse(false.B)
  else !io.externalRenderBusy.getOrElse(false.B)
  fill.io.descriptor.valid := clearStartWrite && dmaOwner === dmaIdle &&
    !blitStartWrite && !stridedStartWrite && tileDmaAllowed
  blit.io.descriptor.valid := blitStartWrite && dmaOwner === dmaIdle &&
    !clearStartWrite && !stridedStartWrite && tileDmaAllowed
  strided.io.descriptor.valid := stridedStartWrite && dmaOwner === dmaIdle &&
    !clearStartWrite && !blitStartWrite && tileDmaAllowed
  when(fill.io.descriptor.fire) { dmaOwner := dmaFill }
  when(blit.io.descriptor.fire) { dmaOwner := dmaBlit }
  when(strided.io.descriptor.fire) { dmaOwner := dmaStrided }
  when(fill.io.completion.fire || blit.io.completion.fire ||
      strided.io.completion.fire) {
    dmaOwner := dmaIdle
  }

  // Queue launches are held off when a legacy START pulses the same cycle.
  // (The job ring is retired; no queue launches occur.)

  private val statusBits =
    Cat(0.U(26.W), strided.io.busy, blit.io.busy, fill.io.busy,
      error, done, busy)
  private val irqBits = Cat(0.U(29.W), vblankPending, irqPending, irqEnable)
  private val scanoutActive = scanoutControlReg(0) &&
    scanoutBaseReg.orR && scanoutStrideReg.orR &&
    scanoutWidthReg.orR && scanoutHeightReg.orR
  private val scanoutVblankEn = scanoutControlReg(1)
  private val vblankArmed = scanoutActive && scanoutVblankEn && scanoutPeriodReg.orR
  private val scanoutStatusBits = Cat(0.U(31.W), scanoutActive)
  private val capabilityBits =
    ((if (fragCore) (1 << GpuCapabilities.FragmentCore) else 0) |
      (if (vertCore) (1 << GpuCapabilities.VertexCore) else 0) |
      (if (unifiedCommands) (1 << GpuCapabilities.NativePixelFormats) else 0) |
      (if (unifiedCommands) (1 << GpuCapabilities.RenderExtents) else 0) |
      (if (unifiedCommands && fragCore) (1 << GpuCapabilities.FragmentInputUsage) else 0) |
      (if (unifiedCommands && fragCore) (1 << GpuCapabilities.FragmentOutputUsage) else 0) |
      (if (protectedVmState) (1 << GpuCapabilities.ProtectedVmState) else 0) |
      (1 << GpuCapabilities.ClearEngine) |
      (1 << GpuCapabilities.BlitEngine) |
      (1 << GpuCapabilities.StridedEngine) |
      (if (unifiedCommands) (1 << GpuCapabilities.UnifiedCommands) else 0) |
      (if (unifiedCommands && fragCore) (1 << GpuCapabilities.ShaderConfigInfo) else 0) |
      (if (unifiedCommands && fragCore) (1 << GpuCapabilities.RuntimeShaderAbi) else 0) |
      // Advertise MSAA only when a mode above 1x is supported. The backend is
      // selected by FragmentCore, independently of this capability bit.
      (if (config.maxSampleCount > 1) (1 << GpuCapabilities.Msaa) else 0) |
      (maxSampleMode << GpuCapabilities.MsaaMaxModeShift) |
      (if (unifiedCommands) (1 << GpuCapabilities.UnifiedReset) else 0) |
      (1 << GpuCapabilities.PersistentDepth) |
      (if (unifiedCommands && !persistentAttachments && !externalBinnedRender) (1 << GpuCapabilities.UnifiedRender) else 0) |
      (if (persistentAttachments) (1 << GpuCapabilities.PersistentTileJobs) else 0) |
      (if (persistentAttachments) (1L << GpuCapabilities.TypedTileCommands) else 0L) |
      (1 << GpuCapabilities.HwVblank) |
      // One bit covers every shader profile: flw is admitted for the
      // graphics validators only when the shader CU carries the FP
      // backend too, so both halves have to be present.
      (if (computeScalarFpu && graphicsScalarFpu)
        (1 << GpuCapabilities.ComputeScalarFpu) else 0) |
      (gpuConfig.warps * gpuConfig.lanes << GpuCapabilities.FragmentBatchShift))
      .U(32.W)

  private def readReg: UInt =
    MuxLookup(rAddr, 0.U(32.W))(Seq(
      RenderHostRegs.ID.U -> id,
      RenderHostRegs.STATUS.U -> statusBits,
      RenderHostRegs.IRQ.U -> irqBits,
      RenderHostRegs.CMD_BASE.U -> cmdBaseReg,
      RenderHostRegs.CMD_COUNT.U -> cmdCountReg,
      RenderHostRegs.COLOR_BASE.U -> colorBaseReg,
      RenderHostRegs.DEPTH_BASE.U -> depthBaseReg,
      RenderHostRegs.STRIDE.U -> strideReg,
      RenderHostRegs.DEPTH_TEST_ENABLE.U -> depthTestEnableReg,
      RenderHostRegs.DEPTH_FUNC.U -> depthFuncReg,
      RenderHostRegs.DEPTH_WRITE_ENABLE.U -> depthWriteEnableReg,
      RenderHostRegs.CULL_MODE.U -> cullModeReg,
      RenderHostRegs.TEX_BASE.U -> texBaseReg,
      RenderHostRegs.TEX_WIDTH.U -> texWidthReg,
      RenderHostRegs.TEX_HEIGHT.U -> texHeightReg,
      RenderHostRegs.TEX_CONFIG.U -> texConfigReg,
      RenderHostRegs.MSAA_CONFIG.U -> msaaConfigReg,
      RenderHostRegs.STENCIL_CONFIG.U -> stencilConfigReg,
      RenderHostRegs.STENCIL_REF_MASKS.U -> stencilRefMasksReg,
      RenderHostRegs.BLEND_CONFIG.U -> blendConfigReg,
      RenderHostRegs.SCANOUT_BASE.U -> scanoutBaseReg,
      RenderHostRegs.SCANOUT_STRIDE.U -> scanoutStrideReg,
      RenderHostRegs.SCANOUT_WIDTH.U -> scanoutWidthReg,
      RenderHostRegs.SCANOUT_HEIGHT.U -> scanoutHeightReg,
      RenderHostRegs.SCANOUT_FORMAT.U -> scanoutFormatReg,
      RenderHostRegs.SCANOUT_CONTROL.U -> scanoutControlReg,
      RenderHostRegs.SCANOUT_STATUS.U -> scanoutStatusBits,
      RenderHostRegs.CAPABILITIES.U -> capabilityBits,
      RenderHostRegs.SCANOUT_PERIOD.U -> scanoutPeriodReg,
      RenderHostRegs.CLEAR_BASE.U -> clearBaseReg,
      RenderHostRegs.CLEAR_BYTES.U -> clearBytesReg,
      RenderHostRegs.CLEAR_PATTERN.U -> clearPatternReg,
      RenderHostRegs.BLIT_SRC_BASE.U -> blitSrcBaseReg,
      RenderHostRegs.BLIT_DST_BASE.U -> blitDstBaseReg,
      RenderHostRegs.BLIT_BYTES.U -> blitBytesReg,
      RenderHostRegs.STRIDED_SRC_BASE.U -> stridedSrcBaseReg,
      RenderHostRegs.STRIDED_DST_BASE.U -> stridedDstBaseReg,
      RenderHostRegs.STRIDED_WIDTH.U -> stridedWidthReg,
      RenderHostRegs.STRIDED_HEIGHT.U -> stridedHeightReg,
      RenderHostRegs.STRIDED_SRC_STRIDE.U -> stridedSrcStrideReg,
      RenderHostRegs.STRIDED_DST_STRIDE.U -> stridedDstStrideReg
    ))

  // Merge a byte-masked write into a 32-bit register (partial-word writes with
  // a Strobe pattern, matching a host bus that can write sub-words).
  private def merge(regVal: UInt, data: UInt, strb: UInt): UInt = {
    val n = Wire(Vec(4, UInt(8.W)))
    for (i <- 0 until 4) {
      n(i) := Mux(strb(i), data((8 * i) + 7, 8 * i), regVal((8 * i) + 7, 8 * i))
    }
    n.asUInt
  }

  when(wFire) {
    switch(rAddr) {
      is(RenderHostRegs.CMD_BASE.U) {
        cmdBaseReg := merge(cmdBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CMD_COUNT.U) {
        cmdCountReg := merge(cmdCountReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.COLOR_BASE.U) {
        colorBaseReg := merge(colorBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_BASE.U) {
        depthBaseReg := merge(depthBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDE.U) {
        strideReg := merge(strideReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_TEST_ENABLE.U) {
        depthTestEnableReg := merge(
          depthTestEnableReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_FUNC.U) {
        depthFuncReg := merge(depthFuncReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_WRITE_ENABLE.U) {
        depthWriteEnableReg := merge(
          depthWriteEnableReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CULL_MODE.U) {
        cullModeReg := merge(cullModeReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_BASE.U) {
        texBaseReg := merge(texBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_WIDTH.U) {
        texWidthReg := merge(texWidthReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_HEIGHT.U) {
        texHeightReg := merge(texHeightReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_CONFIG.U) {
        texConfigReg := merge(texConfigReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_BASE.U) {
        scanoutBaseReg := merge(scanoutBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_STRIDE.U) {
        scanoutStrideReg := merge(scanoutStrideReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_WIDTH.U) {
        val written = merge(scanoutWidthReg, io.reg.req.bits.data, io.reg.req.bits.strb)
        scanoutWidthReg := written
        when(written(15, 0).orR) {
          viewportW := ScreenLimit(written, config.screenWidth)
        }
      }
      is(RenderHostRegs.SCANOUT_HEIGHT.U) {
        val written = merge(scanoutHeightReg, io.reg.req.bits.data, io.reg.req.bits.strb)
        scanoutHeightReg := written
        when(written(15, 0).orR) {
          viewportH := ScreenLimit(written, config.screenHeight)
        }
      }
      is(RenderHostRegs.SCANOUT_FORMAT.U) {
        scanoutFormatReg := merge(scanoutFormatReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_CONTROL.U) {
        scanoutControlReg := merge(
          scanoutControlReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_PERIOD.U) {
        scanoutPeriodReg := merge(
          scanoutPeriodReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STATUS.U) {
        when(io.reg.req.bits.data(1)) { done := false.B }
        when(io.reg.req.bits.data(2)) { error := false.B }
      }
      is(RenderHostRegs.IRQ.U) {
        irqEnable := io.reg.req.bits.data(0)
        when(io.reg.req.bits.data(1)) { irqPending := false.B }
        // vblankPending W1C is handled with the timer so ack beats wrap.
      }
      is(RenderHostRegs.CLEAR_BASE.U) {
        clearBaseReg := merge(clearBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CLEAR_BYTES.U) {
        clearBytesReg := merge(clearBytesReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CLEAR_PATTERN.U) {
        clearPatternReg := merge(clearPatternReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLIT_SRC_BASE.U) {
        blitSrcBaseReg := merge(blitSrcBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLIT_DST_BASE.U) {
        blitDstBaseReg := merge(blitDstBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLIT_BYTES.U) {
        blitBytesReg := merge(blitBytesReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_SRC_BASE.U) {
        stridedSrcBaseReg := merge(stridedSrcBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_DST_BASE.U) {
        stridedDstBaseReg := merge(stridedDstBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_WIDTH.U) {
        stridedWidthReg := merge(stridedWidthReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_HEIGHT.U) {
        stridedHeightReg := merge(stridedHeightReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_SRC_STRIDE.U) {
        stridedSrcStrideReg := merge(stridedSrcStrideReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_DST_STRIDE.U) {
        stridedDstStrideReg := merge(stridedDstStrideReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.MSAA_CONFIG.U) {
        msaaConfigReg := merge(msaaConfigReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STENCIL_CONFIG.U) {
        stencilConfigReg := merge(stencilConfigReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STENCIL_REF_MASKS.U) {
        stencilRefMasksReg := merge(stencilRefMasksReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLEND_CONFIG.U) {
        blendConfigReg := merge(blendConfigReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
    }
  }

  // Only the unified render command launches the engine; the legacy register
  // START snapshot was retired.

  when(blitStartWrite &&
      (dmaOwner =/= dmaIdle || clearStartWrite || stridedStartWrite ||
        !tileDmaAllowed)) {
    error := true.B
  }
  when(clearStartWrite &&
      (dmaOwner =/= dmaIdle || blitStartWrite || stridedStartWrite ||
        !tileDmaAllowed)) {
    error := true.B
  }
  when(stridedStartWrite &&
      (dmaOwner =/= dmaIdle || clearStartWrite || blitStartWrite ||
        !tileDmaAllowed)) {
    error := true.B
  }
  when(blit.io.completion.fire && !blit.io.completion.bits.success) {
    error := true.B
  }
  when(fill.io.completion.fire && !fill.io.completion.bits.success) {
    error := true.B
  }
  when(strided.io.completion.fire && !strided.io.completion.bits.success) {
    error := true.B
  }

  // Unified render command: fetch the 16-word descriptor over the translated
  // command port, then launch from a packed register snapshot.
  if (persistentAttachments) {
    val host = tileHost.get
    val admissible = coreIO.done && dmaOwner === dmaIdle &&
      !clearStartWrite && !blitStartWrite && !stridedStartWrite
    host.io.submit.valid := io.renderCommand.valid && admissible
    host.io.submit.bits.commandId := io.renderCommand.bits.descriptorId
    host.io.submit.bits.base := io.renderCommand.bits.descriptorAddress
    host.io.submit.bits.limit := io.renderCommand.bits.limit.get
    host.io.submit.bits.envelopeValid := io.renderCommand.bits.envelopeValid.get &&
      io.renderCommand.bits.bytes === TileJobMemoryLayout.bytes.U
    io.renderCommand.ready := host.io.submit.ready && admissible
    when(io.renderCommand.fire) { busy := true.B; done := false.B; error := false.B }
    val resultWasPending = RegNext(host.io.completion.valid, false.B)
    when(host.io.completion.valid && !resultWasPending) {
      busy := false.B; done := true.B
      when(host.io.completion.bits.pass.status =/= TilePassStatus.success.U) { error := true.B }
    }
  } else {
    renderLaunch := false.B
    io.renderCommand.ready := !renderFetching && !renderLaunch && !busy &&
      !renderDonePending && (if (config.tileAttachments)
        dmaOwner === dmaIdle && !clearStartWrite && !blitStartWrite &&
          !stridedStartWrite else true.B)
    when(io.renderCommand.fire) {
      renderFetching := true.B
      memoryFaulted := false.B
      invalidSampleMode := false.B
      done := false.B
      error := false.B
      descOutstanding := false.B
      renderWord := 0.U
      renderId := io.renderCommand.bits.descriptorId
      renderAddr := io.renderCommand.bits.descriptorAddress
      renderBytes := io.renderCommand.bits.bytes
    }
    when(renderFetching && io.cbMem.req.fire) { descOutstanding := true.B }
    when(renderFetching && io.cbMem.resp.fire) {
      descOutstanding := false.B
      renderWords(renderWord) := io.cbMem.resp.bits.data
      when(memoryFaulted || memoryFaultNow) {
        renderFetching := false.B
        renderDonePending := true.B
        done := true.B
      }.elsewhen(renderWord === 15.U) {
        renderFetching := false.B
        renderLaunch := true.B
      }.otherwise {
        renderWord := renderWord + 1.U
      }
    }
    when(renderLaunch && !validRenderMode) {
      invalidSampleMode := true.B
      renderDonePending := true.B
      done := true.B
      error := true.B
    }
    when(renderLaunch && validRenderMode && !busy) {
      busy := true.B
      done := false.B
      error := false.B
      sawBusy := false.B
      ownerUnified := true.B
      // Decode the gpu_job_record words into the unified configuration bundle.
      unifiedCfg.cmdCount := renderWords(0)(31, 16)
      unifiedCfg.cmdBase := renderWords(1)
      unifiedCfg.colorBase := renderWords(2)
      unifiedCfg.depthBase := renderWords(3)
      unifiedCfg.stride := renderWords(4)
      // Snapshot the viewport per job. Offscreen targets must not inherit the
      // display size or change the scanout registers. Zero preserves the older
      // descriptor ABI, including the last mode while scanout is disabled.
      unifiedCfg.screenW := Mux(renderWords(14) === 0.U, viewportW,
        ScreenLimit(renderWords(14), config.screenWidth))
      unifiedCfg.screenH := Mux(renderWords(15) === 0.U, viewportH,
        ScreenLimit(renderWords(15), config.screenHeight))
      unifiedCfg.depthTestEnable := renderWords(5)(0)
      unifiedCfg.depthFunc := renderWords(5)(6, 4)
      unifiedCfg.depthWriteEnable := renderWords(5)(7)
      unifiedCfg.cullMode := renderWords(5)(9, 8)
      unifiedCfg.stencilTestEnable := renderWords(5)(17)
      unifiedCfg.texBase := renderWords(6)
      unifiedCfg.texWidth := renderWords(7)(13, 0)
      unifiedCfg.texHeight := renderWords(7)(29, 16)
      unifiedCfg.texWrapClamp := renderWords(8)(0)
      unifiedCfg.texMaxLevel := renderWords(8)(5, 2)
      unifiedCfg.texEnable := renderWords(8)(8)
      unifiedCfg.sampleMode := renderWords(9)(1, 0)
      unifiedCfg.stencilFunc := renderWords(10)(2, 0)
      unifiedCfg.stencilFailOp := renderWords(10)(5, 3)
      unifiedCfg.stencilZFailOp := renderWords(10)(8, 6)
      unifiedCfg.stencilZPassOp := renderWords(10)(11, 9)
      unifiedCfg.stencilRef := renderWords(11)(7, 0)
      unifiedCfg.stencilReadMask := renderWords(11)(15, 8)
      unifiedCfg.stencilWriteMask := renderWords(11)(23, 16)
      unifiedCfg.blendCfgEnable := renderWords(12)(0)
      unifiedCfg.blendSrcFactor := renderWords(12)(7, 4)
      unifiedCfg.blendDstFactor := renderWords(12)(11, 8)
      unifiedCfg.blendEquation := renderWords(12)(14, 12)
      unifiedCfg.colorFormat := renderWords(13)(2, 0)
      unifiedCfg.texFormat := renderWords(13)(6, 4)
    }

    // Completion: the engine must be observed non-idle after launch before its
    // return to idle counts as done.  `core.done` is high before/at the launch
    // cycle (the command stage has not yet started), so the sawBusy guard stops
    // that pre-launch idle from being mistaken for completion.
    when(busy && !coreIO.done) { sawBusy := true.B }
    when(busy && sawBusy && coreIO.done) {
      busy := false.B
      done := true.B
      when(ownerUnified) {
        renderDonePending := true.B
      }.otherwise {
        irqPending := true.B
      }
    }
    when(memoryFaultNow) {
      memoryFaulted := true.B
      error := true.B
    }
    // Preserve failure at completion even if STATUS.ERROR was acknowledged
    // while the failed draw was still draining.
    when(busy && sawBusy && coreIO.done && memoryFaulted) { error := true.B }
  }
  // Integrated compute/DMA completions share the same sticky, AXI-visible
  // pending bit and W1C acknowledgement as graphics completions.
  when(io.externalCompletion) { irqPending := true.B }

  // Scanout-gated vblank timer: reload PERIOD while ACTIVE && CONTROL.bit1.
  private val vblankPulse = vblankArmed && scanoutCounter === 1.U &&
    !(wFire && rAddr === RenderHostRegs.SCANOUT_PERIOD.U)
  when(wFire && rAddr === RenderHostRegs.SCANOUT_PERIOD.U) {
    scanoutCounter := 0.U
  }.elsewhen(!vblankArmed) {
    scanoutCounter := 0.U
  }.elsewhen(scanoutCounter === 0.U) {
    scanoutCounter := scanoutPeriodReg
  }.elsewhen(scanoutCounter === 1.U) {
    scanoutCounter := scanoutPeriodReg
  }.otherwise {
    scanoutCounter := scanoutCounter - 1.U
  }
  when(wFire && rAddr === RenderHostRegs.IRQ.U && io.reg.req.bits.data(2)) {
    vblankPending := false.B
  }.elsewhen(vblankPulse) {
    vblankPending := true.B
  }

  io.irq := irqEnable && (irqPending || vblankPending)

  // The host completion arbiter publishes render results through the shared
  // unified MMIO result slot.
  if (persistentAttachments) {
    val host = tileHost.get
    io.renderCompletion.valid := host.io.completion.valid
    host.io.completion.ready := io.renderCompletion.ready
    io.renderCompletion.bits.descriptorId := host.io.completion.bits.commandId
    io.renderCompletion.bits.status := host.io.completion.bits.pass.status
    io.renderCompletion.bits.success := host.io.completion.bits.pass.status === TilePassStatus.success.U
    io.renderCompletion.bits.bytesProcessed := host.io.completion.bits.commandBytesFetched
  } else {
    io.renderCompletion.valid := renderDonePending
    io.renderCompletion.bits.descriptorId := renderId
    io.renderCompletion.bits.status :=
      Mux(memoryFaulted, RenderStatus.memoryFault,
        Mux(invalidSampleMode, RenderStatus.invalidSampleMode, RenderStatus.success))
    io.renderCompletion.bits.success := !memoryFaulted && !invalidSampleMode
    io.renderCompletion.bits.bytesProcessed := renderBytes
    when(io.renderCompletion.fire) { renderDonePending := false.B }

  }

  // The unified render descriptor is the single configuration source.
  coreIO.cmdBase := unifiedCfg.cmdBase
  coreIO.cmdCount := unifiedCfg.cmdCount
  coreIO.colorFormat := unifiedCfg.colorFormat
  coreIO.colorBase := unifiedCfg.colorBase
  coreIO.depthBase := unifiedCfg.depthBase
  coreIO.stride := unifiedCfg.stride
  coreIO.depthTestEnable := unifiedCfg.depthTestEnable
  coreIO.depthFunc := unifiedCfg.depthFunc
  coreIO.depthWriteEnable := unifiedCfg.depthWriteEnable
  coreIO.blendCfgEnable := unifiedCfg.blendCfgEnable
  coreIO.blendSrcFactor := unifiedCfg.blendSrcFactor
  coreIO.blendDstFactor := unifiedCfg.blendDstFactor
  coreIO.blendEquation := unifiedCfg.blendEquation
  coreIO.stencilTestEnable := unifiedCfg.stencilTestEnable
  coreIO.stencilFunc := unifiedCfg.stencilFunc
  coreIO.stencilRef := unifiedCfg.stencilRef
  coreIO.stencilReadMask := unifiedCfg.stencilReadMask
  coreIO.stencilWriteMask := unifiedCfg.stencilWriteMask
  coreIO.stencilFailOp := unifiedCfg.stencilFailOp
  coreIO.stencilZFailOp := unifiedCfg.stencilZFailOp
  coreIO.stencilZPassOp := unifiedCfg.stencilZPassOp
  coreIO.cullMode := unifiedCfg.cullMode
  coreIO.texEnable := unifiedCfg.texEnable
  coreIO.texFormat := unifiedCfg.texFormat
  coreIO.texBase := unifiedCfg.texBase
  coreIO.texWidth := unifiedCfg.texWidth
  coreIO.texHeight := unifiedCfg.texHeight
  coreIO.texWrapClamp := unifiedCfg.texWrapClamp
  coreIO.texMaxLevel := unifiedCfg.texMaxLevel
  coreIO.sampleMode := unifiedCfg.sampleMode
  coreIO.screenW := unifiedCfg.screenW
  coreIO.screenH := unifiedCfg.screenH
  coreIO.start := (if (persistentAttachments || externalBinnedRender) false.B else renderLaunch && validRenderMode && !busy)

  // Separate word ports.  The engine's command stage (draw records) and the
  // job queue's admin agent (descriptor fetches, IH record writes) are distinct
  // clients with different coherence and translation needs, so they expose
  // The command word port carries draw records and the unified render
  // descriptor; the admin port was removed with the job ring.
  private val cbPort = coreIO.cbMem

  // The descriptor fetch owns the command word port while it runs (the core is
  // idle until launch), then hands it back to the command stage.
  private val descReqValid = renderFetching && !descOutstanding
  io.cbMem.req.valid := Mux(renderFetching, descReqValid, cbPort.req.valid)
  io.cbMem.req.bits.addr :=
    Mux(renderFetching, renderAddr + (renderWord << 2), cbPort.req.bits.addr)
  io.cbMem.req.bits.write :=
    Mux(renderFetching, false.B, cbPort.req.bits.write)
  io.cbMem.req.bits.data := Mux(renderFetching, 0.U, cbPort.req.bits.data)
  cbPort.req.ready := !renderFetching && io.cbMem.req.ready
  cbPort.resp.valid := !renderFetching && io.cbMem.resp.valid
  cbPort.resp.bits := io.cbMem.resp.bits
  io.cbMem.resp.ready := Mux(renderFetching, true.B, cbPort.resp.ready)

  coreIO.fbMem.req <> io.fbMem.req
  io.fbMem.resp <> coreIO.fbMem.resp
  io.kernelMemReq <> coreIO.kernelMemReq
  coreIO.kernelMemResp <> io.kernelMemResp

  coreIO.kernelL1Invalidate <> io.kernelL1Invalidate
  io.kernelL1InvalidateDone <> coreIO.kernelL1InvalidateDone
  io.kernelGlobalAtomicRequest <> coreIO.kernelGlobalAtomicRequest
  coreIO.kernelGlobalAtomicResponse <> io.kernelGlobalAtomicResponse
  coreIO.texMem <> io.texMem

  // Read port: single-cycle combinational response; writes are fire-and-forget.
  io.reg.req.ready := true.B
  io.reg.resp.valid := io.reg.req.valid && !io.reg.req.bits.isWrite
  io.reg.resp.bits.data := readReg
  private val aligned = (rAddr & 0x3.U) === 0.U
  private val mapped = rAddr < RenderHostRegs.END.U
  private val externalDmaBlocked = io.externalRenderBusy.getOrElse(false.B) &&
    (clearStartWrite || blitStartWrite || stridedStartWrite)
  io.reg.resp.bits.ok := aligned && mapped && !externalDmaBlocked
  if (externalBinnedRender) {
    io.renderCommand.ready := false.B
    io.renderCompletion.valid := false.B
  }
}
