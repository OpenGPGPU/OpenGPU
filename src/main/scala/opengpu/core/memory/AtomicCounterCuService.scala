package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class AtomicCounterTask extends Bundle {
  val enabled = Bool()
  val table = new StorageDescriptorTable
}

class AtomicCounterInstructionRequest(config: GpuConfig) extends Bundle {
  val counter = UInt(32.W)
  val operation = UInt(2.W)
  val warpId = UInt(config.warpIdWidth.W)
  val tag = UInt(32.W)
}

/** Captures one task's checked counter table and Sv32 context. IDs 0 and 1
  * belong to descriptor data and its page walk. The enclosing CU must provide
  * an atomic translation/arbitration path, retain the publication through
  * completion, route precise faults and drain accepted traffic on reset.
  */
class AtomicCounterCuService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool())
    val kernelCompletion = Input(Bool())
    val task = Input(new AtomicCounterTask)
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool())
    val in = Flipped(Decoupled(new AtomicCounterInstructionRequest(config)))
    val out = Decoupled(new AtomicCounterResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val atomicSatp = Output(UInt(32.W))
    val enabled = Output(Bool())
    val busy = Output(Bool())
  })
  private val task=RegInit(0.U.asTypeOf(new AtomicCounterTask))
  private val satp=RegInit(0.U(32.W))
  private val counter=Module(new CheckedAtomicCounter(config))
  private val translation=Module(new TranslatedLineClient(config,1,preserveCachePolicy=true))
  private val descriptorPageFault=RegInit(false.B)
  when(io.kernelStart) {
    assert(!counter.io.busy && !translation.io.walkActive,"counter owner changed before drain")
    task:=io.task; satp:=io.satp
  }
  when(io.kernelCompletion) {
    assert(!counter.io.busy && !translation.io.walkActive,"kernel completion escaped counter ownership")
  }
  io.enabled:=task.enabled; io.atomicSatp:=satp
  io.busy:=counter.io.busy || translation.io.walkActive
  counter.io.in.valid:=io.in.valid && !io.kernelStart
  counter.io.in.bits.table:=task.table
  counter.io.in.bits.counter:=io.in.bits.counter
  counter.io.in.bits.operation:=io.in.bits.operation
  counter.io.in.bits.warpId:=io.in.bits.warpId
  counter.io.in.bits.tag:=io.in.bits.tag
  when(!task.enabled) { counter.io.in.bits.table.version:=0.U }
  io.in.ready:=counter.io.in.ready && !io.kernelStart
  io.out<>counter.io.out
  io.out.bits.pageFault:=counter.io.out.bits.pageFault ||
    (counter.io.out.bits.status===AtomicCounterStatus.memoryFault.U && descriptorPageFault)
  counter.io.cancel:=io.cancel
  io.atomicRequest<>counter.io.atomicRequest; counter.io.atomicResponse<>io.atomicResponse
  translation.io.in<>counter.io.descriptorRequest
  counter.io.descriptorResponse<>translation.io.out
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
