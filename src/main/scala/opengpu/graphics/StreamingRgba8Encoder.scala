package opengpu.graphics

import chisel3._
import chisel3.util._

private class Rgba8EncodeOwner extends Bundle {
  val mask=UInt(4.W);val tag=UInt(8.W);val invalid=Bool()
}
private class Rgba8EncodePrepared extends Bundle {
  val owner=new Rgba8EncodeOwner
  val significands=Vec(4,UInt(24.W));val shifts=Vec(4,UInt(6.W))
  val zero=Vec(4,Bool());val saturate=Vec(4,Bool())
}
private class Rgba8EncodeProducts extends Bundle {
  val owner=new Rgba8EncodeOwner
  val products=Vec(4,UInt(32.W));val shifts=Vec(4,UInt(6.W))
  val zero=Vec(4,Bool());val saturate=Vec(4,Bool())
}
private class Rgba8EncodeRounded extends Bundle {
  val owner=new Rgba8EncodeOwner;val values=Vec(4,UInt(8.W))
}

/** Four spatial UNORM8 converters, with exact integer product and RNE.
  * Each elastic stage owns its metadata; a held output never changes.
  */
class StreamingRgba8Encoder extends Module {
  val io=IO(new TypedColorEncoderIO)
  private val prepared=Module(new Queue(new Rgba8EncodePrepared,1,pipe=true))
  private val products=Module(new Queue(new Rgba8EncodeProducts,1,pipe=true))
  private val rounded=Module(new Queue(new Rgba8EncodeRounded,1,pipe=true))
  private val packed=Module(new Queue(new TypedColorEncodeResult,1,pipe=true))
  prepared.io.enq.valid:=io.in.valid;io.in.ready:=prepared.io.enq.ready
  prepared.io.enq.bits.owner.mask:=io.in.bits.mask
  prepared.io.enq.bits.owner.tag:=io.in.bits.tag
  prepared.io.enq.bits.owner.invalid:=io.in.bits.format=/=TypedColorFormat.id("RGBA8").U || io.in.bits.colorType=/=0.U
  for(c<-0 until 4){
    val raw=io.in.bits.rgba(c);val distance=150.U(9.W)-raw(30,23)
    prepared.io.enq.bits.significands(c):=Cat(raw(30,23).orR,raw(22,0))
    prepared.io.enq.bits.shifts(c):=Mux(raw(30,23)===0.U || distance>35.U,35.U,distance)
    prepared.io.enq.bits.zero(c):=raw(31) || Fp32ViewportState.nan(raw)
    prepared.io.enq.bits.saturate(c):=raw>="h3f800000".U
  }
  products.io.enq.valid:=prepared.io.deq.valid;prepared.io.deq.ready:=products.io.enq.ready
  products.io.enq.bits.owner:=prepared.io.deq.bits.owner
  products.io.enq.bits.shifts:=prepared.io.deq.bits.shifts
  products.io.enq.bits.zero:=prepared.io.deq.bits.zero
  products.io.enq.bits.saturate:=prepared.io.deq.bits.saturate
  for(c<-0 until 4){
    val sig=prepared.io.deq.bits.significands(c)
    products.io.enq.bits.products(c):=(sig<<8)-sig
  }
  rounded.io.enq.valid:=products.io.deq.valid;products.io.deq.ready:=rounded.io.enq.ready
  rounded.io.enq.bits.owner:=products.io.deq.bits.owner
  for(c<-0 until 4){
    val p=products.io.deq.bits;val product=p.products(c);val shift=p.shifts(c)
    val truncated=product>>shift
    val remainder=product&((1.U(36.W)<<shift)-1.U)(31,0)
    val half=1.U(36.W)<<(shift-1.U)
    val value=truncated+&((remainder>half)||(remainder===half && truncated(0)))
    rounded.io.enq.bits.values(c):=Mux(p.zero(c),0.U,Mux(p.saturate(c),255.U,value))
  }
  packed.io.enq.valid:=rounded.io.deq.valid;rounded.io.deq.ready:=packed.io.enq.ready
  private val r=rounded.io.deq.bits
  private val masks=VecInit((0 until 4).map(c=>Fill(8,r.owner.mask(c) && !r.owner.invalid)))
  packed.io.enq.bits.value:=Cat((0 until 4).reverse.map(c=>r.values(c)&masks(c)))
  packed.io.enq.bits.mask:=Cat(masks.reverse)
  packed.io.enq.bits.bytes:=Mux(r.owner.invalid,0.U,4.U)
  packed.io.enq.bits.tag:=r.owner.tag;packed.io.enq.bits.status:=r.owner.invalid
  io.out<>packed.io.deq
  io.busy:=prepared.io.deq.valid || products.io.deq.valid || rounded.io.deq.valid || packed.io.deq.valid
}

/** Cohort routing preserves order when switching to the general format service. */
class RoutedTypedColorEncoder extends Module {
  val io=IO(new TypedColorEncoderIO)
  private val streaming=Module(new StreamingRgba8Encoder);private val serial=Module(new TypedColorEncoder)
  private val fast=RegInit(false.B);private val outstanding=RegInit(0.U(3.W))
  private val selected=io.in.bits.format===TypedColorFormat.id("RGBA8").U
  private val switchable=outstanding===0.U || (outstanding===1.U && io.out.fire)
  private val allowed=selected===fast || switchable
  io.in.ready:=allowed && Mux(selected,streaming.io.in.ready,serial.io.in.ready)
  streaming.io.in.valid:=io.in.valid && allowed && selected;streaming.io.in.bits:=io.in.bits
  serial.io.in.valid:=io.in.valid && allowed && !selected;serial.io.in.bits:=io.in.bits
  io.out.valid:=Mux(fast,streaming.io.out.valid,serial.io.out.valid)
  io.out.bits:=Mux(fast,streaming.io.out.bits,serial.io.out.bits)
  streaming.io.out.ready:=fast && io.out.ready;serial.io.out.ready:= !fast && io.out.ready
  io.busy:=outstanding=/=0.U
  when(io.in.fire){fast:=selected}
  switch(Cat(io.in.fire,io.out.fire)){
    is("b10".U){outstanding:=outstanding+1.U}
    is("b01".U){outstanding:=outstanding-1.U}
  }
  when(io.out.fire){assert(outstanding=/=0.U,"typed encoder retired without ownership")}
  when(outstanding===0.U){assert(!streaming.io.busy && !serial.io.busy,"encoder switched before drain")}
}
