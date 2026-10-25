package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

class TypedSampleResolveRequest extends Bundle {
  val format=UInt(6.W);val depth=Bool();val sampleMode=UInt(2.W)
  val samples=Vec(4,UInt(128.W));val tag=UInt(8.W)
}
class TypedSampleResolveResult extends Bundle {
  val value=UInt(128.W);val tag=UInt(8.W);val status=UInt(2.W);val cancelled=Bool()
}
/** Same-format 1x/2x/4x resolve. Integer, depth/stencil and 1x select raw
  * sample zero. Normalized/float colors average decoded FP32 components;
  * sRGB RGB is decoded to linear light before averaging. The finite sum uses
  * exact signed units of 2^-149, then divides by the sample count with one
  * RNE to FP32. This avoids intermediate overflow and subnormal truncation.
  * One component at a time, with sample extraction/decode/alignment pipelined
  * ahead of one exact integer accumulator. No FPU or SRAM is required.
  */
class TypedSampleResolve extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedSampleResolveRequest));val out=Decoupled(new TypedSampleResolveResult)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,accumulate,carry,absolute,negate,absoluteDone,leadingGroups,leading,normalize,round,encode,waitEncode,finish,respond=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TypedSampleResolveRequest)
  private val result=Reg(new TypedSampleResolveResult);private val cancelled=RegInit(false.B)
  private val component=RegInit(0.U(2.W));private val sample=RegInit(0.U(2.W))
  private val savedSum=RegInit(0.U(281.W));private val savedCarry=RegInit(0.U(281.W))
  // Nine 32-bit words retain the complete signed 281-bit sum. The seven high
  // assembly bits are padding; sign and magnitude always use bits280:0.
  private val binarySum=RegInit(0.U(288.W));private val word=RegInit(0.U(4.W));private val wordCarry=RegInit(false.B)
  private val nan=RegInit(false.B);private val positiveInfinity=RegInit(false.B)
  private val negativeInfinity=RegInit(false.B);private val allNegativeZero=RegInit(true.B)
  private val encoding=Reg(UInt(3.W));private val kind=Reg(UInt(2.W))
  private val issueDone=RegInit(false.B);private val received=RegInit(0.U(3.W))
  private val extractedValid=RegInit(false.B);private val decodedValid=RegInit(false.B);private val alignedValid=RegInit(false.B)
  private val extracted=Reg(UInt(32.W));private val extractedWidth=Reg(UInt(6.W));private val decoded=Reg(UInt(32.W))
  private val alignedMagnitude=Reg(UInt(281.W));private val alignedNegative=Reg(Bool());private val alignedFinite=Reg(Bool())
  private val alignedNan=Reg(Bool());private val alignedInfinity=Reg(Bool());private val alignedNegativeZero=Reg(Bool())
  private val magnitude=Reg(UInt(281.W));private val negative=Reg(Bool());private val zero=Reg(Bool())
  private val groupValid=Reg(Vec(9,Bool()));private val groupFirst=Reg(Vec(9,UInt(5.W)));private val groupLast=Reg(Vec(9,UInt(5.W)))
  private val msb=Reg(UInt(9.W));private val lowest=Reg(UInt(9.W));private val normal=Reg(Bool());private val shift=Reg(UInt(8.W))
  private val truncated=Reg(UInt(31.W));private val increment=Reg(Bool())
  // Propagate carry only after all samples, using a bounded datapath. The
  // same adder converts a negative result to magnitude on a second pass.
  private val wordSum=Module(new BlockPrefixAdder(32))
  wordSum.io.a:=Mux(phase===State.negate,~binarySum(31,0),savedSum(31,0))
  wordSum.io.b:=Mux(phase===State.negate,0.U,savedCarry(31,0))
  wordSum.io.carryIn:=wordCarry
  private val rgba=Reg(Vec(4,UInt(32.W)));private val encoder=Module(new TypedColorEncoder)
  private def clearComponent(): Unit={savedSum:=0.U;savedCarry:=0.U;binarySum:=0.U;word:=0.U;wordCarry:=false.B
    nan:=false.B;positiveInfinity:=false.B;negativeInfinity:=false.B;allNegativeZero:=true.B;sample:=0.U
    issueDone:=false.B;received:=0.U;extractedValid:=false.B;decodedValid:=false.B;alignedValid:=false.B}
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.respond;io.out.bits:=result;io.busy:=phase=/=State.idle
  encoder.io.in.valid:=phase===State.encode;encoder.io.in.bits:=0.U.asTypeOf(encoder.io.in.bits)
  encoder.io.in.bits.format:=request.format;encoder.io.in.bits.colorType:=0.U;encoder.io.in.bits.rgba:=rgba
  encoder.io.in.bits.mask:=15.U;encoder.io.in.bits.tag:=request.tag;encoder.io.out.ready:=phase===State.waitEncode
  when(io.cancel && phase=/=State.idle && phase=/=State.respond){cancelled:=true.B}
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag;cancelled:=io.cancel;component:=0.U;clearComponent()
      encoding:=TypedColorFormat.encoding(io.in.bits.format);kind:=TypedColorFormat.kind(io.in.bits.format)
      when(io.in.bits.sampleMode>2.U || (!io.in.bits.depth && !TypedColorFormat.valid(io.in.bits.format))){result.status:=1.U;phase:=State.finish}
        .elsewhen(io.in.bits.depth || io.in.bits.sampleMode===0.U || TypedColorFormat.kind(io.in.bits.format)=/=0.U){result.value:=io.in.bits.samples(0);phase:=State.finish}
        .otherwise{phase:=State.accumulate}}}
    is(State.accumulate){
      extractedValid:= !issueDone;decodedValid:=extractedValid;alignedValid:=decodedValid
      when(!issueDone){
        val bits=TypedColorFormat.componentWidth(request.format,component)
        val offset=TypedColorFormat.componentOffset(request.format,component)
        extracted:=((request.samples(sample)>>offset)(31,0))&((1.U(33.W)<<bits)-1.U)(31,0)
        extractedWidth:=bits
        when(sample+&1.U===(1.U(3.W)<<request.sampleMode)){issueDone:=true.B}.otherwise{sample:=sample+1.U}
      }
      when(extractedValid){decoded:=TypedColorDecode.scalar(extracted,extractedWidth,encoding,component,kind)}
      when(decodedValid){
        val exponent=decoded(30,23);val fraction=decoded(22,0)
        val significand=Cat(exponent.orR,fraction)
        val alignment=Mux(exponent===0.U,0.U,exponent-1.U)(7,0)
        alignedMagnitude:=(significand.pad(281)<<alignment)(280,0)
        alignedNegative:=decoded(31);alignedFinite:=exponent=/=255.U
        alignedNan:=exponent===255.U && fraction.orR
        alignedInfinity:=exponent===255.U && !fraction.orR
        alignedNegativeZero:=decoded==="h80000000".U
      }
      when(alignedValid){
        when(alignedFinite){
          // Two carry-save reductions add the signed sample without a carry
          // chain. Complement plus the low-bit correction is exact modulo
          // 2^281, whose signed range contains every possible four-FP32 sum.
          val term=alignedMagnitude^Fill(281,alignedNegative)
          val partial=savedSum^savedCarry^term
          val carry=(((savedSum&savedCarry)|(savedSum&term)|(savedCarry&term))<<1)(280,0)
          val correction=Cat(0.U(280.W),alignedNegative)
          savedSum:=partial^carry^correction
          savedCarry:=(((partial&carry)|(partial&correction)|(carry&correction))<<1)(280,0)
        }
        nan:=nan || alignedNan
        positiveInfinity:=positiveInfinity || (alignedInfinity && !alignedNegative)
        negativeInfinity:=negativeInfinity || (alignedInfinity && alignedNegative)
        allNegativeZero:=allNegativeZero && alignedNegativeZero
        received:=received+1.U
        when(received+&1.U===(1.U(3.W)<<request.sampleMode)){word:=0.U;wordCarry:=false.B;phase:=State.carry}
      }
    }
    is(State.carry){
      binarySum:=Cat(wordSum.io.sum(31,0),binarySum(287,32))
      savedSum:=savedSum>>32;savedCarry:=savedCarry>>32;wordCarry:=wordSum.io.sum(32)
      when(word===8.U){phase:=State.absolute}.otherwise{word:=word+1.U}
    }
    is(State.absolute){
      negative:=binarySum(280)
      when(binarySum(280)){word:=0.U;wordCarry:=true.B;phase:=State.negate}
        .otherwise{magnitude:=binarySum(280,0);phase:=State.leadingGroups}
    }
    is(State.negate){
      binarySum:=Cat(wordSum.io.sum(31,0),binarySum(287,32));wordCarry:=wordSum.io.sum(32)
      when(word===8.U){phase:=State.absoluteDone}.otherwise{word:=word+1.U}
    }
    is(State.absoluteDone){magnitude:=binarySum(280,0);phase:=State.leadingGroups}
    is(State.leadingGroups){
      // Bound both priority scans before selecting one of nine registered
      // groups. A flat 281-bit priority encoder maps to a long serial cone.
      for(g<-0 until 9){
        val bits=math.min(32,281-g*32);val part=magnitude(g*32+bits-1,g*32)
        groupValid(g):=part.orR;groupFirst(g):=PriorityEncoder(part)
        groupLast(g):=(bits-1).U-PriorityEncoder(Reverse(part))
      }
      phase:=State.leading
    }
    is(State.leading){
      val highest=8.U(4.W)-PriorityEncoder(Reverse(groupValid.asUInt))
      val first=PriorityEncoder(groupValid.asUInt)
      val top=Cat(highest,groupLast(highest))
      val isNormal=top>=23.U+&request.sampleMode
      msb:=top;normal:=isNormal;shift:=Mux(isNormal,top-23.U,request.sampleMode.pad(9))(7,0)
      lowest:=Cat(first,groupFirst(first));zero:= !groupValid.asUInt.orR;phase:=State.normalize
    }
    is(State.normalize){
      val q=magnitude>>shift;val guardIndex=Mux(shift===0.U,0.U,shift-1.U)
      val guard=(magnitude>>guardIndex)(0)
      // A nonzero bit below the guard makes the discarded remainder greater
      // than half. The least-set-bit index replaces a wide variable mask and
      // 281-bit comparison without dropping any subnormal or tie information.
      truncated:=q(30,0);increment:=shift=/=0.U && guard && (lowest<guardIndex || q(0))
      phase:=State.round
    }
    is(State.round){
      val rounded=truncated+&increment;val carry=rounded(24)
      val exponent=(msb-22.U-request.sampleMode)+carry
      val body=Mux(normal,Cat(exponent(7,0),Mux(carry,rounded(23,1),rounded(22,0))),rounded(30,0))
      val finite=Mux(zero,Cat(allNegativeZero,0.U(31.W)),Cat(negative,body))
      rgba(component):=Mux(nan || (positiveInfinity && negativeInfinity),"h7fc00000".U,
        Mux(positiveInfinity,"h7f800000".U,Mux(negativeInfinity,"hff800000".U,finite)))
      when(component===3.U){phase:=State.encode}.otherwise{component:=component+1.U;clearComponent();phase:=State.accumulate}
    }
    is(State.encode){when(encoder.io.in.fire){phase:=State.waitEncode}}
    is(State.waitEncode){when(encoder.io.out.fire){result.value:=encoder.io.out.bits.value;result.status:=encoder.io.out.bits.status;phase:=State.finish}}
    is(State.finish){result.cancelled:=cancelled || io.cancel;phase:=State.respond}
    is(State.respond){when(io.out.fire){phase:=State.idle}}
  }
}
