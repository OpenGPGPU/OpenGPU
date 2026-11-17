package opengpu.system
import opengpu.graphics.tessellation._

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

class TessellationPatchInputDmaTask extends Bundle {
  val taskId = UInt(32.W); val vertices = UInt(6.W); val components = UInt(7.W)
  val view = new VertexInputView; val stride = UInt(32.W)
}
class TessellationPatchInputDmaCompletion extends Bundle {
  val taskId = UInt(32.W); val words = UInt(12.W); val faultAddress = UInt(32.W)
  /** 0 success, 1 invalid descriptor, 2 memory fault, 3 patch fault, 4 cancellation. */
  val status = UInt(3.W)
}

/** Load raw vertex words into an already opened patch. The owner pins the
  * translation and input allocation and exclusively routes transaction ID 0
  * and input-load replies through completion. Accepted offers and responses
  * must drain even after cancellation; completed writes are not rolled back.
  */
class TessellationPatchInputDma(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new TessellationPatchInputDmaTask))
    val completion = Decoupled(new TessellationPatchInputDmaCompletion)
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
    val load = Decoupled(new TessellationPatchAccess)
    val reply = Flipped(Decoupled(new TessellationContextReply))
    val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle,select,offer,waitMemory,load,waitReply,complete = Value }
  private val state = RegInit(State.idle); private val task = Reg(new TessellationPatchInputDmaTask)
  private val result = Reg(new TessellationPatchInputDmaCompletion); private val cancelled = RegInit(false.B)
  private val vertex = Reg(UInt(6.W)); private val component = Reg(UInt(7.W))
  private val cacheValid = RegInit(false.B); private val cacheAddress = Reg(UInt(26.W)); private val cacheData = Reg(UInt(512.W))
  private val stop = io.cancel || cancelled
  private val input = io.task.bits; private val v = input.view
  private val end = v.base.pad(64) +& ((input.vertices - 1.U) * input.stride).pad(64) +& (input.components << 2).pad(64)
  private val validView = v.allocationBase(5,0) === 0.U && v.allocationLimit(5,0) === 0.U &&
    v.allocationLimit <= (BigInt(1)<<32).U && v.allocationLimit > v.allocationBase &&
    v.base >= v.allocationBase && v.limit >= v.base && v.limit <= v.allocationLimit &&
    v.base(1,0) === 0.U && input.stride(1,0) === 0.U && end <= v.limit && end <= (BigInt(1)<<32).U
  private val legal = input.vertices >= 1.U && input.vertices <= TessellationLimits.maxPatchVertices.U &&
    input.components <= 64.U && input.vertices * input.components <= TessellationLimits.maxControlOutputComponents.U &&
    (input.components === 0.U || validView)
  private val byteAddress = task.view.base.pad(64) +& (vertex * task.stride).pad(64) +& (component << 2).pad(64)
  private val lineAddress = byteAddress(31,6)
  io.task.ready := state === State.idle && !io.cancel
  when(io.task.fire) {
    task := io.task.bits; result := 0.U.asTypeOf(result); result.taskId := io.task.bits.taskId
    vertex := 0.U; component := 0.U; cancelled := false.B; cacheValid := false.B
    when(!legal) { result.status := 1.U; state := State.complete }
      .elsewhen(io.task.bits.components === 0.U) { state := State.complete }
      .otherwise { state := State.select }
  }
  when(state =/= State.idle && state =/= State.complete && io.cancel) { cancelled := true.B }
  when(state === State.select) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(cacheValid && cacheAddress === lineAddress) { state := State.load }
      .otherwise { state := State.offer }
  }
  io.memoryRequest.valid := state === State.offer
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := Cat(lineAddress,0.U(6.W)); io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryRequest.bits.byteMask := Fill(64,1.U(1.W)); io.memoryRequest.bits.cachePolicy := CachePolicy.cached
  when(io.memoryRequest.fire) { state := State.waitMemory }
  io.memoryResponse.ready := state === State.waitMemory && io.memoryResponse.bits.transactionId === 0.U
  when(io.memoryResponse.fire) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(io.memoryResponse.bits.fault) { result.status := 2.U; result.faultAddress := byteAddress(31,0); state := State.complete }
      .otherwise { cacheValid := true.B; cacheAddress := lineAddress; cacheData := io.memoryResponse.bits.readData; state := State.load }
  }
  io.load.valid := state === State.load
  io.load.bits := 0.U.asTypeOf(io.load.bits); io.load.bits.write := true.B
  io.load.bits.vertex := vertex; io.load.bits.component := component
  io.load.bits.data := (cacheData >> (byteAddress(5,0) << 3))(31,0)
  when(io.load.fire) { state := State.waitReply }
  io.reply.ready := state === State.waitReply && io.reply.bits.source === 0.U
  when(io.reply.fire) {
    when(stop) { result.status := 4.U; state := State.complete }
      .elsewhen(io.reply.bits.result.status =/= 0.U) { result.status := 3.U; result.faultAddress := byteAddress(31,0); state := State.complete }
      .otherwise {
        result.words := result.words + 1.U
        when(component +& 1.U === task.components) {
          component := 0.U
          when(vertex +& 1.U === task.vertices) { state := State.complete }
            .otherwise { vertex := vertex + 1.U; state := State.select }
        }.otherwise { component := component + 1.U; state := State.select }
      }
  }
  io.completion.valid := state === State.complete; io.completion.bits := result
  io.busy := state =/= State.idle
  when(io.completion.fire) { state := State.idle }
}
