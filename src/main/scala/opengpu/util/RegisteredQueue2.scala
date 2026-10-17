package opengpu.util

import chisel3._
import chisel3.util._

/** Two entries with the same non-flowing, non-pipelined admission contract
  * as Queue(entries=2, pipe=false, flow=false). The head is a register,
  * removing a wide pointer-selected read mux before downstream arithmetic.
  */
class RegisteredQueue2[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
    val count = Output(UInt(2.W))
  })
  private val head = Reg(chiselTypeOf(io.enq.bits))
  private val tail = Reg(chiselTypeOf(io.enq.bits))
  private val headValid = RegInit(false.B)
  private val tailValid = RegInit(false.B)

  io.enq.ready := !tailValid
  io.deq.valid := headValid
  io.deq.bits := head
  io.count := headValid.asUInt +& tailValid.asUInt

  when(io.deq.fire) {
    when(tailValid) {
      head := tail
      tailValid := false.B
    }.otherwise {
      headValid := false.B
    }
  }
  when(io.enq.fire) {
    when(!headValid || io.deq.fire) {
      head := io.enq.bits
      headValid := true.B
    }.otherwise {
      tail := io.enq.bits
      tailValid := true.B
    }
  }
}
