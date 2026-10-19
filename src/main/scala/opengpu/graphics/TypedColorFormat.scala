package opengpu.graphics

import chisel3._
import chisel3.util._

/** Internal attachment encodings. Byte formats have R at the lowest address;
  * 565/4444/5551 retain their conventional packed component order. 10:10:10:2
  * uses low R. This is independent of the legacy 0xRRGGBBAA shader ABI.
  */
object TypedColorFormat {
  case class Info(name: String,kind: Int,widths: Seq[Int],offsets: Seq[Int],encoding: Int) {
    val bytes=(widths.zip(offsets).map{case(w,o)=>w+o}.max+7)/8
  }
  private def ordinary(name: String,kind: Int,width: Int,channels: Int,encoding: Int): Info=
    Info(name,kind,Seq.tabulate(4)(i=>if(i<channels)width else 0),Seq.tabulate(4)(i=>if(i<channels)i*width else 0),encoding)
  // Encoding:0 UNORM,1 uint,2 sint,3 binary16,4 raw binary32,5 sRGB8+UNORM alpha.
  val formats: Vector[Info]=(Vector(
    ordinary("R8",0,8,1,0),ordinary("RG8",0,8,2,0),ordinary("RGB8",0,8,3,0),ordinary("RGBA8",0,8,4,0),
    Info("RGB565",0,Seq(5,6,5,0),Seq(11,5,0,0),0),Info("RGBA4",0,Seq(4,4,4,4),Seq(12,8,4,0),0),
    Info("RGB5_A1",0,Seq(5,5,5,1),Seq(11,6,1,0),0),Info("RGB10_A2",0,Seq(10,10,10,2),Seq(0,10,20,30),0),
    ordinary("SRGB8_ALPHA8",0,8,4,5)) ++
    (for(channels<-Seq(1,2,4);width<-Seq(8,16,32);kind<-Seq(2,1))yield
      ordinary((channels match{case 1=>"R";case 2=>"RG";case _=>"RGBA"})+width+(if(kind==2)"I"else"UI"),kind,width,channels,kind)) ++
    Vector(Info("RGB10_A2UI",1,Seq(10,10,10,2),Seq(0,10,20,30),1)) ++
    (for(channels<-1 to 4;width<-Seq(16,32))yield ordinary((channels match{case 1=>"R";case 2=>"RG";case 3=>"RGB";case _=>"RGBA"})+width+"F",0,width,channels,if(width==16)3 else 4))).toVector
  require(formats.size==36)
  val width=6
  def id(name: String): Int={val i=formats.indexWhere(_.name==name);require(i>=0);i}
  def valid(format: UInt): Bool=format<formats.size.U
  def bytes(format: UInt): UInt=VecInit(formats.map(_.bytes.U(5.W)))(format)
  def kind(format: UInt): UInt=VecInit(formats.map(_.kind.U(2.W)))(format)
  def encoding(format: UInt): UInt=VecInit(formats.map(_.encoding.U(3.W)))(format)
  def componentWidth(format: UInt,component: UInt): UInt=VecInit(formats.map(i=>VecInit(i.widths.map(_.U(6.W)))))(format)(component)
  def componentOffset(format: UInt,component: UInt): UInt=VecInit(formats.map(i=>VecInit(i.offsets.map(_.U(7.W)))))(format)(component)

  /** Exact FP32 rational times(2^n-1), rounded once to nearest/even. */
  def unorm(raw: UInt,bits: UInt): UInt={
    val maximum=((1.U(11.W)<<bits)-1.U)(9,0)
    val significand=Cat(raw(30,23).orR,raw(22,0))
    val product=significand*maximum
    val distance=150.U(9.W)-raw(30,23)
    val shift=Mux(raw(30,23)===0.U || distance>35.U,35.U,distance)(5,0)
    val truncated=product>>shift
    val remainder=product&((1.U(36.W)<<shift)-1.U)(33,0)
    val half=1.U(36.W)<<(shift-1.U)
    val rounded=truncated+&((remainder>half)||(remainder===half && truncated(0)))
    Mux(Fp32ViewportState.nan(raw) || raw(31),0.U,Mux(raw>="h3f800000".U,maximum,rounded)).pad(32)
  }
  def half(raw: UInt): UInt={
    val exponent=raw(30,23);val fraction=raw(22,0);val significand=Cat(exponent.orR,fraction)
    val normal=exponent>=113.U
    val shift=Mux(normal,13.U,Mux(exponent<102.U,25.U,126.U-exponent))(4,0)
    val truncated=significand>>shift
    val remainder=significand&((1.U(26.W)<<shift)-1.U)(23,0)
    val half=1.U(26.W)<<(shift-1.U)
    val rounded=truncated+&((remainder>half)||(remainder===half && truncated(0)))
    val e=exponent-112.U+rounded(11)
    val body=Mux(normal,Cat(e(4,0),rounded(9,0)),rounded(14,0))
    val finite=Cat(raw(31),Mux(exponent>142.U || (normal && e>=31.U),"h7c00".U(15.W),body))
    Mux(exponent===255.U,Mux(fraction.orR,"h7e00".U,Cat(raw(31),"h7c00".U(15.W))),finite)
  }
  def integer(raw: UInt,bits: UInt,signed: Bool): UInt={
    val maxUnsigned=((1.U(33.W)<<bits)-1.U)(31,0)
    val maxSigned=((1.U(33.W)<<(bits-1.U))-1.U)(31,0)
    val minSigned=(~maxSigned).asSInt
    Mux(signed,Mux(raw.asSInt<minSigned,minSigned.asUInt,Mux(raw.asSInt>maxSigned.asSInt,maxSigned,raw)),Mux(raw>maxUnsigned,maxUnsigned,raw))
  }
}
class TypedColorEncodeRequest extends Bundle {
  val format=UInt(TypedColorFormat.width.W);val colorType=UInt(2.W);val rgba=Vec(4,UInt(32.W));val mask=UInt(4.W);val tag=UInt(8.W)
}
class TypedColorEncodeResult extends Bundle {
  val value=UInt(128.W);val mask=UInt(128.W);val bytes=UInt(5.W);val tag=UInt(8.W);val status=UInt(2.W)
}
/** One shared encoder, bounded four-component work; sRGB uses8 binary-search
  * steps per RGB component, with fixed FP32 transition thresholds. UNORM and
  * binary16 round to nearest/even. NaN UNORM/sRGB maps to zero; binary16 NaNs
  * canonicalize, while binary32 storage preserves raw bits. Held result stable.
  */
class TypedColorEncoder extends Module {
  val io=IO(new Bundle{val in=Flipped(Decoupled(new TypedColorEncodeRequest));val out=Decoupled(new TypedColorEncodeResult);val busy=Output(Bool())})
  private object State extends ChiselEnum{val idle,component,srgb,emit=Value}
  private val phase=RegInit(State.idle);private val request=Reg(new TypedColorEncodeRequest);private val result=Reg(new TypedColorEncodeResult)
  private val component=RegInit(0.U(2.W));private val digit=Reg(UInt(3.W));private val prefix=Reg(UInt(8.W))
  private val bits=TypedColorFormat.componentWidth(request.format,component);private val offset=TypedColorFormat.componentOffset(request.format,component)
  private val encoding=TypedColorFormat.encoding(request.format);private val raw=request.rgba(component)
  private val converted=MuxLookup(encoding,raw)(Seq(0.U->TypedColorFormat.unorm(raw,bits),1.U->TypedColorFormat.integer(raw,bits,false.B),
    2.U->TypedColorFormat.integer(raw,bits,true.B),3.U->TypedColorFormat.half(raw),5.U->TypedColorFormat.unorm(raw,8.U)))
  private def save(value: UInt): Unit={
    val mask=(((1.U(129.W)<<bits)-1.U)<<offset)(127,0)
    when(bits=/=0.U && request.mask(component)){result.value:=result.value|((value.pad(128)<<offset)&mask);result.mask:=result.mask|mask}
    when(component===3.U){phase:=State.emit}.otherwise{component:=component+1.U;phase:=State.component}
  }
  io.in.ready:=phase===State.idle;io.out.valid:=phase===State.emit;io.out.bits:=result;io.busy:=phase=/=State.idle
  switch(phase){
    is(State.idle){when(io.in.fire){request:=io.in.bits;result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag;component:=0.U
      when(!TypedColorFormat.valid(io.in.bits.format) || io.in.bits.colorType=/=TypedColorFormat.kind(io.in.bits.format)){
        result.status:=1.U;phase:=State.emit
      }.otherwise{result.bytes:=TypedColorFormat.bytes(io.in.bits.format);phase:=State.component}
    }}
    is(State.component){when(encoding===5.U && component<3.U && request.mask(component)){
      digit:=7.U;prefix:=0.U;phase:=State.srgb
    }.otherwise{save(converted)}}
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
