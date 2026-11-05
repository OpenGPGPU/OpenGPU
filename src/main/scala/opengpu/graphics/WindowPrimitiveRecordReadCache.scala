package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.{CachePolicy,GpuConfig}
import opengpu.core.memory.{ComputeMemoryRequest,ComputeMemoryResponse,L2SramArray}

/** Sidebands come from an already bounds-checked record reader. The pass
  * owner retains one immutable arena/VM binding until every port drains.
  */
class WindowPrimitiveRecordLine(config: GpuConfig,transactions: Int,lineBits: Int) extends Bundle {
  val request=new ComputeMemoryRequest(config,64,transactions)
  val handle=UInt(32.W);val line=UInt(lineBits.W)
}

/** Shared raw-line reuse, never a decoded-record shortcut. Four independent
  * miss ports preserve the original arbiter and uncached request/reply path.
  * Hits share one synchronous SRAM read; each port reserves its own response.
  * Clear is an idle-only pass boundary. Cancel/fault poison reuse and fills,
  * while irrevocable misses, accepted reads and held replies still drain.
  */
class WindowPrimitiveRecordReadCache(config: GpuConfig=GpuConfig(),engines: Int=4,
    recordLines: Int=23,transactions: Int=18,firstReaderId: Int=14,
    useSramBlackBoxes: Boolean=false,shareMisses: Boolean=true) extends Module {
  require(config.xLen==32 && engines>=1 && engines<=4)
  require(recordLines>=2 && recordLines<=32)
  require(firstReaderId>=0 && firstReaderId+engines<=transactions)
  val entries=16
  private val slotBits=log2Ceil(entries);val lineBits=log2Ceil(recordLines)
  private val entryLines=1<<lineBits;private val totalLines=entries*entryLines
  private val bankDepth=math.min(256,totalLines);private val banksCount=totalLines/bankDepth
  private val bankBits=log2Ceil(banksCount).max(1);private val engineBits=log2Ceil(engines).max(1)
  val dataBytes=totalLines*64
  val io=IO(new Bundle {
    val request=Vec(engines,Flipped(Decoupled(new WindowPrimitiveRecordLine(config,transactions,lineBits))))
    val response=Vec(engines,Decoupled(new ComputeMemoryResponse(64,transactions)))
    val memoryRequest=Vec(engines,Decoupled(new ComputeMemoryRequest(config,64,transactions)))
    val memoryResponse=Flipped(Decoupled(new ComputeMemoryResponse(64,transactions)))
    val hitAccepted=Output(Vec(engines,Bool()));val missAccepted=Output(Vec(engines,Bool()))
    val sharedAccepted=Output(Vec(engines,Bool()))
    val clear=Input(Bool());val cancel=Input(Bool());val busy=Output(Bool())
  })
  private val tags=Reg(Vec(entries,UInt((32-slotBits).W)))
  private val valid=RegInit(VecInit(Seq.fill(entries)(0.U(entryLines.W))))
  private val poisoned=RegInit(false.B)
  private val reserved=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val missPending=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val waiting=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val leader=Reg(Vec(engines,UInt(engineBits.W)))
  private val missOffered=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val missHandle=Reg(Vec(engines,UInt(32.W)))
  private val missLine=Reg(Vec(engines,UInt(lineBits.W)))
  private val missAddress=Reg(Vec(engines,UInt(32.W)))
  private val replyValid=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val reply=Reg(Vec(engines,new ComputeMemoryResponse(64,transactions)))
  private val hitPending=RegInit(false.B)
  private val hitEngine=Reg(UInt(engineBits.W));private val hitBank=Reg(UInt(bankBits.W))
  private val memories=Seq.fill(banksCount)(Module(new L2SramArray(bankDepth,512,useSramBlackBoxes)))
  private val returning=VecInit((0 until engines).map(e=>
    io.memoryResponse.valid && io.memoryResponse.bits.transactionId===(firstReaderId+e).U && missPending(e)))
  io.memoryResponse.ready:=returning.asUInt.orR
  private val incomingEngine=PriorityEncoder(returning.asUInt)
  private val incoming=VecInit((0 until engines).map(e=>returning.asUInt.orR && waiting(e) && leader(e)===incomingEngine))
  private val fault=io.memoryResponse.fire && io.memoryResponse.bits.fault
  private val fill=io.memoryResponse.fire && !io.memoryResponse.bits.fault && !poisoned && !io.cancel && !io.clear
  private val fillSlot=missHandle(incomingEngine)(slotBits-1,0)
  private val fillAddress=Cat(fillSlot,missLine(incomingEngine))
  private val fillBank=if(banksCount==1)0.U else fillAddress>>8
  private val hits=Module(new RRArbiter(UInt(engineBits.W),engines))
  private val hitAddress=Wire(UInt(log2Ceil(totalLines).W))
  private val lookupBank=if(banksCount==1)0.U else hitAddress>>8
  // Filter bank conflicts before arbitration. Feeding the chosen address
  // back into ready serializes tag lookup, arbitration and bank selection.
  hits.io.out.ready:= !io.clear
  hitAddress:=VecInit(io.request.map(q=>Cat(q.bits.handle(slotBits-1,0),q.bits.line)))(hits.io.chosen)
  private val readData=if(banksCount==1)memories.head.io.readData else VecInit(memories.map(_.io.readData))(hitBank)
  private val lineHits=VecInit((0 until engines).map{e=>val q=io.request(e);val slot=q.bits.handle(slotBits-1,0)
    !poisoned && !io.cancel && !missOffered(e) && q.bits.line<recordLines.U &&
      tags(slot)===q.bits.handle(31,slotBits) && valid(slot)(q.bits.line)})
  private val available=VecInit((0 until engines).map(e=> !reserved(e) && !io.clear))
  private val shareEnabled=shareMisses.B && !poisoned && !io.cancel && !io.clear
  for((memory,b)<-memories.zipWithIndex){
    memory.io.readEnable:=hits.io.out.fire && lookupBank===b.U
    memory.io.readAddress:=hitAddress(log2Ceil(bankDepth)-1,0)
    memory.io.writeEnable:=fill && fillBank===b.U
    memory.io.writeAddress:=fillAddress(log2Ceil(bankDepth)-1,0)
    memory.io.writeData:=io.memoryResponse.bits.readData
  }
  hitPending:=hits.io.out.fire
  when(hits.io.out.fire){hitEngine:=hits.io.out.bits;hitBank:=lookupBank}
  for(e<-0 until engines){
    val q=io.request(e);val out=io.memoryRequest(e)
    val hit=lineHits(e);val free=available(e)
    val inflightMatches=VecInit((0 until engines).map(k=>missPending(k) && !returning(k) &&
      missHandle(k)===q.bits.handle && missLine(k)===q.bits.line && missAddress(k)===q.bits.request.address))
    val inflight=shareEnabled && !missOffered(e) && inflightMatches.asUInt.orR
    val priorLeader=PriorityEncoder(inflightMatches.asUInt)
    val sameKey=VecInit((0 until engines).map(k=>io.request(k).bits.handle===q.bits.handle &&
      io.request(k).bits.line===q.bits.line && io.request(k).bits.request.address===q.bits.request.address))
    val offeredMatches=VecInit((0 until engines).map(k=>sameKey(k) && io.request(k).valid && available(k) && missOffered(k)))
    val waveMatches=VecInit((0 until engines).map(k=>sameKey(k) && io.request(k).valid && available(k) && !lineHits(k)))
    val waveLeader=Mux(offeredMatches.asUInt.orR,PriorityEncoder(offeredMatches.asUInt),PriorityEncoder(waveMatches.asUInt))
    val waveFollower=shareEnabled && !missOffered(e) && !inflight && waveLeader=/=e.U
    val waveAccepted=if(engines==1)io.memoryRequest.head.fire else io.memoryRequest(waveLeader).fire
    val candidateAddress=Cat(q.bits.handle(slotBits-1,0),q.bits.line)
    val candidateBank=if(banksCount==1)0.U else candidateAddress>>8
    val fillConflict=fill && fillBank===candidateBank
    hits.io.in(e).valid:=q.valid && free && hit && !fillConflict;hits.io.in(e).bits:=e.U
    out.valid:=q.valid && free && !hit && !inflight && !waveFollower;out.bits:=q.bits.request
    q.ready:=free && Mux(hit,hits.io.in(e).ready && !fillConflict,Mux(inflight,true.B,
      Mux(waveFollower,waveAccepted,out.ready)))
    io.hitAccepted(e):=q.fire && hit;io.missAccepted(e):=out.fire
    io.sharedAccepted(e):=q.fire && !hit && !out.fire
    when(out.valid && !out.ready){missOffered(e):=true.B}
    when(out.fire){missOffered(e):=false.B;missPending(e):=true.B
      missHandle(e):=q.bits.handle;missLine(e):=q.bits.line;missAddress(e):=q.bits.request.address}
    when(q.fire){
      reserved(e):=true.B
      when(!hit){waiting(e):=true.B;leader(e):=Mux(inflight,priorLeader,Mux(waveFollower,waveLeader,e.U))}
      assert(!q.bits.request.isWrite && q.bits.request.address(5,0)===0.U &&
        q.bits.request.sizeLog2===6.U && q.bits.request.byteMask.andR &&
        q.bits.request.cachePolicy===CachePolicy.uncached && !q.bits.request.cacheClient && !q.bits.request.cacheResident,
        "record cache accepts only complete uncached reader lines")
      assert(q.bits.request.transactionId===(firstReaderId+e).U && q.bits.line<recordLines.U,
        "record cache request sidebands do not identify their reader line")
    }
    val fromHit=hitPending && hitEngine===e.U
    val direct=Wire(new ComputeMemoryResponse(64,transactions))
    direct:=io.memoryResponse.bits;direct.transactionId:=(firstReaderId+e).U
    when(fromHit){direct.readData:=readData;direct.fault:=false.B;direct.transactionId:=(firstReaderId+e).U}
    io.response(e).valid:=replyValid(e) || fromHit || incoming(e)
    io.response(e).bits:=Mux(replyValid(e),reply(e),direct)
    when((fromHit || incoming(e)) && !io.response(e).ready){reply(e):=direct;replyValid(e):=true.B}
    when(incoming(e) && io.memoryResponse.fire){missPending(e):=false.B;waiting(e):=false.B}
    when(io.response(e).fire){replyValid(e):=false.B;reserved(e):=false.B}
    when(fromHit || incoming(e)){assert(reserved(e) && !replyValid(e),"record cache reply lost its reservation")}
    when(fromHit){assert(!missPending(e),"record cache hit overlaps a pending miss")}
    when(missOffered(e)){assert(q.valid && !reserved(e),"offered record miss cannot be withdrawn")}
    val ownerPending=if(engines==1)missPending.head else missPending(leader(e))
    when(waiting(e)){assert(reserved(e) && ownerPending,"shared record miss lost its physical owner")}
  }
  io.busy:=reserved.asUInt.orR || missOffered.asUInt.orR || hitPending
  when(io.clear){
    assert(!io.busy && !waiting.asUInt.orR && !missPending.asUInt.orR && !io.request.map(_.valid).reduce(_ || _),"record cache clear cannot abandon owned work")
    valid.foreach(_:=0.U);poisoned:=false.B
  }.elsewhen(io.cancel || fault){valid.foreach(_:=0.U);poisoned:=true.B}
    .elsewhen(fill){
      val tag=missHandle(incomingEngine)(31,slotBits)
      tags(fillSlot):=tag
      valid(fillSlot):=Mux(tags(fillSlot)===tag,valid(fillSlot),0.U) | UIntToOH(missLine(incomingEngine),entryLines)
    }
  when(io.memoryResponse.valid){assert(returning.asUInt.orR,"record cache response has no owned miss")}
}
