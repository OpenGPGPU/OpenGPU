package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,GraphicsAddressTranslator,VectorTlbFlush}

class BankedTextureGroupCancel extends Bundle {
  val identity=new GraphicsWorkIdentity
  val tag=UInt(32.W)
}
class TranslatedBankedRgba8Result(rawTapOutput: Boolean=false) extends BankedRgba8FloatResult(rawTapOutput) {
  /** 0=none,1=translation,2=physical data. faultAddress is always virtual. */
  val faultSource=UInt(2.W)
}

/** Translate every active tap before a physical cache lookup,including hits.
  * The parent supplies checked virtual RGBA8 spans,row parity and Q16 weights;
  * coordinate/resource admission is upstream. Four TLB ports retain hit II1.
  * Thirty-two admission/result rows are additional to the physical sampler's
  * sixteen association slots and16 fills. PTE traffic has a separate4-ID port.
  * One VM epoch is pinned until busy clears. A SATP change while idle performs
  * a full translation/cache invalidation before admission;explicit scoped
  * flushes also require idle. Global cancel/invalidate drains all accepted work.
  * cancelGroup poisons only matching identity/tag rows,without stopping other
  * groups. Accepted translations/data for that row drain before its completion.
  */
class TranslatedBankedRgba8TextureService(config: GpuConfig=GpuConfig(),
    dataBytes: Int=8192,entries: Int=32,useBlackBoxes: Boolean=false,
    rawTapOutput: Boolean=false) extends Module {
  require(config.xLen==32 && isPow2(entries) && entries>=16 && entries<=64)
  private val indexBits=log2Ceil(entries)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new BankedRgba8Sample))
    val out=Decoupled(new TranslatedBankedRgba8Result(rawTapOutput))
    val memoryRequest=Decoupled(new ComputeMemoryRequest(config,64,16))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,16)))
    val pageWalk=Decoupled(new ComputeMemoryRequest(config,64,4))
    val pageWalkResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,4)))
    val satp=Input(UInt(32.W));val flush=Flipped(Valid(new VectorTlbFlush(config)))
    val cancel=Input(Bool());val cancelGroup=Flipped(Valid(new BankedTextureGroupCancel))
    val invalidate=Input(Bool());val busy=Output(Bool())
    val occupiedFills=Output(UInt(5.W));val joined=Output(Bool())
  })
  private val backend=Module(new BankedRgba8FloatTextureService(dataBytes,16,useBlackBoxes,cachePolicyInput=true,rawTapOutput=rawTapOutput))
  private val translators=Seq.fill(4)(Module(new GraphicsAddressTranslator(config,entries=16,maxOutstanding=entries)))
  private val samples=Reg(Vec(entries,new BankedRgba8Sample))
  private val physical=Reg(Vec(entries,Vec(4,UInt(32.W))))
  private val policy=Reg(Vec(entries,Vec(4,UInt(CachePolicy.width.W))))
  private val alive=RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val received=Reg(Vec(entries,UInt(4.W)))
  private val failed=Reg(Vec(entries,UInt(4.W)));private val faultAddress=Reg(Vec(entries,UInt(32.W)))
  private val cancelled=Reg(Vec(entries,Bool()))
  private val launched=RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val completed=RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val results=Reg(Vec(entries,new TranslatedBankedRgba8Result(rawTapOutput)))
  private val head=RegInit(0.U(indexBits.W));private val tail=RegInit(0.U(indexBits.W))
  private val count=RegInit(0.U((indexBits+1).W))
  private val issueHead=RegInit(0.U(indexBits.W));private val issueCount=RegInit(0.U((indexBits+1).W))
  private val dispatch=RegInit(false.B);private val dispatchSlot=Reg(UInt(indexBits.W));private val pending=Reg(UInt(4.W))
  private val draining=RegInit(false.B);private val pinnedSatp=RegInit(0.U(32.W))
  // One public completion only: a second hidden final row could otherwise
  // escape a cancel pulse while the visible row is held.
  private val outputs=Module(new Queue(new TranslatedBankedRgba8Result(rawTapOutput),1,pipe=true))
  private val walks=Module(new Queue(new ComputeMemoryRequest(config,64,4),2))
  io.out<>outputs.io.deq
  io.memoryRequest<>backend.io.memoryRequest;backend.io.memoryResponse<>io.memoryResponse
  io.occupiedFills:=backend.io.occupiedFills;io.joined:=backend.io.joined
  io.busy:=alive.asUInt.orR || outputs.io.deq.valid || backend.io.busy || walks.io.deq.valid || translators.map(_.io.walkActive).reduce(_ || _)
  private val vmChanged=io.satp=/=pinnedSatp
  private val automaticFlush= !io.busy && vmChanged
  private val flush=io.flush.valid || automaticFlush
  private val stop=io.cancel || io.invalidate || draining
  when(io.busy){assert(!vmChanged,"texture VM epoch changed before final retirement")}
  when(io.flush.valid){assert(!io.busy,"texture translation flush requires complete drain")}
  when(automaticFlush){pinnedSatp:=io.satp}
  backend.io.cancel:=io.cancel
  backend.io.invalidate:=io.invalidate || draining || flush
  when((io.cancel || io.invalidate) && io.busy){draining:=true.B}
  when(!io.busy){draining:=false.B}

  private val walkArbiter=Module(new RRArbiter(new ComputeMemoryRequest(config,64,entries),4))
  walks.io.enq.valid:=walkArbiter.io.out.valid;walks.io.enq.bits:=walkArbiter.io.out.bits
  walkArbiter.io.out.ready:=walks.io.enq.ready
  io.pageWalk<>walks.io.deq
  private val responseReady=Wire(Vec(4,Bool()))
  private val sent=Wire(Vec(4,Bool()))
  private def scopedCancel(e: UInt): Bool=io.cancelGroup.valid &&
    io.cancelGroup.bits.identity.asUInt===samples(e).identity.asUInt && io.cancelGroup.bits.tag===samples(e).tag
  private val dispatchStopped=stop || cancelled(dispatchSlot) || scopedCancel(dispatchSlot) || failed(dispatchSlot).orR
  for(p<-0 until 4){
    val t=translators(p)
    t.io.satp:=pinnedSatp
    t.io.flush.valid:=flush;t.io.flush.bits:=io.flush.bits
    when(automaticFlush){t.io.flush.bits:=0.U.asTypeOf(t.io.flush.bits)}
    t.io.pageWalkTransactionId:=p.U
    walkArbiter.io.in(p)<>t.io.pageWalk
    t.io.pageWalkResp.valid:=io.pageWalkResponse.valid && io.pageWalkResponse.bits.transactionId===p.U
    t.io.pageWalkResp.bits:=io.pageWalkResponse.bits
    responseReady(p):=t.io.pageWalkResp.ready && io.pageWalkResponse.bits.transactionId===p.U
    t.io.in.valid:=dispatch && pending(p) && !dispatchStopped
    t.io.in.bits:=0.U.asTypeOf(t.io.in.bits)
    t.io.in.bits.address:=samples(dispatchSlot).addresses(p)
    t.io.in.bits.sizeLog2:=2.U;t.io.in.bits.byteMask:=15.U
    t.io.in.bits.transactionId:=dispatchSlot
    t.io.out.ready:=true.B;t.io.faultResponse.ready:=true.B
    sent(p):=t.io.in.fire
    when(t.io.out.fire){
      val e=t.io.out.bits.transactionId
      assert(alive(e) && !received(e)(p),"translated tap lost its admission owner")
      physical(e)(p):=t.io.out.bits.address;policy(e)(p):=t.io.out.bits.cachePolicy
    }
    when(t.io.faultResponse.fire){
      val e=t.io.faultResponse.bits.transactionId
      assert(alive(e) && !received(e)(p),"translation fault lost its tap owner")
    }
  }
  io.pageWalkResponse.ready:=responseReady.asUInt.orR
  private val remaining=pending & ~sent.asUInt
  when(dispatch){pending:=remaining;when(remaining===0.U){dispatch:=false.B}}
  when(dispatch && dispatchStopped){pending:=0.U;dispatch:=false.B}
  for(e<-0 until entries){
    val returned=VecInit((0 until 4).map{p=>val t=translators(p)
      (t.io.out.fire && t.io.out.bits.transactionId===e.U) ||
        (t.io.faultResponse.fire && t.io.faultResponse.bits.transactionId===e.U)}).asUInt
    val faults=VecInit((0 until 4).map{p=>val t=translators(p)
      t.io.faultResponse.fire && t.io.faultResponse.bits.transactionId===e.U}).asUInt
    val abandoned=Mux(dispatch && dispatchSlot===e.U && dispatchStopped,pending,0.U)
    when(alive(e)){
      received(e):=received(e)|returned|abandoned
      when(stop || scopedCancel(e.U)){cancelled(e):=true.B}
      when(faults.orR){failed(e):=failed(e)|faults
        when(!failed(e).orR){faultAddress(e):=samples(e).addresses(PriorityEncoder(faults))}}
    }
  }

  private val readyGroup=issueCount=/=0.U && received(issueHead)===15.U
  private val rejected=failed(issueHead).orR || cancelled(issueHead) || scopedCancel(issueHead) || stop
  backend.io.in.valid:=readyGroup && !rejected
  backend.io.in.bits:=0.U.asTypeOf(backend.io.in.bits)
  backend.io.in.bits.identity:=samples(issueHead).identity
  backend.io.in.bits.textureKey:=samples(issueHead).textureKey
  backend.io.in.bits.weights:=samples(issueHead).weights
  backend.io.in.bits.tag:=issueHead
  private val activeTaps=VecInit(samples(issueHead).weights.map(_=/=0.U)).asUInt
  private val firstActive=PriorityEncoder(activeTaps)
  for(p<-0 until 4){
    val selected=Mux(activeTaps(p),p.U,firstActive)
    backend.io.in.bits.addresses(p):=physical(issueHead)(selected)
    backend.io.in.bits.rowParity(p):=samples(issueHead).rowParity(selected)
  }
  backend.io.in.bits.noCache.get:=VecInit((0 until 4).map(p=>activeTaps(p) && !CachePolicy.isCacheable(policy(issueHead)(p)))).asUInt.orR
  private val issue=readyGroup && (rejected || backend.io.in.ready)
  when(issue){
    assert(alive(issueHead) && !launched(issueHead),"texture group dispatch lost its admission owner")
    issueHead:=issueHead+1.U
    when(rejected){
      completed(issueHead):=true.B
      results(issueHead):=0.U.asTypeOf(results(issueHead))
      results(issueHead).identity:=samples(issueHead).identity;results(issueHead).tag:=samples(issueHead).tag
      results(issueHead).fault:=failed(issueHead).orR
      results(issueHead).faultSource:=Mux(failed(issueHead).orR,1.U,0.U)
      results(issueHead).faultAddress:=Mux(failed(issueHead).orR,faultAddress(issueHead),0.U)
      results(issueHead).missingTaps:=failed(issueHead)
      results(issueHead).cancelled:=cancelled(issueHead) || scopedCancel(issueHead) || stop
    }.otherwise{launched(issueHead):=true.B}
  }
  backend.io.out.ready:=true.B
  when(backend.io.out.fire){
    val b=backend.io.out.bits;val e=b.tag(indexBits-1,0)
    assert(b.tag<entries.U && alive(e) && launched(e) && !completed(e),"physical sampler reply lost its virtual owner")
    completed(e):=true.B
    results(e):=0.U.asTypeOf(results(e));results(e).identity:=samples(e).identity;results(e).tag:=samples(e).tag
    results(e).values:=b.values;results(e).hit:=b.hit;results(e).fault:=b.fault
    results(e).rawTaps.foreach(_:=b.rawTaps.get)
    results(e).cancelled:=b.cancelled;results(e).missingTaps:=b.missingTaps
    results(e).faultSource:=Mux(b.fault,2.U,0.U)
    val matches=VecInit((0 until 4).map(p=>samples(e).weights(p)=/=0.U && physical(e)(p)===b.faultAddress)).asUInt
    when(b.fault){assert(matches.orR,"physical texture fault has no original virtual tap")}
    results(e).faultAddress:=Mux(b.fault,samples(e).addresses(PriorityEncoder(matches)),0.U)
  }

  private val rowStopped=cancelled(head) || scopedCancel(head) || stop || results(head).cancelled
  outputs.io.enq.valid:=alive(head) && completed(head)
  outputs.io.enq.bits:=results(head)
  outputs.io.enq.bits.hit:=results(head).hit && !rowStopped
  outputs.io.enq.bits.cancelled:=rowStopped
  outputs.io.enq.bits.missingTaps:=Mux(rowStopped,0.U,results(head).missingTaps)
  for(c<-0 until 4)outputs.io.enq.bits.values(c):=Mux(rowStopped,0.U,results(head).values(c))
  outputs.io.enq.bits.rawTaps.foreach{v=>for(t<-0 until 4)v(t):=Mux(rowStopped,0.U,results(head).rawTaps.get(t))}
  private val retire=outputs.io.enq.fire
  when(retire){alive(head):=false.B;head:=head+1.U}
  io.in.ready:= !stop && !flush && !vmChanged && count<entries.U && (!dispatch || remaining===0.U)
  when(io.in.fire){
    assert(io.in.bits.weights.reduce(_+& _)===65536.U,"admitted texture weights must sum to one")
    val active=VecInit(io.in.bits.weights.map(_=/=0.U)).asUInt
    for(p<-0 until 4){when(active(p)){assert(io.in.bits.addresses(p)(1,0)===0.U,"admitted RGBA8 span must be word aligned")}}
    samples(tail):=io.in.bits;physical(tail):=0.U.asTypeOf(physical(tail));policy(tail):=0.U.asTypeOf(policy(tail))
    alive(tail):=true.B;received(tail):= ~active;failed(tail):=0.U;faultAddress(tail):=0.U
    cancelled(tail):=io.cancelGroup.valid && io.cancelGroup.bits.identity.asUInt===io.in.bits.identity.asUInt &&
      io.cancelGroup.bits.tag===io.in.bits.tag;launched(tail):=false.B;completed(tail):=false.B
    tail:=tail+1.U;dispatch:=true.B;dispatchSlot:=tail;pending:=active
  }
  when(io.in.fire =/= retire){count:=Mux(io.in.fire,count+1.U,count-1.U)}
  when(io.in.fire =/= issue){issueCount:=Mux(io.in.fire,issueCount+1.U,issueCount-1.U)}
}
