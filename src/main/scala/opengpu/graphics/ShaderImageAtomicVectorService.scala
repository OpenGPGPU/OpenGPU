package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.VectorCommitRequest
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, SharedAtomicRequest, SharedAtomicResponse, VectorTlbFlush}

/** Internal composition; the parent retains both descriptor and VM leases,
  * translates regular image traffic, and translates/arbitrates the atomic port.
  * It must route cancellation to both owners and drain them before task reuse.
  */
class ShaderImageAtomicVectorService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool()); val kernelCompletion = Input(Bool())
    val warpStart = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val task = Input(new ShaderImageTask); val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config))); val cancel = Input(Bool())
    val in = Flipped(Decoupled(new ShaderImageExecuteRequest(config)))
    val commit = Decoupled(new VectorCommitRequest(config))
    val status = Output(UInt(3.W)); val faultAddressValid = Output(Bool())
    val faultAddress = Output(UInt(32.W)); val atomicPageFault = Output(Bool())
    val imageMemoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val imageMemoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val descriptorMemoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val descriptorMemoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val atomicSatp = Output(UInt(32.W)); val busy = Output(Bool())
  })
  private val task = RegInit(0.U.asTypeOf(new ShaderImageTask))
  private val image = Module(new ShaderImageExecuteUnit(config, dynamicStage = true, atomicAvailable = true))
  private val atomic = Module(new ShaderImageAtomicCuService(config))
  when(io.kernelStart) {
    assert(!image.io.busy && !atomic.io.busy, "image vector task changed before both owners drained")
    task := io.task
  }
  when(io.kernelCompletion) {
    assert(!image.io.busy && !atomic.io.busy, "image vector completion escaped ownership")
  }
  io.busy := image.io.busy || atomic.io.busy
  image.io.in <> io.in; io.commit <> image.io.commit
  image.io.enabled := task.enabled; image.io.table := task.table; image.io.taskStage.get := task.stage
  image.io.clearParameters := io.kernelStart; image.io.warpStart := io.warpStart
  image.io.invalidate := io.kernelStart || io.flush.valid; image.io.cancel := io.cancel
  io.status := image.io.status; io.faultAddressValid := image.io.faultAddressValid
  io.faultAddress := image.io.faultAddress; io.atomicPageFault := image.io.atomicPageFault.get
  io.imageMemoryRequest <> image.io.memoryRequest; image.io.memoryResponse <> io.imageMemoryResponse
  atomic.io.kernelStart := io.kernelStart; atomic.io.kernelCompletion := io.kernelCompletion
  atomic.io.task := io.task; atomic.io.satp := io.satp; atomic.io.flush := io.flush; atomic.io.cancel := io.cancel
  atomic.io.in <> image.io.atomicRequest.get; image.io.atomicResponse.get <> atomic.io.out
  io.descriptorMemoryRequest <> atomic.io.memoryRequest; atomic.io.memoryResponse <> io.descriptorMemoryResponse
  io.atomicRequest <> atomic.io.atomicRequest; atomic.io.atomicResponse <> io.atomicResponse
  io.atomicSatp := atomic.io.atomicSatp
}
