package opengpu.graphics

import chisel3._
import chisel3.util._

class BinPagePoolDescriptor extends Bundle {
  val version = UInt(8.W)
  val identity = new GraphicsWorkIdentity
  val base = UInt(32.W)
  /** Exclusive bound; 4 GiB is representable without wrapping to zero. */
  val limit = UInt(33.W)
  val pageSizeLog2 = UInt(6.W)
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
}

/** Monotonic allocation from one immutable software-owned bin-page arena.
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
  })
  private val idle :: validate :: active :: drain :: result :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val pool = RegInit(0.U.asTypeOf(new BinPagePoolDescriptor))
  private val nextAddress = RegInit(0.U(33.W))
  private val count = RegInit(0.U(countWidth.W))
  private val exhausted = RegInit(false.B)
  private val status = RegInit(BinPageStatus.success.U(BinPageStatus.width.W))
  private val replyValid = RegInit(false.B)
  private val reply = Reg(new BinPageAllocation)
  private val completion = Reg(new BinPagePoolCompletion)
  private val afterPage = nextAddress +& pageBytes.U
  private val room = afterPage <= pool.limit.pad(34)
  private val aligned = (pool.base & (pageBytes - 1).U) === 0.U &&
    (pool.limit & (pageBytes - 1).U) === 0.U
  private val legal = pool.version === 1.U && pool.pageSizeLog2 === pageBits.U &&
    aligned && pool.base.pad(33) <= pool.limit &&
    pool.limit <= (BigInt(1) << 32).U(33.W)

  io.begin.ready := state === idle && !io.cancel
  io.allocate.ready := state === active && !exhausted && !io.cancel &&
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

  when(io.allocation.fire) { replyValid := false.B }
  when(io.allocate.fire) {
    replyValid := true.B
    reply.identity := pool.identity
    reply.request := io.allocate.bits
    reply.ordinal := count
    reply.status := Mux(room, BinPageStatus.success.U, BinPageStatus.exhausted.U)
    reply.address := Mux(room, nextAddress(31, 0), 0.U)
    when(room) {
      nextAddress := afterPage(32, 0)
      count := count + 1.U
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
        count := 0.U
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
        state := active
      }.otherwise {
        status := BinPageStatus.invalidDescriptor.U
        state := drain
      }
    }
    is(active) {
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
        completion.allocatedPages := count
        state := result
      }
    }
    is(result) {
      when(io.completion.fire) { state := idle }
    }
  }
}
