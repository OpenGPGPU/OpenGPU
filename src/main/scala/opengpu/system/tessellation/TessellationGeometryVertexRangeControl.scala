package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

class TessellationGeometryVertexRangeBegin(p:VertexShaderStageConfig) extends Bundle {
  val range=new TessellationVertexPatchSequenceDescriptor(p)
  val cursor=new TessellationJointGeometryCursor
  val admissionStatus=UInt(3.W)
}
class TessellationGeometryVertexRangeRetirement extends Bundle {
  val taskId=UInt(32.W);val status=UInt(3.W)
  val tesPublishedValid=Bool();val tesPublished=UInt(16.W)
  val geometryValid=Bool();val actualGsPublished=UInt(94.W)
  val nextOutputSequence=UInt(65.W)
  val received=UInt(97.W);val dropped=UInt(97.W);val admitted=UInt(33.W)
  val firstBinSequence=UInt(33.W);val nextBinSequence=UInt(33.W)
}
class TessellationGeometryVertexRangeControlResult extends Bundle {
  val result=new TessellationVertexTypedRangeResult
  val geometry=new TessellationJointGeometryRangeSummary
}

class TessellationGeometryVertexRangeControl(p:VertexShaderStageConfig) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TessellationGeometryVertexRangeBegin(p)))
    val task=Decoupled(new TessellationVertexPatchTask(p))
    val taskCursor=Output(new TessellationJointGeometryCursor)
    val retired=Flipped(Decoupled(new TessellationGeometryVertexRangeRetirement))
    val taskDrained=Input(Bool());val cancel=Input(Bool());val cancelChild=Output(Bool())
    val completion=Decoupled(new TessellationGeometryVertexRangeControlResult)
    val busy=Output(Bool());val drained=Output(Bool())
  })
  private object State extends ChiselEnum {val idle,start,running,reject,report=Value}
  private val state=RegInit(State.idle)
  private val saved=Reg(new TessellationGeometryVertexRangeBegin(p))
  private val cursor=Reg(new TessellationJointGeometryCursor)
  private val result=Reg(new TessellationGeometryVertexRangeControlResult)
  private val expected=Reg(UInt(32.W));private val originalInstanceEnd=Reg(Bool())
  private val offered=RegInit(false.B);private val nextFault=RegInit(false.B)
  private val cancelled=RegInit(false.B)
  private val stop=cancelled || io.cancel
  private val sequence=Module(new TessellationVertexPatchSequence(p,nextAdmissionAvailable=true))
  private val max32=(BigInt(1)<<32).U;private val max64=(BigInt(1)<<64).U
  private def domain(c:TessellationJointGeometryCursor):Bool=
    c.tes.sequence<=max64 && c.tes.primitiveId<=max32 && c.gsTaskId<=max32 &&
      c.gsInputPrimitiveId<=max32 && c.inputSequence<=max64 && c.outputSequence<=max64 && c.binSequence<=max32
  private def launchable(c:TessellationJointGeometryCursor):Bool=
    c.tes.sequence<max64 && c.tes.primitiveId<max32 && c.gsTaskId<max32 &&
      c.gsInputPrimitiveId<max32 && c.inputSequence<max64 && c.outputSequence<max64 && c.binSequence<max32
  io.begin.ready:=state===State.idle && io.taskDrained && !io.cancel
  when(io.begin.fire){
    saved:=io.begin.bits;cursor:=io.begin.bits.cursor;result:=0.U.asTypeOf(result)
    nextFault:=false.B;cancelled:=false.B;offered:=false.B;state:=State.start
    val d=io.begin.bits;val vertices=d.range.template.layout.inputVertices
    val divisor=Mux(vertices===0.U,1.U,vertices)
    val aligned=d.range.patchCount===0.U || d.range.template.slice.firstOrdinal%divisor===0.U
    when(d.admissionStatus===0.U && (!domain(d.cursor) || !aligned ||
      (d.range.patchCount=/=0.U && !launchable(d.cursor)))){saved.admissionStatus:=1.U}
  }
  when(state=/=State.idle && state=/=State.report && io.cancel){cancelled:=true.B}
  sequence.io.begin.valid:=state===State.start && saved.admissionStatus===0.U
  sequence.io.begin.bits:=saved.range;sequence.io.cancel:=stop && state===State.running
  sequence.io.childBusy:= !io.taskDrained;sequence.io.nextAdmissionFault.get:=nextFault
  io.task.valid:=state===State.running && sequence.io.task.valid;io.task.bits:=sequence.io.task.bits
  sequence.io.task.ready:=state===State.running && io.task.ready
  io.taskCursor:=cursor
  when(io.task.fire){
    expected:=io.task.bits.taskId;offered:=true.B
    val t=io.task.bits;val divisor=Mux(t.layout.inputVertices===0.U,1.U,t.layout.inputVertices)
    originalInstanceEnd:=((t.slice.firstOrdinal/divisor)+&1.U)===(t.draw.input.count/divisor)
  }
  private val r=io.retired.bits
  private val owned=r.taskId===expected
  private val tesSequence=cursor.tes.sequence+&r.tesPublished
  private val tesPrimitive=cursor.tes.primitiveId+&r.tesPublished
  private val gsTask=cursor.gsTaskId+&r.tesPublished
  private val gsInput=cursor.gsInputPrimitiveId+&r.tesPublished
  private val inputSequence=cursor.inputSequence+&r.tesPublished
  private val geometryNext=r.nextOutputSequence
  private val inputBounds=tesSequence<=max64 && tesPrimitive<=max32 && gsTask<=max32 && gsInput<=max32 && inputSequence<=max64
  private val actualGeometry=r.geometryValid && geometryNext>=cursor.outputSequence && geometryNext<=max64 &&
    (geometryNext-cursor.outputSequence).pad(95)===r.actualGsPublished.pad(95)
  private val actualBins=r.firstBinSequence===cursor.binSequence && r.nextBinSequence>=r.firstBinSequence &&
    r.nextBinSequence<=max32 && (r.nextBinSequence-r.firstBinSequence).pad(34)===r.admitted.pad(34) &&
    r.received.pad(98)===(r.admitted.pad(98)+&r.dropped.pad(98))
  private val success=owned && r.tesPublishedValid && inputBounds && actualGeometry && actualBins
  private val code=Mux(r.status===0.U && !success,3.U,r.status)
  sequence.io.retired.valid:=state===State.running && io.retired.valid
  sequence.io.retired.bits.taskId:=r.taskId;sequence.io.retired.bits.status:=code
  io.retired.ready:=state===State.running && sequence.io.retired.ready
  when(io.retired.fire){
    when(r.tesPublishedValid && owned){
      val n=result.result.published+&r.tesPublished
      assert(!n(64),"actual TES range publication count overflow");result.result.published:=n(63,0)
    }
    when(r.geometryValid && owned){
      result.geometry.tasks:=result.geometry.tasks+1.U
      result.geometry.actualGsPublished:=result.geometry.actualGsPublished+r.actualGsPublished
      result.geometry.received:=result.geometry.received+r.received;result.geometry.dropped:=result.geometry.dropped+r.dropped
      result.geometry.admitted:=result.geometry.admitted+r.admitted
    }
    val next=WireDefault(cursor)
    next.tes.sequence:=tesSequence(64,0);next.tes.primitiveId:=Mux(originalInstanceEnd,0.U,tesPrimitive(32,0))
    next.gsTaskId:=gsTask(32,0);next.gsInputPrimitiveId:=Mux(originalInstanceEnd,0.U,gsInput(32,0))
    next.inputSequence:=inputSequence(64,0);next.outputSequence:=geometryNext;next.binSequence:=r.nextBinSequence
    nextFault:= !success || !launchable(next)
    when(code===0.U && success && !stop){cursor:=next}
  }
  when(state===State.start){
    when(saved.admissionStatus=/=0.U){result.result.admissionRejected:=true.B
      result.result.range.status:=saved.admissionStatus;state:=State.reject}
      .elsewhen(sequence.io.begin.fire){state:=State.running}
  }
  sequence.io.completion.ready:=state===State.running
  when(sequence.io.completion.fire){
    result.result.range:=sequence.io.completion.bits
    when(!offered && sequence.io.completion.bits.status===1.U){result.result.admissionRejected:=true.B}
    result.geometry.nextCursor:=cursor;state:=State.report
  }
  when(state===State.reject && io.taskDrained){result.geometry.nextCursor:=cursor;state:=State.report}
  io.cancelChild:=sequence.io.cancelChild
  io.completion.valid:=state===State.report;io.completion.bits:=result
  when(io.completion.fire){state:=State.idle}
  io.busy:=state=/=State.idle
  io.drained:=(state===State.idle || state===State.report) && io.taskDrained
}
