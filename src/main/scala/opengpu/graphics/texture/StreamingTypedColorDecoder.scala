package opengpu.graphics

import chisel3._
import chisel3.util._

private class TypedDecodeFields extends Bundle {
  val raw=Vec(4,UInt(32.W));val widths=Vec(4,UInt(6.W));val encoding=UInt(3.W)
  val colorType=UInt(2.W);val tag=UInt(8.W);val invalid=Bool()
}

/** One complete typed RGBA value per clock, with two elastic ownership stages.
  * Packed extraction precedes four spatial numeric expanders. Every accepted
  * value retains its format and tag through stalls; there is no cancellation.
  */
class StreamingTypedColorDecoder extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TypedColorDecodeRequest))
    val out=Decoupled(new TypedColorDecodeResult);val busy=Output(Bool())
  })
  private val fields=Module(new Queue(new TypedDecodeFields,1,pipe=true))
  private val decoded=Module(new Queue(new TypedColorDecodeResult,1,pipe=true))
  io.in.ready:=fields.io.enq.ready;fields.io.enq.valid:=io.in.valid
  fields.io.enq.bits.encoding:=TypedColorFormat.encoding(io.in.bits.format)
  fields.io.enq.bits.colorType:=TypedColorFormat.kind(io.in.bits.format)
  fields.io.enq.bits.tag:=io.in.bits.tag
  fields.io.enq.bits.invalid:= !TypedColorFormat.valid(io.in.bits.format)
  for(c<-0 until 4){
    val bits=TypedColorFormat.componentWidth(io.in.bits.format,c.U)
    val offset=TypedColorFormat.componentOffset(io.in.bits.format,c.U)
    fields.io.enq.bits.widths(c):=bits
    fields.io.enq.bits.raw(c):=((io.in.bits.value>>offset)(31,0))&((1.U(33.W)<<bits)-1.U)(31,0)
  }
  fields.io.deq.ready:=decoded.io.enq.ready;decoded.io.enq.valid:=fields.io.deq.valid
  private val f=fields.io.deq.bits
  decoded.io.enq.bits.colorType:=f.colorType;decoded.io.enq.bits.tag:=f.tag
  decoded.io.enq.bits.status:=f.invalid
  for(c<-0 until 4)decoded.io.enq.bits.rgba(c):=Mux(f.invalid,0.U,
    TypedColorDecode.scalar(f.raw(c),f.widths(c),f.encoding,c.U,f.colorType))
  io.out<>decoded.io.deq
  io.busy:=fields.io.deq.valid || decoded.io.deq.valid
}
