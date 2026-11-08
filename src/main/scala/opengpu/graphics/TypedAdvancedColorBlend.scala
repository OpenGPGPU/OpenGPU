package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32DivLane,Fp32FmaLane,Fp32Operation,Fp32Request}

/** KHR advanced blend equations evaluated on premultiplied source/destination
  * colors. Supports the separable modes multiply, screen, overlay, darken,
  * lighten, hardlight, difference and exclusion. The remaining separable modes
  * (color dodge, color burn, softlight) and the non-separable HSL modes are
  * rejected.
  *
  * Per KHR, each color is unpremultiplied by its alpha, the mode function is
  * evaluated on the unpremultiplied colors, and the result is recomposited as
  * f*As*Ad + Rs*(1-Ad) + Rd*(1-As) with alpha As+Ad-As*Ad. One serialized FMA
  * lane and one divide lane execute a fixed micro-operation sequence; results
  * are held until accepted and accepted work completes after cancellation.
  */
object TypedAdvancedBlendEquation {
  val multiply=0;val screen=1;val overlay=2;val darken=3;val lighten=4
  val hardlight=5;val difference=6;val exclusion=7
  val supported=8
}

class TypedAdvancedBlendRequest extends Bundle {
  /** Premultiplied RGBA, as KHR advanced blending defines them. */
  val source=Vec(4,UInt(32.W));val destination=Vec(4,UInt(32.W))
  val equation=UInt(4.W);val tag=UInt(8.W)
}
class TypedAdvancedBlendResult extends Bundle {
  val rgba=Vec(4,UInt(32.W));val tag=UInt(8.W);val status=UInt(2.W)
  val arithmeticStatus=UInt(5.W);val cancelled=Bool()
}

class TypedAdvancedColorBlend(tagWidth: Int=8) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedAdvancedBlendRequest));val out=Decoupled(new TypedAdvancedBlendResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,check,run,offer,mathWait,finish,respond=Value}
  private val phase=RegInit(State.idle)
  private val request=Reg(new TypedAdvancedBlendRequest)
  private val result=Reg(new TypedAdvancedBlendResult)
  private val status=RegInit(0.U(2.W));private val cancelled=RegInit(false.B)
  private val step=Reg(UInt(7.W));private val pendingDest=Reg(UInt(7.W));private val laneDiv=RegInit(false.B)
  private val pending=Reg(new Fp32Request(tagWidth))
  private val regs=Reg(Vec(68,UInt(32.W)));private val fRegs=Reg(Vec(3,UInt(32.W)))

  private val one="h3f800000".U(32.W);private val two="h40000000".U(32.W);private val half="h3f000000".U(32.W)
  private def neg(x: UInt): UInt=x^"h80000000".U
  private val as=request.source(3);private val ad=request.destination(3)
  private def base(c: Int): Int=8+c*20
  private def fAt(c: UInt): UInt={
    val b=8.U+c*20.U;val cs=regs(b);val cd=regs(b+1.U);val mul=regs(b+2.U);val screen=regs(b+4.U)
    val b1=regs(b+6.U);val c2=regs(b+11.U);val excl=regs(b+12.U);val diff=regs(b+13.U)
    val cdLE= !PrimitiveClipStage.less(half,cd);val csLE= !PrimitiveClipStage.less(half,cs)
    MuxLookup(request.equation,mul)(Seq(
      TypedAdvancedBlendEquation.multiply.U->mul,
      TypedAdvancedBlendEquation.screen.U->screen,
      TypedAdvancedBlendEquation.overlay.U->Mux(cdLE,b1,c2),
      TypedAdvancedBlendEquation.darken.U->TypedBlendState.minimum(cs,cd),
      TypedAdvancedBlendEquation.lighten.U->TypedBlendState.minimum(cs,cd,true.B),
      TypedAdvancedBlendEquation.hardlight.U->Mux(csLE,b1,c2),
      TypedAdvancedBlendEquation.difference.U->(diff&"h7fffffff".U),
      TypedAdvancedBlendEquation.exclusion.U->excl))
  }

  private case class Op(kind: Int,a: UInt,b: UInt,dest: Int,channel: Int)
  private val ops=scala.collection.mutable.ArrayBuffer.empty[Op]
  private def mul(a: UInt,b: UInt,dest: Int,channel: Int=0): Unit=ops+=Op(0,a,b,dest,channel)
  private def add(a: UInt,b: UInt,dest: Int,channel: Int=0): Unit=ops+=Op(1,a,b,dest,channel)
  private def sub(a: UInt,b: UInt,dest: Int,channel: Int=0): Unit=ops+=Op(1,a,neg(b),dest,channel)
  private def div(a: UInt,b: UInt,dest: Int): Unit=ops+=Op(2,a,b,dest,0)
  private def select(c: Int): Unit=ops+=Op(3,0.U,0.U,0,c)
  mul(as,ad,0);sub(one,ad,1);sub(one,as,2);add(as,ad,3);sub(regs(3),regs(0),4)
  for(c<-0 until 3){
    val b=base(c);val rs=request.source(c);val rd=request.destination(c)
    div(rs,as,b);div(rd,ad,b+1)
    mul(regs(b),regs(b+1),b+2)
    add(regs(b),regs(b+1),b+3)
    sub(regs(b+3),regs(b+2),b+4)
    mul(two,regs(b),b+5)
    mul(regs(b+5),regs(b+1),b+6)
    sub(one,regs(b),b+7)
    sub(one,regs(b+1),b+8)
    mul(two,regs(b+7),b+9)
    mul(regs(b+9),regs(b+8),b+10)
    sub(one,regs(b+10),b+11)
    sub(regs(b+4),regs(b+2),b+12)
    sub(regs(b+1),regs(b),b+13)
    select(c)
    mul(fRegs(c),regs(0),b+14)
    mul(rs,regs(1),b+15)
    add(regs(b+14),regs(b+15),b+16)
    mul(rd,regs(2),b+17)
    add(regs(b+16),regs(b+17),b+18)
  }
  private val lastStep=ops.size-1
  private val kindVec=VecInit(ops.toSeq.map(_.kind.U(2.W)))
  private val aVec=VecInit(ops.toSeq.map(_.a));private val bVec=VecInit(ops.toSeq.map(_.b))
  private val destVec=VecInit(ops.toSeq.map(_.dest.U(7.W)));private val channelVec=VecInit(ops.toSeq.map(_.channel.U(2.W)))

  private val fma=Module(new Fp32FmaLane(tagWidth));private val divLane=Module(new Fp32DivLane(tagWidth))
  fma.io.flush:=false.B;divLane.io.flush:=false.B
  fma.io.in.bits:=pending;divLane.io.in.bits:=pending
  private def advance(): Unit={
    when(step===lastStep.U){phase:=State.finish}.otherwise{step:=step+1.U;phase:=State.run}
  }
  private def issue(kind: UInt,a: UInt,b: UInt,dest: UInt,channel: UInt): Unit={
    pendingDest:=dest
    when(kind===0.U){
      pending:=0.U.asTypeOf(pending);pending.operation:=Fp32Operation.mul
      pending.operandA:=a;pending.operandB:=b;pending.tag:=request.tag
      laneDiv:=false.B;phase:=State.offer
    }.elsewhen(kind===1.U){
      pending:=0.U.asTypeOf(pending);pending.operation:=Fp32Operation.add
      pending.operandB:=a;pending.operandC:=b;pending.tag:=request.tag
      laneDiv:=false.B;phase:=State.offer
    }.elsewhen(kind===2.U){
      when(b(30,0)===0.U){regs(dest):=0.U;advance()}
        .otherwise{pending:=0.U.asTypeOf(pending);pending.operation:=Fp32Operation.div
          pending.operandA:=a;pending.operandB:=b;pending.tag:=request.tag
          laneDiv:=true.B;phase:=State.offer}
    }.otherwise{
      fRegs(channel):=fAt(channel);advance()
    }
  }
  fma.io.in.valid:=phase===State.offer && !laneDiv;divLane.io.in.valid:=phase===State.offer && laneDiv
  fma.io.out.ready:=phase===State.mathWait && !laneDiv;divLane.io.out.ready:=phase===State.mathWait && laneDiv
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.respond;io.out.bits:=result
  io.busy:=phase=/=State.idle
  when(phase=/=State.idle && phase=/=State.respond && io.cancel){cancelled:=true.B}
  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    status:=0.U;cancelled:=false.B;step:=0.U;phase:=State.check
  }
  switch(phase){
    is(State.check){
      when(request.equation>=TypedAdvancedBlendEquation.supported.U){status:=1.U;phase:=State.finish}
        .otherwise{phase:=State.run}
    }
    is(State.run){issue(kindVec(step),aVec(step),bVec(step),destVec(step),channelVec(step))}
    is(State.offer){
      when(Mux(laneDiv,divLane.io.in.fire,fma.io.in.fire)){phase:=State.mathWait}
    }
    is(State.mathWait){
      val fire=Mux(laneDiv,divLane.io.out.fire,fma.io.out.fire)
      when(fire){
        regs(pendingDest):=Mux(laneDiv,divLane.io.out.bits.result,fma.io.out.bits.result)
        result.arithmeticStatus:=result.arithmeticStatus|
          Mux(laneDiv,divLane.io.out.bits.status,fma.io.out.bits.status)
        advance()
      }
    }
    is(State.finish){
      result.status:=status;result.cancelled:=cancelled||io.cancel
      for(c<-0 until 3)result.rgba(c):=regs(base(c)+18)
      result.rgba(3):=regs(4)
      phase:=State.respond
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
