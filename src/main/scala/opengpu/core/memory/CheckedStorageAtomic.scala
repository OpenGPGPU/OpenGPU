package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}

/** A task-owned storage atomic request. The publisher retains the table,
  * backing allocation and address space until accepted traffic drains. */
class StorageAtomicRequest(config: GpuConfig) extends Bundle {
  val table = new StorageDescriptorTable
  val bindingIndex = UInt(32.W)
  val offset = UInt(32.W)
  val operation = UInt(AtomicMemoryOp.width.W)
  val operand = UInt(32.W)
  val compareValue = UInt(32.W)
  val warpId = UInt(config.warpIdWidth.W)
  val tag = UInt(32.W)
}

object StorageAtomicStatus {
  val ok = 0
  val invalid = 1
  val bounds = 2
  val memoryFault = 3
  val cancelled = 4
  val identity = 5
}

class StorageAtomicResult extends Bundle {
  val tag = UInt(32.W)
  val value = UInt(32.W)
  val status = UInt(3.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
  val pageFault = Bool()
}

/** Resolve one immutable storage view and issue an acknowledged 32-bit
  * atomic through the caller's translation and shared L2 serialization path.
  * Held offers and accepted traffic drain before a stable result is retired. */
class CheckedStorageAtomic(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new StorageAtomicRequest(config)))
    val out = Decoupled(new StorageAtomicResult)
    val descriptorRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val descriptorResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, validate, descriptorOffer, descriptorWait, checkView, atomicOffer, atomicWait, complete = Value }
  private val state = RegInit(State.idle)
  private val request = Reg(new StorageAtomicRequest(config))
  private val view = Reg(new StorageWordView)
  private val result = RegInit(0.U.asTypeOf(new StorageAtomicResult))
  private val cancelled = RegInit(false.B)
  private val descriptor = Module(new StorageDescriptorReader(config))
  descriptor.io.memoryRequest <> io.descriptorRequest
  descriptor.io.memoryResponse <> io.descriptorResponse
  descriptor.io.cancel := io.cancel || cancelled
  descriptor.io.in.valid := state === State.descriptorOffer && !io.cancel && !cancelled
  descriptor.io.in.bits.table := request.table
  descriptor.io.in.bits.bindingIndex := request.bindingIndex
  descriptor.io.in.bits.tag := request.tag
  descriptor.io.out.ready := state === State.descriptorWait
  private val address = view.base.pad(33) + request.offset.pad(33)
  private val end = address + 4.U
  private val addressSpace = (BigInt(1) << 32).U(33.W)
  private val line = Cat(address(31,6), 0.U(6.W))
  private val legalView = view.bound && view.readable && view.writable &&
    view.base(7,0) === 0.U && view.limit > view.base && view.limit <= addressSpace &&
    view.limit - view.base <= (BigInt(128) * 1024 * 1024).U(33.W) &&
    view.allocationBase(5,0) === 0.U && view.allocationLimit(5,0) === 0.U &&
    view.allocationLimit <= addressSpace && view.base >= view.allocationBase && view.limit <= view.allocationLimit
  private val legalAddress = request.offset(1,0) === 0.U && address < addressSpace && end <= view.limit &&
    line >= view.allocationBase && line.pad(33) + 64.U <= view.allocationLimit

  io.in.ready := state === State.idle && !io.cancel
  io.out.valid := state === State.complete
  io.out.bits := result
  io.busy := state =/= State.idle
  io.atomicRequest.valid := state === State.atomicOffer
  io.atomicRequest.bits := 0.U.asTypeOf(io.atomicRequest.bits)
  io.atomicRequest.bits.warpId := request.warpId
  io.atomicRequest.bits.address := address(31,0)
  io.atomicRequest.bits.operation := request.operation
  io.atomicRequest.bits.operand := request.operand
  io.atomicRequest.bits.compareValue := request.compareValue
  io.atomicRequest.bits.cachePolicy := CachePolicy.cached
  io.atomicResponse.ready := state === State.atomicWait && io.atomicResponse.bits.warpId === request.warpId
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        request := io.in.bits; result := 0.U.asTypeOf(result); result.tag := io.in.bits.tag
        cancelled := false.B; state := State.validate
      }
    }
    is(State.validate) {
      when(io.cancel || cancelled) { result.status := StorageAtomicStatus.cancelled.U; state := State.complete }
        .elsewhen(request.bindingIndex >= 4.U || request.operation > AtomicMemoryOp.compareSwap) {
          result.status := StorageAtomicStatus.invalid.U; state := State.complete
        }.otherwise { state := State.descriptorOffer }
    }
    is(State.descriptorOffer) {
      when(io.cancel || cancelled) { result.status := StorageAtomicStatus.cancelled.U; state := State.complete }
        .elsewhen(descriptor.io.in.fire) { state := State.descriptorWait }
    }
    is(State.descriptorWait) {
      when(descriptor.io.out.fire) {
        when(io.cancel || cancelled) { result.status := StorageAtomicStatus.cancelled.U; state := State.complete }
          .elsewhen(descriptor.io.out.bits.status =/= StorageDescriptorStatus.ok.U) {
            result.status := descriptor.io.out.bits.status
            result.faultAddressValid := descriptor.io.out.bits.faultAddressValid
            result.faultAddress := descriptor.io.out.bits.faultAddress
            state := State.complete
          }.otherwise { view := descriptor.io.out.bits.view; state := State.checkView }
      }
    }
    is(State.checkView) {
      when(io.cancel || cancelled) { result.status := StorageAtomicStatus.cancelled.U; state := State.complete }
        .elsewhen(!legalView) { result.status := StorageAtomicStatus.invalid.U; state := State.complete }
        .elsewhen(!legalAddress) { result.status := StorageAtomicStatus.bounds.U; state := State.complete }
        .otherwise { state := State.atomicOffer }
    }
    is(State.atomicOffer) { when(io.atomicRequest.fire) { state := State.atomicWait } }
    is(State.atomicWait) {
      when(io.atomicResponse.fire) {
        when(io.cancel || cancelled) { result.status := StorageAtomicStatus.cancelled.U }
          .elsewhen(io.atomicResponse.bits.fault) {
            result.status := StorageAtomicStatus.memoryFault.U; result.faultAddressValid := true.B
            result.faultAddress := address(31,0); result.pageFault := io.atomicResponse.bits.pageFault
          }.otherwise {
            result.status := StorageAtomicStatus.ok.U
            result.value := io.atomicResponse.bits.oldValue
          }
        state := State.complete
      }
    }
    is(State.complete) { when(io.out.fire) { state := State.idle } }
  }
}
