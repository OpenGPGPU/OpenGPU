package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** A checked instruction snapshot. Operand tuples remain in the execution
  * unit's per-warp bank until this instruction retires or resumes there.
  * Only coordinates and offsets are copied into the lane issue contexts.
  */
class NativeTextureStreamInstruction(config: GpuConfig) extends Bundle {
  val template = new NativeRgba8TextureLaneTemplate
  val resources = new ShaderTextureResources
  val warp = UInt(config.warpIdWidth.W)
  val pc = UInt(32.W)
  val vd = UInt(5.W)
  val active = UInt(config.lanes.W)
  val mask = UInt(config.lanes.W)
  val lane = UInt(math.max(1, log2Ceil(config.lanes)).W)
  val component = UInt(2.W)
  val unit = UInt(32.W)
  val coordinates = Vec(config.lanes, Vec(2, UInt(32.W)))
  val offsets = Vec(config.lanes, Vec(2, SInt(5.W)))
  val data = Vec(config.lanes, UInt(32.W))
}

class NativeTextureStreamCompletion(config: GpuConfig) extends Bundle {
  val instruction = new NativeTextureStreamInstruction(config)
  // Invalidation returns an unfinished instruction to the general resolver.
  val completed = Bool()
  val status = UInt(3.W)
  val faultAddressValid = Bool()
  val faultAddress = UInt(32.W)
}

/** Bounded lane contexts share one request port and the existing physical
  * cluster. A context never aliases a live warp or reuses its response tags
  * before public retirement. General descriptor/LOD execution stays singular.
  */
class NativeTextureInstructionStream(config: GpuConfig, contexts: Int) extends Module {
  require(contexts > 0 && contexts <= config.warps)
  private val laneWidth = math.max(1, log2Ceil(config.lanes))
  private val contextWidth = math.max(1, log2Ceil(contexts))
  require(laneWidth + contextWidth < 31)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new NativeTextureStreamInstruction(config)))
    val out = Decoupled(new NativeTextureStreamCompletion(config))
    val grouped = new NativeRgba8TextureClientIO
    val cancel = Input(Bool())
    val invalidate = Input(Bool())
    val busy = Output(Bool())
    val warpBusy = Output(UInt(config.warps.W))
  })
  private val live = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val done = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val instruction = Reg(Vec(contexts, new NativeTextureStreamInstruction(config)))
  private val pending = RegInit(VecInit(Seq.fill(contexts)(0.U(config.lanes.W))))
  private val issuedAll = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val noFill = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val status = RegInit(VecInit(Seq.fill(contexts)(0.U(3.W))))
  private val faultValid = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val faultAddress = Reg(Vec(contexts, UInt(32.W)))
  private val primaryValid = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  private val primaryLane = Reg(Vec(contexts, UInt(laneWidth.W)))
  private def row(index: UInt): UInt = if (contexts == 1) 0.U else index
  private def privateTag(c: UInt, l: UInt): UInt =
    (BigInt(1) << 31).U(32.W) | (c.pad(32) << laneWidth) | l.pad(32)
  private val free = PriorityEncoder(VecInit(live.map(x => !x)).asUInt)
  io.busy := live.asUInt.orR
  io.warpBusy := (0 until contexts).map(c => Mux(live(c), UIntToOH(instruction(c).warp, config.warps), 0.U)).reduce(_ | _)
  io.in.ready := !live.asUInt.andR && !io.warpBusy(io.in.bits.warp)
  when(io.in.fire) {
    val c = row(free)
    assert(io.in.bits.warp < config.warps.U)
    live(c) := true.B; done(c) := false.B
    instruction(c) := io.in.bits
    pending(c) := 0.U; issuedAll(c) := false.B; noFill(c) := io.invalidate
    status(c) := Mux(io.cancel, 4.U, 0.U); faultValid(c) := false.B
    faultAddress(c) := 0.U; primaryValid(c) := false.B
  }

  private val issue = Module(new RRArbiter(new TextureRgba8GroupRequest, contexts))
  // Once accepted internally, this queue owns the offer through external
  // stalls, invalidation and cancellation. Its tag remains live in pending.
  private val requests = Module(new Queue(new TextureRgba8GroupRequest, 2))
  requests.io.enq <> issue.io.out
  io.grouped.request <> requests.io.deq
  private val reply = io.grouped.response.bits
  private val replyContext = row(reply.tag(laneWidth + contextWidth - 1, laneWidth))
  private val replyLane = reply.tag(laneWidth - 1, 0)
  io.grouped.response.ready := true.B
  when(io.grouped.response.fire) {
    assert(reply.tag === privateTag(replyContext, replyLane) && replyContext < contexts.U && replyLane < config.lanes.U)
    assert(live(replyContext) && !done(replyContext) && pending(replyContext)(replyLane))
    assert(reply.identity.asUInt === instruction(replyContext).template.group.lookup.table.identity.asUInt)
    assert(!reply.fallback, "checked nonmip lane unexpectedly requested general fallback")
  }
  for (c <- 0 until contexts) {
    val q = instruction(c)
    val stop = status(c) =/= 0.U || io.cancel || noFill(c) || io.invalidate
    val offer = live(c) && !done(c) && !issuedAll(c) && q.mask(q.lane) && !stop
    issue.io.in(c).valid := offer
    issue.io.in(c).bits := q.template.group
    issue.io.in(c).bits.tag := privateTag(c.U, q.lane)
    issue.io.in(c).bits.coordinates := q.coordinates(q.lane)
    issue.io.in(c).bits.offsets := q.offsets(q.lane)
    val accepting = issue.io.in(c).fire
    val replying = io.grouped.response.fire && replyContext === c.U
    val nextPending = (pending(c) | Mux(accepting, UIntToOH(q.lane, config.lanes), 0.U)) &
      ~Mux(replying, UIntToOH(replyLane, config.lanes), 0.U)
    when(live(c) && !done(c)) {
      pending(c) := nextPending
      when(io.invalidate) { noFill(c) := true.B }
      when(io.cancel && status(c) === 0.U) { status(c) := 4.U }
      when(accepting || (!issuedAll(c) && !q.mask(q.lane) && !stop)) {
        when(accepting) { assert(!pending(c)(q.lane)) }
        when(q.lane === (config.lanes - 1).U) { issuedAll(c) := true.B }
          .otherwise { q.lane := q.lane + 1.U }
      }
      when(replying) {
        val primary = reply.status =/= 0.U && reply.status =/= 4.U
        when(primary && (!primaryValid(c) || replyLane < primaryLane(c))) {
          primaryValid(c) := true.B; primaryLane(c) := replyLane
          status(c) := reply.status; faultValid(c) := reply.faultAddressValid
          faultAddress(c) := reply.faultAddress
        }.elsewhen(reply.status === 4.U && status(c) === 0.U) { status(c) := 4.U }
        when(reply.status === 0.U && status(c) === 0.U && !io.cancel) {
          q.data(replyLane) := TextureImageSwizzle(reply.rgba, 0.U, q.template.swizzle)(q.component)
        }
      }
      // Use the old pending value so the final reply and its primary fault
      // are registered before a completion can become publicly visible.
      when(!pending(c).orR && !accepting && (issuedAll(c) || stop)) { done(c) := true.B }
    }
  }
  private val cancels = Module(new RRArbiter(new BankedTextureGroupCancel, contexts * config.lanes))
  for (c <- 0 until contexts; l <- 0 until config.lanes) {
    val i = c * config.lanes + l
    cancels.io.in(i).valid := live(c) && !done(c) && pending(c)(l) && (status(c) =/= 0.U || io.cancel)
    cancels.io.in(i).bits.identity := instruction(c).template.group.lookup.table.identity
    cancels.io.in(i).bits.tag := privateTag(c.U, l.U)
  }
  io.grouped.cancelGroup.valid := cancels.io.out.valid
  io.grouped.cancelGroup.bits := cancels.io.out.bits
  cancels.io.out.ready := true.B
  private val invalidateEdge = io.invalidate && !RegNext(io.invalidate, false.B)
  io.grouped.invalidate := invalidateEdge ||
    (io.grouped.response.fire && (io.invalidate || noFill(replyContext)))

  private val outputHeld = RegInit(false.B)
  private val outputRow = Reg(UInt(contextWidth.W))
  private val selected = row(Mux(outputHeld, outputRow, PriorityEncoder(done.asUInt)))
  io.out.valid := done.asUInt.orR
  io.out.bits := 0.U.asTypeOf(io.out.bits)
  io.out.bits.instruction := instruction(selected)
  io.out.bits.completed := issuedAll(selected) || status(selected) =/= 0.U
  io.out.bits.status := status(selected)
  io.out.bits.faultAddressValid := faultValid(selected)
  io.out.bits.faultAddress := faultAddress(selected)
  when(io.out.valid && !io.out.ready) { outputHeld := true.B; outputRow := selected }
  when(io.out.fire) {
    assert(!pending(selected).orR)
    live(selected) := false.B; done(selected) := false.B; outputHeld := false.B
  }
}
