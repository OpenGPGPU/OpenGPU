package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.{ObservedCarrySaveAccumulator,PreparedUnsignedSum}

private[graphics] class NativePixelMetadata extends Bundle {
  val tag=UInt(16.W);val result=new TypedTilePixelQuadCompletion
}

/** Capture only an unowned tail row before the sample store accepts its quad.
  * Committing ownership still uses the paired store handshake. Repeated writes
  * of an unaccepted head cannot change any valid row or make a result visible.
  * Registered space keeps the wide RAM write enable off the worker-ready path.
  */
private[graphics] class NativePixelMetadataQueue(entries: Int=64) extends Module {
  require(isPow2(entries) && entries>=2)
  private val bits=log2Ceil(entries)
  val io=IO(new Bundle {
    val capture=Input(Bool())
    val enq=Flipped(Decoupled(new NativePixelMetadata))
    val deq=Decoupled(new NativePixelMetadata)
  })
  private val head=RegInit(0.U(bits.W));private val tail=RegInit(0.U(bits.W))
  private val count=RegInit(0.U((bits+1).W))
  private val data=Mem(entries,new NativePixelMetadata)
  io.enq.ready:=count<entries.U
  io.deq.valid:=count=/=0.U;io.deq.bits:=data(head)
  when(io.capture && io.enq.ready){
    assert(count===0.U || tail=/=head,"metadata capture overwrote an owned row")
    data.write(tail,io.enq.bits)
  }
  when(io.enq.fire){
    assert(io.capture,"metadata committed without capturing its quad")
    tail:=tail+1.U
  }
  when(io.deq.fire){head:=head+1.U}
  switch(Cat(io.enq.fire,io.deq.fire)){
    is("b10".U){count:=count+1.U}
    is("b01".U){count:=count-1.U}
  }
}

/** Overlapped native quads and the general depth/color controller share one
  * canonical store. Whole-quad preflight precedes packetization. Native packet
  * retirements are ordered, so only the oldest quad needs live aggregate state.
  * Generic/native cohorts hand off after physical drain; held public results
  * keep their FIFO order and retain pass ownership through EOF/cancellation.
  */
class NativeTypedSamplePixelBackend(p: TypedTileColorConfig,useBlackBoxes: Boolean=false) extends Module {
  require(p.nativeSamples)
  val io=IO(new TypedTilePixelBackendIO(p))
  private val fallback=Module(new ParallelTypedTilePixelBackend(p,useBlackBoxes,externalColors=true))
  private val store=Module(new SharedTypedSampleTileStore(p,useBlackBoxes))
  fallback.color.get<>store.io
  // Registered occupancy keeps admission independent of the oldest quad's
  // preflight, resident-store readiness and generic/native cohort handoff.
  private val input=Module(new Queue(new TypedTilePixelQuad,2))
  private val metadata=Module(new NativePixelMetadataQueue(64))
  private val output=Module(new Queue(new TypedTilePixelQuadCompletion,2,pipe=true))
  private val descriptor=Reg(new TypedTilePixelDescriptor)
  private val active=RegInit(false.B);private val opened=RegInit(false.B)
  private val closing=RegInit(false.B);private val endSent=RegInit(false.B);private val endSuccess=Reg(Bool())
  private val status=RegInit(0.U(3.W));private val fault=RegInit(0.U(17.W))
  private val genericOwned=RegInit(false.B);private val tag=RegInit(0.U(16.W))
  private val passResult=Reg(new TypedTilePixelCompletion);private val passValid=RegInit(false.B)
  private object Mode extends ChiselEnum {val generic,bind,native,drain=Value}
  private val mode=RegInit(Mode.generic)
  private val nativeActive=mode=/=Mode.generic
  private val stop=status=/=0.U || io.cancel || fallback.io.cancelSource || store.samples.fault ||
    (fallback.io.completion.valid && fallback.io.completion.bits.status=/=0.U)
  private def fail(code: UInt,offset: UInt): Unit={when(status===0.U && !io.cancel){status:=code;fault:=offset}}
  private val quadCounter=Module(new ObservedCarrySaveAccumulator(99))
  private val nativeCounter=Module(new ObservedCarrySaveAccumulator(99))
  private val writeCounter=Module(new ObservedCarrySaveAccumulator(110))
  private val fillCounter=Module(new ObservedCarrySaveAccumulator(110))
  private val colorCounter=Module(new ObservedCarrySaveAccumulator(110))
  for(c<-Seq(quadCounter,nativeCounter,writeCounter,fillCounter,colorCounter))c.io.clear:=io.begin.fire
  quadCounter.io.addend:=io.in.fire.asUInt
  nativeCounter.io.addend:=store.samples.in.fire.asUInt

  fallback.io.begin.valid:=io.begin.valid && !active && !passValid
  fallback.io.begin.bits:=io.begin.bits
  io.begin.ready:=fallback.io.begin.ready && !active && !passValid
  input.io.enq.valid:=io.in.valid && active && opened && !passValid && !closing && !stop && !io.maintenance
  input.io.enq.bits:=io.in.bits
  io.in.ready:=input.io.enq.ready && active && opened && !passValid && !closing && !stop && !io.maintenance
  io.end.ready:=active && opened && !passValid && !closing && !stop && !io.maintenance && !io.in.valid
  when(io.end.fire){closing:=true.B;endSuccess:=io.end.bits;when(!io.end.bits){fail(5.U,0.U)}}
  when(active && !passValid && io.cancel && status===0.U){status:=4.U}
  when(active && (fallback.io.in.ready || fallback.io.memory.ready || fallback.io.end.ready)){opened:=true.B}
  fallback.io.cancel:=active && !passValid && (io.cancel || status=/=0.U)
  io.cancelSource:=active && !passValid && stop
  io.busy:=active || passValid || fallback.io.busy

  private val head=input.io.deq.bits
  private val bad=head.state.version=/=1.U || TypedTileColorRules.invalidQuad(head.color,descriptor.colors)
  private val numeric=TypedDepthFormat.depth(descriptor.depth.format) && head.state.depthEnable &&
    (for(l<-0 until 4;s<-0 until 4)yield {
      val v=head.color.quad.lanes(l)
      head.color.samples(l)(s) && Fp32ViewportState.nan(Mux(v.sampleDepthValid && !v.depthOverride,v.sampleDepth(s),v.depth))
    }).reduce(_ || _)
  private val eligible=store.samples.eligible && TypedSamplePacketLayout.eligible(head,descriptor)
  private def emptyResult(q: TypedTilePixelQuad): TypedTilePixelQuadCompletion={
    val r=WireDefault(0.U.asTypeOf(new TypedTilePixelQuadCompletion));val v=q.color.quad
    r.identity:=v.identity;r.sequence:=v.sequence;r.primitiveId:=v.primitiveId;r.instanceId:=v.instanceId;r.partIndex:=v.partIndex
    r.colors.identity:=v.identity;r.colors.sequence:=v.sequence;r.colors.primitiveId:=v.primitiveId
    r.colors.instanceId:=v.instanceId;r.colors.partIndex:=v.partIndex
    r
  }
  input.io.deq.ready:=false.B
  fallback.io.in.valid:=false.B;fallback.io.in.bits:=head
  metadata.io.enq.valid:=false.B;metadata.io.enq.bits.tag:=tag;metadata.io.enq.bits.result:=emptyResult(head)
  store.samples.active:=nativeActive
  store.samples.bind.valid:=mode===Mode.bind && !stop;store.samples.bind.bits:=descriptor
  private val nativeCandidate=mode===Mode.native && input.io.deq.valid && !bad && !numeric && eligible && !stop
  metadata.io.capture:=nativeCandidate
  store.samples.in.valid:=nativeCandidate && metadata.io.enq.ready
  store.samples.in.bits.tag:=tag;store.samples.in.bits.quad:=head
  when(store.samples.in.fire){
    metadata.io.enq.valid:=true.B;input.io.deq.ready:=true.B;tag:=tag+1.U
  }
  when(mode===Mode.bind){when(stop){mode:=Mode.drain}.elsewhen(store.samples.bind.fire){mode:=Mode.native}}
  when(mode===Mode.native && (stop || (input.io.deq.valid && (bad || numeric || !eligible)) ||
      (!input.io.deq.valid && (closing || io.maintenance)))){mode:=Mode.drain}
  when(mode===Mode.drain && store.samples.drained && !metadata.io.deq.valid){mode:=Mode.generic}

  io.out<>output.io.deq
  output.io.enq.valid:=false.B;output.io.enq.bits:=0.U.asTypeOf(output.io.enq.bits)
  fallback.io.out.ready:=mode===Mode.generic && genericOwned && output.io.enq.ready
  when(fallback.io.out.valid && genericOwned){
    assert(mode===Mode.generic,"legacy quad output crossed a native cohort")
    output.io.enq.valid:=true.B;output.io.enq.bits:=fallback.io.out.bits
  }
  when(fallback.io.in.fire){genericOwned:=true.B}
  when(fallback.io.out.fire){genericOwned:=false.B
    when(fallback.io.out.bits.status=/=0.U && fallback.io.out.bits.status=/=4.U){fail(fallback.io.out.bits.status,fallback.io.out.bits.faultOffset)}}
  when(mode===Mode.generic && !genericOwned && input.io.deq.valid){
    when(stop || bad || numeric){
      output.io.enq.valid:=true.B;output.io.enq.bits:=emptyResult(head)
      output.io.enq.bits.status:=Mux(stop,4.U,Mux(bad,6.U,7.U))
      input.io.deq.ready:=output.io.enq.ready
      when(input.io.deq.fire && !stop){fail(Mux(bad,6.U,7.U),0.U)}
    }.elsewhen(eligible){
      when(store.samples.readyToEnter && fallback.io.in.ready){mode:=Mode.bind}
    }.otherwise{
      fallback.io.in.valid:=true.B;input.io.deq.ready:=fallback.io.in.ready
    }
  }

  private val aggregate=Reg(new TypedTilePixelQuadCompletion)
  private val packet=store.samples.out.bits;private val effects=packet.result
  private val previous=Mux(packet.packet===0.U,metadata.io.deq.bits.result,aggregate)
  private val nativeResult=WireDefault(previous)
  private val colors=PopCount(effects.colorWritten)
  private val dsWrites=PopCount(effects.depthWritten|effects.stencilWritten)
  nativeResult.status:=Mux(previous.status=/=0.U,previous.status,effects.status)
  when(previous.status===0.U && effects.status=/=0.U){nativeResult.faultOffset:=effects.faultOffset}
  nativeResult.testedSamples:=previous.testedSamples+PopCount(effects.tested)
  nativeResult.passingSamples:=previous.passingSamples+PopCount(effects.passing)
  nativeResult.stencilRejected:=previous.stencilRejected+PopCount(effects.stencilRejected)
  nativeResult.depthRejected:=previous.depthRejected+PopCount(effects.depthRejected)
  nativeResult.depthUpdates:=previous.depthUpdates+PopCount(effects.depthWritten)
  nativeResult.stencilUpdates:=previous.stencilUpdates+PopCount(effects.stencilWritten)
  nativeResult.dsReads:=previous.dsReads+effects.dsReads;nativeResult.dsFills:=previous.dsFills+effects.dsFills
  nativeResult.dsWrites:=previous.dsWrites+dsWrites
  nativeResult.colors.status:=nativeResult.status;nativeResult.colors.faultOffset:=nativeResult.faultOffset
  nativeResult.colors.touchedSamples:=previous.colors.touchedSamples+colors
  nativeResult.colors.completedLocations:=previous.colors.completedLocations+colors
  nativeResult.colors.wordWrites:=previous.colors.wordWrites+colors
  nativeResult.colors.writtenBits:=previous.colors.writtenBits+((colors*PopCount(packet.channelMask))<<3)
  store.samples.out.ready:=metadata.io.deq.valid && (!packet.last || output.io.enq.ready)
  metadata.io.deq.ready:=store.samples.out.fire && packet.last
  when(store.samples.out.valid){assert(nativeActive && metadata.io.deq.valid && packet.tag===metadata.io.deq.bits.tag,
    "native packet lost ordered quad metadata")}
  when(store.samples.out.fire){
    aggregate:=nativeResult
    when(effects.status=/=0.U && effects.status=/=4.U){fail(effects.status,effects.faultOffset)}
  }
  private val nativeLast=store.samples.out.fire && packet.last
  when(store.samples.out.valid && packet.last && metadata.io.deq.valid){
    output.io.enq.valid:=true.B;output.io.enq.bits:=nativeResult
  }
  writeCounter.io.addend:=Mux(nativeLast,nativeResult.dsWrites,0.U)
  fillCounter.io.addend:=Mux(nativeLast,nativeResult.dsFills,0.U)
  colorCounter.io.addend:=Mux(nativeLast,nativeResult.colors.wordWrites,0.U)

  private val localDrained= !input.io.deq.valid && !metadata.io.deq.valid && !genericOwned &&
    store.samples.drained && mode===Mode.generic
  private val maintenance=active && opened && localDrained && !endSent
  fallback.io.maintenance:=io.maintenance && maintenance
  fallback.io.memory.valid:=io.memory.valid && maintenance;fallback.io.memory.bits:=io.memory.bits
  io.memory.ready:=maintenance && fallback.io.memory.ready
  io.reply<>fallback.io.reply;io.refill<>fallback.io.refill;fallback.io.refillReply<>io.refillReply
  fallback.io.end.valid:=closing && !stop && !endSent && localDrained && !output.io.deq.valid && !io.maintenance
  fallback.io.end.bits:=endSuccess
  when(fallback.io.end.fire){endSent:=true.B}
  private val totalsPrepared=RegInit(0.U(2.W))
  private val prepareTotals=fallback.io.completion.valid && totalsPrepared===0.U && localDrained && !output.io.deq.valid
  when(io.begin.fire){totalsPrepared:=0.U}
    .elsewhen(prepareTotals){totalsPrepared:=1.U}
    .elsewhen(totalsPrepared===1.U){totalsPrepared:=2.U}
  // Hold the child completion for both preparation clocks. Its report and all
  // native counters remain owned and stable after local/physical drain.
  fallback.io.completion.ready:= !passValid && totalsPrepared===2.U && localDrained && !output.io.deq.valid
  private def passTotal(a: UInt,b: UInt,width: Int): UInt={
    val add=Module(new PreparedUnsignedSum(width))
    add.io.a:=a;add.io.b:=b;add.io.capture:=prepareTotals
    add.io.sum(width-1,0)
  }
  // Preserve the full modular report widths with registered partial sums.
  private val totalWrites=passTotal(fallback.io.completion.bits.dsWrites,writeCounter.io.value,110)
  private val totalFills=passTotal(fallback.io.completion.bits.dsFills,fillCounter.io.value,110)
  private val totalColorQuads=passTotal(fallback.io.completion.bits.colors.inputQuads,nativeCounter.io.value,99)
  private val totalColorWrites=passTotal(fallback.io.completion.bits.colors.wordWrites,colorCounter.io.value,110)
  when(fallback.io.completion.fire){
    passResult:=fallback.io.completion.bits
    passResult.status:=Mux(status=/=0.U,status,fallback.io.completion.bits.status)
    passResult.faultOffset:=Mux(status=/=0.U,fault,fallback.io.completion.bits.faultOffset)
    passResult.inputQuads:=quadCounter.io.value
    passResult.dsWrites:=totalWrites
    passResult.dsFills:=totalFills
    passResult.colors.inputQuads:=totalColorQuads
    passResult.colors.wordWrites:=totalColorWrites
    passValid:=true.B
  }
  io.completion.valid:=passValid;io.completion.bits:=passResult
  when(io.completion.fire){passValid:=false.B;active:=false.B;opened:=false.B}
  when(io.begin.fire){
    assert(localDrained && !output.io.deq.valid,"native pixel rebind crossed owned results")
    descriptor:=io.begin.bits;active:=true.B;opened:=false.B;closing:=false.B;endSent:=false.B
    status:=0.U;fault:=0.U;tag:=0.U;genericOwned:=false.B;mode:=Mode.generic
  }
}
