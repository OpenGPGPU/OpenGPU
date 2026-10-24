package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.BlockPrefixAdder

class TypedDepthVectorRequest(samples: Int) extends Bundle {
  val raw=Vec(samples,UInt(32.W));val format=UInt(3.W);val tag=UInt(8.W)
}
class TypedDepthVectorResult(samples: Int) extends Bundle {
  val values=Vec(samples,UInt(32.W));val tag=UInt(8.W)
}
private class DepthProduct extends Bundle {
  val product=UInt(48.W);val shift=UInt(6.W)
  val unorm=Bool();val zero=Bool();val saturate=Bool();val maximum=UInt(24.W);val other=UInt(32.W)
}
private class DepthQuantized extends Bundle {
  val truncated=UInt(24.W);val increment=Bool()
  val unorm=Bool();val zero=Bool();val saturate=Bool();val maximum=UInt(24.W);val other=UInt(32.W)
}

/** Three-clock vector preparation: classification/exact product, alignment
  * and guard/sticky, then one RNE step at attachment precision. A vector may arrive
  * each clock; consumers must capture every valid output. An isolated output
  * remains held until the next valid vector. This block never flushes on cancel.
  */
class PreparedTypedDepths(samples: Int=4) extends Module {
  require(samples>=1 && samples<=4)
  val io=IO(new Bundle {
    val in=Flipped(Valid(new TypedDepthVectorRequest(samples)))
    val out=Valid(new TypedDepthVectorResult(samples))
  })
  private val products=Reg(Vec(samples,new DepthProduct))
  private val quantized=Reg(Vec(samples,new DepthQuantized))
  private val result=Reg(new TypedDepthVectorResult(samples))
  private val v1=RegNext(io.in.valid,false.B);private val v2=RegNext(v1,false.B)
  private val v3=RegNext(v2,false.B)
  private val tag1=RegEnable(io.in.bits.tag,io.in.valid);private val tag2=RegEnable(tag1,v1)
  io.out.valid:=v3;io.out.bits:=result
  for(s<-0 until samples){
    val raw=io.in.bits.raw(s);val format=io.in.bits.format
    val sig=Cat(raw(30,23).orR,raw(22,0))
    val product=Module(new BlockPrefixAdder(48))
    product.io.a:=Mux(format===TypedDepthFormat.d16.U,sig.pad(48)<<16,sig.pad(48)<<24)
    product.io.b:= ~sig.pad(48);product.io.carryIn:=true.B
    when(io.in.valid){
      val distance=150.U(9.W)-raw(30,23)
      products(s).product:=product.io.sum(47,0)
      products(s).shift:=Mux(raw(30,23)===0.U || distance>49.U,49.U,distance)
      products(s).unorm:=format===TypedDepthFormat.d16.U || format===TypedDepthFormat.d24.U || format===TypedDepthFormat.d24s8.U
      products(s).zero:=raw(31) || Fp32ViewportState.nan(raw)
      products(s).saturate:=raw>="h3f800000".U
      products(s).maximum:=Mux(format===TypedDepthFormat.d16.U,"hffff".U,"hffffff".U)
      products(s).other:=Fp32ViewportState.clampDepth(raw)
    }
    val p=products(s)
    // One alignment supplies both retained bits and guard. The sticky mask
    // covers exactly shift-1 low product bits; shift49 has guard zero.
    val aligned=(p.product<<1)>>p.shift
    val stickyMask=(~0.U(48.W))>>(49.U(6.W)-p.shift)
    when(v1){
      quantized(s).truncated:=aligned(24,1)
      quantized(s).increment:=aligned(0) && ((p.product&stickyMask).orR || aligned(1))
      quantized(s).unorm:=p.unorm;quantized(s).zero:=p.zero;quantized(s).saturate:=p.saturate
      quantized(s).maximum:=p.maximum;quantized(s).other:=p.other
    }
    val q=quantized(s)
    val rounded=q.truncated+&q.increment
    val fixed=Mux(q.zero,0.U,Mux(q.saturate,q.maximum,rounded))(23,0)
    when(v2){result.values(s):=Mux(q.unorm,fixed.pad(32),q.other)}
  }
  when(v2){result.tag:=tag2}
}

/** Constant-format boundary also used to qualify the exact D24 specialization. */
class PreparedD24Depths(samples: Int=4) extends Module {
  val io=IO(new Bundle {
    val raw=Input(Vec(samples,UInt(32.W)));val tag=Input(UInt(8.W));val valid=Input(Bool())
    val out=Valid(new TypedDepthVectorResult(samples))
  })
  private val core=Module(new PreparedTypedDepths(samples))
  core.io.in.valid:=io.valid;core.io.in.bits.raw:=io.raw;core.io.in.bits.tag:=io.tag
  core.io.in.bits.format:=TypedDepthFormat.d24s8.U;io.out:=core.io.out
}
