package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import chisel3.reflect.DataMirror
import opengpu.config.GpuConfig
import opengpu.graphics._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationNativeSequenceReaderHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=2);private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationNativeSequenceRequest(4)))
    val patch=Decoupled(new TessellationSystemIsolineInputImageTask(config,p,4))
    val patchCompletion=Flipped(Decoupled(new TessellationSystemIsolineInputImageTaskCompletion))
    val completion=Decoupled(new TessellationNativeSequenceCompletion(4))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,1))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,1)))
  })
  private val reader=Module(new TessellationNativeSequenceReader(config,p,4));private val sequence=Module(new TessellationPatchSequence(config,p,4))
  reader.io.submit<>io.submit;sequence.io.begin<>reader.io.draw;reader.io.drawCompletion<>sequence.io.completion
  sequence.io.cancel:=reader.io.childCancel;sequence.io.frameStopped:=false.B;reader.io.cancel:=false.B;reader.io.frameStopped:=false.B
  io.patch<>sequence.io.task;sequence.io.taskCompletion<>io.patchCompletion
  io.memoryRequest<>reader.io.memoryRequest;reader.io.memoryResponse<>io.memoryResponse;io.completion<>reader.io.completion
}

class TessellationNativeSequenceReaderSpec extends AnyFlatSpec {
  private def zero(d:Data):Unit=d match {
    case r:Record=>r.elements.values.foreach(zero);case v:Vec[_]=>v.foreach(zero)
    case b:Bool if DataMirror.directionOf(b)==ActualDirection.Input=>b.poke(false.B)
    case u:UInt if DataMirror.directionOf(u)==ActualDirection.Input=>u.poke(0.U)
    case s:SInt if DataMirror.directionOf(s)==ActualDirection.Input=>s.poke(0.S)
    case _=>()
  }
  it should "snapshot bounded GPU records, reject truncation, and drain cancellation" in {
    val config=GpuConfig(lanes=4,warps=2);val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
    simulate(new TessellationNativeSequenceReader(config,p,4)){d=>
      import TessellationNativeSequenceLayout._
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val fs=fields(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val record=Array.fill[BigInt](words)(0);record(0)=magic;record(1)=bytes;record(2)=version
      def set(name:String,value:BigInt):Unit={val f=fs.find(_.name==name).getOrElse(fail(s"missing $name"))
        for(i<-0 until f.words)record(f.word+i)=(value>>(32*i)) & 0xffffffffL}
      set("count",2);set("patchStride",32)
      set("template.source.viewport.identity.context",7);set("template.source.viewport.identity.pass",8);set("template.source.viewport.identity.generation",9)
      set("template.source.viewport.system.patch.control.patch.context.taskId",42)
      set("template.source.viewport.system.patch.control.patch.context.layout.inputVertices",1)
      set("template.source.viewport.system.patch.control.patch.context.layout.inputComponents",1)
      set("template.input.base",0x5000);set("template.input.limit",0x5040)
      set("template.input.allocationBase",0x5000);set("template.input.allocationLimit",0x5040)
      set("template.stride",4);set("template.source.token",77)
      var base=BigInt(0x1000);var faultLine= -1;var cycle=0;var heldRequest:Option[BigInt]=None
      val replies=mutable.Queue.empty[(Int,BigInt)];var pending:Option[(Int,BigInt)]=None
      def tick():Unit={
        d.io.memoryRequest.ready.poke((cycle%3!=0).B)
        if(pending.isEmpty && replies.headOption.exists(_._1<=cycle))pending=Some(replies.dequeue())
        d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.foreach{case(_,index)=>
          val idx=index.toInt;val data=(0 until 16).foldLeft(BigInt(0))((a,i)=>a | (record(idx*16+i)<<(32*i)))
          d.io.memoryResponse.bits.transactionId.poke(0.U);d.io.memoryResponse.bits.fault.poke((idx==faultLine).B);d.io.memoryResponse.bits.readData.poke(data.U)}
        val req=d.io.memoryRequest;val valid=req.valid.peek().litToBoolean;val ready=req.ready.peek().litToBoolean
        heldRequest.foreach(x=>assert(valid && req.bits.peek().litValue==x));heldRequest=if(valid && !ready)Some(req.bits.peek().litValue)else None
        if(valid && ready){val address=req.bits.address.peek().litValue;assert(address>=base && address<base+bytes && (address-base)%64==0)
          req.bits.transactionId.expect(0.U);req.bits.isWrite.expect(false.B);replies.enqueue((cycle+2,(address-base)/64))}
        val consumed=pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
        d.clock.step();if(consumed)pending=None;cycle+=1
      }
      def until(label:String)(condition: =>Boolean):Unit={var n=0;while(!condition && n<3000){tick();n+=1};assert(condition,s"$label timed out")}
      def submit(at:BigInt=BigInt(0x1000)):Unit={base=at;val x=d.io.submit.bits;x.commandId.poke(5.U)
        x.frameIdentity.context.poke(7.U);x.frameIdentity.pass.poke(8.U);x.frameIdentity.generation.poke(9.U)
        x.view.base.poke(base.U);x.view.limit.poke((base+bytes).U);x.view.allocationBase.poke(base.U);x.view.allocationLimit.poke((base+bytes).U)
        d.io.submit.valid.poke(true.B);until("submit")(d.io.submit.ready.peek().litToBoolean);tick();d.io.submit.valid.poke(false.B)}
      def finish(status:Int,read:Int,stopped:Boolean=false,child:Boolean=false):Unit={until("completion")(d.io.completion.valid.peek().litToBoolean)
        val c=d.io.completion.bits;c.status.expect(status.U);c.frameStopped.expect(stopped.B);c.childValid.expect(child.B);c.commandId.expect(5.U);c.readBytes.expect(read.U)
        val value=c.peek().litValue;for(_<-0 until 3){tick();d.io.completion.valid.expect(true.B);assert(c.peek().litValue==value)}
        d.io.completion.ready.poke(true.B);tick();d.io.completion.ready.poke(false.B);assert(replies.isEmpty && pending.isEmpty)}
      def acceptDraw():Unit={until("draw")(d.io.draw.valid.peek().litToBoolean);val x=d.io.draw.bits
        x.count.expect(2.U);x.patchStride.expect(32.U);x.template.source.viewport.system.patch.commandId.expect(5.U)
        x.template.source.viewport.system.patch.control.patch.context.taskId.expect(42.U);x.template.input.base.expect(0x5000.U)
        val held=x.peek().litValue;for(_<-0 until 3){tick();assert(d.io.draw.valid.peek().litToBoolean && x.peek().litValue==held)}
        d.io.draw.ready.poke(true.B);tick();d.io.draw.ready.poke(false.B)}
      def retire(status:Int=0):Unit={d.io.drawCompletion.bits.status.poke(status.U);d.io.drawCompletion.valid.poke(true.B)
        until("child ready")(d.io.drawCompletion.ready.peek().litToBoolean);tick();d.io.drawCompletion.valid.poke(false.B)}
      submit();acceptDraw();retire();finish(0,bytes,child=true)
      submit();acceptDraw();retire(6);finish(6,bytes,child=true)
      submit(BigInt("fffff800",16));acceptDraw();retire();finish(0,bytes,child=true)
      base=0x1000;val invalidView=d.io.submit.bits;invalidView.commandId.poke(5.U)
      invalidView.view.base.poke(0x1004.U);invalidView.view.limit.poke(0x1800.U)
      invalidView.view.allocationBase.poke(0x1000.U);invalidView.view.allocationLimit.poke(0x1800.U)
      d.io.submit.valid.poke(true.B);until("invalid submit")(d.io.submit.ready.peek().litToBoolean);tick()
      d.io.submit.valid.poke(false.B);d.io.memoryRequest.valid.expect(false.B);finish(1,0)
      base=BigInt("fffffc00",16);invalidView.view.base.poke(base.U);invalidView.view.limit.poke((BigInt(1)<<32).U)
      invalidView.view.allocationBase.poke(base.U);invalidView.view.allocationLimit.poke((BigInt(1)<<32).U)
      d.io.submit.valid.poke(true.B);until("wrapping submit")(d.io.submit.ready.peek().litToBoolean);tick()
      d.io.submit.valid.poke(false.B);d.io.memoryRequest.valid.expect(false.B);finish(1,0)
      record(8)=1;submit();finish(1,bytes);record(8)=0
      set("template.source.viewport.identity.context",6);submit();finish(1,bytes)
      set("template.source.viewport.identity.context",7)
      set("template.source.viewport.system.patch.commandId",1);submit();finish(1,bytes)
      set("template.source.viewport.system.patch.commandId",0)
      val flagField=fs.find(_.name=="template.source.viewport.system.nativeResources").get
      record(flagField.word)=2;submit();finish(1,bytes);record(flagField.word)=0
      val limitField=fs.find(_.name=="template.input.limit").get
      record(limitField.word+1)=2;submit();finish(1,bytes);record(limitField.word+1)=0
      set("template.source.viewport.system.patch.control.kernel.invocations.helpers",(BigInt(1)<<16)|1)
      set("template.source.viewport.system.patch.evaluationKernel.invocations.helpers",BigInt(1)<<17)
      submit();until("helper draw")(d.io.draw.valid.peek().litToBoolean)
      d.io.draw.bits.template.source.viewport.system.patch.control.kernel.invocations.helpers.expect(17.U)
      d.io.draw.bits.template.source.viewport.system.patch.evaluationKernel.invocations.helpers.expect(32.U)
      d.io.draw.ready.poke(true.B);tick();d.io.draw.ready.poke(false.B);retire();finish(0,bytes,child=true)
      set("template.source.viewport.system.patch.control.kernel.invocations.helpers",BigInt(1)<<4);submit();finish(1,bytes)
      set("template.source.viewport.system.patch.control.kernel.invocations.helpers",0)
      set("template.source.viewport.system.patch.evaluationKernel.invocations.helpers",0)
      faultLine=2;submit();finish(2,192);faultLine= -1
      submit();until("draw before frame stop")(d.io.draw.valid.peek().litToBoolean)
      d.io.draw.ready.poke(true.B);d.io.frameStopped.poke(true.B);d.io.draw.valid.expect(false.B);tick()
      d.io.frameStopped.poke(false.B);d.io.draw.ready.poke(false.B);finish(1,bytes,stopped=true)
      submit();d.io.memoryRequest.ready.poke(false.B);d.io.frameStopped.poke(true.B);d.clock.step()
      val stoppedLine=d.io.memoryRequest.bits.peek().litValue
      for(_<-0 until 3){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==stoppedLine);d.clock.step()}
      finish(1,64,stopped=true);d.io.frameStopped.poke(false.B)
      submit();d.io.memoryRequest.ready.poke(false.B);d.io.cancel.poke(true.B);d.clock.step();d.io.cancel.poke(false.B)
      val heldLine=d.io.memoryRequest.bits.peek().litValue
      for(_<-0 until 3){d.io.memoryRequest.valid.expect(true.B);assert(d.io.memoryRequest.bits.peek().litValue==heldLine);d.clock.step()}
      finish(4,64)
      submit();until("held draw")(d.io.draw.valid.peek().litToBoolean);d.io.cancel.poke(true.B);tick();d.io.cancel.poke(false.B)
      acceptDraw();d.io.childCancel.expect(true.B);retire(4);finish(4,bytes,child=true)
      submit();acceptDraw();retire();finish(0,bytes,child=true)
    }
  }
  it should "drive the real patch sequencer from a GPU record" in {
    import TessellationNativeSequenceLayout._
    simulate(new TessellationNativeSequenceReaderHarness){d=>
      zero(d.io);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val fs=fields(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val record=Array.fill[BigInt](words)(0);record(0)=magic;record(1)=bytes;record(2)=version
      def set(name:String,value:BigInt):Unit={val f=fs.find(_.name==name).get
        for(i<-0 until f.words)record(f.word+i)=(value>>(32*i)) & 0xffffffffL}
      set("count",2);set("patchStride",32);set("template.stride",4)
      set("template.source.viewport.identity.context",7);set("template.source.viewport.identity.pass",8);set("template.source.viewport.identity.generation",9)
      set("template.source.viewport.system.patch.control.patch.context.taskId",42)
      set("template.source.viewport.system.patch.control.patch.context.layout.inputVertices",1)
      set("template.source.viewport.system.patch.control.patch.context.layout.inputComponents",1)
      set("template.input.base",0x5000);set("template.input.limit",0x5040)
      set("template.input.allocationBase",0x5000);set("template.input.allocationLimit",0x5040)
      val submit=d.io.submit.bits;submit.commandId.poke(5.U);submit.frameIdentity.context.poke(7.U)
      submit.frameIdentity.pass.poke(8.U);submit.frameIdentity.generation.poke(9.U)
      submit.view.base.poke(0x1000.U);submit.view.limit.poke((0x1000+bytes).U)
      submit.view.allocationBase.poke(0x1000.U);submit.view.allocationLimit.poke((0x1000+bytes).U)
      d.io.submit.valid.poke(true.B);d.io.submit.ready.expect(true.B);d.clock.step();d.io.submit.valid.poke(false.B)
      var response:Option[Int]=None;var patchReply=false;var seen=0;var cycles=0
      while(!d.io.completion.valid.peek().litToBoolean && cycles<3000){
        d.io.memoryRequest.ready.poke(true.B);d.io.memoryResponse.valid.poke(response.nonEmpty.B)
        response.foreach{i=>val data=(0 until 16).foldLeft(BigInt(0))((a,j)=>a | (record(i*16+j)<<(32*j)))
          d.io.memoryResponse.bits.transactionId.poke(0.U);d.io.memoryResponse.bits.readData.poke(data.U);d.io.memoryResponse.bits.fault.poke(false.B)}
        d.io.patch.ready.poke((cycles%4!=0).B);d.io.patchCompletion.valid.poke(patchReply.B)
        d.io.patchCompletion.bits.status.poke(0.U);d.io.patchCompletion.bits.source.source.patch.published.poke(1.U)
        val memFire=d.io.memoryRequest.valid.peek().litToBoolean && d.io.memoryRequest.ready.peek().litToBoolean
        val memLine=if(memFire)((d.io.memoryRequest.bits.address.peek().litValue-0x1000)/64).toInt else 0
        val memDone=response.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean
        val patchFire=d.io.patch.valid.peek().litToBoolean && d.io.patch.ready.peek().litToBoolean
        if(patchFire){d.io.patch.bits.input.base.expect((0x5000+seen*32).U)
          d.io.patch.bits.source.viewport.system.patch.control.patch.context.taskId.expect((42+seen).U)
          d.io.patch.bits.source.viewport.system.patch.commandId.expect(5.U)
          d.io.patch.bits.source.viewport.firstPrimitiveId.expect(seen.U);seen+=1}
        val childDone=patchReply && d.io.patchCompletion.ready.peek().litToBoolean
        d.clock.step();if(memDone)response=None;if(memFire){assert(response.isEmpty);response=Some(memLine)}
        if(childDone)patchReply=false;if(patchFire){assert(!patchReply);patchReply=true};cycles+=1
      }
      assert(cycles<3000 && seen==2 && !patchReply && response.isEmpty)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.childValid.expect(true.B);d.io.completion.bits.readBytes.expect(bytes.U)
      d.io.completion.bits.child.completed.expect(2.U);d.io.completion.ready.poke(true.B);d.clock.step()
    }
  }

}
