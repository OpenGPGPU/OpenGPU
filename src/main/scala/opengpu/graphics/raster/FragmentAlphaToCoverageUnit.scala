package opengpu.graphics

import chisel3._
import chisel3.util._

/** Alpha-to-coverage conversion for 1/2/4 samples.
  *
  * Rounds clamped alpha to the sample count and enables that many low-order
  * samples in a deterministic order, intersecting with incoming coverage.
  * NaN, negative and zero alpha enable nothing; alpha at or above one enables
  * every sample under the active count. The converted coverage feeds a
  * subsequent sample-mask combine. Disabled conversion passes coverage.
  */
object FragmentAlphaToCoverageStatus {val ok=0;val invalid=1}

class FragmentAlphaToCoverageRequest extends Bundle {
  val tag=UInt(8.W);val alpha=UInt(32.W);val coverage=UInt(4.W);val sampleCount=UInt(3.W);val enable=Bool()
}
class FragmentAlphaToCoverageResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val covered=UInt(3.W);val coverage=UInt(4.W)
}

class FragmentAlphaToCoverageUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentAlphaToCoverageRequest))
    val out=Decoupled(new FragmentAlphaToCoverageResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new FragmentAlphaToCoverageResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private val validCount=q.sampleCount===1.U || q.sampleCount===2.U || q.sampleCount===4.U
  private val lowMask=MuxLookup(q.sampleCount,0.U(4.W))(Seq(1.U->1.U(4.W),2.U->3.U(4.W),4.U->15.U(4.W)))
  private val canonical=(q.coverage>>q.sampleCount)===0.U
  private val legal=validCount && canonical
  private def threshold(numerator: Int,denominator: Int): UInt=java.lang.Float.floatToRawIntBits(numerator.toFloat/denominator).U
  private val nanOrNeg=(q.alpha(30,23)==="hff".U && q.alpha(22,0).orR) || q.alpha(31)
  private def countAbove(ts: Seq[UInt])=ts.map(t=>q.alpha>=t).map(_.asUInt).reduce((a,b)=>a+&b)
  private val n1=countAbove(Seq(threshold(1,2)))
  private val n2=countAbove(Seq(threshold(1,4),threshold(3,4)))
  private val n4=countAbove(Seq(threshold(1,8),threshold(3,8),threshold(5,8),threshold(7,8)))
  private val wanted=Mux(nanOrNeg,0.U(3.W),
    Mux(q.sampleCount===1.U,n1,Mux(q.sampleCount===2.U,n2,n4)))
  private val lowSamples=MuxLookup(wanted,0.U(4.W))(Seq(0.U->0.U(4.W),1.U->1.U(4.W),2.U->3.U(4.W),
    3.U->7.U(4.W),4.U->15.U(4.W)))
  private val converted=q.coverage&lowSamples
  private val effective=Mux(q.enable,converted,q.coverage&lowMask)
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,FragmentAlphaToCoverageStatus.ok.U,FragmentAlphaToCoverageStatus.invalid.U)
    result.coverage:=Mux(legal,effective,0.U(4.W))
    result.covered:=Mux(legal,PopCount(effective),0.U(3.W))
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
