package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.command.{GpuCommand, GpuCommandResult, GpuCommandRouter, ResolveStatus}
import opengpu.core.GpuComputeUnit
import opengpu.core.backend.FpuFlags
import opengpu.core.backend.issue.{ScalarIssuedInstruction, VectorIssuedInstruction}
import opengpu.core.backend.register.{FpuRegisterWrite, ScalarRegisterWrite, VectorRegisterWrite}
import opengpu.core.execute.control.SimtBranchRequest
import opengpu.core.frontend.decode.FpuDecodeResponse
import opengpu.core.memory._
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch._
import opengpu.dma._
import opengpu.graphics.{MsaaResolveEngine, OmWordToLinePort,ShaderPayloadConfig,BankedShaderPayloadStore,ShaderPayloadControl}
import opengpu.util.{CarrySaveAccumulator, CarrySaveEventCounter}

class GpuPerformanceCounters extends Bundle {
  val cycles = UInt(64.W)
  val activeCuCycles = UInt(64.W)
  val maxActiveComputeUnits = UInt(64.W)
  val lowerReadRequests = UInt(64.W)
  val lowerWriteRequests = UInt(64.W)
  val dmaBytesCompleted = UInt(64.W)
  val l2 = new L2PerformanceCounters
}

object GpuSystem {
  private[system] val copyTransactions = 4
  private[system] val fillTransactions = 16
  private[system] val stridedCopyTransactions = 4
  private[system] val resolveTransactions = 4
  /** One PTE-walk slot per fill/blit/strided/resolve translator. */
  private[system] val dmaWalkTransactions = 4

  def totalMemoryTransactions(
    numComputeUnits: Int,
    transactionsPerCu: Int,
    graphicsHostTransactions: Int,
    enableIndirectCompute: Boolean = false
  ): Int = (numComputeUnits + 1) * transactionsPerCu + copyTransactions +
    fillTransactions + stridedCopyTransactions + resolveTransactions +
    dmaWalkTransactions + graphicsHostTransactions + (if(enableIndirectCompute) 2 else 0)
}

/** Scalable compute-first GPU integration point.
  *
  * Kernels carry host command tags, CUs execute independently, and one shared
  * memory port supports out-of-order responses through globally unique
  * transaction IDs. The graphics shader occupies an additional coherent
  * client slot, while non-cacheable graphics traffic uses a separate host ID
  * range in the same L2 namespace.
  */
class GpuSystem(
  config: GpuConfig = GpuConfig(),
  numComputeUnits: Int = 2,
  commandIdWidth: Int = 8,
  transactionsPerCu: Int = 4,
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false,
  enableUnifiedCommands: Boolean = false,
  graphicsHostTransactions: Int = 8,
  instructionCacheSets: Int = 64,
  instructionCacheWays: Int = 2,
  instructionCacheMissEntries: Int = 4,
  vectorCacheSets: Int = 64,
  vectorCacheWays: Int = 2,
  enableGraphicsKernels: Boolean = false,
  shaderPayload: Option[ShaderPayloadConfig] = None,
  producerPayloadPins: Int = 0,
  drainGraphicsPayloadWork: Boolean = false,
  shaderExecutionLimits: Boolean = false,
  enableIndirectCompute: Boolean = false,
  nativeExecutionProfile: Boolean = false
) extends Module {
  require(numComputeUnits > 0)
  require(!enableIndirectCompute || enableUnifiedCommands)
  require(!shaderExecutionLimits || enableGraphicsKernels)
  require(!drainGraphicsPayloadWork || (shaderPayload.nonEmpty && enableGraphicsKernels))
  require(shaderPayload.isEmpty || enableGraphicsKernels,"local shader payloads require the shared graphics task interface")
  require(producerPayloadPins>=0 && (producerPayloadPins==0 || shaderPayload.nonEmpty))
  shaderPayload.foreach(p=>require(p.ports>=p.owners+numComputeUnits,"reserve independent staging and CU payload response ports"))
  require(graphicsHostTransactions > 0 && isPow2(graphicsHostTransactions),
    "graphics-host transaction count must be a positive power of two")
  private val totalTransactions = numComputeUnits * transactionsPerCu
  private val graphicsShaderBase = totalTransactions
  private val coherentTransactions =
    (numComputeUnits + 1) * transactionsPerCu
  private val copyTransactions = GpuSystem.copyTransactions
  private val fillTransactions = GpuSystem.fillTransactions
  private val stridedCopyTransactions = GpuSystem.stridedCopyTransactions
  private val resolveTransactions = GpuSystem.resolveTransactions
  private val dmaWalkTransactions = GpuSystem.dmaWalkTransactions
  private val copyBase = coherentTransactions
  private val fillBase = copyBase + copyTransactions
  private val stridedCopyBase = fillBase + fillTransactions
  private val resolveBase = stridedCopyBase + stridedCopyTransactions
  private val graphicsHostBase = resolveBase + resolveTransactions
  private val dmaWalkBase = graphicsHostBase + graphicsHostTransactions
  private val copyWalkBase = dmaWalkBase
  private val fillWalkBase = copyWalkBase + 1
  private val stridedWalkBase = fillWalkBase + 1
  private val resolveWalkBase = stridedWalkBase + 1
  private val indirectBase = dmaWalkBase + dmaWalkTransactions
  private val indirectWalkBase = indirectBase + 1
  private val totalSystemTransactions =
    GpuSystem.totalMemoryTransactions(
      numComputeUnits, transactionsPerCu, graphicsHostTransactions, enableIndirectCompute)
  private val systemTransactionWidth =
    math.max(1, log2Ceil(totalSystemTransactions))

  val io = IO(new Bundle {
    val graphicsInstructionLimit = if (shaderExecutionLimits) Some(Input(UInt(32.W))) else None
    val graphicsKernels = if (enableGraphicsKernels)
      Some(new SharedCuGraphicsPort(config, commandIdWidth, numComputeUnits)) else None
    val graphicsPayload=shaderPayload.map(p=>Input(Valid(new ShaderPayloadBinding(config,p))))
    val payloadManagement=shaderPayload.map(p=>new ShaderPayloadManagementPort(p))
    val payloadProducerPins=if(producerPayloadPins>0)
      shaderPayload.map(p=>Input(Vec(producerPayloadPins,Valid(new opengpu.graphics.ShaderPayloadPin(p)))))else None
    val command = Flipped(Decoupled(
      new KernelCommand(config, commandIdWidth)))
    val commandCompletion = Decoupled(
      new KernelCommandResult(commandIdWidth))
    val copyDescriptor = Flipped(Decoupled(
      new CopyDescriptor(config, commandIdWidth)))
    val copyCompletion = Decoupled(new CopyCompletion(commandIdWidth))
    val fillDescriptor = Flipped(Decoupled(
      new FillDescriptor(config, commandIdWidth)))
    val fillCompletion = Decoupled(new FillCompletion(commandIdWidth))
    val stridedCopyDescriptor = Flipped(Decoupled(
      new StridedCopyDescriptor(config, commandIdWidth)))
    val stridedCopyCompletion = Decoupled(
      new StridedCopyCompletion(commandIdWidth))
    val gpuCommand = Flipped(Decoupled(
      new GpuCommand(config, commandIdWidth)))
    val gpuCompletion = Decoupled(new GpuCommandResult(commandIdWidth))

    /** Compatibility attachment for the graphics host's shared line port.
      * IDs are local to this client and remapped above all CU/DMA IDs before
      * entering the shared L2.
      */
    val graphicsHostRequest = Flipped(Decoupled(new ComputeMemoryRequest(
      config, 64, graphicsHostTransactions)))
    val graphicsHostResponse = Decoupled(new ComputeMemoryResponse(
      64, graphicsHostTransactions))

    /** Cached graphics-shader client. Its IDs occupy one CU-sized range so
      * the shared L2 tracks its private-cache sharers and invalidations.
      */
    val graphicsShaderRequest = Flipped(Decoupled(new ComputeMemoryRequest(
      config, 64, transactionsPerCu)))
    val graphicsShaderResponse = Decoupled(new ComputeMemoryResponse(
      64, transactionsPerCu))
    val graphicsShaderL1Invalidate = Decoupled(
      new CacheLineInvalidate(config))
    val graphicsShaderL1InvalidateDone = Flipped(Decoupled(
      new CacheLineInvalidate(config)))
    val graphicsShaderAtomicRequest = Flipped(Decoupled(
      new SharedAtomicRequest(config)))
    val graphicsShaderAtomicResponse = Decoupled(
      new SharedAtomicResponse(config))

    val memoryRequest = Decoupled(new ComputeMemoryRequest(
      config, 64, totalSystemTransactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(
      64, totalSystemTransactions)))
    val invalidateInstructionCache = Input(Bool())
    val instructionSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))
    val vectorSatp = Input(UInt(32.W))
    val vectorTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))

    // Until all decoded operations have native backends, preserve each CU's
    // explicit extension points instead of silently dropping instructions.
    val fpu = Vec(numComputeUnits, Decoupled(new FpuDecodeResponse(config)))
    val vector = Vec(numComputeUnits,
      Decoupled(new VectorIssuedInstruction(config)))
    val scalarMemory = Vec(numComputeUnits,
      Decoupled(new ScalarIssuedInstruction(config)))
    val trap = Vec(numComputeUnits, Decoupled(new CoreTrapEvent(config)))
    val simtBranch = Vec(numComputeUnits,
      Flipped(Decoupled(new SimtBranchRequest(config))))

    val committedWriteback = Output(Vec(numComputeUnits,
      Valid(new ScalarRegisterWrite(config))))
    val committedVectorWriteback = Output(Vec(numComputeUnits,
      Valid(new VectorRegisterWrite(config))))
    val committedFpuWriteback = Output(Vec(numComputeUnits,
      Valid(new FpuRegisterWrite(config))))
    val committedFpuFlags = Output(Vec(numComputeUnits,
      Valid(new FpuFlags(config))))
    val committedFpuIntegerWriteback = Output(Vec(numComputeUnits,
      Valid(new ScalarRegisterWrite(config))))
    val activeWarps = Output(Vec(numComputeUnits, UInt(config.warps.W)))
    val blockedWarps = Output(Vec(numComputeUnits, UInt(config.warps.W)))
    val barrierWaiting = Output(Vec(numComputeUnits, UInt(config.warps.W)))
    val busyComputeUnits = Output(UInt(numComputeUnits.W))
    val commandProcessorBusy = Output(Bool())
    val queuedCommands = Output(UInt(
      math.max(1, log2Ceil(config.commandQueueDepth + 1)).W))
    val inFlightCommands = Output(UInt((commandIdWidth + 1).W))
    val duplicateCommandId = Output(Bool())
    val copyEngineBusy = Output(Bool())
    val fillEngineBusy = Output(Bool())
    val stridedCopyEngineBusy = Output(Bool())
    val unifiedCommandRouterBusy = Output(Bool())
    val duplicateUnifiedCommandId = Output(Bool())
    /** Level request from the unified-command MMIO bridge: stop dispatching,
      * drain every in-flight command and memory transaction, reset the
      * command-path state, then pulse `commandResetDone`.  Meaningful only
      * when enableUnifiedCommands is set. */
    /** Parent graphics engine is idle, including its pending completions. */
    val graphicsDrained = Input(Bool())
    val commandResetActive = Input(Bool())
    val commandResetDone = Output(Bool())
    /** Quiescent boundary for shared SATP/TLB state, including queued work,
      * cache invalidations and lower-memory ownership. */
    val vmContextIdle = Output(Bool())
    /** Memory-engine publication boundary; unrelated compute may continue. */
    val graphicsDependencyIdle = Output(Bool())
    val clearPerformanceCounters = Input(Bool())
    val performance = Output(new GpuPerformanceCounters)
  })

  private val commandProcessor = Module(new GpuCommandProcessor(
    config, commandIdWidth, commandQueueDepth = config.commandQueueDepth,
    completionQueueDepth = config.completionQueueDepth))
  private val commandRouter = Module(new GpuCommandRouter(
    config, commandIdWidth, commandQueueDepth = config.commandQueueDepth,
    completionQueueDepth = config.completionQueueDepth, enableIndirectCompute = enableIndirectCompute))
  private val dispatcher = Module(new SharedCuGridDispatcher(
    config, numComputeUnits, commandIdWidth,shaderPayload))
  private val memory = Module(new SharedComputeMemoryInterconnect(
    config, numComputeUnits, 64, transactionsPerCu))
  private val l2 = Module(new SharedL2Cache(
    config, sets = config.l2Sets, ways = config.l2Ways, lineBytes = 64,
    maxOutstanding = totalSystemTransactions,
    numComputeUnits = numComputeUnits + 1,
    transactionsPerCu = transactionsPerCu, banks = config.l2Banks,
    requestQueueDepth = config.l2RequestQueueDepth,
    useSramBlackBoxes = useBlackBoxes))
  l2.io.clearPerformanceCounters := io.clearPerformanceCounters
  private val computeUnits = Seq.fill(numComputeUnits) {
    Module(
      new GpuComputeUnit(
        config,
        useBlackBoxes,
        enableFpuBackend,
        instructionCacheSets,
        instructionCacheWays,
        instructionCacheMissEntries,
        vectorCacheSets,
        vectorCacheWays,
        finishOnTrap = true,shaderPayload=shaderPayload,executionLimits=shaderExecutionLimits,gridSlices=true,
        textureAvailable=enableGraphicsKernels,taskTextureAvailability=enableGraphicsKernels,
        nativeExecutionProfile=nativeExecutionProfile,nativeUniformsAvailable=enableGraphicsKernels))
  }
  private val payloadStore=shaderPayload.map(p=>Module(new BankedShaderPayloadStore(p,useBlackBoxes,numComputeUnits+1+producerPayloadPins)))
  // The generic compute system has no graphics sampler. Keep the optional
  // texture-instruction sideband quiescent and reject those instructions
  // before issue; graphics top levels connect it to TexSampleUnit instead.
  if (!enableGraphicsKernels) computeUnits.foreach { cu =>
    cu.io.texSample.ready := false.B
    cu.io.texWriteback.valid := false.B
    cu.io.texWriteback.bits := 0.U.asTypeOf(cu.io.texWriteback.bits)
    cu.io.vectorTexSample.ready := false.B
    cu.io.vectorTexWriteback.valid := false.B
    cu.io.vectorTexWriteback.bits :=
      0.U.asTypeOf(cu.io.vectorTexWriteback.bits)
  }
  private val copyEngine = Module(new CopyEngine(
    config, descriptorIdWidth = commandIdWidth, lineBytes = 64,
    maxOutstanding = copyTransactions,
    descriptorQueueDepth = config.copyDescriptorQueueDepth))
  private val fillEngine = Module(new FillEngine(
    config, descriptorIdWidth = commandIdWidth, lineBytes = 64,
    maxOutstanding = fillTransactions,
    lineSlots = fillTransactions,
    descriptorQueueDepth = config.fillDescriptorQueueDepth))
  private val stridedCopyEngine = Module(new StridedCopyEngine(
    config, descriptorIdWidth = commandIdWidth, lineBytes = 64,
    maxOutstanding = stridedCopyTransactions,
    descriptorQueueDepth = config.stridedCopyDescriptorQueueDepth))
  // Unified fill/blit/strided/resolve addresses go through VECTOR_SATP so
  // identity maps can shrink without leaving those engines on a physical bypass.
  private val copyClient = Module(new TranslatedLineClient(
    config, outstanding = copyTransactions, preserveCachePolicy = true))
  private val fillClient = Module(new TranslatedLineClient(
    config, outstanding = fillTransactions, preserveCachePolicy = true))
  private val stridedClient = Module(new TranslatedLineClient(
    config, outstanding = stridedCopyTransactions, preserveCachePolicy = true))
  copyClient.io.in <> copyEngine.io.memoryRequest
  copyEngine.io.memoryResponse <> copyClient.io.out
  fillClient.io.in <> fillEngine.io.memoryRequest
  fillEngine.io.memoryResponse <> fillClient.io.out
  stridedClient.io.in <> stridedCopyEngine.io.memoryRequest
  stridedCopyEngine.io.memoryResponse <> stridedClient.io.out
  // MSAA resolve: a word-level engine behind a word-to-line bridge, then the
  // same Sv32 line translator as the other DMA clients.
  private val resolveEngine = Module(new MsaaResolveEngine())
  private val resolvePort = Module(new OmWordToLinePort(
    config, lineBytes = 64, maxOutstanding = resolveTransactions))
  private val resolveClient = Module(new TranslatedLineClient(
    config, outstanding = resolveTransactions, preserveCachePolicy = true))
  resolvePort.io.in <> resolveEngine.io.mem.req
  resolveEngine.io.mem.resp <> resolvePort.io.out
  resolveClient.io.in <> resolvePort.io.memoryRequest
  resolvePort.io.memoryResponse <> resolveClient.io.out
  private val indirectClient = if(enableIndirectCompute)
    Some(Module(new TranslatedLineClient(config,outstanding=1,preserveCachePolicy=true))) else None
  indirectClient.foreach { client =>
    client.io.in <> commandRouter.io.indirectMemoryRequest.get
    commandRouter.io.indirectMemoryResponse.get <> client.io.out
  }
  for (client <- Seq(copyClient, fillClient, stridedClient, resolveClient) ++ indirectClient.toSeq) {
    client.io.satp := io.vectorSatp
    client.io.flush := io.vectorTlbFlush
  }

  dispatcher.io.launch <> commandProcessor.io.dispatch
  commandProcessor.io.dispatchCompletion <> dispatcher.io.completion
  private val graphicsDispatchBlocked = WireDefault(false.B)
  // A renderer's accepted job owns its later staging/launch sub-operations.
  // They must drain before graphicsDrained can rise during command reset.
  private val drainingPayloadWork=if(drainGraphicsPayloadWork) !io.graphicsDrained else false.B
  private val graphicsWorkAllowed= !graphicsDispatchBlocked || drainingPayloadWork
  private val graphicsCacheDrained = WireDefault(true.B)
  private val payloadContextDrained=WireDefault(true.B)
  private val payloadInputsIdle=WireDefault(true.B)
  shaderPayload.foreach { p=>
    val pool=payloadStore.get
    val producer=io.payloadManagement.get
    dispatcher.io.graphicsPayload.get:=io.graphicsPayload.get
    for(i <- 0 until numComputeUnits+1)pool.io.pins.get(i):=dispatcher.io.payloadPins.get(i)
    for(i <- 0 until producerPayloadPins)pool.io.pins.get(numComputeUnits+1+i):=io.payloadProducerPins.get(i)
    for(i <- 0 until p.owners) {
      val command=producer.control(i)
      val allowed=graphicsWorkAllowed ||
        (command.bits.operation=/=ShaderPayloadControl.allocate.U && !pool.io.drained)
      pool.io.control(i).valid:=command.valid && allowed
      pool.io.control(i).bits:=command.bits
      command.ready:=pool.io.control(i).ready && allowed
      producer.controlResponse(i)<>pool.io.controlResponse(i)
      pool.io.request(i).valid:=producer.request(i).valid && graphicsWorkAllowed
      pool.io.request(i).bits:=producer.request(i).bits
      pool.io.request(i).bits.owner:=i.U
      producer.request(i).ready:=pool.io.request(i).ready && graphicsWorkAllowed
      producer.response(i)<>pool.io.response(i)
    }
    for(i <- p.owners+numComputeUnits until p.ports) {
      pool.io.request(i).valid:=false.B
      pool.io.request(i).bits:=0.U.asTypeOf(pool.io.request(i).bits)
      pool.io.response(i).ready:=true.B
    }
    producer.reservedBytes:=pool.io.reservedBytes
    producer.liveBytes:=pool.io.liveBytes
    producer.drained:=pool.io.drained
    payloadContextDrained:=pool.io.drained
    payloadInputsIdle:= !producer.control.map(_.valid).reduce(_ || _) &&
      !producer.request.map(_.valid).reduce(_ || _) && !io.graphicsKernels.get.launch.valid
  }
  if (enableGraphicsKernels) {
    val graphics = io.graphicsKernels.get
    dispatcher.io.graphicsLaunch.valid := graphics.launch.valid && graphicsWorkAllowed
    dispatcher.io.graphicsLaunch.bits := graphics.launch.bits
    graphics.launch.ready := dispatcher.io.graphicsLaunch.ready && graphicsWorkAllowed
    graphics.completion <> dispatcher.io.graphicsCompletion
    graphics.owned := dispatcher.io.graphicsOwner
    graphics.taskIds := dispatcher.io.taskIds
  } else {
    dispatcher.io.graphicsLaunch.valid := false.B
    dispatcher.io.graphicsLaunch.bits := 0.U.asTypeOf(dispatcher.io.graphicsLaunch.bits)
    dispatcher.io.graphicsCompletion.ready := true.B
  }
  io.busyComputeUnits := dispatcher.io.busy
  io.commandProcessorBusy := commandProcessor.io.busy
  io.queuedCommands := commandProcessor.io.queued
  io.inFlightCommands := commandProcessor.io.inFlight
  io.duplicateCommandId := commandProcessor.io.duplicateCommandId
  io.copyEngineBusy := copyEngine.io.busy
  io.fillEngineBusy := fillEngine.io.busy
  io.stridedCopyEngineBusy := stridedCopyEngine.io.busy
  io.unifiedCommandRouterBusy := commandRouter.io.busy
  io.duplicateUnifiedCommandId := commandRouter.io.duplicateCommandId

  // Line-invalidate engines.  The resolve adapter invalidates its source range
  // before reading (a page recycled from an earlier GPU buffer must not shadow
  // a CPU write), and the unified invalidate command lets the driver drop a
  // range explicitly.  Both share the L2's single host invalidate port.
  private val resolveInvalidate = Module(new LineInvalidateEngine(
    config, commandIdWidth))
  private val driverInvalidate = Module(new LineInvalidateEngine(
    config, commandIdWidth))
  private val invalidateEngines = Seq(resolveInvalidate, driverInvalidate)
  private val invalidateArb = Module(new RRArbiter(
    new CacheLineInvalidate(config), 2))
  private val invalidateBusy = RegInit(false.B)
  private val invalidateOwner = Reg(UInt(1.W))
  for (i <- 0 until 2) {
    invalidateArb.io.in(i).valid :=
      invalidateEngines(i).io.hostInvalidate.valid && !invalidateBusy
    invalidateArb.io.in(i).bits :=
      invalidateEngines(i).io.hostInvalidate.bits
    invalidateEngines(i).io.hostInvalidate.ready :=
      invalidateArb.io.in(i).ready && !invalidateBusy
  }
  l2.io.hostInvalidate.valid := invalidateArb.io.out.valid
  l2.io.hostInvalidate.bits := invalidateArb.io.out.bits
  invalidateArb.io.out.ready := l2.io.hostInvalidate.ready
  invalidateEngines(0).io.hostInvalidateDone.valid :=
    l2.io.hostInvalidateDone.valid && invalidateBusy &&
      invalidateOwner === 0.U
  invalidateEngines(0).io.hostInvalidateDone.bits :=
    l2.io.hostInvalidateDone.bits
  invalidateEngines(1).io.hostInvalidateDone.valid :=
    l2.io.hostInvalidateDone.valid && invalidateBusy &&
      invalidateOwner === 1.U
  invalidateEngines(1).io.hostInvalidateDone.bits :=
    l2.io.hostInvalidateDone.bits
  l2.io.hostInvalidateDone.ready := invalidateBusy &&
    Mux(invalidateOwner === 0.U,
      invalidateEngines(0).io.hostInvalidateDone.ready,
      invalidateEngines(1).io.hostInvalidateDone.ready)
  when(l2.io.hostInvalidate.fire) {
    invalidateBusy := true.B
    invalidateOwner := invalidateArb.io.chosen
  }
  when(l2.io.hostInvalidateDone.fire) { invalidateBusy := false.B }

  // Typed resolve adapter: PA-tagged invalidate, then translated engine traffic.
  private val resolveActive = RegInit(false.B)
  private val resolveDonePending = RegInit(false.B)
  private val resolveCommandId = Reg(UInt(commandIdWidth.W))
  private val resolveBytes = Reg(UInt(64.W))
  private val resolveInvPending = RegInit(false.B)
  private val resolveInvIssued = RegInit(false.B)
  private val resolveSrcBase = Reg(UInt(config.xLen.W))
  private val resolveDstBase = Reg(UInt(config.xLen.W))
  private val resolveInvBase = Reg(UInt(config.xLen.W))
  private val resolveSrcStride = Reg(UInt(32.W))
  private val resolveDstStride = Reg(UInt(32.W))
  private val resolveWidth = Reg(UInt(16.W))
  private val resolveHeight = Reg(UInt(16.W))
  private val resolveMode = Reg(UInt(2.W))
  private val resolveDesc = commandRouter.io.resolve
  // A contiguous span covers every row including any inter-row padding.
  private val resolveInvBytes =
    ((resolveHeight - 1.U) * resolveSrcStride +
      (resolveWidth << (resolveMode + 2.U)))(31, 0)
  resolveDesc.ready := !resolveActive && !resolveDonePending &&
    !resolveInvPending
  resolveInvalidate.io.request.valid := resolveInvPending && !resolveInvIssued
  resolveInvalidate.io.request.bits.descriptorId := 0.U
  resolveInvalidate.io.request.bits.address := resolveInvBase
  resolveInvalidate.io.request.bits.bytes := resolveInvBytes
  resolveInvalidate.io.completion.ready := true.B
  resolveEngine.io.start := resolveInvalidate.io.completion.valid
  resolveEngine.io.srcBase := resolveSrcBase
  resolveEngine.io.dstBase := resolveDstBase
  resolveEngine.io.srcStride := resolveSrcStride
  resolveEngine.io.dstStride := resolveDstStride
  resolveEngine.io.imgWidth := resolveWidth
  resolveEngine.io.imgHeight := resolveHeight
  resolveEngine.io.sampleMode := resolveMode
  when(resolveDesc.fire) {
    resolveActive := true.B
    resolveCommandId := resolveDesc.bits.descriptorId
    resolveBytes :=
      (resolveDesc.bits.imgWidth * resolveDesc.bits.imgHeight * 4.U).pad(64)
    resolveSrcBase := resolveDesc.bits.sourceAddress
    resolveDstBase := resolveDesc.bits.destinationAddress
    resolveInvBase := resolveDesc.bits.invalidateAddress
    resolveSrcStride := resolveDesc.bits.sourceStride
    resolveDstStride := resolveDesc.bits.destinationStride
    resolveWidth := resolveDesc.bits.imgWidth
    resolveHeight := resolveDesc.bits.imgHeight
    resolveMode := resolveDesc.bits.sampleMode
    resolveInvPending := true.B
    resolveInvIssued := false.B
  }
  when(resolveInvalidate.io.request.fire) { resolveInvIssued := true.B }
  when(resolveEngine.io.start) {
    resolveInvPending := false.B
    resolveInvIssued := false.B
  }
  when(resolveEngine.io.done) { resolveActive := false.B }
  commandRouter.io.resolveCompletion.valid := resolveDonePending
  commandRouter.io.resolveCompletion.bits.descriptorId := resolveCommandId
  commandRouter.io.resolveCompletion.bits.status := ResolveStatus.success
  commandRouter.io.resolveCompletion.bits.success := true.B
  commandRouter.io.resolveCompletion.bits.bytesResolved := resolveBytes
  when(resolveEngine.io.done) { resolveDonePending := true.B }
  when(commandRouter.io.resolveCompletion.fire) { resolveDonePending := false.B }

  // Safe unified-command reset: while the MMIO bridge holds
  // commandResetActive, stop dispatching queued commands, wait for every
  // engine, kernel and shared-L2 transaction to drain (completions keep
  // flowing and are discarded by the bridge), then pulse a synchronous reset
  // through the command path and acknowledge.  A command that never retires
  // (hung kernel or lower memory) keeps the FSM in `draining`; only a full
  // fabric reset recovers from that.
  commandRouter.io.blockDispatch := false.B
  commandRouter.io.pathReset := false.B
  if(enableIndirectCompute) commandRouter.io.cancelIndirect.get := io.commandResetActive
  commandProcessor.io.pathReset := false.B
  io.graphicsDependencyIdle := !commandRouter.io.memoryEngineBusy &&
    !copyEngine.io.busy && !fillEngine.io.busy && !stridedCopyEngine.io.busy &&
    !resolveEngine.io.busy && !resolveActive && !resolveDonePending && !commandRouter.io.indirectBusy &&
    !io.copyDescriptor.valid && !io.fillDescriptor.valid && !io.stridedCopyDescriptor.valid &&
    !io.copyCompletion.valid && !io.fillCompletion.valid && !io.stridedCopyCompletion.valid
  private val contextDrained = io.graphicsDrained && !copyEngine.io.busy && !fillEngine.io.busy &&
    !stridedCopyEngine.io.busy && !resolveEngine.io.busy && !commandRouter.io.indirectBusy &&
    !resolveActive && !resolveDonePending &&
    !commandProcessor.io.busy && !dispatcher.io.busy.orR && graphicsCacheDrained && payloadContextDrained &&
    !dispatcher.io.graphicsCompletion.valid &&
    l2.io.drained && !commandRouter.io.completion.valid
  io.vmContextIdle := contextDrained && payloadInputsIdle && !commandRouter.io.busy &&
    !io.command.valid && !io.gpuCommand.valid && !io.copyDescriptor.valid &&
    !io.fillDescriptor.valid && !io.stridedCopyDescriptor.valid &&
    !io.graphicsHostRequest.valid && !io.graphicsShaderRequest.valid &&
    !io.graphicsShaderAtomicRequest.valid && !io.commandResetActive &&
    // L2.drained is a memory-visibility fence; response presentation can
    // outlive it. A VM boundary must also retire those client-owned results.
    !io.graphicsHostResponse.valid && !io.graphicsShaderResponse.valid &&
    !io.graphicsShaderAtomicResponse.valid && !io.commandCompletion.valid &&
    !io.copyCompletion.valid && !io.fillCompletion.valid &&
    !io.stridedCopyCompletion.valid &&
    !io.graphicsKernels.map(p => p.completion.valid || p.trap.valid).getOrElse(false.B)
  if (enableUnifiedCommands) {
    object ResetState extends ChiselEnum { val idle, draining, resetting = Value }
    val resetState = RegInit(ResetState.idle)
    val quiesced = contextDrained
    // Held across the resetting cycle too: otherwise a queue flush and a
    // dispatch fire on the same edge and launch the flushed command.
    commandRouter.io.blockDispatch := resetState =/= ResetState.idle
    if(enableIndirectCompute) commandRouter.io.cancelIndirect.get :=
      io.commandResetActive || resetState =/= ResetState.idle
    // These are shader batches belonging to an already admitted render, not
    // new host commands. The command router blocks new renders; the active
    // renderer must still launch its remaining batches/invalidations while
    // draining, or reset waits forever for graphicsDrained. Once the owner
    // reports drained, reject further tasks through the resetting cycle.
    graphicsDispatchBlocked := resetState === ResetState.resetting ||
      ((resetState =/= ResetState.idle || io.commandResetActive) && io.graphicsDrained)
    commandRouter.io.pathReset := resetState === ResetState.resetting
    commandProcessor.io.pathReset := resetState === ResetState.resetting
    io.commandResetDone := resetState === ResetState.resetting
    switch(resetState) {
      is(ResetState.idle) {
        when(io.commandResetActive) { resetState := ResetState.draining }
      }
      is(ResetState.draining) {
        when(quiesced) { resetState := ResetState.resetting }
      }
      is(ResetState.resetting) {
        resetState := ResetState.idle
      }
    }
  } else {
    io.commandResetDone := false.B
  }
  private val cycleCounter = Module(new CarrySaveEventCounter())
  private val activeCuCycleCounter = Module(new CarrySaveAccumulator())
  private val lowerReadCounter = Module(new CarrySaveEventCounter())
  private val lowerWriteCounter = Module(new CarrySaveEventCounter())
  // Register each engine's completion bytes before accumulating so the addend
  // mux does not sit behind the router's completion-arbiter ready path.
  private val copyBytesFired = RegNext(copyEngine.io.completion.fire, false.B)
  private val copyBytesValue = RegEnable(
    copyEngine.io.completion.bits.bytesCopied, copyEngine.io.completion.fire)
  private val fillBytesFired = RegNext(fillEngine.io.completion.fire, false.B)
  private val fillBytesValue = RegEnable(
    fillEngine.io.completion.bits.bytesFilled, fillEngine.io.completion.fire)
  private val stridedBytesFired =
    RegNext(stridedCopyEngine.io.completion.fire, false.B)
  private val stridedBytesValue = RegEnable(
    stridedCopyEngine.io.completion.bits.bytesCopied,
    stridedCopyEngine.io.completion.fire)
  // Three muxed contributions are absorbed into a local carry-save DMA
  // accumulator with cascaded 3:2 compressors (all bitwise). No 64-bit CPA
  // sits on a core-clock register-to-register path (redfold limiter was
  // fillBytesFired → dmaBytesDelta[63]). Observation CPA stays on the
  // performance-port / vclk path only.
  private val clearPerf = io.clearPerformanceCounters
  private val copyBytesTerm = Mux(copyBytesFired, copyBytesValue, 0.U(64.W))
  private val fillBytesTerm = Mux(fillBytesFired, fillBytesValue, 0.U(64.W))
  private val stridedBytesTerm =
    Mux(stridedBytesFired, stridedBytesValue, 0.U(64.W))
  private val dmaBytesSum = RegInit(0.U(64.W))
  private val dmaBytesCarry = RegInit(0.U(64.W))
  private def csa3(a: UInt, b: UInt, c: UInt): (UInt, UInt) = {
    val sum = a ^ b ^ c
    val carry = ((a & b) | (a & c) | (b & c)) << 1
    (sum, carry(63, 0))
  }
  private val (termSum, termCarry) =
    csa3(copyBytesTerm, fillBytesTerm, stridedBytesTerm)
  private val (foldSum, foldCarry) =
    csa3(dmaBytesSum, dmaBytesCarry, termSum)
  private val (nextDmaSum, nextDmaCarry) =
    csa3(foldSum, foldCarry, termCarry)
  when(clearPerf) {
    dmaBytesSum := 0.U
    dmaBytesCarry := 0.U
  }.otherwise {
    dmaBytesSum := nextDmaSum
    dmaBytesCarry := nextDmaCarry
  }
  cycleCounter.io.clear := clearPerf
  cycleCounter.io.increment := !clearPerf
  activeCuCycleCounter.io.clear := clearPerf
  activeCuCycleCounter.io.addend := Mux(clearPerf, 0.U, PopCount(dispatcher.io.busy))
  lowerReadCounter.io.clear := clearPerf
  lowerReadCounter.io.increment := !clearPerf &&
    io.memoryRequest.fire && !io.memoryRequest.bits.isWrite
  lowerWriteCounter.io.clear := clearPerf
  lowerWriteCounter.io.increment := !clearPerf &&
    io.memoryRequest.fire && io.memoryRequest.bits.isWrite
  io.performance.cycles := cycleCounter.io.value
  io.performance.activeCuCycles := activeCuCycleCounter.io.value
  private val peakActiveCus = RegInit(0.U(64.W))
  private val activeCus = PopCount(dispatcher.io.busy)
  when(clearPerf) { peakActiveCus := 0.U }
    .elsewhen(activeCus > peakActiveCus) { peakActiveCus := activeCus }
  io.performance.maxActiveComputeUnits := peakActiveCus
  io.performance.lowerReadRequests := lowerReadCounter.io.value
  io.performance.lowerWriteRequests := lowerWriteCounter.io.value
  io.performance.dmaBytesCompleted := (dmaBytesSum +& dmaBytesCarry)(63, 0)
  io.performance.l2 := l2.io.performance

  driverInvalidate.io.request <> commandRouter.io.invalidate
  commandRouter.io.invalidateCompletion <> driverInvalidate.io.completion

  if (enableUnifiedCommands) {
    commandRouter.io.command <> io.gpuCommand
    io.gpuCompletion <> commandRouter.io.completion
    commandProcessor.io.command <> commandRouter.io.kernel
    commandRouter.io.kernelCompletion <> commandProcessor.io.completion
    copyEngine.io.descriptor <> commandRouter.io.copy
    commandRouter.io.copyCompletion <> copyEngine.io.completion
    fillEngine.io.descriptor <> commandRouter.io.fill
    commandRouter.io.fillCompletion <> fillEngine.io.completion
    stridedCopyEngine.io.descriptor <> commandRouter.io.stridedCopy
    commandRouter.io.stridedCopyCompletion <> stridedCopyEngine.io.completion

    io.command.ready := false.B
    io.commandCompletion.valid := false.B
    io.commandCompletion.bits := 0.U.asTypeOf(io.commandCompletion.bits)
    io.copyDescriptor.ready := false.B
    io.copyCompletion.valid := false.B
    io.copyCompletion.bits := 0.U.asTypeOf(io.copyCompletion.bits)
    io.fillDescriptor.ready := false.B
    io.fillCompletion.valid := false.B
    io.fillCompletion.bits := 0.U.asTypeOf(io.fillCompletion.bits)
    io.stridedCopyDescriptor.ready := false.B
    io.stridedCopyCompletion.valid := false.B
    io.stridedCopyCompletion.bits :=
      0.U.asTypeOf(io.stridedCopyCompletion.bits)
  } else {
    commandProcessor.io.command <> io.command
    io.commandCompletion <> commandProcessor.io.completion
    copyEngine.io.descriptor <> io.copyDescriptor
    io.copyCompletion <> copyEngine.io.completion
    fillEngine.io.descriptor <> io.fillDescriptor
    io.fillCompletion <> fillEngine.io.completion
    stridedCopyEngine.io.descriptor <> io.stridedCopyDescriptor
    io.stridedCopyCompletion <> stridedCopyEngine.io.completion

    io.gpuCommand.ready := false.B
    io.gpuCompletion.valid := false.B
    io.gpuCompletion.bits := 0.U.asTypeOf(io.gpuCompletion.bits)
    commandRouter.io.command.valid := false.B
    commandRouter.io.command.bits :=
      0.U.asTypeOf(commandRouter.io.command.bits)
    commandRouter.io.completion.ready := false.B
    commandRouter.io.kernel.ready := false.B
    commandRouter.io.copy.ready := false.B
    commandRouter.io.fill.ready := false.B
    commandRouter.io.stridedCopy.ready := false.B
    commandRouter.io.kernelCompletion.valid := false.B
    commandRouter.io.kernelCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.kernelCompletion.bits)
    commandRouter.io.copyCompletion.valid := false.B
    commandRouter.io.copyCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.copyCompletion.bits)
    commandRouter.io.fillCompletion.valid := false.B
    commandRouter.io.fillCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.fillCompletion.bits)
    commandRouter.io.stridedCopyCompletion.valid := false.B
    commandRouter.io.stridedCopyCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.stridedCopyCompletion.bits)
  }

  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).valid :=
    copyEngine.io.completion.fire
  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).bits.source :=
    DmaEventSource.copy
  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).bits.descriptorId :=
    copyEngine.io.completion.bits.descriptorId
  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).bits.success :=
    copyEngine.io.completion.bits.success
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).valid :=
    fillEngine.io.completion.fire
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).bits.source :=
    DmaEventSource.fill
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).bits.descriptorId :=
    fillEngine.io.completion.bits.descriptorId
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).bits.success :=
    fillEngine.io.completion.bits.success
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).valid :=
    stridedCopyEngine.io.completion.fire
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).bits.source :=
    DmaEventSource.stridedCopy
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).bits.descriptorId :=
    stridedCopyEngine.io.completion.bits.descriptorId
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).bits.success :=
    stridedCopyEngine.io.completion.bits.success
  if(enableIndirectCompute) commandRouter.io.dmaCompletion.get := commandProcessor.io.dmaCompletion

  private val l2RequestArbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(config, 64, totalSystemTransactions), if(enableIndirectCompute) 13 else 11))
  l2RequestArbiter.io.in(0).valid := memory.io.memoryRequest.valid
  l2RequestArbiter.io.in(0).bits := memory.io.memoryRequest.bits
  memory.io.memoryRequest.ready := l2RequestArbiter.io.in(0).ready
  l2RequestArbiter.io.in(1).valid := io.graphicsShaderRequest.valid
  l2RequestArbiter.io.in(1).bits := io.graphicsShaderRequest.bits
  l2RequestArbiter.io.in(1).bits.transactionId :=
    graphicsShaderBase.U(systemTransactionWidth.W) +
    io.graphicsShaderRequest.bits.transactionId
  io.graphicsShaderRequest.ready := l2RequestArbiter.io.in(1).ready
  l2RequestArbiter.io.in(2).valid := copyClient.io.memReq.valid
  l2RequestArbiter.io.in(2).bits := copyClient.io.memReq.bits
  l2RequestArbiter.io.in(2).bits.transactionId :=
    copyBase.U(systemTransactionWidth.W) +
    copyClient.io.memReq.bits.transactionId
  copyClient.io.memReq.ready := l2RequestArbiter.io.in(2).ready
  l2RequestArbiter.io.in(3).valid := fillClient.io.memReq.valid
  l2RequestArbiter.io.in(3).bits := fillClient.io.memReq.bits
  l2RequestArbiter.io.in(3).bits.transactionId :=
    fillBase.U(systemTransactionWidth.W) +
    fillClient.io.memReq.bits.transactionId
  fillClient.io.memReq.ready := l2RequestArbiter.io.in(3).ready
  l2RequestArbiter.io.in(4).valid := stridedClient.io.memReq.valid
  l2RequestArbiter.io.in(4).bits := stridedClient.io.memReq.bits
  l2RequestArbiter.io.in(4).bits.transactionId :=
    stridedCopyBase.U(systemTransactionWidth.W) +
    stridedClient.io.memReq.bits.transactionId
  stridedClient.io.memReq.ready := l2RequestArbiter.io.in(4).ready
  l2RequestArbiter.io.in(5).valid := io.graphicsHostRequest.valid
  l2RequestArbiter.io.in(5).bits := io.graphicsHostRequest.bits
  l2RequestArbiter.io.in(5).bits.transactionId :=
    graphicsHostBase.U(systemTransactionWidth.W) +
    io.graphicsHostRequest.bits.transactionId
  io.graphicsHostRequest.ready := l2RequestArbiter.io.in(5).ready
  l2RequestArbiter.io.in(6).valid := resolveClient.io.memReq.valid
  l2RequestArbiter.io.in(6).bits := resolveClient.io.memReq.bits
  l2RequestArbiter.io.in(6).bits.transactionId :=
    resolveBase.U(systemTransactionWidth.W) +
    resolveClient.io.memReq.bits.transactionId
  resolveClient.io.memReq.ready := l2RequestArbiter.io.in(6).ready
  l2RequestArbiter.io.in(7).valid := copyClient.io.pageWalk.valid
  l2RequestArbiter.io.in(7).bits := copyClient.io.pageWalk.bits
  l2RequestArbiter.io.in(7).bits.transactionId :=
    copyWalkBase.U(systemTransactionWidth.W)
  copyClient.io.pageWalk.ready := l2RequestArbiter.io.in(7).ready
  l2RequestArbiter.io.in(8).valid := fillClient.io.pageWalk.valid
  l2RequestArbiter.io.in(8).bits := fillClient.io.pageWalk.bits
  l2RequestArbiter.io.in(8).bits.transactionId :=
    fillWalkBase.U(systemTransactionWidth.W)
  fillClient.io.pageWalk.ready := l2RequestArbiter.io.in(8).ready
  l2RequestArbiter.io.in(9).valid := stridedClient.io.pageWalk.valid
  l2RequestArbiter.io.in(9).bits := stridedClient.io.pageWalk.bits
  l2RequestArbiter.io.in(9).bits.transactionId :=
    stridedWalkBase.U(systemTransactionWidth.W)
  stridedClient.io.pageWalk.ready := l2RequestArbiter.io.in(9).ready
  l2RequestArbiter.io.in(10).valid := resolveClient.io.pageWalk.valid
  l2RequestArbiter.io.in(10).bits := resolveClient.io.pageWalk.bits
  l2RequestArbiter.io.in(10).bits.transactionId :=
    resolveWalkBase.U(systemTransactionWidth.W)
  resolveClient.io.pageWalk.ready := l2RequestArbiter.io.in(10).ready
  indirectClient.foreach { client =>
    l2RequestArbiter.io.in(11).valid := client.io.memReq.valid
    l2RequestArbiter.io.in(11).bits := client.io.memReq.bits
    l2RequestArbiter.io.in(11).bits.transactionId := indirectBase.U(systemTransactionWidth.W)
    client.io.memReq.ready := l2RequestArbiter.io.in(11).ready
    l2RequestArbiter.io.in(12).valid := client.io.pageWalk.valid
    l2RequestArbiter.io.in(12).bits := client.io.pageWalk.bits
    l2RequestArbiter.io.in(12).bits.transactionId := indirectWalkBase.U(systemTransactionWidth.W)
    client.io.pageWalk.ready := l2RequestArbiter.io.in(12).ready
  }
  l2.io.request <> l2RequestArbiter.io.out

  private val l2ResponseForCu =
    l2.io.response.bits.transactionId < totalTransactions.U
  private val l2ResponseForGraphicsShader =
    l2.io.response.bits.transactionId >= graphicsShaderBase.U &&
      l2.io.response.bits.transactionId < copyBase.U
  private val l2ResponseForCopy =
    l2.io.response.bits.transactionId >= copyBase.U &&
      l2.io.response.bits.transactionId < fillBase.U
  private val l2ResponseForFill =
    l2.io.response.bits.transactionId >= fillBase.U &&
      l2.io.response.bits.transactionId < stridedCopyBase.U
  private val l2ResponseForStridedCopy =
    l2.io.response.bits.transactionId >= stridedCopyBase.U &&
      l2.io.response.bits.transactionId < resolveBase.U
  private val l2ResponseForResolve =
    l2.io.response.bits.transactionId >= resolveBase.U &&
      l2.io.response.bits.transactionId < graphicsHostBase.U
  private val l2ResponseForGraphicsHost =
    l2.io.response.bits.transactionId >= graphicsHostBase.U &&
      l2.io.response.bits.transactionId < dmaWalkBase.U
  private val l2ResponseForCopyWalk =
    l2.io.response.bits.transactionId === copyWalkBase.U
  private val l2ResponseForFillWalk =
    l2.io.response.bits.transactionId === fillWalkBase.U
  private val l2ResponseForStridedWalk =
    l2.io.response.bits.transactionId === stridedWalkBase.U
  private val l2ResponseForResolveWalk =
    l2.io.response.bits.transactionId === resolveWalkBase.U
  private val l2ResponseForIndirect = if(enableIndirectCompute)
    l2.io.response.bits.transactionId === indirectBase.U else false.B
  private val l2ResponseForIndirectWalk = if(enableIndirectCompute)
    l2.io.response.bits.transactionId === indirectWalkBase.U else false.B
  memory.io.memoryResponse.valid := l2.io.response.valid && l2ResponseForCu
  memory.io.memoryResponse.bits.readData := l2.io.response.bits.readData
  memory.io.memoryResponse.bits.fault := l2.io.response.bits.fault
  memory.io.memoryResponse.bits.transactionId :=
    l2.io.response.bits.transactionId
  io.graphicsShaderResponse.valid :=
    l2.io.response.valid && l2ResponseForGraphicsShader
  io.graphicsShaderResponse.bits.readData := l2.io.response.bits.readData
  io.graphicsShaderResponse.bits.fault := l2.io.response.bits.fault
  io.graphicsShaderResponse.bits.transactionId :=
    l2.io.response.bits.transactionId - graphicsShaderBase.U
  copyClient.io.memResp.valid :=
    l2.io.response.valid && l2ResponseForCopy
  copyClient.io.memResp.bits.readData := l2.io.response.bits.readData
  copyClient.io.memResp.bits.fault := l2.io.response.bits.fault
  copyClient.io.memResp.bits.transactionId :=
    l2.io.response.bits.transactionId - copyBase.U
  fillClient.io.memResp.valid :=
    l2.io.response.valid && l2ResponseForFill
  fillClient.io.memResp.bits.readData := l2.io.response.bits.readData
  fillClient.io.memResp.bits.fault := l2.io.response.bits.fault
  fillClient.io.memResp.bits.transactionId :=
    l2.io.response.bits.transactionId - fillBase.U
  stridedClient.io.memResp.valid :=
    l2.io.response.valid && l2ResponseForStridedCopy
  stridedClient.io.memResp.bits.readData :=
    l2.io.response.bits.readData
  stridedClient.io.memResp.bits.fault :=
    l2.io.response.bits.fault
  stridedClient.io.memResp.bits.transactionId :=
    l2.io.response.bits.transactionId - stridedCopyBase.U
  resolveClient.io.memResp.valid :=
    l2.io.response.valid && l2ResponseForResolve
  resolveClient.io.memResp.bits.readData := l2.io.response.bits.readData
  resolveClient.io.memResp.bits.fault := l2.io.response.bits.fault
  resolveClient.io.memResp.bits.transactionId :=
    l2.io.response.bits.transactionId - resolveBase.U
  io.graphicsHostResponse.valid :=
    l2.io.response.valid && l2ResponseForGraphicsHost
  io.graphicsHostResponse.bits.readData := l2.io.response.bits.readData
  io.graphicsHostResponse.bits.fault := l2.io.response.bits.fault
  io.graphicsHostResponse.bits.transactionId :=
    l2.io.response.bits.transactionId - graphicsHostBase.U
  copyClient.io.pageWalkResp.valid :=
    l2.io.response.valid && l2ResponseForCopyWalk
  copyClient.io.pageWalkResp.bits.readData := l2.io.response.bits.readData
  copyClient.io.pageWalkResp.bits.fault := l2.io.response.bits.fault
  copyClient.io.pageWalkResp.bits.transactionId := 0.U
  fillClient.io.pageWalkResp.valid :=
    l2.io.response.valid && l2ResponseForFillWalk
  fillClient.io.pageWalkResp.bits.readData := l2.io.response.bits.readData
  fillClient.io.pageWalkResp.bits.fault := l2.io.response.bits.fault
  fillClient.io.pageWalkResp.bits.transactionId := 0.U
  stridedClient.io.pageWalkResp.valid :=
    l2.io.response.valid && l2ResponseForStridedWalk
  stridedClient.io.pageWalkResp.bits.readData := l2.io.response.bits.readData
  stridedClient.io.pageWalkResp.bits.fault := l2.io.response.bits.fault
  stridedClient.io.pageWalkResp.bits.transactionId := 0.U
  resolveClient.io.pageWalkResp.valid :=
    l2.io.response.valid && l2ResponseForResolveWalk
  resolveClient.io.pageWalkResp.bits.readData := l2.io.response.bits.readData
  resolveClient.io.pageWalkResp.bits.fault := l2.io.response.bits.fault
  resolveClient.io.pageWalkResp.bits.transactionId := 0.U
  indirectClient.foreach { client =>
    client.io.memResp.valid := l2.io.response.valid && l2ResponseForIndirect
    client.io.memResp.bits := l2.io.response.bits
    client.io.memResp.bits.transactionId := 0.U
    client.io.pageWalkResp.valid := l2.io.response.valid && l2ResponseForIndirectWalk
    client.io.pageWalkResp.bits := l2.io.response.bits
    client.io.pageWalkResp.bits.transactionId := 0.U
  }
  l2.io.response.ready := MuxCase(false.B, Seq(
    l2ResponseForCu -> memory.io.memoryResponse.ready,
    l2ResponseForGraphicsShader -> io.graphicsShaderResponse.ready,
    l2ResponseForCopy -> copyClient.io.memResp.ready,
    l2ResponseForFill -> fillClient.io.memResp.ready,
    l2ResponseForStridedCopy -> stridedClient.io.memResp.ready,
    l2ResponseForResolve -> resolveClient.io.memResp.ready,
    l2ResponseForGraphicsHost -> io.graphicsHostResponse.ready,
    l2ResponseForCopyWalk -> copyClient.io.pageWalkResp.ready,
    l2ResponseForFillWalk -> fillClient.io.pageWalkResp.ready,
    l2ResponseForStridedWalk -> stridedClient.io.pageWalkResp.ready,
    l2ResponseForResolveWalk -> resolveClient.io.pageWalkResp.ready) ++
    indirectClient.toSeq.flatMap(client => Seq(
      l2ResponseForIndirect -> client.io.memResp.ready,
      l2ResponseForIndirectWalk -> client.io.pageWalkResp.ready)))
  when(l2.io.response.valid) {
    assert(l2ResponseForCu || l2ResponseForGraphicsShader ||
      l2ResponseForCopy || l2ResponseForFill || l2ResponseForStridedCopy ||
      l2ResponseForResolve || l2ResponseForGraphicsHost ||
      l2ResponseForCopyWalk || l2ResponseForFillWalk ||
      l2ResponseForStridedWalk || l2ResponseForResolveWalk || l2ResponseForIndirect || l2ResponseForIndirectWalk,
      "L2 response must target a CU, graphics shader, DMA, resolve, host or DMA walk")
  }
  io.memoryRequest <> l2.io.memoryRequest
  l2.io.memoryResponse <> io.memoryResponse

  private val graphicsTraps = if (enableGraphicsKernels) Some(Module(new RRArbiter(
    new TaggedGraphicsTrap(config, commandIdWidth, numComputeUnits), numComputeUnits))) else None
  graphicsTraps.foreach(arb => io.graphicsKernels.get.trap <> arb.io.out)
  private val graphicsInvalidation = if (enableGraphicsKernels)
    Some(Module(new SharedCuCacheInvalidation(config, numComputeUnits))) else None
  graphicsInvalidation.foreach { broadcast =>
    val request = io.graphicsKernels.get.invalidate
    broadcast.io.request.valid := request.valid && graphicsWorkAllowed
    broadcast.io.request.bits := request.bits
    request.ready := broadcast.io.request.ready && graphicsWorkAllowed
    io.graphicsKernels.get.invalidateDone <> broadcast.io.done
    graphicsCacheDrained := broadcast.io.drained
  }

  for (cu <- 0 until numComputeUnits) {
    val unit = computeUnits(cu)
    shaderPayload.foreach { p=>
      val abort=RegInit(false.B)
      when(dispatcher.io.cuLaunch(cu).fire){abort:=false.B}
        .elsewhen(unit.io.trap.valid && dispatcher.io.graphicsOwner(cu)){abort:=true.B}
      unit.io.payload.get.task:=dispatcher.io.cuPayload.get(cu)
      unit.io.payload.get.cancel:=abort || (unit.io.trap.valid && dispatcher.io.graphicsOwner(cu))
      dispatcher.io.cuPayloadDrained.get(cu):=unit.io.payload.get.drained
      payloadStore.get.io.request(p.owners+cu)<>unit.io.payload.get.request
      unit.io.payload.get.response<>payloadStore.get.io.response(p.owners+cu)
    }
    unit.io.kernel <> dispatcher.io.cuLaunch(cu)
    unit.io.gridSlice.get := dispatcher.io.cuGridSlice(cu)
    if(enableGraphicsKernels) {
      // One physical launch is selected per cycle. Registered graphicsOwner
      // still describes the preceding task on this kernel-acceptance edge.
      unit.io.textureEnabled.get := dispatcher.io.graphicsLaunch.fire && unit.io.kernel.fire
      unit.io.nativeResourcesEnabled.get := dispatcher.io.graphicsLaunch.fire && unit.io.kernel.fire && io.graphicsKernels.get.nativeResources
      unit.io.uniformResourcesEnabled.get := dispatcher.io.graphicsLaunch.fire && unit.io.kernel.fire && io.graphicsKernels.get.uniformResources
    }
    if (shaderExecutionLimits) {
      // graphicsOwner changes after kernel.fire. Snapshot the actual selected
      // producer on admission, including a graphics task drained during reset.
      val graphicsAdmission = dispatcher.io.graphicsLaunch.fire && unit.io.kernel.fire
      unit.io.execution.get.instructionLimit := Mux(graphicsAdmission, io.graphicsInstructionLimit.get, 0.U)
      unit.io.execution.get.interrupt := io.commandResetActive &&
        (dispatcher.io.graphicsOwner(cu) || graphicsAdmission)
      when(graphicsAdmission) { assert(io.graphicsInstructionLimit.get =/= 0.U, "native shaders require a nonzero instruction quota") }
    }
    dispatcher.io.cuCompletion(cu) <> unit.io.completion
    memory.io.cuRequest(cu) <> unit.io.memoryRequest
    unit.io.memoryResponse <> memory.io.cuResponse(cu)
    if (enableGraphicsKernels) {
      val broadcast = graphicsInvalidation.get
      broadcast.io.l2Request(cu) <> l2.io.invalidate(cu)
      l2.io.invalidateDone(cu) <> broadcast.io.l2Done(cu)
      unit.io.l1Invalidate <> broadcast.io.cuRequest(cu)
      broadcast.io.cuDone(cu) <> unit.io.l1InvalidateDone
    } else {
      unit.io.l1Invalidate <> l2.io.invalidate(cu)
      l2.io.invalidateDone(cu) <> unit.io.l1InvalidateDone
    }
    l2.io.atomicRequest(cu) <> unit.io.globalAtomicRequest
    unit.io.globalAtomicResponse <> l2.io.atomicResponse(cu)

    unit.io.invalidateInstructionCache := io.invalidateInstructionCache
    unit.io.instructionSatp := io.instructionSatp
    unit.io.instructionTlbFlush := io.instructionTlbFlush
    unit.io.vectorSatp := io.vectorSatp
    unit.io.vectorTlbFlush := io.vectorTlbFlush
    io.fpu(cu) <> unit.io.fpu
    io.vector(cu) <> unit.io.vector
    io.scalarMemory(cu) <> unit.io.memory
    if (enableGraphicsKernels) {
      val graphics = io.graphicsKernels.get
      val owned = dispatcher.io.graphicsOwner(cu)
      val trap = graphicsTraps.get.io.in(cu)
      trap.valid := unit.io.trap.valid && owned
      trap.bits.taskId := dispatcher.io.taskIds(cu)
      trap.bits.cu := cu.U
      trap.bits.event := unit.io.trap.bits
      io.trap(cu).valid := unit.io.trap.valid && !owned
      io.trap(cu).bits := unit.io.trap.bits
      unit.io.trap.ready := Mux(owned, trap.ready, io.trap(cu).ready)

      val tex = graphics.texture(cu)
      tex.scalar.valid := unit.io.texSample.valid && owned
      tex.scalar.bits := unit.io.texSample.bits
      unit.io.texSample.ready := tex.scalar.ready && owned
      unit.io.texWriteback.valid := tex.scalarWriteback.valid && owned
      unit.io.texWriteback.bits := tex.scalarWriteback.bits
      tex.scalarWriteback.ready := unit.io.texWriteback.ready && owned
      tex.vector.valid := unit.io.vectorTexSample.valid && owned
      tex.vector.bits := unit.io.vectorTexSample.bits
      unit.io.vectorTexSample.ready := tex.vector.ready && owned
      unit.io.vectorTexWriteback.valid := tex.vectorWriteback.valid && owned
      unit.io.vectorTexWriteback.bits := tex.vectorWriteback.bits
      tex.vectorWriteback.ready := unit.io.vectorTexWriteback.ready && owned
    } else {
      io.trap(cu) <> unit.io.trap
    }
    unit.io.simtBranch <> io.simtBranch(cu)

    io.committedWriteback(cu) := unit.io.committedWriteback
    io.committedVectorWriteback(cu) := unit.io.committedVectorWriteback
    io.committedFpuWriteback(cu) := unit.io.committedFpuWriteback
    io.committedFpuFlags(cu) := unit.io.committedFpuFlags
    io.committedFpuIntegerWriteback(cu) :=
      unit.io.committedFpuIntegerWriteback
    io.activeWarps(cu) := unit.io.active
    io.blockedWarps(cu) := unit.io.blocked
    io.barrierWaiting(cu) := unit.io.barrierWaiting
  }

  l2.io.invalidate(numComputeUnits) <> io.graphicsShaderL1Invalidate
  io.graphicsShaderL1InvalidateDone <>
    l2.io.invalidateDone(numComputeUnits)
  l2.io.atomicRequest(numComputeUnits) <>
    io.graphicsShaderAtomicRequest
  io.graphicsShaderAtomicResponse <>
    l2.io.atomicResponse(numComputeUnits)
}
