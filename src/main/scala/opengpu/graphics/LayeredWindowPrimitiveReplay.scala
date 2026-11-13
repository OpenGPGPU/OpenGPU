package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class LayeredWindowReplayDescriptor extends Bundle {
  val arena=new PrimitiveStateDescriptor;val pool=new BinPagePoolDescriptor
  val chain=new TileBinCompletion;val layers=UInt(9.W)
}
class LayeredWindowPrimitiveReplay(p:VertexShaderStageConfig) extends Bundle {
  /** Original bin ordinal remains available for externally reported ownership;
    * replay.source.ordinal is the selected layer's contiguous local ordinal.
    */
  val original=new PrimitiveBinReference;val replay=new WindowPrimitiveReplay(p)
}
class LayeredWindowReplayCompletion extends Bundle {
  val identity=new GraphicsWorkIdentity;val tile=UInt(32.W);val status=UInt(3.W)
  /** 1 descriptor/chain,2 page reader,3 stored record,4 image owner. */
  val faultStage=UInt(3.W);val faultLayer=UInt(9.W);val completedLayers=UInt(9.W)
  val chain=new TileBinCompletion;val pages=new PrimitiveBinReadCompletion
  val image=new TypedReplayTileCompletion
  val scanned=UInt(40.W);val selected=UInt(40.W);val pageReadLines=UInt(40.W);val recordReadLines=UInt(64.W)
}
class LayeredWindowReplayFault extends Bundle {
  val status=UInt(3.W);val stage=UInt(3.W);val layer=UInt(9.W)
}

/** Retain one immutable tile chain across all layer scans. The enclosing image
  * owner binds each offered layer, consumes selected records in order, and
  * returns its real TypedReplayTileCompletion plus busy/drain state. A layer is
  * never rescanned until that image owner has completely retired; the final
  * result retains the original chain, including exhausted-prefix restart data.
  *
  * The enclosing owner must admit the complete layered attachment descriptor
  * before binding this scanner, retain every page/record/state/backing lease,
  * and drain an already offered record during cancellation even when it can no
  * longer submit that record to its image child. This block issues only reads;
  * it neither repeats source shaders nor produces/stores replacement records.
  */
class LayeredWindowPrimitiveReplayPipeline(config:GpuConfig=GpuConfig(),p:VertexShaderStageConfig=VertexShaderStageConfig(),
    pageBytes:Int=4096) extends Module {
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new LayeredWindowReplayDescriptor))
    val layerBegin=Decoupled(UInt(32.W));val out=Decoupled(new LayeredWindowPrimitiveReplay(p))
    val layerEnd=Decoupled(Bool());val layerCompletion=Flipped(Decoupled(new TypedReplayTileCompletion))
    val layerBusy=Input(Bool());val cancelLayer=Output(Bool())
    /** Registered first non-cancellation cause while children are draining.
      * The enclosing image owner must not replace this with its cancel4.
      */
    val fault=Output(Valid(new LayeredWindowReplayFault))
    val completion=Decoupled(new LayeredWindowReplayCompletion)
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,2))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,2)))
    val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val reader=Module(new PrimitiveBinReader(config,pageBytes,2,0))
  private val record=Module(new WindowPrimitiveRecordTransfer(config,p,2,1))
  private object State extends ChiselEnum {val idle,validate,bind,scan,endLayer,waitImage,closing,finish,complete=Value}
  private val phase=RegInit(State.idle);private val descriptor=Reg(new LayeredWindowReplayDescriptor)
  private val layer=RegInit(0.U(9.W));private val completedLayers=RegInit(0.U(9.W))
  private val status=RegInit(0.U(3.W));private val stage=RegInit(0.U(3.W));private val faultLayer=Reg(UInt(9.W))
  private val cancelled=RegInit(false.B)
  private val layerOffered=RegInit(false.B);private val layerStarted=RegInit(false.B);private val layerDone=RegInit(false.B)
  private val endOffered=RegInit(false.B)
  private val readerOffered=RegInit(false.B);private val readerStarted=RegInit(false.B);private val readerDone=RegInit(false.B)
  private val recordOffered=RegInit(false.B);private val recordStarted=RegInit(false.B)
  private val source=Reg(new PrimitiveBinReference);private val output=Reg(new LayeredWindowPrimitiveReplay(p));private val outputValid=RegInit(false.B)
  private val layerSelected=RegInit(0.U(32.W));private val scanned=RegInit(0.U(40.W));private val selected=RegInit(0.U(40.W))
  private val pageLines=RegInit(0.U(40.W));private val recordLines=RegInit(0.U(64.W))
  private val pageResult=Reg(new PrimitiveBinReadCompletion);private val imageResult=Reg(new TypedReplayTileCompletion)
  private val result=Reg(new LayeredWindowReplayCompletion)
  private val stop=status=/=0.U || cancelled || io.cancel
  private def fail(code:UInt,where:Int): Unit=when(status===0.U && !cancelled && !io.cancel){
    status:=code;stage:=where.U;faultLayer:=layer
  }
  private val active=phase=/=State.idle && phase=/=State.complete
  io.fault.valid:=active && status=/=0.U && status=/=4.U
  io.fault.bits.status:=status;io.fault.bits.stage:=stage;io.fault.bits.layer:=faultLayer
  io.begin.ready:=phase===State.idle && !io.cancel;io.busy:=phase=/=State.idle
  io.completion.valid:=phase===State.complete;io.completion.bits:=result
  io.layerBegin.valid:=layerOffered;io.layerBegin.bits:=layer.pad(32)
  when(io.layerBegin.fire){layerOffered:=false.B;layerStarted:=true.B
    when(!stop){readerOffered:=true.B;phase:=State.scan}.otherwise{phase:=State.closing}}
  io.layerEnd.valid:=endOffered;io.layerEnd.bits:=true.B
  when(io.layerEnd.fire){endOffered:=false.B;phase:=State.waitImage}
  io.cancelLayer:=layerStarted && stop
  io.layerCompletion.ready:=layerStarted && !layerDone
  when(io.layerCompletion.fire){layerDone:=true.B;imageResult:=io.layerCompletion.bits
    val r=io.layerCompletion.bits
    val identity=r.identity.asUInt===descriptor.arena.identity.asUInt && r.tile===descriptor.chain.tile
    when(!identity){fail(7.U,4)}
      .elsewhen(r.status=/=0.U){fail(r.status,4)}
      .elsewhen(r.accepted=/=layerSelected || r.issued=/=layerSelected || r.completed=/=layerSelected){fail(7.U,4)}
  }
  reader.io.begin.valid:=readerOffered;reader.io.begin.bits.version:=1.U
  reader.io.begin.bits.pool:=descriptor.pool;reader.io.begin.bits.tile:=descriptor.chain.tile
  reader.io.begin.bits.headValid:=descriptor.chain.headValid;reader.io.begin.bits.head:=descriptor.chain.head
  reader.io.begin.bits.count:=descriptor.chain.committedCount
  when(reader.io.begin.fire){readerOffered:=false.B;readerStarted:=true.B}
  reader.io.cancel:=readerStarted && stop
  reader.io.reference.ready:=stop || (phase===State.scan && !recordOffered && !recordStarted && !outputValid)
  when(reader.io.reference.fire){scanned:=scanned+1.U
    when(!stop){source:=reader.io.reference.bits;recordOffered:=true.B}}
  reader.io.completion.ready:=readerStarted && !readerDone
  when(reader.io.completion.fire){readerDone:=true.B;pageResult:=reader.io.completion.bits
    pageLines:=pageLines+reader.io.completion.bits.readLines
    when(reader.io.completion.bits.status=/=PrimitiveBinStatus.success.U){
      fail(Mux(reader.io.completion.bits.status===PrimitiveBinStatus.memoryFault.U,3.U,
        Mux(reader.io.completion.bits.status===PrimitiveBinStatus.cancelled.U,4.U,7.U)),2)
    }}
  record.io.request.valid:=recordOffered;record.io.request.bits.arena:=descriptor.arena
  record.io.request.bits.handle:=source.reference.primitive;record.io.request.bits.write:=false.B
  record.io.request.bits.record:=0.U.asTypeOf(record.io.request.bits.record)
  when(record.io.request.fire){recordOffered:=false.B;recordStarted:=true.B}
  record.io.cancel:=recordStarted && stop
  record.io.result.ready:=recordStarted && !outputValid
  when(record.io.result.fire){recordStarted:=false.B;recordLines:=recordLines+record.io.result.bits.lines
    val r=record.io.result.bits;val x=r.record
    val matches=r.identity.asUInt===source.identity.asUInt && r.handle===source.reference.primitive &&
      source.identity.asUInt===descriptor.arena.identity.asUInt && source.tile===descriptor.chain.tile &&
      x.sequence===source.reference.sequence && x.state===source.reference.state && x.token===source.reference.token
    val recordLayer=Mux(x.geometry.valid,x.geometry.bits.layer,0.U)
    when(r.status=/=0.U){fail(r.status,3)}
      .elsewhen(!r.recordValid || !matches || recordLayer>=descriptor.layers){fail(7.U,3)}
      .elsewhen(!stop && recordLayer===layer){
        output.original:=source;output.replay.source:=source;output.replay.source.ordinal:=layerSelected
        output.replay.record:=x;outputValid:=true.B
      }
  }
  io.out.valid:=outputValid;io.out.bits:=output
  when(io.out.fire){outputValid:=false.B;when(!stop){layerSelected:=layerSelected+1.U;selected:=selected+1.U}}
  private val arbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,2),2))
  arbiter.io.in(0)<>reader.io.memoryRequest;arbiter.io.in(1)<>record.io.memoryRequest
  private val queue=Module(new Queue(new ComputeMemoryRequest(config,64,2),2,pipe=true))
  queue.io.enq<>arbiter.io.out;io.memoryRequest<>queue.io.deq
  reader.io.memoryResponse.valid:=io.memoryResponse.valid && io.memoryResponse.bits.transactionId===0.U
  reader.io.memoryResponse.bits:=io.memoryResponse.bits
  record.io.memoryResponse.valid:=io.memoryResponse.valid && io.memoryResponse.bits.transactionId===1.U
  record.io.memoryResponse.bits:=io.memoryResponse.bits
  io.memoryResponse.ready:=Mux(io.memoryResponse.bits.transactionId===0.U,reader.io.memoryResponse.ready,record.io.memoryResponse.ready)
  private val inFlight=RegInit(0.U(3.W));inFlight:=inFlight+io.memoryRequest.fire.asUInt-io.memoryResponse.fire.asUInt
  when(io.memoryResponse.fire){assert(inFlight.orR || io.memoryRequest.fire,"unowned layered replay memory response")}
  private val memoryDrained=inFlight===0.U && queue.io.count===0.U
  private val scanDrained= !readerOffered && !recordOffered && !recordStarted && !outputValid &&
    (!readerStarted || (readerDone && !reader.io.busy)) && !record.io.busy && memoryDrained
  when(active && io.cancel){cancelled:=true.B}
  switch(phase){
    is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;layer:=0.U;completedLayers:=0.U
      status:=0.U;stage:=0.U;faultLayer:=0.U;cancelled:=false.B
      layerOffered:=false.B;layerStarted:=false.B;layerDone:=false.B;endOffered:=false.B
      readerOffered:=false.B;readerStarted:=false.B;readerDone:=false.B;recordOffered:=false.B;recordStarted:=false.B;outputValid:=false.B
      layerSelected:=0.U;scanned:=0.U;selected:=0.U;pageLines:=0.U;recordLines:=0.U
      pageResult:=0.U.asTypeOf(pageResult);imageResult:=0.U.asTypeOf(imageResult);phase:=State.validate}}
    is(State.validate){
      val d=descriptor;val c=d.chain;val pool=d.pool
      var previous:UInt=pool.initialLimit
      val segments=(0 until BinPagePoolRules.maxSpills).map{i=>
        val valid=BinPagePoolRules.segmentLegal(pool,i.U,previous,pageBytes)
        previous=Mux(i.U<pool.spillCount,pool.spills(i).limit,previous);valid
      }.reduce(_ && _)
      val poolValid=BinPagePoolRules.headerLegal(pool,pageBytes) &&
        (pool.version===1.U || (segments && previous===pool.limit))
      var pageCount:UInt=(Mux(pool.version===2.U,pool.initialLimit,pool.limit)-pool.base.pad(33))>>log2Ceil(pageBytes)
      for(i<-0 until BinPagePoolRules.maxSpills){
        pageCount=pageCount+&Mux(pool.version===2.U && i.U<pool.spillCount,
          (pool.spills(i).limit-pool.spills(i).base.pad(33))>>log2Ceil(pageBytes),0.U)
      }
      val maximumReferences=pageCount*PrimitiveBinPageLayout.recordsPerPage(pageBytes).U
      val prefix=(c.status===PrimitiveBinStatus.success.U && (c.committedCount===0.U || c.prefixUsable)) ||
        (c.status===PrimitiveBinStatus.exhausted.U && c.prefixUsable)
      val valid=d.layers>=1.U && d.layers<=256.U && d.arena.version===1.U && d.arena.base(5,0)===0.U &&
        d.arena.limit(5,0)===0.U && d.arena.limit>=d.arena.base && d.arena.limit<=(BigInt(1)<<32).U &&
        (c.committedCount===0.U || d.arena.limit>d.arena.base) && c.committedCount<=maximumReferences &&
        d.arena.identity.asUInt===pool.identity.asUInt && c.identity.asUInt===pool.identity.asUInt &&
        c.headValid===(c.committedCount=/=0.U) && (!c.headValid || BinPagePoolRules.pageInBounds(c.head,pool,pageBytes)) && poolValid
      when(stop){phase:=State.closing}.elsewhen(!valid){fail(1.U,1);phase:=State.closing}
        .elsewhen(!prefix){fail(Mux(c.status===PrimitiveBinStatus.memoryFault.U,3.U,
          Mux(c.status===PrimitiveBinStatus.cancelled.U,4.U,1.U)),1);phase:=State.closing}
        .otherwise{layerOffered:=true.B;phase:=State.bind}
    }
    is(State.bind){/* An offered layer binding remains irrevocable during stop. */}
    is(State.scan){when(stop || layerDone){phase:=State.closing}
      .elsewhen(readerDone && scanDrained){endOffered:=true.B;phase:=State.endLayer}}
    is(State.endLayer){when(stop && !endOffered){phase:=State.closing}}
    is(State.waitImage){when(stop){phase:=State.closing}
      .elsewhen(layerDone && !io.layerBusy && scanDrained){
        completedLayers:=completedLayers+1.U
        when(layer+&1.U===descriptor.layers){phase:=State.finish}.otherwise{
          layer:=layer+1.U;layerSelected:=0.U;layerStarted:=false.B;layerDone:=false.B
          readerStarted:=false.B;readerDone:=false.B;layerOffered:=true.B;phase:=State.bind
        }
      }}
    is(State.closing){when(!layerOffered && !endOffered && scanDrained &&
      (!layerStarted || (layerDone && !io.layerBusy))){phase:=State.finish}}
    is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.arena.identity;result.tile:=descriptor.chain.tile
      result.status:=Mux(status=/=0.U,status,Mux(cancelled || io.cancel,4.U,descriptor.chain.status))
      result.faultStage:=Mux(status===0.U && !cancelled && descriptor.chain.status=/=0.U,1.U,stage)
      result.faultLayer:=faultLayer;result.completedLayers:=completedLayers;result.chain:=descriptor.chain
      result.pages:=pageResult;result.image:=imageResult;result.scanned:=scanned;result.selected:=selected
      result.pageReadLines:=pageLines;result.recordReadLines:=recordLines;phase:=State.complete}
    is(State.complete){when(io.completion.fire){phase:=State.idle}}
  }
  when(io.completion.valid){assert(!layerOffered && !endOffered && !reader.io.busy && !record.io.busy &&
    (!layerStarted || (layerDone && !io.layerBusy)) && scanDrained,"layered replay completed before all ownership drained")}
}
