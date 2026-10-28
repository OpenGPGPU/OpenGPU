package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class NativeBinnedGraphicsCompletion(commandIdWidth: Int,p: VertexShaderStageConfig) extends Bundle {
  val commandId=UInt(commandIdWidth.W);val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 0 cancellation/success,1 encoding/bounds,2 descriptor memory,3 child. */
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W);val faultDraw=UInt(32.W)
  val descriptorReadBytes=UInt(64.W);val drawReadBytes=UInt(64.W)
  val issuedDraws=UInt(32.W);val completedDraws=UInt(32.W)
  val frame=new TypedVertexBinImageCompletion(p)
}

/** Four-tag GPU-memory GNJ1/GNF1/GND1 snapshot reader for the combined frame
  * owner. Records and all descriptor replies remain owned until drain. Offered
  * begin/draw/end controls survive cancellation, then the child is cancelled.
  * Writable virtual bin/fragment/vertex staging cannot alias descriptor storage.
  * GNC2 additionally separates image allocations from writable bin/scratch roles
  * and prevents image writes to descriptor/state storage. These are VA checks;
  * translated physical aliases still require allocation/lifetime provenance.
  */
class NativeBinnedGraphicsJobReader(config: GpuConfig=GpuConfig(),commandIdWidth: Int=8,
    p: VertexShaderStageConfig=VertexShaderStageConfig(),engines: Int=2,virtualImageViews: Boolean=false,allowWideTileCount: Boolean=false) extends Module {
  require(config.xLen==32 && commandIdWidth>0 && commandIdWidth<=12 && engines>=1 && engines<=4)
  import NativeBinnedGraphicsLayout._
  import NativeTypedGraphicsLayout.{check,receive,adapt}
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(commandIdWidth)))
    val begin=Decoupled(new TypedVertexBinImageDescriptor);val draw=Decoupled(new VertexBinDraw(p));val end=Decoupled(Bool())
    val drawCompletion=Flipped(Decoupled(new VertexBinDrawCompletion))
    val passCompletion=Flipped(Decoupled(new TypedVertexBinImageCompletion(p)))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val cancelCore=Output(Bool());val busy=Output(Bool())
    val completion=Decoupled(new NativeBinnedGraphicsCompletion(commandIdWidth,p))
  })
  private object Phase extends ChiselEnum {
    val idle,fetchJob,decodeJob,fetchPass,decodePass,offerBegin,fetchDraw,decodeDraw,offerDraw,waitDraw,offerEnd,waitPass,drain,finish,done=Value
  }
  private val phase=RegInit(Phase.idle);private val binding=Reg(new TileJobDescriptorRequest(commandIdWidth))
  private val jobRecord=Reg(new NativeBinnedGraphicsJobRecord);private val frameRecord=Reg(new NativeBinnedGraphicsFrameRecord)
  private val drawRecord=Reg(new NativeBinnedGraphicsDrawRecord)
  private val job=jobRecord.job;private val pass=frameRecord.frame;private val draw=drawRecord.draw
  private val result=Reg(new NativeBinnedGraphicsCompletion(commandIdWidth,p))
  private val image=Reg(new TypedVertexBinImageCompletion(p));private val status=RegInit(0.U(3.W))
  private val faultStage=RegInit(0.U(2.W));private val faultAddress=RegInit(0.U(32.W));private val faultDraw=RegInit(0.U(32.W))
  private val index=RegInit(0.U(32.W));private val issuedDraws=RegInit(0.U(32.W));private val completedDraws=RegInit(0.U(32.W))
  private val arrayEnd=Reg(UInt(33.W))
  private def aliasesDescriptors(base: UInt,end: UInt): Bool=base<end && (
    (base<binding.base+&jobBytes.U && end>binding.base) ||
    (base<job.frameBase+&frameBytes.U && end>job.frameBase) ||
    (job.drawCount=/=0.U && base<arrayEnd && end>job.drawBase))
  private def overlap(a: UInt,b: UInt,c: UInt,d: UInt): Bool=a<b && c<d && a<d && c<b
  private def imageRoles: Seq[(Bool,UInt,UInt,Bool)]={
    val d=pass.image;val enabled=TypedTileImageRules.enabled(d.pixels);val loads=TypedTileImageRules.loads(d.pixels)
    d.views.zipWithIndex.map{case(v,i)=>(enabled(i) && (v.store || loads(i)===AttachmentLoadOp.load.U),v.allocationBase,v.allocationLimit,v.store)}++
      d.resolves.zipWithIndex.map{case(r,i)=>(r.enable && enabled(i),r.destination.allocationBase,r.destination.allocationLimit,true.B)}
  }
  private def aliasesImages(base: UInt,end: UInt): Bool=imageRoles.map{case(active,a,b,_)=>active && overlap(base,end,a,b)}.reduce(_ || _)
  private val descriptorBytes=RegInit(0.U(64.W));private val drawBytesRead=RegInit(0.U(64.W))
  private val beginOffered=RegInit(false.B);private val drawOffered=RegInit(false.B);private val endOffered=RegInit(false.B)
  private val begun=RegInit(false.B);private val drawPending=RegInit(false.B);private val passDone=RegInit(false.B);private val endAccepted=RegInit(false.B)
  private val fetchBase=Reg(UInt(32.W));private val lineCount=Reg(UInt(6.W));private val issued=RegInit(0.U(6.W))
  private val offered=RegInit(false.B);private val request=Reg(new ComputeMemoryRequest(config,64,4))
  private val requestDraw=Reg(Bool())
  private val live=RegInit(VecInit(Seq.fill(4)(false.B)));private val lineForTag=Reg(Vec(4,UInt(6.W)))
  private val active=phase=/=Phase.idle && phase=/=Phase.done
  private val childFault=(io.drawCompletion.valid && io.drawCompletion.bits.status=/=0.U) ||
    (io.passCompletion.valid && io.passCompletion.bits.status=/=0.U)
  private val stop=status=/=0.U || io.cancel || childFault
  private val fetching=phase===Phase.fetchJob || phase===Phase.fetchPass || phase===Phase.fetchDraw
  private val drained= !offered && !live.asUInt.orR
  private val passFinished=passDone || io.passCompletion.fire
  private def fail(code: UInt,stage: UInt,address: UInt=0.U): Unit=when(status===0.U && !io.cancel){
    status:=code;faultStage:=stage;faultAddress:=address;faultDraw:=index
  }
  private def fetch(next: Phase.Type,base: UInt,lines: Int): Unit={
    assert(drained,"native descriptor fetch must not reuse live line tags")
    fetchBase:=base;lineCount:=lines.U;issued:=0.U;phase:=next
  }
  private def nextDraw(): Unit={drawRecord:=0.U.asTypeOf(drawRecord);fetch(Phase.fetchDraw,(job.drawBase+index*job.drawStride)(31,0),NativeBinnedGraphicsLayout.drawBytes/64)}
  io.submit.ready:=phase===Phase.idle && !io.cancel;io.busy:=phase=/=Phase.idle
  io.begin.valid:=beginOffered;io.begin.bits:=pass
  io.draw.valid:=drawOffered;NativeTypedGraphicsLayout.adapt(draw,io.draw.bits)
  io.end.valid:=endOffered;io.end.bits:=true.B
  io.cancelCore:=begun && !passDone && stop && !beginOffered && !drawOffered && !endOffered
  io.drawCompletion.ready:=begun && !passDone
  io.passCompletion.ready:=begun && !passDone
  io.completion.valid:=phase===Phase.done;io.completion.bits:=result
  when(active && io.cancel && status===0.U){status:=4.U;faultStage:=0.U;faultAddress:=0.U;faultDraw:=index}

  when(io.begin.fire){beginOffered:=false.B;begun:=true.B}
  when(io.draw.fire){drawOffered:=false.B;drawPending:=true.B;issuedDraws:=issuedDraws+1.U}
  when(io.end.fire){endOffered:=false.B;endAccepted:=true.B}
  when(io.drawCompletion.fire){
    assert(drawPending,"native job received an unowned draw completion")
    drawPending:=false.B;completedDraws:=completedDraws+1.U
    val r=io.drawCompletion.bits
    when(r.identity.asUInt=/=job.identity.asUInt || r.ordinal=/=index){fail(7.U,3.U)}
      .elsewhen(r.status=/=0.U){fail(r.status,3.U,r.source.source.source.source.faultAddress)}
  }
  when(io.passCompletion.fire){
    image:=io.passCompletion.bits;passDone:=true.B;drawOffered:=false.B;endOffered:=false.B
    when(io.passCompletion.bits.identity.asUInt=/=job.identity.asUInt ||
      (io.passCompletion.bits.status===0.U && (!(endAccepted || io.end.fire) || completedDraws=/=job.drawCount))){fail(7.U,3.U)}
      .elsewhen(io.passCompletion.bits.status=/=0.U){fail(io.passCompletion.bits.status,3.U,Mux(io.passCompletion.bits.source.sourceFaultValid,io.passCompletion.bits.source.sourceFault.source.source.source.faultAddress,0.U))}
  }

  io.memoryRequest.valid:=offered;io.memoryRequest.bits:=request
  private val responseTag=io.memoryResponse.bits.transactionId
  private val responseOwned=live(responseTag) || (io.memoryRequest.fire && request.transactionId===responseTag)
  io.memoryResponse.ready:=responseOwned
  when(io.memoryResponse.valid){assert(responseOwned,"native job received an unowned descriptor reply")}
  when(fetching && !stop && !offered && issued<lineCount && !live.asUInt.andR){
    val tag=PriorityEncoder(~live.asUInt)
    request:=0.U.asTypeOf(request);request.address:=fetchBase+(issued<<6);request.sizeLog2:=6.U
    request.cachePolicy:=CachePolicy.uncached;request.transactionId:=tag
    requestDraw:=phase===Phase.fetchDraw
    lineForTag(tag):=issued;issued:=issued+1.U;offered:=true.B
  }
  when(io.memoryRequest.fire){
    offered:=false.B;live(request.transactionId):=true.B
    when(requestDraw){drawBytesRead:=drawBytesRead+64.U}.otherwise{descriptorBytes:=descriptorBytes+64.U}
  }
  private val replyLine=lineForTag(responseTag)
  private val writeReply=io.memoryResponse.fire && !io.memoryResponse.bits.fault && !stop
  private val jobFieldsMapped=jobFields(jobRecord);private val passFieldsMapped=frameFields(frameRecord);private val drawFieldsMapped=drawFields(drawRecord)
  check(jobRecord,jobFieldsMapped,jobWords);check(frameRecord,passFieldsMapped,frameWords);check(drawRecord,drawFieldsMapped,drawWords)
  private val badJob=receive(jobFieldsMapped,jobWords,jobMagic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchJob)
  private val badPass=receive(passFieldsMapped,frameWords,frameMagic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchPass)
  private val badDraw=receive(drawFieldsMapped,drawWords,drawMagic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchDraw)
  when(io.memoryResponse.fire){
    live(responseTag):=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,2.U,fetchBase+(replyLine<<6))}
      .elsewhen(!stop && Mux(phase===Phase.fetchJob,badJob,Mux(phase===Phase.fetchPass,badPass,badDraw))){fail(1.U,1.U,fetchBase+(replyLine<<6))}
  }
  switch(phase){
    is(Phase.idle){when(io.submit.fire){
      binding:=io.submit.bits;jobRecord:=0.U.asTypeOf(jobRecord);frameRecord:=0.U.asTypeOf(frameRecord);drawRecord:=0.U.asTypeOf(drawRecord)
      image:=0.U.asTypeOf(image);status:=0.U;faultStage:=0.U;faultAddress:=0.U;faultDraw:=0.U;index:=0.U
      issuedDraws:=0.U;completedDraws:=0.U;descriptorBytes:=0.U;drawBytesRead:=0.U
      begun:=false.B;passDone:=false.B;drawPending:=false.B;endAccepted:=false.B;beginOffered:=false.B;drawOffered:=false.B;endOffered:=false.B
      val end=io.submit.bits.base+&NativeBinnedGraphicsLayout.jobBytes.U
      when(!io.submit.bits.envelopeValid || io.submit.bits.base(5,0)=/=0.U || io.submit.bits.limit>(BigInt(1)<<32).U || end>io.submit.bits.limit){
        status:=1.U;faultStage:=1.U;faultAddress:=io.submit.bits.base;phase:=Phase.finish
      }.otherwise{fetch(Phase.fetchJob,io.submit.bits.base,NativeBinnedGraphicsLayout.jobBytes/64)}
    }}
    is(Phase.fetchJob){when(stop){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodeJob}}
    is(Phase.decodeJob){
      val passEnd=job.frameBase+&NativeBinnedGraphicsLayout.frameBytes.U
      val drawEnd=job.drawBase.pad(96)+(job.drawCount-1.U)*job.drawStride+NativeBinnedGraphicsLayout.drawBytes.U
      val badPassBounds=job.frameBase(5,0)=/=0.U || job.frameLimit>(BigInt(1)<<32).U || passEnd>job.frameLimit
      val badDrawBounds=job.drawCount=/=0.U && (job.drawBase(5,0)=/=0.U || job.drawStride(5,0)=/=0.U ||
        job.drawStride<NativeBinnedGraphicsLayout.drawBytes.U || job.drawLimit>(BigInt(1)<<32).U || drawEnd>job.drawLimit)
      when(stop){phase:=Phase.drain}
        .elsewhen(!headerLegal(jobRecord.header) || badPassBounds || badDrawBounds){
          fail(1.U,1.U,Mux(!headerLegal(jobRecord.header),binding.base,Mux(badPassBounds,job.frameBase,job.drawBase)));phase:=Phase.drain}
        .otherwise{arrayEnd:=Mux(job.drawCount===0.U,job.drawBase.pad(33),drawEnd(32,0))
          frameRecord:=0.U.asTypeOf(frameRecord);fetch(Phase.fetchPass,job.frameBase,NativeBinnedGraphicsLayout.frameBytes/64)}
    }
    is(Phase.fetchPass){when(stop){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodePass}}
    is(Phase.decodePass){
      val bins=pass.bins
      val writable=Seq((bins.producer.arena.base,bins.producer.arena.limit),
        (bins.bins.pool.base,bins.bins.pool.limit),(bins.bins.directoryBase,bins.bins.directoryLimit)) ++
        pass.scratch.take(engines).map(s=>(s.base,s.limit))
      val immutable= !writable.map{case(base,end)=>aliasesDescriptors(base,end)}.reduce(_ || _)
      val imageAliases=if(virtualImageViews)imageRoles.map{case(active,base,end,write)=>active && (
        writable.map{case(a,b)=>overlap(base,end,a,b)}.reduce(_ || _) ||
        (write && (aliasesDescriptors(base,end) || overlap(base,end,pass.states.base,pass.states.limit))))}.reduce(_ || _)else false.B
      // GNJ1/GNF1 retain their record sizes and offsets. Frame payload v2
      // widens only the existing tile-count word; v1 keeps canonical zero
      // high bits and is accepted by every previous runtime profile.
      val frameVersion=(pass.version===1.U && !bins.bins.tileCount(31,16).orR) ||
        (allowWideTileCount.B && pass.version===2.U && bins.bins.tileCount>0.U && bins.bins.tileCount<=65536.U)
      val compatible=headerLegal(frameRecord.header) && frameVersion &&
        pass.image.pixels.colors.identity.asUInt===job.identity.asUInt && pass.states.identity.asUInt===job.identity.asUInt &&
        bins.producer.arena.identity.asUInt===job.identity.asUInt && bins.bins.pool.identity.asUInt===job.identity.asUInt && immutable && !imageAliases
      when(stop){phase:=Phase.drain}
        .elsewhen(!compatible){
          val countWord=passFieldsMapped.find(_.name=="frame.bins.bins.tileCount").get.word
          val legacyCountPadding=pass.version===1.U && bins.bins.tileCount(31,16).orR
          fail(1.U,1.U,Mux(legacyCountPadding,job.frameBase+((countWord/16)*64).U,job.frameBase));phase:=Phase.drain}
        .otherwise{beginOffered:=true.B;phase:=Phase.offerBegin}
    }
    is(Phase.offerBegin){when(io.begin.fire){
      when(stop){phase:=Phase.drain}.elsewhen(job.drawCount===0.U){endOffered:=true.B;phase:=Phase.offerEnd}.otherwise{nextDraw()}
    }}
    is(Phase.fetchDraw){when(stop || passFinished){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodeDraw}}
    is(Phase.decodeDraw){
      val v=draw.vertex.geometry.vertex
      val stride=(config.lanes*config.warps*4).U
      val vertexEnd=v.scratch.base.pad(48)+(7.U+&(v.attributeSlots<<2)+&(v.varyingSlots<<2)+&Mux(v.version===2.U,1.U,0.U))*stride
      val compatible=headerLegal(drawRecord.header) && fit(draw,p) && !aliasesDescriptors(v.scratch.base,vertexEnd) &&
        (if(virtualImageViews) !aliasesImages(v.scratch.base,vertexEnd)else true.B) &&
        v.input.identity.asUInt===job.identity.asUInt
      when(stop || passFinished){phase:=Phase.drain}
        .elsewhen(!compatible){fail(1.U,1.U,fetchBase);phase:=Phase.drain}
        .otherwise{drawOffered:=true.B;phase:=Phase.offerDraw}
    }
    is(Phase.offerDraw){
      when(passFinished){phase:=Phase.drain}.elsewhen(io.draw.fire){phase:=Mux(stop,Phase.drain,Phase.waitDraw)}
    }
    is(Phase.waitDraw){
      when(stop || passFinished){phase:=Phase.drain}
        .elsewhen(io.drawCompletion.fire){
          when(io.drawCompletion.bits.status=/=0.U || io.drawCompletion.bits.ordinal=/=index || io.drawCompletion.bits.identity.asUInt=/=job.identity.asUInt){phase:=Phase.drain}
            .elsewhen(index+&1.U===job.drawCount){endOffered:=true.B;phase:=Phase.offerEnd}
            .otherwise{index:=index+1.U;drawRecord:=0.U.asTypeOf(drawRecord)
              fetch(Phase.fetchDraw,(job.drawBase+(index+&1.U)*job.drawStride)(31,0),NativeBinnedGraphicsLayout.drawBytes/64)}
        }
    }
    is(Phase.offerEnd){when(passFinished){phase:=Phase.drain}.elsewhen(io.end.fire){phase:=Phase.waitPass}}
    is(Phase.waitPass){when(stop || passFinished){phase:=Phase.drain}}
    is(Phase.drain){when(drained && !beginOffered && !drawOffered && !endOffered && (!begun || passDone) && !drawPending){phase:=Phase.finish}}
    is(Phase.finish){
      assert(drained && (!begun || passDone) && !drawPending,"native completion must retain all descriptor and image ownership")
      result.commandId:=binding.commandId;result.identity:=job.identity;result.status:=status;result.faultStage:=faultStage
      result.faultAddress:=faultAddress;result.faultDraw:=faultDraw;result.descriptorReadBytes:=descriptorBytes;result.drawReadBytes:=drawBytesRead
      result.issuedDraws:=issuedDraws;result.completedDraws:=completedDraws;result.frame:=image
      when(status===0.U){assert(begun && passDone && completedDraws===job.drawCount)}
      phase:=Phase.done
    }
    is(Phase.done){when(io.completion.fire){phase:=Phase.idle}}
  }
}
