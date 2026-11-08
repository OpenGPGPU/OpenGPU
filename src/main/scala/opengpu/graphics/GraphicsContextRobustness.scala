package opengpu.graphics

import chisel3._
import chisel3.util._

/** Context-level robust-access and reset-notification state.
  *
  * The owner latches the first reset cause when the notification strategy is
  * LOSE_CONTEXT_ON_RESET, reports guilt by comparing the faulting context with
  * the local one, and keeps the context lost until an explicit restore. A
  * status query returns the latched cause and clears it to NO_ERROR, matching
  * `glGetGraphicsResetStatus`. NO_RESET_NOTIFICATION never latches a cause.
  * The context-flag word publishes the robust-access bit when configured.
  *
  * Robust memory behavior at individual resource owners, API object lifetime
  * and physical reset timing remain outside this block.
  */
object GraphicsResetStatus {
  val noError=0;val guilty=1;val innocent=2;val unknown=3
}
object GraphicsContextFlags {
  val robustAccess=BigInt(4)
}
class GraphicsResetFault extends Bundle {
  val contextId=UInt(32.W);val guilty=Bool();val known=Bool()
}

class GraphicsContextRobustness extends Module {
  val io=IO(new Bundle {
    val contextId=Input(UInt(32.W))
    /** 0 NO_RESET_NOTIFICATION,1 LOSE_CONTEXT_ON_RESET; higher values latch nothing. */
    val notificationStrategy=Input(UInt(2.W))
    val robustAccess=Input(Bool())
    val fault=Flipped(Valid(new GraphicsResetFault))
    val query=Decoupled(UInt(2.W))
    val restore=Input(Bool())
    val lost=Output(Bool())
    val flags=Output(UInt(32.W))
  })
  private val latched=RegInit(false.B);private val lost=RegInit(false.B)
  private val status=RegInit(GraphicsResetStatus.noError.U(2.W))
  private val loseContext=io.notificationStrategy===1.U
  when(io.restore){latched:=false.B;lost:=false.B;status:=GraphicsResetStatus.noError.U}
    .elsewhen(io.fault.valid && loseContext && !latched){
      latched:=true.B;lost:=true.B
      status:=Mux(!io.fault.bits.known,GraphicsResetStatus.unknown.U,
        Mux(io.fault.bits.guilty || io.fault.bits.contextId===io.contextId,
          GraphicsResetStatus.guilty.U,GraphicsResetStatus.innocent.U))
    }.elsewhen(io.query.fire){status:=GraphicsResetStatus.noError.U}
  io.query.valid:=true.B;io.query.bits:=status
  io.lost:=lost
  io.flags:=Mux(io.robustAccess,GraphicsContextFlags.robustAccess.U(32.W),0.U)
}
