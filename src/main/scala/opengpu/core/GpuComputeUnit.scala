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
  nativeResourcesAvailable: Boolean = true, nativeUniformsAvailable: Boolean = false
) extends Module {
  require(!executionLimits || finishOnTrap,"execution limits require fault retirement")
  val io = IO(new Bundle {
    /** Sampler ownership for the kernel accepted on this edge. */
    val textureEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val nativeResourcesEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val uniformResourcesEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val execution=if(executionLimits)Some(Input(new opengpu.core.frontend.ShaderExecutionControl))else None
    val kernel = Flipped(Decoupled(new KernelLaunch(config)))
    val gridSlice = if (gridSlices) Some(Input(new opengpu.dispatch.KernelGridSlice)) else None
    val completion = Decoupled(new KernelCompletion)

    val memoryRequest = Decoupled(new ComputeMemoryRequest(config))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse()))
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
      taskTextureAvailability=taskTextureAvailability,nativeResourcesAvailable=nativeResourcesAvailable,nativeUniformsAvailable=nativeUniformsAvailable))
  if(taskTextureAvailability) {
    val enabled = RegInit(false.B)
    when(io.kernel.fire) { enabled := io.textureEnabled.get }
    core.io.textureEnabled.get := enabled
    val nativeEnabled = RegInit(false.B)
    when(io.kernel.fire) { nativeEnabled := io.nativeResourcesEnabled.get }
    core.io.nativeResourcesEnabled.get := nativeEnabled
    val uniformsEnabled = RegInit(false.B)
    when(io.kernel.fire) { uniformsEnabled := io.uniformResourcesEnabled.get }
    core.io.uniformResourcesEnabled.get := uniformsEnabled
  }
  shaderPayload.foreach(_=>core.io.payload.get <> io.payload.get)
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
  io.texSample <> system.io.texSample
  core.io.texCommit <> io.texWriteback
  io.vectorTexSample <> core.io.vectorTexSample
  core.io.vectorTexCommit <> io.vectorTexWriteback
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
  io.memoryRequest <> memoryInterconnect.io.memoryRequest
  memoryInterconnect.io.memoryResponse <> io.memoryResponse
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
