package opengpu.graphics

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.Instantiate
import opengpu.core.execute.fpu.{Fp32DivLane,Fp32FmaLane,Fp32Operation,Fp32Request}

/** Clip arithmetic keeps FP32 significand precision and an extended exponent.
  * Finite nonzero value = (-1)^negative * significand * 2^(exponent-23).
  * Legal exponents [-512,511] cover finite FP32 clip distances, crossing ratios
  * and weighted endpoint contributions without intermediate FP32 range loss.
  * Zero has significand/exponent zero; special=1 is infinity, 2 is NaN.
  * This is an internal arithmetic representation, not a shader scalar format.
  */
class ClipFloat extends Bundle {
  val negative=Bool();val exponent=SInt(12.W);val significand=UInt(24.W);val special=UInt(2.W)
}
class ClipFloatPacked extends Bundle {val bits=UInt(32.W);val status=UInt(5.W)}
object ClipFloat {
  def zero: ClipFloat=0.U.asTypeOf(new ClipFloat)
  def legal(value: ClipFloat): Bool={
    val finite=value.special===0.U && value.exponent>=(-512).S && value.exponent<=511.S &&
      Mux(value.significand===0.U,value.exponent===0.S,value.significand(23))
    val inf=value.special===1.U && value.exponent===0.S && value.significand===0.U
    val nan=value.special===2.U && value.exponent===0.S && !value.significand(23) && value.significand(22,0).orR
    finite || inf || nan
  }
  def fromFp32(bits: UInt): ClipFloat={
    val out=Wire(new ClipFloat);val exponent=bits(30,23);val fraction=bits(22,0)
    val shift=PriorityEncoder(Reverse(fraction.pad(24)))
    out.negative:=bits(31);out.special:=Mux(exponent===255.U,Mux(fraction.orR,2.U,1.U),0.U)
    out.significand:=Mux(exponent===255.U,fraction.pad(24),
      Mux(exponent===0.U,(fraction.pad(24)<<shift)(23,0),Cat(1.U(1.W),fraction)))
    out.exponent:=Mux(exponent===0.U,(-126).S(12.W)-shift.zext.pad(12),exponent.zext.pad(12)-127.S(12.W))
    when(exponent===255.U || (exponent===0.U && !fraction.orR)){out.exponent:=0.S}
    out
  }
  /** RNE packing with gradual underflow and tininess detected after rounding.
    * The optional directed mode truncates underflow and saturates overflow.
    * The bounded 25-bit shift path never creates an exponent-sized shifter.
    * Callers retain the extended value when this convenience result overflows
    * or underflows; packing is not an intermediate clip computation.
    */
  def toFp32(value: ClipFloat,roundTowardZero: Bool=false.B,compactUnderflow: Boolean=false): ClipFloatPacked={
    val out=Wire(new ClipFloatPacked);out.status:=0.U
    val sub=value.exponent<(-126).S
    val shift=Mux(sub,Mux(value.exponent<(-150).S,25.U,((-126).S-value.exponent).asUInt),1.U)(4,0)
    val (rounded,inexact)=if(compactUnderflow){
      // Twenty-five low zeros retain every discarded bit even at shift25.
      // One alignment replaces the mask, half and wide remainder comparison.
      val aligned=Cat(value.significand,0.U(25.W))>>shift
      val truncated=aligned(48,25);val guard=aligned(24);val sticky=aligned(23,0).orR
      val rounded=truncated+&(!roundTowardZero && guard && (sticky || truncated(0)))
      (rounded,guard || sticky)
    }else{
      val truncated=value.significand>>shift
      val remainder=value.significand&((1.U(25.W)<<shift)-1.U)(23,0)
      val half=1.U(25.W)<<(shift-1.U)
      val rounded=truncated+&(!roundTowardZero && ((remainder>half) || (remainder===half && truncated(0))))
      (rounded,remainder.orR)
    }
    val exponent=(value.exponent+127.S).asUInt
    out.bits:=Cat(value.negative,exponent(7,0),value.significand(22,0))
    when(sub) {
      out.bits:=Cat(value.negative,rounded.pad(31)(30,0))
      out.status:=Cat(0.U(3.W),inexact && !rounded(23),inexact)
    }
    when(value.exponent>127.S){out.bits:=Mux(roundTowardZero,Cat(value.negative,"h7f7fffff".U(31.W)),Cat(value.negative,255.U(8.W),0.U(23.W)));out.status:=5.U}
    when(value.significand===0.U){out.bits:=Cat(value.negative,0.U(31.W));out.status:=0.U}
    when(value.special=/=0.U) {
      out.bits:=Cat(value.negative,255.U(8.W),value.significand(22,0));out.status:=0.U
    }
    out
  }
  /** Encode a normalized significand for the existing FP32 arithmetic lanes. */
  def coreBits(value: ClipFloat): UInt=Mux(value.special=/=0.U,
    Cat(value.negative,255.U(8.W),value.significand(22,0)),
    Mux(value.significand===0.U,Cat(value.negative,0.U(31.W)),Cat(value.negative,127.U(8.W),value.significand(22,0))))
}
object ClipFloatOperation {val add=0;val subtract=1;val multiply=2;val divide=3;val addTowardZero=4}
class ClipFloatRequest(tagWidth: Int) extends Bundle {
  val a=new ClipFloat;val b=new ClipFloat;val operation=UInt(3.W);val tag=UInt(tagWidth.W)
}
class ClipFloatResponse(tagWidth: Int) extends Bundle {
  val value=new ClipFloat;val fp32=UInt(32.W)
  val arithmeticStatus=UInt(5.W);val packingStatus=UInt(5.W)
  /** 0=success, 1=malformed operand/operation, 2=extended exponent range. */
  val error=UInt(2.W);val cancelled=Bool();val tag=UInt(tagWidth.W)
}

/** One owned scalar clip arithmetic operation. Reuse the existing FP32 FMA
  * and divider on normalized significands, retaining exponents separately.
  * Operations 0..3 round to nearest/even. Internal addTowardZero also truncates
  * final FP32 packing; this supplies a directed depth-quantization primitive.
  * It is not a fused multi-operation
  * interpolation or a new shader-visible numeric contract.
  *
  * Cancellation never flushes accepted arithmetic. Drain its response before
  * publishing an immutable completion and admitting another request.
  */
class ClipFloatArithmetic(tagWidth: Int=8) extends Module {
  require(tagWidth>0)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ClipFloatRequest(tagWidth)))
    val out=Decoupled(new ClipFloatResponse(tagWidth))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val idle :: prepare :: offer :: waitResult :: pack :: respond :: Nil=Enum(6)
  private val phase=RegInit(idle);private val request=Reg(new ClipFloatRequest(tagWidth))
  private val result=Reg(new ClipFloatResponse(tagWidth));private val value=Reg(new ClipFloat)
  private val coreRequest=Reg(new Fp32Request(tagWidth));private val baseExponent=Reg(SInt(12.W))
  private val arithmeticStatus=Reg(UInt(5.W));private val error=Reg(UInt(2.W))
  private val cancelled=RegInit(false.B);private val alignmentInexact=RegInit(false.B)
  private val fma=Instantiate(new Fp32FmaLane(tagWidth));private val divider=Module(new Fp32DivLane(tagWidth))
  fma.io.flush:=false.B;divider.io.flush:=false.B
  private val isDivide=request.operation===ClipFloatOperation.divide.U
  private val isTowardZero=request.operation===ClipFloatOperation.addTowardZero.U
  fma.io.in.valid:=phase===offer && !isDivide;fma.io.in.bits:=coreRequest
  divider.io.in.valid:=phase===offer && isDivide;divider.io.in.bits:=coreRequest
  fma.io.out.ready:=phase===waitResult && !isDivide;divider.io.out.ready:=phase===waitResult && isDivide
  private val response=Mux(isDivide,divider.io.out.bits,fma.io.out.bits)
  private val responseFire=Mux(isDivide,divider.io.out.fire,fma.io.out.fire)
  io.in.ready:=phase===idle && !io.cancel;io.busy:=phase=/=idle
  io.out.valid:=phase===respond;io.out.bits:=result
  when(io.in.fire){request:=io.in.bits;cancelled:=false.B;error:=0.U;arithmeticStatus:=0.U;phase:=prepare}
  when(phase=/=idle && phase=/=respond && io.cancel){cancelled:=true.B}

  private val a=request.a;private val b=request.b
  private val aZero=a.special===0.U && a.significand===0.U
  private val bZero=b.special===0.U && b.significand===0.U
  private val common=Mux(aZero,b.exponent,Mux(bZero,a.exponent,Mux(a.exponent>b.exponent,a.exponent,b.exponent)))
  private def aligned(v: ClipFloat): UInt={
    val gap=(common-v.exponent).asUInt
    val e=127.U(13.W)-gap
    // Preserve a far operand's sign/sticky effect for directed rounding; its
    // exact magnitude cannot affect a 24-bit result beyond this gap.
    val tiny=Mux(isTowardZero,Cat(v.negative,100.U(8.W),0.U(23.W)),Cat(v.negative,0.U(31.W)))
    Mux(v.special=/=0.U,ClipFloat.coreBits(v),Mux(v.significand===0.U,Cat(v.negative,0.U(31.W)),
      Mux(gap>26.U,tiny,Cat(v.negative,e(7,0),v.significand(22,0)))))
  }
  private val alignmentLost=a.special===0.U && b.special===0.U && !aZero && !bZero &&
    ((common-a.exponent)>26.S || (common-b.exponent)>26.S)
  private val exactPowerTwoDivide=isDivide && a.special===0.U && !aZero &&
    b.special===0.U && b.significand==="h800000".U
  private val scaledExponent=a.exponent.pad(14)-b.exponent.pad(14)
  when(phase===prepare) {
    when(!ClipFloat.legal(a) || !ClipFloat.legal(b) || request.operation>ClipFloatOperation.addTowardZero.U) {
      value:=ClipFloat.zero;error:=1.U;phase:=pack
    }.elsewhen(cancelled || io.cancel){value:=ClipFloat.zero;phase:=pack}
      .elsewhen(exactPowerTwoDivide){
        // Dividing a normalized finite significand by exactly one is exact;
        // only its sign and extended exponent change. Keep the normal pack
        // and response states, including range errors and cancellation.
        value:=a;value.negative:=a.negative^b.negative;value.exponent:=scaledExponent
        when(scaledExponent<(-512).S || scaledExponent>511.S){value:=ClipFloat.zero;error:=2.U}
        phase:=pack
      }
      .otherwise {
        coreRequest:=0.U.asTypeOf(coreRequest);coreRequest.tag:=request.tag
        coreRequest.roundingMode:=Mux(isTowardZero,1.U,0.U)
        coreRequest.operation:=Fp32Operation.add
        coreRequest.operandB:=aligned(a)
        coreRequest.operandC:=aligned(b)^Mux(request.operation===ClipFloatOperation.subtract.U,"h80000000".U,0.U)
        baseExponent:=common;alignmentInexact:=alignmentLost
        when(request.operation===ClipFloatOperation.multiply.U || isDivide) {
          coreRequest.operation:=Mux(isDivide,Fp32Operation.div,Fp32Operation.mul)
          coreRequest.operandA:=ClipFloat.coreBits(a);coreRequest.operandB:=ClipFloat.coreBits(b);coreRequest.operandC:=0.U
          baseExponent:=Mux(isDivide,a.exponent-b.exponent,a.exponent+b.exponent);alignmentInexact:=false.B
        }
        phase:=offer
      }
  }
  when(fma.io.in.fire || divider.io.in.fire){phase:=waitResult}
  private val normalized=ClipFloat.fromFp32(response.result)
  private val combined=baseExponent.pad(14)+normalized.exponent.pad(14)
  private val finiteNonzero=normalized.special===0.U && normalized.significand=/=0.U
  when(responseFire) {
    assert(response.tag===request.tag,"clip arithmetic lost operation tag")
    assert(!finiteNonzero || response.result(30,23)=/=0.U,"normalized clip arithmetic unexpectedly became subnormal")
    value:=normalized
    when(finiteNonzero){value.exponent:=combined}
    when(finiteNonzero && (combined<(-512).S || combined>511.S)){value:=ClipFloat.zero;error:=2.U}
    arithmeticStatus:=response.status|alignmentInexact.asUInt;phase:=pack
  }
  private val packed=ClipFloat.toFp32(value,isTowardZero,compactUnderflow=true)
  when(phase===pack) {
    result.value:=value;result.fp32:=packed.bits;result.packingStatus:=packed.status
    result.arithmeticStatus:=arithmeticStatus;result.error:=error;result.tag:=request.tag
    result.cancelled:=cancelled || io.cancel;phase:=respond
  }
  when(io.out.fire){phase:=idle}
}
