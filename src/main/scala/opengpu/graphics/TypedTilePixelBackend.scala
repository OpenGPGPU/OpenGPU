package opengpu.graphics

import chisel3._
import chisel3.util._

/** Late per-sample depth/stencil followed by typed MRT color writes in the same
  * resident slot. No early depth/stencil side effects precede shader discard or
  * depth override. The backing-memory and final publication owner remains
  * external; color blending is supplied by the shared typed color service.
  */
class TypedTilePixelBackend(p: TypedTileColorConfig=TypedTileColorConfig(),useBlackBoxes: Boolean=false) extends Module {
  val io=IO(new Bundle{
    val begin=Flipped(Decoupled(new TypedTilePixelDescriptor));val end=Flipped(Decoupled(Bool()))
    val in=Flipped(Decoupled(new TypedTilePixelQuad));val out=Decoupled(new TypedTilePixelQuadCompletion)
    val completion=Decoupled(new TypedTilePixelCompletion);val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
    val maintenance=Input(Bool());val memory=Flipped(Decoupled(new TypedTileColorMemoryRequest(p)));val reply=Decoupled(new TypedTileColorMemoryReply)
    val refill=Decoupled(new TypedTileColorRefill(p));val refillReply=Flipped(Decoupled(new TypedTileColorRefillReply))
  })
  private object State extends ChiselEnum {
    val idle,validate,bind,active,checkQuad,sample,stencilRead,stencilTest,depthRead,evaluate,writePrimary,writeSecondary,advance,
      readDone,fillRequest,fillDone,refillWait,waitMemory,sendColor,waitColor,quadDone,emit,externalReply,closing,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTilePixelDescriptor);private val input=Reg(new TypedTilePixelQuad)
  private val output=Reg(new TypedTilePixelQuadCompletion);private val result=Reg(new TypedTilePixelCompletion)
  private val colors=Module(new TypedTileColorStore(p,useBlackBoxes))
  private val beginOffered=RegInit(false.B);private val started=RegInit(false.B);private val colorDone=RegInit(false.B)
  private val colorOffered=RegInit(false.B);private val colorAccepted=RegInit(false.B);private val colorReturned=RegInit(false.B)
  private val quadOwned=RegInit(false.B);private val status=RegInit(0.U(3.W));private val quadStatus=RegInit(0.U(3.W));private val fault=RegInit(0.U(17.W))
  private val colorQuadResult=Reg(new TypedTileColorQuadCompletion);private val colorPassResult=Reg(new TypedTileColorCompletion)
  private val lane=RegInit(0.U(2.W));private val sample=RegInit(0.U(2.W));private val qualified=Reg(Vec(4,UInt(4.W)))
  private val primaryWord=Reg(UInt(64.W));private val secondaryWord=Reg(UInt(64.W));private val primaryLoaded=RegInit(false.B)
  private val stencilPassed=Reg(Bool());private val oldStencil=Reg(UInt(8.W));private val newStencil=Reg(UInt(8.W))
  private val depthPlan=Reg(Bool());private val primaryStencilPlan=Reg(Bool());private val secondaryStencilPlan=Reg(Bool())
  private val tested=RegInit(0.U(5.W));private val passing=RegInit(0.U(5.W));private val stencilRejected=RegInit(0.U(5.W));private val depthRejected=RegInit(0.U(5.W))
  private val depthUpdates=RegInit(0.U(5.W));private val stencilUpdates=RegInit(0.U(5.W));private val reads=RegInit(0.U(6.W));private val fills=RegInit(0.U(6.W));private val writes=RegInit(0.U(6.W))
  private val inputQuads=RegInit(0.U(99.W));private val totalWrites=RegInit(0.U(110.W));private val totalFills=RegInit(0.U(110.W))
  private val memoryOffered=RegInit(false.B);private val memoryPending=RegInit(false.B);private val memoryRequest=Reg(new TypedTileColorMemoryRequest(p))
  private val memoryKind=Reg(UInt(2.W));private val memoryUpdates=Reg(UInt(2.W));private val continuation=Reg(State())
  private val memoryData=Reg(UInt(64.W));private val memoryAvailable=Reg(Bool())
  private val readSecondary=Reg(Bool());private val afterRead=Reg(State());private val fetchedWord=Reg(UInt(64.W))
  private val refillOffered=RegInit(false.B);private val refillPending=RegInit(false.B);private val refillAddress=Reg(UInt(p.addressBits.W))
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel || colors.io.cancelSource
  private val stencilSecondary= !TypedDepthFormat.stencil(descriptor.depth.format)
  private val hasStencil=TypedDepthFormat.stencil(descriptor.depth.format) || descriptor.stencil.format===TypedDepthFormat.s8.U
  private val doStencil=input.state.stencilEnable && hasStencil
  private val doDepth=input.state.depthEnable && TypedDepthFormat.depth(descriptor.depth.format)
  private val face=Mux(input.color.quad.frontFacing,input.state.front,input.state.back)
  private val localX=(input.color.quad.lanes(lane).x.pad(33)-descriptor.colors.originX.pad(33)).asUInt
  private val localY=(input.color.quad.lanes(lane).y.pad(33)-descriptor.colors.originY.pad(33)).asUInt
  private val sampleIndex=(((localY<<descriptor.colors.tileLog2)+localX)<<descriptor.colors.sampleMode)+sample
  private def byteAddress(v: TypedDepthView): UInt=(v.offset+sampleIndex*TypedDepthFormat.bytes(v.format))(16,0)
  private val primaryAddress=byteAddress(descriptor.depth);private val secondaryAddress=byteAddress(descriptor.stencil)
  private val chosenView=Mux(readSecondary,descriptor.stencil,descriptor.depth)
  private val sourceDepth=TypedDepthFormat.encode(input.color.quad.lanes(lane).depth,descriptor.depth.format)
  private def fail(code: UInt,offset: UInt): Unit={when(status===0.U && !io.cancel){status:=code;fault:=offset;when(quadOwned){quadStatus:=code}}}
  private def initialWord(v: TypedDepthView): UInt={
    val z=TypedDepthFormat.encode(v.clearDepth,v.format);val s=v.clearStencil
    val record=MuxLookup(v.format,0.U(64.W))(Seq(
      TypedDepthFormat.d16.U->Fill(4,z(15,0)),TypedDepthFormat.d24.U->Fill(2,Cat(0.U(8.W),z(23,0))),
      TypedDepthFormat.d32f.U->Fill(2,z),TypedDepthFormat.d24s8.U->Fill(2,Cat(s,z(23,0))),
      TypedDepthFormat.d32fs8.U->Cat(0.U(24.W),s,z),TypedDepthFormat.s8.U->Fill(8,s)))
    Mux(v.loadOp===AttachmentLoadOp.clear.U,record,0.U)
  }
  private def requestMemory(write: Boolean,address: UInt,data: UInt,next: State.Type,kind: Int=0,updates: UInt=0.U,fill: Bool=false.B): Unit={
    assert(!memoryOffered && !memoryPending)
    memoryRequest.address:=address;memoryRequest.write:=write.B;memoryRequest.fill:=fill;memoryRequest.data:=data;memoryRequest.byteMask:=255.U
    memoryKind:=kind.U;memoryUpdates:=updates;memoryOffered:=true.B;continuation:=next;phase:=State.waitMemory
  }
  private def requestRead(secondary: Bool,next: State.Type): Unit={
    readSecondary:=secondary;afterRead:=next
    requestMemory(false,Mux(secondary,secondaryAddress,primaryAddress)>>3,0.U,State.readDone)
  }
  private def saveWord(value: UInt): Unit={
    when(readSecondary){secondaryWord:=value}.otherwise{primaryWord:=value;primaryLoaded:=true.B}
    phase:=afterRead
  }
  private def advanceSample(): Unit={
    when(sample===(1.U<<descriptor.colors.sampleMode)-1.U){
      when(lane===3.U){phase:=State.sendColor}.otherwise{lane:=lane+1.U;sample:=0.U;phase:=State.sample}
    }.otherwise{sample:=sample+1.U;phase:=State.sample}
  }
  private def formQuadResult(): Unit={
    val q=input.color.quad;output:=0.U.asTypeOf(output);output.identity:=q.identity;output.sequence:=q.sequence;output.primitiveId:=q.primitiveId
    output.instanceId:=q.instanceId;output.partIndex:=q.partIndex;output.status:=Mux(io.cancel && quadStatus===0.U,4.U,quadStatus);output.faultOffset:=fault
    output.testedSamples:=tested;output.passingSamples:=passing;output.stencilRejected:=stencilRejected;output.depthRejected:=depthRejected
    output.depthUpdates:=depthUpdates;output.stencilUpdates:=stencilUpdates;output.dsReads:=reads;output.dsFills:=fills;output.dsWrites:=writes
    output.colors:=colorQuadResult;phase:=State.emit
  }
  colors.io.begin.valid:=beginOffered;colors.io.begin.bits:=descriptor.colors
  when(colors.io.begin.fire){beginOffered:=false.B;started:=true.B}
  // Pending private offers must be accepted before cancellation reaches the child.
  colors.io.cancel:=started && (status=/=0.U || io.cancel) && !memoryOffered && !colorOffered
  colors.io.in.valid:=colorOffered;colors.io.in.bits.quad:=input.color.quad;colors.io.in.bits.samples:=qualified;colors.io.in.bits.blend:=input.color.blend
  when(colors.io.in.fire){colorOffered:=false.B;colorAccepted:=true.B}
  colors.io.out.ready:=colorAccepted && !colorReturned
  when(colors.io.out.fire){colorQuadResult:=colors.io.out.bits;colorReturned:=true.B
    when(colors.io.out.bits.status=/=0.U){fail(colors.io.out.bits.status,colors.io.out.bits.faultOffset)}
    when(!stop){phase:=State.quadDone}
  }
  colors.io.completion.ready:=started && !colorDone
  when(colors.io.completion.fire){colorDone:=true.B;colorPassResult:=colors.io.completion.bits
    when(colors.io.completion.bits.status=/=0.U){fail(colors.io.completion.bits.status,colors.io.completion.bits.faultOffset)}}
  private val external=phase===State.active && io.maintenance && !stop
  colors.io.maintenance:=memoryOffered || memoryPending || phase===State.externalReply || external
  colors.io.memory.valid:=memoryOffered || (external && io.memory.valid)
  colors.io.memory.bits:=Mux(memoryOffered,memoryRequest,io.memory.bits)
  io.memory.ready:=external && !memoryOffered && colors.io.memory.ready
  when(memoryOffered && colors.io.memory.fire){memoryOffered:=false.B;memoryPending:=true.B}
  io.reply.valid:=phase===State.externalReply && colors.io.reply.valid;io.reply.bits:=colors.io.reply.bits
  colors.io.reply.ready:=memoryPending || (phase===State.externalReply && io.reply.ready)
  when(memoryPending && colors.io.reply.fire){
    memoryPending:=false.B;memoryData:=colors.io.reply.bits.data;memoryAvailable:=colors.io.reply.bits.available
    when(colors.io.reply.bits.fault){fail(3.U,memoryRequest.address<<3)}.otherwise{
      when(memoryKind===0.U){reads:=reads+1.U}
      when(memoryKind===1.U){fills:=fills+1.U;totalFills:=totalFills+1.U}
      when(memoryKind===2.U){writes:=writes+1.U;totalWrites:=totalWrites+1.U
        when(memoryUpdates(0)){depthUpdates:=depthUpdates+1.U};when(memoryUpdates(1)){stencilUpdates:=stencilUpdates+1.U}}
    }
    when(!stop && !colors.io.reply.bits.fault){phase:=continuation}
  }
  assert(!(refillOffered && colors.io.refill.valid),"depth/color refill owners must be exclusive")
  io.refill.valid:=refillOffered || colors.io.refill.valid
  io.refill.bits.address:=Mux(refillOffered,refillAddress,colors.io.refill.bits.address)
  colors.io.refill.ready:= !refillOffered && !refillPending && io.refill.ready
  when(refillOffered && io.refill.fire){refillOffered:=false.B;refillPending:=true.B}
  io.refillReply.ready:=Mux(refillPending,true.B,colors.io.refillReply.ready)
  colors.io.refillReply.valid:=io.refillReply.valid && !refillPending;colors.io.refillReply.bits:=io.refillReply.bits
  when(refillPending && io.refillReply.fire){refillPending:=false.B
    when(io.refillReply.bits.fault){fail(3.U,refillAddress<<3)}.otherwise{fetchedWord:=io.refillReply.bits.data;when(!stop){phase:=State.fillRequest}}}
  private val selectedProducer=Mux(io.maintenance,io.memory.valid,io.in.valid)
  colors.io.end.valid:=phase===State.active && !selectedProducer && io.end.valid && !stop;colors.io.end.bits:=io.end.bits
  io.end.ready:=phase===State.active && !selectedProducer && colors.io.end.ready && !stop
  io.begin.ready:=phase===State.idle && !io.cancel;io.in.ready:=phase===State.active && !io.maintenance && !stop
  io.out.valid:=phase===State.emit;io.out.bits:=output;io.completion.valid:=phase===State.complete;io.completion.bits:=result
  io.busy:=phase=/=State.idle;io.cancelSource:=active && stop
  when(active && io.cancel && status===0.U){status:=4.U;when(quadOwned){quadStatus:=4.U}}
  private val controlsDrained= !beginOffered && !memoryOffered && !memoryPending && !refillOffered && !refillPending &&
    !colorOffered && (!colorAccepted || colorReturned)
  when(!stop || phase===State.idle || phase===State.emit || phase===State.externalReply || phase===State.closing || phase===State.finish || phase===State.complete){
    switch(phase){
      is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;fault:=0.U;started:=false.B;colorDone:=false.B;quadOwned:=false.B
        colorAccepted:=false.B;colorReturned:=false.B;inputQuads:=0.U;totalWrites:=0.U;totalFills:=0.U;colorPassResult:=0.U.asTypeOf(colorPassResult);phase:=State.validate}}
      is(State.validate){
        val d=descriptor;val vs=Seq(d.depth,d.stencil)
        val end=vs.map(v=>v.offset+&((1.U(18.W)<<((d.colors.tileLog2<<1)+&d.colors.sampleMode))*TypedDepthFormat.bytes(v.format)))
        val validViews=vs.zipWithIndex.map{case(v,i)=>v.format===0.U || (TypedDepthFormat.valid(v.format) && v.offset(5,0)===0.U &&
          v.loadOp<=2.U && end(i)<=d.colors.reservedBytes && !(TypedDepthFormat.depth(v.format) && v.loadOp===1.U && Fp32ViewportState.nan(v.clearDepth)))}.reduce(_ && _)
        val overlap=d.depth.format=/=0.U && d.stencil.format=/=0.U && d.depth.offset<end(1) && d.stencil.offset<end(0)
        when(d.version=/=1.U || TypedTileColorRules.invalid(d.colors,p) || !validViews || overlap ||
          (d.stencil.format=/=0.U && d.stencil.format=/=TypedDepthFormat.s8.U) || (TypedDepthFormat.stencil(d.depth.format) && d.stencil.format=/=0.U)){
          fail(1.U,0.U);phase:=State.closing
        }.otherwise{beginOffered:=true.B;phase:=State.bind}
      }
      is(State.bind){when(started && colors.io.in.ready){phase:=State.active}}
      is(State.active){
        when(io.in.fire){input:=io.in.bits;quadOwned:=true.B;quadStatus:=0.U;colorAccepted:=false.B;colorReturned:=false.B
          qualified.foreach(_:=0.U);tested:=0.U;passing:=0.U;stencilRejected:=0.U;depthRejected:=0.U;depthUpdates:=0.U;stencilUpdates:=0.U;reads:=0.U;fills:=0.U;writes:=0.U
          inputQuads:=inputQuads+1.U;colorQuadResult:=0.U.asTypeOf(colorQuadResult);colorQuadResult.identity:=io.in.bits.color.quad.identity;phase:=State.checkQuad}
        when(io.memory.fire){phase:=State.externalReply}
        when(io.end.fire){when(!io.end.bits){fail(5.U,0.U)};phase:=State.closing}
      }
      is(State.checkQuad){
        val numeric=TypedDepthFormat.depth(descriptor.depth.format) && input.state.depthEnable &&
          (0 until 4).map(i=>input.color.samples(i).orR && Fp32ViewportState.nan(input.color.quad.lanes(i).depth)).reduce(_ || _)
        when(input.state.version=/=1.U || TypedTileColorRules.invalidQuad(input.color,descriptor.colors)){fail(6.U,0.U);phase:=State.quadDone}
          .elsewhen(numeric){fail(7.U,0.U);phase:=State.quadDone}.otherwise{lane:=0.U;sample:=0.U;phase:=State.sample}
      }
      is(State.sample){when(input.color.samples(lane)(sample)){
        tested:=tested+1.U;primaryLoaded:=false.B;primaryWord:=0.U;secondaryWord:=0.U;oldStencil:=0.U;stencilPassed:=true.B
        phase:=Mux(doStencil,State.stencilRead,State.depthRead)
      }.otherwise{advanceSample()}}
      is(State.stencilRead){requestRead(stencilSecondary,State.stencilTest)}
      is(State.stencilTest){
        val record=Mux(stencilSecondary,secondaryWord>>(secondaryAddress(2,0)<<3),primaryWord>>(primaryAddress(2,0)<<3))
        val f=Mux(stencilSecondary,descriptor.stencil.format,descriptor.depth.format)
        val old=(record>>TypedDepthFormat.stencilShift(f))(7,0)
        val pass=TypedDepthFormat.compare(face.reference&face.readMask,old&face.readMask,face.function)
        oldStencil:=old;stencilPassed:=pass
        when(!pass){stencilRejected:=stencilRejected+1.U;phase:=State.evaluate}.otherwise{phase:=State.depthRead}
      }
      is(State.depthRead){when(!doDepth || primaryLoaded){phase:=State.evaluate}.otherwise{requestRead(false.B,State.evaluate)}}
      is(State.readDone){when(memoryAvailable){saveWord(memoryData)}.elsewhen(chosenView.loadOp===AttachmentLoadOp.load.U){
        refillAddress:=memoryRequest.address;refillOffered:=true.B;phase:=State.refillWait
      }.otherwise{fetchedWord:=initialWord(chosenView);phase:=State.fillRequest}}
      is(State.fillRequest){requestMemory(true,memoryRequest.address,fetchedWord,State.fillDone,kind=1,fill=chosenView.loadOp===AttachmentLoadOp.load.U)}
      is(State.fillDone){saveWord(fetchedWord)}
      is(State.evaluate){
        val old=((primaryWord>>(primaryAddress(2,0)<<3))(31,0))&TypedDepthFormat.depthMask(descriptor.depth.format)
        val floating=descriptor.depth.format===TypedDepthFormat.d32f.U || descriptor.depth.format===TypedDepthFormat.d32fs8.U
        val dp= !doDepth || !stencilPassed || TypedDepthFormat.compare(sourceDepth,old,input.state.depthFunction,floating)
        val pass=stencilPassed && dp
        val operation=Mux(!stencilPassed,face.fail,Mux(!dp,face.depthFail,face.depthPass))
        val next=TypedDepthFormat.operation(oldStencil,face.reference,operation)
        val masked=(next&face.writeMask)|(oldStencil& ~face.writeMask)
        val writeStencil=doStencil && masked=/=oldStencil
        newStencil:=masked;depthPlan:=doDepth && pass && input.state.depthWrite
        primaryStencilPlan:=writeStencil && !stencilSecondary;secondaryStencilPlan:=writeStencil && stencilSecondary
        when(pass){passing:=passing+1.U;qualified(lane):=qualified(lane)|(1.U(4.W)<<sample)}
        when(stencilPassed && !dp){depthRejected:=depthRejected+1.U}
        phase:=State.writePrimary
      }
      is(State.writePrimary){when(depthPlan || primaryStencilPlan){
        val shift=TypedDepthFormat.stencilShift(descriptor.depth.format)
        val mask=Mux(depthPlan,TypedDepthFormat.depthMask(descriptor.depth.format).pad(64),0.U)|Mux(primaryStencilPlan,255.U(64.W)<<shift,0.U)
        val value=Mux(depthPlan,sourceDepth.pad(64),0.U)|Mux(primaryStencilPlan,newStencil.pad(64)<<shift,0.U);val offset=primaryAddress(2,0)<<3
        val merged=(primaryWord& ~(mask<<offset))|((value&mask)<<offset)
        requestMemory(true,primaryAddress>>3,merged,State.writeSecondary,kind=2,updates=Cat(primaryStencilPlan,depthPlan))
      }.otherwise{phase:=State.writeSecondary}}
      is(State.writeSecondary){when(secondaryStencilPlan){
        val offset=secondaryAddress(2,0)<<3;val mask=255.U(64.W)<<offset
        val merged=(secondaryWord& ~mask)|(newStencil.pad(64)<<offset)
        requestMemory(true,secondaryAddress>>3,merged,State.advance,kind=2,updates=2.U)
      }.otherwise{advanceSample()}}
      is(State.advance){advanceSample()}
      is(State.quadDone){formQuadResult()}
      is(State.sendColor){colorOffered:=true.B;phase:=State.waitColor}
      is(State.externalReply){when(io.reply.fire){phase:=Mux(stop,State.closing,State.active)}}
      is(State.emit){when(io.out.fire){quadOwned:=false.B;phase:=Mux(stop,State.closing,State.active)}}
      is(State.closing){when(controlsDrained && !quadOwned && (!started || (colorDone && !colors.io.busy))){phase:=State.finish}}
      is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.colors.identity;result.status:=Mux(io.cancel && status===0.U,4.U,status)
        result.faultOffset:=fault;result.inputQuads:=inputQuads;result.dsWrites:=totalWrites;result.dsFills:=totalFills;result.colors:=colorPassResult;phase:=State.complete}
      is(State.complete){when(io.completion.fire){phase:=State.idle}}
    }
  }
  when(active && stop && phase=/=State.emit && phase=/=State.externalReply && phase=/=State.closing && phase=/=State.finish){
    when(controlsDrained){when(quadOwned){formQuadResult()}.otherwise{phase:=State.closing}}
  }
  when(io.completion.valid){assert(!colors.io.busy && controlsDrained,"pixel completion escaped resident/refill drain")}
}
