package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics.{VertexInputFetchConfig,VertexShaderStageConfig}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class NativeRecordTranslatedReaderHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=2);private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  private val slots=TessellationNativeRecordMemoryFabric.transactions(8)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TessellationNativeSequenceRequest(4)))
    val draw=Decoupled(new TessellationPatchSequenceDescriptor(config,p,4))
    val drawCompletion=Flipped(Decoupled(new TessellationPatchSequenceCompletion))
    val completion=Decoupled(new TessellationNativeSequenceCompletion(4))
    val imageRequest=Flipped(Decoupled(new ComputeMemoryRequest(config,64,8)))
    val imageResponse=Decoupled(new ComputeMemoryResponse(64,8))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val drained=Output(Bool())
  })
  private val reader=Module(new TessellationNativeSequenceReader(config,p,4));private val fabric=Module(new TessellationNativeRecordMemoryFabric(config,8))
  reader.io.submit<>io.submit;io.draw<>reader.io.draw;reader.io.drawCompletion<>io.drawCompletion;io.completion<>reader.io.completion
  reader.io.cancel:=false.B;reader.io.frameStopped:=false.B
  fabric.io.recordRequest<>reader.io.memoryRequest;reader.io.memoryResponse<>fabric.io.recordResponse
  fabric.io.imageRequest<>io.imageRequest;io.imageResponse<>fabric.io.imageResponse
  fabric.io.satp:="h80000030".U;fabric.io.flush.valid:=false.B;fabric.io.flush.bits:=0.U.asTypeOf(fabric.io.flush.bits)
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse;io.drained:=fabric.io.drained
}

class TessellationNativeRecordMemoryFabricSpec extends AnyFlatSpec {
  for(badRecord<-Seq(false,true))
  it should s"keep physical image and translated record ownership with page fault=$badRecord" in {
    simulate(new TessellationNativeRecordMemoryFabric(GpuConfig(lanes=4,warps=2),8)){d=>
      val io=d.io;io.satp.poke("h80000030".U);io.flush.valid.poke(false.B);io.flush.bits.poke(0.U.asTypeOf(io.flush.bits))
      io.imageRequest.valid.poke(false.B);io.imageRequest.bits.poke(0.U.asTypeOf(io.imageRequest.bits))
      io.recordRequest.valid.poke(false.B);io.recordRequest.bits.poke(0.U.asTypeOf(io.recordRequest.bits))
      io.imageResponse.ready.poke(false.B);io.recordResponse.ready.poke(false.B)
      io.memoryRequest.ready.poke(false.B);io.memoryResponse.valid.poke(false.B);io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.imageRequest.bits.address.poke(0x12000.U);io.imageRequest.bits.transactionId.poke(3.U);io.imageRequest.bits.sizeLog2.poke(6.U)
      io.recordRequest.bits.address.poke(0x400040.U);io.recordRequest.bits.transactionId.poke(0.U);io.recordRequest.bits.sizeLog2.poke(6.U)
      var sendImage=true;var sendRecord=true;var gotImage=false;var gotRecord=false;var held=0;var imageHeld=0;var cycle=0
      var offered:Option[(Int,BigInt)]=None;var heldRequest:Option[BigInt]=None;var heldResponse:Option[BigInt]=None;var heldImage:Option[BigInt]=None
      val pending=mutable.ArrayBuffer.empty[(Int,BigInt)];val seen=mutable.ArrayBuffer.empty[(Int,BigInt)]
      while((!gotImage || !gotRecord || pending.nonEmpty || offered.nonEmpty) && cycle<3000){
        io.imageRequest.valid.poke(sendImage.B);io.recordRequest.valid.poke(sendRecord.B)
        io.memoryRequest.ready.poke((cycle%4!=0).B)
        if(offered.isEmpty && pending.nonEmpty)offered=Some(pending.remove(pending.size-1))
        io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>io.memoryResponse.bits.transactionId.poke(id.U);io.memoryResponse.bits.readData.poke(data.U);io.memoryResponse.bits.fault.poke(false.B)}
        val hold=io.recordResponse.valid.peek().litToBoolean && held<8;io.recordResponse.ready.poke((!hold).B)
        if(io.recordResponse.valid.peek().litToBoolean){val bits=io.recordResponse.bits.peek().litValue
          heldResponse.foreach(x=>assert(x==bits));heldResponse=if(hold)Some(bits)else None
          io.recordResponse.bits.transactionId.expect(0.U);io.recordResponse.bits.fault.expect(badRecord.B)
          if(hold){held+=1;io.drained.expect(false.B)}else{assert(!gotRecord);gotRecord=true
            if(!badRecord)io.recordResponse.bits.readData.expect(0x9008.U)}}else assert(heldResponse.isEmpty)
        val holdImage=io.imageResponse.valid.peek().litToBoolean && imageHeld<5;io.imageResponse.ready.poke((!holdImage).B)
        if(io.imageResponse.valid.peek().litToBoolean){val bits=io.imageResponse.bits.peek().litValue
          heldImage.foreach(x=>assert(x==bits));heldImage=if(holdImage)Some(bits)else None
          io.imageResponse.bits.transactionId.expect(3.U);io.imageResponse.bits.fault.expect(false.B);io.imageResponse.bits.readData.expect(0x9003.U)
          if(holdImage){imageHeld+=1;io.drained.expect(false.B)}else{assert(!gotImage);gotImage=true}}else assert(heldImage.isEmpty)
        val i=sendImage && io.imageRequest.ready.peek().litToBoolean;val r=sendRecord && io.recordRequest.ready.peek().litToBoolean
        val valid=io.memoryRequest.valid.peek().litToBoolean;val ready=io.memoryRequest.ready.peek().litToBoolean
        heldRequest.foreach(x=>assert(valid && io.memoryRequest.bits.peek().litValue==x))
        heldRequest=if(valid && !ready)Some(io.memoryRequest.bits.peek().litValue)else None
        if(valid && ready){val id=io.memoryRequest.bits.transactionId.peek().litValue.toInt;val addr=io.memoryRequest.bits.address.peek().litValue
          seen+=((id,addr));if(id==9){assert(addr==0x30004);pending+=((id,if(badRecord)BigInt(0)else (BigInt(0x800)<<10)|0x43))}
          else {assert((id==3 && addr==0x12000) || (id==8 && addr==0x800040),s"id=$id address=$addr")
            pending+=((id,BigInt(0x9000+id)))}}
        if(offered.nonEmpty && io.memoryResponse.ready.peek().litToBoolean)offered=None
        d.clock.step();if(i)sendImage=false;if(r)sendRecord=false;cycle+=1
      }
      assert(cycle<3000 && gotImage && gotRecord && held==8 && imageHeld==5)
      io.imageRequest.valid.poke(false.B);io.recordRequest.valid.poke(false.B);io.memoryResponse.valid.poke(false.B)
      d.clock.step(3);io.drained.expect(true.B)
      assert(seen.contains((9,BigInt(0x30004))) && seen.contains((3,BigInt(0x12000))))
      assert(seen.exists(_._1==8) != badRecord)
    }
  }
  it should "read a complete native draw through Sv32 while physical image traffic shares the port" in {
    import TessellationNativeSequenceLayout._
    simulate(new NativeRecordTranslatedReaderHarness){d=>
      val io=d.io;io.submit.valid.poke(false.B);io.submit.bits.poke(0.U.asTypeOf(io.submit.bits))
      io.imageRequest.valid.poke(false.B);io.imageRequest.bits.poke(0.U.asTypeOf(io.imageRequest.bits))
      io.imageResponse.ready.poke(true.B);io.draw.ready.poke(true.B);io.drawCompletion.valid.poke(false.B);io.drawCompletion.bits.poke(0.U.asTypeOf(io.drawCompletion.bits))
      io.completion.ready.poke(false.B);io.memoryRequest.ready.poke(true.B);io.memoryResponse.valid.poke(false.B);io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val fs=fields(new TessellationPatchSequenceDescriptor(GpuConfig(),VertexShaderStageConfig(),12))
      val record=Array.fill[BigInt](words)(0);record(0)=magic;record(1)=bytes;record(2)=version
      def set(name:String,value:BigInt):Unit={val f=fs.find(_.name==name).get
        for(i<-0 until f.words)record(f.word+i)=(value>>(32*i)) & 0xffffffffL}
      set("count",1);set("template.source.viewport.identity.context",7);set("template.source.viewport.identity.pass",8);set("template.source.viewport.identity.generation",9)
      set("template.source.viewport.system.patch.control.patch.context.taskId",42)
      val submit=io.submit.bits;submit.commandId.poke(5.U);submit.frameIdentity.context.poke(7.U);submit.frameIdentity.pass.poke(8.U);submit.frameIdentity.generation.poke(9.U)
      submit.view.base.poke(0x400000.U);submit.view.limit.poke((0x400000+bytes).U)
      submit.view.allocationBase.poke(0x400000.U);submit.view.allocationLimit.poke((0x400000+bytes).U)
      io.imageRequest.bits.address.poke(0x12000.U);io.imageRequest.bits.transactionId.poke(3.U);io.imageRequest.bits.sizeLog2.poke(6.U)
      var submitPending=true;var imagePending=true;var imageDone=false;var drawDone=false;var childPending=false;var cycles=0
      val responses=mutable.Queue.empty[(Int,BigInt)];var offered:Option[(Int,BigInt)]=None;var recordLines=0;var walks=0
      while((!io.completion.valid.peek().litToBoolean || !imageDone) && cycles<3000){
        io.submit.valid.poke(submitPending.B);io.imageRequest.valid.poke(imagePending.B)
        if(offered.isEmpty && responses.nonEmpty)offered=Some(responses.dequeue())
        io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>io.memoryResponse.bits.transactionId.poke(id.U);io.memoryResponse.bits.readData.poke(data.U)}
        io.drawCompletion.valid.poke(childPending.B);io.drawCompletion.bits.status.poke(0.U)
        val sub=submitPending && io.submit.ready.peek().litToBoolean;val img=imagePending && io.imageRequest.ready.peek().litToBoolean
        if(io.memoryRequest.valid.peek().litToBoolean){val id=io.memoryRequest.bits.transactionId.peek().litValue.toInt;val addr=io.memoryRequest.bits.address.peek().litValue
          if(id==9){assert(addr==0x30004);walks+=1;responses.enqueue((id,(BigInt(0x800)<<10)|0x43))}
          else if(id==8){assert(addr>=0x800000 && addr<0x800000+bytes);val index=((addr-0x800000)/64).toInt
            val data=(0 until 16).foldLeft(BigInt(0))((a,i)=>a | (record(index*16+i)<<(32*i)));recordLines+=1;responses.enqueue((id,data))}
          else {assert(id==3 && addr==0x12000);responses.enqueue((id,BigInt(0x9876)))}}
        if(offered.nonEmpty && io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(io.imageResponse.valid.peek().litToBoolean){io.imageResponse.bits.transactionId.expect(3.U);io.imageResponse.bits.readData.expect(0x9876.U);imageDone=true}
        val draw=io.draw.valid.peek().litToBoolean && io.draw.ready.peek().litToBoolean
        if(draw){assert(!drawDone);drawDone=true;io.draw.bits.count.expect(1.U);io.draw.bits.template.source.viewport.system.patch.commandId.expect(5.U)
          io.draw.bits.template.source.viewport.system.patch.control.patch.context.taskId.expect(42.U)}
        val child=childPending && io.drawCompletion.ready.peek().litToBoolean
        d.clock.step();if(sub)submitPending=false;if(img)imagePending=false;if(child)childPending=false;if(draw)childPending=true;cycles+=1
      }
      assert(cycles<3000 && drawDone && imageDone && recordLines==lines && walks==1 && responses.isEmpty && offered.isEmpty)
      io.submit.valid.poke(false.B);io.imageRequest.valid.poke(false.B);io.memoryResponse.valid.poke(false.B)
      io.completion.bits.status.expect(0.U);io.completion.bits.childValid.expect(true.B);io.completion.bits.readBytes.expect(bytes.U)
      io.completion.ready.poke(true.B);d.clock.step();d.clock.step(2);io.drained.expect(true.B)
    }
  }

}
