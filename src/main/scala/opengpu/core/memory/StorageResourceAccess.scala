package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class StorageResourceRequest extends Bundle {
  val table = new StorageDescriptorTable
  val bindingIndex = UInt(32.W)
  val offset = UInt(32.W)
  val write = Bool()
  val data = UInt(32.W)
  val byteMask = UInt(4.W)
  val tag = UInt(32.W)
}

class StorageResourceResult extends Bundle {
  val bindingIndex = UInt(32.W)
  val tag = UInt(32.W)
  val data = UInt(32.W)
  val status = UInt(3.W)
  /** 1 for descriptor, 2 for checked word access. */
  val source = UInt(2.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
}

/** Resolves one immutable GPU-backed binding before issuing a checked word
  * operation. Ownership remains with the selected child through every held
  * offer and accepted response. The command owner must pin the table, views
  * and translation context through public result consumption.
  */
class StorageResourceAccess(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new StorageResourceRequest))
    val out = Decoupled(new StorageResourceResult)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, offerDescriptor, waitDescriptor, offerWord, waitWord, complete = Value }
  private val state = RegInit(State.idle)
  private val request = Reg(new StorageResourceRequest)
  private val view = Reg(new StorageWordView)
  private val result = RegInit(0.U.asTypeOf(new StorageResourceResult))
  private val cancelled = RegInit(false.B)
  private val descriptor = Module(new StorageDescriptorReader(config))
  private val word = Module(new CheckedStorageWordAccess(config))
  private val descriptorOwned = state === State.waitDescriptor
  private val wordOwned = state === State.waitWord
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  io.in.ready := state === State.idle && !io.cancel
  io.out.valid := state === State.complete
  io.out.bits := result
  io.busy := state =/= State.idle
  descriptor.io.in.valid := state === State.offerDescriptor
  descriptor.io.in.bits.table := request.table
  descriptor.io.in.bits.bindingIndex := request.bindingIndex
  descriptor.io.in.bits.tag := request.tag
  descriptor.io.out.ready := descriptorOwned
  descriptor.io.cancel := descriptorOwned && (cancelled || io.cancel)
  word.io.in.valid := state === State.offerWord
  word.io.in.bits.view := view
  word.io.in.bits.offset := request.offset
  word.io.in.bits.write := request.write
  word.io.in.bits.data := request.data
  word.io.in.bits.byteMask := request.byteMask
  word.io.in.bits.tag := request.tag
  word.io.out.ready := wordOwned
  word.io.cancel := wordOwned && (cancelled || io.cancel)

  io.memoryRequest.valid := (descriptorOwned && descriptor.io.memoryRequest.valid) ||
    (wordOwned && word.io.memoryRequest.valid)
  io.memoryRequest.bits := Mux(descriptorOwned, descriptor.io.memoryRequest.bits, word.io.memoryRequest.bits)
  io.memoryRequest.bits.transactionId := Mux(descriptorOwned, 0.U, 1.U)
  descriptor.io.memoryRequest.ready := descriptorOwned && io.memoryRequest.ready
  word.io.memoryRequest.ready := wordOwned && io.memoryRequest.ready
  private val descriptorReply = descriptorOwned && io.memoryResponse.bits.transactionId === 0.U
  private val wordReply = wordOwned && io.memoryResponse.bits.transactionId === 1.U
  descriptor.io.memoryResponse.valid := io.memoryResponse.valid && descriptorReply
  descriptor.io.memoryResponse.bits := io.memoryResponse.bits
  descriptor.io.memoryResponse.bits.transactionId := 0.U
  word.io.memoryResponse.valid := io.memoryResponse.valid && wordReply
  word.io.memoryResponse.bits := io.memoryResponse.bits
  word.io.memoryResponse.bits.transactionId := 0.U
  io.memoryResponse.ready := (descriptorReply && descriptor.io.memoryResponse.ready) ||
    (wordReply && word.io.memoryResponse.ready)

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        request := io.in.bits
        result := 0.U.asTypeOf(result)
        result.tag := io.in.bits.tag
        result.bindingIndex := io.in.bits.bindingIndex
        cancelled := false.B
        state := State.offerDescriptor
      }
    }
    is(State.offerDescriptor) { when(descriptor.io.in.fire) { state := State.waitDescriptor } }
    is(State.waitDescriptor) {
      when(descriptor.io.out.fire) {
        val response = descriptor.io.out.bits
        assert(response.tag === request.tag && response.bindingIndex === request.bindingIndex)
        when(cancelled || io.cancel) {
          result.status := StorageDescriptorStatus.cancelled.U; result.source := 1.U; state := State.complete
        }.elsewhen(response.status =/= StorageDescriptorStatus.ok.U) {
          result.status := response.status; result.source := 1.U
          result.faultAddressValid := response.faultAddressValid; result.faultAddress := response.faultAddress
          state := State.complete
        }.otherwise { view := response.view; state := State.offerWord }
      }
    }
    is(State.offerWord) { when(word.io.in.fire) { state := State.waitWord } }
    is(State.waitWord) {
      when(word.io.out.fire) {
        val response = word.io.out.bits
        assert(response.tag === request.tag)
        result.status := Mux(cancelled || io.cancel, StorageWordStatus.cancelled.U, response.status)
        result.source := 2.U
        result.faultAddressValid := response.faultAddressValid; result.faultAddress := response.faultAddress
        when(!cancelled && !io.cancel && response.status === StorageWordStatus.ok.U) { result.data := response.data }
        state := State.complete
      }
    }
    is(State.complete) { when(io.out.fire) { state := State.idle } }
  }
}
