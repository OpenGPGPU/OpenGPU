package opengpu.graphics

import chisel3._
import chisel3.util._

/** The caller supplies D24 bounds from the same resident owner and draw.
  * Older writes must preserve rejection (monotonic compare or read-only depth).
  */
class FragmentEarlyDepthState extends Bundle {
  val bounds=new D24DepthBounds
  val enabled=Bool();val depthWrite=Bool();val function=UInt(3.W)
}
class D24EarlyDepthRequest(samples: Int) extends Bundle {
  val raw=Vec(samples,UInt(32.W));val covered=UInt(samples.W)
  val bounds=new D24DepthBounds;val function=UInt(3.W);val tag=UInt(8.W)
}
class D24EarlyDepthResult extends Bundle {val rejected=Bool();val tag=UInt(8.W)}

/** Four-clock conservative whole-quad test, II=1, using the exact late D24 RNE
  * conversion. It has no attachment side effects and never rejects empty or
  * unknown coverage. Every covered depth must fail against every possible
  * resident value in the interval, including equality at its boundaries.
  * The caller must consume every valid result; output has no backpressure.
  */
class ConservativeD24EarlyDepthTest(samples: Int=16) extends Module {
  require(samples>=1 && samples<=16 && samples%4==0)
  val io=IO(new Bundle {
    val in=Flipped(Valid(new D24EarlyDepthRequest(samples)))
    val out=Valid(new D24EarlyDepthResult);val busy=Output(Bool())
  })
  private val converters=Seq.fill(samples/4)(Module(new PreparedD24Depths()))
  for((converter,group)<-converters.zipWithIndex){
    converter.io.valid:=io.in.valid;converter.io.tag:=io.in.bits.tag
    for(s<-0 until 4)converter.io.raw(s):=io.in.bits.raw(group*4+s)
  }
  private val v1=RegNext(io.in.valid,false.B);private val v2=RegNext(v1,false.B);private val v3=RegNext(v2,false.B)
  private val request1=RegEnable(io.in.bits,io.in.valid)
  private val request2=RegEnable(request1,v1);private val request3=RegEnable(request2,v2)
  private val result=Reg(new D24EarlyDepthResult)
  private val ready=converters.head.io.out.valid
  private val fails=converters.flatMap(_.io.out.bits.values).map{z=>
    val lo=request3.bounds.minimum;val hi=request3.bounds.maximum;val value=z(23,0)
    MuxLookup(request3.function,false.B)(Seq(
      0.U->(value>=hi),1.U->(value>hi),2.U->(value<=lo),3.U->(value<lo),
      4.U->(value<lo || value>hi),5.U->(lo===hi && value===lo),6.U->false.B,7.U->true.B))
  }
  when(ready){
    result.rejected:=request3.bounds.valid && request3.covered.orR &&
      fails.zipWithIndex.map{case(f,i)=> !request3.covered(i) || f}.reduce(_ && _)
    result.tag:=converters.head.io.out.bits.tag
    assert(v3,"early depth metadata lost its exact conversion epoch")
  }
  io.out.valid:=RegNext(ready,false.B);io.out.bits:=result
  io.busy:=io.in.valid || v1 || v2 || v3 || io.out.valid
}
