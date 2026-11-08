package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32DivLane,Fp32FmaLane,Fp32Operation,Fp32Request}

/** Non-separable KHR advanced blend equations on premultiplied colors.
  *
  * Supports HUE, SATURATION, COLOR and LUMINOSITY using the extension's
  * Lum/Sat/SetSat/SetLum/ClipColor helpers. Three FMA lanes and three divide
  * lanes evaluate the RGB triplet in parallel; scalars use lane zero. The
  * KHR composition f*As*Ad + Rs*(1-Ad) + Rd*(1-As) and alpha As+Ad-As*Ad are
  * formed after ClipColor. Results are held until accepted and accepted work
  * completes after cancellation.
  */
object TypedAdvancedHslEquation {
  val hue=0;val saturation=1;val color=2;val luminosity=3
  val supported=4
}

class TypedAdvancedHslBlendRequest extends Bundle {
  val source=Vec(4,UInt(32.W));val destination=Vec(4,UInt(32.W))
  val equation=UInt(2.W);val tag=UInt(8.W)
}

class TypedAdvancedHslBlend(tagWidth: Int=8) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedAdvancedHslBlendRequest));val out=Decoupled(new TypedAdvancedBlendResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,check,offer,mathWait,divCs,divCd,dotCsMul,dotCsAdd1,dotCsAdd2,dotCdMul,dotCdAdd1,dotCdAdd2,
      satCsPrep,satCsSub,satCdPrep,satCdSub,sel,selCopy,satMin,satSb,satSub,satMul,satDiv,satZero,
      dotVMul,dotVAdd1,dotVAdd2,lumDiff,addLum,dotWMul,dotWAdd1,dotWAdd2,clipPrep,
      caDen,caDiv,caSel,caA1,caA2,caA3,cbNum,cbDen,cbDiv,cbSel,cbB1,cbB2,cbB3,
      compP0,compP1,compP2,compT0,compT1,compT2,compSum1,compSum2,compAlpha1,compAlpha2,
      finish,respond=Value
  }
  private val phase=RegInit(State.idle)
  private val request=Reg(new TypedAdvancedHslBlendRequest)
  private val result=Reg(new TypedAdvancedBlendResult)
  private val status=RegInit(0.U(2.W));private val cancelled=RegInit(false.B)
  private val nextState=Reg(State());private val laneDiv=RegInit(false.B);private val vecTarget=RegInit(true.B)
  private val target=Reg(UInt(5.W));private val pending=Reg(Vec(3,new Fp32Request(tagWidth)));private val validMask=RegInit(0.U(3.W))
  private val remaining=RegInit(0.U(3.W));private val captured=Reg(Vec(3,UInt(32.W)))
  private val vRegs=Reg(Vec(10,Vec(3,UInt(32.W))));private val sRegs=Reg(Vec(18,UInt(32.W)))

  private val zero=0.U(32.W);private val one="h3f800000".U(32.W);private val threeTenths="h3e99999a".U(32.W)
  private val fiftyNineHundredths="h3f170a3d".U(32.W);private val elevenHundredths="h3de147ae".U(32.W)
  private def neg(x: UInt): UInt=x^"h80000000".U
  private def nonZero(x: UInt): UInt=Mux(x(30,0)===0.U,one,x)
  private def bcast(x: UInt): Vec[UInt]=VecInit(Seq.fill(3)(x))
  private val as=request.source(3);private val ad=request.destination(3)
  private val vCs: Int=0;private val vCd: Int=1;private val vBase: Int=2;private val vV: Int=3
  private val vW: Int=4;private val vTmp: Int=5;private val vT0: Int=6;private val vT1: Int=7
  private val vT2: Int=8;private val vRes: Int=9
  private val sLumCs: Int=0;private val sLumCd: Int=1;private val sSatCs: Int=2;private val sSatCd: Int=3
  private val sLumV: Int=4;private val sL: Int=5;private val sMn: Int=6;private val sMx: Int=7
  private val sFA: Int=8;private val sFB: Int=9;private val sP0: Int=10;private val sP1: Int=11
  private val sP2: Int=12;private val sAlpha: Int=13;private val sTmp: Int=14;private val sTmp2: Int=15
  private val sDiv: Int=16;private val sSatBase: Int=17

  private val fma=VecInit(Seq.fill(3)(Module(new Fp32FmaLane(tagWidth)).io))
  private val dv=VecInit(Seq.fill(3)(Module(new Fp32DivLane(tagWidth)).io))
  fma.foreach(_.flush:=false.B);dv.foreach(_.flush:=false.B)
  private def issue(kind: Int,vec: Boolean,a: Seq[UInt],b: Seq[UInt],dest: Int,next: State.Type): Unit={
    for(c<-0 until 3){
      val i=if(vec)c else 0
      val p=Wire(new Fp32Request(tagWidth));p:=0.U.asTypeOf(p);p.tag:=request.tag
      if(kind==3){p.operation:=Fp32Operation.div;p.operandA:=a(i);p.operandB:=b(i)}
      else if(kind==0){p.operation:=Fp32Operation.mul;p.operandA:=a(i);p.operandB:=b(i)}
      else{
        p.operation:=Fp32Operation.add;p.operandB:=a(i)
        val addend=if(kind==2){b(i)^"h80000000".U}else{b(i)}
        p.operandC:=addend
      }
      pending(c):=p
    }
    validMask:=Mux(vec.B,7.U(3.W),1.U(3.W));remaining:=Mux(vec.B,7.U(3.W),1.U(3.W))
    target:=dest.U;vecTarget:=vec.B;laneDiv:=(kind==3).B;nextState:=next;phase:=State.offer
  }
  private val laneFire=(0 until 3).map{c=>Mux(laneDiv,dv(c).out.fire,fma(c).out.fire)}
  private val fireBits=VecInit((0 until 3).map{c=>laneFire(c)&&remaining(c)}).asUInt
  private val afterFire=remaining & (~fireBits)(2,0)
  private val offerFired=(0 until 3).map{c=>
    Mux(validMask(c),Mux(laneDiv,dv(c).in.fire,fma(c).in.fire),true.B)}.reduce(_&&_)

  for(c<-0 until 3){
    fma(c).in.valid:=phase===State.offer && !laneDiv && validMask(c)
    dv(c).in.valid:=phase===State.offer && laneDiv && validMask(c)
    fma(c).in.bits:=pending(c);dv(c).in.bits:=pending(c)
    fma(c).out.ready:=phase===State.mathWait && !laneDiv && remaining(c)
    dv(c).out.ready:=phase===State.mathWait && laneDiv && remaining(c)
  }
  io.in.ready:=phase===State.idle && !io.cancel;io.out.valid:=phase===State.respond;io.out.bits:=result
  io.busy:=phase=/=State.idle
  when(phase=/=State.idle && phase=/=State.respond && io.cancel){cancelled:=true.B}
  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    status:=0.U;cancelled:=false.B;phase:=State.check
  }
  private def scalarMax(v: Vec[UInt]): UInt=v.reduce((a,b)=>TypedBlendState.minimum(a,b,true.B))
  private def scalarMin(v: Vec[UInt]): UInt=v.reduce((a,b)=>TypedBlendState.minimum(a,b))
  private val mode=request.equation
  private val satEnabled=mode<=TypedAdvancedHslEquation.saturation.U
  private val satTarget=Mux(mode===TypedAdvancedHslEquation.hue.U,sRegs(sSatCd),sRegs(sSatCs))
  private val lumTarget=Mux(mode===TypedAdvancedHslEquation.luminosity.U,sRegs(sLumCs),sRegs(sLumCd))
  switch(phase){
    is(State.check){
      when(request.equation>=TypedAdvancedHslEquation.supported.U){status:=1.U;phase:=State.finish}
        .otherwise{phase:=State.divCs}
    }
    is(State.divCs){issue(3,true,(0 until 3).map(c=>request.source(c)),Seq.fill(3)(nonZero(as)),vCs,State.divCd)}
    is(State.divCd){issue(3,true,(0 until 3).map(c=>request.destination(c)),Seq.fill(3)(nonZero(ad)),vCd,State.dotCsMul)}
    is(State.dotCsMul){issue(0,true,Seq(threeTenths,fiftyNineHundredths,elevenHundredths),(0 until 3).map(c=>vRegs(vCs)(c)),vTmp,State.dotCsAdd1)}
    is(State.dotCsAdd1){issue(1,false,Seq(vRegs(vTmp)(0)),Seq(vRegs(vTmp)(1)),sTmp,State.dotCsAdd2)}
    is(State.dotCsAdd2){issue(1,false,Seq(sRegs(sTmp)),Seq(vRegs(vTmp)(2)),sLumCs,State.dotCdMul)}
    is(State.dotCdMul){issue(0,true,Seq(threeTenths,fiftyNineHundredths,elevenHundredths),(0 until 3).map(c=>vRegs(vCd)(c)),vTmp,State.dotCdAdd1)}
    is(State.dotCdAdd1){issue(1,false,Seq(vRegs(vTmp)(0)),Seq(vRegs(vTmp)(1)),sTmp,State.dotCdAdd2)}
    is(State.dotCdAdd2){issue(1,false,Seq(sRegs(sTmp)),Seq(vRegs(vTmp)(2)),sLumCd,State.satCsPrep)}
    is(State.satCsPrep){sRegs(sMn):=scalarMin(vRegs(vCs));sRegs(sMx):=scalarMax(vRegs(vCs));phase:=State.satCsSub}
    is(State.satCsSub){issue(2,false,Seq(sRegs(sMx)),Seq(sRegs(sMn)),sSatCs,State.satCdPrep)}
    is(State.satCdPrep){sRegs(sMn):=scalarMin(vRegs(vCd));sRegs(sMx):=scalarMax(vRegs(vCd));phase:=State.satCdSub}
    is(State.satCdSub){issue(2,false,Seq(sRegs(sMx)),Seq(sRegs(sMn)),sSatCd,State.sel)}
    is(State.sel){
      vRegs(vBase):=Mux(mode===TypedAdvancedHslEquation.hue.U||mode===TypedAdvancedHslEquation.color.U,
        vRegs(vCs),vRegs(vCd))
      phase:=Mux(satEnabled,State.satMin,State.selCopy)
    }
    is(State.selCopy){vRegs(vV):=vRegs(vBase);phase:=State.dotVMul}
    is(State.satMin){sRegs(sMn):=scalarMin(vRegs(vBase));sRegs(sMx):=scalarMax(vRegs(vBase));phase:=State.satSb}
    is(State.satSb){issue(2,false,Seq(sRegs(sMx)),Seq(sRegs(sMn)),sSatBase,State.satSub)}
    is(State.satSub){issue(2,true,(0 until 3).map(c=>vRegs(vBase)(c)),Seq.fill(3)(sRegs(sMn)),vTmp,State.satMul)}
    is(State.satMul){issue(0,true,(0 until 3).map(c=>vRegs(vTmp)(c)),Seq.fill(3)(satTarget),vTmp,State.satDiv)}
    is(State.satDiv){issue(3,true,(0 until 3).map(c=>vRegs(vTmp)(c)),Seq.fill(3)(nonZero(sRegs(sSatBase))),vV,State.satZero)}
    is(State.satZero){when(sRegs(sSatBase)(30,0)===0.U){vRegs(vV):=0.U.asTypeOf(vRegs(vV))};phase:=State.dotVMul}
    is(State.dotVMul){issue(0,true,Seq(threeTenths,fiftyNineHundredths,elevenHundredths),(0 until 3).map(c=>vRegs(vV)(c)),vTmp,State.dotVAdd1)}
    is(State.dotVAdd1){issue(1,false,Seq(vRegs(vTmp)(0)),Seq(vRegs(vTmp)(1)),sTmp,State.dotVAdd2)}
    is(State.dotVAdd2){issue(1,false,Seq(sRegs(sTmp)),Seq(vRegs(vTmp)(2)),sLumV,State.lumDiff)}
    is(State.lumDiff){issue(2,false,Seq(lumTarget),Seq(sRegs(sLumV)),sTmp,State.addLum)}
    is(State.addLum){issue(1,true,(0 until 3).map(c=>vRegs(vV)(c)),Seq.fill(3)(sRegs(sTmp)),vW,State.dotWMul)}
    is(State.dotWMul){issue(0,true,Seq(threeTenths,fiftyNineHundredths,elevenHundredths),(0 until 3).map(c=>vRegs(vW)(c)),vTmp,State.dotWAdd1)}
    is(State.dotWAdd1){issue(1,false,Seq(vRegs(vTmp)(0)),Seq(vRegs(vTmp)(1)),sTmp,State.dotWAdd2)}
    is(State.dotWAdd2){issue(1,false,Seq(sRegs(sTmp)),Seq(vRegs(vTmp)(2)),sL,State.clipPrep)}
    is(State.clipPrep){sRegs(sMn):=scalarMin(vRegs(vW));sRegs(sMx):=scalarMax(vRegs(vW));phase:=State.caDen}
    is(State.caDen){issue(2,false,Seq(sRegs(sL)),Seq(sRegs(sMn)),sTmp,State.caDiv)}
    is(State.caDiv){issue(3,false,Seq(sRegs(sL)),Seq(nonZero(sRegs(sTmp))),sDiv,State.caSel)}
    is(State.caSel){
      val negative=PrimitiveClipStage.less(sRegs(sMn),zero)
      sRegs(sFA):=Mux(negative && sRegs(sTmp)(30,0)=/=0.U,sRegs(sDiv),one)
      phase:=State.caA1
    }
    is(State.caA1){issue(2,true,(0 until 3).map(c=>vRegs(vW)(c)),Seq.fill(3)(sRegs(sL)),vTmp,State.caA2)}
    is(State.caA2){issue(0,true,(0 until 3).map(c=>vRegs(vTmp)(c)),Seq.fill(3)(sRegs(sFA)),vTmp,State.caA3)}
    is(State.caA3){issue(1,true,Seq.fill(3)(sRegs(sL)),(0 until 3).map(c=>vRegs(vTmp)(c)),vW,State.cbNum)}
    is(State.cbNum){issue(2,false,Seq(one),Seq(sRegs(sL)),sTmp,State.cbDen)}
    is(State.cbDen){issue(2,false,Seq(sRegs(sMx)),Seq(sRegs(sL)),sTmp2,State.cbDiv)}
    is(State.cbDiv){issue(3,false,Seq(sRegs(sTmp)),Seq(nonZero(sRegs(sTmp2))),sDiv,State.cbSel)}
    is(State.cbSel){
      val above=PrimitiveClipStage.less(one,sRegs(sMx))
      sRegs(sFB):=Mux(above && sRegs(sTmp2)(30,0)=/=0.U,sRegs(sDiv),one)
      phase:=State.cbB1
    }
    is(State.cbB1){issue(2,true,(0 until 3).map(c=>vRegs(vW)(c)),Seq.fill(3)(sRegs(sL)),vTmp,State.cbB2)}
    is(State.cbB2){issue(0,true,(0 until 3).map(c=>vRegs(vTmp)(c)),Seq.fill(3)(sRegs(sFB)),vTmp,State.cbB3)}
    is(State.cbB3){issue(1,true,Seq.fill(3)(sRegs(sL)),(0 until 3).map(c=>vRegs(vTmp)(c)),vW,State.compP0)}
    is(State.compP0){issue(0,false,Seq(as),Seq(ad),sP0,State.compP1)}
    is(State.compP1){issue(2,false,Seq(one),Seq(ad),sP1,State.compP2)}
    is(State.compP2){issue(2,false,Seq(one),Seq(as),sP2,State.compT0)}
    is(State.compT0){issue(0,true,(0 until 3).map(c=>vRegs(vW)(c)),Seq.fill(3)(sRegs(sP0)),vT0,State.compT1)}
    is(State.compT1){issue(0,true,(0 until 3).map(c=>request.source(c)),Seq.fill(3)(sRegs(sP1)),vT1,State.compT2)}
    is(State.compT2){issue(0,true,(0 until 3).map(c=>request.destination(c)),Seq.fill(3)(sRegs(sP2)),vT2,State.compSum1)}
    is(State.compSum1){issue(1,true,(0 until 3).map(c=>vRegs(vT0)(c)),(0 until 3).map(c=>vRegs(vT1)(c)),vT0,State.compSum2)}
    is(State.compSum2){issue(1,true,(0 until 3).map(c=>vRegs(vT0)(c)),(0 until 3).map(c=>vRegs(vT2)(c)),vRes,State.compAlpha1)}
    is(State.compAlpha1){issue(1,false,Seq(as),Seq(ad),sAlpha,State.compAlpha2)}
    is(State.compAlpha2){issue(2,false,Seq(sRegs(sAlpha)),Seq(sRegs(sP0)),sAlpha,State.finish)}
    is(State.offer){when(offerFired){phase:=State.mathWait}}
    is(State.mathWait){
      for(c<-0 until 3){
        when(remaining(c) && laneFire(c)){captured(c):=Mux(laneDiv,dv(c).out.bits.result,fma(c).out.bits.result)}
        when(remaining(c) && laneFire(c)){result.arithmeticStatus:=result.arithmeticStatus|
          Mux(laneDiv,dv(c).out.bits.status,fma(c).out.bits.status)}
      }
      when(afterFire===0.U){
        when(vecTarget){vRegs(target):=VecInit((0 until 3).map(c=>
          Mux(fireBits(c),Mux(laneDiv,dv(c).out.bits.result,fma(c).out.bits.result),captured(c))))}
          .otherwise{sRegs(target):=Mux(fireBits(0),Mux(laneDiv,dv(0).out.bits.result,fma(0).out.bits.result),captured(0))}
        remaining:=0.U
        phase:=nextState
      }.otherwise{remaining:=afterFire}
    }
    is(State.finish){
      result.status:=status;result.cancelled:=cancelled||io.cancel
      for(c<-0 until 3)result.rgba(c):=vRegs(vRes)(c)
      result.rgba(3):=sRegs(sAlpha)
      phase:=State.respond
    }
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
