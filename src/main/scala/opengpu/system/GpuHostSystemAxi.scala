package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{GpuConfig,MobileGpuConfig}
import opengpu.core.memory.{
  ComputeMemoryRequest, ComputeMemoryResponse, TranslatedLineClient,
  TranslatedWordClient
}
import opengpu.graphics.{GraphicsConfig, TextureUnit,MemoryTileBinRenderPipeline}

/** Optional simulation counters; the default product port list is unchanged. */
class GpuHostPerformanceCounters extends Bundle {
  val system = new GpuPerformanceCounters
  val omStallCycles = UInt(64.W)
  val omConflictCycles = UInt(64.W)
  val rasterStallCycles = UInt(64.W)
  val fragmentPhaseWorkerCycles = Vec(6, UInt(64.W))
  val stagingOutstandingCycles = UInt(64.W)
  val stagingBlockedCycles = UInt(64.W)
  val fragmentOutputBlockedCycles = UInt(64.W)
  /** Packed per-cycle events for run-length-encoded simulation traces. */
  val fragmentTrace = UInt(64.W)
  val stagingReadBytes = UInt(64.W)
  val stagingWriteBytes = UInt(64.W)
  val lowerReadBytes = UInt(64.W)
  val lowerWriteBytes = UInt(64.W)
  val textureWordReads = UInt(64.W)
  val textureLineReads = UInt(64.W)
  val translationStallCycles = Vec(4, UInt(64.W))
  val translationMisses = Vec(4, UInt(64.W))
  val walkCycles = Vec(4, UInt(64.W))
  val binAcceptedSamples=Vec(4,UInt(64.W))
  val binCommittedSamples=Vec(4,UInt(64.W))
  val binFourSampleCycles=Vec(4,UInt(64.W))
}

/** AXI-controlled graphics host attached to the GPU-internal shared L2.
  *
  * CPU and GPU L2 caches are separate. The AXI memory master carries GPU L2
  * lower-memory traffic to the SoC fabric and shared DRAM; the control slave
  * provides no CPU data-access path into the GPU L2. Coherence below is limited
  * to GPU clients and does not snoop CPU caches.
  *
  * The graphics host's eight-ID cache-line port and the compute/DMA clients
  * share one lower-memory port. Command-buffer, framebuffer and texture word
  * clients are adapted internally. The graphics shader is an additional
  * coherent L2 client, including private-cache invalidation and global atomic
  * channels, so the top exposes only one lower-memory port.
  */
class GpuHostSystemAxi(
  graphicsConfig: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  numComputeUnits: Int = 1,
  commandIdWidth: Int = 8,
  transactionsPerCu: Int = 4,
  fragCore: Boolean = false,
  vertCore: Boolean = false,
  deviceId: Int = 0x4755,
  version: Int = 0x0001,
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false,
  instructionCacheSets: Int = 64,
  instructionCacheWays: Int = 2,
  instructionCacheMissEntries: Int = 4,
  vectorCacheSets: Int = 64,
  vectorCacheWays: Int = 2,
  memoryAxiDataBytes: Int = 8,
  exposePerformance: Boolean = false,
  graphicsGpuConfig: Option[GpuConfig] = None,
  sharedGraphics: Boolean = false,
  parallelGraphics: Boolean = false,
  coalesceFramebufferWrites: Boolean = false,
  bufferCommandReads: Boolean = false,
  bufferTextureReads: Boolean = false,
  persistentAttachments: Boolean = false,
  tileStoreCredits: Int = opengpu.config.MobileGpuConfig().attachmentStoreCredits,
  binnedRendering: Option[MobileGpuConfig] = None
) extends RawModule {
  private val shaderConfig = graphicsGpuConfig.getOrElse(gpuConfig)
  require(numComputeUnits > 0)
  require(!persistentAttachments || (!bufferTextureReads && !coalesceFramebufferWrites),
    "persistent texture buffering and framebuffer combining need response-aligned fault ownership")
  require(!persistentAttachments || !fragCore || sharedGraphics,
    "programmable persistent jobs use the shared system CU pool")
  require(shaderConfig.xLen == gpuConfig.xLen,
    "graphics and compute CUs must use the same address width")
  require(!vertCore || fragCore,
    "vertex-core graphics requires fragment-core graphics")
  require(!sharedGraphics || fragCore,
    "shared graphics requires programmable fragment graphics")
  require(!parallelGraphics || sharedGraphics, "parallel graphics requires shared graphics")
  require(!sharedGraphics || (shaderConfig.lanes == gpuConfig.lanes &&
    shaderConfig.warps == gpuConfig.warps),
    "shared graphics and compute must use the same lane/warp shader ABI")
  binnedRendering.foreach { mobile=>
    mobile.validateImplementedGraphics(graphicsConfig)
    require(shaderConfig==gpuConfig,"binned host resource discovery must use the system execution profile")
    require(!persistentAttachments && !vertCore && graphicsConfig.tileAttachments && graphicsConfig.tileSize==16 && !graphicsConfig.tileBinning)
    require(mobile.execution==gpuConfig && mobile.computeUnits==numComputeUnits && mobile.axiBytes==memoryAxiDataBytes,
      "binned host must use the system execution, CU and AXI configuration")
    require(!fragCore || sharedGraphics,"binned programmable jobs use the shared system CUs")
    require(!bufferCommandReads && !bufferTextureReads && !coalesceFramebufferWrites,
      "legacy word-port buffering options do not apply to the binned line engine")
  }
  private val sharedShader = if (sharedGraphics)
    Some(SharedCuShaderConfig(numComputeUnits, commandIdWidth, parallelGraphics)) else None

  // Kernel-word IDs reserve a CU-scaled staging range followed by four
  // host-DMA IDs. Command, framebuffer and
  // texture word clients each own a translated range plus a PTE-walk range.
  // Staging and DMA walks sit with the other TLB clients.
  private val translatedWordClients = Seq("command", "framebuffer", "texture")
  private val wordPortTransactions = math.max(4, graphicsConfig.omInflight)
  // Each physical sampler has at most four accepted tap reads. Preserve
  // the existing line cache and all global transaction-ID reservations.
  private val textureWordTransactions = if (bufferTextureReads)
    math.min(wordPortTransactions, math.max(16, TextureUnit.maxOutstandingReads * sharedShader.map(_.fragmentWorkers).getOrElse(1)))
  else wordPortTransactions
  private val kernelWordTransactions = SharedCuShaderConfig.kernelWordTransactions(sharedShader)
  private val stagingTransactions = SharedCuShaderConfig.stagingTransactions(sharedShader)
  private val dmaTransactions = 4
  private val usedExact =
    kernelWordTransactions +
      wordPortTransactions * translatedWordClients.size * 2 +
      stagingTransactions + dmaTransactions
  private val binnedMemoryIds=binnedRendering.map(m=>9+m.binWriteCredits+2*m.tileEngines+
    (if (m.binDirectoryTiles>0) 1 else 0)+m.tileEngines*(2*(m.attachmentStoreCredits+1)+1)).getOrElse(0)
  private val graphicsHostTransactions = binnedRendering.map(m=>TileBinHostMemoryFabric.transactions(
    binnedMemoryIds,m.tileEngines,kernelWordTransactions,fragCore)).getOrElse(1 << log2Ceil(usedExact))
  private val clientBases: Seq[Int] = translatedWordClients.indices
    .scanLeft(kernelWordTransactions)((base, _) => base + wordPortTransactions)
  private val cbBase = clientBases(0)
  private val fbBase = clientBases(1)
  private val texBase = clientBases(2)
  private val usedGraphicsTransactions = clientBases.last
  private val stagingTlbBase = usedGraphicsTransactions
  private val dmaTlbBase = stagingTlbBase + stagingTransactions
  private val tlbBases: Seq[Int] = translatedWordClients.indices
    .scanLeft(dmaTlbBase + dmaTransactions)(
      (base, _) => base + wordPortTransactions)
  private val graphicsTlbBase = tlbBases(0)
  private val cbTlbBase = tlbBases(1)
  private val fbTlbBase = tlbBases(2)
  private val usedGraphicsTransactionsWithTlb = tlbBases.last
  require(binnedRendering.nonEmpty || usedGraphicsTransactionsWithTlb <= graphicsHostTransactions,
    s"graphics host ID budget $graphicsHostTransactions < used " +
      s"$usedGraphicsTransactionsWithTlb")
  private val systemTransactions = GpuSystem.totalMemoryTransactions(
    numComputeUnits, transactionsPerCu, graphicsHostTransactions)
  private val memoryAxiIdWidth = math.max(1, log2Ceil(systemTransactions))
  private val memoryAxiDataWidth = memoryAxiDataBytes * 8

  override def desiredName: String = "GpuHostSystemAxi"

  val io = IO(new Bundle {
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
    val m_irq = Output(Bool())
    val performance = if (exposePerformance) Some(Output(new GpuHostPerformanceCounters)) else None

    val m_axi_awid = Output(UInt(memoryAxiIdWidth.W))
    val m_axi_awaddr = Output(UInt(gpuConfig.xLen.W))
    val m_axi_awlen = Output(UInt(8.W))
    val m_axi_awsize = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awlock = Output(Bool())
    val m_axi_awcache = Output(UInt(4.W))
    val m_axi_awprot = Output(UInt(3.W))
    val m_axi_awqos = Output(UInt(4.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_wdata = Output(UInt(memoryAxiDataWidth.W))
    val m_axi_wstrb = Output(UInt(memoryAxiDataBytes.W))
    val m_axi_wlast = Output(Bool())
    val m_axi_wvalid = Output(Bool())
    val m_axi_wready = Input(Bool())
    val m_axi_bid = Input(UInt(memoryAxiIdWidth.W))
    val m_axi_bresp = Input(UInt(2.W))
    val m_axi_bvalid = Input(Bool())
    val m_axi_bready = Output(Bool())
    val m_axi_arid = Output(UInt(memoryAxiIdWidth.W))
    val m_axi_araddr = Output(UInt(gpuConfig.xLen.W))
    val m_axi_arlen = Output(UInt(8.W))
    val m_axi_arsize = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arlock = Output(Bool())
    val m_axi_arcache = Output(UInt(4.W))
    val m_axi_arprot = Output(UInt(3.W))
    val m_axi_arqos = Output(UInt(4.W))
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())
    val m_axi_rid = Input(UInt(memoryAxiIdWidth.W))
    val m_axi_rdata = Input(UInt(memoryAxiDataWidth.W))
    val m_axi_rresp = Input(UInt(2.W))
    val m_axi_rlast = Input(Bool())
    val m_axi_rvalid = Input(Bool())
    val m_axi_rready = Output(Bool())
  })

  withClockAndReset(io.s_axi_aclk, !io.s_axi_aresetn) {
    val host = Module(new GpuHostAxi(
      graphicsConfig, shaderConfig, fragCore, vertCore,
      deviceId = deviceId, version = version,
      unifiedCommandMmio = true, commandIdWidth = commandIdWidth,
      textureFaultReporting = true,
      computeScalarFpu = enableFpuBackend && gpuConfig.enableFpu,
      graphicsScalarFpu = enableFpuBackend && shaderConfig.enableFpu,
      sharedShader = sharedShader, protectVmState = true,
      persistentAttachments = persistentAttachments, tileStoreCredits = tileStoreCredits,
      useTileSramBlackBoxes = useBlackBoxes, externalBinnedRender = binnedRendering.nonEmpty))
    val system = Module(new GpuSystem(
      gpuConfig,
      numComputeUnits = numComputeUnits,
      commandIdWidth = commandIdWidth,
      transactionsPerCu = transactionsPerCu,
      useBlackBoxes = useBlackBoxes,
      enableFpuBackend = enableFpuBackend,
      enableUnifiedCommands = true,
      graphicsHostTransactions = graphicsHostTransactions,
      instructionCacheSets = instructionCacheSets,
      instructionCacheWays = instructionCacheWays,
      instructionCacheMissEntries = instructionCacheMissEntries,
      vectorCacheSets = vectorCacheSets,
      vectorCacheWays = vectorCacheWays,
      enableGraphicsKernels = sharedGraphics))
    val binned=binnedRendering.map(m=>Module(new MemoryTileBinRenderPipeline(
      graphicsConfig,gpuConfig,commandIdWidth,m.tileEngines,m.binPageBytes,m.binWriteCredits,
      m.binTileSlots,m.binRecordCacheEntries,m.attachmentStoreCredits,fragCore,sharedShader,
      useBlackBoxes,m.binDirectoryTiles,bankedAttachments=m.bankedAttachments)))
    if (binnedRendering.isEmpty) sharedShader.foreach(_ => system.io.graphicsKernels.get <> host.io.shared.get)

    host.io.s_axi_aclk := io.s_axi_aclk
    host.io.s_axi_aresetn := io.s_axi_aresetn
    host.io.s_axi_awaddr := io.s_axi_awaddr
    host.io.s_axi_awlen := io.s_axi_awlen
    host.io.s_axi_awsize := io.s_axi_awsize
    host.io.s_axi_awburst := io.s_axi_awburst
    host.io.s_axi_awvalid := io.s_axi_awvalid
    io.s_axi_awready := host.io.s_axi_awready
    host.io.s_axi_wdata := io.s_axi_wdata
    host.io.s_axi_wstrb := io.s_axi_wstrb
    host.io.s_axi_wlast := io.s_axi_wlast
    host.io.s_axi_wvalid := io.s_axi_wvalid
    io.s_axi_wready := host.io.s_axi_wready
    io.s_axi_bresp := host.io.s_axi_bresp
    io.s_axi_bvalid := host.io.s_axi_bvalid
    host.io.s_axi_bready := io.s_axi_bready
    host.io.s_axi_araddr := io.s_axi_araddr
    host.io.s_axi_arlen := io.s_axi_arlen
    host.io.s_axi_arsize := io.s_axi_arsize
    host.io.s_axi_arburst := io.s_axi_arburst
    host.io.s_axi_arvalid := io.s_axi_arvalid
    io.s_axi_arready := host.io.s_axi_arready
    io.s_axi_rdata := host.io.s_axi_rdata
    io.s_axi_rresp := host.io.s_axi_rresp
    io.s_axi_rlast := host.io.s_axi_rlast
    io.s_axi_rvalid := host.io.s_axi_rvalid
    host.io.s_axi_rready := io.s_axi_rready
    io.m_irq := host.io.m_irq

    system.io.gpuCommand <> host.io.gpuCommand.get
    host.io.gpuCompletion.get <> system.io.gpuCompletion
    system.io.graphicsDrained := host.io.graphicsDrained
    host.io.vmContextIdle.get := system.io.vmContextIdle
    host.io.graphicsDependencyIdle.foreach(_ := system.io.graphicsDependencyIdle)
    system.io.commandResetActive := host.io.commandResetActive.get
    host.io.commandResetDone.get := system.io.commandResetDone

    system.io.graphicsShaderRequest <> host.io.kernelMemReq
    host.io.kernelMemResp <> system.io.graphicsShaderResponse
    system.io.graphicsShaderL1Invalidate <> host.io.kernelL1Invalidate
    host.io.kernelL1InvalidateDone <>
      system.io.graphicsShaderL1InvalidateDone
    system.io.graphicsShaderAtomicRequest <>
      host.io.kernelGlobalAtomicRequest
    host.io.kernelGlobalAtomicResponse <>
      system.io.graphicsShaderAtomicResponse
    val textureWordAccepted=WireDefault(false.B)
    val textureLineAccepted=WireDefault(false.B)
    val translationStalls=WireDefault(VecInit(Seq.fill(4)(0.U(3.W))))
    val translationMisses=WireDefault(VecInit(Seq.fill(4)(0.U(3.W))))
    val translationWalks=WireDefault(VecInit(Seq.fill(4)(0.U(3.W))))
    if (binnedRendering.isEmpty) {
    // Staging (kernarg/VB) and fill/blit/strided DMA share the kernel-word
    // port under VECTOR_SATP. Staging and DMA have disjoint ID ranges. Command,
    // framebuffer and texture word clients each keep their own TLB.
    val stagingClient = Module(new TranslatedLineClient(
      gpuConfig, graphicsHostTransactions, preserveCachePolicy = true))
    val dmaClient = Module(new TranslatedLineClient(
      gpuConfig, graphicsHostTransactions, preserveCachePolicy = true))
    val cbClient = Module(new TranslatedWordClient(
      gpuConfig, wordPortTransactions, graphicsHostTransactions,
      uncached = true, preserveCachePolicy = true,
      bufferCommandReads = bufferCommandReads))
    // Reset drains admitted renders, whose command reads must keep progressing.
    // Invalidate once at reset entry; idle invalidation clears the final line
    // before another job can be admitted.
    if (bufferCommandReads) {
      val resetWasActive = RegNext(host.io.commandResetActive.get, false.B)
      cbClient.io.invalidateCommandReads.get := host.io.commandReadsIdle ||
        (host.io.commandResetActive.get && !resetWasActive)
    }
    val fbClient = Module(new TranslatedWordClient(
      gpuConfig, wordPortTransactions, graphicsHostTransactions,
      uncached = true, preserveCachePolicy = true,
      coalesceWrites = coalesceFramebufferWrites))
    val texClient = Module(new TranslatedWordClient(
      gpuConfig, textureWordTransactions, graphicsHostTransactions,
      bufferTextureReads = bufferTextureReads))
    if (bufferTextureReads) {
      val resetWasActive = RegNext(host.io.commandResetActive.get, false.B)
      // Revoke reuse at the job boundary or reset entry. Unlike command
      // buffering, the texture buffer still admits drain reads while idle
      // invalidation is held high after a render fault.
      texClient.io.invalidateTextureReads.get := host.io.commandReadsIdle ||
        (host.io.commandResetActive.get && !resetWasActive)
    }
    val stagingSelect =
      host.io.kernelWordMemReq.bits.transactionId < stagingTransactions.U
    stagingClient.io.in.valid :=
      host.io.kernelWordMemReq.valid && stagingSelect
    stagingClient.io.in.bits := host.io.kernelWordMemReq.bits
    dmaClient.io.in.valid :=
      host.io.kernelWordMemReq.valid && !stagingSelect
    dmaClient.io.in.bits := host.io.kernelWordMemReq.bits
    host.io.kernelWordMemReq.ready := Mux(stagingSelect,
      stagingClient.io.in.ready, dmaClient.io.in.ready)
    cbClient.io.in <> host.io.cbMem.req
    host.io.cbMem.resp <> cbClient.io.out
    fbClient.io.in <> host.io.fbMem.req
    host.io.fbMem.resp <> fbClient.io.out
    texClient.io.in <> host.io.texMem.req
    host.io.texMem.resp <> texClient.io.out
    stagingClient.io.satp := host.io.vectorSatp
    stagingClient.io.flush := host.io.tlbFlush
    dmaClient.io.satp := host.io.vectorSatp
    dmaClient.io.flush := host.io.tlbFlush
    for (client <- Seq(cbClient, fbClient, texClient)) {
      client.io.satp := host.io.vectorSatp
      client.io.flush := host.io.tlbFlush
    }

    val graphicsRequestArbiter = Module(new RRArbiter(
      new ComputeMemoryRequest(gpuConfig, 64, graphicsHostTransactions), 10))
    def attachGraphicsRequest(
      index: Int,
      request: DecoupledIO[ComputeMemoryRequest],
      base: Int
    ): Unit = {
      graphicsRequestArbiter.io.in(index).valid := request.valid
      graphicsRequestArbiter.io.in(index).bits := request.bits
      graphicsRequestArbiter.io.in(index).bits.transactionId :=
        base.U + request.bits.transactionId
      request.ready := graphicsRequestArbiter.io.in(index).ready
    }
    attachGraphicsRequest(0, stagingClient.io.memReq, 0)
    attachGraphicsRequest(1, dmaClient.io.memReq, 0)
    attachGraphicsRequest(2, cbClient.io.memReq, cbBase)
    attachGraphicsRequest(3, fbClient.io.memReq, fbBase)
    attachGraphicsRequest(4, texClient.io.memReq, texBase)
    attachGraphicsRequest(5, stagingClient.io.pageWalk, stagingTlbBase)
    attachGraphicsRequest(6, dmaClient.io.pageWalk, dmaTlbBase)
    attachGraphicsRequest(7, texClient.io.pageWalk, graphicsTlbBase)
    attachGraphicsRequest(8, cbClient.io.pageWalk, cbTlbBase)
    attachGraphicsRequest(9, fbClient.io.pageWalk, fbTlbBase)
    system.io.graphicsHostRequest <> graphicsRequestArbiter.io.out

    val graphicsResponse = system.io.graphicsHostResponse
    val responseId = graphicsResponse.bits.transactionId
    val responseForStaging = responseId < stagingTransactions.U
    val responseForDma = responseId >= stagingTransactions.U &&
      responseId < cbBase.U
    val responseForCb = responseId >= cbBase.U && responseId < fbBase.U
    val responseForFb = responseId >= fbBase.U && responseId < texBase.U
    val responseForTex = responseId >= texBase.U &&
      responseId < stagingTlbBase.U
    val responseForStagingTlb = responseId >= stagingTlbBase.U &&
      responseId < dmaTlbBase.U
    val responseForDmaTlb = responseId >= dmaTlbBase.U &&
      responseId < graphicsTlbBase.U
    val responseForTlb = responseId >= graphicsTlbBase.U &&
      responseId < cbTlbBase.U
    val responseForCbTlb = responseId >= cbTlbBase.U &&
      responseId < fbTlbBase.U
    val responseForFbTlb = responseId >= fbTlbBase.U &&
      responseId < usedGraphicsTransactionsWithTlb.U
    def attachGraphicsResponse(
      response: DecoupledIO[ComputeMemoryResponse],
      select: Bool,
      base: Int
    ): Unit = {
      response.valid := graphicsResponse.valid && select
      response.bits.readData := graphicsResponse.bits.readData
      response.bits.fault := graphicsResponse.bits.fault
      response.bits.transactionId := responseId - base.U
    }
    attachGraphicsResponse(
      stagingClient.io.memResp, responseForStaging, 0)
    attachGraphicsResponse(
      dmaClient.io.memResp, responseForDma, 0)
    attachGraphicsResponse(cbClient.io.memResp, responseForCb, cbBase)
    attachGraphicsResponse(fbClient.io.memResp, responseForFb, fbBase)
    attachGraphicsResponse(texClient.io.memResp, responseForTex, texBase)
    // Merge translated staging and DMA completions (including local faults)
    // back onto the shared kernel-word response port.
    val kernelWordRespArb = Module(new RRArbiter(
      new ComputeMemoryResponse(64, graphicsHostTransactions), 2))
    kernelWordRespArb.io.in(0) <> stagingClient.io.out
    kernelWordRespArb.io.in(1) <> dmaClient.io.out
    host.io.kernelWordMemResp <> kernelWordRespArb.io.out
    host.io.textureFault.get :=
      stagingClient.io.fault || dmaClient.io.fault || cbClient.io.fault ||
        fbClient.io.fault || texClient.io.fault
    if (persistentAttachments) {
      host.io.commandFault.get := cbClient.io.commandResponseFault.getOrElse(cbClient.io.fault)
      host.io.attachmentFault.get := fbClient.io.responseFault
      host.io.tileTextureFault.get := texClient.io.fault
    }
    stagingClient.io.pageWalkResp.valid :=
      graphicsResponse.valid && responseForStagingTlb
    stagingClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    stagingClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    stagingClient.io.pageWalkResp.bits.transactionId :=
      responseId - stagingTlbBase.U
    dmaClient.io.pageWalkResp.valid :=
      graphicsResponse.valid && responseForDmaTlb
    dmaClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    dmaClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    dmaClient.io.pageWalkResp.bits.transactionId :=
      responseId - dmaTlbBase.U
    texClient.io.pageWalkResp.valid := graphicsResponse.valid && responseForTlb
    texClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    texClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    texClient.io.pageWalkResp.bits.transactionId :=
      responseId - graphicsTlbBase.U
    cbClient.io.pageWalkResp.valid :=
      graphicsResponse.valid && responseForCbTlb
    cbClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    cbClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    cbClient.io.pageWalkResp.bits.transactionId := responseId - cbTlbBase.U
    fbClient.io.pageWalkResp.valid :=
      graphicsResponse.valid && responseForFbTlb
    fbClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    fbClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    fbClient.io.pageWalkResp.bits.transactionId := responseId - fbTlbBase.U
    graphicsResponse.ready := MuxCase(false.B, Seq(
      responseForStaging -> stagingClient.io.memResp.ready,
      responseForDma -> dmaClient.io.memResp.ready,
      responseForCb -> cbClient.io.memResp.ready,
      responseForFb -> fbClient.io.memResp.ready,
      responseForTex -> texClient.io.memResp.ready,
      responseForStagingTlb -> stagingClient.io.pageWalkResp.ready,
      responseForDmaTlb -> dmaClient.io.pageWalkResp.ready,
      responseForTlb -> texClient.io.pageWalkResp.ready,
      responseForCbTlb -> cbClient.io.pageWalkResp.ready,
      responseForFbTlb -> fbClient.io.pageWalkResp.ready))
    when(graphicsResponse.valid) {
      assert(responseForStaging || responseForDma || responseForCb ||
        responseForFb || responseForTex || responseForStagingTlb ||
        responseForDmaTlb || responseForTlb || responseForCbTlb ||
        responseForFbTlb,
        "graphics response must target an attached line, word or TLB client")
    }

    textureWordAccepted:=texClient.io.in.fire
    textureLineAccepted:=texClient.io.memReq.fire
    translationStalls(0):=stagingClient.io.translationStall.asUInt
    translationMisses(0):=stagingClient.io.translationMiss.asUInt
    translationWalks(0):=stagingClient.io.walkActive.asUInt
    for ((client,index) <- Seq(cbClient,fbClient,texClient).zipWithIndex) {
      translationStalls(index+1):=client.io.translationStall.asUInt
      translationMisses(index+1):=client.io.translationMiss.asUInt
      translationWalks(index+1):=client.io.walkActive.asUInt
    }
    } else {
      val renderer=binned.get
      val mobile=binnedRendering.get
      val fabric=Module(new TileBinHostMemoryFabric(gpuConfig,binnedMemoryIds,mobile.tileEngines,
        kernelWordTransactions,stagingTransactions,fragCore))
      renderer.io.submit <> host.io.binSubmit.get
      host.io.binCompletion.get <> renderer.io.completion
      host.io.binCapabilityWords.get:=renderer.io.capabilityWords
      renderer.io.cancel:=host.io.binCancel.get
      host.io.binDrained.get:= !renderer.io.busy && fabric.io.drained
      sharedShader.foreach(_=>renderer.io.shared.get <> system.io.graphicsKernels.get)
      renderer.io.instructionSatp:=host.io.instructionSatp
      renderer.io.vectorSatp:=host.io.vectorSatp
      renderer.io.instructionTlbFlush:=host.io.tlbFlush
      renderer.io.vectorTlbFlush:=host.io.tlbFlush
      fabric.io.renderRequest <> renderer.io.memoryRequest
      renderer.io.memoryResponse <> fabric.io.renderResponse
      fabric.io.dmaRequest <> host.io.kernelWordMemReq
      host.io.kernelWordMemResp <> fabric.io.dmaResponse
      fabric.io.satp:=host.io.vectorSatp; fabric.io.flush:=host.io.tlbFlush
      system.io.graphicsHostRequest <> fabric.io.memoryRequest
      fabric.io.memoryResponse <> system.io.graphicsHostResponse
      for (e <- 0 until mobile.tileEngines) {
        fabric.io.stagingRequest(e) <> renderer.io.kernelWordMemReq(e)
        renderer.io.kernelWordMemResp(e) <> fabric.io.stagingResponse(e)
        renderer.io.kernelMemReq(e).ready:=false.B
        renderer.io.kernelMemResp(e).valid:=false.B
        renderer.io.kernelMemResp(e).bits:=0.U.asTypeOf(renderer.io.kernelMemResp(e).bits)
        renderer.io.kernelGlobalAtomicRequest(e).ready:=false.B
        renderer.io.kernelGlobalAtomicResponse(e).valid:=false.B
        renderer.io.kernelGlobalAtomicResponse(e).bits:=0.U.asTypeOf(renderer.io.kernelGlobalAtomicResponse(e).bits)
        renderer.io.kernelL1Invalidate(e).valid:=false.B
        renderer.io.kernelL1Invalidate(e).bits:=0.U.asTypeOf(renderer.io.kernelL1Invalidate(e).bits)
        renderer.io.kernelL1InvalidateDone(e).ready:=true.B
        assert(!renderer.io.kernelMemReq(e).valid && !renderer.io.kernelGlobalAtomicRequest(e).valid)
      }
      for (port <- Seq(host.io.cbMem,host.io.fbMem,host.io.texMem)) {
        port.req.ready:=false.B; port.resp.valid:=false.B; port.resp.bits:=0.U.asTypeOf(port.resp.bits)
        assert(!port.req.valid,"binned host must not dispatch the legacy word renderer")
      }
      host.io.textureFault.get:=false.B
      translationStalls:=fabric.io.translationStall
      translationMisses:=fabric.io.translationMiss
      translationWalks:=fabric.io.walkActive
    }

    val memoryAxi = Module(new ComputeMemoryAxiMaster(
      gpuConfig, 64, systemTransactions, memoryAxiDataBytes))
    memoryAxi.io.request <> system.io.memoryRequest
    system.io.memoryResponse <> memoryAxi.io.response
    io.m_axi_awid := memoryAxi.io.m_axi_awid
    io.m_axi_awaddr := memoryAxi.io.m_axi_awaddr
    io.m_axi_awlen := memoryAxi.io.m_axi_awlen
    io.m_axi_awsize := memoryAxi.io.m_axi_awsize
    io.m_axi_awburst := memoryAxi.io.m_axi_awburst
    io.m_axi_awlock := memoryAxi.io.m_axi_awlock
    io.m_axi_awcache := memoryAxi.io.m_axi_awcache
    io.m_axi_awprot := memoryAxi.io.m_axi_awprot
    io.m_axi_awqos := memoryAxi.io.m_axi_awqos
    io.m_axi_awvalid := memoryAxi.io.m_axi_awvalid
    memoryAxi.io.m_axi_awready := io.m_axi_awready
    io.m_axi_wdata := memoryAxi.io.m_axi_wdata
    io.m_axi_wstrb := memoryAxi.io.m_axi_wstrb
    io.m_axi_wlast := memoryAxi.io.m_axi_wlast
    io.m_axi_wvalid := memoryAxi.io.m_axi_wvalid
    memoryAxi.io.m_axi_wready := io.m_axi_wready
    memoryAxi.io.m_axi_bid := io.m_axi_bid
    memoryAxi.io.m_axi_bresp := io.m_axi_bresp
    memoryAxi.io.m_axi_bvalid := io.m_axi_bvalid
    io.m_axi_bready := memoryAxi.io.m_axi_bready
    io.m_axi_arid := memoryAxi.io.m_axi_arid
    io.m_axi_araddr := memoryAxi.io.m_axi_araddr
    io.m_axi_arlen := memoryAxi.io.m_axi_arlen
    io.m_axi_arsize := memoryAxi.io.m_axi_arsize
    io.m_axi_arburst := memoryAxi.io.m_axi_arburst
    io.m_axi_arlock := memoryAxi.io.m_axi_arlock
    io.m_axi_arcache := memoryAxi.io.m_axi_arcache
    io.m_axi_arprot := memoryAxi.io.m_axi_arprot
    io.m_axi_arqos := memoryAxi.io.m_axi_arqos
    io.m_axi_arvalid := memoryAxi.io.m_axi_arvalid
    memoryAxi.io.m_axi_arready := io.m_axi_arready
    memoryAxi.io.m_axi_rid := io.m_axi_rid
    memoryAxi.io.m_axi_rdata := io.m_axi_rdata
    memoryAxi.io.m_axi_rresp := io.m_axi_rresp
    memoryAxi.io.m_axi_rlast := io.m_axi_rlast
    memoryAxi.io.m_axi_rvalid := io.m_axi_rvalid
    io.m_axi_rready := memoryAxi.io.m_axi_rready
    system.io.command.valid := false.B
    system.io.command.bits := 0.U.asTypeOf(system.io.command.bits)
    system.io.commandCompletion.ready := true.B
    system.io.copyDescriptor.valid := false.B
    system.io.copyDescriptor.bits :=
      0.U.asTypeOf(system.io.copyDescriptor.bits)
    system.io.copyCompletion.ready := true.B
    system.io.fillDescriptor.valid := false.B
    system.io.fillDescriptor.bits :=
      0.U.asTypeOf(system.io.fillDescriptor.bits)
    system.io.fillCompletion.ready := true.B
    system.io.stridedCopyDescriptor.valid := false.B
    system.io.stridedCopyDescriptor.bits :=
      0.U.asTypeOf(system.io.stridedCopyDescriptor.bits)
    system.io.stridedCopyCompletion.ready := true.B

    io.performance.foreach { perf =>
      def count(value: UInt): UInt = {
        val counter = RegInit(0.U(64.W))
        counter := counter + value
        counter
      }
      perf.system := system.io.performance
      for(e <- 0 until 4) {
        val engine=binned.filter(_.io.committedSamples.length>e)
        val accepted=engine.map(_.io.acceptedSamples(e)).getOrElse(0.U(3.W))
        val committed=engine.map(_.io.committedSamples(e)).getOrElse(0.U(3.W))
        perf.binAcceptedSamples(e):=count(accepted)
        perf.binCommittedSamples(e):=count(committed)
        perf.binFourSampleCycles(e):=count((committed===4.U).asUInt)
      }
      perf.textureWordReads := count(textureWordAccepted.asUInt)
      perf.textureLineReads := count(textureLineAccepted.asUInt)
      perf.omStallCycles := count(host.io.performance.omStall.asUInt)
      perf.omConflictCycles := count(host.io.performance.omConflict.asUInt)
      perf.rasterStallCycles := count(host.io.performance.rasterStall.asUInt)
      val fragmentEvents = host.io.performance.fragment
      for (phase <- 0 until 6)
        perf.fragmentPhaseWorkerCycles(phase) := count(fragmentEvents.phaseWorkers(phase))
      perf.stagingOutstandingCycles := count(fragmentEvents.stagingOutstanding)
      perf.stagingBlockedCycles := count(fragmentEvents.stagingBlocked.asUInt)
      perf.fragmentOutputBlockedCycles := count(fragmentEvents.outputBlocked.asUInt)
      perf.fragmentTrace := Cat(io.m_axi_arvalid, io.m_axi_awvalid,
        io.m_axi_rvalid, io.m_axi_bvalid, host.io.performance.rasterStall,
        host.io.performance.omStall, fragmentEvents.outputBlocked,
        fragmentEvents.stagingBlocked, fragmentEvents.stagingOutstanding,
        fragmentEvents.phaseWorkers.asUInt)
      perf.stagingReadBytes := count(host.io.performance.stagingReadBytes)
      perf.stagingWriteBytes := count(host.io.performance.stagingWriteBytes)
      for (slot <- 0 until 4) {
        perf.translationStallCycles(slot):=count(translationStalls(slot))
        perf.translationMisses(slot):=count(translationMisses(slot))
        perf.walkCycles(slot):=count(translationWalks(slot))
      }
      val request = system.io.memoryRequest
      val bytes = 1.U(8.W) << request.bits.sizeLog2
      perf.lowerReadBytes := count(Mux(request.fire && !request.bits.isWrite, bytes, 0.U))
      perf.lowerWriteBytes := count(Mux(request.fire && request.bits.isWrite, bytes, 0.U))
    }
    system.io.clearPerformanceCounters := false.B
    // Remapped executable snapshots may recycle physical lines; drop I-cache
    // contents whenever software shoots down translation.
    system.io.invalidateInstructionCache := host.io.tlbFlush.valid
    system.io.instructionSatp := host.io.instructionSatp
    system.io.vectorSatp := host.io.vectorSatp
    // `TLB_FLUSH` forwards its scope to both CU MMUs and the graphics
    // word/line clients, so an ASID- or VPN-scoped shootdown leaves other
    // entries warm everywhere.
    system.io.vectorTlbFlush := host.io.tlbFlush
    system.io.instructionTlbFlush := host.io.tlbFlush

    for (cu <- 0 until numComputeUnits) {
      system.io.fpu(cu).ready := false.B
      system.io.vector(cu).ready := false.B
      system.io.scalarMemory(cu).ready := false.B
      // Product software has no resumable trap service.  Each CU converts a
      // consumed trap into a failed kernel completion and retires its faulting
      // warp, so accepting the diagnostic event guarantees forward progress.
      system.io.trap(cu).ready := true.B
      system.io.simtBranch(cu).valid := false.B
      system.io.simtBranch(cu).bits :=
        0.U.asTypeOf(system.io.simtBranch(cu).bits)
    }

  }
}
