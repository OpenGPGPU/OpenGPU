package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.system._
import opengpu.graphics.WindowRecordTest._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

/** Explicit interface peer: captured metadata and retirement, never shader,
  * patch, image, publication or actual parent execution. */
class TessellationVertexTypedRangePeer(domain:Int) extends Module {
  private val p=VertexShaderStageConfig();private val config=GpuConfig()
  val io=IO(new Bundle {
    val capture=Input(new TessellationVertexCaptureCompletion(p))
    val submit=Input(Bool());val submitReady=Output(Bool());val badBinding=Input(Bool())
    val gntStatus=Input(UInt(3.W));val sequence=Input(UInt(64.W));val primitive=Input(UInt(32.W))
    val task=Decoupled(new TessellationVertexPatchTask(p))
    val taskSequence=Output(UInt(64.W));val taskPrimitive=Output(UInt(32.W))
    val taskInstance=Output(UInt(32.W));val taskBaseInstance=Output(UInt(32.W))
    val taskToken=Output(UInt(32.W));val taskCommand=Output(UInt(4.W))
    val returned=Flipped(Decoupled(new Bundle {
      val taskId=UInt(32.W);val nestedId=UInt(32.W);val status=UInt(3.W)
      val rawValid=Bool();val ownerValid=Bool();val tupleRejected=Bool();val published=UInt(16.W)
      val imageStatus=UInt(3.W);val sourceStatus=UInt(3.W);val patchStatus=UInt(3.W)
    }))
    val taskDrained=Input(Bool());val cancel=Input(Bool());val cancelChild=Output(Bool())
    val completion=Decoupled(new TessellationVertexTypedRangeResult)
    val lastValid=Output(Bool());val lastTaskId=Output(UInt(32.W));val lastRawValid=Output(Bool())
    val lastPublished=Output(UInt(16.W));val busy=Output(Bool());val drained=Output(Bool())
  })
  if(domain==0){
    val a=Module(new TessellationSystemIsolineVertexTypedRange(config,p,4,evaluationPatchAvailable=true))
    a.io.begin.valid:=io.submit;io.submitReady:=a.io.begin.ready
    a.io.begin.bits:=0.U.asTypeOf(a.io.begin.bits)
    a.io.begin.bits.capture:=io.capture
    val g=a.io.begin.bits.gnt;val c=io.capture
    g.status:=io.gntStatus;g.sourceRequest:=c.request
    when(io.badBinding){g.sourceRequest.drawIndex:=c.request.drawIndex+1.U}
    g.sequence.count:=c.root.patchCount
    val t=g.sequence.template.source.viewport
    t.identity:=c.root.identity;t.instanceId:=c.root.firstInstance;t.baseInstance:=c.draw.input.baseInstance
    t.firstSequence:=io.sequence;t.firstPrimitiveId:=io.primitive
    t.system.patch.control.patch.context.taskId:=c.root.taskIdBase
    t.system.patch.control.patch.context.layout:=c.root.layout
    t.system.patch.commandId:=9.U;t.system.patch.outputComponents:=8.U
    g.sequence.template.source.token:=123.U
    io.task.valid:=a.io.task.valid;a.io.task.ready:=io.task.ready;io.task.bits:=a.io.task.bits.vertex
    val out=a.io.task.bits.source.viewport
    io.taskSequence:=out.firstSequence;io.taskPrimitive:=out.firstPrimitiveId
    io.taskInstance:=out.instanceId;io.taskBaseInstance:=out.baseInstance
    io.taskToken:=a.io.task.bits.source.token;io.taskCommand:=out.system.patch.commandId
    a.io.taskCompletion.valid:=io.returned.valid;io.returned.ready:=a.io.taskCompletion.ready
    a.io.taskCompletion.bits:=0.U.asTypeOf(a.io.taskCompletion.bits)
    val r=a.io.taskCompletion.bits;val in=io.returned.bits
    r.taskId:=in.taskId;r.status:=in.status;r.tupleRejected:=in.tupleRejected
    r.rawSourceValid:=in.rawValid;r.ownerValid:=in.ownerValid
    r.owner.taskId:=in.taskId;r.owner.status:=in.status;r.owner.source.taskId:=in.nestedId
    r.owner.image.published:=in.published;r.owner.image.status:=in.imageStatus
    r.owner.source.status:=in.sourceStatus
    r.source.status:=in.imageStatus;r.source.source.status:=in.patchStatus;r.source.source.patch.status:=in.patchStatus
    r.source.source.patch.taskId:=in.nestedId;r.source.source.patch.published:=in.published
    a.io.taskDrained:=io.taskDrained;a.io.cancel:=io.cancel;io.cancelChild:=a.io.cancelChild
    io.completion.valid:=a.io.completion.valid;a.io.completion.ready:=io.completion.ready
    io.completion.bits:=a.io.completion.bits.result
    io.lastValid:=a.io.completion.bits.lastTaskValid;io.lastTaskId:=a.io.completion.bits.lastTask.taskId
    io.lastRawValid:=a.io.completion.bits.lastTask.rawSourceValid
    io.lastPublished:=a.io.completion.bits.lastTask.source.source.patch.published
    io.busy:=a.io.busy;io.drained:=a.io.drained
  }
  else if(domain==1){
    val a=Module(new TessellationSystemTriangleVertexTypedRange(config,p,4,evaluationPatchAvailable=true))
    a.io.begin.valid:=io.submit;io.submitReady:=a.io.begin.ready
    a.io.begin.bits:=0.U.asTypeOf(a.io.begin.bits)
    a.io.begin.bits.capture:=io.capture
    val g=a.io.begin.bits.gnt;val c=io.capture
    g.status:=io.gntStatus;g.sourceRequest:=c.request
    when(io.badBinding){g.sourceRequest.drawIndex:=c.request.drawIndex+1.U}
    g.sequence.count:=c.root.patchCount
    val t=g.sequence.template.source.viewport
    t.identity:=c.root.identity;t.instanceId:=c.root.firstInstance;t.baseInstance:=c.draw.input.baseInstance
    t.firstSequence:=io.sequence;t.firstPrimitiveId:=io.primitive
    t.system.patch.control.patch.context.taskId:=c.root.taskIdBase
    t.system.patch.control.patch.context.layout:=c.root.layout
    t.system.patch.commandId:=9.U;t.system.patch.outputComponents:=8.U
    g.sequence.template.source.token:=123.U
    io.task.valid:=a.io.task.valid;a.io.task.ready:=io.task.ready;io.task.bits:=a.io.task.bits.vertex
    val out=a.io.task.bits.source.viewport
    io.taskSequence:=out.firstSequence;io.taskPrimitive:=out.firstPrimitiveId
    io.taskInstance:=out.instanceId;io.taskBaseInstance:=out.baseInstance
    io.taskToken:=a.io.task.bits.source.token;io.taskCommand:=out.system.patch.commandId
    a.io.taskCompletion.valid:=io.returned.valid;io.returned.ready:=a.io.taskCompletion.ready
    a.io.taskCompletion.bits:=0.U.asTypeOf(a.io.taskCompletion.bits)
    val r=a.io.taskCompletion.bits;val in=io.returned.bits
    r.taskId:=in.taskId;r.status:=in.status;r.tupleRejected:=in.tupleRejected
    r.rawSourceValid:=in.rawValid;r.ownerValid:=in.ownerValid
    r.owner.taskId:=in.taskId;r.owner.status:=in.status;r.owner.source.taskId:=in.nestedId
    r.owner.image.published:=in.published;r.owner.image.status:=in.imageStatus
    r.owner.source.status:=in.sourceStatus
    r.source.status:=in.imageStatus;r.source.source.status:=in.patchStatus;r.source.source.patch.status:=in.patchStatus
    r.source.source.patch.taskId:=in.nestedId;r.source.source.patch.published:=in.published
    a.io.taskDrained:=io.taskDrained;a.io.cancel:=io.cancel;io.cancelChild:=a.io.cancelChild
    io.completion.valid:=a.io.completion.valid;a.io.completion.ready:=io.completion.ready
    io.completion.bits:=a.io.completion.bits.result
    io.lastValid:=a.io.completion.bits.lastTaskValid;io.lastTaskId:=a.io.completion.bits.lastTask.taskId
    io.lastRawValid:=a.io.completion.bits.lastTask.rawSourceValid
    io.lastPublished:=a.io.completion.bits.lastTask.source.source.patch.published
    io.busy:=a.io.busy;io.drained:=a.io.drained
  }
  else if(domain==2){
    val a=Module(new TessellationSystemQuadVertexTypedRange(config,p,4,evaluationPatchAvailable=true))
    a.io.begin.valid:=io.submit;io.submitReady:=a.io.begin.ready
    a.io.begin.bits:=0.U.asTypeOf(a.io.begin.bits)
    a.io.begin.bits.capture:=io.capture
    val g=a.io.begin.bits.gnt;val c=io.capture
    g.status:=io.gntStatus;g.sourceRequest:=c.request
    when(io.badBinding){g.sourceRequest.drawIndex:=c.request.drawIndex+1.U}
    g.sequence.count:=c.root.patchCount
    val t=g.sequence.template.source.viewport
    t.identity:=c.root.identity;t.instanceId:=c.root.firstInstance;t.baseInstance:=c.draw.input.baseInstance
    t.firstSequence:=io.sequence;t.firstPrimitiveId:=io.primitive
    t.system.patch.control.patch.context.taskId:=c.root.taskIdBase
    t.system.patch.control.patch.context.layout:=c.root.layout
    t.system.patch.commandId:=9.U;t.system.patch.outputComponents:=8.U
    g.sequence.template.source.token:=123.U
    io.task.valid:=a.io.task.valid;a.io.task.ready:=io.task.ready;io.task.bits:=a.io.task.bits.vertex
    val out=a.io.task.bits.source.viewport
    io.taskSequence:=out.firstSequence;io.taskPrimitive:=out.firstPrimitiveId
    io.taskInstance:=out.instanceId;io.taskBaseInstance:=out.baseInstance
    io.taskToken:=a.io.task.bits.source.token;io.taskCommand:=out.system.patch.commandId
    a.io.taskCompletion.valid:=io.returned.valid;io.returned.ready:=a.io.taskCompletion.ready
    a.io.taskCompletion.bits:=0.U.asTypeOf(a.io.taskCompletion.bits)
    val r=a.io.taskCompletion.bits;val in=io.returned.bits
    r.taskId:=in.taskId;r.status:=in.status;r.tupleRejected:=in.tupleRejected
    r.rawSourceValid:=in.rawValid;r.ownerValid:=in.ownerValid
    r.owner.taskId:=in.taskId;r.owner.status:=in.status;r.owner.source.taskId:=in.nestedId
    r.owner.image.published:=in.published;r.owner.image.status:=in.imageStatus
    r.owner.source.status:=in.sourceStatus
    r.source.status:=in.imageStatus;r.source.source.status:=in.patchStatus;r.source.source.patch.status:=in.patchStatus
    r.source.source.patch.taskId:=in.nestedId;r.source.source.patch.published:=in.published
    a.io.taskDrained:=io.taskDrained;a.io.cancel:=io.cancel;io.cancelChild:=a.io.cancelChild
    io.completion.valid:=a.io.completion.valid;a.io.completion.ready:=io.completion.ready
    io.completion.bits:=a.io.completion.bits.result
    io.lastValid:=a.io.completion.bits.lastTaskValid;io.lastTaskId:=a.io.completion.bits.lastTask.taskId
    io.lastRawValid:=a.io.completion.bits.lastTask.rawSourceValid
    io.lastPublished:=a.io.completion.bits.lastTask.source.source.patch.published
    io.busy:=a.io.busy;io.drained:=a.io.drained
  }
}

class TessellationVertexTypedRangeSpec extends AnyFlatSpec {
  private class Driver(val d:TessellationVertexTypedRangePeer){
    zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    d.io.taskDrained.poke(true.B)
    def until(done: =>Boolean):Unit={var n=0;while(!done && n<30){d.clock.step();n+=1};assert(done,"typed range interface stalled")}
    def setup(patches:Int=4,pp:Int=2,instances:Int=2,seq:BigInt=13,primitive:BigInt=17):Unit={
      zero(d.io.capture);d.io.badBinding.poke(false.B);d.io.gntStatus.poke(0.U)
      val c=d.io.capture;val r=c.root
      identity(r.identity);r.sequenceIndex.poke(11.U);r.drawIndex.poke(7.U);r.taskIdBase.poke(55.U)
      r.patchCount.poke(patches.U);r.patchesPerInstance.poke(pp.U);r.instanceCount.poke(instances.U)
      r.firstOrdinal.poke((if(patches==0)0 else 5).U);r.firstInstance.poke((if(patches==0)0 else 3).U)
      r.sliceVersion.poke(1.U);r.vertexAbi.poke(2.U);r.entry.poke(0x2000.U)
      r.layout.inputVertices.poke(2.U);r.layout.inputComponents.poke(64.U)
      r.layout.outputVertices.poke(2.U);r.layout.outputComponents.poke(12.U);r.layout.patchComponents.poke(8.U)
      for(i<-0 until 64){r.components(i).kind.poke(1.U);r.components(i).component.poke(i.U)}
      val q=c.request;identity(q.identity);q.sequenceIndex.poke(11.U);q.drawIndex.poke(7.U)
      q.taskIdBase.poke(55.U);q.patchCount.poke(patches.U)
      q.layout.poke(r.layout.peek());q.privateBase.poke(0x1000.U);q.privateLimit.poke(0x9000.U)
      val v=c.draw;v.version.poke(2.U);v.shaderPc.poke(0x2000.U);v.varyingSlots.poke(16.U)
      v.varyingMask.poke(((BigInt(1)<<64)-1).U)
      v.input.version.poke(2.U);identity(v.input.identity)
      v.input.count.poke(20.U);v.input.instances.poke(9.U);v.input.firstVertex.poke(123.U)
      v.input.firstIndex.poke(47.U);v.input.baseVertex.poke((-17).S);v.input.baseInstance.poke(99.U)
      v.input.bindings(15).divisor.poke(31.U)
      d.io.sequence.poke(seq.U);d.io.primitive.poke(primitive.U)
    }
    def begin():Unit={d.io.submit.poke(true.B);until(d.io.submitReady.peek().litToBoolean);d.clock.step();d.io.submit.poke(false.B)}
    def task(id:Int,ordinal:Int,instance:Int,seq:BigInt,primitive:BigInt):Unit={
      until(d.io.task.valid.peek().litToBoolean)
      d.io.task.bits.taskId.expect(id.U);d.io.task.bits.slice.firstOrdinal.expect(ordinal.U)
      d.io.task.bits.slice.firstInstance.expect(instance.U);d.io.task.bits.layout.outputComponents.expect(12.U)
      d.io.task.bits.draw.input.baseInstance.expect(99.U);d.io.task.bits.draw.input.firstVertex.expect(123.U)
      d.io.task.bits.draw.input.bindings(15).divisor.expect(31.U)
      d.io.task.bits.components(63).component.expect(63.U)
      d.io.taskInstance.expect(instance.U);d.io.taskBaseInstance.expect(99.U)
      d.io.taskSequence.expect(seq.U);d.io.taskPrimitive.expect(primitive.U)
      d.io.taskCommand.expect(9.U);d.io.taskToken.expect(123.U)
      val held=d.io.task.bits.peek().litValue;d.clock.step(2)
      assert(d.io.task.bits.peek().litValue==held);d.io.taskSequence.expect(seq.U)
      d.io.task.ready.poke(true.B);d.clock.step();d.io.task.ready.poke(false.B);d.io.taskDrained.poke(false.B)
    }
    def retire(id:Int,published:Int,status:Int=0,raw:Boolean=true,owner:Boolean=true,nested:Int = -1,rejected:Boolean=false,
        imageStatus:Int=0,sourceStatus:Int=0,patchStatus:Int=0):Unit={
      val r=d.io.returned.bits;r.taskId.poke(id.U);r.nestedId.poke((if(nested<0)id else nested).U)
      r.published.poke(published.U);r.status.poke(status.U);r.rawValid.poke(raw.B)
      r.ownerValid.poke(owner.B);r.tupleRejected.poke(rejected.B)
      r.imageStatus.poke(imageStatus.U);r.sourceStatus.poke(sourceStatus.U);r.patchStatus.poke(patchStatus.U)
      d.io.returned.valid.poke(true.B);until(d.io.returned.ready.peek().litToBoolean)
      d.clock.step();d.io.returned.valid.poke(false.B)
    }
    def drain():Unit={d.clock.step(2);d.io.task.valid.expect(false.B);d.io.completion.valid.expect(false.B)
      d.io.taskDrained.poke(true.B);d.clock.step()}
    def report(status:Int,completed:Int,published:Int,failed:Int=0,rejected:Boolean=false):Unit={
      until(d.io.completion.valid.peek().litToBoolean)
      d.io.task.valid.expect(false.B)
      d.io.completion.bits.range.status.expect(status.U);d.io.completion.bits.range.completed.expect(completed.U)
      d.io.completion.bits.range.failedPatch.expect(failed.U);d.io.completion.bits.published.expect(published.U)
      d.io.completion.bits.admissionRejected.expect(rejected.B)
      val held=d.io.completion.bits.peek().litValue
      d.io.taskDrained.poke(false.B);d.io.cancel.poke(true.B);d.io.submit.poke(true.B)
      for(_<-0 until 3){d.io.completion.valid.expect(true.B);assert(d.io.completion.bits.peek().litValue==held)
        d.io.submitReady.expect(false.B);d.io.drained.expect(false.B);d.clock.step()}
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.completion.ready.poke(false.B)
      d.io.cancel.poke(false.B);d.io.submitReady.expect(false.B);d.clock.step()
      d.io.submit.poke(false.B);d.io.taskDrained.poke(true.B);d.clock.step()
    }
  }
  for(domain<-0 until 3){
    "Typed original VS range" should s"preserve domain $domain tuples and account only actual publications" in {
      simulate(new TessellationVertexTypedRangePeer(domain)){d=>
        val x=new Driver(d);x.setup();x.begin()
        var seq=BigInt(13);var prim=BigInt(17)
        for((published,i)<-Seq(3,0,7,2).zipWithIndex){
          x.task(55+i,5+2*(i%2),3+i/2,seq,prim);x.retire(55+i,published)
          seq+=published;prim=if((i+1)%2==0)BigInt(0)else prim+published;x.drain()
        }
        x.report(0,4,12);d.io.lastValid.expect(true.B);d.io.lastTaskId.expect(58.U);d.io.lastPublished.expect(2.U)
        x.setup(0,0,0);x.begin();d.io.taskDrained.poke(false.B);d.clock.step(3)
        d.io.completion.valid.expect(false.B);d.io.task.valid.expect(false.B)
        d.io.taskDrained.poke(true.B);x.report(0,0,0);d.io.lastValid.expect(false.B)
        x.setup();d.io.badBinding.poke(true.B);x.begin();x.report(1,0,0,rejected=true);d.io.lastValid.expect(false.B)
      }
    }
  }
  it should "attribute next cursor overflow to the next patch and preserve actual error and cancellation priority" in {
    simulate(new TessellationVertexTypedRangePeer(0)){d=>
      val x=new Driver(d);val max64=(BigInt(1)<<64)-1;val max32=(BigInt(1)<<32)-1
      for((seq,prim)<-Seq((max64,BigInt(7)),(BigInt(7),max32))){
        x.setup(2,2,1,seq,prim);x.begin();x.task(55,5,3,seq,prim);x.retire(55,1);x.drain()
        d.clock.step(3);d.io.task.valid.expect(false.B);x.report(1,1,1,1)
      }
      // The exhausted primitive space of one original instance does not
      // prevent a valid next instance, whose rasterizer PrimitiveID is zero.
      x.setup(2,1,2,7,max32);x.begin();x.task(55,5,3,7,max32);x.retire(55,1);x.drain()
      x.task(56,5,4,8,0);x.retire(56,1);x.drain();x.report(0,2,2)
      // The independent ordering sequence still cannot wrap at that boundary.
      x.setup(2,1,2,max64,max32);x.begin();x.task(55,5,3,max64,max32);x.retire(55,1);x.drain()
      d.clock.step(3);d.io.task.valid.expect(false.B);x.report(1,1,1,1)
      x.setup(1,1,1,max64,max32);x.begin();x.task(55,5,3,max64,max32);x.retire(55,1);x.drain();x.report(0,1,1)
      x.setup(2,2,1,max64,max32);x.begin();x.task(55,5,3,max64,max32)
      d.io.cancel.poke(true.B);x.retire(55,1,2,imageStatus=4,sourceStatus=2,patchStatus=4)
      x.drain();x.report(2,0,1)
      // Actual early image admission: raw/owner reports exist, but no patch or
      // VS task was opened. Their absent nested task IDs remain canonical zero.
      x.setup(1,1,1);x.begin();x.task(55,5,3,13,17)
      x.retire(55,0,1,nested=0,imageStatus=1);x.drain();x.report(1,0,0)
      d.io.lastValid.expect(true.B);d.io.lastRawValid.expect(true.B);d.io.lastTaskId.expect(55.U)
      x.setup(2,2,1,max64,max32);x.begin();x.task(55,5,3,max64,max32)
      d.io.cancel.poke(true.B);x.retire(55,1);x.drain();x.report(4,0,1)
      x.setup(1,1,1);x.begin();x.task(55,5,3,13,17);x.retire(55,9,nested=99);x.drain();x.report(3,0,0)
      d.io.lastPublished.expect(9.U);d.io.lastRawValid.expect(true.B)
      x.setup(1,1,1);x.begin();x.task(55,5,3,13,17);x.retire(55,0,5,false,false,0,true)
      x.drain();x.report(5,0,0);d.io.lastRawValid.expect(false.B)
      x.setup(1,1,1);x.begin();x.until(d.io.task.valid.peek().litToBoolean)
      val held=d.io.task.bits.peek().litValue;d.io.cancel.poke(true.B);d.clock.step(3)
      d.io.task.valid.expect(true.B);assert(d.io.task.bits.peek().litValue==held)
      x.task(55,5,3,13,17);x.retire(55,0,4,false,true,0);x.drain();x.report(4,0,0)
    }
  }
}
