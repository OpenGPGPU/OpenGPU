package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.core.execute.fpu.{Fp32Request,Fp32Operation}

private class TypedStreamBlendOperands extends Bundle {
  val original=Vec(4,UInt(32.W));val source=Vec(4,UInt(32.W));val destination=Vec(4,UInt(32.W))
  val state=new TypedBlendState;val tag=UInt(8.W);val error=Bool();val normalized=Bool();val floating=Bool()
}
private class TypedStreamBlendProducts extends Bundle {
  val operands=new TypedStreamBlendOperands;val factors=Vec(8,UInt(32.W));val status=UInt(5.W)
}
private class TypedStreamBlendCombination extends Bundle {
  val original=Vec(4,UInt(32.W));val extrema=Vec(4,UInt(32.W));val equations=Vec(4,UInt(3.W))
  val enabled=Bool();val error=Bool();val tag=UInt(8.W);val status=UInt(5.W);val normalized=Bool()
}

private class TypedStreamFactorPrepared extends Bundle {
  val requests=Vec(8,new Fp32Request(8));val owner=new TypedStreamBlendOperands
}
private class TypedStreamProductsPrepared extends Bundle {
  val requests=Vec(8,new Fp32Request(8));val owner=new TypedStreamBlendProducts
}
private class TypedStreamCombinationPrepared extends Bundle {
  val requests=Vec(4,new Fp32Request(8));val owner=new TypedStreamBlendCombination
}

/** One typed RGBA vector per cycle after warm-up, with separate RNE operations.
  * Eight factor inversions, eight products and four combinations have distinct
  * pipelined FP32 hardware. Full result credits and metadata queues absorb
  * backpressure. Cancellation drains accepted work and preserves an already
  * offered result; fresh admission resumes only after that cancelled cohort.
  */
class StreamingTypedColorBlend extends Module {
  val io=IO(new TypedColorBlendIO)
  private val decoder=Module(new StreamingTypedColorDecoder)
  private val decodeOwners=Module(new Queue(new TypedStreamBlendOperands,4))
  private val decoded=Module(new Queue(new TypedStreamBlendOperands,2))
  private val factorPrepared=Module(new Queue(new TypedStreamFactorPrepared,2))
  private val inverse=Module(new BlendArithmeticVector(8))
  private val inverseOwners=Module(new Queue(new TypedStreamBlendOperands,16))
  private val productsPrepared=Module(new Queue(new TypedStreamProductsPrepared,2))
  private val products=Module(new BlendArithmeticVector(8))
  private val productOwners=Module(new Queue(new TypedStreamBlendProducts,16))
  private val combinationPrepared=Module(new Queue(new TypedStreamCombinationPrepared,2))
  private val combination=Module(new BlendArithmeticVector(4))
  private val combinationOwners=Module(new Queue(new TypedStreamBlendCombination,16))
  private val outstanding=RegInit(0.U(7.W));private val cancelled=RegInit(false.B)
  private val output=Reg(new TypedColorBlendResult);private val outputValid=RegInit(false.B)
  private val outputReady= !outputValid || io.out.ready
  private val admission= !io.cancel && !cancelled && outstanding<64.U
  io.in.ready:=decoder.io.in.ready && decodeOwners.io.enq.ready && admission
  decoder.io.in.valid:=io.in.valid && decodeOwners.io.enq.ready && admission
  decoder.io.in.bits.value:=io.in.bits.destination;decoder.io.in.bits.format:=io.in.bits.format
  decoder.io.in.bits.tag:=io.in.bits.tag
  decodeOwners.io.enq.valid:=io.in.valid && decoder.io.in.ready && admission
  decodeOwners.io.enq.bits:=0.U.asTypeOf(decodeOwners.io.enq.bits)
  decodeOwners.io.enq.bits.original:=io.in.bits.source;decodeOwners.io.enq.bits.source:=io.in.bits.source
  decodeOwners.io.enq.bits.state:=io.in.bits.state;decodeOwners.io.enq.bits.tag:=io.in.bits.tag
  decodeOwners.io.enq.bits.normalized:=TypedColorFormat.encoding(io.in.bits.format)===0.U || TypedColorFormat.encoding(io.in.bits.format)===5.U
  decodeOwners.io.enq.bits.floating:=TypedColorFormat.kind(io.in.bits.format)===0.U
  decodeOwners.io.enq.bits.error:= !TypedColorFormat.valid(io.in.bits.format) || TypedBlendState.invalid(io.in.bits.state)
  decoded.io.enq.valid:=decoder.io.out.valid && decodeOwners.io.deq.valid
  decoder.io.out.ready:=decoded.io.enq.ready && decodeOwners.io.deq.valid
  decodeOwners.io.deq.ready:=decoded.io.enq.ready && decoder.io.out.valid
  decoded.io.enq.bits:=decodeOwners.io.deq.bits
  decoded.io.enq.bits.destination:=decoder.io.out.bits.rgba
  for(c<-0 until 4){
    val p=decodeOwners.io.deq.bits
    decoded.io.enq.bits.source(c):=Mux(p.normalized,TypedBlendState.clamp(p.source(c)),p.source(c))
    decoded.io.enq.bits.state.constant(c):=Mux(p.normalized,TypedBlendState.clamp(p.state.constant(c)),p.state.constant(c))
  }
  when(decoded.io.enq.fire){assert(decoder.io.out.bits.tag===decodeOwners.io.deq.bits.tag,"typed decoder lost its blend owner")}
  io.out.valid:=outputValid;io.out.bits:=output;io.busy:=outstanding=/=0.U
  when(io.cancel && outstanding=/=0.U){cancelled:=true.B}
  when(outstanding===0.U && !io.cancel){cancelled:=false.B}
  switch(Cat(io.in.fire,io.out.fire)){
    is("b10".U){outstanding:=outstanding+1.U}
    is("b01".U){outstanding:=outstanding-1.U}
  }
  private def equation(p: TypedStreamBlendOperands,c: Int): UInt=if(c==3)p.state.equationAlpha else p.state.equationRGB
  private def selector(p: TypedStreamBlendOperands,c: Int,second: Boolean): UInt=
    if(c==3){if(second)p.state.dstAlpha else p.state.srcAlpha}else{if(second)p.state.dstRGB else p.state.srcRGB}
  private def factorBase(p: TypedStreamBlendOperands,c: Int,second: Boolean): UInt={
    val f=selector(p,c,second)
    MuxLookup(f,0.U(32.W))(Seq(1.U->"h3f800000".U,2.U->p.source(c),3.U->p.source(c),4.U->p.source(3),5.U->p.source(3),
      6.U->p.destination(c),7.U->p.destination(c),8.U->p.destination(3),9.U->p.destination(3),10.U->p.destination(3),
      11.U->p.state.constant(c),12.U->p.state.constant(c),13.U->p.state.constant(3),14.U->p.state.constant(3)))
  }
  private def inverted(p: TypedStreamBlendOperands,c: Int,second: Boolean): Bool={
    val f=selector(p,c,second)
    (f===3.U || f===5.U || f===7.U || f===9.U || f===10.U || f===12.U || f===14.U) && (if(c==3)f=/=10.U else true.B)
  }
  private def active(p: TypedStreamBlendOperands,c: Int): Bool=p.state.enable && p.floating && !p.error && equation(p,c)<3.U
  private def request(value: Fp32Request,a: UInt,b: UInt,multiply: Boolean,tag: UInt): Unit={
    value:=0.U.asTypeOf(value);value.operation:=(if(multiply)Fp32Operation.mul else Fp32Operation.add)
    value.operandA:=a;value.operandB:=(if(multiply)b else a);value.operandC:=(if(multiply)0.U else b);value.tag:=tag
  }
  // Decode is registered before any factor selection or FP32 arithmetic.
  factorPrepared.io.enq.valid:=decoded.io.deq.valid;factorPrepared.io.enq.bits.owner:=decoded.io.deq.bits
  decoded.io.deq.ready:=factorPrepared.io.enq.ready
  inverse.io.in.valid:=factorPrepared.io.deq.valid && inverseOwners.io.enq.ready
  inverse.io.in.bits:=factorPrepared.io.deq.bits.requests
  inverseOwners.io.enq.valid:=factorPrepared.io.deq.valid && inverse.io.in.ready
  inverseOwners.io.enq.bits:=factorPrepared.io.deq.bits.owner
  factorPrepared.io.deq.ready:=inverse.io.in.ready && inverseOwners.io.enq.ready
  for(c<-0 until 4;second<-Seq(false,true)){
    val p=decoded.io.deq.bits;val index=2*c+(if(second)1 else 0)
    request(factorPrepared.io.enq.bits.requests(index),"h3f800000".U,factorBase(p,c,second)^"h80000000".U,false,p.tag)
  }
  private val factorValid=inverse.io.out.valid && inverseOwners.io.deq.valid
  private val factorReady=productsPrepared.io.enq.ready
  inverse.io.out.ready:=factorReady && inverseOwners.io.deq.valid
  inverseOwners.io.deq.ready:=factorReady && inverse.io.out.valid
  productsPrepared.io.enq.valid:=factorValid
  productsPrepared.io.enq.bits.owner.operands:=inverseOwners.io.deq.bits
  products.io.in.valid:=productsPrepared.io.deq.valid && productOwners.io.enq.ready
  products.io.in.bits:=productsPrepared.io.deq.bits.requests
  productOwners.io.enq.valid:=productsPrepared.io.deq.valid && products.io.in.ready
  productOwners.io.enq.bits:=productsPrepared.io.deq.bits.owner
  productsPrepared.io.deq.ready:=products.io.in.ready && productOwners.io.enq.ready
  private val inverseStatus=Wire(Vec(8,UInt(5.W)))
  for(c<-0 until 4;second<-Seq(false,true)){
    val p=inverseOwners.io.deq.bits;val index=2*c+(if(second)1 else 0);val f=selector(p,c,second)
    val inv=inverted(p,c,second)
    val raw=Mux(inv,inverse.io.out.bits(index).result,factorBase(p,c,second))
    val selected=Mux(f===10.U,(if(c==3)"h3f800000".U else TypedBlendState.minimum(p.source(3),raw)),raw)
    val factor=Mux(p.normalized,TypedBlendState.clamp(selected),selected)
    productsPrepared.io.enq.bits.owner.factors(index):=factor
    request(productsPrepared.io.enq.bits.requests(index),(if(second)p.destination(c)else p.source(c)),factor,true,p.tag)
    inverseStatus(index):=Mux(active(p,c) && inv,inverse.io.out.bits(index).status,0.U)
    when(productsPrepared.io.enq.fire){assert(inverse.io.out.bits(index).tag===p.tag)}
  }
  productsPrepared.io.enq.bits.owner.status:=inverseStatus.reduce(_|_)
  private val productValid=products.io.out.valid && productOwners.io.deq.valid
  private val productReady=combinationPrepared.io.enq.ready
  products.io.out.ready:=productReady && productOwners.io.deq.valid
  productOwners.io.deq.ready:=productReady && products.io.out.valid
  combinationPrepared.io.enq.valid:=productValid
  combination.io.in.valid:=combinationPrepared.io.deq.valid && combinationOwners.io.enq.ready
  combination.io.in.bits:=combinationPrepared.io.deq.bits.requests
  combinationOwners.io.enq.valid:=combinationPrepared.io.deq.valid && combination.io.in.ready
  combinationOwners.io.enq.bits:=combinationPrepared.io.deq.bits.owner
  combinationPrepared.io.deq.ready:=combination.io.in.ready && combinationOwners.io.enq.ready
  private val productStatus=Wire(Vec(8,UInt(5.W)))
  private val p=productOwners.io.deq.bits.operands
  combinationPrepared.io.enq.bits.owner.original:=p.original;combinationPrepared.io.enq.bits.owner.enabled:=p.state.enable && p.floating
  combinationPrepared.io.enq.bits.owner.normalized:=p.normalized
  combinationPrepared.io.enq.bits.owner.error:=p.error;combinationPrepared.io.enq.bits.owner.tag:=p.tag
  for(c<-0 until 4){
    val terms=Wire(Vec(2,UInt(32.W)))
    for(second<-0 until 2){
      val index=2*c+second;val factor=productOwners.io.deq.bits.factors(index)
      val zero= !factor(30,0).orR;val one=factor==="h3f800000".U
      terms(second):=Mux(zero,0.U,Mux(one,(if(second==1)p.destination(c)else p.source(c)),products.io.out.bits(index).result))
      productStatus(index):=Mux(active(p,c) && !zero && !one,products.io.out.bits(index).status,0.U)
      when(combinationPrepared.io.enq.fire){assert(products.io.out.bits(index).tag===p.tag)}
    }
    val eq=equation(p,c)
    val a=Mux(eq===2.U,terms(1),terms(0))
    val b=Mux(eq===1.U,terms(1)^"h80000000".U,Mux(eq===2.U,terms(0)^"h80000000".U,terms(1)))
    request(combinationPrepared.io.enq.bits.requests(c),a,b,false,p.tag)
    combinationPrepared.io.enq.bits.owner.extrema(c):=TypedBlendState.minimum(p.source(c),p.destination(c),eq===4.U)
    combinationPrepared.io.enq.bits.owner.equations(c):=eq
  }
  combinationPrepared.io.enq.bits.owner.status:=productOwners.io.deq.bits.status|productStatus.reduce(_|_)
  private val combinationValid=combination.io.out.valid && combinationOwners.io.deq.valid
  combination.io.out.ready:=outputReady && combinationOwners.io.deq.valid
  combinationOwners.io.deq.ready:=outputReady && combination.io.out.valid
  private val finalStatus=Wire(Vec(4,UInt(5.W)))
  private val owner=combinationOwners.io.deq.bits
  for(c<-0 until 4)finalStatus(c):=Mux(owner.enabled && !owner.error && owner.equations(c)<3.U,combination.io.out.bits(c).status,0.U)
  when(outputReady){
    outputValid:=combinationValid
    when(combinationValid){
      output:=0.U.asTypeOf(output);output.tag:=owner.tag;output.status:=Mux(owner.error,1.U,0.U)
      output.cancelled:=cancelled || io.cancel
      output.arithmeticStatus:=Mux(owner.enabled && !owner.error,owner.status|finalStatus.reduce(_|_),0.U)
      for(c<-0 until 4){
        val raw=Mux(owner.equations(c)>=3.U,owner.extrema(c),combination.io.out.bits(c).result)
        val value=Mux(owner.normalized,TypedBlendState.clamp(raw),raw)
        output.rgba(c):=Mux(owner.error,0.U,Mux(owner.enabled,value,owner.original(c)))
        assert(combination.io.out.bits(c).tag===owner.tag)
      }
    }
  }
  when(io.out.fire){assert(outstanding=/=0.U,"typed streaming blend retired without ownership")}
  when(outstanding===0.U){assert(!decoder.io.busy && !decodeOwners.io.deq.valid && !decoded.io.deq.valid && !inverse.io.busy && !products.io.busy && !combination.io.busy && !outputValid,
    "typed streaming blend released arithmetic ownership before drain")}
}
