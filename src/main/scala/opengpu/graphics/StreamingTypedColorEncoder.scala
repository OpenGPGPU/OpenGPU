package opengpu.graphics

import chisel3._
import chisel3.util._

private class TypedEncodePipeline extends Bundle {
  val raw=Vec(4,UInt(32.W));val widths=Vec(4,UInt(6.W));val offsets=Vec(4,UInt(7.W))
  val masks=Vec(4,UInt(32.W));val encoding=UInt(3.W)
  val invalid=Bool();val bytes=UInt(5.W);val tag=UInt(8.W)
  val normalized=Vec(3,UInt(32.W));val prefix=Vec(3,UInt(8.W))
  val scaled=Vec(4,UInt(34.W));val significand=Vec(4,UInt(34.W));val product=Vec(4,UInt(34.W))
  val shift=Vec(4,UInt(6.W));val zero=Vec(4,Bool());val saturate=Vec(4,Bool())
  val maximum=Vec(4,UInt(10.W));val converted=Vec(4,UInt(32.W))
  val truncated=Vec(4,UInt(34.W));val remainder=Vec(4,UInt(34.W));val midpoint=Vec(4,UInt(99.W))
  val unsignedMaximum=Vec(4,UInt(32.W));val signedMaximum=Vec(4,UInt(32.W));val signedMinimum=Vec(4,UInt(32.W))
}

/** Spatial four-component conversion with one complete typed color per clock.
  * Eight registered sRGB search steps overlap the exact UNORM preparation,
  * product and RNE stages. Ten elastic stages preserve ownership through stalls.
  * There is no cancellation: every accepted request produces one ordered reply.
  */
class StreamingTypedColorEncoder extends Module {
  val io=IO(new TypedColorEncoderIO)
  private val stages=Seq.fill(9)(Module(new Queue(new TypedEncodePipeline,1,pipe=true)))
  private val packed=Module(new Queue(new TypedColorEncodeResult,1,pipe=true))
  private val srgbThresholds=Srgb8Thresholds.values
  private val input=stages.head.io.enq
  io.in.ready:=input.ready;input.valid:=io.in.valid;input.bits:=0.U.asTypeOf(input.bits)
  input.bits.raw:=io.in.bits.rgba
  input.bits.encoding:=TypedColorFormat.encoding(io.in.bits.format)
  input.bits.invalid:= !TypedColorFormat.valid(io.in.bits.format) ||
    io.in.bits.colorType=/=TypedColorFormat.kind(io.in.bits.format)
  input.bits.bytes:=TypedColorFormat.bytes(io.in.bits.format);input.bits.tag:=io.in.bits.tag
  for(c<-0 until 4){
    val bits=TypedColorFormat.componentWidth(io.in.bits.format,c.U)
    input.bits.widths(c):=bits;input.bits.offsets(c):=TypedColorFormat.componentOffset(io.in.bits.format,c.U)
    // Prepare format bounds before raw integer comparisons. Direct layout
    // tables avoid width subtraction and variable shifts in the clamp stage.
    def bounds(value: Int=>BigInt): UInt=VecInit(TypedColorFormat.formats.map { f=>
      value(f.widths(c)).U(32.W)
    })(io.in.bits.format)
    input.bits.unsignedMaximum(c):=bounds(w=>(BigInt(1)<<w)-1)
    input.bits.signedMaximum(c):=bounds(w=>if(w==0)BigInt(0)else (BigInt(1)<<(w-1))-1)
    input.bits.signedMinimum(c):=bounds(w=>if(w==0)BigInt(0)else (-(BigInt(1)<<(w-1)))&BigInt("ffffffff",16))
    input.bits.masks(c):=Mux(io.in.bits.mask(c),((1.U(33.W)<<bits)-1.U)(31,0),0.U)
    if(c<3){
      val raw=io.in.bits.rgba(c)
      input.bits.normalized(c):=Mux(raw(31) || Fp32ViewportState.nan(raw),0.U,
        Mux(raw>"h3f800000".U,"h3f800000".U,raw))
    }
  }
  for(index<-0 until 8){
    val from=stages(index).io.deq;val into=stages(index+1).io.enq;val f=from.bits
    from.ready:=into.ready;into.valid:=from.valid;into.bits:=f
    val bit=7-index
    // Each stage selects only thresholds reachable by its completed prefix.
    val thresholds=VecInit(Seq.tabulate(1<<index)(i=>srgbThresholds((i<<(bit+1))+(1<<bit)-1)))
    for(c<-0 until 3){
      val threshold=if(index==0)thresholds(0)else thresholds(f.prefix(c)(7,bit+1))
      into.bits.prefix(c):=Mux(f.normalized(c)>=threshold,f.prefix(c)|(1<<bit).U,f.prefix(c))
    }
    for(c<-0 until 4){
      if(index==0){
        val raw=f.raw(c);val bits=f.widths(c);val distance=150.U(9.W)-raw(30,23)
        val significand=Cat(raw(30,23).orR,raw(22,0)).pad(34)
        val scale=Mux(bits>10.U,10.U,bits)(3,0)
        into.bits.scaled(c):=(significand<<scale)(33,0);into.bits.significand(c):=significand
        into.bits.shift(c):=Mux(raw(30,23)===0.U || distance>35.U,35.U,distance)(5,0)
        into.bits.zero(c):=raw(31) || Fp32ViewportState.nan(raw)
        into.bits.saturate(c):=raw>="h3f800000".U
        into.bits.maximum(c):=((1.U(11.W)<<bits)-1.U)(9,0)
        val unsigned=Mux(raw>f.unsignedMaximum(c),f.unsignedMaximum(c),raw)
        val signed=Mux(raw.asSInt<f.signedMinimum(c).asSInt,f.signedMinimum(c),
          Mux(raw.asSInt>f.signedMaximum(c).asSInt,f.signedMaximum(c),raw))
        into.bits.converted(c):=MuxLookup(f.encoding,raw)(Seq(
          1.U->unsigned,2.U->signed,3.U->TypedColorFormat.half(raw)))
      }
      if(index==1)into.bits.product(c):=f.scaled(c)-f.significand(c)
      if(index==2){
        // Isolate variable shifts from comparison, carry and saturation. The
        // sRGB search already reserves the following stage, so latency stays ten.
        val shift=f.shift(c);val product=f.product(c)
        into.bits.truncated(c):=product>>shift
        into.bits.remainder(c):=product&((1.U(36.W)<<shift)-1.U)(33,0)
        into.bits.midpoint(c):=1.U(36.W)<<(shift-1.U)
      }
      if(index==3){
        val truncated=f.truncated(c);val remainder=f.remainder(c);val half=f.midpoint(c)
        val rounded=truncated+&((remainder>half)||(remainder===half && truncated(0)))
        val unorm=Mux(f.zero(c),0.U,Mux(f.saturate(c),f.maximum(c),rounded))
        when(f.encoding===0.U || f.encoding===5.U){into.bits.converted(c):=unorm}
      }
    }
  }
  private val tail=stages.last.io.deq;private val f=tail.bits
  tail.ready:=packed.io.enq.ready;packed.io.enq.valid:=tail.valid
  private val masks=(0 until 4).map(c=>(f.masks(c).pad(128)<<f.offsets(c))(127,0))
  private val values=(0 until 4).map{c=>
    val value=if(c<3)Mux(f.encoding===5.U,f.prefix(c),f.converted(c))else f.converted(c)
    ((value.pad(128)<<f.offsets(c))(127,0))&masks(c)
  }
  packed.io.enq.bits.value:=Mux(f.invalid,0.U,values.reduce(_|_))
  packed.io.enq.bits.mask:=Mux(f.invalid,0.U,masks.reduce(_|_))
  packed.io.enq.bits.bytes:=Mux(f.invalid,0.U,f.bytes)
  packed.io.enq.bits.status:=f.invalid;packed.io.enq.bits.tag:=f.tag
  io.out<>packed.io.deq
  io.busy:=stages.map(_.io.deq.valid).reduce(_||_) || packed.io.deq.valid
}
