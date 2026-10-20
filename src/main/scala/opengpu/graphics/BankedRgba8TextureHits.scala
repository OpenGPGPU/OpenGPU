package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse}

class BankedRgba8Sample(cachePolicyInput: Boolean=false) extends Bundle {
  val identity=new GraphicsWorkIdentity;val textureKey=UInt(64.W);val tag=UInt(32.W)
  val addresses=Vec(4,UInt(32.W));val rowParity=Vec(4,Bool());val weights=Vec(4,UInt(17.W))
  val noCache=if(cachePolicyInput)Some(Bool())else None
}
class BankedRgba8Result(readThrough: Boolean=false,weightedOutput: Boolean=false) extends Bundle {
  val identity=new GraphicsWorkIdentity;val tag=UInt(32.W)
  val hit=Bool();val cancelled=Bool();val missingTaps=UInt(4.W)
  /** Logical packed RGBA:R at bits31:24,unlike little-endian input texels. */
  val color=UInt(32.W)
  val fault=if(readThrough)Some(Bool())else None
  val faultAddress=if(readThrough)Some(UInt(32.W))else None
  val weightedSums=if(weightedOutput)Some(Vec(4,UInt(24.W)))else None
}
/** Resident RGBA8 bilinear service:four banked taps and a two-stage weighted
  * filter,with bounded sample association and duplicate-tap coalescing.
  * Conflict-free resident samples can enter/retire every cycle after warm-up.
  * A miss is explicitly returned for the enclosing translated miss/fallback
  * owner;it is never successful zero texture data or an architectural fault.
  * Metadata/coordinate/format admission and sixteen-fill scheduling remain
  * outside this block. It is not yet wired into the general shader sampler.
  */
class BankedRgba8TextureHits(dataBytes: Int=8192,entries: Int=16,useBlackBoxes: Boolean=false,
    readThrough: Boolean=false,weightedOutput: Boolean=false,cachePolicyInput: Boolean=false) extends Module {
  require(!cachePolicyInput || readThrough, "page policy requires read-through ownership")
  require(isPow2(entries) && entries>=4 && entries<=64)
  private val slotBits=log2Ceil(entries)
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new BankedRgba8Sample(cachePolicyInput)));val out=Decoupled(new BankedRgba8Result(readThrough,weightedOutput))
    val refill=Flipped(Decoupled(new BankedTextureRefill));val refillDone=Decoupled(new BankedTextureRefillCompletion)
    val invalidate=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
    val tapIssues=Output(UInt(3.W));val bankConflicts=Output(UInt(3.W))
    val memoryRequest=if(readThrough)Some(Decoupled(new ComputeMemoryRequest(GpuConfig(),64,16)))else None
    val memoryResponse=if(readThrough)Some(Flipped(Decoupled(new ComputeMemoryResponse(64,16))))else None
    val occupiedFills=if(readThrough)Some(Output(UInt(5.W)))else None
    val joined=if(readThrough)Some(Output(Bool()))else None
  })
  private val lookups=Wire(Vec(4,Decoupled(new BankedTextureLookup(slotBits+2,cachePolicyInput))))
  private val replies=Wire(Vec(4,Decoupled(new BankedTextureReadResult(slotBits+2))))
  private val serviceBusy=Wire(Bool())
  private val stop=io.cancel || (if(readThrough)io.invalidate else false.B)
  if(readThrough){
    val service=Module(new BankedTextureWordService(GpuConfig(),dataBytes,slotBits+2,useBlackBoxes=useBlackBoxes,cachePolicyInput=cachePolicyInput))
    service.io.in<>lookups;replies<>service.io.out
    io.memoryRequest.get<>service.io.memoryRequest;service.io.memoryResponse<>io.memoryResponse.get
    service.io.cancel:=io.cancel;service.io.invalidate:=io.invalidate
    serviceBusy:=service.io.busy;io.tapIssues:=service.io.tapIssues;io.bankConflicts:=service.io.bankConflicts
    io.occupiedFills.get:=service.io.occupiedFills;io.joined.get:=service.io.joined
    io.refill.ready:=false.B;io.refillDone.valid:=false.B;io.refillDone.bits:=0.U.asTypeOf(io.refillDone.bits)
  }else{
    val cache=Module(new BankedTextureLineStore(dataBytes,slotBits+2,useBlackBoxes))
    cache.io.in<>lookups;cache.io.invalidate:=io.invalidate || io.cancel
    cache.io.refill.valid:=io.refill.valid && !io.cancel;cache.io.refill.bits:=io.refill.bits
    io.refill.ready:=cache.io.refill.ready && !io.cancel;io.refillDone<>cache.io.refillDone
    serviceBusy:=cache.io.busy;io.tapIssues:=cache.io.readIssues;io.bankConflicts:=cache.io.conflicts
    for(b<-0 until 4){
      replies(b).valid:=cache.io.out(b).valid;cache.io.out(b).ready:=replies(b).ready
      replies(b).bits:=0.U.asTypeOf(replies(b).bits)
      replies(b).bits.tag:=cache.io.out(b).bits.tag;replies(b).bits.hit:=cache.io.out(b).bits.hit
      replies(b).bits.data:=cache.io.out(b).bits.data
    }
  }
  private val alive=RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val samples=Reg(Vec(entries,new BankedRgba8Sample(cachePolicyInput)));private val aliases=Reg(Vec(entries,Vec(4,UInt(4.W))))
  private val received=Reg(Vec(entries,UInt(4.W)));private val hitMask=Reg(Vec(entries,UInt(4.W)))
  private val data=Reg(Vec(entries,Vec(4,UInt(32.W))));private val cancelled=Reg(Vec(entries,Bool()))
  private val faults=if(readThrough)Some(Reg(Vec(entries,Bool())))else None
  private val faultAddresses=if(readThrough)Some(Reg(Vec(entries,UInt(32.W))))else None
  private val head=RegInit(0.U(slotBits.W));private val tail=RegInit(0.U(slotBits.W));private val count=RegInit(0.U((slotBits+1).W))
  private val dispatch=RegInit(false.B);private val dispatchSlot=Reg(UInt(slotBits.W));private val pending=RegInit(0.U(4.W))
  private val sent=Wire(Vec(4,Bool()))
  for(t<-0 until 4){val p=lookups(t);val s=samples(dispatchSlot)
    p.valid:=dispatch && pending(t) && !stop;p.bits.identity:=s.identity;p.bits.textureKey:=s.textureKey
    p.bits.noCache.foreach(_:=s.noCache.get)
    p.bits.address:=s.addresses(t);p.bits.rowParity:=s.rowParity(t);p.bits.tag:=Cat(dispatchSlot,t.U(2.W));sent(t):=p.fire
  }
  private val remaining=pending & ~sent.asUInt
  when(dispatch){pending:=remaining;when(remaining===0.U){dispatch:=false.B}}
  when(stop){dispatch:=false.B;pending:=0.U}
  for(b<-0 until 4)replies(b).ready:=true.B
  for(e<-0 until entries){
    val replyMasks=(0 until 4).map{b=>val r=replies(b)
      Mux(r.fire && r.bits.tag(slotBits+1,2)===e.U,aliases(e)(r.bits.tag(1,0)),0.U(4.W))}
    val hitMasks=(0 until 4).map(b=>Mux(replies(b).bits.hit,replyMasks(b),0.U(4.W)))
    val abandoned=Mux(stop && dispatch && dispatchSlot===e.U,
      (0 until 4).map(t=>Mux(pending(t),aliases(e)(t),0.U(4.W))).reduce(_|_),0.U(4.W))
    when(alive(e)){
      received(e):=received(e)|replyMasks.reduce(_|_)|abandoned
      hitMask(e):=hitMask(e)|hitMasks.reduce(_|_)
      when(stop){cancelled(e):=true.B}
    }
    if(readThrough){
      val failures=VecInit((0 until 4).map(b=>replies(b).fire && replies(b).bits.tag(slotBits+1,2)===e.U && replies(b).bits.fault))
      val poisoned=VecInit((0 until 4).map(b=>replies(b).fire && replies(b).bits.tag(slotBits+1,2)===e.U &&
        (replies(b).bits.cancelled || replies(b).bits.invalidated))).asUInt.orR
      when(alive(e) && failures.asUInt.orR){
        faults.get(e):=true.B
        when(!faults.get(e)){faultAddresses.get(e):=replies(PriorityEncoder(failures)).bits.address}
      }
      when(alive(e) && poisoned){cancelled(e):=true.B}
    }
    for(b<-0 until 4;t<-0 until 4){val r=replies(b)
      when(r.fire && r.bits.tag(slotBits+1,2)===e.U && aliases(e)(r.bits.tag(1,0))(t)){
        assert(alive(e),"texture bank reply must retain its sample owner");data(e)(t):=r.bits.data
      }
    }
  }
  private val productValid=RegInit(false.B);private val sumValid=RegInit(false.B)
  private val products=Reg(Vec(4,Vec(4,UInt(25.W))));private val productResult=Reg(new BankedRgba8Result(readThrough,weightedOutput))
  private val result=Reg(new BankedRgba8Result(readThrough,weightedOutput))
  io.out.valid:=sumValid;io.out.bits:=result
  private val sumReady= !sumValid || io.out.ready
  private val productReady= !productValid || sumReady
  private val completed=alive(head) && received(head)===15.U
  private val pop=completed && productReady
  // Cancellation owns every unoffered sample, including a filter stage held
  // behind an immutable public result. Remember a pulse until that stage drains.
  when(productValid && !sumReady && stop){
    productResult.cancelled:=true.B;productResult.hit:=false.B;productResult.missingTaps:=0.U
  }
  when(sumReady){sumValid:=productValid
    when(productValid){
      val stopped=productResult.cancelled || stop
      result:=productResult;result.cancelled:=stopped;result.hit:=productResult.hit && !stopped
      result.missingTaps:=Mux(stopped,0.U,productResult.missingTaps)
      val channels=(0 until 4).map(c=>products(c).reduce(_+& _)(23,16))
      result.color:=Mux(productResult.hit && !stopped,Cat(channels),0.U)
      result.weightedSums.foreach{values=>
        for(c<-0 until 4)values(c):=Mux(productResult.hit && !stopped,products(c).reduce(_+& _)(23,0),0.U)
      }
    }
  }
  when(productReady){productValid:=completed
    when(completed){
      val stopped=cancelled(head) || stop
      productResult:=0.U.asTypeOf(productResult);productResult.identity:=samples(head).identity;productResult.tag:=samples(head).tag
      productResult.cancelled:=stopped;productResult.hit:=hitMask(head)===15.U && !stopped
      productResult.fault.foreach(_:=faults.get(head))
      productResult.faultAddress.foreach(_:=Mux(faults.get(head),faultAddresses.get(head),0.U))
      productResult.missingTaps:=Mux(stopped,0.U,(~hitMask(head))(3,0))
      for(c<-0 until 4;t<-0 until 4)products(c)(t):=data(head)(t)(c*8+7,c*8)*samples(head).weights(t)
    }
  }
  when(pop){alive(head):=false.B;head:=head+1.U}
  io.in.ready:= !stop && (count<entries.U || pop) && (!dispatch || remaining===0.U)
  private def sameInput(a: Int,b: Int): Bool=io.in.bits.addresses(a)===io.in.bits.addresses(b) && io.in.bits.rowParity(a)===io.in.bits.rowParity(b)
  private val unique=VecInit((0 until 4).map(t=> !(0 until t).map(j=>sameInput(j,t)).foldLeft(false.B)(_ || _)))
  when(io.in.fire){
    assert(io.in.bits.weights.reduce(_+& _)===65536.U,"bilinear weights must preserve the exact unit sum")
    for(t<-0 until 4){
      def same(j: Int): Bool=io.in.bits.addresses(j)===io.in.bits.addresses(t) && io.in.bits.rowParity(j)===io.in.bits.rowParity(t)
      aliases(tail)(t):=VecInit((0 until 4).map(same)).asUInt
    }
    samples(tail):=io.in.bits;received(tail):=0.U;hitMask(tail):=0.U;cancelled(tail):=false.B
    faults.foreach(_(tail):=false.B);faultAddresses.foreach(_(tail):=0.U)
    alive(tail):=true.B;dispatch:=true.B;dispatchSlot:=tail;pending:=unique.asUInt;tail:=tail+1.U
  }
  when(io.in.fire =/= pop){count:=Mux(io.in.fire,count+1.U,count-1.U)}
  io.busy:=count=/=0.U || dispatch || productValid || sumValid || serviceBusy
}
