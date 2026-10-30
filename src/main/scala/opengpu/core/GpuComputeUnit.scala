package opengpu.core

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{FpuFlags, VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.issue.{ScalarIssuedInstruction, VectorIssuedInstruction}
import opengpu.core.backend.register.{FpuRegisterWrite, ScalarRegisterWrite, VectorRegisterWrite}
import opengpu.core.execute.control.{SimtBranchRequest, SimtPath}
import opengpu.core.frontend.{InstructionFetchRequest, InstructionFetchResponse}
import opengpu.core.frontend.decode.FpuDecodeResponse
import opengpu.core.memory._
import opengpu.core.system.{WarpSystemControl, WorkgroupBarrierController}
import opengpu.core.trap.CoreTrapEvent
import opengpu.dispatch.{KernelCompletion, KernelLaunch, SingleCuKernelController}
import opengpu.core.backend.writeback.ScalarCommitRequest

/** One usable compute unit: kernel dispatch, context initialization, core
  * execution, and instruction-driven warp/kernel completion.
  */
class GpuComputeUnit(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false,
  instructionCacheSets: Int = 64,
  instructionCacheWays: Int = 2,
  instructionCacheMissEntries: Int = 4,
  vectorCacheSets: Int = 64,
  vectorCacheWays: Int = 2,
  finishOnTrap: Boolean = false,
  shaderPayload: Option[opengpu.graphics.ShaderPayloadConfig] = None,
  executionLimits: Boolean = false,
  gridSlices: Boolean = false,
  textureAvailable: Boolean = true,
  taskTextureAvailability: Boolean = false,
  // Native adapters have no external scalar-FP completion service.
  nativeExecutionProfile: Boolean = false,
  nativeResourcesAvailable: Boolean = true, nativeUniformsAvailable: Boolean = false, imageAvailable: Boolean = false, storageAvailable: Boolean = false, computeUniformAvailable: Boolean = false, computeTextureAvailable: Boolean = false
) extends Module {
  require(!executionLimits || finishOnTrap,"execution limits require fault retirement")
  require(!computeUniformAvailable || taskTextureAvailability, "compute uniform execution requires task ownership")
  require(!computeTextureAvailable || taskTextureAvailability, "compute texture execution requires task ownership")
  val io = IO(new Bundle {
    /** Sampler ownership for the kernel accepted on this edge. */
    val textureEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val nativeResourcesEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val uniformResourcesEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val images = if(imageAvailable) Some(Input(new opengpu.graphics.ShaderImageTask)) else None
    val imageCancel = if(imageAvailable) Some(Input(Bool())) else None
    val computeUniform = if(computeUniformAvailable) Some(Input(new ComputeUniformTask)) else None
    val computeUniformCancel = if(computeUniformAvailable) Some(Input(Bool())) else None
    val computeUniformLease = if(computeUniformAvailable) Some(Output(Bool())) else None
    val computeTexture = if(computeTextureAvailable) Some(Input(new ComputeTextureTask)) else None
    val computeTextureCancel = if(computeTextureAvailable) Some(Input(Bool())) else None
    val computeTextureLease = if(computeTextureAvailable) Some(Output(Bool())) else None
    val storage = if(storageAvailable) Some(Input(new StorageTask)) else None
    val storageCancel = if(storageAvailable) Some(Input(Bool())) else None
    val storageLease = if(storageAvailable) Some(Output(Bool())) else None
    /** Parent must retain image descriptor publication and VM/coherence ownership while asserted. */
    val imageLease = if(imageAvailable) Some(Output(Bool())) else None
    val execution=if(executionLimits)Some(Input(new opengpu.core.frontend.ShaderExecutionControl))else None
    val kernel = Flipped(Decoupled(new KernelLaunch(config)))
    val gridSlice = if (gridSlices) Some(Input(new opengpu.dispatch.KernelGridSlice)) else None
    val completion = Decoupled(new KernelCompletion)

    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, if(storageAvailable || computeUniformAvailable || computeTextureAvailable) 16 else if(imageAvailable) 8 else 4))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, if(storageAvailable || computeUniformAvailable || computeTextureAvailable) 16 else if(imageAvailable) 8 else 4)))
    val invalidateInstructionCache = Input(Bool())
    val instructionSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))
    val vectorSatp = Input(UInt(32.W))
    val payload=shaderPayload.map(p=>new ShaderPayloadAccessPort(config,p))
    val vectorTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))

    val fpu = Decoupled(new FpuDecodeResponse(config))
    val vector = Decoupled(new VectorIssuedInstruction(config))
    val memory = Decoupled(new ScalarIssuedInstruction(config))
    val trap = Decoupled(new CoreTrapEvent(config))
    val simtBranch = Flipped(Decoupled(new SimtBranchRequest(config)))

    val committedWriteback = Valid(new ScalarRegisterWrite(config))
    val committedVectorWriteback = Valid(new VectorRegisterWrite(config))
    val committedFpuWriteback = Valid(new FpuRegisterWrite(config))
    val committedFpuFlags = Valid(new FpuFlags(config))
    val committedFpuIntegerWriteback = Valid(new ScalarRegisterWrite(config))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val barrierWaiting = Output(UInt(config.warps.W))
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    /** tex.sample execute-side handoff + writeback (graphics TexSampleUnit). */
    val texSample = Decoupled(new ScalarIssuedInstruction(config))
    val texWriteback = Flipped(Decoupled(new ScalarCommitRequest(config)))
    /** Per-lane vtex.sample execute-side handoff + vector writeback. */
    val vectorTexSample = Decoupled(new VectorTextureRequest(config))
    val vectorTexWriteback =
      Flipped(Decoupled(new VectorCommitRequest(config)))
  })

  private val controller = Module(new SingleCuKernelController(config, gridSlices))
  private val core = Module(
    new GpuCore(
      config,
      useBlackBoxes,
      enableFpuBackend,
      vectorCacheSets,
      vectorCacheWays,shaderPayload,nativeExecutionProfile=executionLimits || nativeExecutionProfile,textureAvailable=textureAvailable,
      taskTextureAvailability=taskTextureAvailability,nativeResourcesAvailable=nativeResourcesAvailable,nativeUniformsAvailable=nativeUniformsAvailable || computeUniformAvailable,imageAvailable=imageAvailable,storageAvailable=storageAvailable,computeUniformAvailable=computeUniformAvailable,computeTextureAvailable=computeTextureAvailable))
  if(taskTextureAvailability) {
    val enabled = RegInit(false.B)
    when(io.kernel.fire) { enabled := io.textureEnabled.get }
    core.io.textureEnabled.get := enabled
    val nativeEnabled = RegInit(false.B)
    when(io.kernel.fire) { nativeEnabled := io.nativeResourcesEnabled.get }
    core.io.nativeResourcesEnabled.get := nativeEnabled
    val uniformsEnabled = RegInit(false.B)
    when(io.kernel.fire) { uniformsEnabled := io.uniformResourcesEnabled.get || computeUniformAvailable.B && io.computeUniform.map(_.enabled).getOrElse(false.B) }
    core.io.uniformResourcesEnabled.get := uniformsEnabled
  }
  shaderPayload.foreach(_=>core.io.payload.get <> io.payload.get)
  private val image = if(imageAvailable) Some(Module(new opengpu.graphics.ShaderImageCuService(config))) else None
  image.foreach { service =>
    service.io.kernelStart := io.kernel.fire; service.io.task := io.images.get
    service.io.kernelCompletion := io.completion.valid
    service.io.warpStart.valid := core.io.launch.fire
    service.io.warpStart.bits := core.io.launch.bits.warpId
    service.io.satp := io.vectorSatp; service.io.flush := io.vectorTlbFlush
    service.io.cancel := io.imageCancel.get
    core.io.imageEnabled.get := service.io.enabled
    val lease = RegInit(false.B)
    when(io.kernel.fire) { lease := io.images.get.enabled }
    when(io.completion.fire) { lease := false.B }
    io.imageLease.get := lease
  }
  private val storageService = if(storageAvailable) Some(Module(new StorageVectorCuService(config))) else None
  storageService.foreach { service =>
    service.io.kernelStart := io.kernel.fire; service.io.task := io.storage.get
    service.io.kernelCompletion := io.completion.valid
    service.io.warpStart.valid := core.io.launch.fire
    service.io.warpStart.bits := core.io.launch.bits.warpId
    service.io.satp := io.vectorSatp; service.io.flush := io.vectorTlbFlush
    service.io.cancel := io.storageCancel.get
    core.io.storageEnabled.get := service.io.enabled
    val lease = RegInit(false.B)
    when(io.kernel.fire) { lease := io.storage.get.enabled }
    when(io.completion.fire) { lease := false.B }
    io.storageLease.get := lease
  }
  private val computeUniformService = if(computeUniformAvailable) Some(Module(new ComputeUniformCuService(config))) else None
  computeUniformService.foreach { service =>
    service.io.kernelStart := io.kernel.fire; service.io.task := io.computeUniform.get
    service.io.kernelCompletion := io.completion.valid
    service.io.satp := io.vectorSatp; service.io.flush := io.vectorTlbFlush
    service.io.cancel := io.computeUniformCancel.get
    core.io.computeUniformEnabled.get := service.io.enabled
    val lease = RegInit(false.B)
    when(io.kernel.fire) { lease := io.computeUniform.get.enabled }
    when(io.completion.fire) { lease := false.B }
    io.computeUniformLease.get := lease
  }
  private val computeTextureService = if(computeTextureAvailable) Some(Module(new ComputeTextureCuService(config))) else None
  computeTextureService.foreach { service =>
    service.io.kernelStart := io.kernel.fire; service.io.task := io.computeTexture.get
    service.io.kernelCompletion := io.completion.valid
    service.io.satp := io.vectorSatp; service.io.flush := io.vectorTlbFlush
    service.io.cancel := io.computeTextureCancel.get
    core.io.computeTextureEnabled.get := service.io.enabled
    val lease=RegInit(false.B)
    when(io.kernel.fire) { lease:=io.computeTexture.get.enabled }
    when(io.completion.fire) { lease:=false.B }
    io.computeTextureLease.get:=lease
  }
  private val system = Module(new WarpSystemControl(config))
  private val barrier = Module(new WorkgroupBarrierController(config))
  private val instructionCache = Module(
    new InstructionCache(
      config,
      instructionCacheSets,
      instructionCacheWays,
      lineBytes = 64,
      missEntries = instructionCacheMissEntries))
  private val instructionTlb = Module(new InstructionTlb(config))
  private val instructionPageTableWalker = Module(new Sv32PageTableWalker(config))
  private val memoryInterconnect = Module(new ComputeUnitMemoryInterconnect(config))

  controller.io.kernel <> io.kernel
  if (gridSlices) { controller.io.gridSlice.get := io.gridSlice.get }
  io.completion <> controller.io.completion
  controller.io.activeWarps := core.io.active
  core.io.scalarInitialize <> controller.io.scalarInitialize
  core.io.vectorInitialize <> controller.io.vectorInitialize
  core.io.launch <> controller.io.launch

  system.io.in <> core.io.system
  core.io.shaderCsrWrite := system.io.vxrmWrite
  if(computeUniformAvailable || computeTextureAvailable) {
    val instruction=system.io.texSample.bits.decode.instruction
    val uniformSelected=computeUniformService.map(s=>s.io.enabled && opengpu.graphics.ShaderUniformOpcode.scalar(instruction)).getOrElse(false.B)
    val textureSelected=computeTextureService.map(s=>s.io.enabled && (opengpu.graphics.ShaderTextureFetchOpcode.scalar(instruction) || opengpu.graphics.ShaderTextureSampleOpcode.scalar(instruction))).getOrElse(false.B)
    computeUniformService.foreach { service => service.io.in.valid:=system.io.texSample.valid && uniformSelected; service.io.in.bits:=system.io.texSample.bits }
    computeTextureService.foreach { service => service.io.in.valid:=system.io.texSample.valid && textureSelected; service.io.in.bits:=system.io.texSample.bits }
    io.texSample.valid := system.io.texSample.valid && !uniformSelected && !textureSelected
    io.texSample.bits := system.io.texSample.bits
    system.io.texSample.ready := Mux(uniformSelected,computeUniformService.map(_.io.in.ready).getOrElse(false.B),
      Mux(textureSelected,computeTextureService.map(_.io.in.ready).getOrElse(false.B),io.texSample.ready))
    val commits = Module(new RRArbiter(new opengpu.core.backend.writeback.ScalarCommitRequest(config), 1+(if(computeUniformAvailable)1 else 0)+(if(computeTextureAvailable)1 else 0)))
    commits.io.in(0) <> io.texWriteback
    if(computeUniformAvailable) commits.io.in(1) <> computeUniformService.get.io.commit
    if(computeTextureAvailable) commits.io.in(1+(if(computeUniformAvailable)1 else 0)) <> computeTextureService.get.io.commit
    core.io.texCommit <> commits.io.out
    if(computeTextureAvailable || computeUniformAvailable) {
      core.io.texFaultAddress.get := 0.U
      if(computeUniformAvailable) when(commits.io.chosen === 1.U) {
        core.io.texFaultAddress.get := computeUniformService.get.io.faultAddress
      }
      if(computeTextureAvailable) when(commits.io.chosen === (1+(if(computeUniformAvailable)1 else 0)).U) {
        core.io.texFaultAddress.get := computeTextureService.get.io.faultAddress
      }
    }
  } else {
    io.texSample <> system.io.texSample
    core.io.texCommit <> io.texWriteback
  }
  if(imageAvailable || storageAvailable || computeUniformAvailable || computeTextureAvailable) {
    val instruction = core.io.vectorTexSample.bits.issued.decode.instruction
    val imageInstruction = if(imageAvailable) opengpu.graphics.ShaderImageOpcode.legal(instruction) else false.B
    val storageInstruction = if(storageAvailable) StorageVectorOpcode.legal(instruction) else false.B
    val uniformInstruction = if(computeUniformAvailable) computeUniformService.get.io.enabled && opengpu.graphics.ShaderUniformOpcode.vector(instruction) else false.B
    val textureInstruction = if(computeTextureAvailable) computeTextureService.get.io.enabled && (opengpu.graphics.ShaderTextureFetchOpcode.vector(instruction) || opengpu.graphics.ShaderTextureSampleOpcode.vector(instruction)) else false.B
    image.foreach { service =>
      service.io.in.valid := core.io.vectorTexSample.valid && imageInstruction
      service.io.in.bits.issued := core.io.vectorTexSample.bits.issued
      service.io.in.bits.executionMask := core.io.vectorTexSample.bits.executionMask
      service.io.in.bits.sideEffectMask := core.io.imageSideEffectMask.get
    }
    storageService.foreach { service =>
      service.io.in.valid := core.io.vectorTexSample.valid && storageInstruction
      service.io.in.bits.issued := core.io.vectorTexSample.bits.issued
      service.io.in.bits.executionMask := core.io.vectorTexSample.bits.executionMask
      service.io.in.bits.sideEffectMask := core.io.imageSideEffectMask.get
    }
    computeUniformService.foreach { service =>
      service.io.vectorIn.valid := core.io.vectorTexSample.valid && uniformInstruction
      service.io.vectorIn.bits := core.io.vectorTexSample.bits
    }
    computeTextureService.foreach { service =>
      service.io.vectorIn.valid:=core.io.vectorTexSample.valid && textureInstruction
      service.io.vectorIn.bits:=core.io.vectorTexSample.bits
    }
    io.vectorTexSample.valid := core.io.vectorTexSample.valid && !imageInstruction && !storageInstruction && !uniformInstruction && !textureInstruction
    io.vectorTexSample.bits := core.io.vectorTexSample.bits
    core.io.vectorTexSample.ready := Mux(imageInstruction, image.map(_.io.in.ready).getOrElse(false.B),
      Mux(storageInstruction, storageService.map(_.io.in.ready).getOrElse(false.B),
        Mux(uniformInstruction, computeUniformService.map(_.io.vectorIn.ready).getOrElse(false.B),
          Mux(textureInstruction,computeTextureService.map(_.io.vectorIn.ready).getOrElse(false.B),io.vectorTexSample.ready))))
    val commits = Module(new RRArbiter(new VectorCommitRequest(config), 1 + (if(imageAvailable) 1 else 0) + (if(storageAvailable) 1 else 0) + (if(computeUniformAvailable) 1 else 0) + (if(computeTextureAvailable) 1 else 0)))
    commits.io.in(0) <> io.vectorTexWriteback
    if(imageAvailable) { commits.io.in(1) <> image.get.io.commit }
    if(storageAvailable) { commits.io.in(1 + (if(imageAvailable) 1 else 0)) <> storageService.get.io.commit }
    val uniformIndex = 1 + (if(imageAvailable) 1 else 0) + (if(storageAvailable) 1 else 0)
    if(computeUniformAvailable) { commits.io.in(uniformIndex) <> computeUniformService.get.io.vectorCommit }
    val textureIndex=uniformIndex+(if(computeUniformAvailable) 1 else 0)
    if(computeTextureAvailable) { commits.io.in(textureIndex) <> computeTextureService.get.io.vectorCommit }
    core.io.vectorTexCommit <> commits.io.out
    core.io.imageFault.get.valid := false.B
    core.io.imageFault.get.bits := 0.U.asTypeOf(core.io.imageFault.get.bits)
    if(imageAvailable) { when(commits.io.chosen === 1.U) {
      core.io.imageFault.get.valid := image.get.io.fault.valid && commits.io.out.valid
      core.io.imageFault.get.bits := image.get.io.fault.bits
    }}
    if(storageAvailable) { when(commits.io.chosen === (1 + (if(imageAvailable) 1 else 0)).U) {
      core.io.imageFault.get.valid := storageService.get.io.fault.valid && commits.io.out.valid
      core.io.imageFault.get.bits := storageService.get.io.fault.bits
    }}
    if(computeUniformAvailable) { when(commits.io.chosen === uniformIndex.U) {
      val service = computeUniformService.get
      core.io.imageFault.get.valid := service.io.vectorCommit.valid && service.io.status =/= 0.U && commits.io.out.valid
      core.io.imageFault.get.bits := 0.U.asTypeOf(core.io.imageFault.get.bits)
      core.io.imageFault.get.bits.warpId := service.io.vectorCommit.bits.writeback.warpId
      core.io.imageFault.get.bits.pc := service.io.vectorCommit.bits.pc
      core.io.imageFault.get.bits.warpActiveMask := service.io.vectorCommit.bits.warpActiveMask
      core.io.imageFault.get.bits.faultMask := service.io.vectorCommit.bits.warpActiveMask
      core.io.imageFault.get.bits.vd := service.io.vectorCommit.bits.writeback.vd
      core.io.imageFault.get.bits.writeVd := true.B
      core.io.imageFault.get.bits.addresses := VecInit(Seq.fill(config.lanes)(service.io.faultAddress))
    }}
    if(computeTextureAvailable) { when(commits.io.chosen === textureIndex.U) {
      val service=computeTextureService.get
      core.io.imageFault.get.valid:=service.io.vectorCommit.valid && service.io.responseFault && commits.io.out.valid
      core.io.imageFault.get.bits:=0.U.asTypeOf(core.io.imageFault.get.bits)
      core.io.imageFault.get.bits.warpId:=service.io.vectorCommit.bits.writeback.warpId
      core.io.imageFault.get.bits.pc:=service.io.vectorCommit.bits.pc
      core.io.imageFault.get.bits.warpActiveMask:=service.io.vectorCommit.bits.warpActiveMask
      core.io.imageFault.get.bits.faultMask:=service.io.vectorCommit.bits.warpActiveMask
      core.io.imageFault.get.bits.vd:=service.io.vectorCommit.bits.writeback.vd
      core.io.imageFault.get.bits.writeVd:=service.io.vectorFaultWritesVd
      core.io.imageFault.get.bits.addresses:=VecInit(Seq.fill(config.lanes)(service.io.faultAddress))
    }}
  } else {
    io.vectorTexSample <> core.io.vectorTexSample
    core.io.vectorTexCommit <> io.vectorTexWriteback
  }
  core.io.restore <> system.io.restore
  system.io.restoreEmpty := core.io.restoreEmpty
  barrier.io.arrive <> system.io.barrier
  barrier.io.residentWarps := controller.io.residentWarps
  barrier.io.dispatchComplete := controller.io.workgroupDispatchComplete
  barrier.io.memoryIdle := core.io.sharedMemoryIdle
  private val resumeArbiter = Module(new RRArbiter(new SimtPath(config), 2))
  resumeArbiter.io.in(0) <> system.io.resume
  resumeArbiter.io.in(1) <> barrier.io.release
  core.io.faultResume <> resumeArbiter.io.out
  private val warpFinish=Wire(Valid(UInt(config.warpIdWidth.W)))
  warpFinish:=system.io.finish
  core.io.finish:=warpFinish;controller.io.finish:=warpFinish

  private val fetchRequest=Wire(Decoupled(new InstructionFetchRequest(config)))
  private val fetchResponse=Wire(Decoupled(new InstructionFetchResponse(config)))
  if(executionLimits){
    val guard=Module(new opengpu.core.frontend.WarpInstructionGuard(config))
    val kernelLimit=RegEnable(io.execution.get.instructionLimit,0.U,io.kernel.fire)
    guard.io.request<>core.io.fetchRequest;core.io.fetchResponse<>guard.io.response
    fetchRequest<>guard.io.physicalRequest;guard.io.physicalResponse<>fetchResponse
    guard.io.launch.valid:=core.io.launch.fire;guard.io.launch.bits.warpId:=core.io.launch.bits.warpId
    guard.io.launch.bits.instructions:=kernelLimit;guard.io.finish:=warpFinish
    guard.io.kernelStart:=io.kernel.fire;guard.io.interrupt:=io.execution.get.interrupt
  }else{fetchRequest<>core.io.fetchRequest;core.io.fetchResponse<>fetchResponse}

  private val instructionTranslationEnabled = io.instructionSatp(31)
  instructionTlb.io.in.valid :=
    fetchRequest.valid && instructionTranslationEnabled
  instructionTlb.io.in.bits := fetchRequest.bits
  fetchRequest.ready := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.in.ready,
    instructionCache.io.fetch.ready
  )
  instructionCache.io.fetch.valid := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.physicalRequest.valid,
    fetchRequest.valid
  )
  instructionCache.io.fetch.bits := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.physicalRequest.bits,
    fetchRequest.bits
  )
  instructionTlb.io.physicalRequest.ready :=
    instructionTranslationEnabled && instructionCache.io.fetch.ready
  instructionTlb.io.physicalResponse.valid :=
    instructionTranslationEnabled && instructionCache.io.response.valid
  instructionTlb.io.physicalResponse.bits := instructionCache.io.response.bits
  instructionCache.io.response.ready := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.physicalResponse.ready,
    fetchResponse.ready
  )
  fetchResponse.valid := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.out.valid,
    instructionCache.io.response.valid
  )
  fetchResponse.bits := Mux(
    instructionTranslationEnabled,
    instructionTlb.io.out.bits,
    instructionCache.io.response.bits
  )
  instructionTlb.io.out.ready :=
    instructionTranslationEnabled && fetchResponse.ready
  memoryInterconnect.io.instructionRequest <> instructionCache.io.lowerRequest
  instructionCache.io.lowerResponse <> memoryInterconnect.io.instructionResponse
  instructionCache.io.invalidate := io.invalidateInstructionCache
  instructionTlb.io.translationEnabled := instructionTranslationEnabled
  instructionTlb.io.asid := io.instructionSatp(30, 22)
  instructionTlb.io.flush := io.instructionTlbFlush
  instructionPageTableWalker.io.rootPpn := io.instructionSatp(19, 0)
  instructionPageTableWalker.io.request <> instructionTlb.io.pageWalkRequest
  instructionTlb.io.pageWalkResponse <> instructionPageTableWalker.io.response
  memoryInterconnect.io.instructionPageTableRequest <>
    instructionPageTableWalker.io.memoryRequest
  instructionPageTableWalker.io.memoryResponse <>
    memoryInterconnect.io.instructionPageTableResponse
  memoryInterconnect.io.dataRequest <> core.io.vectorMemoryRequest
  core.io.vectorMemoryResponse <> memoryInterconnect.io.dataResponse
  core.io.vectorSatp := io.vectorSatp
  core.io.vectorTlbFlush := io.vectorTlbFlush
  memoryInterconnect.io.dataPageTableRequest <> core.io.vectorPageTableRequest
  core.io.vectorPageTableResponse <> memoryInterconnect.io.dataPageTableResponse
  if(storageAvailable || computeUniformAvailable || computeTextureAvailable) {
    val baseRequest = Wire(Decoupled(new ComputeMemoryRequest(config, 64, 8)))
    val baseResponse = Wire(Flipped(Decoupled(new ComputeMemoryResponse(64, 8))))
    if(imageAvailable) {
      val service = image.get
      service.io.coreRequest <> memoryInterconnect.io.memoryRequest
      memoryInterconnect.io.memoryResponse <> service.io.coreResponse
      baseRequest <> service.io.memoryRequest
      service.io.memoryResponse <> baseResponse
    } else {
      baseRequest <> memoryInterconnect.io.memoryRequest
      memoryInterconnect.io.memoryResponse <> baseResponse
    }
    val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, 16), 1 + (if(storageAvailable) 1 else 0) + (if(computeUniformAvailable) 1 else 0) + (if(computeTextureAvailable) 1 else 0)))
    val offers = Module(new Queue(new ComputeMemoryRequest(config, 64, 16), 2, pipe = true))
    arbiter.io.in(0).valid := baseRequest.valid; arbiter.io.in(0).bits := baseRequest.bits
    baseRequest.ready := arbiter.io.in(0).ready
    if(storageAvailable) {
      arbiter.io.in(1).valid := storageService.get.io.memoryRequest.valid
      arbiter.io.in(1).bits := storageService.get.io.memoryRequest.bits
      arbiter.io.in(1).bits.transactionId := storageService.get.io.memoryRequest.bits.transactionId.pad(4) + 8.U
      storageService.get.io.memoryRequest.ready := arbiter.io.in(1).ready
    }
    if(computeUniformAvailable) {
      val port = arbiter.io.in(1 + (if(storageAvailable) 1 else 0))
      port.valid := computeUniformService.get.io.memoryRequest.valid
      port.bits := computeUniformService.get.io.memoryRequest.bits
      port.bits.transactionId := computeUniformService.get.io.memoryRequest.bits.transactionId.pad(4) + 11.U
      computeUniformService.get.io.memoryRequest.ready := port.ready
    }
    if(computeTextureAvailable) {
      val port=arbiter.io.in(1+(if(storageAvailable)1 else 0)+(if(computeUniformAvailable)1 else 0))
      port.valid:=computeTextureService.get.io.memoryRequest.valid
      port.bits:=computeTextureService.get.io.memoryRequest.bits
      port.bits.transactionId:=computeTextureService.get.io.memoryRequest.bits.transactionId.pad(4)+13.U
      computeTextureService.get.io.memoryRequest.ready:=port.ready
    }
    offers.io.enq <> arbiter.io.out; io.memoryRequest <> offers.io.deq
    val id = io.memoryResponse.bits.transactionId
    val baseReply = id < (if(imageAvailable) 8 else 4).U
    val storageReply = if(storageAvailable) id >= 8.U && id <= 10.U else false.B
    val uniformReply = if(computeUniformAvailable) id >= 11.U && id <= 12.U else false.B
    val textureReply = if(computeTextureAvailable) id >= 13.U else false.B
    baseResponse.valid := io.memoryResponse.valid && baseReply; baseResponse.bits := io.memoryResponse.bits
    storageService.foreach { service =>
      service.io.memoryResponse.valid := io.memoryResponse.valid && storageReply
      service.io.memoryResponse.bits := io.memoryResponse.bits
      service.io.memoryResponse.bits.transactionId := id - 8.U
    }
    computeUniformService.foreach { service =>
      service.io.memoryResponse.valid := io.memoryResponse.valid && uniformReply
      service.io.memoryResponse.bits := io.memoryResponse.bits
      service.io.memoryResponse.bits.transactionId := id - 11.U
    }
    computeTextureService.foreach { service =>
      service.io.memoryResponse.valid:=io.memoryResponse.valid && textureReply
      service.io.memoryResponse.bits:=io.memoryResponse.bits
      service.io.memoryResponse.bits.transactionId:=id-13.U
    }
    io.memoryResponse.ready := (baseReply && baseResponse.ready) ||
      (storageReply && storageService.map(_.io.memoryResponse.ready).getOrElse(false.B)) ||
      (uniformReply && computeUniformService.map(_.io.memoryResponse.ready).getOrElse(false.B)) ||
      (textureReply && computeTextureService.map(_.io.memoryResponse.ready).getOrElse(false.B))
    val namespaceGuard = Module(new StorageResponseNamespaceGuard(imageAvailable, computeUniformAvailable, storageAvailable, computeTextureAvailable))
    namespaceGuard.io.valid := io.memoryResponse.valid
    namespaceGuard.io.id := io.memoryResponse.bits.transactionId
  } else if(imageAvailable) {
    val service = image.get
    service.io.coreRequest <> memoryInterconnect.io.memoryRequest
    memoryInterconnect.io.memoryResponse <> service.io.coreResponse
    io.memoryRequest <> service.io.memoryRequest
    service.io.memoryResponse <> io.memoryResponse
  } else {
    io.memoryRequest <> memoryInterconnect.io.memoryRequest
    memoryInterconnect.io.memoryResponse <> io.memoryResponse
  }
  core.io.l1Invalidate <> io.l1Invalidate
  io.l1InvalidateDone <> core.io.l1InvalidateDone
  io.globalAtomicRequest <> core.io.globalAtomicRequest
  core.io.globalAtomicResponse <> io.globalAtomicResponse

  io.fpu <> core.io.fpu
  core.io.fpuInitialize.valid := false.B
  core.io.fpuInitialize.bits := 0.U.asTypeOf(core.io.fpuInitialize.bits)
  io.vector <> core.io.vector
  io.memory <> core.io.memory
  // CSR, ECALL and EBREAK are decoded system instructions, but this GPU does
  // not implement a privileged/CSR execution service.  Report them through
  // the architectural trap path instead of leaving the warp permanently
  // backpressured at an unserviced extension port.
  private val trapArbiter = Module(new RRArbiter(new CoreTrapEvent(config), 2))
  trapArbiter.io.in(0) <> core.io.trap
  trapArbiter.io.in(1).valid := system.io.unsupported.valid
  trapArbiter.io.in(1).bits.warpId :=
    system.io.unsupported.bits.decode.warpId
  trapArbiter.io.in(1).bits.pc := system.io.unsupported.bits.decode.pc
  trapArbiter.io.in(1).bits.activeMask :=
    system.io.unsupported.bits.decode.activeMask
  trapArbiter.io.in(1).bits.cause :=
    Mux(system.io.unsupported.bits.decode.decoded.join,
      opengpu.core.trap.TrapCause.simtStackUnderflow.U,
      opengpu.core.trap.TrapCause.illegalInstruction.U)
  trapArbiter.io.in(1).bits.tval :=
    Mux(system.io.unsupported.bits.decode.decoded.join,
      system.io.unsupported.bits.decode.pc, system.io.unsupported.bits.decode.instruction)
  trapArbiter.io.in(1).bits.laneFaultMask :=
    system.io.unsupported.bits.decode.activeMask
  system.io.unsupported.ready := trapArbiter.io.in(1).ready
  if (finishOnTrap) {
    // Retire the complete arbiter, including unsupported system instructions.
    // Public trap stalls and simultaneous peer cease retain distinct owners.
    val retirement=Module(new opengpu.core.trap.ShaderTrapRetirement(config))
    retirement.io.in<>trapArbiter.io.out;io.trap<>retirement.io.out
    retirement.io.normalFinish:=system.io.finish;retirement.io.kernelStart:=io.kernel.fire
    warpFinish:=retirement.io.finish
    io.completion.bits.success:=controller.io.completion.bits.success && !retirement.io.failed
  }else{io.trap<>trapArbiter.io.out}
  core.io.simtBranch <> io.simtBranch

  io.committedWriteback := core.io.committedWriteback
  io.committedVectorWriteback := core.io.committedVectorWriteback
  io.committedFpuWriteback := core.io.committedFpuWriteback
  io.committedFpuFlags := core.io.committedFpuFlags
  io.committedFpuIntegerWriteback := core.io.committedFpuIntegerWriteback
  io.active := core.io.active
  io.blocked := core.io.blocked
  io.barrierWaiting := barrier.io.waiting
}
