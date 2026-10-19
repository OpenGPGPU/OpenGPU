package opengpu.graphics

import chisel3._
import chisel3.util._

class TileSampleQuad extends Bundle {
  val mask=UInt(4.W)
  val ticket=UInt(8.W)
  val index=Vec(4,UInt(10.W))
  val color=Vec(4,UInt(32.W))
  val depth=Vec(4,UInt(24.W))
  val state=new TileSampleMergeState
}
class TileSampleQuadCompletion extends Bundle {
  val ticket=UInt(8.W)
  val mask=UInt(4.W)
  val testsPass=UInt(4.W)
  val colorWrites=UInt(4.W)
  val depthStencilWrites=UInt(4.W)
  val canceled=UInt(4.W)
}
private class TileSampleLaneWork extends Bundle {
  val slot=UInt(3.W)
  val index=UInt(10.W)
  val color=UInt(32.W)
  val depth=UInt(24.W)
  val state=new TileSampleMergeState
}

/** Four-lane resident read/modify/write frontend for BankedTileSampleSram.
  * A packet's active lanes must address distinct banks (a native2x2 quad at
  * one sample satisfies this). Atomic admission reserves every active sample.
  * Per-bank reservations remain live through actual write fire, not arithmetic
  * completion. Eight packet contexts retire in acceptance order while the
  * lanes and SRAM banks may complete in different cycles.
  *
  * The parent owns initialized old values, attachment valid/dirty/refill and
  * exports. Begin requires complete local drain and binds the same sample mode
  * as the SRAM. Cancellation is sticky until begin: unoffered work is dropped,
  * but every already offered read/write remains stable and drains normally.
  * Completion masks distinguish committed writes from canceled samples.
  */
class TileSampleQuadMerge extends Module {
  val io=IO(new Bundle {
    val begin=Input(Bool())
    val sampleMode=Input(UInt(2.W))
    val cancel=Input(Bool())
    val in=Flipped(Decoupled(new TileSampleQuad))
    val out=Decoupled(new TileSampleQuadCompletion)
    val read=Vec(4,Decoupled(new BankedTileSampleRead))
    /** Stable head metadata while the parent services a deferred color load. */
    val readState=Output(Vec(4,new TileSampleMergeState))
    val readDepth=Output(Vec(4,UInt(24.W)))
    val response=Vec(4,Flipped(Decoupled(new BankedTileSampleReply)))
    val write=Vec(4,Decoupled(new BankedTileSampleWrite))
    val drained=Output(Bool())
    val acceptedSamples=Output(UInt(3.W))
    val committedSamples=Output(UInt(3.W))
  })
  private val mode=RegInit(0.U(2.W));private val bound=RegInit(false.B)
  private val aborted=RegInit(false.B)
  private val cancel=aborted || io.cancel
  private val head=RegInit(0.U(3.W));private val tail=RegInit(0.U(3.W))
  private val count=RegInit(0.U(4.W))
  private val tickets=Reg(Vec(8,UInt(8.W)))
  private val masks=Reg(Vec(8,UInt(4.W)))
  private val done=RegInit(VecInit(Seq.fill(8)(0.U(4.W))))
  private val passed=Reg(Vec(8,UInt(4.W)))
  private val colors=Reg(Vec(8,UInt(4.W)))
  private val depths=Reg(Vec(8,UInt(4.W)))
  private val canceled=Reg(Vec(8,UInt(4.W)))
  private val reserved=RegInit(VecInit(Seq.fill(8)(VecInit(Seq.fill(8)(false.B)))))
  private val rows=Reg(Vec(8,Vec(8,UInt(7.W))))
  private val inputs=Seq.fill(4)(Module(new Queue(new TileSampleLaneWork,8)))
  private val metadata=Seq.fill(4)(Module(new Queue(new TileSampleLaneWork,8)))
  private val merge=Seq.fill(4)(Module(new TileSampleMergePipeline))
  private val readOffered=RegInit(VecInit(Seq.fill(4)(false.B)))
  private val writeOffered=RegInit(VecInit(Seq.fill(4)(false.B)))

  private def location(index: UInt): (UInt,UInt) = {
    val sample=Mux(mode===2.U,index(1,0),Mux(mode===1.U,Cat(0.U(1.W),index(0)),0.U(2.W)))
    val pixel=Mux(mode===2.U,index>>2,Mux(mode===1.U,index>>1,index))
    val x=pixel(3,0);val y=pixel(7,4)
    (Cat(x(1),y(0)^sample(1),x(0)^sample(0)),Cat(y(3,1),x(3,2),sample))
  }
  private val locations=(0 until 4).map(p=>location(io.in.bits.index(p)))
  private val conflicts=(0 until 4).map { p=>
    val(bank,row)=locations(p)
    io.in.bits.mask(p) && VecInit((0 until 8).map(s=>reserved(bank)(s) && rows(bank)(s)===row)).asUInt.orR
  }
  private val duplicateBank=VecInit((for(a <- 0 until 4;b <- a+1 until 4)yield
    io.in.bits.mask(a) && io.in.bits.mask(b) && locations(a)._1===locations(b)._1)).asUInt.orR
  private val laneCapacity=VecInit(inputs.map(_.io.enq.ready)).asUInt.andR
  io.in.ready:=bound && !io.begin && !cancel && count<8.U && laneCapacity && !VecInit(conflicts).asUInt.orR
  io.out.valid:=count=/=0.U && done(head)===masks(head)
  io.out.bits.ticket:=tickets(head);io.out.bits.mask:=masks(head)
  io.out.bits.testsPass:=passed(head);io.out.bits.colorWrites:=colors(head)
  io.out.bits.depthStencilWrites:=depths(head);io.out.bits.canceled:=canceled(head)

  private val finished=Wire(Vec(4,Bool()))
  private val finishSlot=Wire(Vec(4,UInt(3.W)))
  private val finishIndex=Wire(Vec(4,UInt(10.W)))
  private val finishPass=Wire(Vec(4,Bool()))
  private val finishColor=Wire(Vec(4,Bool()))
  private val finishDepth=Wire(Vec(4,Bool()))
  private val finishCancel=Wire(Vec(4,Bool()))
  for(p <- 0 until 4) {
    val q=inputs(p);val meta=metadata(p);val math=merge(p)
    q.io.enq.valid:=io.in.fire && io.in.bits.mask(p)
    q.io.enq.bits.slot:=tail;q.io.enq.bits.index:=io.in.bits.index(p)
    q.io.enq.bits.color:=io.in.bits.color(p);q.io.enq.bits.depth:=io.in.bits.depth(p)
    q.io.enq.bits.state:=io.in.bits.state
    val discardInput=cancel && !readOffered(p)
    io.read(p).valid:=q.io.deq.valid && meta.io.enq.ready && !discardInput
    io.read(p).bits.index:=q.io.deq.bits.index
    io.read(p).bits.ticket:=Cat(0.U(3.W),q.io.deq.bits.slot,p.U(2.W))
    io.readState(p):=q.io.deq.bits.state
    io.readDepth(p):=q.io.deq.bits.depth
    meta.io.enq.valid:=io.read(p).fire;meta.io.enq.bits:=q.io.deq.bits
    q.io.deq.ready:=discardInput || (meta.io.enq.ready && io.read(p).ready)
    when(io.read(p).valid && !io.read(p).ready){readOffered(p):=true.B}
    when(io.read(p).fire){readOffered(p):=false.B}
    val response=io.response(p)
    math.io.in.valid:=response.valid && meta.io.deq.valid && !cancel
    math.io.in.bits.index:=response.bits.index;math.io.in.bits.ticket:=response.bits.ticket
    math.io.in.bits.sourceColor:=meta.io.deq.bits.color;math.io.in.bits.sourceDepth:=meta.io.deq.bits.depth
    math.io.in.bits.oldColor:=response.bits.color;math.io.in.bits.oldDepthStencil:=response.bits.depthStencil
    math.io.in.bits.state:=meta.io.deq.bits.state
    response.ready:=meta.io.deq.valid && (cancel || math.io.in.ready)
    meta.io.deq.ready:=response.valid && (cancel || math.io.in.ready)
    when(response.fire) {
      assert(response.bits.index===meta.io.deq.bits.index,"read response index lost sample ownership")
      assert(response.bits.ticket===Cat(0.U(3.W),meta.io.deq.bits.slot,p.U(2.W)),"read response ticket lost packet ownership")
    }
    val out=math.io.out
    val wantsWrite=out.bits.colorWrite || out.bits.depthStencilWrite
    val discardResult=cancel && !writeOffered(p)
    io.write(p).valid:=out.valid && wantsWrite && !discardResult
    io.write(p).bits.index:=out.bits.index;io.write(p).bits.color:=out.bits.color
    io.write(p).bits.depthStencil:=out.bits.depthStencil
    io.write(p).bits.colorEnable:=out.bits.colorWrite
    io.write(p).bits.depthStencilEnable:=out.bits.depthStencilWrite
    out.ready:=discardResult || !wantsWrite || io.write(p).ready
    when(io.write(p).valid && !io.write(p).ready){writeOffered(p):=true.B}
    when(out.fire){writeOffered(p):=false.B}
    // At most one terminal event/lane/cycle may update the reservation table.
    // Give an older arithmetic result priority, then the returned read, then
    // work which has never offered an SRAM read.
    val completeMath=out.fire
    val dropResponse=response.valid && meta.io.deq.valid && cancel && !completeMath
    val dropInput=q.io.deq.valid && discardInput && !completeMath && !dropResponse
    when(cancel) {
      response.ready:=meta.io.deq.valid && !completeMath
      meta.io.deq.ready:=response.valid && !completeMath
      when(discardInput){q.io.deq.ready:= !completeMath && !dropResponse}
    }
    finished(p):=completeMath || dropResponse || dropInput
    finishSlot(p):=Mux(completeMath,out.bits.ticket(4,2),Mux(dropResponse,meta.io.deq.bits.slot,q.io.deq.bits.slot))
    finishIndex(p):=Mux(completeMath,out.bits.index,Mux(dropResponse,meta.io.deq.bits.index,q.io.deq.bits.index))
    finishPass(p):=completeMath && !discardResult && out.bits.testsPass
    finishColor(p):=io.write(p).fire && out.bits.colorWrite
    finishDepth(p):=io.write(p).fire && out.bits.depthStencilWrite
    finishCancel(p):= !completeMath || discardResult
  }
  for(s <- 0 until 8) {
    val events=VecInit((0 until 4).map(p=>finished(p) && finishSlot(p)===s.U)).asUInt
    val passEvents=VecInit((0 until 4).map(p=>events(p) && finishPass(p))).asUInt
    val colorEvents=VecInit((0 until 4).map(p=>events(p) && finishColor(p))).asUInt
    val depthEvents=VecInit((0 until 4).map(p=>events(p) && finishDepth(p))).asUInt
    val cancelEvents=VecInit((0 until 4).map(p=>events(p) && finishCancel(p))).asUInt
    when(events.orR) {
      assert(!(done(s)&events).orR,"sample completed twice")
      done(s):=done(s)|events;passed(s):=passed(s)|passEvents
      colors(s):=colors(s)|colorEvents;depths(s):=depths(s)|depthEvents;canceled(s):=canceled(s)|cancelEvents
    }
    when(io.in.fire && tail===s.U) {
      masks(s):=io.in.bits.mask;tickets(s):=io.in.bits.ticket
      done(s):=0.U;passed(s):=0.U;colors(s):=0.U;depths(s):=0.U;canceled(s):=0.U
    }
    for(b <- 0 until 8) {
      val releases=(0 until 4).map(p=>finished(p) && finishSlot(p)===s.U && location(finishIndex(p))._1===b.U)
      when(VecInit(releases).asUInt.orR){reserved(b)(s):=false.B}
      for(p <- 0 until 4)when(io.in.fire && tail===s.U && io.in.bits.mask(p) && locations(p)._1===b.U) {
        reserved(b)(s):=true.B;rows(b)(s):=locations(p)._2
      }
    }
  }
  when(io.in.fire) {
    assert(!duplicateBank,"a native quad's active samples must use distinct banks")
    for(p <- 0 until 4)when(io.in.bits.mask(p)) {
      assert(mode===2.U || Mux(mode===1.U,!io.in.bits.index(p)(9),!io.in.bits.index(p)(9,8).orR),"sample index exceeds admitted mode")
    }
    tail:=tail+1.U
  }
  when(io.out.fire){head:=head+1.U}
  when(io.in.fire=/=io.out.fire){count:=Mux(io.in.fire,count+1.U,count-1.U)}
  io.drained:=count===0.U && VecInit(inputs.map(q=> !q.io.deq.valid)).asUInt.andR &&
    VecInit(metadata.map(q=> !q.io.deq.valid)).asUInt.andR && VecInit(merge.map(_.io.drained)).asUInt.andR
  io.acceptedSamples:=Mux(io.in.fire,PopCount(io.in.bits.mask),0.U)
  io.committedSamples:=PopCount(VecInit(io.write.map(_.fire)))
  when(io.cancel){aborted:=true.B}
  when(io.begin) {
    assert(io.drained,"quad merge must drain before tile rebinding")
    assert(!io.in.valid,"quad producer must be quiescent at begin")
    assert(io.sampleMode<=2.U,"unsupported sample mode")
    mode:=io.sampleMode;bound:=true.B;aborted:=false.B
  }
}
