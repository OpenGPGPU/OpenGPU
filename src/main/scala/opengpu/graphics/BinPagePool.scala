package opengpu.graphics

import chisel3._
import chisel3.util._

class BinPageSpillSegment extends Bundle {
  val base=UInt(32.W)
  val limit=UInt(33.W)
}

class BinPagePoolDescriptor extends Bundle {
  val version = UInt(8.W)
  val identity = new GraphicsWorkIdentity
  val base = UInt(32.W)
  /** Exclusive bound; 4 GiB is representable without wrapping to zero. */
  val limit = UInt(33.W)
  val pageSizeLog2 = UInt(6.W)
  /** Version 2 reserves [base,limit), with an initial prefix and sorted,
    * nonoverlapping fallback segments. The final active end equals limit.
    * Version 1 ignores these fields and uses the full contiguous envelope.
    */
  val initialLimit=UInt(33.W)
  val spillCount=UInt(4.W)
  val spills=Vec(BinPagePoolRules.maxSpills,new BinPageSpillSegment)
}

class BinPageRequest extends Bundle {
  val tile = UInt(32.W)
  /** Opaque caller token, also returned when the pool cannot allocate. */
  val token = UInt(32.W)
}

object BinPageStatus {
  val width = 3
  val success = 0
  val invalidDescriptor = 1
  val cancelled = 2
  val exhausted = 3
}

class BinPageAllocation extends Bundle {
  val identity = new GraphicsWorkIdentity
  val request = new BinPageRequest
  val status = UInt(BinPageStatus.width.W)
  val address = UInt(32.W)
  val ordinal = UInt(32.W)
}

class BinPagePoolCompletion extends Bundle {
  val identity = new GraphicsWorkIdentity
  val status = UInt(BinPageStatus.width.W)
  val allocatedPages = UInt(32.W)
  val spillTransitions = UInt(4.W)
}

/** Monotonic allocation from immutable software-owned bin-page backing.
  * Version 2 automatically advances through checked, preallocated segments.
  * Ordinals of successful pages are their address offset in the reserved
  * envelope, so existing chain headers remain self-checking across gaps.
  *
  * An exhausted request returns its unchanged tile/token and no address, then
  * closes allocation admission until end/cancel. The caller must retain that
  * primitive as its restart point; it must never commit a bin entry for a
  * failed allocation. Pages cannot alias or be reused during this binding.
  *
  * This block owns allocation replies, not memory transactions or primitive
  * storage. Its caller must drain all page users before ending the binding
  * or recycling its arena. Cancellation preserves an already offered reply
  * and delays completion until the reply is consumed. Integration with the
  * bin writer, spill/restart controller and tile scheduler remains separate.
  */
class BinPagePool(pageBytes: Int = 4096) extends Module {
  require(pageBytes >= 64 && pageBytes <= 65536 && (pageBytes & (pageBytes - 1)) == 0)
  private val pageBits = log2Ceil(pageBytes)
  private val countWidth = 33 - pageBits
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new BinPagePoolDescriptor))
    val allocate = Flipped(Decoupled(new BinPageRequest))
    val allocation = Decoupled(new BinPageAllocation)
    val end = Flipped(Decoupled(Bool()))
    val cancel = Input(Bool())
    val completion = Decoupled(new BinPagePoolCompletion)
    val descriptor = Output(new BinPagePoolDescriptor)
    val busy = Output(Bool())
    val exhausted = Output(Bool())
    /** Cycle events for the eventual bin/performance counter interface. */
    val allocated = Output(Bool())
    val allocationFailed = Output(Bool())
    val spilled = Output(Bool())
  })
  private val idle :: validate :: segments :: active :: drain :: result :: Nil = Enum(6)
  private val state = RegInit(idle)
  private val pool = RegInit(0.U.asTypeOf(new BinPagePoolDescriptor))
  private val currentLimit=Reg(UInt(33.W))
  private val segmentIndex=RegInit(0.U(4.W))
  private val validateIndex=Reg(UInt(3.W))
  private val previousLimit=Reg(UInt(33.W))
  private val nextAddress = RegInit(0.U(33.W))
  private val count = RegInit(0.U(countWidth.W))
  private val exhausted = RegInit(false.B)
  private val status = RegInit(BinPageStatus.success.U(BinPageStatus.width.W))
  private val replyValid = RegInit(false.B)
  private val reply = Reg(new BinPageAllocation)
  private val completion = Reg(new BinPagePoolCompletion)
  private val afterPage = nextAddress +& pageBytes.U
  private val room = afterPage <= currentLimit.pad(34)
  private val legal=BinPagePoolRules.headerLegal(pool,pageBytes)
  private val nextSpill=pool.version===2.U && segmentIndex<pool.spillCount
  private val advance=state===active && !room && nextSpill && !io.cancel && !io.end.valid
  // Elastic page counter: one allocation may sit in `pending` until the next
  // cycle, when it is absorbed. The late allocation enable then only updates
  // the pending flag and reply enable, never the carry chain of `count`.
  private val pending = RegInit(false.B)
  private val allocatedPages = Mux(pending, count + 1.U, count)

  io.begin.ready := state === idle && !io.cancel
  io.allocate.ready := state === active && !exhausted && !advance && !io.cancel &&
    !io.end.valid && (!replyValid || io.allocation.ready)
  io.allocation.valid := replyValid
  io.allocation.bits := reply
  io.end.ready := state === active && !io.cancel
  io.completion.valid := state === result
  io.completion.bits := completion
  io.descriptor := pool
  io.busy := state =/= idle
  io.exhausted := exhausted
  io.allocated := io.allocate.fire && room
  io.allocationFailed := io.allocate.fire && !room
  io.spilled:=advance

  pending := io.allocate.fire && room
  count := allocatedPages
  when(io.allocation.fire) { replyValid := false.B }
  when(io.allocate.fire) {
    replyValid := true.B
    reply.identity := pool.identity
    reply.request := io.allocate.bits
    reply.ordinal := Mux(room && pool.version===2.U,((nextAddress-pool.base.pad(33))>>pageBits).pad(32),allocatedPages)
    reply.status := Mux(room, BinPageStatus.success.U, BinPageStatus.exhausted.U)
    reply.address := Mux(room, nextAddress(31, 0), 0.U)
    when(room) {
      nextAddress := afterPage(32, 0)
    }.otherwise {
      exhausted := true.B
      status := BinPageStatus.exhausted.U
    }
  }
  switch(state) {
    is(idle) {
      when(io.begin.fire) {
        pool := io.begin.bits
        nextAddress := io.begin.bits.base
        currentLimit:=Mux(io.begin.bits.version===2.U,io.begin.bits.initialLimit,io.begin.bits.limit)
        segmentIndex:=0.U
        count := 0.U
        pending := false.B
        exhausted := false.B
        status := BinPageStatus.success.U
        state := validate
      }
    }
    is(validate) {
      when(io.cancel) {
        status := BinPageStatus.cancelled.U
        state := drain
      }.elsewhen(legal) {
        when(pool.version===2.U) { validateIndex:=0.U; previousLimit:=pool.initialLimit; state:=segments }
          .otherwise { state:=active }
      }.otherwise {
        status := BinPageStatus.invalidDescriptor.U
        state := drain
      }
    }
    is(segments) {
      val valid=BinPagePoolRules.segmentLegal(pool,validateIndex,previousLimit,pageBytes)
      val last=Mux(validateIndex<pool.spillCount,pool.spills(validateIndex).limit,previousLimit)
      when(io.cancel) { status:=BinPageStatus.cancelled.U; state:=drain }
        .elsewhen(!valid || (validateIndex===(BinPagePoolRules.maxSpills-1).U && last=/=pool.limit)) {
          status:=BinPageStatus.invalidDescriptor.U; state:=drain
        }.elsewhen(validateIndex===(BinPagePoolRules.maxSpills-1).U) { state:=active }
        .otherwise { validateIndex:=validateIndex+1.U; previousLimit:=last }
    }
    is(active) {
      when(advance) {
        nextAddress:=pool.spills(segmentIndex(2,0)).base
        currentLimit:=pool.spills(segmentIndex(2,0)).limit
        segmentIndex:=segmentIndex+1.U
      }
      when(io.cancel) {
        status := BinPageStatus.cancelled.U
        state := drain
      }.elsewhen(io.end.fire) {
        state := drain
      }
    }
    is(drain) {
      when(io.cancel) { status := BinPageStatus.cancelled.U }
      when(!replyValid) {
        completion.identity := pool.identity
        completion.status := Mux(io.cancel, BinPageStatus.cancelled.U, status)
        completion.allocatedPages := allocatedPages
        completion.spillTransitions:=segmentIndex
        state := result
      }
    }
    is(result) {
      when(io.completion.fire) { state := idle }
    }
  }
}
