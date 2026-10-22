package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

class VertexInputConversionRequest extends Bundle {
  val word=UInt(32.W);val format=UInt(VertexInputFormat.width.W);val component=UInt(2.W)
}

/** Ordered, elastic scalar conversion. Separate extraction, signed magnitude,
  * fractional expansion, normalization and RNE packing with registered cuts.
  * Two-entry queues also break backpressure; an unstalled stream accepts one
  * component per cycle and returns it five cycles later.
  */
class VertexInputConversionPipeline extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new VertexInputConversionRequest))
    val out=Decoupled(UInt(32.W))
  })
  private class Extracted extends Bundle {
    val raw=UInt(32.W);val bits=UInt(6.W);val signed=Bool();val normalized=Bool();val fixed=Bool()
    val converted=Bool();val fallback=UInt(32.W)
  }
  private class Magnitude extends Bundle {
    val n=UInt(32.W);val bits=UInt(6.W);val normalized=Bool();val fixed=Bool();val negative=Bool()
    val converted=Bool();val fallback=UInt(32.W)
  }
  private class Fraction extends Bundle {
    val source=UInt(64.W);val baseExponent=UInt(9.W);val negative=Bool();val zero=Bool()
    val converted=Bool();val fallback=UInt(32.W)
  }
  private class Aligned extends Bundle {
    val normalized=UInt(64.W);val exponent=UInt(9.W);val negative=Bool();val zero=Bool()
    val converted=Bool();val fallback=UInt(32.W)
  }
  private val extracted=Module(new Queue(new Extracted,2)).io
  private val magnitude=Module(new Queue(new Magnitude,2)).io
  private val fraction=Module(new Queue(new Fraction,2)).io
  private val aligned=Module(new Queue(new Aligned,2)).io
  private val rounded=Module(new Queue(UInt(32.W),2)).io
  import VertexInputFormat._
  private val q=io.in.bits;private val packed=VertexInputFormat.packed(q.format)
  private val bits=Mux(packed,Mux(q.component===3.U,2.U(6.W),10.U(6.W)),MuxLookup(q.format,32.U(6.W))(
    Seq(float16,uint16Float,sint16Float,unorm16,snorm16).map(_.U->16.U(6.W))++
      Seq(uint8Float,sint8Float,unorm8,snorm8).map(_.U->8.U(6.W))))
  private val shift=Mux(packed,Mux(q.component===3.U,30.U,q.component*10.U),0.U)
  extracted.enq.valid:=io.in.valid;io.in.ready:=extracted.enq.ready
  extracted.enq.bits.raw:=(q.word>>shift)&((1.U(33.W)<<bits)-1.U)(31,0)
  extracted.enq.bits.bits:=bits
  extracted.enq.bits.signed:=Seq(sint8Float,sint16Float,sint32Float,snorm8,snorm16,snorm32,
    snorm2101010,sint2101010Float,fixed16_16).map(q.format===_.U).reduce(_ || _)
  extracted.enq.bits.normalized:=Seq(unorm8,snorm8,unorm16,snorm16,unorm32,snorm32,unorm2101010,snorm2101010)
    .map(q.format===_.U).reduce(_ || _)
  extracted.enq.bits.fixed:=q.format===fixed16_16.U
  extracted.enq.bits.converted:=q.format>float16.U
  extracted.enq.bits.fallback:=Mux(q.format===float16.U,TypedColorDecode.half(q.word(15,0)),VertexInputFormat.expand(q.word,q.format))

  private val e=extracted.deq.bits
  // Extraction produces only these widths and zeroes every higher raw bit.
  // Negate modulo the original width instead of sign-extending through two
  // variable shifts. The only signed magnitude above maximum is the minimum.
  private val rawMask=MuxLookup(e.bits,"hffffffff".U(32.W))(
    Seq(2,8,10,16).map(b=>b.U->((BigInt(1)<<b)-1).U(32.W)))
  private val signMask=MuxLookup(e.bits,"h80000000".U(32.W))(
    Seq(2,8,10,16).map(b=>b.U->(BigInt(1)<<(b-1)).U(32.W)))
  private val negative=e.signed && (e.raw & signMask).orR
  private val negate=Module(new BlockPrefixAdder(32))
  negate.io.a:= ~e.raw;negate.io.b:=0.U;negate.io.carryIn:=true.B
  private val absolute=Mux(negative,negate.io.sum(31,0)&rawMask,e.raw)
  private val maximum=rawMask>>1
  magnitude.enq.valid:=extracted.deq.valid;extracted.deq.ready:=magnitude.enq.ready
  magnitude.enq.bits.n:=Mux(e.normalized && e.signed && e.raw===signMask,maximum,absolute)
  magnitude.enq.bits.bits:=e.bits-(e.normalized && e.signed).asUInt
  magnitude.enq.bits.normalized:=e.normalized;magnitude.enq.bits.fixed:=e.fixed
  magnitude.enq.bits.negative:=negative;magnitude.enq.bits.converted:=e.converted;magnitude.enq.bits.fallback:=e.fallback

  private val m=magnitude.deq.bits
  private val repeated=MuxLookup(m.bits,0.U(64.W))(Seq(1,2,7,8,9,10,15,16,31,32).map{b=>
    val copies=(64+b-1)/b;val width=copies*b
    b.U->Fill(copies,m.n(b-1,0))(width-1,width-64)
  })
  fraction.enq.valid:=magnitude.deq.valid;magnitude.deq.ready:=fraction.enq.ready
  fraction.enq.bits.source:=Mux(m.normalized,repeated,Cat(m.n,0.U(32.W)))
  fraction.enq.bits.baseExponent:=Mux(m.normalized,126.U(9.W),158.U(9.W)-Mux(m.fixed,16.U,0.U))
  fraction.enq.bits.negative:=m.negative;fraction.enq.bits.zero:=m.n===0.U
  fraction.enq.bits.converted:=m.converted;fraction.enq.bits.fallback:=m.fallback

  private val f=fraction.deq.bits;private val leading=PriorityEncoder(Reverse(f.source))
  aligned.enq.valid:=fraction.deq.valid;fraction.deq.ready:=aligned.enq.ready
  aligned.enq.bits.normalized:=(f.source<<leading)(63,0);aligned.enq.bits.exponent:=f.baseExponent-leading
  aligned.enq.bits.negative:=f.negative;aligned.enq.bits.zero:=f.zero
  aligned.enq.bits.converted:=f.converted;aligned.enq.bits.fallback:=f.fallback

  private val a=aligned.deq.bits;private val n=a.normalized
  private val significand=n(63,40)+&(n(39) && (n(38,0).orR || n(40)))
  private val exponent=a.exponent+significand(24)
  private val converted=Mux(a.zero,0.U(32.W),Cat(a.negative,exponent(7,0),significand(22,0)))
  rounded.enq.valid:=aligned.deq.valid;aligned.deq.ready:=rounded.enq.ready
  rounded.enq.bits:=Mux(a.converted,converted,a.fallback)
  io.out<>rounded.deq
}
