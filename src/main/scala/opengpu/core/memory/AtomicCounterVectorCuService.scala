package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.VectorCommitRequest

/** Composes per-lane counter execution with a captured task publication.
  * The enclosing CU still owns instruction gating, memory/atomic translation
  * arbitration, protected VM lifetime and public trap/completion retirement.
  */
class AtomicCounterVectorCuService(config:GpuConfig=GpuConfig()) extends Module {
  val io=IO(new Bundle {
    val kernelStart=Input(Bool());val kernelCompletion=Input(Bool());val task=Input(new AtomicCounterTask)
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)));val cancel=Input(Bool())
    val in=Flipped(Decoupled(new AtomicCounterVectorRequest(config)))
    val commit=Decoupled(new VectorCommitRequest(config));val fault=Valid(new VectorMemoryFault(config))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val atomicRequest=Decoupled(new SharedAtomicRequest(config))
    val atomicResponse=Flipped(Decoupled(new SharedAtomicResponse(config)))
    val atomicSatp=Output(UInt(32.W));val enabled=Output(Bool());val busy=Output(Bool())
  })
  private val vector=Module(new AtomicCounterVectorExecuteUnit(config))
  private val service=Module(new AtomicCounterCuService(config))
  service.io.kernelStart:=io.kernelStart;service.io.kernelCompletion:=io.kernelCompletion
  service.io.task:=io.task;service.io.satp:=io.satp;service.io.flush:=io.flush
  // A vector offer is irrevocable; forward cancellation only after the
  // service owns it, then drain its descriptor and atomic transactions.
  service.io.cancel:=service.io.busy && (io.cancel || vector.io.status===AtomicCounterStatus.cancelled.U)
  vector.io.in<>io.in;io.commit<>vector.io.commit
  vector.io.enabled:=service.io.enabled;vector.io.kernelStart:=io.kernelStart;vector.io.cancel:=io.cancel
  service.io.in<>vector.io.request;vector.io.response<>service.io.out
  io.memoryRequest<>service.io.memoryRequest;service.io.memoryResponse<>io.memoryResponse
  io.atomicRequest<>service.io.atomicRequest;service.io.atomicResponse<>io.atomicResponse
  io.atomicSatp:=service.io.atomicSatp;io.enabled:=service.io.enabled;io.busy:=vector.io.busy || service.io.busy
  when(io.kernelCompletion){assert(!vector.io.busy,"kernel completion escaped counter instruction")}
  private val instruction=RegEnable(io.in.bits.issued.decode.instruction,io.in.fire)
  io.fault.valid:=vector.io.commit.valid && vector.io.status=/=0.U
  io.fault.bits:=0.U.asTypeOf(io.fault.bits)
  io.fault.bits.warpId:=vector.io.commit.bits.writeback.warpId;io.fault.bits.pc:=vector.io.commit.bits.pc
  io.fault.bits.warpActiveMask:=vector.io.commit.bits.warpActiveMask;io.fault.bits.faultMask:=vector.io.faultMask
  io.fault.bits.pageFault:=vector.io.pageFault;io.fault.bits.vd:=vector.io.commit.bits.writeback.vd
  io.fault.bits.writeVd:=true.B;io.fault.bits.isStore:= !AtomicCounterVectorOpcode.read(instruction)
  io.fault.bits.addresses:=VecInit(Seq.fill(config.lanes)(vector.io.faultAddress))
}
