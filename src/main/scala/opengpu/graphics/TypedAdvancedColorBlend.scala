package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32DivLane,Fp32FmaLane,Fp32Operation,Fp32Request,Fp32SqrtLane}

/** KHR advanced blend equations evaluated on premultiplied source/destination
  * colors. Supports every separable mode: multiply, screen, overlay, darken,
  * lighten, color dodge, color burn, hardlight, softlight, difference and
  * exclusion. The four non-separable HSL modes are rejected.
  *
  * Per KHR, each color is unpremultiplied by its alpha, the mode function is
  * evaluated on the unpremultiplied colors, and the result is recomposited as
  * f*As*Ad + Rs*(1-Ad) + Rd*(1-As) with alpha As+Ad-As*Ad. One serialized FMA
  * lane, one divide lane and one square-root lane execute a fixed
  * micro-operation sequence; operations used only by the division and root
  * modes are skipped for the simpler equations.
  */
object TypedAdvancedBlendEquation {
  val multiply=0;val screen=1;val overlay=2;val darken=3;val lighten=4
  val colordodge=5;val colorburn=6;val hardlight=7;val softlight=8
  val difference=9;val exclusion=10
  val supported=11
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
  private val step=Reg(UInt(7.W));private val pendingDest=Reg(UInt(7.W))
  private val lane=Reg(UInt(2.W));private val pending=Reg(new Fp32Request(tagWidth))
  private val regs=Reg(Vec(136,UInt(32.W)));private val fRegs=Reg(Vec(3,UInt(32.W)))

  private val zero=0.U(32.W);private val one="h3f800000".U(32.W);private val two="h40000000".U(32.W)
  private val three="h40400000".U(32.W);private val twelve="h41400000".U(32.W)
  private val sixteen="h41800000".U(32.W);private val quarter="h3e800000".U(32.W);private val half="h3f000000".U(32.W)
  private def neg(x: UInt): UInt=x^"h80000000".U
  private val as=request.source(3);private val ad=request.destination(3)
  private def base(c: Int): Int=8+c*40
  private def nonZero(x: UInt): UInt=Mux(x(30,0)===0.U,one,x)

  private def fAt(c: UInt): UInt={
    val b=8.U+c*40.U
    val cs=regs(b);val cd=regs(b+1.U);val mul=regs(b+2.U);val screen=regs(b+4.U)
    val b1=regs(b+6.U);val c2=regs(b+11.U);val excl=regs(b+12.U);val diff=regs(b+13.U)
    val dodge=regs(b+19.U);val burn=regs(b+21.U);val softLow=regs(b+25.U)
    val softHighSmall=regs(b+33.U);val softHigh=regs(b+37.U)
    val cdLEhalf= !PrimitiveClipStage.less(half,cd);val csLEhalf= !PrimitiveClipStage.less(half,cs)
    val cdLEzero= !PrimitiveClipStage.less(zero,cd);val csGEone= !PrimitiveClipStage.less(cs,one)
    val cdGEone= !PrimitiveClipStage.less(cd,one);val csLEzero= !PrimitiveClipStage.less(zero,cs)
    val cdLEquarter= !PrimitiveClipStage.less(quarter,cd)
    MuxLookup(request.equation,mul)(Seq(
      TypedAdvancedBlendEquation.multiply.U->mul,
      TypedAdvancedBlendEquation.screen.U->screen,
      TypedAdvancedBlendEquation.overlay.U->Mux(cdLEhalf,b1,c2),
      TypedAdvancedBlendEquation.darken.U->TypedBlendState.minimum(cs,cd),
      TypedAdvancedBlendEquation.lighten.U->TypedBlendState.minimum(cs,cd,true.B),
      TypedAdvancedBlendEquation.colordodge.U->Mux(cdLEzero,zero,
        Mux(csGEone,one,TypedBlendState.minimum(dodge,one))),
      TypedAdvancedBlendEquation.colorburn.U->Mux(cdGEone,one,Mux(csLEzero,zero,burn)),
      TypedAdvancedBlendEquation.hardlight.U->Mux(csLEhalf,b1,c2),
      TypedAdvancedBlendEquation.softlight.U->Mux(csLEhalf,softLow,Mux(cdLEquarter,softHighSmall,softHigh)),
      TypedAdvancedBlendEquation.difference.U->(diff&"h7fffffff".U),
      TypedAdvancedBlendEquation.exclusion.U->excl))
  }

  private case class Op(kind: Int,a: UInt,b: UInt,dest: Int,channel: Int,minEquation: Int)
  private val ops=scala.collection.mutable.ArrayBuffer.empty[Op]
  private def emit(kind: Int,a: UInt,b: UInt,dest: Int,channel: Int=0,minEquation: Int=0): Unit=
    ops+=Op(kind,a,b,dest,channel,minEquation)
  private def mul(a: UInt,b: UInt,dest: Int,minEquation: Int=0): Unit=emit(0,a,b,dest,0,minEquation)
  private def add(a: UInt,b: UInt,dest: Int,minEquation: Int=0): Unit=emit(1,a,b,dest,0,minEquation)
  private def sub(a: UInt,b: UInt,dest: Int,minEquation: Int=0): Unit=emit(1,a,neg(b),dest,0,minEquation)
  private def div(a: UInt,b: UInt,dest: Int,minEquation: Int=0): Unit=emit(2,a,b,dest,0,minEquation)
  private def sqrt(a: UInt,dest: Int,minEquation: Int): Unit=emit(4,a,zero,dest,0,minEquation)
  private def select(c: Int): Unit=emit(3,zero,zero,0,c)

  mul(as,ad,0);sub(one,ad,1);sub(one,as,2);add(as,ad,3);sub(regs(3),regs(0),4)
  for(c<-0 until 3){
    val b=base(c);val rs=request.source(c);val rd=request.destination(c)
    div(rs,nonZero(as),b);div(rd,nonZero(ad),b+1)
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
    val advanced=TypedAdvancedBlendEquation.colordodge
    div(regs(b+1),nonZero(regs(b+7)),b+19,advanced)
    div(regs(b+8),nonZero(regs(b)),b+20,advanced)
    sub(one,TypedBlendState.minimum(regs(b+20),one),b+21,advanced)
    sqrt(regs(b+1),b+34,advanced)
    sub(one,regs(b+5),b+22,advanced)
    mul(regs(b+22),regs(b+1),b+23,advanced)
    mul(regs(b+23),regs(b+8),b+24,advanced)
    sub(regs(b+1),regs(b+24),b+25,advanced)
    mul(sixteen,regs(b+1),b+26,advanced)
    sub(regs(b+26),twelve,b+27,advanced)
    mul(regs(b+27),regs(b+1),b+28,advanced)
    add(regs(b+28),three,b+29,advanced)
    sub(regs(b+5),one,b+30,advanced)
    mul(regs(b+30),regs(b+1),b+31,advanced)
    mul(regs(b+31),regs(b+29),b+32,advanced)
    add(regs(b+1),regs(b+32),b+33,advanced)
    sub(regs(b+34),regs(b+1),b+35,advanced)
    mul(regs(b+30),regs(b+35),b+36,advanced)
    add(regs(b+1),regs(b+36),b+37,advanced)
    select(c)
    mul(fRegs(c),regs(0),b+14)
    mul(rs,regs(1),b+15)
    add(regs(b+14),regs(b+15),b+16)
    mul(rd,regs(2),b+17)
    add(regs(b+16),regs(b+17),b+18)
  }
  private val lastStep=ops.size-1
  private val kindVec=VecInit(ops.toSeq.map(_.kind.U(3.W)))
  private val aVec=VecInit(ops.toSeq.map(_.a));private val bVec=VecInit(ops.toSeq.map(_.b))
  private val destVec=VecInit(ops.toSeq.map(_.dest.U(7.W)))
  private val channelVec=VecInit(ops.toSeq.map(_.channel.U(2.W)))
  private val minEquationVec=VecInit(ops.toSeq.map(_.minEquation.U(4.W)))

  private val fma=Module(new Fp32FmaLane(tagWidth));private val divLane=Module(new Fp32DivLane(tagWidth))
  private val sqrtLane=Module(new Fp32SqrtLane(tagWidth))
  fma.io.flush:=false.B;divLane.io.flush:=false.B;sqrtLane.io.flush:=false.B
  fma.io.in.bits:=pending;divLane.io.in.bits:=pending;sqrtLane.io.in.bits:=pending
  fma.io.in.valid:=phase===State.offer && lane===0.U
  divLane.io.in.valid:=phase===State.offer && lane===1.U
  sqrtLane.io.in.valid:=phase===State.offer && lane===2.U
  fma.io.out.ready:=phase===State.mathWait && lane===0.U
  divLane.io.out.ready:=phase===State.mathWait && lane===1.U
  sqrtLane.io.out.ready:=phase===State.mathWait && lane===2.U
  private val mathFire=MuxLookup(lane,false.B)(Seq(0.U->fma.io.out.fire,1.U->divLane.io.out.fire,2.U->sqrtLane.io.out.fire))
  private val offerFire=MuxLookup(lane,false.B)(Seq(0.U->fma.io.in.fire,1.U->divLane.io.in.fire,2.U->sqrtLane.io.in.fire))
  private val mathResult=MuxLookup(lane,0.U(32.W))(Seq(0.U->fma.io.out.bits.result,
    1.U->divLane.io.out.bits.result,2.U->sqrtLane.io.out.bits.result))
  private val mathStatus=MuxLookup(lane,0.U(5.W))(Seq(0.U->fma.io.out.bits.status,
    1.U->divLane.io.out.bits.status,2.U->sqrtLane.io.out.bits.status))

  private def advance(): Unit={
    when(step===lastStep.U){phase:=State.finish}.otherwise{step:=step+1.U;phase:=State.run}
  }
  private def issue(kind: UInt,a: UInt,b: UInt,dest: UInt,channel: UInt): Unit={
    pendingDest:=dest
    when(kind===0.U){
      pending:=0.U.asTypeOf(pending);pending.operation:=Fp32Operation.mul
      pending.operandA:=a;pending.operandB:=b;pending.tag:=request.tag
      lane:=0.U;phase:=State.offer
    }.elsewhen(kind===1.U){
      pending:=0.U.asTypeOf(pending);pending.operation:=Fp32Operation.add
      pending.operandB:=a;pending.operandC:=b;pending.tag:=request.tag
      lane:=0.U;phase:=State.offer
    }.elsewhen(kind===2.U){
      when(b(30,0)===0.U){regs(dest):=0.U;advance()}
        .otherwise{pending:=0.U.asTypeOf(pending);pending.operation:=Fp32Operation.div
          pending.operandA:=a;pending.operandB:=b;pending.tag:=request.tag
          lane:=1.U;phase:=State.offer}
    }.elsewhen(kind===4.U){
      pending:=0.U.asTypeOf(pending);pending.operation:=Fp32Operation.sqrt
      pending.operandA:=a;pending.tag:=request.tag
      lane:=2.U;phase:=State.offer
    }.otherwise{
      fRegs(channel):=fAt(channel);advance()
    }
  }
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
    is(State.run){
      when(request.equation<minEquationVec(step)){advance()}
        .otherwise{issue(kindVec(step),aVec(step),bVec(step),destVec(step),channelVec(step))}
    }
    is(State.offer){when(offerFire){phase:=State.mathWait}}
    is(State.mathWait){when(mathFire){
      regs(pendingDest):=mathResult
      result.arithmeticStatus:=result.arithmeticStatus|mathStatus
      advance()
    }}
    is(State.finish){
      result.status:=status;result.cancelled:=cancelled||io.cancel
      for(c<-0 until 3)result.rgba(c):=regs(base(c)+18)
      result.rgba(3):=regs(4)
      phase:=State.respond
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
