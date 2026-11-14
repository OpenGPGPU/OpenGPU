package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._
import opengpu.graphics.tessellation._

/** Linked component source: position=0, generic=1, point size=2. */
class TessellationVertexComponent extends Bundle {
  val kind=UInt(2.W);val component=UInt(7.W)
}
class TessellationVertexPatchTask(p:VertexShaderStageConfig) extends Bundle {
  val version=UInt(8.W);val taskId=UInt(32.W)
  val layout=new TessellationPatchLayout
  val draw=new VertexShaderDraw(p);val slice=new VertexInputSlice
  val components=Vec(64,new TessellationVertexComponent)
}
class TessellationVertexPatchCompletion extends Bundle {
  val taskId=UInt(32.W)
  /** 0 success, 1 source/layout, 2 VS failure, 3 patch failure, 4 cancel. */
  val status=UInt(3.W);val vertices=UInt(64.W);val words=UInt(32.W)
  val source=new VertexShaderCompletion
}

/** Publish a real VS stream into an already opened, exclusively owned patch.
  * The parent supplies the exact same patch layout/identity and retains its
  * load/reply/seal acceptors through this completion, including cancellation.
  * This internal component does not validate GEM authority or native records.
  */
class TessellationVertexPatchPublisher(p:VertexShaderStageConfig) extends Module {
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationVertexPatchTask(p)))
    val sourceDraw=Decoupled(new VertexShaderDraw(p));val sourceSlice=Output(new VertexInputSlice)
    val sourceOut=Flipped(Decoupled(new ShadedVertexRecord(p)))
    val sourceCompletion=Flipped(Decoupled(new VertexShaderCompletion))
    val sourceBusy=Input(Bool());val cancelSource=Output(Bool());val sourceLive=Output(Bool())
    val load=Decoupled(new TessellationPatchAccess)
    val reply=Flipped(Decoupled(new TessellationContextReply));val seal=Decoupled(Bool())
    val cancelPatch=Output(Bool());val completion=Decoupled(new TessellationVertexPatchCompletion)
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,validate,offer,collect,prepare,write,waitReply,waitSource,check,seal,finish,complete=Value}
  private val state=RegInit(State.idle)
  private val task=Reg(new TessellationVertexPatchTask(p));private val record=Reg(new ShadedVertexRecord(p))
  private val result=Reg(new TessellationVertexPatchCompletion)
  private val status=RegInit(0.U(3.W));private val cancelled=RegInit(false.B)
  private val launched=RegInit(false.B);private val sourceDone=RegInit(false.B)
  private val sourceResult=Reg(new VertexShaderCompletion)
  private val received=RegInit(0.U(64.W));private val words=RegInit(0.U(32.W))
  private val vertex=Reg(UInt(6.W));private val component=Reg(UInt(7.W))
  private val active=state=/=State.idle && state=/=State.complete
  private val sourceFailed=launched && !sourceDone && io.sourceCompletion.valid && io.sourceCompletion.bits.status=/=0.U
  private val stop=status=/=0.U || cancelled || io.cancel || sourceFailed
  private def fail(code:Int):Unit=when(status===0.U || status===4.U){status:=code.U}
  when(active && io.cancel){cancelled:=true.B;when(status===0.U){status:=4.U}}

  io.task.ready:=state===State.idle && !io.cancel && !io.sourceBusy
  io.sourceDraw.valid:=state===State.offer;io.sourceDraw.bits:=task.draw;io.sourceSlice:=task.slice
  io.sourceLive:=launched && active
  io.cancelSource:=launched && active && stop
  io.sourceOut.ready:=state===State.collect || (active && launched && stop)
  io.sourceCompletion.ready:=active && launched && !sourceDone
  when(io.sourceCompletion.fire){
    sourceDone:=true.B;sourceResult:=io.sourceCompletion.bits
    when(io.sourceCompletion.bits.status=/=0.U){
      when(io.sourceCompletion.bits.status===4.U){cancelled:=true.B;when(status===0.U){status:=4.U}}
        .otherwise{fail(2)}
    }
  }
  io.load.valid:=state===State.write
  io.load.bits:=0.U.asTypeOf(io.load.bits)
  io.load.bits.write:=true.B;io.load.bits.vertex:=vertex;io.load.bits.component:=component
  private val selection=task.components(component(5,0))
  private val varying=VecInit(record.varyings.flatMap(_.toSeq))
  private def varyingIndex(c:UInt):UInt=
    Mux(c<(4*p.varyings).U,c,0.U)(log2Ceil(4*p.varyings)-1,0)
  private val safeVarying=varyingIndex(selection.component)
  io.load.bits.data:=MuxLookup(selection.kind,0.U)(Seq(
    0.U->record.position(selection.component(1,0)),1.U->varying(safeVarying),2.U->record.pointSize))
  io.reply.ready:=state===State.waitReply
  io.seal.valid:=state===State.seal;io.seal.bits:=true.B
  io.cancelPatch:=(state=/=State.idle && status=/=0.U) || (active && (cancelled || io.cancel || sourceFailed))
  io.completion.valid:=state===State.complete;io.completion.bits:=result
  io.busy:=state=/=State.idle || io.sourceBusy

  private val l=task.layout
  private val maps=task.components.zipWithIndex.map{case(m,i)=>
    val generic=m.component<(4*p.varyings).U && m.component<(task.draw.varyingSlots<<2) &&
      task.draw.varyingMask(varyingIndex(m.component))
    val valid=(m.kind===0.U && m.component<4.U) || (m.kind===1.U && generic) ||
      (m.kind===2.U && m.component===0.U && task.draw.version===2.U)
    Mux(i.U<l.inputComponents,valid,m.asUInt===0.U)
  }.reduce(_ && _)
  private val legal=task.version===1.U && VertexInputSlice.legal(task.slice,task.draw.input) &&
    task.slice.instances===1.U && task.slice.count===l.inputVertices &&
    l.inputVertices>=1.U && l.inputVertices<=32.U && l.outputVertices>=1.U && l.outputVertices<=32.U &&
    l.inputComponents<=64.U && l.outputComponents<=64.U && l.patchComponents<=120.U &&
    l.inputVertices*l.inputComponents<=2048.U &&
    l.outputVertices*l.outputComponents+&l.patchComponents<=2048.U && maps
  private val pointSizeNeeded=task.components.zipWithIndex.map{case(m,i)=>i.U<l.inputComponents && m.kind===2.U}.reduce(_ || _)
  private val out=io.sourceOut.bits
  private val expectedOrdinal=task.slice.firstOrdinal.pad(65)+received.pad(65)
  private val badRecord=out.restart || out.identity.asUInt=/=task.draw.input.identity.asUInt ||
    out.ordinal.pad(65)=/=expectedOrdinal || out.instanceId=/=task.slice.firstInstance ||
    out.baseInstance=/=task.draw.input.baseInstance || received>=l.inputVertices ||
    (pointSizeNeeded && !out.pointSizeWritten)
  switch(state){
    is(State.idle){when(io.task.fire){
      task:=io.task.bits;result:=0.U.asTypeOf(result);sourceResult:=0.U.asTypeOf(sourceResult)
      status:=0.U;cancelled:=false.B;launched:=false.B;sourceDone:=false.B
      received:=0.U;words:=0.U;vertex:=0.U;component:=0.U;state:=State.validate
    }}
    is(State.validate){when(!legal){fail(1);state:=State.finish}
      .elsewhen(stop){state:=State.finish}.otherwise{state:=State.offer}}
    // A held draw is accepted first; cancellation reaches the child afterward.
    is(State.offer){when(io.sourceDraw.fire){launched:=true.B;state:=State.collect}}
    is(State.collect){
      when(stop){state:=State.waitSource}
        .elsewhen(io.sourceOut.fire){
          received:=received+1.U
          when(badRecord){fail(1);state:=State.waitSource}.otherwise{
            record:=out;vertex:=received(5,0);component:=0.U
            when(l.inputComponents=/=0.U){state:=State.prepare}
          }
        }.elsewhen(sourceDone && !io.sourceBusy){state:=State.check}
    }
    is(State.prepare){state:=Mux(stop,State.waitSource,State.write)}
    // Neither a stalled write nor its accepted reply is abandoned on cancel.
    is(State.write){when(io.load.fire){state:=State.waitReply}}
    is(State.waitReply){when(io.reply.fire){
      val protocol=io.reply.bits.source===0.U && io.reply.bits.invocation===0.U
      when(protocol && io.reply.bits.result.status===0.U){words:=words+1.U}
      when(!protocol){fail(3);state:=State.waitSource}
        // PatchContext rejects already-held loads with status 1 while aborting.
        // That drain acknowledgement must not replace the primary cancellation
        // or source error. A malformed reply remains a protocol failure above.
        .elsewhen(stop){state:=State.waitSource}
        .elsewhen(io.reply.bits.result.status=/=0.U){fail(3);state:=State.waitSource}
        .elsewhen(component+&1.U===l.inputComponents){state:=State.collect}
        .otherwise{component:=component+1.U;state:=State.prepare}
    }}
    is(State.waitSource){when(sourceDone && !io.sourceBusy){state:=State.check}}
    is(State.check){
      when(stop){state:=State.finish}
        .elsewhen(!sourceDone || received=/=l.inputVertices || sourceResult.vertices=/=received ||
          sourceResult.restarts=/=0.U || sourceResult.identity.asUInt=/=task.draw.input.identity.asUInt ||
          words=/=l.inputVertices*l.inputComponents){fail(1);state:=State.finish}
        .otherwise{state:=State.seal}
    }
    // Once offered, sealing also survives cancellation; the parent cancels the
    // patch and must not launch a later stage merely because this offer drains.
    is(State.seal){when(io.seal.fire){state:=State.finish}}
    is(State.finish){when(!io.sourceBusy){
      result.taskId:=task.taskId;result.status:=Mux(status=/=0.U,status,Mux(cancelled || io.cancel,4.U,0.U))
      result.vertices:=received;result.words:=words;result.source:=sourceResult;state:=State.complete
    }}
    is(State.complete){when(io.completion.fire){state:=State.idle}}
  }
  when(io.seal.valid){assert(sourceDone && !io.sourceBusy,"patch sealed before actual VS retirement")}
  when(io.load.valid){assert(component<l.inputComponents && component<64.U,"patch component outside validated source layout")}
  when(io.completion.valid){assert(!io.sourceBusy,"VS source completion escaped child drain")}
}

/** Actual shared-CU VS execution followed by acknowledged patch publication.
  * The enclosing owner still admits the original GEMs, opens the matching
  * patch, arbitrates shared resources and waits for complete patch retirement.
  */
class TessellationVertexPatchSource(config:GpuConfig,p:VertexShaderStageConfig,shared:SharedCuShaderConfig,
    useSramBlackBoxes:Boolean=false,payload:Option[ShaderPayloadConfig]=None,payloadOwner:Int=0) extends Module {
  private val payloadConfig=payload
  private val sharedConfig=shared
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationVertexPatchTask(p)))
    val completion=Decoupled(new TessellationVertexPatchCompletion)
    val load=Decoupled(new TessellationPatchAccess);val reply=Flipped(Decoupled(new TessellationContextReply))
    val seal=Decoupled(Bool());val cancelPatch=Output(Bool())
    val shared=Flipped(new SharedCuGraphicsPort(config,sharedConfig.taskIdWidth,sharedConfig.numComputeUnits))
    val payload=payloadConfig.map(q=>new FragmentPayloadPort(config,q))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val publisher=Module(new TessellationVertexPatchPublisher(p))
  private val vertex=Module(new VertexShaderStage(config,p,sharedConfig,useSramBlackBoxes,payload,payloadOwner,drawSlices=true))
  publisher.io.task<>io.task;io.completion<>publisher.io.completion
  vertex.io.draw<>publisher.io.sourceDraw;vertex.io.slice.get:=publisher.io.sourceSlice
  publisher.io.sourceOut<>vertex.io.out;publisher.io.sourceCompletion<>vertex.io.completion
  publisher.io.sourceBusy:=vertex.io.busy;vertex.io.cancel:=publisher.io.cancelSource
  io.load<>publisher.io.load;publisher.io.reply<>io.reply;io.seal<>publisher.io.seal
  io.cancelPatch:=publisher.io.cancelPatch
  publisher.io.cancel:=io.cancel || io.payload.map(_.cancel).getOrElse(false.B)
  io.busy:=publisher.io.busy;io.shared<>vertex.io.shared
  io.memoryRequest<>vertex.io.memoryRequest;vertex.io.memoryResponse<>io.memoryResponse
  if(payload.nonEmpty){
    io.payload.get<>vertex.io.payload.get
    // An already offered child draw transfers before cancellation. No payload
    // request can be generated until after that acceptance.
    vertex.io.payload.get.cancel:=io.payload.get.cancel && publisher.io.sourceLive
  }
}
