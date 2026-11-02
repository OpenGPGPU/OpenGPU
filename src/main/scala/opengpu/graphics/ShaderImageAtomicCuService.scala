package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, SharedAtomicRequest, SharedAtomicResponse, TranslatedLineClient, VectorTlbFlush}

class ShaderImageAtomicInstructionRequest(config: GpuConfig) extends Bundle {
  val tag = UInt(32.W); val unitIndex = UInt(32.W)
  val shaderTarget = UInt(3.W); val colorType = UInt(2.W)
  val hasFormatQualifier = Bool(); val shaderFormat = UInt(TextureImageFormat.width.W)
  val x = UInt(32.W); val y = UInt(32.W); val z = UInt(32.W)
  val warpId = UInt(config.warpIdWidth.W); val operation = UInt(3.W)
  val operand = UInt(32.W); val compareValue = UInt(32.W)
}

/** Internal task-captured image atomic service. Descriptor reads use the
  * captured Sv32 root; the parent must translate and arbitrate the virtual
  * atomic port with atomicSatp and retain publication/VM ownership through drain.
  */
class ShaderImageAtomicCuService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool()); val kernelCompletion = Input(Bool())
    val task = Input(new ShaderImageTask); val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool()); val in = Flipped(Decoupled(new ShaderImageAtomicInstructionRequest(config)))
    val out = Decoupled(new ShaderImageAtomicResourceResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val atomicSatp = Output(UInt(32.W)); val enabled = Output(Bool()); val busy = Output(Bool())
  })
  private val task = RegInit(0.U.asTypeOf(new ShaderImageTask))
  private val satp = RegInit(0.U(32.W))
  private val resource = Module(new ShaderImageAtomicResourceAccess(config))
  private val translation = Module(new TranslatedLineClient(config, 1, preserveCachePolicy = true))
  private val descriptorPageFault = RegInit(false.B)
  when(io.kernelStart) {
    assert(!resource.io.busy && !translation.io.walkActive, "image atomic task changed before drain")
    task := io.task; satp := io.satp
  }
  when(io.kernelCompletion) {
    assert(!resource.io.busy && !translation.io.walkActive, "kernel completion escaped image atomic ownership")
  }
  io.enabled := task.enabled; io.atomicSatp := satp
  io.busy := resource.io.busy || translation.io.walkActive
  resource.io.in.valid := io.in.valid && !io.kernelStart
  resource.io.in.bits := 0.U.asTypeOf(resource.io.in.bits)
  resource.io.in.bits.table := task.table
  when(!task.enabled) { resource.io.in.bits.table.version := 0.U }
  resource.io.in.bits.tag := io.in.bits.tag; resource.io.in.bits.unitIndex := io.in.bits.unitIndex
  resource.io.in.bits.stage := task.stage; resource.io.in.bits.shaderTarget := io.in.bits.shaderTarget
  resource.io.in.bits.colorType := io.in.bits.colorType
  resource.io.in.bits.hasFormatQualifier := io.in.bits.hasFormatQualifier; resource.io.in.bits.shaderFormat := io.in.bits.shaderFormat
  resource.io.in.bits.x := io.in.bits.x; resource.io.in.bits.y := io.in.bits.y; resource.io.in.bits.z := io.in.bits.z
  resource.io.in.bits.warpId := io.in.bits.warpId; resource.io.in.bits.operation := io.in.bits.operation
  resource.io.in.bits.operand := io.in.bits.operand; resource.io.in.bits.compareValue := io.in.bits.compareValue
  io.in.ready := resource.io.in.ready && !io.kernelStart
  io.out <> resource.io.out
  io.out.bits.pageFault := resource.io.out.bits.pageFault ||
    (resource.io.out.bits.status === ShaderImageAccessStatus.memory.U && descriptorPageFault)
  resource.io.invalidate := io.kernelStart || io.flush.valid; resource.io.cancel := io.cancel
  io.atomicRequest <> resource.io.atomicRequest; resource.io.atomicResponse <> io.atomicResponse
  translation.io.in <> resource.io.descriptorRequest; resource.io.descriptorResponse <> translation.io.out
  when(io.in.fire) { descriptorPageFault := false.B }
  when(translation.io.out.fire && translation.io.out.bits.fault) { descriptorPageFault := translation.io.faultPage }
  translation.io.satp := satp
  translation.io.flush.valid := io.kernelStart || io.flush.valid
  translation.io.flush.bits := Mux(io.kernelStart, 0.U.asTypeOf(io.flush.bits), io.flush.bits)
  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, 2), 2))
  private val offers = Module(new Queue(new ComputeMemoryRequest(config, 64, 2), 2, pipe = true))
  arbiter.io.in(0).valid := translation.io.memReq.valid; arbiter.io.in(0).bits := translation.io.memReq.bits
  translation.io.memReq.ready := arbiter.io.in(0).ready
  arbiter.io.in(1).valid := translation.io.pageWalk.valid; arbiter.io.in(1).bits := translation.io.pageWalk.bits
  arbiter.io.in(1).bits.transactionId := 1.U; translation.io.pageWalk.ready := arbiter.io.in(1).ready
  offers.io.enq <> arbiter.io.out; io.memoryRequest <> offers.io.deq
  val data = io.memoryResponse.bits.transactionId === 0.U
  val walk = io.memoryResponse.bits.transactionId === 1.U
  translation.io.memResp.valid := io.memoryResponse.valid && data; translation.io.memResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.valid := io.memoryResponse.valid && walk
  translation.io.pageWalkResp.bits := io.memoryResponse.bits; translation.io.pageWalkResp.bits.transactionId := 0.U
  io.memoryResponse.ready := (data && translation.io.memResp.ready) || (walk && translation.io.pageWalkResp.ready)
}
