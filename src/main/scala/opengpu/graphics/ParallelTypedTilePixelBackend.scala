package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.ObservedCarrySaveAccumulator

/** Four ordered depth/stencil lanes followed by four typed color lanes.
  * One quad retains order until all its samples, word operations and colors
  * complete. The color service owns the shared SRAM and external refills.
  */
private[graphics] class ParallelTypedTilePixelBackend(p: TypedTileColorConfig,useBlackBoxes: Boolean,externalColors: Boolean=false) extends Module {
  require(p.parallelLanes && p.parallelDepth)
  val io=IO(new TypedTilePixelBackendIO(p))
  private object State extends ChiselEnum {
    val idle,prepareValidation,validate,bind,active,checkQuad,runDepth,depthDone,sendColor,waitColor,quadDone,emit,externalReply,closing,finish,complete=Value
  }
  private val phase=RegInit(State.idle);private val descriptor=Reg(new TypedTilePixelDescriptor);private val input=Reg(new TypedTilePixelQuad)
  private val output=Reg(new TypedTilePixelQuadCompletion);private val result=Reg(new TypedTilePixelCompletion)
  val color=if(externalColors)Some(IO(Flipped(new TypedTileColorStoreIO(p))))else None
  private val colors=if(externalColors)None else Some(Module(new TypedTileColorStore(p,useBlackBoxes)))
  private val colorIO=colors.map(_.io).getOrElse(color.get)
  private val depth=colorIO.depth.get
  private val workers=Seq.tabulate(4)(lane=>Module(new TypedDepthLane(lane)))
  private val beginOffered=RegInit(false.B);private val started=RegInit(false.B);private val colorDone=RegInit(false.B)
  private val colorOffered=RegInit(false.B);private val colorAccepted=RegInit(false.B);private val colorReturned=RegInit(false.B)
  private val quadOwned=RegInit(false.B);private val depthOwned=RegInit(false.B);private val depthStarted=RegInit(false.B)
  private val status=RegInit(0.U(3.W));private val quadStatus=RegInit(0.U(3.W));private val fault=RegInit(0.U(17.W))
  // Bound/overlap arithmetic belongs to admission. Capture its result before
  // driving fault priority and child ownership; no service starts in this phase.
  private val invalidDescriptor=Reg(Bool())
  when(phase===State.prepareValidation){invalidDescriptor:=TypedTilePixelRules.invalid(descriptor,p)}
  private val colorQuadResult=Reg(new TypedTileColorQuadCompletion);private val colorPassResult=Reg(new TypedTileColorCompletion)
  private val quadCounter=Module(new ObservedCarrySaveAccumulator(99))
  private val writeCounter=Module(new ObservedCarrySaveAccumulator(110));private val fillCounter=Module(new ObservedCarrySaveAccumulator(110))
  for(counter<-Seq(quadCounter,writeCounter,fillCounter))counter.io.clear:=io.begin.fire
  quadCounter.io.addend:=io.in.fire.asUInt
  private val inputQuads=quadCounter.io.value;private val totalWrites=writeCounter.io.value;private val totalFills=fillCounter.io.value
  private def sum(values: Seq[UInt]): UInt=if(values.size==1)values.head else{
    val (left,right)=values.splitAt(values.size/2);sum(left)+&sum(right)
  }
  private def observed(select: TypedDepthLane=>UInt): UInt=Mux(depthStarted,sum(workers.map(select)),0.U)
  writeCounter.io.addend:=Mux(phase===State.depthDone,observed(_.io.writes),0.U)
  fillCounter.io.addend:=Mux(phase===State.depthDone,observed(_.io.fills),0.U)
  private val active=phase=/=State.idle && phase=/=State.complete
  private val stop=status=/=0.U || io.cancel || colorIO.cancelSource
  private val anyFault=depthOwned && workers.map(_.io.fault).reduce(_ || _)
  private val badQuad=input.state.version=/=1.U || TypedTileColorRules.invalidQuad(input.color,descriptor.colors)
  private val numeric=TypedDepthFormat.depth(descriptor.depth.format) && input.state.depthEnable &&
    (for(i<-0 until 4;s<-0 until 4)yield {
      val lane=input.color.quad.lanes(i)
      input.color.samples(i)(s) && Fp32ViewportState.nan(Mux(lane.sampleDepthValid && !lane.depthOverride,lane.sampleDepth(s),lane.depth))
    }).reduce(_ || _)
  private val launch=phase===State.checkQuad && !stop && !badQuad && !numeric
  private def fail(code: UInt,offset: UInt): Unit={
    when(status===0.U && !io.cancel){status:=code;fault:=offset;when(quadOwned){quadStatus:=code}}
  }
  depth.active:=depthOwned;depth.views(0):=descriptor.depth;depth.views(1):=descriptor.stencil
  for((worker,lane)<-workers.zipWithIndex){
    worker.io.start:=launch;worker.io.cancel:=stop || anyFault;worker.io.descriptor:=descriptor;worker.io.input:=input
    depth.request(lane)<>worker.io.request;worker.io.response<>depth.response(lane)
  }
  // Pick one stable diagnostic when several lanes fault together.
  when(anyFault){
    val selected=PriorityEncoder(VecInit(workers.map(_.io.fault)).asUInt)
    fail(3.U,VecInit(workers.map(_.io.faultOffset))(selected))
  }
  colorIO.begin.valid:=beginOffered;colorIO.begin.bits:=descriptor.colors
  when(colorIO.begin.fire){beginOffered:=false.B;started:=true.B}
  colorIO.cancel:=started && (status=/=0.U || io.cancel) && !colorOffered
  colorIO.in.valid:=colorOffered;colorIO.in.bits.quad:=input.color.quad;colorIO.in.bits.blend:=input.color.blend
  for(lane<-0 until 4)colorIO.in.bits.samples(lane):=workers(lane).io.qualified
  when(colorIO.in.fire){colorOffered:=false.B;colorAccepted:=true.B}
  colorIO.out.ready:=colorAccepted && !colorReturned
  when(colorIO.out.fire){colorQuadResult:=colorIO.out.bits;colorReturned:=true.B
    when(colorIO.out.bits.status=/=0.U){fail(colorIO.out.bits.status,colorIO.out.bits.faultOffset)}
    when(!stop){phase:=State.quadDone}
  }
  colorIO.completion.ready:=started && !colorDone
  when(colorIO.completion.fire){colorDone:=true.B;colorPassResult:=colorIO.completion.bits
    when(colorIO.completion.bits.status=/=0.U){fail(colorIO.completion.bits.status,colorIO.completion.bits.faultOffset)}}
  private val external=phase===State.active && io.maintenance && !stop
  colorIO.maintenance:=external || phase===State.externalReply
  colorIO.memory.valid:=external && io.memory.valid;colorIO.memory.bits:=io.memory.bits
  io.memory.ready:=external && colorIO.memory.ready
  io.reply.valid:=phase===State.externalReply && colorIO.reply.valid;io.reply.bits:=colorIO.reply.bits
  colorIO.reply.ready:=phase===State.externalReply && io.reply.ready
  io.refill<>colorIO.refill;colorIO.refillReply<>io.refillReply
  private val producer=Mux(io.maintenance,io.memory.valid,io.in.valid)
  colorIO.end.valid:=phase===State.active && !producer && io.end.valid && !stop;colorIO.end.bits:=io.end.bits
  io.end.ready:=phase===State.active && !producer && colorIO.end.ready && !stop
  io.begin.ready:=phase===State.idle && !io.cancel;io.in.ready:=phase===State.active && !io.maintenance && !stop
  io.out.valid:=phase===State.emit;io.out.bits:=output;io.completion.valid:=phase===State.complete;io.completion.bits:=result
  io.busy:=phase=/=State.idle;io.cancelSource:=active && stop
  private val controlsDrained= !beginOffered && !depthOwned && depth.drained && !colorOffered && (!colorAccepted || colorReturned)
  private def formQuadResult(): Unit={
    val q=input.color.quad;output:=0.U.asTypeOf(output);output.identity:=q.identity;output.sequence:=q.sequence;output.primitiveId:=q.primitiveId
    output.instanceId:=q.instanceId;output.partIndex:=q.partIndex;output.status:=Mux(io.cancel && quadStatus===0.U,4.U,quadStatus);output.faultOffset:=fault
    output.testedSamples:=observed(_.io.tested);output.passingSamples:=observed(_.io.passing)
    output.stencilRejected:=observed(_.io.stencilRejected);output.depthRejected:=observed(_.io.depthRejected)
    output.depthUpdates:=observed(_.io.depthUpdates);output.stencilUpdates:=observed(_.io.stencilUpdates)
    output.dsReads:=observed(_.io.reads);output.dsFills:=observed(_.io.fills);output.dsWrites:=observed(_.io.writes)
    output.colors:=colorQuadResult;phase:=State.emit
  }
  when(active && io.cancel && status===0.U){status:=4.U;when(quadOwned){quadStatus:=4.U}}
  // The running depth owner must drain even after faults or cancellation.
  when(phase===State.runDepth && workers.map(_.io.done).reduce(_ && _) && depth.drained){phase:=State.depthDone}
  when(phase===State.depthDone){
    depthOwned:=false.B;phase:=Mux(stop,State.quadDone,State.sendColor)
  }
  when(!stop || phase===State.idle || phase===State.emit || phase===State.externalReply || phase===State.closing || phase===State.finish || phase===State.complete){
    switch(phase){
      is(State.idle){when(io.begin.fire){descriptor:=io.begin.bits;status:=0.U;fault:=0.U;started:=false.B;colorDone:=false.B
        quadOwned:=false.B;depthOwned:=false.B;depthStarted:=false.B;colorAccepted:=false.B;colorReturned:=false.B
        colorPassResult:=0.U.asTypeOf(colorPassResult);phase:=State.prepareValidation}}
      is(State.prepareValidation){phase:=State.validate}
      is(State.validate){when(invalidDescriptor){fail(1.U,0.U);phase:=State.closing}
        .otherwise{beginOffered:=true.B;phase:=State.bind}}
      is(State.bind){when(started && colorIO.in.ready){phase:=State.active}}
      is(State.active){
        when(io.in.fire){input:=io.in.bits;quadOwned:=true.B;depthStarted:=false.B;quadStatus:=0.U
          colorAccepted:=false.B;colorReturned:=false.B
          colorQuadResult:=0.U.asTypeOf(colorQuadResult);colorQuadResult.identity:=io.in.bits.color.quad.identity;phase:=State.checkQuad}
        when(io.memory.fire){phase:=State.externalReply}
        when(io.end.fire){when(!io.end.bits){fail(5.U,0.U)};phase:=State.closing}
      }
      is(State.checkQuad){when(badQuad){fail(6.U,0.U);phase:=State.quadDone}
        .elsewhen(numeric){fail(7.U,0.U);phase:=State.quadDone}
        .otherwise{depthStarted:=true.B;depthOwned:=true.B;phase:=State.runDepth}}
      is(State.sendColor){colorOffered:=true.B;phase:=State.waitColor}
      is(State.quadDone){formQuadResult()}
      is(State.emit){when(io.out.fire){quadOwned:=false.B;phase:=Mux(stop,State.closing,State.active)}}
      is(State.externalReply){when(io.reply.fire){phase:=Mux(stop,State.closing,State.active)}}
      is(State.closing){when(controlsDrained && !quadOwned && (!started || (colorDone && !colorIO.busy))){phase:=State.finish}}
      is(State.finish){result:=0.U.asTypeOf(result);result.identity:=descriptor.colors.identity;result.status:=Mux(io.cancel && status===0.U,4.U,status)
        result.faultOffset:=fault;result.inputQuads:=inputQuads;result.dsWrites:=totalWrites;result.dsFills:=totalFills;result.colors:=colorPassResult;phase:=State.complete}
      is(State.complete){when(io.completion.fire){phase:=State.idle}}
    }
  }
  when(active && stop && phase=/=State.emit && phase=/=State.externalReply && phase=/=State.closing && phase=/=State.finish && phase=/=State.depthDone){
    when(controlsDrained){when(quadOwned){formQuadResult()}.otherwise{phase:=State.closing}}
  }
  when(io.completion.valid){assert(!colorIO.busy && controlsDrained,"parallel pixel completion escaped word/refill drain")}
}
