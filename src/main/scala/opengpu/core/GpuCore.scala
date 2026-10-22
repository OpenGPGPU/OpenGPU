package opengpu.core

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.ScalarBackend
import opengpu.core.backend.{VectorBackend, VectorCommitRequest, VectorTextureRequest}
import opengpu.core.backend.{FpuBackend, FpuFlags}
import opengpu.core.backend.issue.{FpuIssuedInstruction, ScalarIssuedInstruction, VectorIssuedInstruction}
import opengpu.core.backend.register.{FpuRegisterWrite, ScalarRegisterWrite}
import opengpu.core.backend.writeback.ScalarCommitRequest
import opengpu.core.backend.scoreboard.RegisterReservation
import opengpu.core.execute.control.{SimtBranchRequest, SimtPath}
import opengpu.core.frontend._
import opengpu.core.frontend.decode.{FpuDecodeResponse, VectorDecodeResponse}
import opengpu.core.frontend.warp.WarpLaunch
import opengpu.core.trap.{CoreTrapArbiter, CoreTrapEvent}
import opengpu.core.vector.VectorCsrWrite
import opengpu.core.memory.{
  BankedSharedMemory,
  VectorMemorySpaceRouter,
  VectorDataCache,
  VectorLowerMemoryRequest,
  VectorLowerMemoryResponse,
  VectorMemoryCoalescer,
  PageTableMemoryRequest,
  PageTableMemoryResponse,
  Sv32PageTableWalker,
  SharedCacheLinePort,
  ShaderPayloadAccessPort,
  ShaderPayloadMemoryRouter,
  ShaderPayloadAtomicGuard,
  VectorTlb,
  VectorTlbFlush,
  CacheLineInvalidate,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** Current synthesizable GPU core integration boundary.
  *
  * The RV32I scalar loop is connected from fetch through commit and back to
  * warp scheduling. Unimplemented execution families remain explicit ports.
  */
class GpuCore(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false,
  vectorCacheSets: Int = 64,
  vectorCacheWays: Int = 2,
  shaderPayload: Option[opengpu.graphics.ShaderPayloadConfig] = None,
  nativeExecutionProfile: Boolean = false,
  textureAvailable: Boolean = true,
  taskTextureAvailability: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val textureEnabled = if(taskTextureAvailability) Some(Input(Bool())) else None
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val scalarInitialize =
      Flipped(Decoupled(new ScalarRegisterWrite(config)))
    val fetchRequest = Decoupled(new InstructionFetchRequest(config))
    val fetchResponse = Flipped(Decoupled(new InstructionFetchResponse(config)))

    val fpu = Decoupled(new FpuDecodeResponse(config))
    val fpuInitialize = Flipped(Decoupled(new FpuRegisterWrite(config)))
    val committedFpuWriteback = Valid(new FpuRegisterWrite(config))
    val committedFpuFlags = Valid(new FpuFlags(config))
    val committedFpuIntegerWriteback = Valid(new ScalarRegisterWrite(config))
    val vector = Decoupled(new VectorIssuedInstruction(config))
    val vectorInitialize =
      Flipped(Decoupled(new opengpu.core.backend.register.VectorRegisterWrite(config)))
    val committedVectorWriteback =
      Valid(new opengpu.core.backend.register.VectorRegisterWrite(config))
    val vectorMemoryRequest =
      Decoupled(new VectorLowerMemoryRequest(config))
    val vectorMemoryResponse =
      Flipped(Decoupled(new VectorLowerMemoryResponse()))
    val vectorSatp = Input(UInt(32.W))
    val payload=shaderPayload.map(p=>new ShaderPayloadAccessPort(config,p))
    val vectorTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))
    val vectorPageTableRequest =
      Decoupled(new PageTableMemoryRequest(config))
    val vectorPageTableResponse =
      Flipped(Decoupled(new PageTableMemoryResponse))
    val memory = Decoupled(new ScalarIssuedInstruction(config))
    val system = Decoupled(new ScalarIssuedInstruction(config))
    val shaderCsrWrite = Flipped(Valid(new VectorCsrWrite(config)))
    val trap = Decoupled(new CoreTrapEvent(config))
    val faultResume = Flipped(Decoupled(new SimtPath(config)))

    val simtBranch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val restore = Flipped(Decoupled(UInt(config.warpIdWidth.W)))
    val restoreEmpty = Output(Bool())
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val committedWriteback = Valid(new ScalarRegisterWrite(config))
    /** tex.sample commit sideband (see WarpSystemControl / TexSampleUnit). */
    val texCommit = Flipped(Decoupled(new ScalarCommitRequest(config)))
    /** vtex.sample execute-side handoff and vector commit sideband. */
    val vectorTexSample = Decoupled(new VectorTextureRequest(config))
    val vectorTexCommit = Flipped(Decoupled(new VectorCommitRequest(config)))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val sharedMemoryIdle = Output(Bool())
    val l1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val l1InvalidateDone = Decoupled(new CacheLineInvalidate(config))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
  })

  private val frontend = Module(new GpuFrontend(config,
    scalarFpuAvailable = !nativeExecutionProfile || (enableFpuBackend && config.enableFpu),
    instructionSimt = config.enableVector, textureAvailable = textureAvailable,
    taskTextureAvailability = taskTextureAvailability))
  if(taskTextureAvailability) { frontend.io.textureEnabled.get := io.textureEnabled.get }
  private val scalar = Module(new ScalarBackend(config, useBlackBoxes,helperInvocations=true))
  private val vector = Module(new VectorBackend(config, useBlackBoxes,helperInvocations=true,
    instructionSimt=config.enableVector))
  private val scalarVectorRead =
    Module(new opengpu.core.backend.register.ScalarRegisterFile(config))
  private val scalarFpuRead =
    Module(new opengpu.core.backend.register.ScalarRegisterFile(config))
  private val vectorCoalescer = Module(new VectorMemoryCoalescer(config))
  private val vectorMemoryRouter = Module(new VectorMemorySpaceRouter(config))
  private val sharedMemory = Module(new BankedSharedMemory(config))
  private val vectorDataCache = Module(
    new VectorDataCache(config, sets = vectorCacheSets, ways = vectorCacheWays))
  private val vectorTlb = Module(new VectorTlb(config))
  private val vectorPageTableWalker = Module(new Sv32PageTableWalker(config))
  private val trapArbiter = Module(new CoreTrapArbiter(config))
  private val sharedCachePort = Module(new SharedCacheLinePort(config))

  private val invocationPolicies=RegInit(VecInit(Seq.fill(config.warps)(0.U.asTypeOf(new opengpu.core.memory.WarpInvocationPolicy(config)))))
  when(io.launch.fire){
    if(config.warps==1)invocationPolicies(0):=io.launch.bits.invocations
    else invocationPolicies(io.launch.bits.warpId):=io.launch.bits.invocations
  }
  scalar.io.invocations.get:=invocationPolicies;vector.io.invocations.get:=invocationPolicies
  frontend.io.launch <> io.launch
  io.fetchRequest <> frontend.io.fetchRequest
  frontend.io.fetchResponse <> io.fetchResponse
  scalar.io.in <> frontend.io.scalarOut
  scalar.io.texCommit <> io.texCommit
  scalar.io.initialize <> io.scalarInitialize
  private val completionArbiter = Module(new RRArbiter(
    new SimtPath(config), if (enableFpuBackend && config.enableFpu) 4 else 3))
  completionArbiter.io.in(0) <> scalar.io.redirect
  completionArbiter.io.in(1) <> vector.io.redirect
  completionArbiter.io.in(2) <> io.faultResume
  io.fpuInitialize.ready := false.B
  io.committedFpuWriteback.valid := false.B
  io.committedFpuWriteback.bits := 0.U.asTypeOf(io.committedFpuWriteback.bits)
  io.committedFpuFlags.valid := false.B
  io.committedFpuFlags.bits := 0.U.asTypeOf(io.committedFpuFlags.bits)
  io.committedFpuIntegerWriteback.valid := false.B
  io.committedFpuIntegerWriteback.bits :=
    0.U.asTypeOf(io.committedFpuIntegerWriteback.bits)

  if (enableFpuBackend && config.enableFpu) {
    val fpu = Module(new FpuBackend(config,helperInvocations=true))
    fpu.io.invocations.get:=invocationPolicies
    fpu.io.in <> frontend.io.fpuOut
    fpu.io.unimplemented.ready := io.fpu.ready
    io.fpu.valid := fpu.io.unimplemented.valid
    io.fpu.bits := fpu.io.unimplemented.bits.decode
    fpu.io.initialize <> io.fpuInitialize
    io.committedFpuWriteback := fpu.io.committedWriteback
    io.committedFpuFlags.valid :=
      fpu.io.committedFlags.valid || vector.io.committedVectorFlags.valid
    io.committedFpuFlags.bits := Mux(
      fpu.io.committedFlags.valid,
      fpu.io.committedFlags.bits,
      vector.io.committedVectorFlags.bits
    )
    io.committedFpuIntegerWriteback.valid := fpu.io.scalarWriteback.valid
    io.committedFpuIntegerWriteback.bits := fpu.io.scalarWriteback.bits
    fpu.io.flush := false.B
    fpu.io.fvfRead := vector.io.scalarFpRead
    fpu.io.frm := vector.io.frm
    vector.io.scalarFpData := fpu.io.fvfData
    vector.io.scalarFpBusy := fpu.io.fpuBusyByWarp
    fpu.io.externalReserve <> vector.io.fpReserve
    fpu.io.externalWrite <> vector.io.fpWriteback
    vector.io.scalarFlagsWrite.valid := fpu.io.committedFlags.valid
    vector.io.scalarFlagsWrite.bits.warpId :=
      fpu.io.committedFlags.bits.warpId
    vector.io.scalarFlagsWrite.bits.flags :=
      fpu.io.committedFlags.bits.flags
    completionArbiter.io.in(3) <> fpu.io.redirect
    scalarFpuRead.io.read := fpu.io.scalarRead
    fpu.io.scalarRs1Data := scalarFpuRead.io.rs1Data
    sharedCachePort.io.fpuRequest <> fpu.io.memoryRequest
    fpu.io.memoryResponse <> sharedCachePort.io.fpuResponse
    trapArbiter.io.fpuMemory <> fpu.io.memoryFault
    val fpuScalarReserveArbiter = Module(new RRArbiter(
      new RegisterReservation(config), 2))
    fpuScalarReserveArbiter.io.in(0) <> fpu.io.scalarReserve
    fpuScalarReserveArbiter.io.in(1) <> vector.io.scalarReserve
    scalar.io.externalReserve <> fpuScalarReserveArbiter.io.out
    val fpuScalarWritebackArbiter = Module(new RRArbiter(
      new ScalarRegisterWrite(config), 2))
    fpuScalarWritebackArbiter.io.in(0) <> fpu.io.scalarWriteback
    fpuScalarWritebackArbiter.io.in(1) <> vector.io.scalarWriteback
    scalar.io.externalWriteback <> fpuScalarWritebackArbiter.io.out
  } else {
    io.fpu <> frontend.io.fpuOut
    io.committedFpuFlags := vector.io.committedVectorFlags
    scalar.io.externalWriteback <> vector.io.scalarWriteback
    scalar.io.externalReserve <> vector.io.scalarReserve
    vector.io.scalarFpData := 0.U
    vector.io.scalarFpBusy := 0.U.asTypeOf(vector.io.scalarFpBusy)
    vector.io.fpReserve.ready := true.B
    vector.io.fpWriteback.ready := true.B
    vector.io.scalarFlagsWrite.valid := false.B
    vector.io.scalarFlagsWrite.bits.warpId := 0.U
    vector.io.scalarFlagsWrite.bits.flags := 0.U
    scalarFpuRead.io.read := 0.U.asTypeOf(scalarFpuRead.io.read)
    sharedCachePort.io.fpuRequest.valid := false.B
    sharedCachePort.io.fpuRequest.bits :=
      0.U.asTypeOf(sharedCachePort.io.fpuRequest.bits)
    sharedCachePort.io.fpuResponse.ready := true.B
    trapArbiter.io.fpuMemory.valid := false.B
    trapArbiter.io.fpuMemory.bits :=
      0.U.asTypeOf(trapArbiter.io.fpuMemory.bits)
  }
  scalarFpuRead.io.write.valid := scalar.io.appliedWriteback.valid
  scalarFpuRead.io.write.bits := scalar.io.appliedWriteback.bits
  frontend.io.scalarRedirect <> completionArbiter.io.out
  vector.io.in <> frontend.io.vectorOut
  vector.io.shaderCsrWrite := io.shaderCsrWrite
  vector.io.clearWarp.valid := io.launch.fire
  vector.io.clearWarp.bits := io.launch.bits.warpId
  io.vectorTexSample <> vector.io.texSample
  vector.io.texCommit <> io.vectorTexCommit
  io.vector <> vector.io.unimplemented
  vector.io.initialize <> io.vectorInitialize
  io.committedVectorWriteback := vector.io.committedVectorWriteback
  vectorMemoryRouter.io.in <> vector.io.memoryRequest
  vectorCoalescer.io.in <> vectorMemoryRouter.io.globalRequest
  vectorMemoryRouter.io.globalResponse <> vectorCoalescer.io.out
  sharedMemory.io.in <> vectorMemoryRouter.io.localRequest
  vectorMemoryRouter.io.localResponse <> sharedMemory.io.out
  sharedMemory.io.atomicIn <> scalar.io.sharedAtomicRequest
  scalar.io.sharedAtomicResponse <> sharedMemory.io.atomicOut
  vector.io.memoryResponse <> vectorMemoryRouter.io.out
  sharedCachePort.io.vectorRequest <> vectorCoalescer.io.cacheRequest
  vectorCoalescer.io.cacheResponse <> sharedCachePort.io.vectorResponse
  sharedCachePort.io.scalarRequest <> scalar.io.cacheRequest
  scalar.io.cacheResponse <> sharedCachePort.io.scalarResponse
  shaderPayload match {
    case Some(p)=>
      val router=Module(new ShaderPayloadMemoryRouter(config,p))
      val atomics=Module(new ShaderPayloadAtomicGuard(config,p))
      router.io.in <> sharedCachePort.io.sharedRequest
      sharedCachePort.io.sharedResponse <> router.io.out
      vectorTlb.io.in <> router.io.memoryRequest
      router.io.memoryResponse <> vectorTlb.io.out
      router.io.payload.task:=io.payload.get.task
      router.io.payload.cancel:=io.payload.get.cancel
      io.payload.get.request <> router.io.payload.request
      router.io.payload.response <> io.payload.get.response
      atomics.io.task:=io.payload.get.task;atomics.io.cancel:=io.payload.get.cancel
      atomics.io.in <> scalar.io.globalAtomicRequest
      scalar.io.globalAtomicResponse <> atomics.io.out
      io.globalAtomicRequest <> atomics.io.memoryRequest
      atomics.io.memoryResponse <> io.globalAtomicResponse
      io.payload.get.drained:=router.io.payload.drained && atomics.io.drained
    case None=>
      vectorTlb.io.in <> sharedCachePort.io.sharedRequest
      sharedCachePort.io.sharedResponse <> vectorTlb.io.out
      io.globalAtomicRequest <> scalar.io.globalAtomicRequest
      scalar.io.globalAtomicResponse <> io.globalAtomicResponse
  }
  vectorDataCache.io.in <> vectorTlb.io.physicalRequest
  vectorTlb.io.physicalResponse <> vectorDataCache.io.out
  vectorTlb.io.translationEnabled := io.vectorSatp(31)
  vectorTlb.io.asid := io.vectorSatp(30, 22)
  vectorTlb.io.flush := io.vectorTlbFlush
  vectorPageTableWalker.io.rootPpn := io.vectorSatp(19, 0)
  vectorPageTableWalker.io.request <> vectorTlb.io.pageWalkRequest
  vectorTlb.io.pageWalkResponse <> vectorPageTableWalker.io.response
  io.vectorPageTableRequest <> vectorPageTableWalker.io.memoryRequest
  vectorPageTableWalker.io.memoryResponse <> io.vectorPageTableResponse
  io.vectorMemoryRequest <> vectorDataCache.io.lowerRequest
  vectorDataCache.io.lowerResponse <> io.vectorMemoryResponse
  vectorDataCache.io.invalidate <> io.l1Invalidate
  io.l1InvalidateDone <> vectorDataCache.io.invalidateDone
  trapArbiter.io.vector <> vector.io.memoryFault

  scalarVectorRead.io.read := vector.io.scalarRead
  vector.io.scalarRs1Data := scalarVectorRead.io.rs1Data
  vector.io.scalarRs2Data := scalarVectorRead.io.rs2Data
  scalarVectorRead.io.write.valid := scalar.io.appliedWriteback.valid
  scalarVectorRead.io.write.bits := scalar.io.appliedWriteback.bits
  io.memory <> scalar.io.memory
  if(config.enableVector) {
    val sync = frontend.io.sync.get
    val isSync = scalar.io.system.bits.decode.decoded.simtSync
    sync.valid := scalar.io.system.valid && isSync
    sync.bits.warpId := scalar.io.system.bits.decode.warpId
    sync.bits.pc := scalar.io.system.bits.decode.pc
    sync.bits.activeMask := scalar.io.system.bits.decode.activeMask
    sync.bits.joinPc := scalar.io.system.bits.decode.pc + scalar.io.system.bits.decode.decoded.immediate
    io.system.valid := scalar.io.system.valid && !isSync
    io.system.bits := scalar.io.system.bits
    scalar.io.system.ready := Mux(isSync,sync.ready,io.system.ready)
    frontend.io.instructionBranch.get <> vector.io.simtBranch.get
    vector.io.simtClear.get.valid := io.finish.valid || io.launch.fire
    vector.io.simtClear.get.bits := Mux(io.finish.valid,io.finish.bits,io.launch.bits.warpId)
  } else {
    io.system <> scalar.io.system
  }
  trapArbiter.io.scalar <> scalar.io.trap
  trapArbiter.io.scalarMemory <> scalar.io.memoryFault
  // Own the selected fault before public presentation, so a newly arriving
  // peer fault cannot replace an already stalled SIMT event (or vice versa).
  private val allTraps = Module(new RRArbiter(new CoreTrapEvent(config), if(config.enableVector)3 else 2))
  private val trapOffers = Module(new Queue(new CoreTrapEvent(config), 1))
  allTraps.io.in(0) <> trapArbiter.io.out
  allTraps.io.in(1).valid := frontend.io.overflow.valid
  allTraps.io.in(1).bits.warpId := frontend.io.overflow.bits.warpId
  allTraps.io.in(1).bits.pc := frontend.io.overflow.bits.pc
  allTraps.io.in(1).bits.activeMask := frontend.io.overflow.bits.activeMask
  allTraps.io.in(1).bits.cause := opengpu.core.trap.TrapCause.simtStackOverflow.U
  allTraps.io.in(1).bits.tval := frontend.io.overflow.bits.pc
  allTraps.io.in(1).bits.laneFaultMask := frontend.io.overflow.bits.activeMask
  frontend.io.overflow.ready := allTraps.io.in(1).ready
  if(config.enableVector) {
    allTraps.io.in(2) <> frontend.io.fault.get
  }
  trapOffers.io.enq <> allTraps.io.out
  io.trap <> trapOffers.io.deq

  frontend.io.branch <> io.simtBranch
  frontend.io.restore <> io.restore
  io.restoreEmpty := frontend.io.restoreEmpty
  frontend.io.finish <> io.finish
  io.committedWriteback := scalar.io.committedWriteback
  io.active := frontend.io.active
  io.blocked := frontend.io.blocked
  io.sharedMemoryIdle := sharedMemory.io.idle
}
