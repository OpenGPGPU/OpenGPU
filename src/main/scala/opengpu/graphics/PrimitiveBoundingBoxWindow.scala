package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32DivLane,Fp32FmaLane,Fp32Operation,Fp32Request}

/** Window-space bounds for a submitted clip-space primitive bounding box.
  *
  * The sixteen min/max clip-space vertices are projected by x/w and y/w and
  * viewport-transformed with a fused multiply-add. If every w is positive the
  * reported X/Y bounds are the projected minima and maxima. If any w is not
  * positive, or a projection is non-finite, the volume needs true clipping;
  * this owner reports the full viewport and clears `exact`, which is a safe
  * superset for a rasterization hint. Minima are floored and maxima ceiled to
  * fragment boundaries, with half the point size or rounded line width added
  * or subtracted (triangles use zero). Coordinates are GL window coordinates
  * with Y upward.
  */
class PrimitiveBoundingBoxWindowRequest extends Bundle {
  val tag=UInt(16.W);val box=new PrimitiveBoundingBoxState;val validBox=Bool()
  val viewport=new Fp32ViewportState
  /** 0 point,1 line,2 triangle,3 other (treated as triangle). */
  val kind=UInt(2.W);val size=UInt(32.W)
}
class PrimitiveBoundingBoxWindowResult extends Bundle {
  val tag=UInt(16.W);val status=UInt(2.W);val exact=Bool();val cancelled=Bool()
  val xmin=SInt(32.W);val ymin=SInt(32.W);val xmax=SInt(32.W);val ymax=SInt(32.W)
}

class PrimitiveBoundingBoxWindow extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new PrimitiveBoundingBoxWindowRequest));val out=Decoupled(new PrimitiveBoundingBoxWindowResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,check,mathOffer,mathWait,halfH,originX,originY,halfSize,vertex,divY,projX,projY,accumulate,
      edgeLeftX,edgeRightX,edgeRightX2,edgeLeftY,edgeRightY,edgeRightY2,finish,respond=Value
  }
  private object Dest extends ChiselEnum {
    val none,halfW,halfH,originX,originY,halfSize,qx,qy,px,py,leftX,rightX,leftY,rightY=Value
  }
  private val phase=RegInit(State.idle)
  private val request=Reg(new PrimitiveBoundingBoxWindowRequest)
  private val result=Reg(new PrimitiveBoundingBoxWindowResult)
  private val status=RegInit(0.U(2.W));private val cancelled=RegInit(false.B)
  private val vertex=Reg(UInt(4.W));private val inexact=RegInit(false.B)
  private val halfW=Reg(UInt(32.W));private val halfH=Reg(UInt(32.W))
  private val originX=Reg(UInt(32.W));private val originY=Reg(UInt(32.W))
  private val halfSize=Reg(UInt(32.W))
  private val minPx=Reg(UInt(32.W));private val maxPx=Reg(UInt(32.W))
  private val minPy=Reg(UInt(32.W));private val maxPy=Reg(UInt(32.W))
  private val qx=Reg(UInt(32.W));private val qy=Reg(UInt(32.W))
  private val px=Reg(UInt(32.W));private val py=Reg(UInt(32.W))
  private val loX=Reg(UInt(32.W));private val hiX=Reg(UInt(32.W))
  private val loY=Reg(UInt(32.W));private val hiY=Reg(UInt(32.W))
  private val pending=Reg(new Fp32Request(8));private val laneDiv=RegInit(false.B)
  private val dest=Reg(Dest());private val nextState=Reg(State())
  private val fma=Module(new Fp32FmaLane(8));private val divLane=Module(new Fp32DivLane(8))
  fma.io.flush:=false.B;divLane.io.flush:=false.B
  fma.io.in.bits:=pending;divLane.io.in.bits:=pending
  fma.io.in.valid:=phase===State.mathOffer && !laneDiv
  divLane.io.in.valid:=phase===State.mathOffer && laneDiv
  fma.io.out.ready:=phase===State.mathWait && !laneDiv
  divLane.io.out.ready:=phase===State.mathWait && laneDiv
  private val offerFire=Mux(laneDiv,divLane.io.in.fire,fma.io.in.fire)
  private val mathFire=Mux(laneDiv,divLane.io.out.fire,fma.io.out.fire)
  private val mathResult=Mux(laneDiv,divLane.io.out.bits.result,fma.io.out.bits.result)

  private val zero=0.U(32.W);private val half="h3f000000".U(32.W)
  private val signFlip="h80000000".U
  private def finite(x: UInt): Bool=x(30,23)=/=255.U
  private def intToFp(mag: UInt,negative: Bool): UInt={
    val top=(31.U-PriorityEncoder(Reverse(mag)))(4,0)
    val isZero=mag===0.U
    val leftShift=(23.U-top)(4,0);val rightShift=(top-23.U)(4,0)
    val exactSig=(mag<<leftShift)(23,0)
    val shifted=(mag>>rightShift)(31,0)
    val sig0=Mux(top<=23.U,exactSig,shifted(23,0))
    val remMask=((1.U(64.W)<<rightShift)-1.U)(31,0)
    val rem=mag&remMask
    val halfBit=Mux(top>23.U,(1.U(32.W)<<(rightShift-1.U))(31,0),zero)
    val roundUp=Mux(top<=23.U,false.B,(rem>halfBit)||(rem===halfBit && shifted(0)))
    val rounded=sig0+&roundUp.asUInt
    val carry=rounded(24)
    val sig=Mux(carry,(rounded>>1)(23,0),rounded(23,0))
    val exponent=(127.U(8.W)+top.pad(8)+carry.asUInt)(7,0)
    Mux(isZero,zero,Cat(negative,exponent,sig(22,0)))
  }
  private def floorCeil(bits: UInt): (SInt,SInt)={
    val c=ClipFloat.fromFp32(bits)
    val neg=c.negative;val sig=c.significand;val e=c.exponent
    val isZero=sig===0.U
    val shiftRight=Mux(e>=0.S && e<23.S,(23.S-e).asUInt,0.U)(4,0)
    val intPart=Mux(e<0.S,zero,
      Mux(e<23.S,(sig>>shiftRight).pad(64),
        Mux(e<31.S,(sig.pad(64)<<(e-23.S).asUInt)(63,0),"h80000000".U(64.W))))
    val frac=Mux(e<0.S,!isZero,
      Mux(e<23.S,(sig&((1.U(64.W)<<shiftRight)-1.U)(63,0))=/=0.U,false.B))
    val magnitude=intPart+frac.asUInt
    def clampPos(v: UInt): SInt=Mux(v>"h7fffffff".U,"h7fffffff".U.asSInt,v(31,0).asSInt)
    def clampNeg(v: UInt): SInt=Mux(v>"h80000000".U,"h80000000".U.asSInt,(0.S(64.W)-v.pad(64).asSInt)(31,0).asSInt)
    (Mux(neg,clampNeg(magnitude),clampPos(intPart)),Mux(neg,clampNeg(intPart),clampPos(magnitude)))
  }

  private val viewportLegal=Fp32ViewportState.legal(request.viewport)
  private val boxLegal=request.validBox && PrimitiveBoundingBox.valid(request.box)
  private val sizeLegal=Mux(request.kind<2.U,finite(request.size)&& !request.size(31),true.B)
  private val invalid= !(viewportLegal && boxLegal && sizeLegal)
  private val floatX0=intToFp(Mux(request.viewport.x(31),(0.U-request.viewport.x.asUInt)(31,0),request.viewport.x.asUInt),request.viewport.x(31))
  private val floatY0=intToFp(Mux(request.viewport.y(31),(0.U-request.viewport.y.asUInt)(31,0),request.viewport.y.asUInt),request.viewport.y(31))
  private val floatW=intToFp(request.viewport.extentX,false.B)
  private val floatH=intToFp(request.viewport.extentY,false.B)
  private val vx=Mux(vertex(0),request.box.max(0),request.box.min(0))
  private val vy=Mux(vertex(1),request.box.max(1),request.box.min(1))
  private val vw=Mux(vertex(3),request.box.max(3),request.box.min(3))
  private val wNonPositive=vw(30,0)===0.U || vw(31)

  private def issue(kind: Int,a: UInt,b: UInt,c: UInt,d: Dest.Type,next: State.Type): Unit={
    pending:=0.U.asTypeOf(pending);pending.tag:=request.tag(7,0)
    if(kind==3){pending.operation:=Fp32Operation.div;pending.operandA:=a;pending.operandB:=b}
    else if(kind==0){pending.operation:=Fp32Operation.mul;pending.operandA:=a;pending.operandB:=b}
    else if(kind==1){pending.operation:=Fp32Operation.add;pending.operandB:=a;pending.operandC:=b}
    else{pending.operation:=Fp32Operation.fmadd;pending.operandA:=a;pending.operandB:=b;pending.operandC:=c}
    laneDiv:=(kind==3).B;dest:=d;nextState:=next;phase:=State.mathOffer
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.respond;io.out.bits:=result
  io.busy:=phase=/=State.idle
  when(phase=/=State.idle && phase=/=State.respond && io.cancel){cancelled:=true.B}
  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    status:=0.U;cancelled:=false.B;inexact:=false.B;vertex:=0.U
    minPx:="h7f800000".U;maxPx:="hff800000".U;minPy:="h7f800000".U;maxPy:="hff800000".U
    phase:=State.check
  }
  switch(phase){
    is(State.check){
      when(invalid){status:=1.U;phase:=State.finish}
        .otherwise{issue(0,floatW,half,zero,Dest.halfW,State.halfH)}
    }
    is(State.mathOffer){when(offerFire){phase:=State.mathWait}}
    is(State.mathWait){when(mathFire){
      switch(dest){
        is(Dest.halfW){halfW:=mathResult}
        is(Dest.halfH){halfH:=mathResult}
        is(Dest.originX){originX:=mathResult}
        is(Dest.originY){originY:=mathResult}
        is(Dest.halfSize){halfSize:=mathResult}
        is(Dest.qx){qx:=mathResult}
        is(Dest.qy){qy:=mathResult}
        is(Dest.px){px:=mathResult}
        is(Dest.py){py:=mathResult}
        is(Dest.leftX){loX:=mathResult}
        is(Dest.rightX){hiX:=mathResult}
        is(Dest.leftY){loY:=mathResult}
        is(Dest.rightY){hiY:=mathResult}
      }
      phase:=nextState
    }}
    is(State.halfH){issue(0,floatH,half,zero,Dest.halfH,State.originX)}
    is(State.originX){issue(1,floatX0,halfW,zero,Dest.originX,State.originY)}
    is(State.originY){issue(1,floatY0,halfH,zero,Dest.originY,State.halfSize)}
    is(State.halfSize){
      when(request.kind<2.U){issue(0,request.size,half,zero,Dest.halfSize,State.vertex)}
        .otherwise{halfSize:=zero;phase:=State.vertex}
    }
    is(State.vertex){
      when(wNonPositive){inexact:=true.B;phase:=State.accumulate}
        .otherwise{issue(3,vx,vw,zero,Dest.qx,State.divY)}
    }
    is(State.divY){issue(3,vy,vw,zero,Dest.qy,State.projX)}
    is(State.projX){issue(2,qx,halfW,originX,Dest.px,State.projY)}
    is(State.projY){issue(2,qy,halfH,originY,Dest.py,State.accumulate)}
    is(State.accumulate){
      when(!wNonPositive && finite(px) && finite(py)){
        minPx:=TypedBlendState.minimum(px,minPx);maxPx:=TypedBlendState.minimum(px,maxPx,true.B)
        minPy:=TypedBlendState.minimum(py,minPy);maxPy:=TypedBlendState.minimum(py,maxPy,true.B)
      }.otherwise{inexact:=true.B}
      when(vertex===15.U){phase:=State.edgeLeftX}.otherwise{vertex:=vertex+1.U;phase:=State.vertex}
    }
    is(State.edgeLeftX){
      when(inexact){issue(1,floatX0,halfSize^signFlip,zero,Dest.leftX,State.edgeRightX)}
        .otherwise{issue(1,minPx,halfSize^signFlip,zero,Dest.leftX,State.edgeRightX)}
    }
    is(State.edgeRightX){
      when(inexact){issue(1,originX,halfW,zero,Dest.rightX,State.edgeRightX2)}
        .otherwise{issue(1,maxPx,halfSize,zero,Dest.rightX,State.edgeLeftY)}
    }
    is(State.edgeRightX2){issue(1,hiX,halfSize,zero,Dest.rightX,State.edgeLeftY)}
    is(State.edgeLeftY){
      when(inexact){issue(1,floatY0,halfSize^signFlip,zero,Dest.leftY,State.edgeRightY)}
        .otherwise{issue(1,minPy,halfSize^signFlip,zero,Dest.leftY,State.edgeRightY)}
    }
    is(State.edgeRightY){
      when(inexact){issue(1,originY,halfH,zero,Dest.rightY,State.edgeRightY2)}
        .otherwise{issue(1,maxPy,halfSize,zero,Dest.rightY,State.finish)}
    }
    is(State.edgeRightY2){issue(1,hiY,halfSize,zero,Dest.rightY,State.finish)}
    is(State.finish){
      result.status:=status;result.cancelled:=cancelled||io.cancel
      val (xminS,_)=floorCeil(loX);val (_,xmaxS)=floorCeil(hiX)
      val (yminS,_)=floorCeil(loY);val (_,ymaxS)=floorCeil(hiY)
      result.xmin:=Mux(status===0.U,xminS,0.S);result.ymin:=Mux(status===0.U,yminS,0.S)
      result.xmax:=Mux(status===0.U,xmaxS,0.S);result.ymax:=Mux(status===0.U,ymaxS,0.S)
      result.exact:=status===0.U && !inexact
      phase:=State.respond
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
