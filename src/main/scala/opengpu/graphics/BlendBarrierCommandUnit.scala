package opengpu.graphics

import chisel3._
import chisel3.util._

/** `glBlendBarrier` command ordering.
  *
  * Draw commands are released immediately and may pipeline with outstanding
  * blending. A blend-barrier command is not released until the blend service
  * reports no pending work; commands after the barrier are held by ordinary
  * backpressure until it completes. Reserved command codes are rejected.
  */
object BlendBarrierCommand {val draw=0;val barrier=1}
object BlendBarrierStatus {val ok=0;val invalid=1}
object BlendBarrierResultKind {val drawReleased=0;val barrierComplete=1;val invalid=2}

class BlendBarrierRequest extends Bundle {val tag=UInt(8.W);val command=UInt(2.W)}
class BlendBarrierResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val kind=UInt(2.W);val drawAllowed=Bool()
}

class BlendBarrierCommandUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new BlendBarrierRequest))
    val out=Decoupled(new BlendBarrierResult)
    val blendBarrierReady=Input(Bool())
    val barrierActive=Output(Bool())
    val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,draw,barrierWait,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new BlendBarrierRequest)
  private val result=Reg(new BlendBarrierResult)
  io.in.ready:=phase===State.idle
  io.out.valid:=phase===State.respond;io.out.bits:=result
  io.barrierActive:=phase===State.barrierWait
  io.busy:=phase=/=State.idle
  private val validCommand=request.command===BlendBarrierCommand.draw.U || request.command===BlendBarrierCommand.barrier.U
  when(io.in.fire){
    request:=io.in.bits
    phase:=Mux(io.in.bits.command===BlendBarrierCommand.draw.U,State.draw,
      Mux(io.in.bits.command===BlendBarrierCommand.barrier.U,State.barrierWait,State.respond))
    result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    when(io.in.bits.command=/=BlendBarrierCommand.draw.U && io.in.bits.command=/=BlendBarrierCommand.barrier.U){
      result.status:=BlendBarrierStatus.invalid.U;result.kind:=BlendBarrierResultKind.invalid.U;result.drawAllowed:=false.B
    }
  }
  switch(phase){
    is(State.draw){
      result.status:=BlendBarrierStatus.ok.U;result.kind:=BlendBarrierResultKind.drawReleased.U
      result.drawAllowed:=true.B
      phase:=State.respond
    }
    is(State.barrierWait){
      result.tag:=request.tag
      when(io.blendBarrierReady){
        result.status:=BlendBarrierStatus.ok.U;result.kind:=BlendBarrierResultKind.barrierComplete.U
        result.drawAllowed:=false.B
        phase:=State.respond
      }
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
