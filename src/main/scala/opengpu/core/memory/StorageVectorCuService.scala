package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.VectorCommitRequest

/** Storage vector instructions with task-captured descriptors and translated
  * lower memory. The enclosing CU must route only storage opcodes here and
  * retain its publication and VM lease through command completion.
  */
class StorageVectorCuService(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernelStart = Input(Bool())
    val kernelCompletion = Input(Bool())
    val warpStart = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val task = Input(new StorageTask)
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val cancel = Input(Bool())
    val in = Flipped(Decoupled(new StorageVectorRequest(config)))
    val commit = Decoupled(new VectorCommitRequest(config))
    val fault = Valid(new VectorMemoryFault(config))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 4))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 4)))
    val enabled = Output(Bool())
    val busy = Output(Bool())
  })
  private val vector = Module(new StorageVectorExecuteUnit(config))
  private val service = Module(new StorageCuService(config))
  service.io.kernelStart := io.kernelStart
  service.io.kernelCompletion := io.kernelCompletion
  service.io.task := io.task
  service.io.satp := io.satp
  service.io.flush := io.flush
  service.io.cancel := io.cancel
  vector.io.in <> io.in
  io.commit <> vector.io.commit
  vector.io.enabled := service.io.enabled
  vector.io.kernelStart := io.kernelStart
  vector.io.warpStart := io.warpStart
  vector.io.cancel := io.cancel
  service.io.in <> vector.io.request
  vector.io.response <> service.io.out
  io.memoryRequest <> service.io.memoryRequest
  service.io.memoryResponse <> io.memoryResponse
  io.enabled := service.io.enabled
  io.busy := vector.io.busy || service.io.busy
  when(io.kernelCompletion) { assert(!vector.io.busy, "kernel completion escaped storage instruction") }
  private val instruction = RegEnable(io.in.bits.issued.decode.instruction, io.in.fire)
  io.fault.valid := vector.io.commit.valid && vector.io.status =/= 0.U
  io.fault.bits := 0.U.asTypeOf(io.fault.bits)
  io.fault.bits.warpId := vector.io.commit.bits.writeback.warpId
  io.fault.bits.pc := vector.io.commit.bits.pc
  io.fault.bits.warpActiveMask := vector.io.commit.bits.warpActiveMask
  io.fault.bits.faultMask := vector.io.commit.bits.warpActiveMask
  io.fault.bits.vd := vector.io.commit.bits.writeback.vd
  io.fault.bits.writeVd := StorageVectorOpcode.load(instruction)
  io.fault.bits.isStore := StorageVectorOpcode.store(instruction)
  io.fault.bits.addresses := VecInit(Seq.fill(config.lanes)(vector.io.faultAddress))
}
