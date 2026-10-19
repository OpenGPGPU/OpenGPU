package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class WindowPrimitiveBinReplayDescriptor extends Bundle {
  val producer=new WindowPrimitiveBinDescriptor
  val bins=new TileBinDirectoryDescriptor
}
class WindowPrimitiveReplay(p: VertexShaderStageConfig) extends Bundle {
  val source=new PrimitiveBinReference;val record=new WindowPrimitiveRecord(p)
}
class WindowPrimitiveTileCompletion extends Bundle {
  val bins=new TileBinReplayCompletion
  val recordStatus=UInt(3.W)
}
class WindowPrimitiveBinReplayCompletion(p: VertexShaderStageConfig) extends Bundle {
  val status=UInt(3.W);val producer=new WindowPrimitiveBinCompletion(p)
  val bins=new TileBinReplayPassCompletion;val resolved=UInt(64.W);val recordReadLines=UInt(64.W)
}

/** Generic post-transform bin build and independently progressing record
  * replay engines. Each engine owns a tile through its final full-record
  * output and tile-result handshake. No vertex/clip/viewport work is repeated
  * for another tile. The native host, fragment state resolver and typed image
  * engine are intentionally external to this stream-level pass owner.
  *
  * Every store/fetch, queued request, held output and tile result drains before
  * completion. Stop the producer before cancelling bins, so its irrevocable
  * append receives a reply. A private bridge can synthesize a failed reply if
  * the directory has already closed. It exposes an append only on a ready
  * child cycle, avoiding an offer to a directory that cannot accept after a
  * spontaneous fault. Failed passes must discard all rendered tile outputs.
  */
class WindowPrimitiveBinReplayPass(config: GpuConfig=GpuConfig(),p: VertexShaderStageConfig=VertexShaderStageConfig(),
    g: GraphicsConfig=GraphicsConfig(),engines: Int=2,pageBytes: Int=4096,writeCredits: Int=8,
    tileSlots: Int=8,pagedCapacity: Int=0,useDirectorySramBlackBoxes: Boolean=false) extends Module {
  require(engines>=1 && engines<=4)
  private val binTransactions=writeCredits+engines+(if(pagedCapacity>0)1 else 0)
  private val writerId=binTransactions;private val transactions=binTransactions+1+engines
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new WindowPrimitiveBinReplayDescriptor))
    val in=Flipped(Decoupled(new WindowPrimitiveRecord(p)));val end=Flipped(Decoupled(Bool()))
    val out=Vec(engines,Decoupled(new WindowPrimitiveReplay(p)))
    val tileDone=Decoupled(new WindowPrimitiveTileCompletion)
    val completion=Decoupled(new WindowPrimitiveBinReplayCompletion(p))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val producer=Module(new WindowPrimitiveBinProducer(config,p,g))
  val recordBytes=producer.recordBytes
  private val bins=Module(new TileBinReplayPass(config,engines,pageBytes,writeCredits,tileSlots,pagedCapacity,
    useDirectorySramBlackBoxes,externalDispatchCredits=true))
  private val readers=Seq.tabulate(engines)(e=>Module(new WindowPrimitiveRecordTransfer(config,p,transactions,writerId+1+e)))
  private object State extends ChiselEnum {val idle,validate,start,run,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new WindowPrimitiveBinReplayDescriptor)
  private val producerOffered=RegInit(false.B);private val binsOffered=RegInit(false.B)
  private val producerStarted=RegInit(false.B);private val binsStarted=RegInit(false.B)
  private val producerDone=RegInit(false.B);private val binsDone=RegInit(false.B)
  private val endOffered=RegInit(false.B);private val endAccepted=RegInit(false.B)
  private val producerResult=Reg(new WindowPrimitiveBinCompletion(p));private val binResult=Reg(new TileBinReplayPassCompletion)
  private val status=RegInit(0.U(3.W));private val result=Reg(new WindowPrimitiveBinReplayCompletion(p))
  private val owned=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val pending=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val offered=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val source=Reg(Vec(engines,new PrimitiveBinReference));private val tileStatus=Reg(Vec(engines,UInt(3.W)))
  private val resolved=RegInit(VecInit(Seq.fill(engines)(0.U(64.W))))
  private val readLines=RegInit(VecInit(Seq.fill(engines)(0.U(64.W))))
  private val stop=status=/=0.U || io.cancel
  private def fail(code: UInt): Unit=when(status===0.U && !io.cancel){status:=code}
  io.begin.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.cancelSource:=phase=/=State.idle && phase=/=State.complete && stop
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  producer.io.begin.valid:=producerOffered;producer.io.begin.bits:=descriptor.producer
  bins.io.begin.valid:=binsOffered;bins.io.begin.bits:=descriptor.bins
  when(producer.io.begin.fire){producerOffered:=false.B;producerStarted:=true.B}
  when(bins.io.begin.fire){binsOffered:=false.B;binsStarted:=true.B}
  producer.io.in.valid:=phase===State.run && !stop && io.in.valid;producer.io.in.bits:=io.in.bits
  io.in.ready:=phase===State.run && !stop && producer.io.in.ready
  producer.io.end.valid:=phase===State.run && io.end.valid && !stop;producer.io.end.bits:=io.end.bits
  io.end.ready:=phase===State.run && producer.io.end.ready && !stop
  producer.io.cancel:=producerStarted && stop
  producer.io.completion.ready:=producerStarted && !producerDone
  when(producer.io.completion.fire){producerResult:=producer.io.completion.bits;producerDone:=true.B
    when(producer.io.completion.bits.status=/=0.U){fail(producer.io.completion.bits.status)}}
  bins.io.seal.valid:=false.B;bins.io.seal.bits:=0.U
  when(phase===State.run && producerDone && !stop && !binsDone && !endAccepted && !endOffered){endOffered:=true.B}
  bins.io.end.valid:=endOffered;bins.io.end.bits:=true.B
  when(bins.io.end.fire){endOffered:=false.B;endAccepted:=true.B}
  bins.io.cancel:=binsStarted && producerDone && stop && !endOffered
  bins.io.completion.ready:=binsStarted && !binsDone
  when(bins.io.completion.fire){binResult:=bins.io.completion.bits;binsDone:=true.B
    when(bins.io.completion.bits.status=/=0.U){fail(5.U)}}

  private object Bridge extends ChiselEnum {val empty,offer,waitReply,reply=Value}
  private val bridge=RegInit(Bridge.empty);private val append=Reg(new TileBinAppend);private val reply=Reg(new TileBinAppendResult)
  producer.io.append.ready:=bridge===Bridge.empty
  bins.io.append.valid:=bridge===Bridge.offer && bins.io.append.ready && !stop && !binsDone
  bins.io.append.bits:=append;bins.io.appended.ready:=bridge===Bridge.waitReply
  producer.io.appended.valid:=bridge===Bridge.reply;producer.io.appended.bits:=reply
  when(producer.io.append.fire){append:=producer.io.append.bits;bridge:=Bridge.offer}
  when(bridge===Bridge.offer){
    when(stop || binsDone){reply:=0.U.asTypeOf(reply);reply.identity:=descriptor.producer.arena.identity
      reply.tile:=append.tile;reply.reference:=append.reference;reply.status:=PrimitiveBinStatus.cancelled.U;bridge:=Bridge.reply}
      .elsewhen(bins.io.append.fire){bridge:=Bridge.waitReply}
  }
  when(bins.io.appended.fire){reply:=bins.io.appended.bits;bridge:=Bridge.reply
    when(bins.io.appended.bits.status=/=0.U){fail(5.U)}}
  when(producer.io.appended.fire){bridge:=Bridge.empty}

  for(e<-0 until engines){
    val r=readers(e)
    // Cancelled directory reports still need an unowned scheduler result slot.
    bins.io.dispatchPermit.get(e):= !owned(e)
    when(bins.io.tileAssigned.get(e)){owned(e):=true.B;tileStatus(e):=0.U}
    bins.io.reference(e).ready:= !pending(e)
    when(bins.io.reference(e).fire){when(!stop){source(e):=bins.io.reference(e).bits;pending(e):=true.B;offered(e):=true.B}}
    r.io.request.valid:=offered(e);r.io.request.bits:=0.U.asTypeOf(r.io.request.bits)
    r.io.request.bits.arena:=descriptor.producer.arena;r.io.request.bits.handle:=source(e).reference.primitive
    when(r.io.request.fire){offered(e):=false.B}
    r.io.cancel:=stop && !offered(e)
    val matchReference=r.io.result.bits.identity.asUInt===source(e).identity.asUInt &&
      r.io.result.bits.record.state===source(e).reference.state && r.io.result.bits.record.sequence===source(e).reference.sequence &&
      r.io.result.bits.record.token===source(e).reference.token
    val good=r.io.result.bits.status===0.U && r.io.result.bits.recordValid && matchReference
    io.out(e).valid:=pending(e) && r.io.result.valid && good
    io.out(e).bits.source:=source(e);io.out(e).bits.record:=r.io.result.bits.record
    r.io.result.ready:=pending(e) && (!good || io.out(e).ready)
    when(r.io.result.fire){pending(e):=false.B;readLines(e):=readLines(e)+r.io.result.bits.lines
      when(good){resolved(e):=resolved(e)+1.U}.otherwise{
        val code=Mux(r.io.result.bits.status===0.U,7.U,r.io.result.bits.status)
        tileStatus(e):=code;fail(code)
      }}
  }
  private val engine=bins.io.tileDone.bits.engine(log2Ceil(engines).max(1)-1,0)
  private val idleEngines=VecInit(readers.zipWithIndex.map{case(r,e)=> !pending(e) && !r.io.busy})
  io.tileDone.valid:=bins.io.tileDone.valid && idleEngines(engine)
  io.tileDone.bits.bins:=bins.io.tileDone.bits;io.tileDone.bits.recordStatus:=tileStatus(engine)
  bins.io.tileDone.ready:=io.tileDone.ready && idleEngines(engine)
  when(io.tileDone.fire){owned(engine):=false.B}

  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,transactions),engines+2))
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,transactions),2,pipe=true))
  arbiter.io.in(0).valid:=bins.io.memoryRequest.valid;arbiter.io.in(0).bits:=bins.io.memoryRequest.bits
  bins.io.memoryRequest.ready:=arbiter.io.in(0).ready
  arbiter.io.in(1).valid:=producer.io.memoryRequest.valid;arbiter.io.in(1).bits:=producer.io.memoryRequest.bits
  arbiter.io.in(1).bits.transactionId:=writerId.U;producer.io.memoryRequest.ready:=arbiter.io.in(1).ready
  for(e<-0 until engines)arbiter.io.in(e+2)<>readers(e).io.memoryRequest
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  val binResponse=io.memoryResponse.bits.transactionId<binTransactions.U
  val writerResponse=io.memoryResponse.bits.transactionId===writerId.U
  bins.io.memoryResponse.valid:=io.memoryResponse.valid && binResponse;bins.io.memoryResponse.bits:=io.memoryResponse.bits
  producer.io.memoryResponse.valid:=io.memoryResponse.valid && writerResponse;producer.io.memoryResponse.bits:=io.memoryResponse.bits
  producer.io.memoryResponse.bits.transactionId:=0.U
  io.memoryResponse.ready:=Mux(binResponse,bins.io.memoryResponse.ready,writerResponse && producer.io.memoryResponse.ready)
  for(e<-0 until engines){val selected=io.memoryResponse.bits.transactionId===(writerId+1+e).U
    readers(e).io.memoryResponse.valid:=io.memoryResponse.valid && selected;readers(e).io.memoryResponse.bits:=io.memoryResponse.bits
    when(selected){io.memoryResponse.ready:=readers(e).io.memoryResponse.ready}}

  when(io.cancel && phase=/=State.idle && phase=/=State.complete && status===0.U){status:=4.U}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;producerStarted:=false.B;binsStarted:=false.B
      producerDone:=false.B;binsDone:=false.B;endOffered:=false.B;endAccepted:=false.B
      producerResult:=0.U.asTypeOf(producerResult);binResult:=0.U.asTypeOf(binResult)
      for(e<-0 until engines){owned(e):=false.B;pending(e):=false.B;offered(e):=false.B;tileStatus(e):=0.U;resolved(e):=0.U;readLines(e):=0.U}
      phase:=State.validate}}
    is(State.validate){
      val d=descriptor.producer;val b=descriptor.bins
      val columns=(d.screenWidth.pad(17)+(1.U(17.W)<<d.tileLog2)-1.U)>>d.tileLog2
      val rows=(d.screenHeight.pad(17)+(1.U(17.W)<<d.tileLog2)-1.U)>>d.tileLog2
      val disjoint=d.arena.limit<=b.pool.base || b.pool.limit<=d.arena.base
      val directoryDisjoint=(b.version===1.U) || (b.directoryLimit<=d.arena.base || d.arena.limit<=b.directoryBase)
      val legal= !b.resume && b.pool.identity.asUInt===d.arena.identity.asUInt && columns*rows===b.tileCount &&
        columns*rows<=65535.U && disjoint && directoryDisjoint
      when(stop){phase:=State.finish}.elsewhen(!legal){status:=1.U;phase:=State.finish}
        .otherwise{producerOffered:=true.B;binsOffered:=true.B;phase:=State.start}
    }
    is(State.start){when(producerStarted && binsStarted){phase:=State.run}}
    is(State.run){when(producerDone && binsDone && !pending.asUInt.orR && !owned.asUInt.orR && bridge===Bridge.empty && queue.io.count===0.U){phase:=State.finish}}
    is(State.finish){result:=0.U.asTypeOf(result);result.status:=Mux(io.cancel && status===0.U,4.U,status)
      result.producer:=producerResult;result.bins:=binResult;result.resolved:=resolved.reduce(_+_);result.recordReadLines:=readLines.reduce(_+_)
      phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!producer.io.busy && !bins.io.busy && !readers.map(_.io.busy).reduce(_ || _) && queue.io.count===0.U,
    "general bin completion escaped record/bin/offer drain")}
}
