package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system.{SharedCuGraphicsPort, SharedCuShaderConfig}

/** Parallel programmable fragment backend with ordered output retirement.
  * Workers retain the existing quad, shader and two-bank inner ABI, while
  * every worker's shader executes on the common system CU pool. No private
  * compute units are instantiated here.
  */
class ParallelKernelFragStage(
  config: GpuConfig,
  gfxConfig: GraphicsConfig,
  sharedShader: SharedCuShaderConfig,
  graphicsScalarFpu: Boolean = true
) extends Module {
  require(sharedShader.parallelFragments)
  private val count = sharedShader.fragmentWorkers
  val io = IO(new KernelFragStageIO(config, gfxConfig, sharedShader.fragmentStagingTransactions))
  val sharedClients = IO(Vec(count, Flipped(new SharedCuGraphicsPort(
    config, sharedShader.taskIdWidth, sharedShader.numComputeUnits))))
  val shaderFault = IO(Output(Bool()))
  private val scheduler = Module(new ParallelFragmentScheduler(config, gfxConfig, count))
  private val staging = Module(new FragmentStagingArbiter(config, count,
    slots = sharedShader.fragmentStagingTransactions,
    localOutstanding = sharedShader.fragmentStagingPerWorker))
  // Each physical sampler has one four-tap group in flight, independent of
  // shader vector length. Overlapping sample groups must raise this bound.
  private val texture = Module(new FragmentTextureArbiter(count,
    TextureUnit.maxOutstandingReads * count))
  private val faults = Wire(Vec(count, Bool()))
  private val executionFaults = Wire(Vec(count, Bool()))
  private val events = Wire(Vec(count, new FragmentPerformanceEvents))
  KernelFragFrontendIO.connect(io, scheduler.io.front)

  for (workerId <- 0 until count) {
    val worker = Module(new KernelFragStage(config, gfxConfig, sharedShader.fragmentStagingPerWorker))
    events(workerId) := worker.io.performance
    executionFaults(workerId) := worker.io.executionFault
    val shader = Module(new KernelShaderStage(config, graphicsScalarFpu, Some(sharedShader)))
    KernelFragFrontendIO.connect(scheduler.io.workers(workerId), worker.io)
    shader.io.shared.get <> sharedClients(workerId)
    shader.io.launch.valid := worker.io.kernelLaunch.valid
    worker.io.kernelLaunch.ready := shader.io.launch.ready
    shader.io.launch.kernelPc := worker.io.kernelLaunch.kernelPc
    shader.io.launch.kernargAddress := worker.io.kernelLaunch.kernargAddress
    shader.io.launch.gridX := worker.io.kernelLaunch.gridX
    shader.io.launch.gridY := worker.io.kernelLaunch.gridY
    shader.io.launch.gridZ := worker.io.kernelLaunch.gridZ
    shader.io.launch.localX := worker.io.kernelLaunch.localX
    shader.io.launch.localY := worker.io.kernelLaunch.localY
    shader.io.launch.localZ := worker.io.kernelLaunch.localZ
    worker.io.kernelCompletion <> shader.io.completion
    worker.io.kernelTrap <> shader.io.trap
    shader.io.texSample <> worker.io.kernelTexSample
    worker.io.kernelTexWriteback <> shader.io.texWriteback
    shader.io.vectorTexSample <> worker.io.kernelVectorTexSample
    worker.io.kernelVectorTexWriteback <> shader.io.vectorTexWriteback
    faults(workerId) := shader.io.trap.fire

    staging.io.clients(workerId).request <> worker.io.wordMemReq
    worker.io.wordMemResp <> staging.io.clients(workerId).response
    texture.io.clients(workerId).request <> worker.io.texMem.req
    worker.io.texMem.resp <> texture.io.clients(workerId).response

    // Shared execution uses the system CU's MMUs, coherent caches and atomic
    // ports. The old standalone cached-memory and resume inputs are idle.
    worker.io.memReq.ready := false.B
    worker.io.memResp.valid := false.B
    worker.io.memResp.bits := 0.U.asTypeOf(worker.io.memResp.bits)
    worker.io.l1Invalidate.valid := false.B
    worker.io.l1Invalidate.bits := 0.U.asTypeOf(worker.io.l1Invalidate.bits)
    worker.io.l1InvalidateDone.ready := false.B
    worker.io.globalAtomicRequest.ready := false.B
    worker.io.globalAtomicResponse.valid := false.B
    worker.io.globalAtomicResponse.bits := 0.U.asTypeOf(worker.io.globalAtomicResponse.bits)
    worker.io.kernelSimtBranch.valid := false.B
    worker.io.kernelSimtBranch.bits := 0.U.asTypeOf(worker.io.kernelSimtBranch.bits)
    shader.io.memoryRequest.ready := false.B
    shader.io.memoryResponse.valid := false.B
    shader.io.memoryResponse.bits := 0.U.asTypeOf(shader.io.memoryResponse.bits)
    shader.io.l1Invalidate.valid := false.B
    shader.io.l1Invalidate.bits := 0.U.asTypeOf(shader.io.l1Invalidate.bits)
    shader.io.l1InvalidateDone.ready := true.B
    shader.io.globalAtomicRequest.ready := false.B
    shader.io.globalAtomicResponse.valid := false.B
    shader.io.globalAtomicResponse.bits := 0.U.asTypeOf(shader.io.globalAtomicResponse.bits)
    shader.io.simtBranch.valid := false.B
    shader.io.simtBranch.bits := 0.U.asTypeOf(shader.io.simtBranch.bits)
    shader.io.instructionSatp := 0.U
    shader.io.instructionTlbFlush.valid := false.B
    shader.io.instructionTlbFlush.bits := 0.U.asTypeOf(shader.io.instructionTlbFlush.bits)
    shader.io.vectorSatp := 0.U
    shader.io.vectorTlbFlush.valid := false.B
    shader.io.vectorTlbFlush.bits := 0.U.asTypeOf(shader.io.vectorTlbFlush.bits)
  }
  io.wordMemReq <> staging.io.memoryRequest
  staging.io.memoryResponse <> io.wordMemResp
  io.texMem.req <> texture.io.memoryRequest
  texture.io.memoryResponse <> io.texMem.resp
  io.drained := scheduler.io.front.drained && staging.io.drained && texture.io.drained
  shaderFault := faults.asUInt.orR
  io.executionFault := executionFaults.asUInt.orR
  for (phase <- 0 until 6)
    io.performance.phaseWorkers(phase) := PopCount(events.map(_.phaseWorkers(phase).orR))
  io.performance.stagingOutstanding := staging.io.outstanding
  io.performance.stagingBlocked := events.map(_.stagingBlocked).reduce(_ || _)
  io.performance.outputBlocked := events.map(_.outputBlocked).reduce(_ || _)

  // Compatibility ports remain quiescent: all real kernel traffic uses the
  // independently tagged sharedClients above, including texture writeback.
  io.memReq.valid := false.B
  io.memReq.bits := 0.U.asTypeOf(io.memReq.bits)
  io.memResp.ready := true.B
  io.l1Invalidate.ready := false.B
  io.l1InvalidateDone.valid := false.B
  io.l1InvalidateDone.bits := 0.U.asTypeOf(io.l1InvalidateDone.bits)
  io.globalAtomicRequest.valid := false.B
  io.globalAtomicRequest.bits := 0.U.asTypeOf(io.globalAtomicRequest.bits)
  io.globalAtomicResponse.ready := true.B
  io.kernelLaunch.valid := false.B
  io.kernelLaunch.kernelPc := 0.U
  io.kernelLaunch.kernargAddress := 0.U
  io.kernelLaunch.gridX := 1.U
  io.kernelLaunch.gridY := 1.U
  io.kernelLaunch.gridZ := 1.U
  io.kernelLaunch.localX := 1.U
  io.kernelLaunch.localY := 1.U
  io.kernelLaunch.localZ := 1.U
  io.kernelCompletion.ready := true.B
  io.kernelTrap.ready := true.B
  io.kernelSimtBranch.ready := false.B
  io.kernelTexSample.ready := false.B
  io.kernelTexWriteback.valid := false.B
  io.kernelTexWriteback.bits := 0.U.asTypeOf(io.kernelTexWriteback.bits)
  io.kernelVectorTexSample.ready := false.B
  io.kernelVectorTexWriteback.valid := false.B
  io.kernelVectorTexWriteback.bits := 0.U.asTypeOf(io.kernelVectorTexWriteback.bits)
}
