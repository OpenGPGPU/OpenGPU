package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy, GpuConfig}

/** A task-owned view; its publisher must keep the descriptor and backing
  * allocation immutable until the task and all accepted memory traffic drain.
  */
class StorageWordView extends Bundle {
  val bound = Bool()
  val readable = Bool()
  val writable = Bool()
  val base = UInt(32.W)
  val limit = UInt(33.W)
  val allocationBase = UInt(32.W)
  val allocationLimit = UInt(33.W)
}

class StorageWordRequest extends Bundle {
  val view = new StorageWordView
  val offset = UInt(32.W)
  val write = Bool()
  val data = UInt(32.W)
  val byteMask = UInt(4.W)
  val tag = UInt(32.W)
}

class StorageWordResult extends Bundle {
  val tag = UInt(32.W)
  val data = UInt(32.W)
  val status = UInt(3.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
}

object StorageWordStatus {
  val ok = 0
  val invalidView = 1
  val outOfBounds = 2
  val memoryFault = 3
  val cancelled = 4
}

/** One checked 32-bit load or store, backed by an acknowledged 64-byte line
  * request. A held memory offer is never withdrawn, even on cancellation.
  * Accepted traffic drains before a stable public result can be retired.
  * Descriptor publication, translation and CU instruction routing are owned
  * by the integrating system; this component does not implement them.
  */
class CheckedStorageWordAccess(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new StorageWordRequest))
    val out = Decoupled(new StorageWordResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, offer, waitReply, complete = Value }
  private val state = RegInit(State.idle)
  private val request = Reg(new StorageWordRequest)
  private val result = RegInit(0.U.asTypeOf(new StorageWordResult))
  private val memory = Reg(new ComputeMemoryRequest(config, 64, 1))
  private val shift = Reg(UInt(9.W))
  private val cancelled = RegInit(false.B)
  private val address = request.view.base.pad(33) + request.offset.pad(33)
  private val end = address + 4.U(33.W)
  private val line = Cat(address(31, 6), 0.U(6.W))
  private val addressSpace = (BigInt(1) << 32).U(33.W)
  private val maxSize = (BigInt(128) * 1024 * 1024).U(33.W)
  private val view = request.view
  private val legalView = view.bound && view.base(7, 0) === 0.U &&
    view.limit > view.base && view.limit <= addressSpace &&
    view.limit - view.base <= maxSize && view.allocationBase(5, 0) === 0.U &&
    view.allocationLimit(5, 0) === 0.U && view.allocationLimit <= addressSpace &&
    view.base >= view.allocationBase && view.limit <= view.allocationLimit &&
    Mux(request.write, view.writable, view.readable)
  private val legalAddress = request.offset(1, 0) === 0.U && address < addressSpace &&
    end <= view.limit && line >= view.allocationBase && line.pad(33) + 64.U <= view.allocationLimit

  io.in.ready := state === State.idle && !io.cancel
  io.out.valid := state === State.complete
  io.out.bits := result
  io.busy := state =/= State.idle
  io.memoryRequest.valid := state === State.offer
  io.memoryRequest.bits := memory
  io.memoryResponse.ready := state === State.waitReply && io.memoryResponse.bits.transactionId === 0.U
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        request := io.in.bits
        result := 0.U.asTypeOf(result)
        result.tag := io.in.bits.tag
        cancelled := false.B
        state := State.check
      }
    }
    is(State.check) {
      when(io.cancel || cancelled) {
        result.status := StorageWordStatus.cancelled.U
        state := State.complete
      }.elsewhen(!legalView) {
        result.status := StorageWordStatus.invalidView.U
        state := State.complete
      }.elsewhen(!legalAddress) {
        result.status := StorageWordStatus.outOfBounds.U
        state := State.complete
      }.otherwise {
        val bytes = address(5, 0)
        memory := 0.U.asTypeOf(memory)
        memory.address := line
        memory.sizeLog2 := 6.U
        memory.isWrite := request.write
        memory.cachePolicy := CachePolicy.uncached
        memory.byteMask := Mux(request.write, (request.byteMask << bytes)(63, 0), Fill(64, 1.U(1.W)))
        memory.writeData := (request.data << (bytes << 3))(511, 0)
        shift := bytes << 3
        state := State.offer
      }
    }
    is(State.offer) { when(io.memoryRequest.fire) { state := State.waitReply } }
    is(State.waitReply) {
      when(io.memoryResponse.fire) {
        when(io.cancel || cancelled) { result.status := StorageWordStatus.cancelled.U }
          .elsewhen(io.memoryResponse.bits.fault) {
            result.status := StorageWordStatus.memoryFault.U
            result.faultAddressValid := true.B
            result.faultAddress := memory.address
          }.otherwise {
            result.status := StorageWordStatus.ok.U
            when(!request.write) { result.data := (io.memoryResponse.bits.readData >> shift)(31, 0) }
          }
        state := State.complete
      }
    }
    is(State.complete) { when(io.out.fire) { state := State.idle } }
  }
}
