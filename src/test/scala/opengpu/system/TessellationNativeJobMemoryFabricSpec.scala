package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

private class TessellationNativeJobTranslatedHarness extends Module {
  private val cfg=GpuConfig(lanes=4,warps=2);private val p=VertexShaderStageConfig(VertexInputFetchConfig(2,2),2)
  private val slots=TessellationNativeJobMemoryFabric.transactions(8)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(4)))
    val begin=Decoupled(new TypedVertexBinImageDescriptor);val draw=Decoupled(new TessellationNativeSequenceImageSubmit(4))
    val end=Decoupled(Bool());val dc=Flipped(Decoupled(new TessellationNativeSequenceCompletion(4)))
    val fc=Flipped(Decoupled(new TessellationSystemIsolineInputImageCompletion(p)))
    val done=Decoupled(new TessellationNativeJobCompletion(4,p))
    val imageRequest=Flipped(Decoupled(new ComputeMemoryRequest(cfg,64,8)))
    val imageResponse=Decoupled(new ComputeMemoryResponse(64,8))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(cfg,64,slots));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,slots)))
    val drained=Output(Bool())
  })
  private val reader=Module(new TessellationNativeJobReader(cfg,p,4));private val fabric=Module(new TessellationNativeJobMemoryFabric(cfg,8))
  reader.io.submit<>io.submit;io.begin<>reader.io.begin;io.draw<>reader.io.draw;io.end<>reader.io.end
  reader.io.drawCompletion<>io.dc;reader.io.frameCompletion<>io.fc;io.done<>reader.io.completion;reader.io.cancel:=false.B
  fabric.io.jobRequest<>reader.io.memoryRequest;reader.io.memoryResponse<>fabric.io.jobResponse
  fabric.io.imageRequest<>io.imageRequest;io.imageResponse<>fabric.io.imageResponse
  fabric.io.satp:="h80000030".U;fabric.io.flush.valid:=false.B;fabric.io.flush.bits:=0.U.asTypeOf(fabric.io.flush.bits)
  io.memoryRequest<>fabric.io.memoryRequest;fabric.io.memoryResponse<>io.memoryResponse;io.drained:=fabric.io.drained
}

class TessellationNativeJobMemoryFabricSpec extends AnyFlatSpec {
  private def initializeImages(d:TessellationNativeJobMemoryFabric):Unit={
    val io=d.io
    io.satp.poke(0.U);io.flush.valid.poke(false.B);io.flush.bits.poke(0.U.asTypeOf(io.flush.bits))
    io.imageRequest.valid.poke(false.B);io.imageRequest.bits.poke(0.U.asTypeOf(io.imageRequest.bits))
    io.jobRequest.valid.poke(false.B);io.jobRequest.bits.poke(0.U.asTypeOf(io.jobRequest.bits))
    io.imageResponse.ready.poke(true.B);io.jobResponse.ready.poke(true.B)
    io.memoryRequest.ready.poke(true.B);io.memoryResponse.valid.poke(false.B)
    io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
    d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
    io.imageRequest.bits.sizeLog2.poke(6.U)
  }

  private def sendImage(d:TessellationNativeJobMemoryFabric,id:Int):Unit={
    val io=d.io
    io.imageRequest.bits.transactionId.poke(id.U);io.imageRequest.bits.address.poke((0x10000+64*id).U)
    io.imageRequest.valid.poke(true.B)
    var cycles=0
    while(!io.imageRequest.ready.peek().litToBoolean && cycles<16){d.clock.step();cycles+=1}
    assert(cycles<16);d.clock.step();io.imageRequest.valid.poke(false.B)
    cycles=0
    while(!io.memoryRequest.valid.peek().litToBoolean && cycles<16){d.clock.step();cycles+=1}
    assert(cycles<16);io.memoryRequest.bits.transactionId.expect(id.U);d.clock.step()
  }

  for(ids<-Seq(1,8,4096))
  it should s"retain image ownership across held faults, out-of-order replies and same-cycle reuse with $ids IDs" in {
    simulate(new TessellationNativeJobMemoryFabric(GpuConfig(lanes=4,warps=2),ids)){d=>
      initializeImages(d);val io=d.io
      val selected=if(ids<=8)(0 until ids).toVector else
        Vector(0,1,31,32,63,64,255,256,1023,1024,2047,2048,4094,4095)
      selected.foreach(id=>sendImage(d,id))
      io.drained.expect(false.B)
      // A flush or a caller that stops issuing requests cannot abandon replies.
      io.flush.valid.poke(true.B);d.clock.step(3);io.flush.valid.poke(false.B)
      io.drained.expect(false.B)
      for(id<-selected.reverse){
        io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(id.U)
        io.memoryResponse.bits.readData.poke((0x80000000L+id).U)
        io.memoryResponse.bits.fault.poke((id%2==0).B);io.imageResponse.ready.poke(false.B)
        val held=io.imageResponse.bits.peek().litValue
        for(_<-0 until 3){
          io.imageResponse.valid.expect(true.B);io.imageResponse.bits.transactionId.expect(id.U)
          assert(io.imageResponse.bits.peek().litValue==held)
          io.memoryResponse.ready.expect(false.B);io.drained.expect(false.B);d.clock.step()
        }
        io.imageResponse.ready.poke(true.B);d.clock.step();io.memoryResponse.valid.poke(false.B)
      }
      d.clock.step();io.drained.expect(true.B)
      val last=ids-1;sendImage(d,last)
      io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(last.U)
      io.memoryResponse.bits.fault.poke(false.B)
      io.imageRequest.valid.poke(true.B);io.imageRequest.bits.transactionId.poke(last.U)
      io.imageRequest.ready.expect(true.B);io.memoryResponse.ready.expect(true.B)
      d.clock.step();io.imageRequest.valid.poke(false.B);io.memoryResponse.valid.poke(false.B)
      io.drained.expect(false.B)
      io.memoryRequest.valid.expect(true.B);io.memoryRequest.bits.transactionId.expect(last.U);d.clock.step()
      io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(last.U)
      d.clock.step();io.memoryResponse.valid.poke(false.B);d.clock.step();io.drained.expect(true.B)
      // Reset clears the old epoch; all IDs remain reusable in the new epoch.
      sendImage(d,last);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.drained.expect(true.B);sendImage(d,last)
      io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(last.U)
      d.clock.step();io.memoryResponse.valid.poke(false.B);d.clock.step();io.drained.expect(true.B)
    }
  }

  it should "retain accepted owners while an unaccepted image offer waits behind the full queue" in {
    simulate(new TessellationNativeJobMemoryFabric(GpuConfig(lanes=4,warps=2),8)){d=>
      initializeImages(d);val io=d.io;io.memoryRequest.ready.poke(false.B)
      for(id<-0 until 2){
        io.imageRequest.valid.poke(true.B);io.imageRequest.bits.transactionId.poke(id.U)
        io.imageRequest.ready.expect(true.B);d.clock.step()
      }
      io.imageRequest.bits.transactionId.poke(2.U)
      val held=io.memoryRequest.bits.peek().litValue
      for(_<-0 until 4){
        io.imageRequest.ready.expect(false.B);io.memoryRequest.valid.expect(true.B)
        assert(io.memoryRequest.bits.peek().litValue==held);io.drained.expect(false.B);d.clock.step()
      }
      io.memoryRequest.ready.poke(true.B);io.imageRequest.ready.expect(true.B)
      io.memoryRequest.bits.transactionId.expect(0.U);d.clock.step();io.imageRequest.valid.poke(false.B)
      for(id<-1 to 2){io.memoryRequest.valid.expect(true.B);io.memoryRequest.bits.transactionId.expect(id.U);d.clock.step()}
      for(id<-Seq(2,0,1)){
        io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(id.U);d.clock.step()
      }
      io.memoryResponse.valid.poke(false.B);d.clock.step();io.drained.expect(true.B)
    }
  }

  for(kind<-Seq("duplicate","unowned","late-after-reset","unreserved-single-id"))
  it should s"reject $kind image ownership violations" in {
    intercept[chisel3.simulator.Exceptions.AssertionFailed]{
      simulate(new TessellationNativeJobMemoryFabric(GpuConfig(lanes=4,warps=2),if(kind=="unreserved-single-id")1 else 8)){d=>
        initializeImages(d);val io=d.io
        if(kind=="duplicate"){
          sendImage(d,7);io.imageRequest.valid.poke(true.B);io.imageRequest.bits.transactionId.poke(7.U)
        }else if(kind=="unreserved-single-id"){
          io.imageRequest.valid.poke(true.B);io.imageRequest.bits.transactionId.poke(1.U)
        }else{
          if(kind=="late-after-reset"){
            sendImage(d,7);d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
          }
          io.memoryResponse.valid.poke(true.B);io.memoryResponse.bits.transactionId.poke(7.U)
          io.imageResponse.ready.poke(false.B)
        }
        d.clock.step()
      }
    }
  }

  for(pageFault<-Seq(false,true))
  it should s"retain four job tags alongside image traffic with page fault=$pageFault" in {
    simulate(new TessellationNativeJobMemoryFabric(GpuConfig(lanes=4,warps=2),8)){d=>
      val io=d.io;io.satp.poke("h80000030".U);io.flush.valid.poke(false.B);io.flush.bits.poke(0.U.asTypeOf(io.flush.bits))
      io.imageRequest.valid.poke(false.B);io.imageRequest.bits.poke(0.U.asTypeOf(io.imageRequest.bits))
      io.jobRequest.valid.poke(false.B);io.jobRequest.bits.poke(0.U.asTypeOf(io.jobRequest.bits))
      io.imageResponse.ready.poke(false.B);io.jobResponse.ready.poke(false.B)
      io.memoryRequest.ready.poke(false.B);io.memoryResponse.valid.poke(false.B);io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      io.imageRequest.bits.address.poke(0x12000.U);io.imageRequest.bits.transactionId.poke(3.U);io.imageRequest.bits.sizeLog2.poke(6.U)
      io.jobRequest.bits.sizeLog2.poke(6.U)
      var next=0;var sendImage=true;var imageDone=false;var cycle=0;var held=0;var imageHeld=0
      val done=mutable.Set.empty[Int];val pending=mutable.ArrayBuffer.empty[(Int,BigInt)]
      val seen=mutable.ArrayBuffer.empty[(Int,BigInt)];var offered:Option[(Int,BigInt)]=None
      var heldRequest:Option[BigInt]=None;var heldReply:Option[BigInt]=None;var heldImage:Option[BigInt]=None
      while((done.size<4 || !imageDone || pending.nonEmpty || offered.nonEmpty) && cycle<5000){
        io.imageRequest.valid.poke(sendImage.B);io.jobRequest.valid.poke((next<4).B)
        io.jobRequest.bits.transactionId.poke((next.min(3)).U);io.jobRequest.bits.address.poke((0x400000+next.min(3)*64).U)
        io.memoryRequest.ready.poke((cycle%4!=0).B)
        if(offered.isEmpty && pending.nonEmpty)offered=Some(pending.remove(pending.size-1))
        io.memoryResponse.valid.poke(offered.nonEmpty.B)
        offered.foreach{case(id,data)=>io.memoryResponse.bits.transactionId.poke(id.U);io.memoryResponse.bits.readData.poke(data.U);io.memoryResponse.bits.fault.poke(false.B)}
        val hold=io.jobResponse.valid.peek().litToBoolean && held<8;io.jobResponse.ready.poke((!hold).B)
        if(io.jobResponse.valid.peek().litToBoolean){val bits=io.jobResponse.bits.peek().litValue;heldReply.foreach(x=>assert(x==bits));heldReply=if(hold)Some(bits)else None
          val id=io.jobResponse.bits.transactionId.peek().litValue.toInt;io.jobResponse.bits.fault.expect(pageFault.B)
          if(hold){held+=1;io.drained.expect(false.B)}else{assert(!done(id));done+=id;if(!pageFault)io.jobResponse.bits.readData.expect((0x9008+id).U)}}else assert(heldReply.isEmpty)
        val holdImage=io.imageResponse.valid.peek().litToBoolean && imageHeld<5;io.imageResponse.ready.poke((!holdImage).B)
        if(io.imageResponse.valid.peek().litToBoolean){val bits=io.imageResponse.bits.peek().litValue;heldImage.foreach(x=>assert(x==bits));heldImage=if(holdImage)Some(bits)else None
          io.imageResponse.bits.transactionId.expect(3.U);io.imageResponse.bits.readData.expect(0x9003.U)
          if(holdImage){imageHeld+=1;io.drained.expect(false.B)}else{assert(!imageDone);imageDone=true}}else assert(heldImage.isEmpty)
        val img=sendImage && io.imageRequest.ready.peek().litToBoolean;val job=next<4 && io.jobRequest.ready.peek().litToBoolean
        val valid=io.memoryRequest.valid.peek().litToBoolean;val ready=io.memoryRequest.ready.peek().litToBoolean
        heldRequest.foreach(x=>assert(valid && io.memoryRequest.bits.peek().litValue==x));heldRequest=if(valid && !ready)Some(io.memoryRequest.bits.peek().litValue)else None
        if(valid && ready){val id=io.memoryRequest.bits.transactionId.peek().litValue.toInt;val addr=io.memoryRequest.bits.address.peek().litValue;seen+=((id,addr))
          if(id==12){assert(addr==0x30004);pending+=((id,if(pageFault)BigInt(0)else (BigInt(0x800)<<10)|0x43))}
          else if(id==3){assert(addr==0x12000);pending+=((id,BigInt(0x9003)))}
          else {assert(id>=8 && id<12 && addr==0x800000+(id-8)*64,s"id=$id address=$addr");pending+=((id,BigInt(0x9000+id)))}}
        if(offered.nonEmpty && io.memoryResponse.ready.peek().litToBoolean)offered=None
        d.clock.step();if(img)sendImage=false;if(job)next+=1;cycle+=1
      }
      assert(cycle<5000 && done==Set(0,1,2,3) && imageDone && held==8 && imageHeld==5)
      io.imageRequest.valid.poke(false.B);io.jobRequest.valid.poke(false.B);io.memoryResponse.valid.poke(false.B)
      d.clock.step(3);io.drained.expect(true.B)
      assert(seen.exists(_._1==12) && seen.contains((3,BigInt(0x12000))))
      assert(seen.count(x=>x._1>=8 && x._1<12)==(if(pageFault)0 else 4))
    }
  }
  it should "read a complete job and frame through Sv32 while image traffic shares the port" in {
    import NativeBinnedGraphicsLayout._
    simulate(new TessellationNativeJobTranslatedHarness){d=>
      val io=d.io;io.submit.valid.poke(false.B);io.submit.bits.poke(0.U.asTypeOf(io.submit.bits))
      io.begin.ready.poke(true.B);io.draw.ready.poke(true.B);io.end.ready.poke(true.B)
      io.dc.valid.poke(false.B);io.dc.bits.poke(0.U.asTypeOf(io.dc.bits));io.fc.valid.poke(false.B);io.fc.bits.poke(0.U.asTypeOf(io.fc.bits))
      io.done.ready.poke(false.B);io.imageRequest.valid.poke(false.B);io.imageRequest.bits.poke(0.U.asTypeOf(io.imageRequest.bits));io.imageResponse.ready.poke(true.B)
      io.memoryRequest.ready.poke(true.B);io.memoryResponse.valid.poke(false.B);io.memoryResponse.bits.poke(0.U.asTypeOf(io.memoryResponse.bits))
      d.reset.poke(true.B);d.clock.step();d.reset.poke(false.B)
      val memory=mutable.Map.empty[BigInt,BigInt]
      val header=Map[String,BigInt]("header.attributes"->16,"header.bindings"->16,"header.varyings"->16,"header.colors"->4,"header.engineViews"->4)
      def ids(prefix:String)=Map(prefix+"context"->BigInt(7),prefix+"pass"->BigInt(8),prefix+"generation"->BigInt(9))
      def put(base:BigInt,words:Int,magic:BigInt,fields:Seq[NativeTypedGraphicsLayout.Field],values:Map[String,BigInt]):Unit={
        for(w<-0 until words)memory(base+4*w)=0;memory(base)=magic;memory(base+4)=words*4;memory(base+8)=1
        for(f<-fields){val value=values.getOrElse(f.name,BigInt(0)) & ((BigInt(1)<<(32*f.words))-1)
          for(w<-0 until f.words)memory(base+4*(f.word+w))=(value>>(32*w)) & 0xffffffffL}}
      val job=header++ids("job.identity.")++Map[String,BigInt]("job.frameBase"->0x401000,"job.frameLimit"->0x401800,
        "job.drawBase"->0x402000,"job.drawLimit"->0x402800,"job.drawCount"->1,"job.drawStride"->2048)
      val frame=header++ids("frame.image.pixels.colors.identity.")++ids("frame.states.identity.")++
        ids("frame.bins.producer.arena.identity.")++ids("frame.bins.bins.pool.identity.")++Map[String,BigInt]("frame.version"->1)
      put(0x800000,jobWords,TessellationNativeJobLayout.magic,jobFields(new NativeBinnedGraphicsJobRecord),job)
      put(0x801000,frameWords,frameMagic,frameFields(new NativeBinnedGraphicsFrameRecord),frame)
      io.submit.bits.commandId.poke(5.U);io.submit.bits.base.poke(0x400000.U);io.submit.bits.limit.poke((0x400000+jobBytes).U);io.submit.bits.envelopeValid.poke(true.B)
      io.imageRequest.bits.address.poke(0x12000.U);io.imageRequest.bits.transactionId.poke(3.U);io.imageRequest.bits.sizeLog2.poke(6.U)
      var submitPending=true;var imagePending=true;var imageDone=false;var childPending=false;var framePending=false
      var beginSeen=false;var drawSeen=false;var endSeen=false;var cycles=0;var lines=0;var walks=0
      val responses=mutable.ArrayBuffer.empty[(Int,BigInt)];var offered:Option[(Int,BigInt)]=None
      while((!io.done.valid.peek().litToBoolean || !imageDone) && cycles<5000){
        io.submit.valid.poke(submitPending.B);io.imageRequest.valid.poke(imagePending.B)
        if(offered.isEmpty && responses.nonEmpty)offered=Some(responses.remove(responses.size-1))
        io.memoryResponse.valid.poke(offered.nonEmpty.B);offered.foreach{case(id,data)=>io.memoryResponse.bits.transactionId.poke(id.U);io.memoryResponse.bits.readData.poke(data.U)}
        io.dc.valid.poke(childPending.B);io.dc.bits.commandId.poke(5.U);io.dc.bits.status.poke(0.U)
        io.fc.valid.poke(framePending.B);io.fc.bits.img.identity.context.poke(7.U);io.fc.bits.img.identity.pass.poke(8.U);io.fc.bits.img.identity.generation.poke(9.U);io.fc.bits.status.poke(0.U)
        val sub=submitPending && io.submit.ready.peek().litToBoolean;val img=imagePending && io.imageRequest.ready.peek().litToBoolean
        if(io.memoryRequest.valid.peek().litToBoolean){val id=io.memoryRequest.bits.transactionId.peek().litValue.toInt;val addr=io.memoryRequest.bits.address.peek().litValue
          if(id==12){assert(addr==0x30004);walks+=1;responses+=((id,(BigInt(0x800)<<10)|0x43))}
          else if(id==3){assert(addr==0x12000);responses+=((id,BigInt(0x9876)))}
          else {assert(id>=8 && id<12 && ((addr>=0x800000 && addr<0x800200) || (addr>=0x801000 && addr<0x801800)),s"id=$id address=$addr")
            val data=(0 until 16).foldLeft(BigInt(0))((x,w)=>x | (memory.getOrElse(addr+4*w,BigInt(0))<<(32*w)))
            lines+=1;responses+=((id,data))}}
        if(offered.nonEmpty && io.memoryResponse.ready.peek().litToBoolean)offered=None
        if(io.imageResponse.valid.peek().litToBoolean){io.imageResponse.bits.transactionId.expect(3.U);io.imageResponse.bits.readData.expect(0x9876.U);imageDone=true}
        val begin=io.begin.valid.peek().litToBoolean;val draw=io.draw.valid.peek().litToBoolean;val end=io.end.valid.peek().litToBoolean
        if(begin){assert(!beginSeen);beginSeen=true;io.begin.bits.version.expect(1.U)}
        if(draw){assert(!drawSeen);drawSeen=true;io.draw.bits.commandId.expect(5.U);io.draw.bits.view.base.expect(0x402000.U)}
        if(end){assert(!endSeen);endSeen=true}
        val dc=childPending && io.dc.ready.peek().litToBoolean;val fc=framePending && io.fc.ready.peek().litToBoolean
        d.clock.step();if(sub)submitPending=false;if(img)imagePending=false;if(dc)childPending=false;if(draw)childPending=true;if(fc)framePending=false;if(end)framePending=true;cycles+=1
      }
      assert(cycles<5000 && beginSeen && drawSeen && endSeen && imageDone && lines==(jobBytes+frameBytes)/64 && walks>=1 && responses.isEmpty && offered.isEmpty)
      io.submit.valid.poke(false.B);io.imageRequest.valid.poke(false.B);io.memoryResponse.valid.poke(false.B)
      io.done.bits.status.expect(0.U);io.done.bits.issuedDraws.expect(1.U);io.done.bits.completedDraws.expect(1.U)
      io.done.ready.poke(true.B);d.clock.step();d.clock.step(2);io.drained.expect(true.B)
    }
  }

}
