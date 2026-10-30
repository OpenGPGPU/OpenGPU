package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class StorageTask extends Bundle {
  val enabled = Bool()
  val table = new StorageDescriptorTable
}

class StorageInstructionRequest extends Bundle {
  val bindingIndex = UInt(32.W)
  val offset = UInt(32.W)
  val write = Bool()
  val data = UInt(32.W)
  val byteMask = UInt(4.W)
  val tag = UInt(32.W)
}

/** Captures a storage publication and its Sv32 owner for one CU task. The
  * parent must retain that task's descriptor/data lease until completion and
  * must connect the instruction and commit/fault paths before enabling it.
  * IDs 0/1 are descriptor/data, and 2 belongs to the page walker.
  */
class StorageCuService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool())
    val kernelCompletion = Input(Bool())
    val task = Input(new StorageTask)
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool())
    val in = Flipped(Decoupled(new StorageInstructionRequest))
    val out = Decoupled(new StorageResourceResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val enabled = Output(Bool())
    val busy = Output(Bool())
  })
  private val task = RegInit(0.U.asTypeOf(new StorageTask))
  private val satp = RegInit(0.U(32.W))
  private val resource = Module(new StorageResourceAccess(config))
  private val translation = Module(new TranslatedLineClient(config, 2, preserveCachePolicy = true))
  when(io.kernelStart) {
    assert(!resource.io.busy && !translation.io.walkActive,
      "storage owner changed before the previous task drained")
    task := io.task
    satp := io.satp
  }
  when(io.kernelCompletion) {
    assert(!resource.io.busy && !translation.io.walkActive,
      "kernel completion escaped storage ownership")
  }
  io.enabled := task.enabled
  io.busy := resource.io.busy || translation.io.walkActive
  // A launch updates the captured task on this edge; never admit a request
  // against the previous task while its successor is being installed.
  resource.io.in.valid := io.in.valid && !io.kernelStart
  resource.io.in.bits.table := task.table
  when(!task.enabled) { resource.io.in.bits.table.version := 0.U }
  resource.io.in.bits.bindingIndex := io.in.bits.bindingIndex
  resource.io.in.bits.offset := io.in.bits.offset
  resource.io.in.bits.write := io.in.bits.write
  resource.io.in.bits.data := io.in.bits.data
  resource.io.in.bits.byteMask := io.in.bits.byteMask
  resource.io.in.bits.tag := io.in.bits.tag
  io.in.ready := resource.io.in.ready && !io.kernelStart
  io.out <> resource.io.out
  resource.io.cancel := io.cancel
  translation.io.in <> resource.io.memoryRequest
  resource.io.memoryResponse <> translation.io.out
  translation.io.satp := satp
  translation.io.flush.valid := io.kernelStart || io.flush.valid
  translation.io.flush.bits := Mux(io.kernelStart, 0.U.asTypeOf(io.flush.bits), io.flush.bits)

  private val arbiter = Module(new RRArbiter(new ComputeMemoryRequest(config, 64, 4), 2))
  private val offers = Module(new Queue(new ComputeMemoryRequest(config, 64, 4), 2, pipe = true))
  arbiter.io.in(0).valid := translation.io.memReq.valid
  arbiter.io.in(0).bits := translation.io.memReq.bits
  translation.io.memReq.ready := arbiter.io.in(0).ready
  arbiter.io.in(1).valid := translation.io.pageWalk.valid
  arbiter.io.in(1).bits := translation.io.pageWalk.bits
  arbiter.io.in(1).bits.transactionId := 2.U
  translation.io.pageWalk.ready := arbiter.io.in(1).ready
  offers.io.enq <> arbiter.io.out
  io.memoryRequest <> offers.io.deq
  private val dataReply = io.memoryResponse.bits.transactionId < 2.U
  private val walkReply = io.memoryResponse.bits.transactionId === 2.U
  translation.io.memResp.valid := io.memoryResponse.valid && dataReply
  translation.io.memResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.valid := io.memoryResponse.valid && walkReply
  translation.io.pageWalkResp.bits := io.memoryResponse.bits
  translation.io.pageWalkResp.bits.transactionId := 0.U
  io.memoryResponse.ready := (dataReply && translation.io.memResp.ready) ||
    (walkReply && translation.io.pageWalkResp.ready)
}
