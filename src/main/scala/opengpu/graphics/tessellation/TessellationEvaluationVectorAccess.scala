package opengpu.graphics.tessellation

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest,VectorTextureRequest}

/** Internal TES program-v2 instruction. No old program/profile admits it. */
object TessellationEvaluationPatchOpcode {
  def legal(i:UInt):Bool=i(6,0)==="h2b".U && i(31,26)===33.U && i(14,12)===0.U
}
/** Captured with the real single-invocation TES launch, never GPU operands. */
class TessellationEvaluationPatchBinding extends Bundle {
  val version=UInt(8.W);val stage=UInt(3.W)
  val taskId=UInt(32.W);val sequence=UInt(32.W)
  val layout=new TessellationPatchLayout
}
object TessellationEvaluationPatchBinding {
  def legal(b:TessellationEvaluationPatchBinding):Bool={val l=b.layout
    b.version===1.U && b.stage===5.U && l.inputVertices>=1.U && l.inputVertices<=32.U &&
      l.outputVertices>=1.U && l.outputVertices<=32.U && l.inputComponents<=64.U && l.outputComponents<=64.U &&
      l.patchComponents<=120.U && l.inputVertices*l.inputComponents<=2048.U &&
      l.outputVertices*l.outputComponents+&l.patchComponents<=2048.U
  }
  def addressLegal(vertex:UInt,address:UInt,l:TessellationPatchLayout):Bool={
    val pp=address(1);val level=address(2);val c=address(9,3)
    !vertex(31,6).orR && !address(31,10).orR && address(0) &&
      Mux(level,pp && vertex===0.U && c<6.U,
        Mux(pp,vertex===0.U && c<l.patchComponents,vertex<l.outputVertices && c<l.outputComponents))
  }
}
class TessellationEvaluationPatchRequest(config:GpuConfig) extends Bundle {
  val scope=new TessellationEvaluationPatchBinding
  val warp=UInt(config.warpIdWidth.W);val mask=UInt(config.lanes.W)
  val vertices=Vec(config.lanes,UInt(32.W));val addresses=Vec(config.lanes,UInt(32.W))
}
/** One CU-local outstanding instruction. No scalar scratch/GS permission or
  * external memory transaction ID is borrowed. Cancellation drains offers and
  * accepted replies, then produces the real vector fault/retirement commit.
  */
class TessellationEvaluationVectorAccess(config:GpuConfig) extends Module {
  require(config.xLen==32 && config.lanes<=32 && config.warps>=2 && isPow2(config.warps))
  val io=IO(new Bundle {
    val start=Flipped(Valid(new TessellationEvaluationPatchBinding));val end=Input(Bool())
    val warpStart=Flipped(Valid(UInt(config.warpIdWidth.W)))
    val enabled=Output(Bool());val cancel=Input(Bool());val busy=Output(Bool());val status=Output(UInt(2.W))
    val in=Flipped(Decoupled(new VectorTextureRequest(config)))
    val request=Decoupled(new TessellationEvaluationPatchRequest(config))
    val result=Flipped(Decoupled(new TessellationWarpPatchResult(config.warps,config.lanes)))
    val commit=Decoupled(new VectorCommitRequest(config))
  })
  private object State extends ChiselEnum {val idle,check,offer,reply,commit=Value}
  private val state=RegInit(State.idle);private val enabled=RegInit(false.B)
  private val binding=Reg(new TessellationEvaluationPatchBinding);private val warp=Reg(UInt(config.warpIdWidth.W))
  private val warpValid=RegInit(false.B);private val saved=Reg(new VectorTextureRequest(config))
  private val mask=Reg(UInt(config.lanes.W));private val data=Reg(Vec(config.lanes,UInt(32.W)))
  private val status=RegInit(0.U(2.W));private val cancelled=RegInit(false.B)
  private val stop=io.cancel || cancelled
  io.enabled:=enabled;io.busy:=state=/=State.idle;io.status:=status
  when(io.start.valid){assert(state===State.idle && !enabled,"TES binding reused before complete drain")
    binding:=io.start.bits;enabled:=TessellationEvaluationPatchBinding.legal(io.start.bits);warpValid:=false.B}
  when(io.end){assert(state===State.idle,"TES binding released before commit drain");enabled:=false.B;warpValid:=false.B}
  when(io.warpStart.valid && enabled){assert(!warpValid,"TES patch transport requires one physical warp")
    warp:=io.warpStart.bits;warpValid:=true.B}
  when(state=/=State.idle && state=/=State.commit && io.cancel){cancelled:=true.B}
  io.in.ready:=enabled && warpValid && state===State.idle && !io.cancel && !io.end
  io.request.valid:=state===State.offer;io.request.bits.scope:=binding
  io.request.bits.warp:=saved.issued.decode.warpId;io.request.bits.mask:=mask
  io.request.bits.vertices:=saved.issued.vs1Data;io.request.bits.addresses:=saved.issued.vs2Data
  io.result.ready:=state===State.reply
  io.commit.valid:=state===State.commit;io.commit.bits:=0.U.asTypeOf(io.commit.bits)
  io.commit.bits.writeback.warpId:=saved.issued.decode.warpId
  io.commit.bits.writeback.vd:=saved.issued.decode.instruction(11,7)
  io.commit.bits.writeback.data:=data;io.commit.bits.writesVd:=status===0.U
  io.commit.bits.pc:=saved.issued.decode.pc;io.commit.bits.warpActiveMask:=saved.issued.decode.activeMask
  when(io.in.fire){saved:=io.in.bits;mask:=io.in.bits.executionMask & io.in.bits.issued.decode.activeMask
    data:=io.in.bits.issued.oldVdData;status:=0.U;cancelled:=false.B;state:=State.check}
  when(state===State.check){
    val bad=(0 until config.lanes).map(i=>mask(i) && !TessellationEvaluationPatchBinding.addressLegal(
      saved.issued.vs1Data(i),saved.issued.vs2Data(i),binding.layout)).reduce(_ || _)
    when(saved.issued.decode.warpId=/=warp || !TessellationEvaluationPatchOpcode.legal(saved.issued.decode.instruction) || bad){status:=1.U;state:=State.commit}
      .elsewhen(stop){status:=2.U;state:=State.commit}
      .elsewhen(!mask.orR){state:=State.commit}.otherwise{state:=State.offer}
  }
  when(io.request.fire){state:=State.reply}
  when(io.result.fire){
    when(io.result.bits.warp=/=saved.issued.decode.warpId || io.result.bits.status===1.U || io.result.bits.status===3.U){status:=1.U}
      .elsewhen(stop || io.result.bits.status===2.U){status:=2.U}
      .elsewhen(io.result.bits.retired=/=mask){status:=1.U}
      .otherwise{for(i<-0 until config.lanes)when(mask(i)){data(i):=io.result.bits.data(i)}}
    state:=State.commit
  }
  when(io.commit.fire){state:=State.idle}
}
