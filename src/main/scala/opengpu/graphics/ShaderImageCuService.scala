package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.VectorCommitRequest
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse, SharedAtomicRequest, SharedAtomicResponse, TranslatedLineClient, VectorMemoryFault, VectorTlbFlush}

class ShaderImageTask extends Bundle {
  val enabled = Bool()
  /** Trusted stage of the accepted task, held for its entire lifetime. */
  val stage = UInt(GraphicsStage.width.W)
  val table = new TextureImageLevelTable
}

/** Task-captured image execution and Sv32 transport beside the ordinary CU.
  * External IDs 0..3 retain the CU's owners; 4/5 carry image descriptor/data
  * traffic and 6 carries the image page walker. No image request enters a
  * private data cache. The enclosing task still owns cross-client coherence.
  */
class ShaderImageCuService(config: GpuConfig = GpuConfig(), atomicAvailable: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool())
    val kernelCompletion = Input(Bool())
    val warpStart = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val task = Input(new ShaderImageTask)
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool())
    val in = Flipped(Decoupled(new ShaderImageExecuteRequest(config)))
    val commit = Decoupled(new VectorCommitRequest(config))
    val fault = Valid(new VectorMemoryFault(config))
    val coreRequest = Flipped(Decoupled(new ComputeMemoryRequest(config)))
    val coreResponse = Decoupled(new ComputeMemoryResponse())
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 8))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 8)))
    val atomicRequest = if (atomicAvailable) Some(Decoupled(new SharedAtomicRequest(config))) else None
    val atomicResponse = if (atomicAvailable) Some(Flipped(Decoupled(new SharedAtomicResponse(config)))) else None
    val atomicSatp = if (atomicAvailable) Some(Output(UInt(32.W))) else None
    val enabled = Output(Bool())
    val busy = Output(Bool())
  })
  private val task = RegInit(0.U.asTypeOf(new ShaderImageTask))
  private val satp = RegInit(0.U(32.W))
  private val image = Module(new ShaderImageExecuteUnit(config, dynamicStage = true, atomicAvailable = atomicAvailable))
  private val atomic = if (atomicAvailable) Some(Module(new ShaderImageAtomicCuService(config))) else None
  private val atomicSlotBusy = if (atomicAvailable) Some(RegInit(false.B)) else None
  private val atomicSlotId = if (atomicAvailable) Some(Reg(UInt(1.W))) else None
  private val translation = Module(new TranslatedLineClient(config, 2, preserveCachePolicy = true))
  when(io.kernelStart) {
    assert(!image.io.busy && !atomic.map(_.io.busy).getOrElse(false.B), "image task changed before its instruction retired")
    task := io.task; satp := io.satp
  }
  io.enabled := task.enabled; io.busy := image.io.busy || atomic.map(_.io.busy).getOrElse(false.B)
  // Keep this invariant inside the service. A bound assertion on the CU
  // must not reach through a Verilator hierarchical-block boundary.
  when(io.kernelCompletion) {
    assert(!image.io.busy && !atomic.map(_.io.busy).getOrElse(false.B), "kernel completion escaped image instruction ownership")
  }
  image.io.in <> io.in; io.commit <> image.io.commit
  image.io.table := task.table; image.io.enabled := task.enabled
  image.io.taskStage.get := task.stage
  image.io.clearParameters := io.kernelStart
  image.io.warpStart := io.warpStart
  image.io.invalidate := io.kernelStart || io.flush.valid
  image.io.cancel := io.cancel
  atomic.foreach { service =>
    service.io.kernelStart := io.kernelStart; service.io.kernelCompletion := io.kernelCompletion
    service.io.task := io.task; service.io.satp := io.satp; service.io.flush := io.flush; service.io.cancel := io.cancel
    service.io.in <> image.io.atomicRequest.get; image.io.atomicResponse.get <> service.io.out
    io.atomicRequest.get <> service.io.atomicRequest; service.io.atomicResponse <> io.atomicResponse.get
    io.atomicSatp.get := service.io.atomicSatp
  }
  translation.io.in <> image.io.memoryRequest
  image.io.memoryResponse <> translation.io.out
  translation.io.satp := satp
  translation.io.flush.valid := io.kernelStart || io.flush.valid
  translation.io.flush.bits := Mux(io.kernelStart, 0.U.asTypeOf(io.flush.bits), io.flush.bits)

  private val instruction = RegEnable(io.in.bits.issued.decode.instruction, io.in.fire)
  io.fault.valid := image.io.commit.valid && image.io.status =/= 0.U
  io.fault.bits := 0.U.asTypeOf(io.fault.bits)
  io.fault.bits.warpId := image.io.commit.bits.writeback.warpId
  io.fault.bits.pc := image.io.commit.bits.pc
  io.fault.bits.warpActiveMask := image.io.commit.bits.warpActiveMask
  val isAtomic = if (atomicAvailable) ShaderImageOpcode.atomic(instruction) else false.B
  io.fault.bits.faultMask := Mux(isAtomic, image.io.atomicFaultMask.getOrElse(0.U), image.io.commit.bits.warpActiveMask)
  io.fault.bits.vd := image.io.commit.bits.writeback.vd
  io.fault.bits.writeVd := ShaderImageOpcode.load(instruction) || ShaderImageOpcode.component(instruction) || isAtomic
  io.fault.bits.isStore := ShaderImageOpcode.store(instruction) || isAtomic
  io.fault.bits.pageFault := isAtomic && image.io.atomicPageFault.getOrElse(false.B)
  io.fault.bits.addresses := VecInit(Seq.fill(config.lanes)(image.io.faultAddress))

  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, 8), if (atomicAvailable) 4 else 3))
  private val offers = Module(new Queue(new ComputeMemoryRequest(config, 64, 8), 2, pipe = true))
  arbiter.io.in(0).valid := io.coreRequest.valid
  arbiter.io.in(0).bits := io.coreRequest.bits
  io.coreRequest.ready := arbiter.io.in(0).ready
  arbiter.io.in(1).valid := translation.io.memReq.valid
  arbiter.io.in(1).bits := translation.io.memReq.bits
  arbiter.io.in(1).bits.transactionId := translation.io.memReq.bits.transactionId.pad(3) + 4.U
  translation.io.memReq.ready := arbiter.io.in(1).ready
  arbiter.io.in(2).valid := translation.io.pageWalk.valid
  arbiter.io.in(2).bits := translation.io.pageWalk.bits
  arbiter.io.in(2).bits.transactionId := 6.U
  translation.io.pageWalk.ready := arbiter.io.in(2).ready
  atomic.foreach { service =>
    // One outstanding ID-7 request preserves the atomic service's original
    // descriptor/page-walk ID until the corresponding reply is consumed.
    val port = arbiter.io.in(3)
    port.valid := service.io.memoryRequest.valid && !atomicSlotBusy.get
    port.bits := service.io.memoryRequest.bits; port.bits.transactionId := 7.U
    service.io.memoryRequest.ready := port.ready && !atomicSlotBusy.get
    when(service.io.memoryRequest.fire) {
      atomicSlotBusy.get := true.B; atomicSlotId.get := service.io.memoryRequest.bits.transactionId
    }
  }
  offers.io.enq <> arbiter.io.out; io.memoryRequest <> offers.io.deq

  private val id = io.memoryResponse.bits.transactionId
  private val coreReply = id < 4.U
  private val imageReply = id === 4.U || id === 5.U
  private val walkReply = id === 6.U
  private val atomicReply = if (atomicAvailable) id === 7.U else false.B
  io.coreResponse.valid := io.memoryResponse.valid && coreReply
  io.coreResponse.bits := io.memoryResponse.bits
  translation.io.memResp.valid := io.memoryResponse.valid && imageReply
  translation.io.memResp.bits := io.memoryResponse.bits
  translation.io.memResp.bits.transactionId := id - 4.U
  translation.io.pageWalkResp.valid := io.memoryResponse.valid && walkReply
  translation.io.pageWalkResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.bits.transactionId := 0.U
  atomic.foreach { service =>
    service.io.memoryResponse.valid := io.memoryResponse.valid && atomicReply && atomicSlotBusy.get
    service.io.memoryResponse.bits := io.memoryResponse.bits
    service.io.memoryResponse.bits.transactionId := atomicSlotId.get
    when(service.io.memoryResponse.fire) { atomicSlotBusy.get := false.B }
    when(io.memoryResponse.valid && atomicReply) { assert(atomicSlotBusy.get, "image atomic reply without an owner") }
  }
  io.memoryResponse.ready := MuxCase(false.B, Seq(
    coreReply -> io.coreResponse.ready,
    imageReply -> translation.io.memResp.ready,
    walkReply -> translation.io.pageWalkResp.ready,
    atomicReply -> atomic.map(_.io.memoryResponse.ready).getOrElse(false.B)))
  when(io.memoryResponse.valid) {
    assert(coreReply || imageReply || walkReply || atomicReply, "response outside CU/image/page-walk namespaces")
  }
}
