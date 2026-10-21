package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

object VisibilityQueryAction {
  val begin = 0.U(2.W); val end = 1.U(2.W)
  val poll = 2.U(2.W); val result = 3.U(2.W)
}

/** Internal command contract; API object names and allocation leases stay with
  * the command producer. A cookie distinguishes reuses of one GPU record.
  */
class VisibilityQueryCommand extends Bundle {
  val action = UInt(2.W)
  val address = UInt(32.W); val limit = UInt(33.W)
  val context = UInt(32.W); val cookie = UInt(32.W)
  val conservative = Bool(); val tag = UInt(32.W)
}
class VisibilityQueryReply extends Bundle {
  val tag = UInt(32.W); val context = UInt(32.W); val cookie = UInt(32.W)
  val address = UInt(32.W)
  /** 0 success,1 encoding/bounds,2 record identity,3 memory/renderer fault,
    * 4 reset cancellation,5 active/target mismatch,6 active-context capacity.
    */
  val status = UInt(3.W); val available = Bool(); val value = Bool()
}
class VisibilityQuerySample extends Bundle {
  val context = UInt(32.W); val root = UInt(32.W)
  val status = UInt(3.W); val passingSamples = UInt(5.W)
}
class VisibilityQueryJobFault extends Bundle {
  val context = UInt(32.W); val root = UInt(32.W); val status = UInt(3.W)
}

object VisibilityQueryRecord {
  val magic = BigInt("47514f01", 16) // GQO1
  val bytes = 64
}

/** Boolean occlusion queries with GPU-memory result lifetime.
  *
  * The caller orders begin/end against complete render drain and does not
  * dispatch following draws until the begin reply. Sample events are accepted
  * independently of command/result backpressure. Exact visibility also serves
  * the conservative target. End writes one complete result line and releases
  * its active slot only after acknowledgement; unread completed records consume
  * no active slots. The memory owner uses memorySatp, not a changing host root.
  *
  * Reset stops command admission, preserves irrevocable memory offers and
  * clears active contexts only after rendering and this memory command drain.
  * Its reset event invalidates unfinished query objects, like aborted jobs;
  * software must reinitialize their cookies before reuse.
  */
class NativeVisibilityQueryEngine(config: GpuConfig = GpuConfig(), contexts: Int = 8,
    producers: Int = 2) extends Module {
  require(config.xLen == 32 && contexts > 0 && contexts <= 16)
  require(producers > 0 && producers <= 4)
  private val indexBits = math.max(1, log2Ceil(contexts))
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new VisibilityQueryCommand))
    val reply = Decoupled(new VisibilityQueryReply)
    val samples = Input(Vec(producers, Valid(new VisibilityQuerySample)))
    val jobFault = Flipped(Valid(new VisibilityQueryJobFault))
    val satp = Input(UInt(32.W)); val memorySatp = Output(UInt(32.W))
    val renderIdle = Input(Bool()); val cancel = Input(Bool())
    val memoryRequest = Decoupled(new ComputeMemoryRequest(config, 64, 2))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(64, 2)))
    val busy = Output(Bool()); val activeContexts = Output(UInt(log2Ceil(contexts + 1).W))
  })
  private class Active extends Bundle {
    val context = UInt(32.W); val root = UInt(32.W)
    val address = UInt(32.W); val cookie = UInt(32.W); val conservative = Bool()
    val seen = Bool(); val fault = UInt(3.W)
  }
  private val live = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val active = Reg(Vec(contexts, new Active))
  private object State extends ChiselEnum { val idle, issue, waitMemory, publish = Value }
  private val phase = RegInit(State.idle)
  private val command = Reg(new VisibilityQueryCommand)
  private val root = RegInit(0.U(32.W)); private val slot = Reg(UInt(indexBits.W))
  private val write = Reg(Bool()); private val payload = Reg(UInt(512.W))
  private val response = Reg(new VisibilityQueryReply)
  private val resetPending = RegInit(false.B)
  private val interrupted = RegInit(false.B)
  private val replies = Module(new Queue(new VisibilityQueryReply, 1))
  io.reply <> replies.io.deq
  replies.io.enq.valid := phase === State.publish; replies.io.enq.bits := response
  when(replies.io.enq.fire) { phase := State.idle }
  io.memorySatp := root; io.activeContexts := PopCount(live)
  io.busy := phase =/= State.idle || replies.io.deq.valid || resetPending

  private val selected = VecInit((0 until contexts).map(i => live(i) &&
    active(i).context === io.command.bits.context && active(i).root === io.satp))
  private val alias = VecInit((0 until contexts).map(i => live(i) &&
    active(i).address === io.command.bits.address && active(i).root === io.satp))
  private val hasActive = selected.asUInt.orR
  private val selectedSlot = if (contexts == 1) 0.U(indexBits.W) else PriorityEncoder(selected.asUInt)
  private val free = VecInit(live.map(v => !v))
  private val freeSlot = if (contexts == 1) 0.U(indexBits.W) else PriorityEncoder(free.asUInt)
  private val boundary = io.command.bits.action === VisibilityQueryAction.begin ||
    io.command.bits.action === VisibilityQueryAction.end
  io.command.ready := phase === State.idle && replies.io.enq.ready && !io.cancel &&
    !resetPending && (!boundary || io.renderIdle)

  private def record(available: Bool, value: Bool, fault: UInt): UInt = {
    val words = Wire(Vec(16, UInt(32.W))); words.foreach(_ := 0.U)
    words(0) := VisibilityQueryRecord.magic.U; words(1) := VisibilityQueryRecord.bytes.U
    words(2) := io.command.bits.context; words(3) := io.command.bits.cookie
    words(4) := (fault.pad(32) << 2) | (io.command.bits.conservative.asUInt << 1) | available.asUInt
    words(5) := value; words(6) := io.satp
    words.asUInt
  }

  when(io.command.fire) {
    val q = io.command.bits
    val invalid = q.address(5, 0).orR || q.limit > (BigInt(1) << 32).U ||
      q.address.pad(33) + VisibilityQueryRecord.bytes.U > q.limit
    command := q; root := io.satp; interrupted := false.B
    response := 0.U.asTypeOf(response)
    response.tag := q.tag; response.context := q.context; response.cookie := q.cookie
    response.address := q.address
    phase := State.publish
    when(invalid) { response.status := 1.U }
      .elsewhen(q.action === VisibilityQueryAction.begin) {
        when(hasActive || alias.asUInt.orR) { response.status := 5.U }
          .elsewhen(!free.asUInt.orR) { response.status := 6.U }
          .otherwise {
            slot := freeSlot; write := true.B; payload := record(false.B, false.B, 0.U)
            phase := State.issue
          }
      }.elsewhen(q.action === VisibilityQueryAction.end) {
        val a = active(selectedSlot)
        when(!hasActive || a.address =/= q.address || a.cookie =/= q.cookie || a.conservative =/= q.conservative) {
          response.status := 5.U
        }.otherwise {
          slot := selectedSlot; write := true.B
          payload := record(true.B, a.seen && a.fault === 0.U, a.fault)
          response.available := true.B; response.value := a.seen && a.fault === 0.U
          response.status := a.fault; phase := State.issue
        }
      }.otherwise {
        when(alias.asUInt.orR) { response.status := 5.U }
          .otherwise { write := false.B; phase := State.issue }
      }
  }

  io.memoryRequest.valid := phase === State.issue
  io.memoryRequest.bits := 0.U.asTypeOf(io.memoryRequest.bits)
  io.memoryRequest.bits.address := command.address; io.memoryRequest.bits.isWrite := write
  io.memoryRequest.bits.sizeLog2 := 6.U; io.memoryRequest.bits.byteMask := ((BigInt(1) << 64) - 1).U
  io.memoryRequest.bits.writeData := payload; io.memoryRequest.bits.transactionId := 0.U
  when(io.memoryRequest.fire) { phase := State.waitMemory }
  io.memoryResponse.ready := phase === State.waitMemory ||
    (phase === State.issue && io.memoryRequest.fire)
  when(io.memoryResponse.valid) {
    assert(io.memoryResponse.ready, "unowned visibility-query memory response")
    assert(io.memoryResponse.bits.transactionId === 0.U)
  }
  when(io.memoryResponse.fire) {
    val failed = io.memoryResponse.bits.fault
    val stopped = io.cancel || interrupted
    when(failed) { response.status := 3.U; response.available := false.B; response.value := false.B }
      .elsewhen(stopped && response.status === 0.U) {
        response.status := 4.U; response.available := false.B; response.value := false.B
      }
    when(write) {
      when(command.action === VisibilityQueryAction.begin && !failed && !stopped) {
        assert(!live(slot))
        live(slot) := true.B; active(slot).context := command.context; active(slot).root := root
        active(slot).address := command.address; active(slot).cookie := command.cookie
        active(slot).conservative := command.conservative; active(slot).seen := false.B; active(slot).fault := 0.U
      }
      when(command.action === VisibilityQueryAction.end) { live(slot) := false.B }
    }.elsewhen(!failed && !stopped) {
      val words = io.memoryResponse.bits.readData.asTypeOf(Vec(16, UInt(32.W)))
      val available = words(4)(0); val fault = words(4)(4, 2)
      val badEncoding = words(0) =/= VisibilityQueryRecord.magic.U || words(1) =/= VisibilityQueryRecord.bytes.U ||
        words(4)(31, 5).orR || words(5) > 1.U || (!available && words(5).orR) ||
        (fault =/= 0.U && words(5).orR) || words.slice(7, 16).map(_.orR).reduce(_ || _)
      val badIdentity = words(2) =/= command.context || words(3) =/= command.cookie || words(6) =/= root
      response.status := Mux(badEncoding, 1.U, Mux(badIdentity, 2.U,
        Mux(command.action === VisibilityQueryAction.result && available, fault, 0.U)))
      response.available := available && !badEncoding && !badIdentity
      response.value := command.action === VisibilityQueryAction.result && available && !badEncoding &&
        !badIdentity && fault === 0.U && words(5)(0)
    }
    phase := State.publish
  }

  for (i <- 0 until contexts) {
    val hits = io.samples.map(e => e.valid && live(i) && e.bits.context === active(i).context && e.bits.root === active(i).root)
    when(hits.zip(io.samples).map { case (hit, e) => hit && e.bits.status === 0.U && e.bits.passingSamples.orR }.reduce(_ || _)) {
      active(i).seen := true.B
    }
    val failures = io.samples.zip(hits).map { case (e, hit) => (hit && e.bits.status =/= 0.U && e.bits.status =/= 4.U) -> 3.U(3.W) }
    val jobFailed = io.jobFault.valid && live(i) && io.jobFault.bits.context === active(i).context &&
      io.jobFault.bits.root === active(i).root && io.jobFault.bits.status =/= 0.U && io.jobFault.bits.status =/= 4.U
    val cancelled = io.cancel || hits.zip(io.samples).map { case (hit, e) => hit && e.bits.status === 4.U }.reduce(_ || _) ||
      (io.jobFault.valid && live(i) && io.jobFault.bits.context === active(i).context &&
        io.jobFault.bits.root === active(i).root && io.jobFault.bits.status === 4.U)
    when(live(i) && cancelled && active(i).fault === 0.U) { active(i).fault := 4.U }
    when(live(i) && (active(i).fault === 0.U || active(i).fault === 4.U) &&
      (jobFailed || failures.map(_._1).reduce(_ || _))) { active(i).fault := 3.U }
    for (j <- 0 until i) {
      assert(!(live(i) && live(j) && active(i).root === active(j).root && active(i).context === active(j).context),
        "duplicate active visibility-query context")
    }
  }
  when(io.cancel) {
    when(live.asUInt.orR || phase =/= State.idle) { resetPending := true.B }
    when(phase =/= State.idle) { interrupted := true.B }
  }
  when(resetPending && io.renderIdle && phase === State.idle) {
    live.foreach(_ := false.B); resetPending := false.B
  }
}
