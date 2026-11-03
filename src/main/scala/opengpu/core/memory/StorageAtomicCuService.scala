package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class StorageAtomicInstructionRequest(config: GpuConfig) extends Bundle {
  val bindingIndex = UInt(32.W)
  val offset = UInt(32.W)
  val operation = UInt(AtomicMemoryOp.width.W)
  val operand = UInt(32.W)
  val compareValue = UInt(32.W)
  val warpId = UInt(config.warpIdWidth.W)
  val tag = UInt(32.W)
}

/** Captures one task's checked storage table and Sv32 context. IDs 0 and 1
  * belong to descriptor data and its page walk. The enclosing CU must provide
  * an atomic translation/arbitration path, retain the publication through
  * completion, route precise faults and drain accepted traffic on reset.
  */
class StorageAtomicCuService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool())
    val kernelCompletion = Input(Bool())
    val task = Input(new StorageTask)
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool())
    val in = Flipped(Decoupled(new StorageAtomicInstructionRequest(config)))
    val out = Decoupled(new StorageAtomicResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val atomicSatp = Output(UInt(32.W))
    val enabled = Output(Bool())
    val busy = Output(Bool())
  })
  private val task=RegInit(0.U.asTypeOf(new StorageTask))
  private val satp=RegInit(0.U(32.W))
  private val access=Module(new CheckedStorageAtomic(config))
  private val translation=Module(new TranslatedLineClient(config,1,preserveCachePolicy=true))
  private val descriptorPageFault=RegInit(false.B)
  when(io.kernelStart) {
    assert(!access.io.busy && !translation.io.walkActive,"storage atomic owner changed before drain")
    task:=io.task; satp:=io.satp
  }
  when(io.kernelCompletion) {
    assert(!access.io.busy && !translation.io.walkActive,"kernel completion escaped storage atomic ownership")
  }
  io.enabled:=task.enabled; io.atomicSatp:=satp
  io.busy:=access.io.busy || translation.io.walkActive
  access.io.in.valid:=io.in.valid && !io.kernelStart
  access.io.in.bits.table:=task.table
  access.io.in.bits.bindingIndex:=io.in.bits.bindingIndex
  access.io.in.bits.offset:=io.in.bits.offset
  access.io.in.bits.operand:=io.in.bits.operand
  access.io.in.bits.compareValue:=io.in.bits.compareValue
  access.io.in.bits.operation:=io.in.bits.operation
  access.io.in.bits.warpId:=io.in.bits.warpId
  access.io.in.bits.tag:=io.in.bits.tag
  when(!task.enabled) { access.io.in.bits.table.version:=0.U }
  io.in.ready:=access.io.in.ready && !io.kernelStart
  io.out<>access.io.out
  io.out.bits.pageFault:=access.io.out.bits.pageFault ||
    (access.io.out.bits.status===StorageAtomicStatus.memoryFault.U && descriptorPageFault)
  access.io.cancel:=io.cancel
  io.atomicRequest<>access.io.atomicRequest; access.io.atomicResponse<>io.atomicResponse
  translation.io.in<>access.io.descriptorRequest
  access.io.descriptorResponse<>translation.io.out
  when(io.in.fire){descriptorPageFault:=false.B}
  when(translation.io.out.fire && translation.io.out.bits.fault){descriptorPageFault:=translation.io.faultPage}
  translation.io.satp:=satp
  translation.io.flush.valid:=io.kernelStart || io.flush.valid
  translation.io.flush.bits:=Mux(io.kernelStart,0.U.asTypeOf(io.flush.bits),io.flush.bits)
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,2),2))
  private val offers=Module(new Queue(new ComputeMemoryRequest(config,64,2),2,pipe=true))
  arbiter.io.in(0).valid:=translation.io.memReq.valid
  arbiter.io.in(0).bits:=translation.io.memReq.bits
  translation.io.memReq.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=translation.io.pageWalk.valid
  arbiter.io.in(1).bits:=translation.io.pageWalk.bits
  arbiter.io.in(1).bits.transactionId:=1.U
  translation.io.pageWalk.ready:=arbiter.io.in(1).ready
  offers.io.enq<>arbiter.io.out;io.memoryRequest<>offers.io.deq
  val data=io.memoryResponse.bits.transactionId===0.U
  val walk=io.memoryResponse.bits.transactionId===1.U
  translation.io.memResp.valid:=io.memoryResponse.valid && data
  translation.io.memResp.bits:=io.memoryResponse.bits
  translation.io.pageWalkResp.valid:=io.memoryResponse.valid && walk
  translation.io.pageWalkResp.bits:=io.memoryResponse.bits
  translation.io.pageWalkResp.bits.transactionId:=0.U
  io.memoryResponse.ready:=(data && translation.io.memResp.ready) || (walk && translation.io.pageWalkResp.ready)
}
