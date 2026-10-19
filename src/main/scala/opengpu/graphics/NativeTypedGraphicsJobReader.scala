package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class NativeTypedGraphicsCompletion(commandIdWidth: Int) extends Bundle {
  val commandId=UInt(commandIdWidth.W);val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  /** 0 cancellation/success,1 encoding/bounds,2 descriptor memory,3 child. */
  val faultStage=UInt(2.W);val faultAddress=UInt(32.W);val faultDraw=UInt(32.W)
  val descriptorReadBytes=UInt(64.W);val drawReadBytes=UInt(64.W)
  val issuedDraws=UInt(32.W);val completedDraws=UInt(32.W)
  val image=new TypedTileImageCompletion
}

/** Bounded native GTJ1 submission. Four independently tagged line reads fill
  * private explicit-layout snapshots; faulted bytes never become child input.
  * Header/pass reads finish before begin. Draw records execute in order and
  * keep one resident image pass until final acknowledged publication.
  *
  * All offered memory requests and begin controls are irrevocable. Cancellation
  * also retains offered draw/end controls until accepted, then cancels the
  * child. A child pass completion explicitly closes any remaining private
  * draw/end offer. Completion owns descriptor reads and the complete child
  * lifetime. The native host must pin/publish descriptor/resource allocations
  * and preserve the VM context until this completion is consumed.
  */
class NativeTypedGraphicsJobReader(config: GpuConfig=GpuConfig(),commandIdWidth: Int=8,
    p: VertexShaderStageConfig=VertexShaderStageConfig(),f: FragmentShaderConfig=FragmentShaderConfig(),
    storage: TypedTileColorConfig=TypedTileColorConfig()) extends Module {
  require(config.xLen==32 && commandIdWidth>0 && commandIdWidth<=12 && f.colors==4)
  import NativeTypedGraphicsLayout._
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(commandIdWidth)))
    val begin=Decoupled(new TypedTileImageDescriptor);val draw=Decoupled(new TypedTileRenderDraw(p,f));val end=Decoupled(Bool())
    val drawCompletion=Flipped(Decoupled(new TypedTileRenderDrawCompletion))
    val passCompletion=Flipped(Decoupled(new TypedTileImageCompletion))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,4))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val cancel=Input(Bool());val cancelCore=Output(Bool());val busy=Output(Bool())
    val completion=Decoupled(new NativeTypedGraphicsCompletion(commandIdWidth))
  })
  private object Phase extends ChiselEnum {
    val idle,fetchJob,decodeJob,fetchPass,decodePass,offerBegin,fetchDraw,decodeDraw,offerDraw,waitDraw,offerEnd,waitPass,drain,finish,done=Value
  }
  private val phase=RegInit(Phase.idle);private val binding=Reg(new TileJobDescriptorRequest(commandIdWidth))
  private val job=Reg(new NativeTypedGraphicsJob);private val pass=Reg(new TypedTileImageDescriptor)
  private val draw=Reg(new TypedTileRenderDraw(vertex,fragment));private val result=Reg(new NativeTypedGraphicsCompletion(commandIdWidth))
  private val image=Reg(new TypedTileImageCompletion);private val status=RegInit(0.U(3.W))
  private val faultStage=RegInit(0.U(2.W));private val faultAddress=RegInit(0.U(32.W));private val faultDraw=RegInit(0.U(32.W))
  private val index=RegInit(0.U(32.W));private val issuedDraws=RegInit(0.U(32.W));private val completedDraws=RegInit(0.U(32.W))
  private val arrayEnd=Reg(UInt(33.W))
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
  private def nextDraw(): Unit={draw:=0.U.asTypeOf(draw);fetch(Phase.fetchDraw,(job.drawBase+index*job.drawStride)(31,0),NativeTypedGraphicsLayout.drawBytes/64)}
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
      .elsewhen(r.status=/=0.U){fail(r.status,3.U,r.source.faultAddress)}
  }
  when(io.passCompletion.fire){
    image:=io.passCompletion.bits;passDone:=true.B;drawOffered:=false.B;endOffered:=false.B
    when(io.passCompletion.bits.identity.asUInt=/=job.identity.asUInt ||
      (io.passCompletion.bits.status===0.U && (!(endAccepted || io.end.fire) || completedDraws=/=job.drawCount))){fail(7.U,3.U)}
      .elsewhen(io.passCompletion.bits.status=/=0.U){fail(io.passCompletion.bits.status,3.U,io.passCompletion.bits.faultAddress)}
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
  private val jobFieldsMapped=jobFields(job);private val passFieldsMapped=passFields(pass);private val drawFieldsMapped=drawFields(draw)
  check(job,jobFieldsMapped,jobWords);check(pass,passFieldsMapped,passWords);check(draw,drawFieldsMapped,drawWords)
  private val badJob=receive(jobFieldsMapped,jobWords,jobMagic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchJob)
  private val badPass=receive(passFieldsMapped,passWords,passMagic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchPass)
  private val badDraw=receive(drawFieldsMapped,drawWords,drawMagic,replyLine,io.memoryResponse.bits.readData,writeReply && phase===Phase.fetchDraw)
  when(io.memoryResponse.fire){
    live(responseTag):=false.B
    when(io.memoryResponse.bits.fault){fail(3.U,2.U,fetchBase+(replyLine<<6))}
      .elsewhen(!stop && Mux(phase===Phase.fetchJob,badJob,Mux(phase===Phase.fetchPass,badPass,badDraw))){fail(1.U,1.U,fetchBase+(replyLine<<6))}
  }
  switch(phase){
    is(Phase.idle){when(io.submit.fire){
      binding:=io.submit.bits;job:=0.U.asTypeOf(job);pass:=0.U.asTypeOf(pass);draw:=0.U.asTypeOf(draw)
      image:=0.U.asTypeOf(image);status:=0.U;faultStage:=0.U;faultAddress:=0.U;faultDraw:=0.U;index:=0.U
      issuedDraws:=0.U;completedDraws:=0.U;descriptorBytes:=0.U;drawBytesRead:=0.U
      begun:=false.B;passDone:=false.B;drawPending:=false.B;endAccepted:=false.B;beginOffered:=false.B;drawOffered:=false.B;endOffered:=false.B
      val end=io.submit.bits.base+&NativeTypedGraphicsLayout.jobBytes.U
      when(!io.submit.bits.envelopeValid || io.submit.bits.base(5,0)=/=0.U || io.submit.bits.limit>(BigInt(1)<<32).U || end>io.submit.bits.limit){
        status:=1.U;faultStage:=1.U;faultAddress:=io.submit.bits.base;phase:=Phase.finish
      }.otherwise{fetch(Phase.fetchJob,io.submit.bits.base,NativeTypedGraphicsLayout.jobBytes/64)}
    }}
    is(Phase.fetchJob){when(stop){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodeJob}}
    is(Phase.decodeJob){
      val passEnd=job.passBase+&NativeTypedGraphicsLayout.passBytes.U
      val drawEnd=job.drawBase.pad(96)+(job.drawCount-1.U)*job.drawStride+NativeTypedGraphicsLayout.drawBytes.U
      val badPassBounds=job.passBase(5,0)=/=0.U || job.passLimit>(BigInt(1)<<32).U || passEnd>job.passLimit
      val badDrawBounds=job.drawCount=/=0.U && (job.drawBase(5,0)=/=0.U || job.drawStride(5,0)=/=0.U ||
        job.drawStride<NativeTypedGraphicsLayout.drawBytes.U || job.drawLimit>(BigInt(1)<<32).U || drawEnd>job.drawLimit)
      when(stop){phase:=Phase.drain}
        .elsewhen(badPassBounds || badDrawBounds){fail(1.U,1.U,Mux(badPassBounds,job.passBase,job.drawBase));phase:=Phase.drain}
        .otherwise{arrayEnd:=Mux(job.drawCount===0.U,job.drawBase.pad(33),drawEnd(32,0))
          pass:=0.U.asTypeOf(pass);fetch(Phase.fetchPass,job.passBase,NativeTypedGraphicsLayout.passBytes/64)}
    }
    is(Phase.fetchPass){when(stop){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodePass}}
    is(Phase.decodePass){
      when(stop){phase:=Phase.drain}
        .elsewhen(pass.pixels.colors.identity.asUInt=/=job.identity.asUInt || TypedTileImageRules.invalid(pass,storage)){
          fail(1.U,1.U,job.passBase);phase:=Phase.drain
        }.otherwise{beginOffered:=true.B;phase:=Phase.offerBegin}
    }
    is(Phase.offerBegin){when(io.begin.fire){
      when(stop){phase:=Phase.drain}.elsewhen(job.drawCount===0.U){endOffered:=true.B;phase:=Phase.offerEnd}.otherwise{nextDraw()}
    }}
    is(Phase.fetchDraw){when(stop || passFinished){phase:=Phase.drain}.elsewhen(issued===lineCount && drained){phase:=Phase.decodeDraw}}
    is(Phase.decodeDraw){
      val input=draw.shaders.vertex.vertex.geometry.vertex.input
      val v=draw.shaders.vertex.vertex.geometry.vertex;val fragment=draw.shaders.fragment
      val stride=(config.lanes*config.warps*4).U
      val vertexEnd=v.scratch.base.pad(48)+(7.U+&(v.attributeSlots<<2)+&(v.varyingSlots<<2)+&Mux(v.version===2.U,1.U,0.U))*stride
      val fragmentEnd=fragment.scratch.base.pad(48)+(8.U+&(fragment.varyingSlots<<2)+&(fragment.colorSlots<<2)+&Mux(fragment.version===2.U,2.U,0.U))*stride
      def aliases(base: UInt,end: UInt): Bool=
        (base<binding.base+&NativeTypedGraphicsLayout.jobBytes.U && end>binding.base) ||
        (base<job.passBase+&NativeTypedGraphicsLayout.passBytes.U && end>job.passBase) ||
        (job.drawCount=/=0.U && base<arrayEnd && end>job.drawBase)
      val immutable= !aliases(v.scratch.base,vertexEnd) && !aliases(fragment.scratch.base,fragmentEnd)
      val compatible=fit(draw,p,f) && immutable && input.identity.asUInt===job.identity.asUInt && fragment.identity.asUInt===job.identity.asUInt
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
            .otherwise{index:=index+1.U;draw:=0.U.asTypeOf(draw)
              fetch(Phase.fetchDraw,(job.drawBase+(index+&1.U)*job.drawStride)(31,0),NativeTypedGraphicsLayout.drawBytes/64)}
        }
    }
    is(Phase.offerEnd){when(passFinished){phase:=Phase.drain}.elsewhen(io.end.fire){phase:=Phase.waitPass}}
    is(Phase.waitPass){when(stop || passFinished){phase:=Phase.drain}}
    is(Phase.drain){when(drained && !beginOffered && !drawOffered && !endOffered && (!begun || passDone) && !drawPending){phase:=Phase.finish}}
    is(Phase.finish){
      assert(drained && (!begun || passDone) && !drawPending,"native completion must retain all descriptor and image ownership")
      result.commandId:=binding.commandId;result.identity:=job.identity;result.status:=status;result.faultStage:=faultStage
      result.faultAddress:=faultAddress;result.faultDraw:=faultDraw;result.descriptorReadBytes:=descriptorBytes;result.drawReadBytes:=drawBytesRead
      result.issuedDraws:=issuedDraws;result.completedDraws:=completedDraws;result.image:=image
      when(status===0.U){assert(begun && passDone && completedDraws===job.drawCount)}
      phase:=Phase.done
    }
    is(Phase.done){when(io.completion.fire){phase:=Phase.idle}}
  }
}
