package opengpu.graphics

import chisel3._
import chisel3.util._

/** Normalized binary32 coordinates for one mip-level axis. Mode3 is internal
  * cube border clamping; it is not a legal GXS1 wrap enumeration.
  */
class TextureFloatAxisRequest extends Bundle {
  val coordinate=UInt(32.W);val extent=UInt(14.W);val wrap=UInt(2.W)
  val linear=Bool();val tag=UInt(32.W)
}
class TextureFloatAxisResult extends Bundle {
  val first=SInt(16.W);val second=SInt(16.W);val weight=UInt(32.W)
  val status=UInt(3.W);val tag=UInt(32.W)
}
object TextureFloatAxisWrap {val repeat=0;val clamp=1;val mirroredRepeat=2;val cubeBorder=3}

/** Exact binary-rational tap selection, followed by RNE binary32 weight
  * conversion. Retain149 fractional bits, including every FP32 subnormal;
  * reducing periods before multiplication bounds arbitrary finite inputs.
  * Seven elastic stages accept one axis per cycle when unstalled. Two-entry
  * stage queues break the ready path. There is no memory or cancellation
  * ownership here: parents drain every accepted tag before retiring work.
  */
class TextureFloatAxisPipeline extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new TextureFloatAxisRequest))
    val out=Decoupled(new TextureFloatAxisResult)
  })
  private class Meta extends Bundle {
    val extent=UInt(14.W);val wrap=UInt(2.W);val linear=Bool();val tag=UInt(32.W);val invalid=Bool()
  }
  private class Reduced extends Bundle {
    val meta=new Meta;val negative=Bool();val aboveOne=Bool();val aboveTwo=Bool()
    val mantissa=UInt(24.W);val shift=UInt(8.W)
  }
  private class Product extends Bundle {
    val meta=new Meta;val negative=Bool();val aboveOne=Bool();val aboveTwo=Bool()
    val product=UInt(38.W);val shift=UInt(8.W)
  }
  private class Aligned extends Bundle {val meta=new Meta;val scaled=SInt(166.W)}
  private class Located extends Bundle {
    val meta=new Meta;val first=SInt(18.W);val second=SInt(18.W);val fraction=UInt(149.W)
  }
  private class Leading extends Bundle {
    val result=new TextureFloatAxisResult;val fraction=UInt(149.W);val leading=UInt(8.W);val zero=Bool()
  }
  private class Normalized extends Bundle {
    val result=new TextureFloatAxisResult;val mantissa=UInt(24.W);val guard=Bool();val sticky=Bool()
    val exponent=UInt(8.W);val subnormal=UInt(23.W);val isSubnormal=Bool();val zero=Bool()
  }
  private val reduced=Module(new Queue(new Reduced,2)).io
  private val products=Module(new Queue(new Product,2)).io
  private val aligned=Module(new Queue(new Aligned,2)).io
  private val located=Module(new Queue(new Located,2)).io
  private val leading=Module(new Queue(new Leading,2)).io
  private val normalized=Module(new Queue(new Normalized,2)).io
  private val rounded=Module(new Queue(new TextureFloatAxisResult,2)).io

  reduced.enq.valid:=io.in.valid;io.in.ready:=reduced.enq.ready
  private val input=io.in.bits;private val exponent=input.coordinate(30,23)
  private val significand=Cat(exponent=/=0.U,input.coordinate(22,0))
  // abs(u) mod2 has at most24 significant bits. For E>=151 every
  // representable finite input is an even integer; E150 retains parity.
  private val keptBits=Mux(exponent<=127.U,24.U,151.U-exponent)(4,0)
  private val mask=((1.U(25.W)<<keptBits)-1.U)(23,0)
  reduced.enq.bits.meta.extent:=input.extent;reduced.enq.bits.meta.wrap:=input.wrap
  reduced.enq.bits.meta.linear:=input.linear;reduced.enq.bits.meta.tag:=input.tag
  reduced.enq.bits.meta.invalid:=exponent===255.U || input.extent===0.U
  reduced.enq.bits.negative:=input.coordinate(31)
  reduced.enq.bits.aboveOne:=input.coordinate(30,0)>="h3f800000".U
  reduced.enq.bits.aboveTwo:=input.coordinate(30,0)>="h40000000".U
  reduced.enq.bits.mantissa:=Mux(exponent>=151.U,0.U,significand&mask)
  reduced.enq.bits.shift:=Mux(exponent===0.U || exponent>=151.U,0.U,exponent-1.U)

  products.enq.valid:=reduced.deq.valid;reduced.deq.ready:=products.enq.ready
  products.enq.bits.meta:=reduced.deq.bits.meta;products.enq.bits.negative:=reduced.deq.bits.negative
  products.enq.bits.aboveOne:=reduced.deq.bits.aboveOne;products.enq.bits.aboveTwo:=reduced.deq.bits.aboveTwo
  products.enq.bits.shift:=reduced.deq.bits.shift
  products.enq.bits.product:=reduced.deq.bits.mantissa*reduced.deq.bits.meta.extent

  aligned.enq.valid:=products.deq.valid;products.deq.ready:=aligned.enq.ready
  private val p=products.deq.bits
  private val magnitude=((p.product.pad(165)<<p.shift)(164,0)).pad(166).asSInt
  private val unit=Cat(p.meta.extent,0.U(149.W)).pad(166).asSInt
  private val period=Cat(p.meta.extent,0.U(150.W)).pad(166).asSInt
  private val periodic=Mux(p.negative && magnitude=/=0.S,period-magnitude,magnitude)
  private val clamped=Mux(p.negative,0.S,Mux(p.aboveOne,unit,magnitude))
  // Outside[-1,2], both border taps coincide. Saturating there preserves
  // their value while keeping both integer coordinates tightly bounded.
  private val border=Mux(p.negative,Mux(p.aboveOne,-unit,-magnitude),Mux(p.aboveTwo,period,magnitude))
  aligned.enq.bits.meta:=p.meta
  aligned.enq.bits.scaled:=Mux(p.meta.wrap===TextureFloatAxisWrap.clamp.U,clamped,
    Mux(p.meta.wrap===TextureFloatAxisWrap.cubeBorder.U,border,periodic))

  located.enq.valid:=aligned.deq.valid;aligned.deq.ready:=located.enq.ready
  private val a=aligned.deq.bits;private val scaled=a.scaled.asUInt
  private val whole=scaled(165,149).asSInt.pad(18)
  private val halfBorrow=a.meta.linear && !scaled(148)
  private val first=whole-halfBorrow.asUInt.zext
  located.enq.bits.meta:=a.meta;located.enq.bits.first:=first
  located.enq.bits.second:=Mux(a.meta.linear,first+1.S,first)
  located.enq.bits.fraction:=Mux(a.meta.linear,scaled(148,0)^(BigInt(1)<<148).U(149.W),0.U)

  leading.enq.valid:=located.deq.valid;located.deq.ready:=leading.enq.ready
  private val l=located.deq.bits
  private def wrap(index: SInt): SInt={
    val n=l.meta.extent.zext.pad(18);val twoN=(l.meta.extent<<1).zext.pad(18)
    // Period reduction bounds repeat/mirror indices to[-1,2N]. The two
    // exceptional endpoints are known constants, so choose them directly
    // instead of chaining modulo addition/subtraction into a second subtract.
    val repeat=Mux(index<0.S,n-1.S,Mux(index>=twoN,0.S,Mux(index>=n,index-n,index)))
    val mirror=Mux(index<0.S || index>=twoN,0.S,Mux(index>=n,twoN+(~index.asUInt).asSInt,index))
    val clamp=Mux(index<0.S,0.S,Mux(index>=n,n-1.S,index))
    val border=Mux(index<(-1).S,(-1).S,Mux(index>n,n,index))
    MuxLookup(l.meta.wrap,repeat)(Seq(TextureFloatAxisWrap.clamp.U->clamp,
      TextureFloatAxisWrap.mirroredRepeat.U->mirror,TextureFloatAxisWrap.cubeBorder.U->border))
  }
  leading.enq.bits.result:=0.U.asTypeOf(leading.enq.bits.result)
  leading.enq.bits.result.tag:=l.meta.tag
  leading.enq.bits.result.status:=Mux(l.meta.invalid,TextureImageResourceStatus.coordinate.U,0.U)
  when(!l.meta.invalid){leading.enq.bits.result.first:=wrap(l.first);leading.enq.bits.result.second:=wrap(l.second)}
  leading.enq.bits.fraction:=l.fraction;leading.enq.bits.zero:= !l.fraction.orR
  leading.enq.bits.leading:=PriorityEncoder(Reverse(l.fraction))

  normalized.enq.valid:=leading.deq.valid;leading.deq.ready:=normalized.enq.ready
  private val f=leading.deq.bits
  private val shifted=(f.fraction<<f.leading)(148,0)
  normalized.enq.bits.result:=f.result;normalized.enq.bits.zero:=f.zero
  normalized.enq.bits.mantissa:=shifted(148,125);normalized.enq.bits.guard:=shifted(124)
  normalized.enq.bits.sticky:=shifted(123,0).orR;normalized.enq.bits.exponent:=126.U-f.leading
  normalized.enq.bits.isSubnormal:=f.leading>=126.U;normalized.enq.bits.subnormal:=f.fraction(22,0)

  rounded.enq.valid:=normalized.deq.valid;normalized.deq.ready:=rounded.enq.ready
  private val q=normalized.deq.bits
  private val mantissa=q.mantissa+&(q.guard && (q.sticky || q.mantissa(0)))
  private val finalExponent=q.exponent+mantissa(24)
  private val normal=Cat(0.U(1.W),finalExponent, mantissa(22,0))
  rounded.enq.bits:=q.result
  rounded.enq.bits.weight:=Mux(q.zero || q.result.status=/=0.U,0.U,Mux(q.isSubnormal,q.subnormal.pad(32),normal))
  io.out<>rounded.deq
}
