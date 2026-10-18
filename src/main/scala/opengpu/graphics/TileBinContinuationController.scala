package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.util.CarrySaveAccumulator

/** Streaming submission owns its sealing boundaries. A positive restart limit
  * requires the existing exported-prefix contract (both attachments stored).
  * The input producer receives one reply per append, after any internal retry.
  */
class TileBinContinuationDescriptor extends Bundle {
  val version = UInt(8.W)
  val render = new TileBinRenderPassDescriptor
  val restartLimit = UInt(16.W)
}
class TileBinContinuationAppendResult extends Bundle {
  val batch = UInt(16.W)
  /** committedCount and recordAddress belong to this batch, not the stream. */
  val result = new TileBinAppendResult
}
class TileBinContinuationTileResult extends Bundle {
  val batch = UInt(16.W)
  /** One event per tile visit. Only the final stream status validates images. */
  val result = new TileBinRenderTileCompletion
}
class TileBinContinuationTotals extends Bundle {
  val tileVisits = UInt(64.W)
  val draws = UInt(64.W)
  val committed = UInt(64.W)
  val references = UInt(64.W)
  val resolved = UInt(64.W)
  val loadBytes = UInt(64.W)
  val storeBytes = UInt(64.W)
  val resolveBytes = UInt(64.W)
  val allocatedPages = UInt(64.W)
  val spillTransitions = UInt(64.W)
  val readPages = UInt(64.W)
  val readLines = UInt(64.W)
  val recordReadLines = UInt(64.W)
  val directoryReadBytes = UInt(64.W)
  val directoryWriteBytes = UInt(64.W)
}
class TileBinContinuationCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(TileBinRenderStatus.width.W)
  val batches = UInt(17.W)
  val restarts = UInt(16.W)
  val totals = new TileBinContinuationTotals
  /** Cache hit/miss and other nested counters describe only the last batch. */
  val last = new TileBinRenderPassCompletion
}

/** Recycle a fully drained bin arena and retry the exact rejected append.
  * This controller never replays a successful producer append. It withholds
  * an exhausted reply, ends/drains all tile visits, reloads the exported image,
  * and sends the saved append before reopening producer admission. There is
  * no retry on a fault, unsafe prefix, zero progress, cancellation or budget
  * exhaustion. The child owns every memory transaction through its summary.
  */
class TileBinContinuationController(engines: Int = 2,paged: Boolean = false) extends Module {
  require(engines>=1 && engines<=4)
  val io=IO(new Bundle {
    val begin=Flipped(Decoupled(new TileBinContinuationDescriptor))
    val append=Flipped(Decoupled(new TileBinAppend))
    val appended=Decoupled(new TileBinContinuationAppendResult)
    val end=Flipped(Decoupled(Bool()))
    val cancel=Input(Bool())
    val tileDone=Vec(engines,Decoupled(new TileBinContinuationTileResult))
    val completion=Decoupled(new TileBinContinuationCompletion)
    val busy=Output(Bool())
    val childBegin=Decoupled(new TileBinRenderPassDescriptor)
    val childAppend=Decoupled(new TileBinAppend)
    val childAppended=Flipped(Decoupled(new TileBinAppendResult))
    val childEnd=Decoupled(Bool())
    val childCancel=Output(Bool())
    val childTileDone=Vec(engines,Flipped(Decoupled(new TileBinRenderTileCompletion)))
    val childCompletion=Flipped(Decoupled(new TileBinRenderPassCompletion))
  })
  private val Seq(idle,check,start,building,ending,draining,inspect,finish,done)=Enum(9)
  private val phase=RegInit(idle)
  private val snapshot=Reg(new TileBinContinuationDescriptor)
  private val batch=RegInit(0.U(16.W))
  private val batches=RegInit(0.U(17.W))
  private val restarts=RegInit(0.U(16.W))
  private val started=RegInit(false.B)
  private val cancelled=RegInit(false.B)
  private val failure=RegInit(0.U(TileBinRenderStatus.width.W))
  private val terminal=RegInit(0.U(TileBinRenderStatus.width.W))
  private val inputEnded=RegInit(false.B)
  private val prefixSafe=RegInit(true.B)
  private val retry=RegInit(false.B)
  private val request=Reg(new TileBinAppend)
  private val haveRequest=RegInit(false.B)
  private val accepted=RegInit(false.B)
  private val reply=Reg(new TileBinContinuationAppendResult)
  private val replyValid=RegInit(false.B)
  private val last=Reg(new TileBinRenderPassCompletion)
  private val result=Reg(new TileBinContinuationCompletion)
  private val stop=cancelled || io.cancel || failure=/=0.U
  private val active=phase=/=idle && phase=/=done
  private def merge(a: UInt,b: UInt): UInt=TileBinRenderStatus.merge(a,b)
  private def appendStatus(status: UInt): UInt=Mux(status>7.U,PrimitiveBinStatus.memoryFault.U,status)
  private def rejectPending(status: UInt): Unit = {
    reply.batch:=batch
    reply.result:=0.U.asTypeOf(reply.result)
    reply.result.identity:=snapshot.render.attachments.identity
    reply.result.tile:=request.tile; reply.result.reference:=request.reference
    reply.result.status:=appendStatus(status)
    replyValid:=true.B
  }

  io.begin.ready:=phase===idle && !io.cancel
  io.busy:=phase=/=idle
  io.completion.valid:=phase===done; io.completion.bits:=result
  io.append.ready:=phase===building && !haveRequest && !replyValid && !stop
  io.appended.valid:=replyValid; io.appended.bits:=reply
  io.end.ready:=phase===building && !haveRequest && !replyValid && !io.append.valid && !stop
  io.childBegin.valid:=phase===start && !stop
  io.childBegin.bits:=snapshot.render
  io.childBegin.bits.replay.bins.version:=3.U
  io.childBegin.bits.replay.bins.resume:=batch=/=0.U
  when(batch=/=0.U) {
    io.childBegin.bits.attachments.color.loadOp:=AttachmentLoadOp.load.U
    io.childBegin.bits.attachments.depth.loadOp:=AttachmentLoadOp.load.U
  }
  io.childAppend.valid:=phase===building && haveRequest && !accepted && !replyValid && !retry && !stop
  io.childAppend.bits:=request
  io.childAppended.ready:=started && !replyValid && !retry
  io.childEnd.valid:=phase===ending && !stop
  io.childEnd.bits:=inputEnded
  io.childCancel:=started && stop
  io.childCompletion.ready:=started && !io.childTileDone.map(_.valid).reduce(_ || _)

  val totals=Wire(new TileBinContinuationTotals)
  val summary=io.childCompletion.bits
  val additions=Seq(summary.tiles,summary.draws,summary.replay.replay.committed,
    summary.replay.replay.references,summary.replay.resolved,summary.loadBytes,summary.storeBytes,
    summary.resolveBytes,summary.replay.replay.allocatedPages,summary.replay.replay.spillTransitions,
    summary.replay.replay.readPages,summary.replay.replay.readLines,summary.replay.recordReadLines,
    summary.replay.replay.directoryReadBytes,summary.replay.replay.directoryWriteBytes)
  val observations=Seq(totals.tileVisits,totals.draws,totals.committed,totals.references,totals.resolved,
    totals.loadBytes,totals.storeBytes,totals.resolveBytes,totals.allocatedPages,totals.spillTransitions,
    totals.readPages,totals.readLines,totals.recordReadLines,totals.directoryReadBytes,totals.directoryWriteBytes)
  for ((addend,observation) <- additions.zip(observations)) {
    val counter=Module(new CarrySaveAccumulator(64,prefixValue=true))
    counter.io.clear:=io.begin.fire
    counter.io.addend:=Mux(io.childCompletion.fire,addend.pad(64),0.U)
    observation:=counter.io.value
  }
  for (e <- 0 until engines) {
    io.tileDone(e).valid:=io.childTileDone(e).valid
    io.tileDone(e).bits.batch:=batch
    io.tileDone(e).bits.result:=io.childTileDone(e).bits
    io.childTileDone(e).ready:=io.tileDone(e).ready
    when(io.childTileDone(e).fire && !io.childTileDone(e).bits.prefixUsable) { prefixSafe:=false.B }
  }
  when(io.begin.fire) {
    snapshot:=io.begin.bits; batch:=0.U; batches:=0.U; restarts:=0.U
    started:=false.B; cancelled:=false.B; failure:=0.U; terminal:=0.U
    inputEnded:=false.B; prefixSafe:=true.B; retry:=false.B
    haveRequest:=false.B; accepted:=false.B; replyValid:=false.B
    last:=0.U.asTypeOf(last); phase:=check
  }
  when(phase===check) {
    when(stop) { terminal:=merge(failure,PrimitiveBinStatus.cancelled.U); phase:=finish }
      .elsewhen(snapshot.version=/=1.U) {
        terminal:=PrimitiveBinStatus.invalidDescriptor.U; phase:=finish
      }.elsewhen(snapshot.render.replay.bins.version=/=(if (paged) 2.U else 1.U) || snapshot.render.replay.bins.resume) {
        terminal:=PrimitiveBinStatus.invalidDescriptor.U; phase:=finish
      }.elsewhen(snapshot.restartLimit=/=0.U &&
        (!snapshot.render.attachments.color.store || !snapshot.render.attachments.depth.store)) {
        terminal:=PrimitiveBinStatus.invalidDescriptor.U; phase:=finish
      }.otherwise { phase:=start }
  }
  when(io.childBegin.fire) {
    started:=true.B; batches:=batches+1.U
    when(batch=/=0.U) { restarts:=restarts+1.U }
    prefixSafe:=true.B; accepted:=false.B; phase:=building
  }
  when(io.append.fire) { request:=io.append.bits; haveRequest:=true.B }
  when(io.childAppend.fire) { accepted:=true.B }
  when(io.childAppended.fire) {
    assert(haveRequest && accepted)
    assert(io.childAppended.bits.tile===request.tile && io.childAppended.bits.reference.asUInt===request.reference.asUInt)
    accepted:=false.B
    when(io.childAppended.bits.status===PrimitiveBinStatus.exhausted.U && !stop) {
      retry:=true.B; phase:=ending
    }.otherwise {
      reply.batch:=batch; reply.result:=io.childAppended.bits; replyValid:=true.B
      when(io.childAppended.bits.status=/=0.U) {
        failure:=merge(failure,io.childAppended.bits.status); phase:=draining
      }
    }
  }
  when(io.appended.fire) { replyValid:=false.B; haveRequest:=false.B }
  when(io.end.fire) { inputEnded:=true.B; phase:=ending }
  when(io.childEnd.fire) { phase:=draining }
  when(io.childCompletion.fire) {
    assert(!accepted,"child completion must drain every accepted append reply")
    last:=io.childCompletion.bits; started:=false.B; phase:=inspect
  }
  when(phase===inspect) {
    val canRetry=retry && last.status===PrimitiveBinStatus.exhausted.U &&
      prefixSafe && last.replay.replay.committed=/=0.U && batch<snapshot.restartLimit && !stop
    when(canRetry) {
      batch:=batch+1.U; retry:=false.B; phase:=start
    }.otherwise {
      val status=merge(merge(failure,last.status),Mux(cancelled || io.cancel,PrimitiveBinStatus.cancelled.U,0.U))
      terminal:=status
      when(haveRequest && !replyValid) {
        rejectPending(Mux(status===0.U,PrimitiveBinStatus.exhausted.U,status))
      }
      retry:=false.B; phase:=finish
    }
  }
  // Cancel before child admission still owes the producer one stable reply.
  when(stop && haveRequest && !accepted && !replyValid && phase=/=inspect && phase=/=finish) {
    rejectPending(merge(failure,PrimitiveBinStatus.cancelled.U)); retry:=false.B
  }
  when(io.cancel && active) { cancelled:=true.B }
  when(stop && active && phase=/=inspect && phase=/=finish && !io.childCompletion.fire) {
    when(started) { phase:=draining }
      .otherwise { terminal:=merge(merge(terminal,failure),PrimitiveBinStatus.cancelled.U); phase:=finish }
  }
  when(phase===finish && !haveRequest && !replyValid) {
    result.identity:=snapshot.render.attachments.identity
    result.status:=merge(terminal,Mux(cancelled || io.cancel,PrimitiveBinStatus.cancelled.U,0.U))
    result.batches:=batches; result.restarts:=restarts; result.totals:=totals; result.last:=last
    phase:=done
  }
  when(io.completion.fire) { phase:=idle }
}
