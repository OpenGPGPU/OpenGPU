package opengpu.graphics

import chisel3._
import chisel3.util._

class TypedSampleTerminal extends Bundle {
  val status=UInt(3.W);val faultOffset=UInt(17.W)
  val tested=UInt(4.W);val passing=UInt(4.W);val stencilRejected=UInt(4.W);val depthRejected=UInt(4.W)
  val colorWritten=UInt(4.W);val depthWritten=UInt(4.W);val stencilWritten=UInt(4.W)
  val dsReads=UInt(3.W);val dsFills=UInt(2.W)
}
class TypedSamplePacketWork(tokenBits: Int) extends Bundle {
  val token=UInt(tokenBits.W);val packet=new TypedSamplePacket
}
class TypedSamplePacketDone(tokenBits: Int) extends Bundle {
  val token=UInt(tokenBits.W);val result=new TypedSampleTerminal
}
class TypedSamplePacketRetired extends Bundle {
  val tag=UInt(16.W);val packet=UInt(2.W);val last=Bool();val channelMask=UInt(4.W)
  val result=new TypedSampleTerminal
}

/** Overlapped packet ownership for one typed tile. Word reservations are taken
  * before work is offered and held until the worker's terminal acknowledgement.
  * A worker returns a terminal only after every memory/arithmetic obligation
  * for that token has drained. Completions may reorder; retirement is ordered.
  *
  * Input is an INTERNAL owned-packet drain port: it keeps accepting through
  * cancellation so accepted upstream native quads can finish packetization.
  * The native-quad frontend stops new public admissions. The worker also sees
  * global cancel; already offered work and already formed retirement stay held.
  */
class TypedSamplePacketWindow(entries: Int=128,addressBits: Int=14) extends Module {
  require(isPow2(entries) && entries>=4 && entries<=128)
  require(addressBits>=1 && addressBits<=32)
  private val slotBits=log2Ceil(entries);private val tokenBits=slotBits+1
  val io=IO(new Bundle {
    val begin=Input(Bool());val cancel=Input(Bool())
    val in=Flipped(Decoupled(new TypedSamplePacket))
    val work=Decoupled(new TypedSamplePacketWork(tokenBits))
    val complete=Flipped(Decoupled(new TypedSamplePacketDone(tokenBits)))
    val out=Decoupled(new TypedSamplePacketRetired)
    val drained=Output(Bool());val cancelSource=Output(Bool())
  })
  private val bound=RegInit(false.B);private val aborted=RegInit(false.B)
  private val stop=aborted || io.cancel
  private val input=Module(new Queue(new TypedSamplePacket,2))
  // Registered capacity keeps worker and retirement readiness out of the
  // reservation-count update. Two entries still permit one transfer per cycle
  // at steady occupancy, including simultaneous enqueue and dequeue.
  private val work=Module(new Queue(new TypedSamplePacketWork(tokenBits),2))
  private val output=Module(new Queue(new TypedSamplePacketRetired,2))
  private val head=RegInit(0.U(slotBits.W));private val tail=RegInit(0.U(slotBits.W))
  private val valid=RegInit(0.U(entries.W));private val issued=RegInit(0.U(entries.W))
  private val done=RegInit(0.U(entries.W));private val locked=RegInit(0.U(entries.W))
  private val epoch=RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val words=Reg(Vec(entries,Vec(4,UInt(addressBits.W))))
  private val masks=Reg(Vec(entries,UInt(4.W)))
  private val tags=Reg(Vec(entries,UInt(16.W)));private val packets=Reg(Vec(entries,UInt(2.W)))
  private val last=Reg(Vec(entries,Bool()));private val channels=Reg(Vec(entries,UInt(4.W)))
  private val cancelled=Reg(Vec(entries,Bool()))
  private val results=Reg(Vec(entries,new TypedSampleTerminal))
  io.work<>work.io.deq;io.out<>output.io.deq
  io.cancelSource:=bound && stop
  input.io.enq.valid:=io.in.fire;input.io.enq.bits:=io.in.bits
  io.in.ready:=bound && !io.begin && input.io.enq.ready

  private val oldest=valid(head) && done(head)
  output.io.enq.valid:=oldest && output.io.enq.ready
  output.io.enq.bits.tag:=tags(head);output.io.enq.bits.packet:=packets(head)
  output.io.enq.bits.last:=last(head);output.io.enq.bits.channelMask:=channels(head)
  output.io.enq.bits.result:=results(head)
  output.io.enq.bits.result.status:=Mux((stop || cancelled(head)) && results(head).status===0.U,4.U,results(head).status)
  private val retire=output.io.enq.fire

  private val incoming=input.io.deq.bits
  private val accesses=Wire(Vec(4,Bool()))
  for(pair<-0 until 2){
    accesses(pair):=incoming.colorActive(pair*2+1,pair*2).orR
    accesses(pair+2):=incoming.active(pair*2+1,pair*2).orR &&
      (incoming.tests.depthEnable || incoming.tests.stencilEnable)
  }
  private val needsLocks= !stop && !incoming.cancelled
  private val mask=Mux(needsLocks,accesses.asUInt,0.U(4.W))
  // Compute address conflicts independently of cancellation. Apply its one-bit
  // qualification after the reduction, outside the full reservation fanout.
  private val accessedConflict=VecInit((for(s<-0 until entries;a<-0 until 4;b<-0 until 4)yield
    locked(s) && masks(s)(b) && accesses(a) && words(s)(b)===incoming.words(a)(addressBits-1,0))).asUInt.orR
  private val conflict=needsLocks && accessedConflict
  // Allocation and retirement advance in order, so valid ownership is one
  // contiguous circular interval. Tail can name a live owner only when full.
  // Reuse this canonical ownership bit instead of maintaining a redundant
  // binary occupancy counter on the late cancellation/allocation path.
  private val room= !valid(tail) || retire
  // Begin requires complete local drain, excluding both a queued input and
  // any live completion owner. Keep rebind admission out of these owned
  // handshakes; the public input retains its explicit begin-cycle exclusion.
  input.io.deq.ready:=bound && room && work.io.enq.ready && !conflict
  private val allocate=input.io.deq.fire
  work.io.enq.valid:=allocate;work.io.enq.bits.token:=Cat(!epoch(tail),tail)
  work.io.enq.bits.packet:=incoming
  work.io.enq.bits.packet.cancelled:=incoming.cancelled || stop
  when(allocate){
    assert(!valid(tail) || (retire && head===tail),"sample packet slot reused before retirement capture")
    tags(tail):=incoming.tag;packets(tail):=incoming.packet;last(tail):=incoming.last
    channels(tail):=incoming.channelMask;cancelled(tail):=incoming.cancelled || stop
    masks(tail):=mask;epoch(tail):= !epoch(tail)
    for(w<-0 until 4){
      words(tail)(w):=incoming.words(w)
      if(addressBits<32)when(mask(w)){assert(!incoming.words(w)(31,addressBits).orR,"sample word address exceeds reservation width")}
      for(v<-0 until w)when(mask(w) && mask(v)){
        assert(incoming.words(w)=/=incoming.words(v),"one sample packet contains conflicting word owners")
      }
    }
    tail:=tail+1.U
  }
  private val workSlot=io.work.bits.token(slotBits-1,0)
  when(io.work.fire){
    assert(valid(workSlot) && !issued(workSlot) && epoch(workSlot)===io.work.bits.token(slotBits),
      "sample work offer lost its reserved generation")
  }
  private val completedSlot=io.complete.bits.token(slotBits-1,0)
  private val completionOwned=valid(completedSlot) && issued(completedSlot) && !done(completedSlot) &&
    epoch(completedSlot)===io.complete.bits.token(slotBits)
  io.complete.ready:=bound && completionOwned
  when(io.complete.valid && bound && !io.begin){assert(completionOwned,"sample terminal has no live issued generation")}
  when(io.complete.fire){results(completedSlot):=io.complete.bits.result}

  private val allocated=Mux(allocate,UIntToOH(tail,entries),0.U(entries.W))
  private val dispatched=Mux(io.work.fire,UIntToOH(workSlot,entries),0.U(entries.W))
  private val completed=Mux(io.complete.fire,UIntToOH(completedSlot,entries),0.U(entries.W))
  private val retired=Mux(retire,UIntToOH(head,entries),0.U(entries.W))
  valid:=(valid& ~retired)|allocated
  issued:=((issued|dispatched)& ~retired)& ~allocated
  done:=((done|completed)& ~retired)& ~allocated
  locked:=((locked& ~completed)& ~retired)|Mux(allocate && mask.orR,allocated,0.U(entries.W))
  when(retire){head:=head+1.U}
  assert(!valid(tail) || valid.andR,"sample tail names an owner before full capacity")
  assert(valid(head) || !valid.orR,"sample head lost the oldest live owner")
  when(io.cancel){aborted:=true.B}
  io.drained:= !valid.orR && !input.io.deq.valid && !work.io.deq.valid && !output.io.deq.valid
  when(io.begin){assert(io.drained && !io.cancel,"sample window begin requires complete local drain");bound:=true.B;aborted:=false.B}
  when(io.drained){assert(!valid.orR && !locked.orR,"sample window leaked an owner or word reservation")}
}
