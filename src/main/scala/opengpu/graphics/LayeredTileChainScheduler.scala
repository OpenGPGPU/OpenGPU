package opengpu.graphics

import chisel3._
import chisel3.util._

class LayeredTileChainCompletion extends Bundle {
  val engine=UInt(8.W);val renderStarted=Bool();val image=new LayeredTypedReplayTileCompletion
}
/** Internal version 1 is deliberately separate from the ordinary 32-bit
  * replay summary. Host publication must encode these complete 64-bit words.
  */
class LayeredTileChainSummary extends Bundle {
  val version=UInt(8.W);val identity=new GraphicsWorkIdentity;val status=UInt(3.W)
  val faultValid=Bool();val fault=new LayeredTypedReplayTileCompletion
  val overflow=UInt(11.W)
  val tiles=UInt(64.W);val scanned=UInt(64.W);val selected=UInt(64.W)
  val pageReadLines=UInt(64.W);val recordReadLines=UInt(64.W)
  val accepted=UInt(64.W);val issued=UInt(64.W);val completed=UInt(64.W)
  val loadBytes=UInt(64.W);val storedBytes=UInt(64.W);val resolvedBytes=UInt(64.W)
}

/** Dispatch the ORIGINAL directory chain, including healthy empty chains, to
  * complete layered image owners. Every engine retains a running and buffered
  * slot. A child's drained completion fills the running slot's result; only
  * acceptance of the public tile result releases that slot/engine credit.
  *
  * Already offered chain tuples are irrevocable during cancellation. Cancel is
  * delivered to the image only after its binding handshake. Unoffered queued
  * work and unusable failed chains report locally without image writes. The
  * caller retains the directory, record arena and all attachment/VM leases
  * until the held pass summary is accepted.
  */
class LayeredTileChainScheduler(engines:Int=4) extends Module {
  require(engines>=1 && engines<=4)
  private val bits=log2Ceil(engines).max(1)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new GraphicsWorkIdentity))
    val tile=Flipped(Decoupled(new TileBinCompletion))
    val chain=Vec(engines,Decoupled(new TileBinCompletion))
    val imageCompletion=Vec(engines,Flipped(Decoupled(new LayeredTypedReplayTileCompletion)))
    val imageBusy=Input(Vec(engines,Bool()));val cancelImage=Output(Vec(engines,Bool()))
    val permit=Input(Vec(engines,Bool()));val engineBusy=Output(Vec(engines,Bool()))
    val tileDone=Decoupled(new LayeredTileChainCompletion)
    val producerDone=Input(Bool());val completion=Decoupled(new LayeredTileChainSummary)
    val cancel=Input(Bool());val cancelSource=Output(Bool());val busy=Output(Bool())
  })
  private val active=RegInit(false.B);private val cancelled=RegInit(false.B)
  private val identity=Reg(new GraphicsWorkIdentity);private val status=RegInit(0.U(3.W))
  private val curValid=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val nextValid=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val offered=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val started=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val doneValid=RegInit(VecInit(Seq.fill(engines)(false.B)))
  private val cur=Reg(Vec(engines,new TileBinCompletion));private val next=Reg(Vec(engines,new TileBinCompletion))
  private val done=Reg(Vec(engines,new LayeredTypedReplayTileCompletion))
  private val dispatchIndex=RegInit(0.U(bits.W));private val doneIndex=RegInit(0.U(bits.W))
  private val headValid=RegInit(false.B);private val head=Reg(UInt(bits.W))
  private val totals=RegInit(VecInit(Seq.fill(11)(0.U(64.W))))
  private val overflow=RegInit(0.U(11.W));private val result=Reg(new LayeredTileChainSummary)
  private val resultValid=RegInit(false.B)
  private val faultValid=RegInit(false.B);private val fault=Reg(new LayeredTypedReplayTileCompletion)
  private val childFault=io.imageCompletion.map(c=>c.valid && c.bits.status=/=0.U && c.bits.status=/=4.U).reduce(_ || _)
  private def usable(c:TileBinCompletion):Bool=c.status===PrimitiveBinStatus.success.U ||
    (c.status===PrimitiveBinStatus.exhausted.U && c.prefixUsable)
  private val sourceFaults=(0 until engines).map(e=>curValid(e) && !offered(e) && !started(e) &&
    !doneValid(e) && !usable(cur(e)) && cur(e).status=/=PrimitiveBinStatus.cancelled.U)
  private val sourceFault=sourceFaults.reduce(_ || _)
  private val stop=cancelled || io.cancel || status=/=0.U || childFault || sourceFault
  private def rotate(start:UInt,n:Int):UInt=((start+&n.U)%engines.U)(bits-1,0)
  private val free=VecInit((0 until engines).map(e=> !nextValid(e) && io.permit(e)))
  private val order=(0 until engines).map(i=>rotate(dispatchIndex,i))
  private val pick=PriorityMux(order.map(e=>free(e)->e))
  private val doneOrder=(0 until engines).map(i=>rotate(doneIndex,i))
  private val donePick=Mux(headValid,head,PriorityMux(doneOrder.map(e=>doneValid(e)->e)))
  io.begin.ready:= !active && !resultValid && !io.cancel;io.busy:=active
  io.tile.ready:=active && free.asUInt.orR && !resultValid
  io.tileDone.valid:=doneValid.asUInt.orR;io.tileDone.bits.engine:=donePick
  io.tileDone.bits.renderStarted:=started(donePick);io.tileDone.bits.image:=done(donePick)
  io.completion.valid:=resultValid;io.completion.bits:=result
  io.cancelSource:=active && stop
  when(io.begin.fire){active:=true.B;identity:=io.begin.bits;status:=0.U;cancelled:=false.B
    dispatchIndex:=0.U;doneIndex:=0.U;headValid:=false.B;totals.foreach(_:=0.U);overflow:=0.U
    faultValid:=false.B;fault:=0.U.asTypeOf(fault)
    for(e<-0 until engines){curValid(e):=false.B;nextValid(e):=false.B;offered(e):=false.B;started(e):=false.B;doneValid(e):=false.B}}
  when(active && (io.cancel || io.imageCompletion.map(c=>c.valid && c.bits.status===4.U).reduce(_ || _) ||
    (0 until engines).map(e=>curValid(e) && cur(e).status===PrimitiveBinStatus.cancelled.U).reduce(_ || _))){cancelled:=true.B}
  when(io.tile.fire){
    when(!curValid(pick)){cur(pick):=io.tile.bits;curValid(pick):=true.B}
      .otherwise{next(pick):=io.tile.bits;nextValid(pick):=true.B}
    dispatchIndex:=Mux(pick===(engines-1).U,0.U,pick+1.U)
  }
  for(e<-0 until engines){
    io.engineBusy(e):=curValid(e) || nextValid(e) || io.imageBusy(e)
    io.chain(e).valid:=offered(e);io.chain(e).bits:=cur(e)
    io.cancelImage(e):=active && started(e) && stop
    io.imageCompletion(e).ready:=active && started(e) && !doneValid(e)
    // Exhaustion with a usable prefix remains a real image operation; both a
    // healthy zero-count tile and an empty usable exhausted prefix must bind.
    when(active && curValid(e) && !offered(e) && !started(e) && !doneValid(e)){
      when(stop || !usable(cur(e))){
        done(e):=0.U.asTypeOf(done(e));done(e).identity:=identity;done(e).tile:=cur(e).tile
        done(e).replay.identity:=identity;done(e).replay.tile:=cur(e).tile;done(e).replay.chain:=cur(e)
        val ownFault= !usable(cur(e)) && cur(e).status=/=PrimitiveBinStatus.cancelled.U
        done(e).status:=Mux(ownFault,Mux(cur(e).status===PrimitiveBinStatus.memoryFault.U,3.U,1.U),4.U)
        done(e).faultStage:=Mux(ownFault,2.U,0.U);doneValid(e):=true.B
      }.otherwise{offered(e):=true.B}
    }
    when(io.chain(e).fire){offered(e):=false.B;started(e):=true.B}
    when(io.imageCompletion(e).fire){
      val r=io.imageCompletion(e).bits
      assert(r.identity.asUInt===identity.asUInt && r.tile===cur(e).tile &&
        r.replay.chain.asUInt===cur(e).asUInt,"layered image returned a different original chain")
      done(e):=r;doneValid(e):=true.B
    }
    when(io.tileDone.fire && donePick===e.U){
      assert(!io.imageBusy(e),"layered tile credit released before image drain")
      doneValid(e):=false.B;started(e):=false.B;curValid(e):=false.B
    }
    // Promotion is deliberately a later edge than tileDone. It cannot replace
    // a held original tuple or race with a same-cycle arriving second slot.
    when(active && !curValid(e) && nextValid(e)){
      cur(e):=next(e);curValid(e):=true.B;nextValid(e):=false.B
    }
  }
  when(io.tileDone.fire){
    val r=io.tileDone.bits.image
    val values=Seq(1.U,r.replay.scanned,r.replay.selected,r.replay.pageReadLines,r.replay.recordReadLines,
      r.accepted,r.issued,r.completed,r.loadBytes,r.storedBytes,r.resolvedBytes)
    val carries=Wire(Vec(11,Bool()))
    for(i<-0 until 11){val sum=totals(i)+&values(i);carries(i):=sum(64)
      when(!sum(64)){totals(i):=sum(63,0)}}
    overflow:=overflow|carries.asUInt
    when(status===0.U && (r.status=/=0.U && r.status=/=4.U || carries.asUInt.orR)){
      status:=Mux(carries.asUInt.orR,6.U,r.status)}
    headValid:=false.B;doneIndex:=Mux(donePick===(engines-1).U,0.U,donePick+1.U)
  }.elsewhen(io.tileDone.valid && !headValid){headValid:=true.B;head:=donePick}
  // Retain the first observable non-cancel cause without waiting for a held
  // public result. Simultaneous failures have deterministic source/engine
  // order; a later cancel or another engine cannot replace the chosen cause.
  when(active && status===0.U){
    when(sourceFault){
      val selected=PriorityMux(sourceFaults.zipWithIndex.map{case(valid,e)=>valid->cur(e)})
      val code=Mux(selected.status===PrimitiveBinStatus.memoryFault.U,3.U,1.U)
      status:=code;faultValid:=true.B;fault:=0.U.asTypeOf(fault)
      fault.status:=code;fault.faultStage:=2.U;fault.identity:=identity;fault.tile:=selected.tile
      fault.replay.identity:=identity;fault.replay.tile:=selected.tile;fault.replay.chain:=selected
    }.elsewhen(childFault){
      val selected=PriorityMux(io.imageCompletion.map(c=>(c.valid && c.bits.status=/=0.U && c.bits.status=/=4.U)->c.bits))
      status:=selected.status;faultValid:=true.B;fault:=selected
    }
  }
  private val drained= !(curValid.asUInt.orR || nextValid.asUInt.orR || offered.asUInt.orR ||
    started.asUInt.orR || doneValid.asUInt.orR || io.imageBusy.asUInt.orR)
  when(active && io.producerDone && !io.tile.valid && drained && !resultValid){
    result:=0.U.asTypeOf(result);result.version:=1.U;result.identity:=identity
    result.status:=Mux(status=/=0.U,status,Mux(cancelled || io.cancel,4.U,0.U));result.overflow:=overflow
    result.faultValid:=faultValid;result.fault:=fault
    result.tiles:=totals(0);result.scanned:=totals(1);result.selected:=totals(2)
    result.pageReadLines:=totals(3);result.recordReadLines:=totals(4)
    result.accepted:=totals(5);result.issued:=totals(6);result.completed:=totals(7)
    result.loadBytes:=totals(8);result.storedBytes:=totals(9);result.resolvedBytes:=totals(10);resultValid:=true.B
  }
  when(io.completion.fire){resultValid:=false.B;active:=false.B}
}
