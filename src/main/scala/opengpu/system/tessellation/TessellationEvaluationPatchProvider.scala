package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.tessellation._

/** One retained inspection credit, shared by all physical CUs. This accesses the actual current PatchContext; no
  * snapshot/CPU return or memory ID exists.
  */
class TessellationEvaluationPatchProvider(config: GpuConfig, idWidth: Int, cus: Int) extends Module {
  require(cus > 0 && idWidth > 0)
  require(config.xLen == 32 && config.lanes <= 32 && config.warps >= 2 && isPow2(config.warps))
  val io = IO(new Bundle {
    val ports = Flipped(Vec(cus, new SharedCuEvaluationPatchPort(config)))
    val owned = Input(UInt(cus.W)); val taskIds = Input(Vec(cus, UInt(idWidth.W)))
    val commandId = Input(UInt(idWidth.W)); val active = Input(Bool())
    val scope = Flipped(Valid(new TessellationEvaluationPatchBinding))
    val inspect = Decoupled(new TessellationPatchAccess); val reply = Flipped(Decoupled(new TessellationContextReply))
    val cancel = Input(Bool()); val busy = Output(Bool()); val fault = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, offer, reply, result = Value }
  private val state = RegInit(State.idle); private val client = Reg(UInt(math.max(1, log2Ceil(cus)).W))
  private val request = Reg(new TessellationEvaluationPatchRequest(config))
  private val expected = Reg(new TessellationEvaluationPatchBinding); private val expectedValid = Reg(Bool())
  private val result = Reg(new TessellationWarpPatchResult(config.warps, config.lanes))
  private val pending = Reg(UInt(config.lanes.W)); private val lane = Reg(UInt(math.max(1, log2Ceil(config.lanes)).W))
  private val cancelled = RegInit(false.B); private val stop = cancelled || io.cancel
  private val selected = VecInit((0 until cus).map(i => io.active && io.owned(i) && io.taskIds(i) === io.commandId))
  assert(PopCount(selected) <= 1.U, "TES inspection must belong to one command/CU")
  private val offers = Module(new RRArbiter(new TessellationEvaluationPatchRequest(config), cus))
  for (i <- 0 until cus) {
    io.ports(i).cancel := selected(i) && io.cancel
    offers.io.in(i).valid := selected(i) && io.ports(i).request.valid
    offers.io.in(i).bits := io.ports(i).request.bits
    io.ports(i).request.ready := selected(i) && offers.io.in(i).ready
    io.ports(i).result.valid := state === State.result && client === i.U
    io.ports(i).result.bits := result
  }
  offers.io.out.ready := state === State.idle
  private val resultReady = Mux1H((0 until cus).map(i => (client === i.U) -> io.ports(i).result.ready))
  io.inspect.valid := state === State.offer; io.inspect.bits := 0.U.asTypeOf(io.inspect.bits)
  io.inspect.bits.output := true.B; io.inspect.bits.vertex := request.vertices(lane)(5, 0)
  io.inspect.bits.component := request.addresses(lane)(9, 3)
  io.inspect.bits.perPatch := request.addresses(lane)(1); io.inspect.bits.tessLevel := request.addresses(lane)(2)
  io.reply.ready := state === State.reply
  io.busy := state =/= State.idle
  io.fault := state === State.result && result.status === 1.U
  when(state =/= State.idle && state =/= State.result && io.cancel) { cancelled := true.B }
  when(offers.io.out.fire) {
    request := offers.io.out.bits; client := offers.io.chosen
    expected := io.scope.bits; expectedValid := io.scope.valid
    pending := offers.io.out.bits.mask; lane := PriorityEncoder(offers.io.out.bits.mask)
    result := 0.U.asTypeOf(result); result.warp := offers.io.out.bits.warp
    cancelled := io.cancel; state := State.check
  }
  when(state === State.check) {
    val bad = (0 until config.lanes)
      .map(i =>
        request.mask(i) && !TessellationEvaluationPatchBinding
          .addressLegal(request.vertices(i), request.addresses(i), expected.layout)
      )
      .reduce(_ || _)
    when(
      !expectedValid || !TessellationEvaluationPatchBinding.legal(
        expected
      ) || request.scope.asUInt =/= expected.asUInt || bad
    ) { result.status := 1.U; state := State.result }
      .elsewhen(stop) { result.status := 2.U; state := State.result }
      .elsewhen(!pending.orR) { state := State.result }
      .otherwise { state := State.offer }
  }
  // Preserve the accepted credit and an already held inspection through cancel.
  when(io.inspect.fire) { state := State.reply }
  when(io.reply.fire) {
    val bit = UIntToOH(lane, config.lanes); val next = pending & ~bit
    result.retired := result.retired | bit; result.data(lane) := io.reply.bits.result.data
    when(io.reply.bits.source =/= 2.U || io.reply.bits.invocation =/= 0.U) {
      result.status := 1.U; state := State.result
    }
      .elsewhen(stop) { result.status := 2.U; state := State.result }
      .elsewhen(io.reply.bits.result.status =/= 0.U) { result.status := 1.U; state := State.result }
      .elsewhen(!next.orR) { state := State.result }
      .otherwise { pending := next; lane := PriorityEncoder(next); state := State.offer }
  }
  when(state === State.result) {
    assert(
      io.active && io.owned(client) && io.taskIds(client) === io.commandId,
      "TES result lost its original owner before acceptance"
    )
    when(resultReady) { state := State.idle }
  }
}

/** Three-way lock covers the offered request and its complete accepted credit. No issuer may replace bits while stalled
  * or steal another issuer's reply.
  */
class TessellationInspectionArbiter extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(3, Decoupled(new TessellationPatchAccess)))
    val replies = Vec(3, Decoupled(new TessellationContextReply))
    val out = Decoupled(new TessellationPatchAccess); val reply = Flipped(Decoupled(new TessellationContextReply))
    val busy = Output(Bool())
  })
  private val arb = Module(new RRArbiter(new TessellationPatchAccess, 3))
  private val locked = RegInit(false.B); private val credit = RegInit(false.B)
  private val owner = Reg(UInt(2.W))
  for (i <- 0 until 3) {
    arb.io.in(i).valid := io.in(i).valid && !locked && !credit; arb.io.in(i).bits := io.in(i).bits
    io.in(i).ready := !credit && Mux(locked, owner === i.U && io.out.ready, arb.io.in(i).ready)
    io.replies(i).valid := credit && owner === i.U && io.reply.valid; io.replies(i).bits := io.reply.bits
  }
  io.out.valid := !credit && Mux(locked, io.in(owner).valid, arb.io.out.valid)
  io.out.bits := Mux(locked, io.in(owner).bits, arb.io.out.bits)
  arb.io.out.ready := io.out.ready && !locked && !credit
  when(io.out.valid && !io.out.ready && !locked) { locked := true.B; owner := arb.io.chosen }
  when(io.out.fire) { credit := true.B; when(!locked) { owner := arb.io.chosen }; locked := false.B }
  io.reply.ready := credit && Mux1H((0 until 3).map(i => (owner === i.U) -> io.replies(i).ready))
  when(io.reply.fire) { credit := false.B }
  io.busy := locked || credit
}
