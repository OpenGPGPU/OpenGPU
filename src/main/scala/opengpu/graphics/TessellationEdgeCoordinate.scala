package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationEdgeRequest extends Bundle {
  /** Positive clamped FP32 level from TessellationLevelPrepare. */
  val level=UInt(32.W);val spacing=UInt(2.W);val index=UInt(7.W);val promoted=Bool()
}
class TessellationEdgeResult extends Bundle {
  /** Unsigned Q0.24 coordinate, including 1.0 as 1<<24. */
  val coordinate=UInt(25.W);val segments=UInt(7.W);val index=UInt(7.W)
  /** 0 success, 1 invalid input, 2 cancelled. */
  val status=UInt(2.W)
}

/** Deterministic symmetric edge coordinates with a bounded iterative divider.
  * Fractional spacing places the two short segments at opposite endpoints.
  */
class TessellationEdgeCoordinate extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TessellationEdgeRequest))
    val out=Decoupled(new TessellationEdgeResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,send,waitLevel,divide,offer=Value}
  private val state=RegInit(State.idle);private val request=Reg(new TessellationEdgeRequest)
  private val result=Reg(new TessellationEdgeResult);private val cancelled=RegInit(false.B)
  private val rem=Reg(UInt(31.W));private val den=Reg(UInt(31.W));private val quotient=Reg(UInt(25.W));private val step=Reg(UInt(5.W))
  private val levels=Module(new TessellationLevelPrepare)
  io.in.ready:=state===State.idle && !io.cancel;io.busy:=state=/=State.idle
  io.out.valid:=state===State.offer;io.out.bits:=result
  levels.io.in.valid:=state===State.send
  levels.io.in.bits.domain:=TessellationDomain.triangles.U;levels.io.in.bits.spacing:=request.spacing
  levels.io.in.bits.outer:=VecInit(Seq(request.level,"h3f800000".U,"h3f800000".U,"h3f800000".U))
  levels.io.in.bits.inner:=VecInit(Seq.fill(2)("h3f800000".U(32.W)))
  levels.io.out.ready:=state===State.waitLevel
  when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);cancelled:=false.B;state:=State.send}
  when(state=/=State.idle && state=/=State.offer && io.cancel){cancelled:=true.B}
  when(levels.io.in.fire){state:=State.waitLevel}
  when(levels.io.out.fire){
    val l=levels.io.out.bits.outer(0)
    val promoteValid= !request.promoted || (l.segments===1.U && request.spacing=/=TessellationSpacing.fractionalEven.U)
    val count=Mux(request.promoted,Mux(request.spacing===TessellationSpacing.fractionalOdd.U,3.U,2.U),l.segments)
    val invalid=levels.io.out.bits.invalid || levels.io.out.bits.discard || !promoteValid || request.index>count
    result.index:=request.index;result.segments:=count
    when(invalid || request.index===0.U || request.index===count){
      result.coordinate:=Mux(!invalid && request.index===count,(BigInt(1)<<24).U,0.U)
      result.status:=Mux(cancelled || io.cancel,2.U,Mux(invalid,1.U,0.U));state:=State.offer
    }.otherwise{
      val mantissa=Cat(1.U(1.W),l.clamped(22,0))
      val shift=(l.clamped(30,23)-127.U)(2,0)
      val fixed=(mantissa << shift)(29,0)
      val delta=(request.index << 1).zext - count.zext
      val fractional=(fixed.zext +& (delta << 23)).asUInt
      rem:=Mux(request.spacing===TessellationSpacing.equal.U,request.index,fractional(30,0))
      den:=Mux(request.spacing===TessellationSpacing.equal.U,count,Cat(fixed,0.U(1.W)))
      quotient:=0.U;step:=0.U;state:=State.divide
    }
  }
  when(state===State.divide){
    val doubled=Cat(rem,0.U(1.W));val take=doubled>=den
    val nextRem=Mux(take,doubled-den,doubled)(30,0)
    val nextQuotient=Cat(quotient(23,0),take)
    rem:=nextRem;quotient:=nextQuotient
    when(step===23.U){
      val twice=Cat(nextRem,0.U(1.W));val round=twice>den || (twice===den && nextQuotient(0))
      result.coordinate:=nextQuotient+round
      result.status:=Mux(cancelled || io.cancel,2.U,0.U);state:=State.offer
    }.otherwise{step:=step+1.U}
  }
  when(io.out.fire){state:=State.idle}
}
