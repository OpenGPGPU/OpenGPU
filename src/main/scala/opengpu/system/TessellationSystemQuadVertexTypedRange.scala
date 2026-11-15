package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.graphics.tessellation.TessellationInputPatchBinding

class TessellationSystemQuadVertexTypedRangeBegin(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int) extends Bundle {
  val gnt=new TessellationVertexSequenceCompletion(new TessellationQuadPatchSequenceDescriptor(config,p,idWidth),idWidth)
  val capture=new TessellationVertexCaptureCompletion(p)
}
class TessellationSystemQuadVertexTypedRangeCompletion extends Bundle {
  val result=new TessellationVertexTypedRangeResult
  val lastTaskValid=Bool();val lastTask=new TessellationSystemQuadVertexInputImageTaskCompletion
}

/** Typed range adapter only: task/retirement connect to the actual image parent.
  * It creates neither patch bytes nor child completions and grants no native authority. */
class TessellationSystemQuadVertexTypedRange(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int,
    tessellationUniforms:Boolean=false,evaluationPatchAvailable:Boolean=false,tessellationInfoAvailable:Boolean=false) extends Module {
  // Preserve the previous full JVM constructor with the language service disabled.
  def this(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int,tessellationUniforms:Boolean,evaluationPatchAvailable:Boolean) =
    this(config,p,idWidth,tessellationUniforms,evaluationPatchAvailable,false)

  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TessellationSystemQuadVertexTypedRangeBegin(config,p,idWidth)))
    val task=Decoupled(new TessellationSystemQuadVertexInputImageTask(config,p,idWidth))
    val taskCompletion=Flipped(Decoupled(new TessellationSystemQuadVertexInputImageTaskCompletion))
    val taskDrained=Input(Bool());val cancel=Input(Bool());val cancelChild=Output(Bool())
    val completion=Decoupled(new TessellationSystemQuadVertexTypedRangeCompletion)
    val busy=Output(Bool());val drained=Output(Bool())
  })
  private val core=Module(new TessellationVertexTypedRangeControl(p))
  private val template=Reg(new TessellationSystemQuadBinTask(config,p,idWidth))
  private val lastValid=RegInit(false.B)
  private val last=Reg(new TessellationSystemQuadVertexInputImageTaskCompletion)
  private val b=io.begin.bits;private val c=b.capture;private val g=b.gnt
  private val v=TessellationVertexTypedRangeBinding.descriptor(p,c)
  private val t=g.sequence.template.source.viewport;private val s=t.system
  private val context=s.patch.control.patch.context
  private val linked=TessellationVertexTypedRangeBinding.matches(c,g.sourceRequest,context.taskId,context.layout,
    g.sequence.count,t.identity) && t.instanceId===c.root.firstInstance && t.baseInstance===c.draw.input.baseInstance
  private val tuple=TessellationVertexInputImageTuple.legal(v.template,context,t.identity,t.instanceId,t.baseInstance,
    s.uniforms,s.uniformResources,s.patch.patchInputCount,s.patch.patchInputs,g.sourceRequest.identity,
    tessellationUniforms,evaluationPatchAvailable)
  core.io.begin.valid:=io.begin.valid;io.begin.ready:=core.io.begin.ready
  core.io.begin.bits.range:=v;core.io.begin.bits.firstSequence:=t.firstSequence
  core.io.begin.bits.firstPrimitiveId:=t.firstPrimitiveId
  core.io.begin.bits.admissionStatus:=TessellationVertexTypedRangeBinding.admission(g.status,c.status,
    linked && (g.sequence.count===0.U || (tuple &&
      (if(tessellationInfoAvailable)TessellationInputPatchBinding.aligned(v.template.slice.firstOrdinal,v.template.layout.inputVertices)else true.B))))
  when(io.begin.fire){template:=g.sequence.template.source;lastValid:=false.B;last:=0.U.asTypeOf(last)}
  io.task.valid:=core.io.task.valid;core.io.task.ready:=io.task.ready
  io.task.bits.source:=template;io.task.bits.vertex:=core.io.task.bits
  private val out=io.task.bits.source.viewport
  out.system.patch.control.patch.context.taskId:=core.io.task.bits.taskId
  out.system.patch.control.patch.context.layout:=core.io.task.bits.layout
  out.instanceId:=core.io.task.bits.slice.firstInstance;out.baseInstance:=core.io.task.bits.draw.input.baseInstance
  out.firstSequence:=core.io.firstSequence;out.firstPrimitiveId:=core.io.firstPrimitiveId
  private val r=io.taskCompletion.bits;private val id=core.io.expectedTaskId
  private val top=r.taskId===id
  private val rawId=r.source.source.patch.taskId===id
  private val ownerId= !r.ownerValid || r.owner.taskId===id
  private val successful= !r.tupleRejected && r.rawSourceValid && r.ownerValid && rawId &&
    r.owner.source.taskId===id && r.owner.status===0.U && r.owner.image.status===0.U &&
    r.owner.source.status===0.U && r.source.status===0.U && r.source.source.status===0.U &&
    r.source.source.patch.status===0.U
  private val absentSource=r.status=/=0.U && r.owner.source.taskId===0.U
  private val rawIdentity= !r.rawSourceValid || rawId || (r.source.source.patch.taskId===0.U && absentSource)
  private val sourceIdentity= !r.ownerValid || r.owner.source.taskId===id || absentSource
  private val shape=r.status<=5.U && r.tupleRejected===(r.status===5.U) &&
    Mux(r.tupleRejected,!r.ownerValid && !r.rawSourceValid,r.ownerValid)
  private val publication= !r.ownerValid || !r.rawSourceValid || r.owner.image.published===r.source.source.patch.published
  private val identities=top && ownerId && rawIdentity && sourceIdentity && shape && publication &&
    (!r.ownerValid || r.owner.status===r.status) && (r.status=/=0.U || successful)
  core.io.retired.valid:=io.taskCompletion.valid;io.taskCompletion.ready:=core.io.retired.ready
  core.io.retired.bits.taskId:=r.taskId
  core.io.retired.bits.status:=Mux(identities,r.status,3.U)
  // A real early-rejection report may carry no meaningful nested taskId.
  // Only an attributable actual publication contributes to the 64-bit total.
  core.io.retired.bits.publishedValid:=r.rawSourceValid && rawId && top
  core.io.retired.bits.published:=r.source.source.patch.published
  when(io.taskCompletion.fire){last:=r;lastValid:=true.B}
  core.io.taskDrained:=io.taskDrained;core.io.cancel:=io.cancel;io.cancelChild:=core.io.cancelChild
  io.completion.valid:=core.io.completion.valid;core.io.completion.ready:=io.completion.ready
  io.completion.bits.result:=core.io.completion.bits
  io.completion.bits.lastTaskValid:=lastValid;io.completion.bits.lastTask:=last
  io.busy:=core.io.busy;io.drained:=core.io.drained
}
