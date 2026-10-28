package opengpu.graphics

import chisel3._
import chisel3.util._

class D24DepthBounds extends Bundle {
  val valid=Bool();val minimum=UInt(24.W);val maximum=UInt(24.W)
}
class D24SummaryBinding(maxWords: Int) extends Bundle {
  val words=UInt(log2Ceil(maxWords+1).W)
  val clear=Bool();val depth=UInt(24.W)
}
class D24SummaryWrite(maxWords: Int) extends Bundle {
  val word=UInt(math.max(1,log2Ceil(maxWords)).W)
  val depths=Vec(2,UInt(24.W))
}

/** Conservative bounds over a resident D24S8 plane, observed after full-word
  * writes become irrevocable. No SRAM arbitration or producer-ready dependency.
  *
  * A clear supplies the initial uniform bound. A load/dont-care binding stays
  * unknown until every word has been observed. Each coverage epoch accumulates
  * all values written, including overwritten values; only complete coverage
  * permits tightening. Between epochs every write expands the live interval.
  * Thus duplicates, reordered independent writes and simultaneous ports cannot
  * exclude a current sample. Unobserved/generic writes invalidate the summary.
  * Invalidation/rebinding requires the caller's ordinary ownership drain.
  */
class ConservativeD24DepthSummary(maxWords: Int=512,ports: Int=2) extends Module {
  require(isPow2(maxWords) && maxWords>=2 && maxWords<=512 && ports>=1 && ports<=4)
  val io=IO(new Bundle {
    val bind=Input(Valid(new D24SummaryBinding(maxWords)))
    val writes=Input(Vec(ports,Valid(new D24SummaryWrite(maxWords))))
    val invalidate=Input(Bool());val bounds=Output(new D24DepthBounds)
  })
  private val bound=RegInit(false.B);private val known=RegInit(false.B)
  private val words=Reg(UInt(log2Ceil(maxWords+1).W))
  private val minimum=Reg(UInt(24.W));private val maximum=Reg(UInt(24.W))
  private val covered=RegInit(0.U(maxWords.W))
  private val epochMinimum=RegInit("hffffff".U(24.W));private val epochMaximum=RegInit(0.U(24.W))
  private val events=io.writes.map(w=>w.valid && bound && w.bits.word<words)
  private val coverage=io.writes.zip(events).map{case(w,valid)=>
    Mux(valid,(1.U(maxWords.W)<<w.bits.word)(maxWords-1,0),0.U(maxWords.W))
  }.reduce(_|_)
  private val nextCovered=covered|coverage
  private val required=VecInit((0 until maxWords).map(i=>i.U<words)).asUInt
  private val lows=io.writes.zip(events).flatMap{case(w,valid)=>w.bits.depths.map(z=>Mux(valid,z,"hffffff".U(24.W)))}
  private val highs=io.writes.zip(events).flatMap{case(w,valid)=>w.bits.depths.map(z=>Mux(valid,z,0.U(24.W)))}
  private def low(xs: Seq[UInt]): UInt=xs.reduce((a,b)=>Mux(a<b,a,b))
  private def high(xs: Seq[UInt]): UInt=xs.reduce((a,b)=>Mux(a>b,a,b))
  private val writeMinimum=low(lows);private val writeMaximum=high(highs)
  private val nextMinimum=low(Seq(epochMinimum,writeMinimum))
  private val nextMaximum=high(Seq(epochMaximum,writeMaximum))
  private val complete=bound && (nextCovered&required)===required
  io.bounds.valid:=known && !io.invalidate && !io.bind.valid
  io.bounds.minimum:=minimum;io.bounds.maximum:=maximum
  when(io.invalidate){bound:=false.B;known:=false.B;covered:=0.U
    epochMinimum:="hffffff".U;epochMaximum:=0.U
  }.elsewhen(io.bind.valid){
    assert(io.bind.bits.words=/=0.U && io.bind.bits.words<=maxWords.U,"invalid depth-summary extent")
    bound:=true.B;known:=io.bind.bits.clear;words:=io.bind.bits.words
    minimum:=io.bind.bits.depth;maximum:=io.bind.bits.depth
    covered:=0.U;epochMinimum:="hffffff".U;epochMaximum:=0.U
  }.elsewhen(VecInit(events).asUInt.orR){
    when(complete){
      minimum:=nextMinimum;maximum:=nextMaximum;known:=true.B
      covered:=0.U;epochMinimum:="hffffff".U;epochMaximum:=0.U
    }.otherwise{
      when(known){minimum:=low(Seq(minimum,writeMinimum));maximum:=high(Seq(maximum,writeMaximum))}
      covered:=nextCovered;epochMinimum:=nextMinimum;epochMaximum:=nextMaximum
    }
  }
}
