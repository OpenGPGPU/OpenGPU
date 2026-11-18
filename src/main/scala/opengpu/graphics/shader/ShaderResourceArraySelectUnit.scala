package opengpu.graphics

import chisel3._
import chisel3.util._

/** Dynamic selection of one entry in a shader resource array.
  *
  * Validates a dynamically uniform 32-bit index against the array length, the
  * stage descriptor limit and the bound-entry bitmap, then publishes the
  * logical slot. Sampler arrays require a dynamically uniform index; other
  * resource classes may use divergent indices. Zero lengths, out-of-range
  * indices and unbound entries are rejected without publishing a slot.
  */
object ShaderResourceArraySelectStatus {val ok=0;val invalid=1}

class ShaderResourceArraySelectRequest extends Bundle {
  val tag=UInt(8.W);val baseSlot=UInt(8.W);val arrayLength=UInt(8.W)
  val index=UInt(32.W);val stageLimit=UInt(8.W);val boundMask=UInt(16.W)
  /** 0=sampler, 1=uniform block, 2=image, 3=storage buffer. */
  val resourceClass=UInt(2.W);val indexUniform=Bool()
}
class ShaderResourceArraySelectResult extends Bundle {
  val tag=UInt(8.W);val status=UInt(2.W);val slot=UInt(8.W)
  val outOfRange=Bool();val unbound=Bool();val divergentSampler=Bool()
}

class ShaderResourceArraySelectUnit extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ShaderResourceArraySelectRequest))
    val out=Decoupled(new ShaderResourceArraySelectResult)
    val busy=Output(Bool())
  })
  private val phase=RegInit(false.B);private val result=Reg(new ShaderResourceArraySelectResult)
  io.in.ready:= !phase;io.out.valid:=phase;io.out.bits:=result;io.busy:=phase
  private val q=io.in.bits
  private val inArray=q.index<q.arrayLength
  private val slotWide=q.baseSlot.pad(33)+q.index.pad(33)
  private val inStage=slotWide<q.stageLimit.pad(33)
  private val bound=inArray && q.index<16.U && (q.boundMask>>q.index(3,0))(0)
  private val divergent=q.resourceClass===0.U && !q.indexUniform
  private val outOfRange= !inArray || !inStage
  private val legal=q.arrayLength>=1.U && q.arrayLength<=16.U && !divergent && !outOfRange && bound
  when(!phase && io.in.valid){
    result:=0.U.asTypeOf(result);result.tag:=q.tag
    result.status:=Mux(legal,ShaderResourceArraySelectStatus.ok.U,ShaderResourceArraySelectStatus.invalid.U)
    result.slot:=Mux(legal,slotWide(7,0),0.U)
    result.outOfRange:=outOfRange
    result.unbound:=inArray && inStage && !bound
    result.divergentSampler:=divergent
    phase:=true.B
  }.elsewhen(phase && io.out.ready){phase:=false.B}
}
