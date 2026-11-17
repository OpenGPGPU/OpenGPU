package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}
import opengpu.graphics._

object TessellationNativeTriangleJobLayout { val magic=BigInt("474e5102",16) }

class TessellationNativeTriangleJobCompletion(idWidth:Int,p:VertexShaderStageConfig) extends Bundle {
  val commandId=UInt(idWidth.W);val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 0 success/cancel, 1 descriptor encoding/bounds, 2 descriptor memory, 3 child. */
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W);val faultDraw=UInt(32.W)
  val descriptorReadBytes=UInt(64.W);val issuedDraws=UInt(32.W);val completedDraws=UInt(32.W)
  val frame=new TessellationSystemTriangleInputImageCompletion(p)
}

/** GNQ2/GNF1 job and frame reader for an array of GNT2 draw snapshots.
  * The caller supplies an Sv32-translated memory port and pins all mappings and
  * physical allocations. Writable frame spans cannot overlap the job, frame,
  * draw array or active image allocations in virtual address space. Translated
  * physical alias provenance remains the enclosing owner's responsibility.
  * Offers remain held across cancellation until accepted or the frame stops.
  */
class TessellationNativeTriangleJobReader(config:GpuConfig,p:VertexShaderStageConfig,idWidth:Int,engines:Int=2) extends Module {
  require(config.xLen==32 && idWidth>0 && idWidth<=12 && engines>=1 && engines<=4)
  import NativeBinnedGraphicsLayout._
  import NativeTypedGraphicsLayout.{check,receive}
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(idWidth)))
    val begin=Decoupled(new TypedVertexBinImageDescriptor)
    val draw=Decoupled(new TessellationNativeTriangleSequenceImageSubmit(idWidth));val end=Decoupled(Bool())
    val drawCompletion=Flipped(Decoupled(new TessellationNativeTriangleSequenceCompletion(idWidth)))
    val frameCompletion=Flipped(Decoupled(new TessellationSystemTriangleInputImageCompletion(p)))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4));val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val cancelCore=Output(Bool());val busy=Output(Bool())
    val completion=Decoupled(new TessellationNativeTriangleJobCompletion(idWidth,p))
  })
  private object Phase extends ChiselEnum {val idle,fetchJob,decodeJob,fetchFrame,decodeFrame,offerBegin,offerDraw,waitDraw,offerEnd,waitFrame,drain,finish,done=Value}
  private val phase=RegInit(Phase.idle);private val binding=Reg(new TileJobDescriptorRequest(idWidth))
  private val jr=Reg(new NativeBinnedGraphicsJobRecord);private val fr=Reg(new NativeBinnedGraphicsFrameRecord)
  private val job=jr.job;private val frame=fr.frame
  private val result=Reg(new TessellationNativeTriangleJobCompletion(idWidth,p));private val image=Reg(new TessellationSystemTriangleInputImageCompletion(p))
  private val status=RegInit(0.U(3.W));private val stage=RegInit(0.U(2.W));private val address=RegInit(0.U(32.W));private val faultDraw=RegInit(0.U(32.W))
  private val index=RegInit(0.U(32.W));private val issuedDraws=RegInit(0.U(32.W));private val completedDraws=RegInit(0.U(32.W));private val bytes=RegInit(0.U(64.W))
  private val arrayEnd=Reg(UInt(33.W));private val begun=RegInit(false.B);private val frameDone=RegInit(false.B);private val drawPending=RegInit(false.B);private val endAccepted=RegInit(false.B)
  private val beginOffered=RegInit(false.B);private val drawOffered=RegInit(false.B);private val endOffered=RegInit(false.B)
  private val fetchBase=Reg(UInt(32.W));private val lineCount=Reg(UInt(6.W));private val issued=RegInit(0.U(6.W))
  private val offered=RegInit(false.B);private val request=Reg(new ComputeMemoryRequest(config,64,4))
  private val live=RegInit(VecInit(Seq.fill(4)(false.B)));private val lineForTag=Reg(Vec(4,UInt(6.W)))
  private val active=phase=/=Phase.idle && phase=/=Phase.done
  private val childFault=(io.drawCompletion.valid && io.drawCompletion.bits.status=/=0.U) || (io.frameCompletion.valid && io.frameCompletion.bits.status=/=0.U)
  private val feedbackStatus=io.drawCompletion.bits.child.child.source.source.feedbackStatus
  private val feedbackPrimary=io.drawCompletion.fire && io.drawCompletion.bits.commandId===binding.commandId &&
    io.drawCompletion.bits.status===5.U && io.drawCompletion.bits.childValid && feedbackStatus=/=0.U && feedbackStatus=/=4.U
  private val stop=status=/=0.U || io.cancel || childFault
  private val fetching=phase===Phase.fetchJob || phase===Phase.fetchFrame
  private val drained= !offered && !live.asUInt.orR
  private val frameFinished=frameDone || io.frameCompletion.fire
  private def overlap(a:UInt,b:UInt,c:UInt,d:UInt):Bool=a<b && c<d && a<d && c<b
  private def descriptorAlias(base:UInt,end:UInt):Bool=overlap(base,end,binding.base,binding.base+&jobBytes.U) ||
    overlap(base,end,job.frameBase,job.frameBase+&frameBytes.U) ||
    (job.drawCount=/=0.U && overlap(base,end,job.drawBase,arrayEnd))
  private def imageRoles:Seq[(Bool,UInt,UInt,Bool)]={
    val d=frame.image;val enabled=TypedTileImageRules.enabled(d.pixels);val loads=TypedTileImageRules.loads(d.pixels)
    d.views.zipWithIndex.map{case(v,i)=>(enabled(i) && (v.store || loads(i)===AttachmentLoadOp.load.U),v.allocationBase,v.allocationLimit,v.store)}++
      d.resolves.zipWithIndex.map{case(r,i)=>(r.enable && enabled(i),r.destination.allocationBase,r.destination.allocationLimit,true.B)}
  }
  private def fail(code:UInt,s:UInt,a:UInt=0.U):Unit=when(status===0.U && !io.cancel){status:=code;stage:=s;address:=a;faultDraw:=index}
  private def fetch(next:Phase.Type,base:UInt,lines:Int):Unit={assert(drained);fetchBase:=base;lineCount:=lines.U;issued:=0.U;phase:=next}
  io.submit.ready:=phase===Phase.idle && !io.cancel;io.busy:=phase=/=Phase.idle
  io.begin.valid:=beginOffered;io.begin.bits:=frame
  io.draw.valid:=drawOffered && !frameFinished;io.draw.bits.commandId:=binding.commandId
  io.draw.bits.view.base:=(job.drawBase+index*job.drawStride)(31,0);io.draw.bits.view.limit:=job.drawLimit
  io.draw.bits.view.allocationBase:=job.drawBase;io.draw.bits.view.allocationLimit:=job.drawLimit
  io.end.valid:=endOffered && !frameFinished;io.end.bits:=true.B
  io.cancelCore:=begun && !frameDone && stop && !beginOffered && !drawOffered && !endOffered
  io.drawCompletion.ready:=begun && drawPending
  io.frameCompletion.ready:=begun && !frameDone
  io.completion.valid:=phase===Phase.done;io.completion.bits:=result
  when(active && io.cancel && status===0.U){status:=4.U;stage:=0.U;address:=0.U;faultDraw:=index}
  when(io.begin.fire){beginOffered:=false.B;begun:=true.B}
  when(io.draw.fire){drawOffered:=false.B;drawPending:=true.B;issuedDraws:=issuedDraws+1.U}
  when(io.end.fire){endOffered:=false.B;endAccepted:=true.B}
  when(io.drawCompletion.fire){drawPending:=false.B;completedDraws:=completedDraws+1.U
    val r=io.drawCompletion.bits
    when(r.commandId=/=binding.commandId){fail(7.U,3.U)}
      .elsewhen(feedbackPrimary && (status===0.U || status===4.U)){status:=5.U;stage:=3.U;address:=r.faultAddress;faultDraw:=index}
      .elsewhen(r.status=/=0.U){fail(r.status,3.U,r.faultAddress)}}
  when(io.frameCompletion.fire){image:=io.frameCompletion.bits;frameDone:=true.B;drawOffered:=false.B;endOffered:=false.B
    when(!feedbackPrimary){when(io.frameCompletion.bits.img.identity.asUInt=/=job.identity.asUInt ||
      (io.frameCompletion.bits.status===0.U && (!(endAccepted || io.end.fire) || completedDraws=/=job.drawCount))){fail(7.U,3.U)}
      .elsewhen(io.frameCompletion.bits.status=/=0.U){fail(io.frameCompletion.bits.status,3.U)}}}
  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=request
  private val tag=io.memoryResponse.bits.transactionId
  private val responseOwned=live(tag) || (io.memoryRequest.fire && request.transactionId===tag)
  io.memoryResponse.ready:=responseOwned
  when(io.memoryResponse.valid){assert(responseOwned,"native tessellation job received an unowned reply")}
  when(fetching && !stop && !offered && issued<lineCount && !live.asUInt.andR){
    val t=PriorityEncoder(~live.asUInt);request:=0.U.asTypeOf(request);request.address:=fetchBase+(issued<<6)
    request.sizeLog2:=6.U;request.byteMask:=Fill(64,1.U(1.W));request.cachePolicy:=CachePolicy.uncached;request.transactionId:=t
    lineForTag(t):=issued;issued:=issued+1.U;offered:=true.B}
  when(io.memoryRequest.fire){offered:=false.B;live(request.transactionId):=true.B;bytes:=bytes+64.U}
  private val replyLine=lineForTag(tag);private val writeReply=io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop
  private val jf=jobFields(jr);private val ff=frameFields(fr)
  check(jr,jf,jobWords);check(fr,ff,frameWords)
  private val badJob=receive(jf,jobWords,TessellationNativeTriangleJobLayout.magic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchJob)
  private val badFrame=receive(ff,frameWords,frameMagic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchFrame)
  when(io.memoryResponse.fire){live(tag):=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,2.U,fetchBase+(replyLine<<6))}
      .elsewhen(!stop && Mux(phase===Phase.fetchJob,badJob,badFrame)){fail(1.U,1.U,fetchBase+(replyLine<<6))}}
  switch(phase){
    is(Phase.idle){when(io.submit.fire){
      binding:=io.submit.bits;jr:=0.U.asTypeOf(jr);fr:=0.U.asTypeOf(fr);image:=0.U.asTypeOf(image)
      status:=0.U;stage:=0.U;address:=0.U;faultDraw:=0.U;index:=0.U;issuedDraws:=0.U;completedDraws:=0.U;bytes:=0.U
      begun:=false.B;frameDone:=false.B;drawPending:=false.B;endAccepted:=false.B;beginOffered:=false.B;drawOffered:=false.B;endOffered:=false.B
      val end=io.submit.bits.base+&jobBytes.U
      when(!io.submit.bits.envelopeValid || io.submit.bits.base(5,0)=/=0.U || io.submit.bits.limit>(BigInt(1)<<32).U || end>io.submit.bits.limit){
        status:=1.U;stage:=1.U;address:=io.submit.bits.base;phase:=Phase.finish
      }.otherwise{fetch(Phase.fetchJob,io.submit.bits.base,jobBytes/64)}}}
    is(Phase.fetchJob){when(stop){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodeJob}}
    is(Phase.decodeJob){
      val frameEnd=job.frameBase+&frameBytes.U
      val drawEnd=job.drawBase.pad(96)+(job.drawCount-1.U)*job.drawStride+TessellationNativeTriangleSequenceLayout.bytes.U
      val badFrameBounds=job.frameBase(5,0)=/=0.U || job.frameLimit>(BigInt(1)<<32).U || frameEnd>job.frameLimit
      val badDrawBounds=job.drawCount=/=0.U && (job.drawBase(5,0)=/=0.U || job.drawLimit(5,0)=/=0.U || job.drawStride(5,0)=/=0.U ||
        job.drawStride<TessellationNativeTriangleSequenceLayout.bytes.U || job.drawLimit>(BigInt(1)<<32).U || drawEnd>job.drawLimit)
      when(stop){phase:=Phase.drain}.elsewhen(!headerLegal(jr.header) || badFrameBounds || badDrawBounds){
        fail(1.U,1.U,Mux(!headerLegal(jr.header),binding.base,Mux(badFrameBounds,job.frameBase,job.drawBase)));phase:=Phase.drain
      }.otherwise{arrayEnd:=Mux(job.drawCount===0.U,job.drawBase.pad(33),drawEnd(32,0));fr:=0.U.asTypeOf(fr);fetch(Phase.fetchFrame,job.frameBase,frameBytes/64)}}
    is(Phase.fetchFrame){when(stop){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodeFrame}}
    is(Phase.decodeFrame){
      val b=frame.bins;val writable=Seq((b.producer.arena.base,b.producer.arena.limit),(b.bins.pool.base,b.bins.pool.limit),
        (b.bins.directoryBase,b.bins.directoryLimit))++frame.scratch.take(engines).map(s=>(s.base,s.limit))
      val immutable= !writable.map{case(a,z)=>descriptorAlias(a,z)}.reduce(_ || _)
      val imageAlias=imageRoles.map{case(active,a,z,write)=>active && (writable.map{case(x,y)=>overlap(a,z,x,y)}.reduce(_ || _) ||
        (write && (descriptorAlias(a,z) || overlap(a,z,frame.states.base,frame.states.limit))))}.reduce(_ || _)
      val compatible=headerLegal(fr.header) && frame.version===1.U && !b.bins.tileCount(31,16).orR &&
        frame.image.pixels.colors.identity.asUInt===job.identity.asUInt && frame.states.identity.asUInt===job.identity.asUInt &&
        b.producer.arena.identity.asUInt===job.identity.asUInt && b.bins.pool.identity.asUInt===job.identity.asUInt && immutable && !imageAlias
      when(stop){phase:=Phase.drain}.elsewhen(!compatible){fail(1.U,1.U,job.frameBase);phase:=Phase.drain}
        .otherwise{beginOffered:=true.B;phase:=Phase.offerBegin}}
    is(Phase.offerBegin){when(io.begin.fire){when(stop){phase:=Phase.drain}.elsewhen(job.drawCount===0.U){endOffered:=true.B;phase:=Phase.offerEnd}
      .otherwise{drawOffered:=true.B;phase:=Phase.offerDraw}}}
    is(Phase.offerDraw){when(frameFinished){phase:=Phase.drain}.elsewhen(io.draw.fire){phase:=Mux(stop,Phase.drain,Phase.waitDraw)}}
    is(Phase.waitDraw){when(stop || frameFinished){phase:=Phase.drain}.elsewhen(io.drawCompletion.fire){
      when(io.drawCompletion.bits.status=/=0.U || io.drawCompletion.bits.commandId=/=binding.commandId){phase:=Phase.drain}
        .elsewhen(index+&1.U===job.drawCount){endOffered:=true.B;phase:=Phase.offerEnd}
        .otherwise{index:=index+1.U;drawOffered:=true.B;phase:=Phase.offerDraw}}}
    is(Phase.offerEnd){when(frameFinished){phase:=Phase.drain}.elsewhen(io.end.fire){phase:=Phase.waitFrame}}
    is(Phase.waitFrame){when(stop || frameFinished){phase:=Phase.drain}}
    is(Phase.drain){when(drained && !beginOffered && !drawOffered && !endOffered && (!begun || frameDone) && !drawPending){phase:=Phase.finish}}
    is(Phase.finish){assert(drained && (!begun || frameDone) && !drawPending)
      result.commandId:=binding.commandId;result.identity:=job.identity;result.status:=status;result.faultStage:=stage
      result.faultAddress:=address;result.faultDraw:=faultDraw;result.descriptorReadBytes:=bytes
      result.issuedDraws:=issuedDraws;result.completedDraws:=completedDraws;result.frame:=image
      when(status===0.U){assert(begun && frameDone && completedDraws===job.drawCount)};phase:=Phase.done}
    is(Phase.done){when(io.completion.fire){phase:=Phase.idle}}
  }
}
