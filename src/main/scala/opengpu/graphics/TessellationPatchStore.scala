package opengpu.graphics

import chisel3._
import chisel3.util._

class TessellationPatchLayout extends Bundle {
  val inputVertices=UInt(6.W);val inputComponents=UInt(7.W)
  val outputVertices=UInt(6.W);val outputComponents=UInt(7.W);val patchComponents=UInt(7.W)
}
class TessellationPatchAccess extends Bundle {
  /** output=false selects input vertices; output=true selects control output. */
  /** tessLevel selects outer[0..3] and inner[0..1], outside the patch-component budget. */
  val output=Bool();val perPatch=Bool();val tessLevel=Bool();val write=Bool()
  val vertex=UInt(6.W);val component=UInt(7.W);val data=UInt(32.W)
}
class TessellationPatchReply extends Bundle {
  val status=UInt(2.W);val data=UInt(32.W)
}

/** One owned patch with separately packed input and control-output memories.
  * Unwritten components have undefined contents; consumers must observe the
  * control shader's initialization and barrier rules. Requests and replies
  * are serialized, and a held reply prevents reuse or patch release.
  */
class TessellationPatchStore extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TessellationPatchLayout))
    val beginResult=Decoupled(UInt(2.W))
    val access=Flipped(Decoupled(new TessellationPatchAccess))
    val reply=Decoupled(new TessellationPatchReply)
    val end=Flipped(Decoupled(Bool()))
    val busy=Output(Bool())
  })
  private val depth=TessellationLimits.maxControlOutputComponents
  private val input=SyncReadMem(depth,UInt(32.W));private val output=SyncReadMem(depth,UInt(32.W))
  private val levels=Reg(Vec(6,UInt(32.W)))
  private val layout=Reg(new TessellationPatchLayout)
  private val active=RegInit(false.B);private val opening=RegInit(false.B);private val openStatus=Reg(UInt(2.W))
  private val responseValid=RegInit(false.B);private val response=Reg(new TessellationPatchReply)
  private val readPending=RegInit(false.B);private val readOutput=Reg(Bool())
  private val inCount=io.begin.bits.inputVertices*io.begin.bits.inputComponents
  private val outCount=io.begin.bits.outputVertices*io.begin.bits.outputComponents+&io.begin.bits.patchComponents
  private val legal=io.begin.bits.inputVertices>=1.U && io.begin.bits.inputVertices<=TessellationLimits.maxPatchVertices.U &&
    io.begin.bits.outputVertices>=1.U && io.begin.bits.outputVertices<=TessellationLimits.maxPatchVertices.U &&
    io.begin.bits.inputComponents<=64.U && io.begin.bits.outputComponents<=64.U && io.begin.bits.patchComponents<=120.U &&
    inCount<=depth.U && outCount<=depth.U
  io.begin.ready:= !active && !opening && !responseValid && !readPending
  io.beginResult.valid:=opening;io.beginResult.bits:=openStatus
  io.busy:=active || opening || responseValid || readPending
  when(io.begin.fire){layout:=io.begin.bits;active:=legal;opening:=true.B;openStatus:=Mux(legal,0.U,1.U)}
  when(io.beginResult.fire){opening:=false.B}

  private val req=io.access.bits
  private val vertexBound=Mux(req.output,layout.outputVertices,layout.inputVertices)
  private val componentBound=Mux(req.output,layout.outputComponents,layout.inputComponents)
  private val vertexValid= !req.perPatch && req.vertex<vertexBound && req.component<componentBound
  private val patchValid=req.output && req.perPatch && req.vertex===0.U && req.component<layout.patchComponents
  private val levelValid=req.output && req.perPatch && req.tessLevel && req.vertex===0.U && req.component<6.U
  private val accessValid=Mux(req.tessLevel,levelValid,vertexValid || patchValid)
  private val vertexAddress=req.vertex*componentBound+&req.component
  private val patchAddress=layout.outputVertices*layout.outputComponents+&req.component
  private val address=Mux(req.perPatch,patchAddress,vertexAddress)(10,0)
  io.access.ready:=active && !opening && !responseValid && !readPending && !io.end.valid
  io.end.ready:=active && !opening && !responseValid && !readPending
  when(io.end.fire){active:=false.B}
  private val read=io.access.fire && accessValid && !req.write && !req.tessLevel
  private val inputData=input.read(address,read && !req.output)
  private val outputData=output.read(address,read && req.output)
  when(io.access.fire){
    when(!accessValid){response.status:=1.U;response.data:=0.U;responseValid:=true.B}
      .elsewhen(req.tessLevel){
        when(req.write){levels(req.component(2,0)):=req.data}
        response.status:=0.U;response.data:=Mux(req.write,0.U,levels(req.component(2,0)));responseValid:=true.B
      }.elsewhen(req.write){
        when(req.output){output.write(address,req.data)}.otherwise{input.write(address,req.data)}
        response.status:=0.U;response.data:=0.U;responseValid:=true.B
      }.otherwise{readPending:=true.B;readOutput:=req.output}
  }
  when(readPending){response.status:=0.U;response.data:=Mux(readOutput,outputData,inputData);responseValid:=true.B;readPending:=false.B}
  io.reply.valid:=responseValid;io.reply.bits:=response
  when(io.reply.fire){responseValid:=false.B}
}
