package opengpu.graphics

import chisel3._
import chisel3.util._

/** Versioned transport of a shader-written sample mask.
  *
  * Carries the mask in a versioned fragment-output record, rejects unknown
  * versions and noncanonical coverage, ignores mask bits above the active
  * sample count, and applies discard. The effective mask is the value a
  * versioned consumer combines with raster coverage.
  */
object FragmentSampleMaskOutputVersion {val v1=1}
object FragmentSampleMaskOutputStatus {val ok=0;val invalid=1}

class FragmentSampleMaskOutputRequest extends Bundle {
  val tag=UInt(8.W);val version=UInt(8.W);val sampleCount=UInt(3.W)
  val coverage=UInt(4.W);val shaderMask=UInt(4.W);val written=Bool();val discard=Bool()
}
class FragmentSampleMaskOutputResult extends Bundle {
  val tag=UInt(8.W);val version=UInt(8.W);val status=UInt(2.W)
  val sampleCount=UInt(3.W);val effectiveMask=UInt(4.W);val discard=Bool()
}

class FragmentSampleMaskOutputUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new FragmentSampleMaskOutputRequest))
    val out=Decoupled(new FragmentSampleMaskOutputResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new FragmentSampleMaskOutputResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private val validCount=q.sampleCount===1.U || q.sampleCount===2.U || q.sampleCount===4.U
  private val lowMask=MuxLookup(q.sampleCount,0.U(4.W))(Seq(1.U->1.U(4.W),2.U->3.U(4.W),4.U->15.U(4.W)))
  private val canonical=(q.coverage>>q.sampleCount)===0.U
  private val legal=validCount && canonical && q.version===FragmentSampleMaskOutputVersion.v1.U
  private val effective=Mux(q.discard,0.U(4.W),Mux(q.written,q.coverage&q.shaderMask&lowMask,q.coverage&lowMask))
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag;result.version:=q.version
    result.status:=Mux(legal,FragmentSampleMaskOutputStatus.ok.U,FragmentSampleMaskOutputStatus.invalid.U)
    result.sampleCount:=Mux(legal,q.sampleCount,0.U)
    result.effectiveMask:=Mux(legal,effective,0.U)
    result.discard:=Mux(legal,q.discard,false.B)
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
