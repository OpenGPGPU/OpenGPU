package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationBarrierTask extends Bundle {
  val taskId=UInt(32.W);val invocations=UInt(6.W)
}
class TessellationBarrierArrival extends Bundle {
  val invocation=UInt(5.W);val barrierId=UInt(32.W)
}
class TessellationBarrierRelease extends Bundle {
  val invocation=UInt(5.W);val barrierId=UInt(32.W);val epoch=UInt(32.W)
}
class TessellationBarrierResult extends Bundle {
  val taskId=UInt(32.W);val status=UInt(2.W);val completed=UInt(6.W);val barriers=UInt(32.W)
}

/** One control-shader patch. All invocations must reach the same barrier
  * before any is released; each release is held until consumed. Completion
  * and divergent/duplicate arrival faults retain ownership until reported.
  */
class TessellationBarrier extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationBarrierTask))
    val arrive=Flipped(Decoupled(new TessellationBarrierArrival))
    val finish=Flipped(Decoupled(UInt(5.W)))
    val release=Decoupled(new TessellationBarrierRelease)
    val result=Decoupled(new TessellationBarrierResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,running,releasing,report=Value}
  private val state=RegInit(State.idle);private val task=Reg(new TessellationBarrierTask)
  private val expected=Reg(UInt(32.W));private val arrived=RegInit(0.U(32.W));private val finished=RegInit(0.U(32.W))
  private val remaining=RegInit(0.U(32.W));private val barrierId=Reg(UInt(32.W));private val epochs=RegInit(0.U(32.W))
  private val status=RegInit(0.U(2.W));private val cancelPending=RegInit(false.B)
  io.task.ready:=state===State.idle && !io.cancel;io.busy:=state=/=State.idle
  when(io.task.fire){
    task:=io.task.bits;arrived:=0.U;finished:=0.U;remaining:=0.U;epochs:=0.U;cancelPending:=false.B
    val valid=io.task.bits.invocations>=1.U && io.task.bits.invocations<=TessellationLimits.maxPatchVertices.U
    expected:=("hffffffff".U(32.W) >> (32.U(6.W)-io.task.bits.invocations))(31,0)
    status:=Mux(valid,0.U,1.U);state:=Mux(valid,State.running,State.report)
  }
  io.finish.ready:=state===State.running && !io.cancel
  io.arrive.ready:=state===State.running && !io.cancel && !io.finish.valid
  private val finishBit=UIntToOH(io.finish.bits,32);private val arriveBit=UIntToOH(io.arrive.bits.invocation,32)
  when(state===State.running){
    when(io.cancel){status:=2.U;state:=State.report}
      .elsewhen(io.finish.fire){
        when(io.finish.bits>=task.invocations || (finished & finishBit).orR || arrived.orR){status:=1.U;state:=State.report}
          .otherwise{val next=finished|finishBit;finished:=next;when(next===expected){state:=State.report}}
      }.elsewhen(io.arrive.fire){
        when(io.arrive.bits.invocation>=task.invocations || (arrived & arriveBit).orR || finished.orR ||
          (arrived.orR && io.arrive.bits.barrierId=/=barrierId)) {status:=1.U;state:=State.report}
          .otherwise{
            when(!arrived.orR){barrierId:=io.arrive.bits.barrierId}
            val next=arrived|arriveBit;arrived:=next
            when(next===expected){remaining:=expected;state:=State.releasing}
          }
      }
  }
  io.release.valid:=state===State.releasing
  io.release.bits.invocation:=PriorityEncoder(remaining)
  io.release.bits.barrierId:=barrierId;io.release.bits.epoch:=epochs
  when(state===State.releasing){
    when(io.cancel){cancelPending:=true.B}
    when(io.release.fire){
      val next=remaining & ~UIntToOH(io.release.bits.invocation,32);remaining:=next
      when(io.cancel || cancelPending){status:=2.U;state:=State.report}
        .elsewhen(!next.orR){
          when(epochs.andR){status:=1.U;state:=State.report}
            .otherwise{epochs:=epochs+1.U;arrived:=0.U;state:=State.running}
        }
    }
  }
  io.result.valid:=state===State.report;io.result.bits.taskId:=task.taskId;io.result.bits.status:=status
  io.result.bits.completed:=PopCount(finished);io.result.bits.barriers:=epochs
  when(io.result.fire){state:=State.idle}
}
