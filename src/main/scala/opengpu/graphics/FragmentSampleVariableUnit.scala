package opengpu.graphics

import chisel3._
import chisel3.util._

/** Shader sample variables for per-sample fragment execution.
  *
  * Publishes `gl_SampleID`, the programmed `gl_SamplePosition` and
  * `gl_SampleMaskIn` for the active 1/2/4 sample count. The sample ID must be
  * below the count and coverage must be canonical; unsupported counts are
  * rejected. The sample mask is the incoming coverage under the active count.
  */
object FragmentSampleVariableStatus {val ok=0;val invalid=1}

class FragmentSampleVariableRequest extends Bundle {
  val tag=UInt(8.W);val sampleId=UInt(2.W);val sampleCount=UInt(3.W)
  val coverage=UInt(4.W);val positions=Vec(4,Vec(2,UInt(32.W)))
}
class FragmentSampleVariableResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val sampleId=UInt(2.W)
  val samplePosition=Vec(2,UInt(32.W));val sampleMaskIn=UInt(4.W)
}

class FragmentSampleVariableUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentSampleVariableRequest))
    val out=Decoupled(new FragmentSampleVariableResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new FragmentSampleVariableResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private val validCount=q.sampleCount===1.U || q.sampleCount===2.U || q.sampleCount===4.U
  private val lowMask=MuxLookup(q.sampleCount,0.U(4.W))(Seq(1.U->1.U(4.W),2.U->3.U(4.W),4.U->15.U(4.W)))
  private val canonical=(q.coverage>>q.sampleCount)===0.U
  private val idOk=q.sampleId<q.sampleCount
  private val legal=validCount && canonical && idOk
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,FragmentSampleVariableStatus.ok.U,FragmentSampleVariableStatus.invalid.U)
    result.sampleId:=Mux(legal,q.sampleId,0.U)
    result.samplePosition:=Mux(legal,q.positions(q.sampleId),VecInit(Seq(0.U(32.W),0.U(32.W))))
    result.sampleMaskIn:=Mux(legal,q.coverage&lowMask,0.U(4.W))
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
