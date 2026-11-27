package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

/** Lossless common envelope for the actual domain evaluator outputs. Isoline
  * pads vertex two with zero; triangle/quad pad their 14-bit sequence to 16.
  * This is pre-flat, pre-clip TES data, never a VS invocation. */
class TessellationGeometryEvaluatedPrimitive extends Bundle {
  val taskId=UInt(32.W);val sequence=UInt(16.W);val point=Bool()
  val vertices=Vec(3,Vec(64,UInt(32.W)))
}
/** The enclosing domain composition also retains its original typed report.
  * This summary is copied only on the actual SystemClient completion.fire.
  */
class TessellationGeometryPatchSourceReport extends Bundle {
  val taskId=UInt(32.W);val status=UInt(3.W);val published=UInt(17.W)
}
class TessellationGeometryPatchDispatchTask(p:VertexShaderStageConfig,w:Int) extends Bundle {
  /** ID/sequence fields are bases for this patch. expectedTesSequence is zero.
    * tesLanguagePatchId is the actual input-patch binding's language identity. */
  val link=new TessellationGeometryRawBinding(p)
  val commandId=UInt(w.W);val viewport=new Fp32ViewportState
}
class TessellationGeometryPatchDispatchResult(p:VertexShaderStageConfig,w:Int) extends Bundle {
  val identity=new GraphicsWorkIdentity
  val tesTaskId=UInt(32.W);val tesLanguagePatchId=UInt(32.W)
  val status=UInt(3.W)
  /** 1 local admission, 2 actual TCS/TES, 3 actual GS/clip/viewport,
    * 4 tuple/report protocol. Original child status values remain unchanged. */
  val faultStage=UInt(3.W)
  val sourceValid=Bool();val source=new TessellationGeometryPatchSourceReport
  val received=UInt(17.W);val submitted=UInt(17.W);val dropped=UInt(17.W)
  // A patch can publish up to 2^16 TES primitives. Preserve the complete GS
  // per-input inventories (78/81/94 bits) when accumulating across the patch.
  val mapped=UInt(94.W);val prepared=UInt(97.W);val arithmeticOperations=UInt(110.W)
  val nextSequence=UInt(64.W)
  val lastGeometryValid=Bool();val lastGeometry=new TessellationGeometryViewportCompletion(w)
  val faultGeometryValid=Bool();val faultGeometry=new TessellationGeometryViewportCompletion(w)
}

/** Production patch lifetime shared by all three real SystemClient wrappers.
  * sourceTask is a one-shot credit paired with the enclosing client's actual
  * typed task handshake. No shader, pool, resource permission or TES words are
  * generated here. geometryTask is an irrevocable captured raw-TES offer.
  * Both children receive cancel only after their own task.fire; each actual
  * held report and local transport must retire before patch publication.
  */
class TessellationGeometryPatchDispatch(p:VertexShaderStageConfig,w:Int,domain:Int,exclusiveGsSequence:Boolean=false) extends Module {
  def this(p:VertexShaderStageConfig,w:Int,domain:Int)=this(p,w,domain,false)
  require(w>0 && domain>=0 && domain<=2)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationGeometryPatchDispatchTask(p,w)))
    val sourceTask=Decoupled(Bool())
    val evaluated=Flipped(Decoupled(new TessellationGeometryEvaluatedPrimitive))
    val sourceCompletion=Flipped(Decoupled(new TessellationGeometryPatchSourceReport))
    val sourceBusy=Input(Bool());val sourceTransportDrained=Input(Bool())
    /** Exact domain source/link/uniform/input-patch checks from the enclosing
      * typed composition. Never retag a rejected source or fabricate its report. */
    val tupleLegal=Input(Bool())
    val geometryTask=Decoupled(new TessellationGeometryViewportTask(p,w))
    val geometryCompletion=Flipped(Decoupled(new TessellationGeometryViewportCompletion(w)))
    val geometryNextSequenceExclusive=if(exclusiveGsSequence)Some(Input(UInt(65.W)))else None
    val nextSequenceExclusive=if(exclusiveGsSequence)Some(Output(UInt(65.W)))else None
    val geometryBusy=Input(Bool());val geometryDrained=Input(Bool())
    val sourceCancel=Output(Bool());val geometryCancel=Output(Bool())
    val completion=Decoupled(new TessellationGeometryPatchDispatchResult(p,w))
    val cancel=Input(Bool());val busy=Output(Bool());val drained=Output(Bool())
  })
  private val saved=Reg(new TessellationGeometryPatchDispatchTask(p,w))
  private val slot=Reg(new TessellationGeometryViewportTask(p,w))
  private val result=Reg(new TessellationGeometryPatchDispatchResult(p,w))
  private val source=Reg(new TessellationGeometryPatchSourceReport)
  private val lastGeometry=Reg(new TessellationGeometryViewportCompletion(w))
  private val faultGeometry=Reg(new TessellationGeometryViewportCompletion(w))
  private val active=RegInit(false.B);private val done=RegInit(false.B)
  private val sourceOffered=RegInit(false.B);private val sourceStarted=RegInit(false.B)
  private val sourceDone=RegInit(false.B);private val sourceValid=RegInit(false.B)
  private val geometryPending=RegInit(false.B);private val geometryOffered=RegInit(false.B)
  private val geometryStarted=RegInit(false.B);private val lastGeometryValid=RegInit(false.B)
  private val faultGeometryValid=RegInit(false.B)
  private val received=RegInit(0.U(17.W));private val submitted=RegInit(0.U(17.W));private val dropped=RegInit(0.U(17.W))
  private val mapped=RegInit(0.U(94.W));private val prepared=RegInit(0.U(97.W))
  private val arithmetic=RegInit(0.U(110.W));private val sequence=Reg(UInt(64.W))
  private val exclusiveCursor=if(exclusiveGsSequence)Some(Reg(UInt(65.W)))else None
  private val exclusiveResult=if(exclusiveGsSequence)Some(Reg(UInt(65.W)))else None
  if(exclusiveGsSequence)io.nextSequenceExclusive.get:=exclusiveResult.get
  private val status=RegInit(0.U(3.W));private val faultStage=RegInit(0.U(3.W))
  private val childrenDrained= !io.sourceBusy && io.sourceTransportDrained && !io.geometryBusy && io.geometryDrained
  private val visibleDrained= !io.evaluated.valid && !io.sourceCompletion.valid && !io.geometryCompletion.valid
  io.task.ready:= !active && childrenDrained && visibleDrained
  io.sourceTask.valid:=sourceOffered;io.sourceTask.bits:=true.B
  io.geometryTask.valid:=geometryOffered;io.geometryTask.bits:=slot
  io.sourceCompletion.ready:=active && sourceStarted && !sourceDone && !done
  io.geometryCompletion.ready:=active && geometryStarted && !done
  io.completion.valid:=done;io.completion.bits:=result
  io.busy:=active || !childrenDrained || !visibleDrained
  io.drained:=childrenDrained && visibleDrained && !sourceOffered && !geometryPending && (!active || done)

  private val e=io.evaluated.bits
  private val gsTask=saved.link.gsTaskId+&e.sequence
  private val gsInput=saved.link.gsInputPrimitiveId+&e.sequence
  private val inputSequence=saved.link.inputSequence+&e.sequence
  private val sequenceFits=if(domain==0)true.B else e.sequence<16384.U
  private val evaluatedBad=io.evaluated.valid && (sourceDone ||
    e.taskId=/=saved.link.expectedTesTaskId || received>65535.U || e.sequence=/=received ||
    !sequenceFits || gsTask(32) || gsInput(32) || inputSequence(64) ||
    (if(exclusiveGsSequence)exclusiveCursor.get(64) else false.B) ||
    (if(domain==0)e.vertices(2).asUInt=/=0.U else false.B))
  private val rawSourceFault=io.sourceCompletion.valid && io.sourceCompletion.bits.status=/=0.U
  private val rawGeometryFault=io.geometryCompletion.valid && io.geometryCompletion.bits.status=/=0.U
  private val stop=status=/=0.U || io.cancel || evaluatedBad || rawSourceFault || rawGeometryFault
  // During stop, even an extra late TES tuple must be consumed and counted as
  // dropped. It can never replace a previously captured GS offer or report.
  io.evaluated.ready:=active && sourceStarted && (!sourceDone || stop) && !done && (!geometryPending || stop)
  io.sourceCancel:=active && sourceStarted && !sourceDone && !done && stop
  io.geometryCancel:=active && geometryStarted && !done && stop

  private val g=io.geometryCompletion.bits
  private val expected=Wire(new TessellationGeometryViewportProvenance(w))
  expected.identity:=slot.source.raw.link.identity;expected.commandId:=slot.source.commandId
  expected.tesTaskId:=slot.source.raw.tesTaskId;expected.tesSequence:=slot.source.raw.tesSequence
  expected.tesLanguagePatchId:=slot.source.raw.link.tesLanguagePatchId
  expected.gsTaskId:=slot.source.raw.link.gsTaskId;expected.gsInputPrimitiveId:=slot.source.raw.link.gsInputPrimitiveId
  expected.inputSequence:=slot.source.raw.link.inputSequence;expected.firstOutputSequence:=slot.source.raw.link.firstOutputSequence
  private val geometryIdentity=g.provenance.asUInt===expected.asUInt
  private val actualOrigin=Wire(new TessellationGeometryViewportProvenance(w))
  actualOrigin.identity:=g.source.identity;actualOrigin.commandId:=g.source.commandId
  actualOrigin.tesTaskId:=g.source.tesTaskId;actualOrigin.tesSequence:=g.source.tesSequence
  actualOrigin.tesLanguagePatchId:=g.source.tesLanguagePatchId
  actualOrigin.gsTaskId:=g.source.gsTaskId;actualOrigin.gsInputPrimitiveId:=g.source.gsInputPrimitiveId
  actualOrigin.inputSequence:=g.source.inputSequence;actualOrigin.firstOutputSequence:=g.source.firstOutputSequence
  private val sourceOrigin=actualOrigin.asUInt===expected.asUInt &&
    (g.source.geometryValid || g.source.bridgeFaultValid) && (!g.source.geometryValid ||
      (g.source.geometry.taskId===expected.gsTaskId && g.source.geometry.inputPrimitiveId===expected.gsInputPrimitiveId))
  private val geometryOrigin=Mux(g.sourceValid,sourceOrigin,
    g.status=/=0.U && g.faultStage===1.U && g.source.asUInt===0.U)
  private val actualGeometry=g.sourceValid && g.source.geometryValid
  private val reportedNext=if(exclusiveGsSequence)io.geometryNextSequenceExclusive.get else g.source.geometry.nextSequence.pad(65)
  private val previous=if(exclusiveGsSequence)exclusiveCursor.get else sequence.pad(65)
  private val nextSequenceLegal= !actualGeometry || (reportedNext>=previous && reportedNext<=(BigInt(1)<<64).U &&
    reportedNext(63,0)===g.source.geometry.nextSequence)
  private val successfulSequence=g.status=/=0.U || (actualGeometry &&
    reportedNext-previous===g.source.geometry.producedPrimitives)
  private val geometryOwned=geometryIdentity && geometryOrigin && nextSequenceLegal && successfulSequence
  private val geometryCode=Mux(geometryOwned,g.status,3.U)
  private val sourceCount=received+&io.evaluated.fire.asUInt
  private val sourceLegal=io.sourceCompletion.bits.taskId===saved.link.expectedTesTaskId &&
    io.sourceCompletion.bits.status<=4.U && io.sourceCompletion.bits.published===sourceCount
  private val sourceCode=Mux(sourceLegal,io.sourceCompletion.bits.status,3.U)
  private var nextStatus:UInt=Mux(status===0.U && io.cancel,4.U,status)
  private var nextStage:UInt=Mux(status===0.U && io.cancel,0.U,faultStage)
  private def consider(valid:Bool,code:UInt,stage:UInt):Unit={
    val replace=valid && code=/=0.U && (nextStatus===0.U || (nextStatus===4.U && code=/=4.U))
    nextStage=Mux(replace,stage,nextStage);nextStatus=Mux(replace,code,nextStatus)
  }
  consider(io.evaluated.fire && evaluatedBad,3.U,4.U)
  consider(io.sourceCompletion.fire,sourceCode,Mux(sourceLegal,2.U,4.U))
  consider(io.geometryCompletion.fire,geometryCode,Mux(geometryOwned,3.U,4.U))
  when(active && !done){status:=nextStatus;faultStage:=nextStage}
  when(io.task.fire){
    saved:=io.task.bits;active:=true.B;done:=false.B;sourceStarted:=false.B;sourceValid:=false.B
    geometryPending:=false.B;geometryOffered:=false.B;geometryStarted:=false.B
    lastGeometryValid:=false.B;faultGeometryValid:=false.B
    source:=0.U.asTypeOf(source);lastGeometry:=0.U.asTypeOf(lastGeometry);faultGeometry:=0.U.asTypeOf(faultGeometry)
    received:=0.U;submitted:=0.U;dropped:=0.U;mapped:=0.U;prepared:=0.U;arithmetic:=0.U
    sequence:=io.task.bits.link.firstOutputSequence
    if(exclusiveGsSequence)exclusiveCursor.get:=io.task.bits.link.firstOutputSequence
    val b=io.task.bits.link
    val legal=io.tupleLegal && b.version===1.U && b.domain===domain.U && b.expectedTesSequence===0.U &&
      b.identity.asUInt===b.program.identity.asUInt && Fp32ViewportState.legal(io.task.bits.viewport)
    sourceOffered:=legal;sourceDone:= !legal
    status:=Mux(!legal,1.U,Mux(io.cancel,4.U,0.U));faultStage:=Mux(!legal,1.U,0.U)
  }
  when(io.sourceTask.fire){sourceOffered:=false.B;sourceStarted:=true.B}
  when(io.evaluated.fire){
    received:=received+1.U
    when(stop){dropped:=dropped+1.U}.otherwise{
      geometryPending:=true.B;geometryOffered:=true.B
      slot.source.commandId:=saved.commandId;slot.viewport:=saved.viewport
      slot.source.raw.link:=saved.link
      slot.source.raw.link.expectedTesSequence:=e.sequence
      slot.source.raw.link.gsTaskId:=gsTask(31,0);slot.source.raw.link.gsInputPrimitiveId:=gsInput(31,0)
      slot.source.raw.link.inputSequence:=inputSequence(63,0);slot.source.raw.link.firstOutputSequence:=sequence
      slot.source.raw.tesTaskId:=e.taskId;slot.source.raw.tesSequence:=e.sequence
      slot.source.raw.point:=e.point;slot.source.raw.vertices:=e.vertices
    }
  }
  when(io.geometryTask.fire){geometryOffered:=false.B;geometryStarted:=true.B;submitted:=submitted+1.U}
  when(io.sourceCompletion.fire){source:=io.sourceCompletion.bits;sourceValid:=true.B;sourceDone:=true.B}
  when(io.geometryCompletion.fire){
    geometryPending:=false.B;geometryStarted:=false.B;lastGeometryValid:=true.B;lastGeometry:=g
    when(geometryOwned){
      when(actualGeometry){sequence:=g.source.geometry.nextSequence
        if(exclusiveGsSequence)exclusiveCursor.get:=reportedNext}
      mapped:=mapped+g.counts.mappedInputs;prepared:=prepared+g.counts.preparedParts
      arithmetic:=arithmetic+g.counts.arithmeticOperations
    }
    when(geometryCode=/=0.U && geometryCode=/=4.U && !faultGeometryValid){faultGeometryValid:=true.B;faultGeometry:=g}
  }
  when(active && !done && sourceDone && !sourceOffered && !geometryPending && childrenDrained && visibleDrained){
    if(exclusiveGsSequence)exclusiveResult.get:=exclusiveCursor.get
    result.identity:=saved.link.identity;result.tesTaskId:=saved.link.expectedTesTaskId
    result.tesLanguagePatchId:=saved.link.tesLanguagePatchId
    result.status:=nextStatus;result.faultStage:=nextStage;result.sourceValid:=sourceValid;result.source:=source
    result.received:=received;result.submitted:=submitted;result.dropped:=dropped
    result.mapped:=mapped;result.prepared:=prepared;result.arithmeticOperations:=arithmetic;result.nextSequence:=sequence
    result.lastGeometryValid:=lastGeometryValid;result.lastGeometry:=lastGeometry
    result.faultGeometryValid:=faultGeometryValid;result.faultGeometry:=faultGeometry;done:=true.B
  }
  when(io.completion.fire){active:=false.B;done:=false.B}
}
