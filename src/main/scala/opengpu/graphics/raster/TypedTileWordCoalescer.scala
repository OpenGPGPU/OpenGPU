package opengpu.graphics

import chisel3._
import chisel3.util._

private class QueuedTypedWord(slotBits: Int) extends Bundle {
  val request=new TypedTileWordRequest;val slot=UInt(slotBits.W)
}
private class TypedWordGroupOwner(ports: Int,slotBits: Int) extends Bundle {
  val address=UInt(32.W);val members=UInt(ports.W);val slots=Vec(ports,UInt(slotBits.W))
}

/** Coalesce equal-word reads and disjoint masked writes before SRAM banking.
  * Per-client reorder slots preserve original tags and response order even when
  * physical bank replies reorder. A word stays reserved through its physical
  * reply, preventing a younger alias from overtaking an offered operation.
  * Accepted requests are irrevocable and drain without a cancellation flush.
  */
class TypedTileWordCoalescer(ports: Int=4,slots: Int=16) extends Module {
  require(isPow2(ports) && ports>=2 && ports<=8)
  require(isPow2(slots) && slots>=4 && slots<=256)
  private val portBits=log2Ceil(ports);private val slotBits=log2Ceil(slots)
  val io=IO(new Bundle {
    val request=Vec(ports,Flipped(Decoupled(new TypedTileWordRequest)))
    val response=Vec(ports,Decoupled(new TypedTileWordResponse))
    val memoryRequest=Vec(ports,Decoupled(new TypedTileWordRequest))
    val memoryResponse=Vec(ports,Flipped(Decoupled(new TypedTileWordResponse)))
    val drained=Output(Bool())
  })
  private val inputs=Seq.fill(ports)(Module(new Queue(new QueuedTypedWord(slotBits),slots)))
  private val groups=Seq.fill(ports)(Module(new Queue(new TypedTileWordRequest,2,pipe=true)))
  private val head=RegInit(VecInit(Seq.fill(ports)(0.U(slotBits.W))))
  private val tail=RegInit(VecInit(Seq.fill(ports)(0.U(slotBits.W))))
  private val count=RegInit(VecInit(Seq.fill(ports)(0.U((slotBits+1).W))))
  private val complete=RegInit(VecInit(Seq.fill(ports)(0.U(slots.W))))
  private val results=Reg(Vec(ports,Vec(slots,new TypedTileWordResponse)))
  private val owned=RegInit(VecInit(Seq.fill(ports)(0.U(slots.W))))
  private val owners=Reg(Vec(ports,Vec(slots,new TypedWordGroupOwner(ports,slotBits))))
  private val cursor=RegInit(0.U(portBits.W))
  private val filled=Wire(Vec(ports,Vec(slots,Bool())))
  for(p<-0 until ports;s<-0 until slots)filled(p)(s):=false.B

  for(p<-0 until ports){
    val q=inputs(p);val in=io.request(p);val out=io.response(p)
    q.io.enq.valid:=in.valid && count(p)<slots.U
    q.io.enq.bits.request:=in.bits;q.io.enq.bits.slot:=tail(p)
    in.ready:=q.io.enq.ready && count(p)<slots.U
    out.valid:=count(p)=/=0.U && complete(p)(head(p));out.bits:=results(p)(head(p))
    when(in.fire){
      assert(!complete(p)(tail(p)),"typed word slot reused before retirement")
      results(p)(tail(p)).tag:=in.bits.tag;tail(p):=tail(p)+1.U
    }
    when(out.fire){head(p):=head(p)+1.U}
    switch(Cat(in.fire,out.fire)){
      is("b10".U){count(p):=count(p)+1.U}
      is("b01".U){count(p):=count(p)-1.U}
    }
  }

  // Rotate logical priority; physical group ports have independent queues.
  private val logical=VecInit((0 until ports).map(k=>(cursor+k.U)(portBits-1,0)))
  private val heads=VecInit(inputs.map(_.io.deq.bits))
  private val present=VecInit(inputs.map(_.io.deq.valid))
  private val work=VecInit((0 until ports).map(k=>heads(logical(k))))
  private val valid=VecInit((0 until ports).map(k=>present(logical(k))))
  private val consumed=Wire(Vec(ports,Vec(ports,Bool())))
  private val formed=Wire(Vec(ports,Bool()))
  var claimed: UInt=0.U(ports.W)
  var earlier=Seq.empty[(Bool,UInt)]
  for(k<-0 until ports){
    val request=work(k).request
    val busy=VecInit((for(p<-0 until ports;s<-0 until slots)yield
      owned(p)(s) && owners(p)(s).address===request.address)).asUInt.orR
    val blocked=earlier.map{case(v,a)=>v && a===request.address}.foldLeft(false.B)(_ || _)
    val eligible=valid(k) && !claimed(k) && !busy && !blocked
    var members: UInt=(BigInt(1)<<k).U(ports.W)
    var mask: UInt=request.mask
    var data: UInt=request.data&request.mask
    for(j<-k+1 until ports){
      val other=work(j).request
      val compatible=(!request.write && !other.write) ||
        (request.write && other.write && !(mask&other.mask).orR)
      val take=valid(j) && !claimed(j) && other.address===request.address && compatible
      members=members|Mux(take,(BigInt(1)<<j).U(ports.W),0.U)
      data=data|Mux(take,other.data&other.mask,0.U)
      mask=mask|Mux(take,other.mask,0.U)
    }
    val free= ~owned(k);val id=PriorityEncoder(free)
    val group=groups(k)
    formed(k):=eligible && free.orR && group.io.enq.ready
    // Only offer a formation when its registered queue can capture it.
    group.io.enq.valid:=formed(k);group.io.enq.bits:=request
    group.io.enq.bits.data:=Mux(request.write,data,request.data)
    group.io.enq.bits.mask:=Mux(request.write,mask,request.mask)
    group.io.enq.bits.tag:=id
    when(formed(k)){
      owners(k)(id).address:=request.address
      owners(k)(id).members:=VecInit((0 until ports).map(p=>
        VecInit((0 until ports).map(j=>members(j) && logical(j)===p.U)).asUInt.orR)).asUInt
      for(p<-0 until ports){
        val positions=VecInit((0 until ports).map(j=>members(j) && logical(j)===p.U))
        owners(k)(id).slots(p):=Mux1H(positions,work.map(_.slot))
      }
    }
    for(j<-0 until ports)consumed(k)(j):=formed(k) && members(j)
    claimed=claimed|Mux(formed(k),members,0.U)
    earlier=earlier:+(eligible->request.address)
    io.memoryRequest(k)<>group.io.deq

    val reply=io.memoryResponse(k);val replyId=reply.bits.tag(slotBits-1,0)
    val owner=owners(k)(replyId)
    reply.ready:=owned(k)(replyId)
    when(reply.valid){
      assert(reply.bits.tag<slots.U && owned(k)(replyId),"typed word physical reply has no owner")
    }
    when(reply.fire){
      for(p<-0 until ports)when(owner.members(p)){
        results(p)(owner.slots(p)).data:=reply.bits.data
        results(p)(owner.slots(p)).fault:=reply.bits.fault
      }
    }
    for(p<-0 until ports;s<-0 until slots){
      when(reply.fire && owner.members(p) && owner.slots(p)===s.U){filled(p)(s):=true.B}
    }
    val allocate=Mux(formed(k),UIntToOH(id,slots),0.U)
    val release=Mux(reply.fire,UIntToOH(replyId,slots),0.U)
    owned(k):=(owned(k)|allocate)& ~release
  }
  for(p<-0 until ports){
    inputs(p).io.deq.ready:=VecInit((for(k<-0 until ports;j<-0 until ports)yield
      consumed(k)(j) && logical(j)===p.U)).asUInt.orR
    val retired=Mux(io.response(p).fire,UIntToOH(head(p),slots),0.U)
    complete(p):=(complete(p)& ~retired)|filled(p).asUInt
  }
  when(formed.asUInt.orR){cursor:=cursor+1.U}
  io.drained:= !count.asUInt.orR && !owned.asUInt.orR && !VecInit(groups.map(_.io.deq.valid)).asUInt.orR
}
