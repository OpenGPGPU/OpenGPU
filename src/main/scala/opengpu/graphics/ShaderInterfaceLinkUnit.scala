package opengpu.graphics

import chisel3._
import chisel3.util._

/** Link-time validation of versioned vertex/fragment interface blocks. */
object ShaderInterfaceLinkStatus {val ok=0;val invalid=1}

/** One interface location. Interpolation 0=smooth,1=flat,2=centroid. */
class ShaderInterfaceSlot extends Bundle {
  val valid=Bool();val location=UInt(5.W);val kind=UInt(2.W)
  val components=UInt(3.W);val arraySize=UInt(8.W);val interpolation=UInt(2.W)
}
class ShaderInterfaceLinkRequest extends Bundle {
  val tag=UInt(8.W);val vertexCount=UInt(5.W);val fragmentCount=UInt(5.W)
  val vertex=Vec(16,new ShaderInterfaceSlot);val fragment=Vec(16,new ShaderInterfaceSlot)
}
class ShaderInterfaceLinkResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W)
  val matchedMask=UInt(16.W);val missingMask=UInt(16.W)
  val duplicateMask=UInt(16.W);val vertexDuplicateMask=UInt(16.W);val mismatchMask=UInt(16.W)
}

/** Every active fragment input must match exactly one vertex output at the
  * same location with identical kind, component count, array size and
  * interpolation. Extra vertex outputs are allowed. Duplicate locations on
  * either side, inactive slots marked valid, or counts above sixteen reject
  * the whole interface.
  */
class ShaderInterfaceLinkUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ShaderInterfaceLinkRequest))
    val out=Decoupled(new ShaderInterfaceLinkResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new ShaderInterfaceLinkResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private val vActive=(0 until 16).map{i=>q.vertexCount>i.U && q.vertex(i).valid}
  private val fActive=(0 until 16).map{i=>q.fragmentCount>i.U && q.fragment(i).valid}
  private val vExtra=(0 until 16).map{i=>q.vertexCount>i.U && !q.vertex(i).valid}
  private val fExtra=(0 until 16).map{i=>q.fragmentCount>i.U && !q.fragment(i).valid}
  private val countsLegal=q.vertexCount<=16.U && q.fragmentCount<=16.U &&
    !vExtra.reduce(_||_) && !fExtra.reduce(_||_)
  private def dup(slots: Seq[ShaderInterfaceSlot],act: Seq[Bool])=
    (0 until 16).map{i=>(0 until i).map{j=>act(j)&&act(i)&&slots(j).location===slots(i).location}.foldLeft(false.B)(_||_)}
  private val vDup=dup(q.vertex,vActive);private val fDup=dup(q.fragment,fActive)
  private val dupAny=(vDup++fDup).reduce(_||_)
  private val matchVec=(0 until 16).map{f=>
    (0 until 16).map{v=>vActive(v)&&fActive(f)&&q.vertex(v).location===q.fragment(f).location}}
  private val matched=matchVec.map(_.reduce(_||_))
  private val fieldEqual=matchVec.map{row=>
    (0 until 16).map{v=>row(v)&&q.vertex(v).kind===q.fragment(0).kind}.reduce(_||_)} // placeholder
  // Compare against the active fragment slot fields explicitly.
  private val equal=(0 until 16).map{f=>
    (0 until 16).map{v=>matchVec(f)(v)&&q.vertex(v).kind===q.fragment(f).kind &&
      q.vertex(v).components===q.fragment(f).components &&
      q.vertex(v).arraySize===q.fragment(f).arraySize &&
      q.vertex(v).interpolation===q.fragment(f).interpolation}.reduce(_||_)}
  private val missing=(0 until 16).map{f=>fActive(f)&& !matched(f)}
  private val mismatch=(0 until 16).map{f=>fActive(f)&&matched(f)&& !equal(f)}
  private val legal=countsLegal && !dupAny &&
    !(missing++mismatch).reduce(_||_)
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,ShaderInterfaceLinkStatus.ok.U,ShaderInterfaceLinkStatus.invalid.U)
    result.matchedMask:=VecInit(matched).asUInt
    result.missingMask:=VecInit(missing).asUInt
    result.duplicateMask:=VecInit(fDup).asUInt
    result.vertexDuplicateMask:=VecInit(vDup).asUInt
    result.mismatchMask:=VecInit(mismatch).asUInt
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
