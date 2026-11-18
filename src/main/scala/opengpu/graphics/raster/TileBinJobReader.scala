package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

/** Explicit little-endian ABI; independent of Bundle packing. See the word
  * map in TileBinJobReader. Allocations and VM remain pinned until completion.
  */
object TileBinJobMemoryLayout {
  val magic=BigInt("47424a01",16)
  val words=128
  val bytes=512
  val descriptorLines=8
  val appendBytes=32
  val transactions=9
  val appendTransaction=8
}

class TileBinJobCompletion(commandIdWidth: Int) extends Bundle {
  val commandId=UInt(commandIdWidth.W)
  /** Only this final status validates the complete image. */
  val status=UInt(TileBinRenderStatus.width.W)
  val frontendStatus=UInt(TileBinRenderStatus.width.W)
  val descriptorReadBytes=UInt(16.W)
  val appendReadBytes=UInt(64.W)
  /** Producer handshakes, not committed references or per-batch retries. */
  val acceptedAppends=UInt(32.W)
  val renderValid=Bool()
  val render=new TileBinContinuationCompletion
}

/** Bounded memory submission for the streaming renderer. Descriptor reads use
  * eight independently owned tags and may return in any order. One append
  * line supplies two records; the next record is retained until its producer
  * reply, including internal bin restarts. Faulted data never becomes input.
  *
  * All external memory offers are irrevocable through cancellation. An offered
  * begin is also retained; cancelCore starts only after begin is accepted.
  * Cancellation terminates unaccepted private append/end controls, but drains
  * every accepted append reply, memory transaction and renderer completion.
  * Descriptor/append/record stores must be published before submit. Bounds are
  * virtual; physical alias exclusion and VM pinning belong to the host owner.
  */
class TileBinJobReader(config: GpuConfig=GpuConfig(),commandIdWidth: Int=8,
  paged: Boolean=true,programmable: Boolean=false) extends Module {
  require(config.xLen==32 && commandIdWidth>0 && commandIdWidth<=12)
  val io=IO(new Bundle {
    val submit=Flipped(Decoupled(new TileJobDescriptorRequest(commandIdWidth)))
    val begin=Decoupled(new TileBinContinuationDescriptor)
    val append=Decoupled(new TileBinAppend)
    val appended=Flipped(Decoupled(new TileBinContinuationAppendResult))
    val end=Decoupled(Bool())
    val renderCompletion=Flipped(Decoupled(new TileBinContinuationCompletion))
    val cancel=Input(Bool())
    val cancelCore=Output(Bool())
    val completion=Decoupled(new TileBinJobCompletion(commandIdWidth))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,TileBinJobMemoryLayout.transactions))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,TileBinJobMemoryLayout.transactions)))
    val busy=Output(Bool())
  })
  private val Seq(idle,bounds,fetchDescriptor,decode,envelopes,offerBegin,stream,
    offerAppend,waitAppend,offerEnd,waitChild,drain,finish,done)=Enum(14)
  private val phase=RegInit(idle)
  private val binding=Reg(new TileJobDescriptorRequest(commandIdWidth))
  private val words=Reg(Vec(TileBinJobMemoryLayout.words,UInt(32.W)))
  private val descriptor=Reg(new TileBinContinuationDescriptor)
  private val appendBase=Reg(UInt(32.W)); private val appendLimit=Reg(UInt(33.W))
  private val appendCount=Reg(UInt(32.W)); private val index=RegInit(0.U(32.W))
  private val appendLine=Reg(UInt(512.W)); private val lineValid=RegInit(false.B)
  private val reference=Reg(new TileBinAppend)
  private val accepted=RegInit(false.B)
  private val acceptedCount=RegInit(0.U(32.W))
  private val begun=RegInit(false.B); private val childFinished=RegInit(false.B)
  private val childResult=Reg(new TileBinContinuationCompletion)
  private val cancelled=RegInit(false.B)
  private val failure=RegInit(0.U(TileBinRenderStatus.width.W))
  private val result=Reg(new TileBinJobCompletion(commandIdWidth))
  private val issued=RegInit(0.U(4.W))
  private val live=RegInit(0.U(9.W))
  private val offered=RegInit(false.B)
  private val offerAddress=Reg(UInt(32.W)); private val offerId=Reg(UInt(4.W))
  private val descriptorBytes=RegInit(0.U(16.W))
  private val appendLines=RegInit(0.U(32.W))
  private val responseId=io.memoryResponse.bits.transactionId
  private val ownedResponse=responseId<9.U && live(responseId)
  io.memoryResponse.ready:=ownedResponse
  private val faultNow=io.memoryResponse.fire && io.memoryResponse.bits.fault
  private val stop=cancelled || io.cancel || failure=/=0.U || faultNow
  private val active=phase=/=idle && phase=/=done

  // Word map: 0 magic, 1 size, 2..4 identity, 5..8 append allocation/count,
  // 9 restart budget, 10 screenWH, 11 extentWH, 12 originXY, 13 tile flags,
  // 14 tile count, 15 first handle, 16..22 pool, 23..25 directory,
  // 26..28 records, 29..32 shader staging, 33..38 color, 39..44 depth,
  // 45..48 resolve, 49..72 eight spill segments, 73..127 reserved zero.
  private val decoded=WireDefault(0.U.asTypeOf(new TileBinContinuationDescriptor))
  decoded.version:=1.U; decoded.restartLimit:=words(9)(15,0)
  private val r=decoded.render
  r.version:=1.U; r.replay.version:=1.U
  r.screenW:=words(10)(15,0); r.screenH:=words(10)(31,16)
  r.extentW:=words(11)(15,0); r.extentH:=words(11)(31,16)
  r.replay.firstHandle:=words(15)
  private val bins=r.replay.bins
  bins.version:=(if (paged) 2.U else 1.U); bins.resume:=false.B
  bins.tileCount:=words(14)(15,0)
  bins.directoryBase:=words(23); bins.directoryLimit:=Cat(words(25)(0),words(24))
  private val pool=bins.pool
  pool.version:=words(21)(7,0); pool.base:=words(16)
  pool.limit:=Cat(words(18)(0),words(17)); pool.initialLimit:=Cat(words(20)(0),words(19))
  pool.spillCount:=words(21)(11,8); pool.pageSizeLog2:=words(22)(5,0)
  for (i <- 0 until 8) {
    pool.spills(i).base:=words(49+3*i)
    pool.spills(i).limit:=Cat(words(51+3*i)(0),words(50+3*i))
  }
  r.replay.records.version:=1.U; r.replay.records.base:=words(26)
  r.replay.records.limit:=Cat(words(28)(0),words(27))
  r.shaderBase:=words(29); r.shaderLimit:=Cat(words(31)(0),words(30)); r.shaderEngineStride:=words(32)
  private val a=r.attachments
  a.version:=1.U; a.originX:=words(12)(15,0); a.originY:=words(12)(31,16)
  a.tileLog2:=words(13)(2,0); a.tileWidth:=Mux(a.tileLog2===3.U,8.U,16.U); a.tileHeight:=a.tileWidth
  a.sampleMode:=words(13)(5,4); a.colorFormat:=words(13)(10,8); a.resolve:=words(13)(16)
  for ((view,offset) <- Seq((a.color,33),(a.depth,39))) {
    view.base:=words(offset); view.limit:=Cat(words(offset+2)(0),words(offset+1))
    view.rowPitch:=words(offset+3); view.loadOp:=words(offset+4)(1,0)
    view.store:=words(offset+4)(2); view.clearValue:=words(offset+5)
  }
  a.resolveBase:=words(45); a.resolveLimit:=Cat(words(47)(0),words(46)); a.resolveRowPitch:=words(48)
  for (identity <- Seq(a.identity,pool.identity,r.replay.records.identity)) {
    identity.context:=words(2); identity.pass:=words(3); identity.generation:=words(4)
  }
  private def masked(i: Int,mask: BigInt): Bool=(words(i) & (((BigInt(1)<<32)-1)^mask).U(32.W))===0.U
  private val highWords=Seq(7,18,20,25,28,31,35,41,47) ++ (0 until 8).map(i=>51+3*i)
  private val encodingLegal=words(0)===TileBinJobMemoryLayout.magic.U && words(1)===128.U &&
    highWords.map(masked(_,1)).reduce(_ && _) && masked(9,65535) && masked(14,65535) &&
    masked(13,0x10737) && masked(21,0xfff) && masked(22,63) && masked(37,7) && masked(43,7) &&
    words.drop(73).map(_===0.U).reduce(_ && _) &&
    (pool.version=/=1.U || (pool.initialLimit===0.U && pool.spillCount===0.U && pool.spills.map(_.asUInt===0.U).reduce(_ && _))) &&
    (if (paged) true.B else bins.directoryBase===0.U && bins.directoryLimit===0.U) &&
    (if (programmable) true.B else r.shaderBase===0.U && r.shaderLimit===0.U && r.shaderEngineStride===0.U)

  private val d=descriptor.render
  private val views=Seq((d.replay.bins.pool.base,d.replay.bins.pool.limit,true.B),
    (d.replay.bins.directoryBase,d.replay.bins.directoryLimit,(if (paged) true.B else false.B)),
    (d.attachments.color.base,d.attachments.color.limit,true.B),
    (d.attachments.depth.base,d.attachments.depth.limit,true.B),
    (d.attachments.resolveBase,d.attachments.resolveLimit,d.attachments.resolve),
    (d.shaderBase,d.shaderLimit,(if (programmable) true.B else false.B)))
  private def disjoint(base: UInt,limit: UInt,other: UInt,end: UInt): Bool=
    base.pad(33)===limit || other.pad(33)===end || limit<=other.pad(33) || end<=base.pad(33)
  private val appendEnd=appendBase.pad(38)+(appendCount.pad(38)<<5)
  private val envelopesLegal=appendBase(5,0)===0.U && appendLimit(5,0)===0.U &&
    appendBase.pad(33)<=appendLimit && appendLimit<=(BigInt(1)<<32).U && appendEnd<=appendLimit &&
    views.map { case (base,limit,enabled)=> !enabled ||
      (base.pad(33)<=limit && limit<=(BigInt(1)<<32).U &&
        disjoint(binding.base,binding.limit,base,limit) && disjoint(appendBase,appendLimit,base,limit))
    }.reduce(_ && _)

  io.submit.ready:=phase===idle && !io.cancel
  io.busy:=phase=/=idle
  io.completion.valid:=phase===done; io.completion.bits:=result
  io.begin.valid:=phase===offerBegin; io.begin.bits:=descriptor
  io.append.valid:=phase===offerAppend && !stop && !childFinished
  io.append.bits:=reference
  io.appended.ready:=begun && accepted
  io.end.valid:=phase===offerEnd && !stop && !childFinished; io.end.bits:=true.B
  io.cancelCore:=begun && !childFinished && stop
  io.renderCompletion.ready:=begun && !childFinished && !accepted
  io.memoryRequest.valid:=offered
  io.memoryRequest.bits:=0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address:=offerAddress
  io.memoryRequest.bits.transactionId:=offerId
  io.memoryRequest.bits.sizeLog2:=6.U
  io.memoryRequest.bits.cachePolicy:=CachePolicy.uncached
  private val sent=Mux(io.memoryRequest.fire,UIntToOH(offerId,9),0.U)
  private val received=Mux(io.memoryResponse.fire,UIntToOH(responseId,9),0.U)
  live:=(live | sent) & ~received
  when(!offered && !stop && !io.renderCompletion.valid) {
    when(phase===fetchDescriptor && issued<8.U) {
      offered:=true.B; offerId:=issued; offerAddress:=binding.base+(issued<<6)
    }.elsewhen(phase===stream && !lineValid && !live(8) && index<appendCount && !childFinished) {
      offered:=true.B; offerId:=8.U; offerAddress:=appendBase+((index>>1)<<6)
    }
  }
  when(io.memoryRequest.fire) {
    offered:=false.B
    when(offerId===8.U) { appendLines:=appendLines+1.U }
      .otherwise { issued:=issued+1.U; descriptorBytes:=descriptorBytes+64.U }
  }
  when(io.memoryResponse.fire) {
    when(io.memoryResponse.bits.fault) { failure:=PrimitiveBinStatus.memoryFault.U; lineValid:=false.B }
      .elsewhen(responseId===8.U) { appendLine:=io.memoryResponse.bits.readData; lineValid:=true.B }
      .otherwise {
        for (i <- 0 until 16) words(Cat(responseId(2,0),i.U(4.W))):=io.memoryResponse.bits.readData(32*i+31,32*i)
      }
  }
  when(io.submit.fire) {
    binding:=io.submit.bits; cancelled:=false.B; failure:=0.U
    issued:=0.U; live:=0.U; offered:=false.B; descriptorBytes:=0.U; appendLines:=0.U
    index:=0.U; acceptedCount:=0.U; accepted:=false.B; begun:=false.B; childFinished:=false.B
    childResult:=0.U.asTypeOf(childResult); lineValid:=false.B; phase:=bounds
  }
  when(active && io.cancel) { cancelled:=true.B }
  when(phase===bounds) {
    val end=binding.base +& TileBinJobMemoryLayout.bytes.U
    when(!binding.envelopeValid || binding.base(5,0)=/=0.U || binding.limit>(BigInt(1)<<32).U || end>binding.limit) {
      failure:=PrimitiveBinStatus.invalidDescriptor.U; phase:=drain
    }.otherwise { phase:=fetchDescriptor }
  }
  when(phase===fetchDescriptor && issued===8.U && live===0.U && !offered) { phase:=decode }
  when(phase===decode) {
    when(!encodingLegal) { failure:=TileBinRenderStatus.invalidEncoding.U; phase:=drain }
      .otherwise {
        descriptor:=decoded; appendBase:=words(5); appendLimit:=Cat(words(7)(0),words(6)); appendCount:=words(8)
        phase:=envelopes
      }
  }
  when(phase===envelopes) {
    when(!envelopesLegal) { failure:=PrimitiveBinStatus.invalidDescriptor.U; phase:=drain }
      .otherwise { phase:=offerBegin }
  }
  when(io.begin.fire) { begun:=true.B; phase:=Mux(stop,waitChild,Mux(appendCount===0.U,offerEnd,stream)) }
  private val appendWords=appendLine.asTypeOf(Vec(16,UInt(32.W)))
  private val half=Mux(index(0),8.U(4.W),0.U(4.W))
  private val appendLegal=appendWords(half)<d.replay.bins.tileCount &&
    (5 until 8).map(i=>appendWords(half+i.U)===0.U).reduce(_ && _)
  when(phase===stream && lineValid && !stop && !childFinished) {
    when(!appendLegal) { failure:=TileBinRenderStatus.invalidEncoding.U; phase:=waitChild }
      .otherwise {
        reference.tile:=appendWords(half)
        reference.reference.primitive:=appendWords(half+1.U); reference.reference.state:=appendWords(half+2.U)
        reference.reference.sequence:=appendWords(half+3.U); reference.reference.token:=appendWords(half+4.U)
        phase:=offerAppend
      }
  }
  when(io.append.fire) { accepted:=true.B; acceptedCount:=acceptedCount+1.U; phase:=waitAppend }
  when(io.appended.fire) {
    assert(io.appended.bits.result.identity.asUInt===descriptor.render.attachments.identity.asUInt,
      "memory producer reply must retain its submitting identity")
    assert(io.appended.bits.result.tile===reference.tile && io.appended.bits.result.reference.asUInt===reference.reference.asUInt,
      "memory producer must retain the exact accepted reference through its reply")
    accepted:=false.B
    when(io.appended.bits.result.status===0.U && !stop) {
      index:=index+1.U
      when(index(0)) { lineValid:=false.B }
      phase:=Mux(index+1.U===appendCount,offerEnd,stream)
    }.otherwise { phase:=waitChild }
  }
  when(io.end.fire) { phase:=waitChild }
  when(stop && active && phase=/=offerBegin) { phase:=Mux(begun && !childFinished,waitChild,drain) }
  when(io.renderCompletion.fire) {
    assert(io.renderCompletion.bits.identity.asUInt===descriptor.render.attachments.identity.asUInt)
    childResult:=io.renderCompletion.bits; childFinished:=true.B; phase:=drain; lineValid:=false.B
  }
  when(phase===drain && !offered && live===0.U && !accepted && (!begun || childFinished)) { phase:=finish }
  when(phase===finish) {
    val frontend=TileBinRenderStatus.merge(failure,Mux(cancelled || io.cancel,PrimitiveBinStatus.cancelled.U,0.U))
    result.commandId:=binding.commandId; result.frontendStatus:=frontend
    result.status:=TileBinRenderStatus.merge(frontend,Mux(childFinished,childResult.status,0.U))
    result.descriptorReadBytes:=descriptorBytes; result.appendReadBytes:=appendLines.pad(64)<<6
    result.acceptedAppends:=acceptedCount; result.renderValid:=childFinished; result.render:=childResult
    phase:=done
  }
  when(io.completion.fire) { phase:=idle }
}
