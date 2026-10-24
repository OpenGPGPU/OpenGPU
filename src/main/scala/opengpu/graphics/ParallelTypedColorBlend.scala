package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.Instantiate
import opengpu.core.execute.fpu.{Fp32FmaLane,Fp32Operation,Fp32Request}

private class DecodedTypedBlend extends Bundle {
  val source=Vec(4,UInt(32.W));val destination=Vec(4,UInt(32.W))
  val state=new TypedBlendState;val normalized=Bool();val tag=UInt(8.W)
}

/** One component owns its FMA until all separately rounded operations drain. */
private class TypedBlendComponent(component: Int) extends Module {
  require(component>=0 && component<4)
  val io=IO(new Bundle {
    val start=Input(Bool());val request=Input(new DecodedTypedBlend)
    val value=Output(UInt(32.W));val arithmeticStatus=Output(UInt(5.W))
    val done=Output(Bool());val drained=Output(Bool())
  })
  private object State extends ChiselEnum {
    val idle,evaluate,factor,saveFactor,sourceProduct,destinationProduct,combine,saveResult,offer,waitMath,done=Value
  }
  private val phase=RegInit(State.idle);private val next=Reg(State())
  private val second=RegInit(false.B);private val sf=Reg(UInt(32.W));private val df=Reg(UInt(32.W))
  private val st=Reg(UInt(32.W));private val dt=Reg(UInt(32.W));private val value=Reg(UInt(32.W))
  private val status=RegInit(0.U(5.W));private val coreRequest=Reg(new Fp32Request(8))
  private val math=Instantiate(new Fp32FmaLane(8))
  math.io.flush:=false.B;math.io.in.valid:=phase===State.offer;math.io.in.bits:=coreRequest
  math.io.out.ready:=phase===State.waitMath
  private val p=io.request;private val c=component
  private val equation=if(c==3)p.state.equationAlpha else p.state.equationRGB
  private val source=p.source(c);private val destination=p.destination(c)
  private val sourceFactor=if(c==3)p.state.srcAlpha else p.state.srcRGB
  private val destinationFactor=if(c==3)p.state.dstAlpha else p.state.dstRGB
  private val f=Mux(second,destinationFactor,sourceFactor)
  private def arithmetic(a: UInt,b: UInt,multiply: Boolean,continuation: State.Type): Unit={
    coreRequest:=0.U.asTypeOf(coreRequest);coreRequest.tag:=p.tag
    coreRequest.operation:=(if(multiply)Fp32Operation.mul else Fp32Operation.add)
    coreRequest.operandA:=a;coreRequest.operandB:=(if(multiply)b else a)
    coreRequest.operandC:=(if(multiply)0.U else b);next:=continuation;phase:=State.offer
  }
  io.value:=Mux(p.normalized,TypedBlendState.clamp(value),value)
  io.arithmeticStatus:=status;io.done:=phase===State.done;io.drained:= !math.io.busy
  switch(phase){
    is(State.evaluate){
      when(equation>=3.U){value:=TypedBlendState.minimum(source,destination,equation===4.U);phase:=State.saveResult}
        .otherwise{second:=false.B;phase:=State.factor}
    }
    is(State.factor){
      val base=MuxLookup(f,0.U(32.W))(Seq(1.U->"h3f800000".U,2.U->source,3.U->source,4.U->p.source(3),5.U->p.source(3),
        6.U->destination,7.U->destination,8.U->p.destination(3),9.U->p.destination(3),10.U->p.destination(3),
        11.U->p.state.constant(c),12.U->p.state.constant(c),13.U->p.state.constant(3),14.U->p.state.constant(3)))
      val inverse=f===3.U || f===5.U || f===7.U || f===9.U || f===10.U || f===12.U || f===14.U
      when(f===10.U && (c==3).B){value:="h3f800000".U;phase:=State.saveFactor}
        .elsewhen(inverse){arithmetic("h3f800000".U,base^"h80000000".U,false,State.saveFactor)}
        .otherwise{value:=base;phase:=State.saveFactor}
    }
    is(State.saveFactor){
      val selected=Mux(f===10.U && (c!=3).B,TypedBlendState.minimum(p.source(3),value),value)
      val factor=Mux(p.normalized,TypedBlendState.clamp(selected),selected)
      when(second){df:=factor;phase:=State.sourceProduct}.otherwise{sf:=factor;second:=true.B;phase:=State.factor}
    }
    is(State.sourceProduct){
      when(!sf(30,0).orR){st:=0.U;phase:=State.destinationProduct}
        .elsewhen(sf==="h3f800000".U){st:=source;phase:=State.destinationProduct}
        .otherwise{arithmetic(source,sf,true,State.destinationProduct)}
    }
    is(State.destinationProduct){
      when(!df(30,0).orR){dt:=0.U;phase:=State.combine}
        .elsewhen(df==="h3f800000".U){dt:=destination;phase:=State.combine}
        .otherwise{arithmetic(destination,df,true,State.combine)}
    }
    is(State.combine){
      arithmetic(Mux(equation===2.U,dt,st),Mux(equation===1.U,dt^"h80000000".U,
        Mux(equation===2.U,st^"h80000000".U,dt)),false,State.saveResult)
    }
    is(State.saveResult){phase:=State.done}
    is(State.offer){when(math.io.in.fire){phase:=State.waitMath}}
    is(State.waitMath){when(math.io.out.fire){
      assert(math.io.out.bits.tag===p.tag);value:=math.io.out.bits.result
      when(next===State.destinationProduct){st:=math.io.out.bits.result}
      when(next===State.combine){dt:=math.io.out.bits.result}
      status:=status|math.io.out.bits.status;phase:=next
    }}
  }
  when(io.start){
    assert(!math.io.busy && (phase===State.idle || phase===State.done),"blend component rebound before drain")
    status:=0.U;phase:=State.evaluate
  }
  when(io.done){assert(!math.io.busy,"blend component completed before FMA drain")}
}

/** Four component arithmetic owners share one destination decoder. Products
  * and sums retain separate RNE operations and the serial service's shortcuts.
  * One request owns the group through its held result and cancellation drain.
  */
class ParallelTypedColorBlend extends Module {
  val io=IO(new TypedColorBlendIO)
  private object State extends ChiselEnum {val idle,check,decode,waitDecode,launch,run,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TypedColorBlendRequest)
  private val prepared=Reg(new DecodedTypedBlend);private val result=Reg(new TypedColorBlendResult)
  private val cancelled=RegInit(false.B);private val decoder=Module(new TypedColorDecoder)
  private val workers=Seq.tabulate(4)(c=>Module(new TypedBlendComponent(c)))
  private val normalized=TypedColorFormat.encoding(request.format)===0.U || TypedColorFormat.encoding(request.format)===5.U
  decoder.io.in.valid:=phase===State.decode;decoder.io.in.bits.value:=request.destination
  decoder.io.in.bits.format:=request.format;decoder.io.in.bits.tag:=request.tag
  decoder.io.out.ready:=phase===State.waitDecode
  for(w<-workers){w.io.start:=phase===State.launch;w.io.request:=prepared}
  io.in.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.out.valid:=phase===State.respond;io.out.bits:=result
  when(phase=/=State.idle && phase=/=State.respond && io.cancel){cancelled:=true.B}
  when(io.in.fire){
    request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag
    cancelled:=false.B;phase:=State.check
  }
  switch(phase){
    is(State.check){
      result.cancelled:=cancelled || io.cancel
      when(!TypedColorFormat.valid(request.format) || TypedBlendState.invalid(request.state)){result.status:=1.U;phase:=State.respond}
        .elsewhen(!request.state.enable || TypedColorFormat.kind(request.format)=/=0.U){result.rgba:=request.source;phase:=State.respond}
        .otherwise{phase:=State.decode}
    }
    is(State.decode){when(decoder.io.in.fire){phase:=State.waitDecode}}
    is(State.waitDecode){when(decoder.io.out.fire){
      assert(decoder.io.out.bits.status===0.U && decoder.io.out.bits.tag===request.tag)
      prepared.destination:=decoder.io.out.bits.rgba;prepared.state:=request.state
      prepared.normalized:=normalized;prepared.tag:=request.tag
      for(c<-0 until 4){
        prepared.source(c):=Mux(normalized,TypedBlendState.clamp(request.source(c)),request.source(c))
        prepared.state.constant(c):=Mux(normalized,TypedBlendState.clamp(request.state.constant(c)),request.state.constant(c))
      }
      phase:=State.launch
    }}
    is(State.launch){phase:=State.run}
    is(State.run){when(workers.map(_.io.done).reduce(_ && _)){
      for(c<-0 until 4)result.rgba(c):=workers(c).io.value
      result.arithmeticStatus:=workers.map(_.io.arithmeticStatus).reduce(_|_)
      result.cancelled:=cancelled || io.cancel;phase:=State.respond
    }}
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
  when(io.out.valid){assert(!decoder.io.busy && workers.map(_.io.drained).reduce(_ && _),
    "parallel blend completion escaped decoder/arithmetic drain")}
}
