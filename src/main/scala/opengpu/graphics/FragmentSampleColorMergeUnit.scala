package opengpu.graphics

import chisel3._
import chisel3.util._

/** Merge per-sample UNORM8 colours into one resolved pixel.
  *
  * Each covered sample among the active 1/2/4 sample count contributes one
  * RGBA8 value. Channels are summed and divided by the covered count with
  * round-half-up. Uncovered samples are ignored; a pixel with no covered
  * samples resolves to zero. Coverage or sample counts outside the canonical
  * set are rejected.
  */
object FragmentSampleColorMergeStatus {val ok=0;val invalid=1}

class FragmentSampleColorMergeRequest extends Bundle {
  val tag=UInt(8.W);val samples=Vec(4,Vec(4,UInt(8.W)));val coverage=UInt(4.W);val sampleCount=UInt(3.W)
}
class FragmentSampleColorMergeResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val covered=UInt(3.W);val rgba=Vec(4,UInt(8.W))
}

class FragmentSampleColorMergeUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentSampleColorMergeRequest))
    val out=Decoupled(new FragmentSampleColorMergeResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new FragmentSampleColorMergeResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private val validCount=q.sampleCount===1.U || q.sampleCount===2.U || q.sampleCount===4.U
  private val lowMask=MuxLookup(q.sampleCount,0.U(4.W))(Seq(1.U->1.U(4.W),2.U->3.U(4.W),4.U->15.U(4.W)))
  private val canonical=(q.coverage>>q.sampleCount)===0.U
  private val covered=q.coverage&lowMask
  private val coveredCount=PopCount(covered)
  private val legal=validCount && canonical
  private val sums=(0 until 4).map{c=>
    (0 until 4).map{s=>Mux(covered(s),q.samples(s)(c).pad(10),0.U(10.W))}.reduce((a,b)=>a+&b)}
  private val merged=(0 until 4).map{c=>
    val sum=sums(c)
    Mux(coveredCount===0.U,0.U(8.W),((sum+(coveredCount>>1.U))/coveredCount)(7,0))}
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,FragmentSampleColorMergeStatus.ok.U,FragmentSampleColorMergeStatus.invalid.U)
    result.covered:=Mux(legal,coveredCount,0.U)
    for(c<-0 until 4)result.rgba(c):=Mux(legal,merged(c),0.U(8.W))
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
