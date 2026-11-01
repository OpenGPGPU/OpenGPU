package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics.{TessellationContextReply,TessellationPatchAccess}
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationInputTranslatedHarness extends Module {
  private val config=GpuConfig(lanes=4,warps=2);private val slots=TessellationInputMemoryFabric.transactions(8)
  val io=IO(new Bundle {
    val task=Flipped(Decoupled(new TessellationPatchInputDmaTask));val completion=Decoupled(new TessellationPatchInputDmaCompletion)
    val load=Decoupled(new TessellationPatchAccess);val reply=Flipped(Decoupled(new TessellationContextReply))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val satp=Input(UInt(32.W));val drained=Output(Bool())
  })
  private val dma=Module(new TessellationPatchInputDma(config));private val fabric=Module(new TessellationInputMemoryFabric(config,8))
  dma.io.task<>io.task;io.completion<>dma.io.completion;io.load<>dma.io.load;dma.io.reply<>io.reply;dma.io.cancel:=false.B
  fabric.io.inputRequest<>dma.io.memoryRequest;dma.io.memoryResponse<>fabric.io.inputResponse
  fabric.io.renderRequest.valid:=false.B;fabric.io.renderRequest.bits:=0.U.asTypeOf(fabric.io.renderRequest.bits)
  fabric.io.renderResponse.ready:=true.B;fabric.io.satp:=io.satp
  fabric.io.flush.valid:=false.B;fabric.io.flush.bits:=0.U.asTypeOf(fabric.io.flush.bits)
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse;io.drained:=fabric.io.drained
}

class TessellationInputMemoryFabricSpec extends AnyFlatSpec {
  for (badInput <- Seq(false,true))
  it should s"translate and drain render and patch-input requests with input fault=$badInput" in {
    simulate(new TessellationInputMemoryFabric(GpuConfig(lanes=4,warps=2),8)){d=>
      val io=d.io
      io.satp.poke("h80000030".U);io.flush.valid.poke(false.B);io.flush.bits.poke(0.U.asTypeOf(io.flush.bits))
      io.renderRequest.valid.poke(false.B);io.renderRequest.bits.poke(0.U.asTypeOf(io.renderRequest.bits))
      io.inputRequest.valid.poke(false.B);io.inputRequest.bits.poke(0.U.asTypeOf(io.inputRequest.bits))
      io.renderResponse.ready.poke(true.B);io.inputResponse.ready.poke(false.B)
      io.memoryRequest.ready.poke(true.B);io.memoryResponse.valid.poke(false.B);io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.renderRequest.bits.address.poke(0x400080.U);io.renderRequest.bits.transactionId.poke(2.U);io.renderRequest.bits.sizeLog2.poke(6.U)
      io.inputRequest.bits.address.poke(0x400040.U);io.inputRequest.bits.transactionId.poke(0.U);io.inputRequest.bits.sizeLog2.poke(6.U)
      var sendRender=true;var sendInput=true;var gotRender=false;var gotInput=false;var held=0;var cycles=0
      var heldBits:Option[BigInt]=None;var offered:Option[(Int,BigInt)]=None
      val pending=mutable.ArrayBuffer.empty[(Int,BigInt)];val requests=mutable.ArrayBuffer.empty[(Int,BigInt)]
      while((!gotRender || !gotInput || pending.nonEmpty || offered.nonEmpty) && cycles<3000){
        io.renderRequest.valid.poke(sendRender.B);io.inputRequest.valid.poke(sendInput.B)
        if(offered.isEmpty && pending.nonEmpty)offered=Some(pending.remove(pending.size-1))
        io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>io.memoryResponse.bits.transactionId.poke(id.U);io.memoryResponse.bits.readData.poke(data.U);io.memoryResponse.bits.fault.poke(false.B)}
        val hold=io.inputResponse.valid.peek().litToBoolean && held<8
        io.inputResponse.ready.poke((!hold).B)
        if(io.inputResponse.valid.peek().litToBoolean){val bits=io.inputResponse.bits.peek().litValue
          heldBits.foreach(x=>assert(x==bits));heldBits=if(hold)Some(bits)else None
          io.inputResponse.bits.transactionId.expect(0.U);io.inputResponse.bits.fault.expect(badInput.B)
          if(hold){held+=1;io.drained.expect(false.B)}else{assert(!gotInput);gotInput=true
            if(!badInput)io.inputResponse.bits.readData.expect(0x900c.U)}
        }else assert(heldBits.isEmpty)
        if(io.renderResponse.valid.peek().litToBoolean){assert(!gotRender);gotRender=true
          io.renderResponse.bits.transactionId.expect(2.U);io.renderResponse.bits.fault.expect(false.B);io.renderResponse.bits.readData.expect(0x9006.U)}
        val r=sendRender && io.renderRequest.ready.peek().litToBoolean
        val i=sendInput && io.inputRequest.ready.peek().litToBoolean
        if(io.memoryRequest.valid.peek().litToBoolean){val id=io.memoryRequest.bits.transactionId.peek().litValue.toInt
          val addr=io.memoryRequest.bits.address.peek().litValue;requests+=((id,addr))
          if(id>=16){assert(Set(17,18)(id) && addr==0x30004,s"unexpected walk id=$id addr=$addr")
            pending+=((id,if(id==18 && badInput) BigInt(0) else (BigInt(0x800)<<10)|0x43))}
          else {assert((id==6 && addr==0x800080) || (id==12 && addr==0x800040),s"wrong routing id=$id addr=$addr")
            pending+=((id,BigInt(0x9000+id)))}
        }
        if(offered.nonEmpty && io.memoryResponse.ready.peek().litToBoolean)offered=None
        d.clock.step();if(r)sendRender=false;if(i)sendInput=false;cycles+=1
      }
      assert(cycles<3000 && gotRender && gotInput && held==8)
      io.renderRequest.valid.poke(false.B);io.inputRequest.valid.poke(false.B);io.memoryResponse.valid.poke(false.B)
      d.clock.step(3);io.drained.expect(true.B)
      assert(requests.exists(_._1==17) && requests.exists(_._1==18))
      assert(requests.exists(_._1==6) && (requests.exists(_._1==12) != badInput))
    }
  }
  it should "load a patch word through the translated fabric before reporting completion" in {
    simulate(new TessellationInputTranslatedHarness){d=>
      d.io.satp.poke("h80000030".U);d.io.task.valid.poke(false.B);d.io.task.bits.poke(0.U.asTypeOf(d.io.task.bits))
      d.io.completion.ready.poke(false.B);d.io.load.ready.poke(true.B);d.io.reply.valid.poke(false.B);d.io.reply.bits.poke(0.U.asTypeOf(d.io.reply.bits))
      d.io.memoryRequest.ready.poke(true.B);d.io.memoryResponse.valid.poke(false.B);d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val t=d.io.task.bits;t.taskId.poke(9.U);t.vertices.poke(1.U);t.components.poke(1.U);t.stride.poke(4.U)
      t.view.base.poke(0x400040.U);t.view.limit.poke(0x400044.U);t.view.allocationBase.poke(0x400040.U);t.view.allocationLimit.poke(0x400080.U)
      d.io.task.valid.poke(true.B);d.io.task.ready.expect(true.B);d.clock.step();d.io.task.valid.poke(false.B)
      val replies=mutable.Queue.empty[(Int,BigInt)];var offered:Option[(Int,BigInt)]=None;var patchReply=false;var loaded=false;var cycles=0
      while(!d.io.completion.valid.peek().litToBoolean && cycles<1000){
        if(offered.isEmpty && replies.nonEmpty)offered=Some(replies.dequeue())
        d.io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>d.io.memoryResponse.bits.transactionId.poke(id.U);d.io.memoryResponse.bits.readData.poke(data.U)}
        d.io.reply.valid.poke(patchReply.B)
        if(d.io.memoryRequest.valid.peek().litToBoolean){val id=d.io.memoryRequest.bits.transactionId.peek().litValue.toInt
          val addr=d.io.memoryRequest.bits.address.peek().litValue
          if(id==18){assert(addr==0x30004);replies.enqueue((id,(BigInt(0x800)<<10)|0x43))}
          else {assert(id==12 && addr==0x800040);replies.enqueue((id,BigInt("3f800000",16)))}}
        if(offered.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean)offered=None
        val load=d.io.load.valid.peek().litToBoolean && d.io.load.ready.peek().litToBoolean
        if(load){assert(!loaded);loaded=true;d.io.load.bits.data.expect("h3f800000".U);d.io.load.bits.vertex.expect(0.U)}
        val reply=patchReply && d.io.reply.ready.peek().litToBoolean
        d.clock.step();if(reply)patchReply=false;if(load)patchReply=true;cycles+=1
      }
      assert(cycles<1000 && loaded && !patchReply && replies.isEmpty && offered.isEmpty)
      d.io.completion.bits.status.expect(0.U);d.io.completion.bits.words.expect(1.U)
      d.io.completion.ready.poke(true.B);d.clock.step();d.io.drained.expect(true.B)
    }
  }

}
