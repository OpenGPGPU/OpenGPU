package opengpu.graphics

import chisel3._
import chisel3.util._

object TypedColorDecode {
  /** n/(2^b-1) repeats n's b-bit pattern forever. Forty fractional bits
    * include a leading bit and25 rounding bits for every supported b<=10.
    * A nonzero rational with odd denominator never ends at a binary midpoint.
    */
  def unorm(n: UInt,bits: UInt): UInt={
    val repeated=MuxLookup(bits,0.U(40.W))(Seq(1,2,4,5,6,8,10).map{b=>
      val size=((40+b-1)/b)*b;b.U->Fill((40+b-1)/b,n(b-1,0))(size-1,size-40)})
    val leading=PriorityEncoder(Reverse(repeated));val normalized=(repeated<<leading)(39,0)
    val rounded=normalized(39,16)+&normalized(15);val exponent=126.U(8.W)-leading+rounded(24)
    Mux(n===0.U,0.U,Cat(0.U(1.W),exponent(7,0),rounded(22,0)))
  }
  def half(n: UInt): UInt={
    val sign=n(15);val exponent=n(14,10);val fraction=n(9,0);val leading=PriorityEncoder(Reverse(fraction))
    val normalized=(fraction.pad(24)<<(leading+&14.U))(22,0)
    Mux(exponent===31.U,Mux(fraction.orR,"h7fc00000".U,Cat(sign,255.U(8.W),0.U(23.W))),
      Mux(exponent===0.U,Mux(fraction.orR,Cat(sign,(112.U(8.W)-leading)(7,0),normalized),Cat(sign,0.U(31.W))),
        Cat(sign,(exponent+&112.U)(7,0),fraction,0.U(13.W))))
  }
  def scalar(raw: UInt,bits: UInt,encoding: UInt,c: UInt,kind: UInt): UInt={
    val signed=((raw<<(32.U-bits))(31,0).asSInt>>(32.U-bits)).asUInt
    val decoded=MuxLookup(encoding,raw)(Seq(0.U->unorm(raw,bits),2.U->signed,3.U->half(raw(15,0)),
      5.U->Mux(c===3.U,unorm(raw,8.U),Srgb8Decode.values(raw(7,0)))))
    Mux(bits===0.U,Mux(c===3.U,Mux(kind===0.U,"h3f800000".U,1.U),0.U),decoded)
  }
  def component(value: UInt,format: UInt,c: UInt): UInt={
    val bits=TypedColorFormat.componentWidth(format,c);val shift=TypedColorFormat.componentOffset(format,c)
    val raw=((value>>shift)(31,0))&((1.U(33.W)<<bits)-1.U)(31,0)
    scalar(raw,bits,TypedColorFormat.encoding(format),c,TypedColorFormat.kind(format))
  }
}
class TypedColorDecodeRequest extends Bundle {val value=UInt(128.W);val format=UInt(6.W);val tag=UInt(8.W)}
class TypedColorDecodeResult extends Bundle {val rgba=Vec(4,UInt(32.W));val colorType=UInt(2.W);val tag=UInt(8.W);val status=UInt(2.W)}
class TypedColorDecoder extends Module {
  val io=IO(new Bundle{val in=Flipped(Decoupled(new TypedColorDecodeRequest));val out=Decoupled(new TypedColorDecodeResult);val busy=Output(Bool())})
  private val idle::decode::respond::Nil=Enum(3);private val phase=RegInit(idle)
  private val components=Reg(Vec(4,UInt(32.W)));private val widths=Reg(Vec(4,UInt(6.W)))
  private val encoding=Reg(UInt(3.W))
  private val result=Reg(new TypedColorDecodeResult);private val c=RegInit(0.U(2.W))
  io.in.ready:=phase===idle;io.out.valid:=phase===respond;io.out.bits:=result;io.busy:=phase=/=idle
  when(io.in.fire){result:=0.U.asTypeOf(result);result.tag:=io.in.bits.tag;result.colorType:=TypedColorFormat.kind(io.in.bits.format);c:=0.U
    // Extract the immutable packed fields at admission. Numeric expansion
    // still issues one component each clock with the original four-clock tail.
    encoding:=TypedColorFormat.encoding(io.in.bits.format)
    for(index<-0 until 4){
      val bits=TypedColorFormat.componentWidth(io.in.bits.format,index.U)
      val shift=TypedColorFormat.componentOffset(io.in.bits.format,index.U)
      widths(index):=bits
      components(index):=((io.in.bits.value>>shift)(31,0))&((1.U(33.W)<<bits)-1.U)(31,0)
    }
    when(TypedColorFormat.valid(io.in.bits.format)){phase:=decode}.otherwise{result.status:=1.U;phase:=respond}}
  when(phase===decode){result.rgba(c):=TypedColorDecode.scalar(components(c),widths(c),encoding,c,result.colorType)
    when(c===3.U){phase:=respond}.otherwise{c:=c+1.U}}
  when(io.out.fire){phase:=idle}
}
