package opengpu.system
import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Actual adapter; captures and typed image retirement are explicit protocol
  * peers, not actual readers, shaders or a native resource authority. */
private class GeometryRangeTagObserved(domain:Int) extends Module {
  val config=GpuConfig();val p=VertexShaderStageConfig()
  val io=IO(new Bundle {
    val submit=Input(Bool());val ready=Output(Bool());val badTag=Input(Bool())
    val task=Decoupled(new TessellationVertexPatchTask(p))
    val gsTag=Output(UInt(12.W));val gsTask=Output(UInt(32.W));val gsInput=Output(UInt(32.W))
    val gsIn=Output(UInt(64.W));val gsOut=Output(UInt(64.W));val patch=Output(UInt(32.W))
    val retired=Flipped(Decoupled(Bool()));val taskDrained=Input(Bool());val cancel=Input(Bool())
    val out=Decoupled(new TessellationVertexTypedRangeResult)
    val next=Output(new TessellationJointGeometryCursor)
  })
  if(domain==0){
    val a=Module(new TessellationSystemIsolineGeometryVertexTypedRange(config,p,12,evaluationPatchAvailable=true,tessellationInfoAvailable=true))
    a.io.begin.valid:=io.submit;io.ready:=a.io.begin.ready;a.io.begin.bits:=0.U.asTypeOf(a.io.begin.bits)
    val b=a.io.begin.bits;val c=b.capture;val g=b.gnt;val f=b.attachment;val root=c.root
    root.identity.context:=7.U;root.identity.pass:=8.U;root.identity.generation:=9.U
    root.sequenceIndex:=2.U;root.drawIndex:=7.U;root.taskIdBase:=100.U
    root.patchCount:=1.U;root.patchesPerInstance:=1.U;root.instanceCount:=1.U
    root.sliceVersion:=1.U;root.vertexAbi:=2.U;root.entry:=0x2000.U
    root.layout.inputVertices:=2.U;root.layout.inputComponents:=64.U
    root.layout.outputVertices:=32.U;root.layout.outputComponents:=60.U;root.layout.patchComponents:=120.U
    for(i<-0 until 64){root.components(i).kind:=1.U;root.components(i).component:=i.U}
    c.request.identity:=root.identity;c.request.sequenceIndex:=2.U;c.request.drawIndex:=7.U
    c.request.taskIdBase:=100.U;c.request.patchCount:=1.U;c.request.layout:=root.layout
    c.draw.version:=2.U;c.draw.shaderPc:=0x2000.U;c.draw.varyingSlots:=16.U
    c.draw.varyingMask:=((BigInt(1)<<64)-1).U
    c.draw.input.version:=2.U;c.draw.input.identity:=root.identity
    c.draw.input.count:=2.U;c.draw.input.instances:=1.U;c.draw.input.firstVertex:=123.U
    c.draw.input.firstIndex:=47.U;c.draw.input.baseVertex:=(-17).S;c.draw.input.baseInstance:=99.U
    c.draw.input.bindings(15).divisor:=31.U
    g.commandId:=5.U;g.sourceRequest:=c.request;g.sequence.count:=1.U
    val v=g.sequence.template.source.viewport
    v.identity:=root.identity;v.baseInstance:=99.U
    v.system.patch.control.patch.context.taskId:=100.U;v.system.patch.control.patch.context.layout:=root.layout
    v.system.patch.outputComponents:=8.U;g.sequence.template.source.token:=123.U
    f.commandId:=Mux(io.badTag,6.U,5.U);f.captured.sequenceIndex:=2.U;f.captured.domain:=0.U
    f.captured.commandId:=19.U;f.captured.identity:=root.identity;f.captured.program.identity:=root.identity
    for(i<-0 until 8){f.captured.evaluationComponents(i).valid:=true.B;f.captured.evaluationComponents(i).component:=i.U}
    a.io.cursor:=0.U.asTypeOf(a.io.cursor);a.io.cursor.tes.sequence:=10.U;a.io.cursor.tes.primitiveId:=20.U
    a.io.cursor.gsTaskId:=31.U;a.io.cursor.inputSequence:=40.U;a.io.cursor.outputSequence:=100.U;a.io.cursor.binSequence:=7.U
    io.task.valid:=a.io.task.valid;io.task.bits:=a.io.task.bits.vertex;a.io.task.ready:=io.task.ready
    val link=a.io.task.bits.source.geometryLink
    io.gsTag:=a.io.task.bits.source.geometryCommandId;io.gsTask:=link.gsTaskId;io.gsInput:=link.gsInputPrimitiveId
    io.gsIn:=link.inputSequence;io.gsOut:=link.firstOutputSequence;io.patch:=link.tesLanguagePatchId
    val accepted=Reg(new TessellationSystemIsolineGeometryVertexInputImageTask(config,p,12))
    when(a.io.task.fire){accepted:=a.io.task.bits}
    a.io.taskCompletion.valid:=io.retired.valid;io.retired.ready:=a.io.taskCompletion.ready
    a.io.taskCompletion.bits:=0.U.asTypeOf(a.io.taskCompletion.bits)
    val r=a.io.taskCompletion.bits;r.taskId:=accepted.vertex.taskId;r.rawSourceValid:=true.B;r.ownerValid:=true.B
    r.owner.taskId:=accepted.vertex.taskId;r.owner.source.taskId:=accepted.vertex.taskId;r.owner.image.published:=2.U
    r.source.task:=accepted.source;r.source.sourceValid:=true.B;r.source.publicationValid:=true.B
    r.source.actualGsPublished:=3.U;r.source.nextGsSequence:=103.U;r.source.received:=5.U
    r.source.dropped:=1.U;r.source.admitted:=4.U;r.source.firstSequence:=7.U;r.source.nextSequence:=11.U
    val original=r.source.source;original.sourceValid:=true.B;original.source.taskId:=accepted.vertex.taskId
    original.source.published:=2.U;original.patch.tesTaskId:=accepted.vertex.taskId;original.patch.mapped:=3.U
    a.io.nextGsSequenceExclusive:=103.U;a.io.taskDrained:=io.taskDrained;a.io.cancel:=io.cancel
    io.out.valid:=a.io.completion.valid;io.out.bits:=a.io.completion.bits.result;a.io.completion.ready:=io.out.ready
    io.next:=a.io.nextCursor
  }
  else if(domain==1){
    val a=Module(new TessellationSystemTriangleGeometryVertexTypedRange(config,p,12,evaluationPatchAvailable=true,tessellationInfoAvailable=true))
    a.io.begin.valid:=io.submit;io.ready:=a.io.begin.ready;a.io.begin.bits:=0.U.asTypeOf(a.io.begin.bits)
    val b=a.io.begin.bits;val c=b.capture;val g=b.gnt;val f=b.attachment;val root=c.root
    root.identity.context:=7.U;root.identity.pass:=8.U;root.identity.generation:=9.U
    root.sequenceIndex:=2.U;root.drawIndex:=7.U;root.taskIdBase:=100.U
    root.patchCount:=1.U;root.patchesPerInstance:=1.U;root.instanceCount:=1.U
    root.sliceVersion:=1.U;root.vertexAbi:=2.U;root.entry:=0x2000.U
    root.layout.inputVertices:=2.U;root.layout.inputComponents:=64.U
    root.layout.outputVertices:=32.U;root.layout.outputComponents:=60.U;root.layout.patchComponents:=120.U
    for(i<-0 until 64){root.components(i).kind:=1.U;root.components(i).component:=i.U}
    c.request.identity:=root.identity;c.request.sequenceIndex:=2.U;c.request.drawIndex:=7.U
    c.request.taskIdBase:=100.U;c.request.patchCount:=1.U;c.request.layout:=root.layout
    c.draw.version:=2.U;c.draw.shaderPc:=0x2000.U;c.draw.varyingSlots:=16.U
    c.draw.varyingMask:=((BigInt(1)<<64)-1).U
    c.draw.input.version:=2.U;c.draw.input.identity:=root.identity
    c.draw.input.count:=2.U;c.draw.input.instances:=1.U;c.draw.input.firstVertex:=123.U
    c.draw.input.firstIndex:=47.U;c.draw.input.baseVertex:=(-17).S;c.draw.input.baseInstance:=99.U
    c.draw.input.bindings(15).divisor:=31.U
    g.commandId:=5.U;g.sourceRequest:=c.request;g.sequence.count:=1.U
    val v=g.sequence.template.source.viewport
    v.identity:=root.identity;v.baseInstance:=99.U
    v.system.patch.control.patch.context.taskId:=100.U;v.system.patch.control.patch.context.layout:=root.layout
    v.system.patch.outputComponents:=8.U;g.sequence.template.source.token:=123.U
    f.commandId:=Mux(io.badTag,6.U,5.U);f.captured.sequenceIndex:=2.U;f.captured.domain:=1.U
    f.captured.commandId:=19.U;f.captured.identity:=root.identity;f.captured.program.identity:=root.identity
    for(i<-0 until 8){f.captured.evaluationComponents(i).valid:=true.B;f.captured.evaluationComponents(i).component:=i.U}
    a.io.cursor:=0.U.asTypeOf(a.io.cursor);a.io.cursor.tes.sequence:=10.U;a.io.cursor.tes.primitiveId:=20.U
    a.io.cursor.gsTaskId:=31.U;a.io.cursor.inputSequence:=40.U;a.io.cursor.outputSequence:=100.U;a.io.cursor.binSequence:=7.U
    io.task.valid:=a.io.task.valid;io.task.bits:=a.io.task.bits.vertex;a.io.task.ready:=io.task.ready
    val link=a.io.task.bits.source.geometryLink
    io.gsTag:=a.io.task.bits.source.geometryCommandId;io.gsTask:=link.gsTaskId;io.gsInput:=link.gsInputPrimitiveId
    io.gsIn:=link.inputSequence;io.gsOut:=link.firstOutputSequence;io.patch:=link.tesLanguagePatchId
    val accepted=Reg(new TessellationSystemTriangleGeometryVertexInputImageTask(config,p,12))
    when(a.io.task.fire){accepted:=a.io.task.bits}
    a.io.taskCompletion.valid:=io.retired.valid;io.retired.ready:=a.io.taskCompletion.ready
    a.io.taskCompletion.bits:=0.U.asTypeOf(a.io.taskCompletion.bits)
    val r=a.io.taskCompletion.bits;r.taskId:=accepted.vertex.taskId;r.rawSourceValid:=true.B;r.ownerValid:=true.B
    r.owner.taskId:=accepted.vertex.taskId;r.owner.source.taskId:=accepted.vertex.taskId;r.owner.image.published:=2.U
    r.source.task:=accepted.source;r.source.sourceValid:=true.B;r.source.publicationValid:=true.B
    r.source.actualGsPublished:=3.U;r.source.nextGsSequence:=103.U;r.source.received:=5.U
    r.source.dropped:=1.U;r.source.admitted:=4.U;r.source.firstSequence:=7.U;r.source.nextSequence:=11.U
    val original=r.source.source;original.sourceValid:=true.B;original.source.taskId:=accepted.vertex.taskId
    original.source.published:=2.U;original.patch.tesTaskId:=accepted.vertex.taskId;original.patch.mapped:=3.U
    a.io.nextGsSequenceExclusive:=103.U;a.io.taskDrained:=io.taskDrained;a.io.cancel:=io.cancel
    io.out.valid:=a.io.completion.valid;io.out.bits:=a.io.completion.bits.result;a.io.completion.ready:=io.out.ready
    io.next:=a.io.nextCursor
  }
  else if(domain==2){
    val a=Module(new TessellationSystemQuadGeometryVertexTypedRange(config,p,12,evaluationPatchAvailable=true,tessellationInfoAvailable=true))
    a.io.begin.valid:=io.submit;io.ready:=a.io.begin.ready;a.io.begin.bits:=0.U.asTypeOf(a.io.begin.bits)
    val b=a.io.begin.bits;val c=b.capture;val g=b.gnt;val f=b.attachment;val root=c.root
    root.identity.context:=7.U;root.identity.pass:=8.U;root.identity.generation:=9.U
    root.sequenceIndex:=2.U;root.drawIndex:=7.U;root.taskIdBase:=100.U
    root.patchCount:=1.U;root.patchesPerInstance:=1.U;root.instanceCount:=1.U
    root.sliceVersion:=1.U;root.vertexAbi:=2.U;root.entry:=0x2000.U
    root.layout.inputVertices:=2.U;root.layout.inputComponents:=64.U
    root.layout.outputVertices:=32.U;root.layout.outputComponents:=60.U;root.layout.patchComponents:=120.U
    for(i<-0 until 64){root.components(i).kind:=1.U;root.components(i).component:=i.U}
    c.request.identity:=root.identity;c.request.sequenceIndex:=2.U;c.request.drawIndex:=7.U
    c.request.taskIdBase:=100.U;c.request.patchCount:=1.U;c.request.layout:=root.layout
    c.draw.version:=2.U;c.draw.shaderPc:=0x2000.U;c.draw.varyingSlots:=16.U
    c.draw.varyingMask:=((BigInt(1)<<64)-1).U
    c.draw.input.version:=2.U;c.draw.input.identity:=root.identity
    c.draw.input.count:=2.U;c.draw.input.instances:=1.U;c.draw.input.firstVertex:=123.U
    c.draw.input.firstIndex:=47.U;c.draw.input.baseVertex:=(-17).S;c.draw.input.baseInstance:=99.U
    c.draw.input.bindings(15).divisor:=31.U
    g.commandId:=5.U;g.sourceRequest:=c.request;g.sequence.count:=1.U
    val v=g.sequence.template.source.viewport
    v.identity:=root.identity;v.baseInstance:=99.U
    v.system.patch.control.patch.context.taskId:=100.U;v.system.patch.control.patch.context.layout:=root.layout
    v.system.patch.outputComponents:=8.U;g.sequence.template.source.token:=123.U
    f.commandId:=Mux(io.badTag,6.U,5.U);f.captured.sequenceIndex:=2.U;f.captured.domain:=2.U
    f.captured.commandId:=19.U;f.captured.identity:=root.identity;f.captured.program.identity:=root.identity
    for(i<-0 until 8){f.captured.evaluationComponents(i).valid:=true.B;f.captured.evaluationComponents(i).component:=i.U}
    a.io.cursor:=0.U.asTypeOf(a.io.cursor);a.io.cursor.tes.sequence:=10.U;a.io.cursor.tes.primitiveId:=20.U
    a.io.cursor.gsTaskId:=31.U;a.io.cursor.inputSequence:=40.U;a.io.cursor.outputSequence:=100.U;a.io.cursor.binSequence:=7.U
    io.task.valid:=a.io.task.valid;io.task.bits:=a.io.task.bits.vertex;a.io.task.ready:=io.task.ready
    val link=a.io.task.bits.source.geometryLink
    io.gsTag:=a.io.task.bits.source.geometryCommandId;io.gsTask:=link.gsTaskId;io.gsInput:=link.gsInputPrimitiveId
    io.gsIn:=link.inputSequence;io.gsOut:=link.firstOutputSequence;io.patch:=link.tesLanguagePatchId
    val accepted=Reg(new TessellationSystemQuadGeometryVertexInputImageTask(config,p,12))
    when(a.io.task.fire){accepted:=a.io.task.bits}
    a.io.taskCompletion.valid:=io.retired.valid;io.retired.ready:=a.io.taskCompletion.ready
    a.io.taskCompletion.bits:=0.U.asTypeOf(a.io.taskCompletion.bits)
    val r=a.io.taskCompletion.bits;r.taskId:=accepted.vertex.taskId;r.rawSourceValid:=true.B;r.ownerValid:=true.B
    r.owner.taskId:=accepted.vertex.taskId;r.owner.source.taskId:=accepted.vertex.taskId;r.owner.image.published:=2.U
    r.source.task:=accepted.source;r.source.sourceValid:=true.B;r.source.publicationValid:=true.B
    r.source.actualGsPublished:=3.U;r.source.nextGsSequence:=103.U;r.source.received:=5.U
    r.source.dropped:=1.U;r.source.admitted:=4.U;r.source.firstSequence:=7.U;r.source.nextSequence:=11.U
    val original=r.source.source;original.sourceValid:=true.B;original.source.taskId:=accepted.vertex.taskId
    original.source.published:=2.U;original.patch.tesTaskId:=accepted.vertex.taskId;original.patch.mapped:=3.U
    a.io.nextGsSequenceExclusive:=103.U;a.io.taskDrained:=io.taskDrained;a.io.cancel:=io.cancel
    io.out.valid:=a.io.completion.valid;io.out.bits:=a.io.completion.bits.result;a.io.completion.ready:=io.out.ready
    io.next:=a.io.nextCursor
  }
}
class TessellationGeometryVertexTypedRangeTagSpec extends AnyFlatSpec {
  private def zero(x:Data):Unit=x match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if chisel3.reflect.DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if chisel3.reflect.DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case _=>()
  }
  for(domain<-0 until 3){
    it should s"retain independent GS19 GNQ5 commands and reject a foreign GNQ completion for domain $domain" in {
      simulate(new GeometryRangeTagObserved(domain)){d=>
        zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B);d.io.taskDrained.poke(true.B)
        def until(label:String)(ok: =>Boolean):Unit={var n=0;while(!ok && n<80){d.clock.step();n+=1};assert(ok,s"$label stalled")}
        def begin(bad:Boolean):Unit={d.io.badTag.poke(bad.B);d.io.submit.poke(true.B)
          until("adapter begin")(d.io.ready.peek().litToBoolean);d.clock.step();d.io.submit.poke(false.B)}
        def finish(status:Int):Unit={until("adapter report")(d.io.out.valid.peek().litToBoolean)
          d.io.out.bits.range.status.expect(status.U);d.io.out.bits.range.completed.expect((if(status==0)1 else 0).U)
          d.io.out.bits.admissionRejected.expect((status!=0).B)
          val held=d.io.out.bits.peek().litValue;d.io.cancel.poke(true.B);d.io.taskDrained.poke(false.B)
          for(_<-0 until 3){d.clock.step();d.io.out.valid.expect(true.B);assert(d.io.out.bits.peek().litValue==held);d.io.ready.expect(false.B)}
          d.io.out.ready.poke(true.B);d.clock.step();d.io.out.ready.poke(false.B);d.io.cancel.poke(false.B);d.io.taskDrained.poke(true.B)}
        def healthy():Unit={
          begin(false);until("actual adapter task")(d.io.task.valid.peek().litToBoolean)
          d.io.gsTag.expect(19.U);d.io.gsTask.expect(31.U);d.io.gsInput.expect(0.U)
          d.io.gsIn.expect(40.U);d.io.gsOut.expect(100.U);d.io.patch.expect(0.U)
          d.io.task.bits.draw.input.firstVertex.expect(123.U);d.io.task.bits.draw.input.firstIndex.expect(47.U)
          d.io.task.bits.draw.input.baseVertex.expect((-17).S);d.io.task.bits.draw.input.baseInstance.expect(99.U)
          d.io.task.bits.draw.input.bindings(15).divisor.expect(31.U)
          d.io.task.bits.components(63).component.expect(63.U)
          val held=d.io.task.bits.peek().litValue
          for(_<-0 until 3){d.clock.step();d.io.task.valid.expect(true.B);assert(d.io.task.bits.peek().litValue==held)}
          d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B);d.io.taskDrained.poke(false.B)
          d.io.retired.valid.poke(true.B);until("actual typed peer report")(d.io.retired.ready.peek().litToBoolean)
          d.clock.step();d.io.retired.valid.poke(false.B)
          for(_<-0 until 3){d.clock.step();d.io.out.valid.expect(false.B)}
          d.io.taskDrained.poke(true.B);until("cursor report")(d.io.out.valid.peek().litToBoolean)
          d.io.next.tes.sequence.expect(12.U);d.io.next.tes.primitiveId.expect(0.U)
          d.io.next.gsTaskId.expect(33.U);d.io.next.gsInputPrimitiveId.expect(0.U)
          d.io.next.inputSequence.expect(42.U);d.io.next.outputSequence.expect(103.U);d.io.next.binSequence.expect(11.U)
          finish(0)
        }
        healthy();begin(true)
        for(_<-0 until 10){d.io.task.valid.expect(false.B);d.clock.step()};finish(1)
        healthy() // actual adapter reset-free recovery after rejected GNQ tag
      }
    }
  }
}
