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
  * exclude a current sample. Bounds are withheld while the two write-observation
  * stages drain; input writes suppress trust immediately. Unobserved/generic
  * writes invalidate the summary.
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
  private def extremum(xs: Seq[UInt],less: Boolean): UInt={
    require(xs.nonEmpty)
    if(xs.size==1)xs.head else {
      val halves=xs.splitAt(xs.size/2)
      val a=extremum(halves._1,less);val b=extremum(halves._2,less)
      Mux(if(less)a<b else a>b,a,b)
    }
  }
  private def low(xs: Seq[UInt]): UInt=extremum(xs,true)
  private def high(xs: Seq[UInt]): UInt=extremum(xs,false)
  private val events=io.writes.map(w=>w.valid && bound && w.bits.word<words)
  private val coverage=io.writes.zip(events).map{case(w,valid)=>
    Mux(valid,(1.U(maxWords.W)<<w.bits.word)(maxWords-1,0),0.U(maxWords.W))
  }
  // First compare the two samples within each physical word. Keep word-range
  // decoding out of those comparators, and register coverage independently.
  private val observed=RegInit(VecInit(Seq.fill(ports)(false.B)))
  private val wordMinimum=Reg(Vec(ports,UInt(24.W)));private val wordMaximum=Reg(Vec(ports,UInt(24.W)))
  private val wordCoverage=Reg(Vec(ports,UInt(maxWords.W)))
  for(i<-0 until ports){
    observed(i):=events(i)
    wordMinimum(i):=low(io.writes(i).bits.depths.toSeq)
    wordMaximum(i):=high(io.writes(i).bits.depths.toSeq)
    wordCoverage(i):=coverage(i)
  }
  // Accept every cycle, including back-to-back complete epochs. Only the
  // observer is pipelined: canonical SRAM writes never wait for this state.
  private val pending=RegNext(observed.asUInt.orR,false.B)
  private val writeMinimum=RegNext(low((0 until ports).map(i=>Mux(observed(i),wordMinimum(i),"hffffff".U(24.W)))))
  private val writeMaximum=RegNext(high((0 until ports).map(i=>Mux(observed(i),wordMaximum(i),0.U(24.W)))))
  private val pendingCoverage=RegNext(wordCoverage.reduce(_|_))
  when(io.invalidate || io.bind.valid){observed.foreach(_:=false.B);pending:=false.B}
  private val nextCovered=covered|pendingCoverage
  private val required=VecInit((0 until maxWords).map(i=>i.U<words)).asUInt
  private val nextMinimum=low(Seq(epochMinimum,writeMinimum))
  private val nextMaximum=high(Seq(epochMaximum,writeMaximum))
  private val complete=bound && (nextCovered&required)===required
  private val observing=io.writes.map(_.valid).reduce(_||_) || observed.asUInt.orR || pending
  io.bounds.valid:=known && !io.invalidate && !io.bind.valid && !observing
  io.bounds.minimum:=minimum;io.bounds.maximum:=maximum
  when(io.invalidate){bound:=false.B;known:=false.B;covered:=0.U
    epochMinimum:="hffffff".U;epochMaximum:=0.U
  }.elsewhen(io.bind.valid){
    assert(io.bind.bits.words=/=0.U && io.bind.bits.words<=maxWords.U,"invalid depth-summary extent")
    bound:=true.B;known:=io.bind.bits.clear;words:=io.bind.bits.words
    minimum:=io.bind.bits.depth;maximum:=io.bind.bits.depth
    covered:=0.U;epochMinimum:="hffffff".U;epochMaximum:=0.U
  }.elsewhen(pending){
    when(complete){
      minimum:=nextMinimum;maximum:=nextMaximum;known:=true.B
      covered:=0.U;epochMinimum:="hffffff".U;epochMaximum:=0.U
    }.otherwise{
      when(known){minimum:=low(Seq(minimum,writeMinimum));maximum:=high(Seq(maximum,writeMaximum))}
      covered:=nextCovered;epochMinimum:=nextMinimum;epochMaximum:=nextMaximum
    }
  }
}
