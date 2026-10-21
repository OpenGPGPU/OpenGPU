package opengpu.command

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class IndirectDispatchView extends Bundle {
  val allocationBase = UInt(32.W)
  val allocationBytes = UInt(32.W)
  val offset = UInt(32.W)
}

class IndirectDispatchResult extends Bundle {
  val groups = Vec(3, UInt(32.W))
  val status = UInt(GpuCommandResultStatus.width.W)
  val success = Bool()
  val empty = Bool()
}

/** Capture the three uint32 DispatchComputeIndirect arguments from one or
  * two translated, coherent cache-line reads. Only the requested twelve-byte
  * span is interpreted; the view and its exclusive allocation end are checked
  * at33 bits before any request is offered. One physical reply owner persists
  * through cancel: an already offered request cannot be withdrawn or its ID
  * reused before the real reply drains. No second line is issued after cancel.
  */
class IndirectDispatchReader(config: GpuConfig = GpuConfig()) extends Module {
  require(config.xLen == 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IndirectDispatchView))
    val out = Decoupled(new IndirectDispatchResult)
    val cancel = Input(Bool())
    val busy = Output(Bool())
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 1))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 1)))
  })
  private val idle :: check :: offer :: reply :: complete :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val view = Reg(new IndirectDispatchView)
  private val start = Reg(UInt(32.W))
  private val lineAddress = Reg(UInt(32.W))
  private val secondLine = RegInit(false.B)
  private val canceled = RegInit(false.B)
  private val result = Reg(new IndirectDispatchResult)
  io.in.ready := state === idle && !io.cancel
  io.out.valid := state === complete && !io.cancel && !canceled
  io.out.bits := result
  io.busy := state =/= idle
  io.memoryRequest.valid := state === offer
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := lineAddress
  io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryResponse.ready := state === reply
  when(io.cancel && state =/= idle) { canceled := true.B }

  private val allocationEnd = view.allocationBase +& view.allocationBytes
  private val offsetEnd = view.offset +& 12.U(32.W)
  private val requestedStart = view.allocationBase +& view.offset
  private val requestedEnd = requestedStart +& 12.U
  private val validView = view.offset(1,0) === 0.U && requestedStart(1,0) === 0.U &&
    allocationEnd <= (BigInt(1) << 32).U && offsetEnd <= view.allocationBytes &&
    requestedEnd <= (BigInt(1) << 32).U
  private val words = io.memoryResponse.bits.readData.asTypeOf(Vec(16, UInt(32.W)))
  private val readGroups = Wire(Vec(3, UInt(32.W)))
  for(k <- 0 until 3) {
    val index = start(5,2) +& k.U(4.W)
    val inThisLine = Mux(secondLine, index >= 16.U, index < 16.U)
    readGroups(k) := Mux(inThisLine, words(index(3,0)), result.groups(k))
  }
  switch(state) {
    is(idle) {
      when(io.in.fire) {
        view := io.in.bits
        result := 0.U.asTypeOf(result)
        secondLine := false.B; canceled := false.B; state := check
      }
    }
    is(check) {
      when(io.cancel || canceled) { state := idle }
        .elsewhen(!validView) {
          result.status := GpuCommandResultStatus.indirectBounds
          state := complete
        }.otherwise {
          start := requestedStart(31,0)
          lineAddress := Cat(requestedStart(31,6), 0.U(6.W))
          state := offer
        }
    }
    is(offer) { when(io.memoryRequest.fire) { state := reply } }
    is(reply) {
      when(io.memoryResponse.fire) {
        when(io.cancel || canceled) { state := idle }
          .elsewhen(io.memoryResponse.bits.fault) {
            result.status := GpuCommandResultStatus.indirectReadFailed
            state := complete
          }.otherwise {
            result.groups := readGroups
            when(!secondLine && start(5,2) >= 14.U) {
              secondLine := true.B
              lineAddress := lineAddress + 64.U
              state := offer
            }.otherwise {
              result.status := 0.U
              result.success := true.B
              result.empty := readGroups.map(_ === 0.U).reduce(_ || _)
              state := complete
            }
          }
      }
    }
    is(complete) { when(io.out.fire || io.cancel || canceled) { state := idle } }
  }
  when(io.memoryResponse.fire) {
    assert(io.memoryResponse.bits.transactionId === 0.U,
      "indirect argument replies must retain their sole physical owner")
  }
}
