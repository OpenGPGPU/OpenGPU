package opengpu.graphics

import chisel3._
import chisel3.util._

private class PreparedTypedColorNumericPreparation extends Bundle {
  val unorm=Bool();val scaled=UInt(34.W);val significand=UInt(34.W);val shift=UInt(6.W)
  val zero=Bool();val saturate=Bool();val maximum=UInt(10.W);val other=UInt(32.W)
  val mask=UInt(32.W);val offset=UInt(7.W);val last=Bool()
}
/** One owned typed clear/packing request with overlapped component conversion
  * and placement. Consume its result before starting the next request. */
class PreparedTypedColorEncoder extends Module {
  val io=IO(new TypedColorEncoderIO)
  private val unormBits=10
  require(TypedColorFormat.formats.filter(f=>f.encoding==0 || f.encoding==5)
    .forall(_.widths.forall(_<=unormBits)),"prepared UNORM rounding width must cover every format")
  private object State extends ChiselEnum{val idle,component,srgb,packDrain,emit=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TypedColorEncodeRequest);private val result=Reg(new TypedColorEncodeResult)
  private val component=RegInit(0.U(2.W));private val digit=Reg(UInt(3.W));private val prefix=Reg(UInt(8.W))
  // Decode the immutable layout at admission, before numeric conversion.
  private val widths=Reg(Vec(4,UInt(6.W)));private val offsets=Reg(Vec(4,UInt(7.W)))
  private val encoding=Reg(UInt(3.W))
  private val bits=widths(component);private val offset=offsets(component);private val raw=request.rgba(component)
  private val converted=MuxLookup(encoding,raw)(Seq(1.U->TypedColorFormat.integer(raw,bits,false.B),
    2.U->TypedColorFormat.integer(raw,bits,true.B),3.U->TypedColorFormat.half(raw)))
  // Conversion and 128-bit placement overlap on successive components.
  private val packValid=RegInit(false.B);private val packValue=Reg(UInt(32.W))
  private val packMask=Reg(UInt(32.W));private val packOffset=Reg(UInt(7.W));private val packLast=Reg(Bool())
  private val numericValid=RegInit(false.B);private val numericUnorm=Reg(Bool())
  private val numericProduct=Reg(UInt(34.W));private val numericShift=Reg(UInt(6.W))
  private val numericZero=Reg(Bool());private val numericSaturate=Reg(Bool());private val numericMaximum=Reg(UInt(10.W))
  private val numericOther=Reg(UInt(32.W));private val numericMask=Reg(UInt(32.W))
  private val numericOffset=Reg(UInt(7.W));private val numericLast=Reg(Bool())
  private val prepared=Reg(new PreparedTypedColorNumericPreparation);private val preparedValid=RegInit(false.B)
  preparedValid:=false.B
  numericValid:=false.B
  when(preparedValid){
    numericValid:=true.B;numericProduct:=prepared.scaled-prepared.significand
    numericUnorm:=prepared.unorm;numericShift:=prepared.shift
    numericZero:=prepared.zero;numericSaturate:=prepared.saturate;numericMaximum:=prepared.maximum
    numericOther:=prepared.other;numericMask:=prepared.mask;numericOffset:=prepared.offset;numericLast:=prepared.last
  }
  packValid:=false.B
  when(numericValid){
    // For nonnegative finite inputs below one, the scaled result fits the
    // largest UNORM component (10 bits). Zero/saturation and non-UNORM values
    // select separate results. Keep full remainder precision for exact RNE,
    // but avoid a 35-bit carry chain for an at-most-10-bit quantized value.
    val truncated=(numericProduct>>numericShift)(unormBits-1,0)
    val remainder=numericProduct&((1.U(36.W)<<numericShift)-1.U)(33,0)
    val half=1.U(36.W)<<(numericShift-1.U)
    val rounded=truncated+&((remainder>half)||(remainder===half && truncated(0)))
    val unorm=Mux(numericZero,0.U,Mux(numericSaturate,numericMaximum,rounded))
    packValue:=Mux(numericUnorm,unorm,numericOther);packMask:=numericMask
    packOffset:=numericOffset;packLast:=numericLast;packValid:=true.B
  }
  when(packValid){
    val mask=(packMask.pad(128)<<packOffset)(127,0)
    when(packMask.orR){result.value:=result.value|((packValue.pad(128)<<packOffset)&mask);result.mask:=result.mask|mask}
    when(packLast){phase:=State.emit}
  }
  private def save(value: UInt,unorm: Bool=false.B): Unit={
    val maximum=((1.U(11.W)<<bits)-1.U)(9,0)
    val distance=150.U(9.W)-raw(30,23)
    preparedValid:=true.B;prepared.unorm:=unorm;prepared.other:=value
    // The UNORM maximum is exactly 2^n-1 (n<=10). Form the exact
    // 34-bit product with a shift/subtract, ahead of the RNE stage.
    val significand=Cat(raw(30,23).orR,raw(22,0)).pad(34)
    val scale=Mux(bits>10.U,10.U,bits)(3,0)
    prepared.scaled:=(significand<<scale)(33,0);prepared.significand:=significand
    prepared.shift:=Mux(raw(30,23)===0.U || distance>35.U,35.U,distance)(5,0)
    prepared.zero:=raw(31) || Fp32ViewportState.nan(raw);prepared.saturate:=raw>="h3f800000".U
    prepared.maximum:=maximum;prepared.offset:=offset;prepared.last:=component===3.U
    prepared.mask:=Mux(request.mask(component),((1.U(33.W)<<bits)-1.U)(31,0),0.U)
    when(component===3.U){phase:=State.packDrain}.otherwise{component:=component+1.U;phase:=State.component}
  }
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.emit;io.out.bits:=result;io.busy:=phase=/=State.idle
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag;component:=0.U
      for(c<-0 until 4){widths(c):=TypedColorFormat.componentWidth(io.in.bits.format,c.U);offsets(c):=TypedColorFormat.componentOffset(io.in.bits.format,c.U)}
      encoding:=TypedColorFormat.encoding(io.in.bits.format)
      when(!TypedColorFormat.valid(io.in.bits.format) || io.in.bits.colorType=/=TypedColorFormat.kind(io.in.bits.format)){
        result.status:=1.U;phase:=State.emit
      }.otherwise{result.bytes:=TypedColorFormat.bytes(io.in.bits.format);phase:=State.component}
    }}
    is(State.component){when(encoding===5.U && component<3.U && request.mask(component)){
      digit:=7.U;prefix:=0.U;phase:=State.srgb
    }.otherwise{save(converted,encoding===0.U || encoding===5.U)}}
    is(State.srgb){
      val candidate=prefix|(1.U(8.W)<<digit)
      val threshold=Srgb8Thresholds.values((candidate-1.U)(7,0))
      val normalized=Mux(raw(31) || Fp32ViewportState.nan(raw),0.U,Mux(raw>"h3f800000".U,"h3f800000".U,raw))
      val next=Mux(normalized>=threshold,candidate,prefix)
      prefix:=next;when(digit===0.U){save(next)}.otherwise{digit:=digit-1.U}
    }
    is(State.emit){when(io.out.fire){phase:=State.idle}}
  }
}
