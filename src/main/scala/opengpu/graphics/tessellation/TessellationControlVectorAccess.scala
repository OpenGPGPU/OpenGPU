package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}

object TessellationControlVectorOperation {
  val parameters = 0; val load = 1; val store = 2
}
/** Internal CUSTOM-1 encodings; admission requires a task-owned patch. */
object TessellationControlVectorOpcode {
  private def function(i: UInt, n: Int): Bool = i(6,0) === "h2b".U && i(31,26) === n.U && i(14,12) === 0.U
  def parameters(i: UInt): Bool = function(i,20) && i(11,7) === 0.U
  def load(i: UInt): Bool = function(i,21)
  def store(i: UInt): Bool = function(i,22) && i(11,7) === 0.U
  def legal(i: UInt): Bool = parameters(i) || load(i) || store(i)
  def operation(i: UInt): UInt = Mux(load(i), TessellationControlVectorOperation.load.U,
    Mux(store(i), TessellationControlVectorOperation.store.U, TessellationControlVectorOperation.parameters.U))
}
class TessellationControlVectorRequest(config: GpuConfig) extends Bundle {
  val operation = UInt(2.W); val base = UInt(5.W)
  val vector = new VectorTextureRequest(config); val sideEffectMask = UInt(config.lanes.W)
}

/** Adapt one decoded vector instruction to the patch scheduler. The issuer
  * owns task availability and warp-to-invocation mapping. Store parameters
  * are private to a warp slot and invalidated when that slot is reused.
  */
class TessellationControlVectorAccess(config: GpuConfig = GpuConfig()) extends Module {
  require(config.warps >= 2 && isPow2(config.warps) && config.lanes >= 1 && config.lanes <= 32)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new TessellationControlVectorRequest(config)))
    val request = Decoupled(new TessellationWarpPatchRequest(config.warps, config.lanes))
    val result = Flipped(Decoupled(new TessellationWarpPatchResult(config.warps, config.lanes)))
    val commit = Decoupled(new VectorCommitRequest(config))
    val kernelStart = Input(Bool()); val warpStart = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val cancel = Input(Bool())
    /** The patch context has accepted an error result and will not accept new requests. */
    val terminal = Input(Bool()); val busy = Output(Bool()); val status = Output(UInt(2.W))
  })
  private val idle :: check :: offer :: waitResult :: commit :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val saved = Reg(new TessellationControlVectorRequest(config))
  private val values = Reg(Vec(config.warps, Vec(config.lanes, UInt(32.W))))
  private val valid = RegInit(VecInit(Seq.fill(config.warps)(0.U(config.lanes.W))))
  private val selected = Reg(UInt(config.lanes.W)); private val status = RegInit(0.U(2.W))
  private val data = Reg(Vec(config.lanes, UInt(32.W)))
  private val cancelled = RegInit(false.B)
  private val warp = saved.vector.issued.decode.warpId
  private val isParams = saved.operation === TessellationControlVectorOperation.parameters.U
  private val isLoad = saved.operation === TessellationControlVectorOperation.load.U
  private val isStore = saved.operation === TessellationControlVectorOperation.store.U
  private val stopped = cancelled || io.cancel || io.terminal
  io.busy := state =/= idle; io.status := status
  io.in.ready := state === idle && !io.kernelStart && !io.cancel && !io.terminal
  io.request.valid := state === offer
  io.request.bits := 0.U.asTypeOf(io.request.bits)
  io.request.bits.warp := warp; io.request.bits.base := saved.base; io.request.bits.mask := selected
  for (i <- 0 until config.lanes) {
    val address = saved.vector.issued.vs2Data(i)
    val a = io.request.bits.access(i)
    a.output := address(0); a.perPatch := address(1); a.tessLevel := address(2)
    a.write := isStore; a.vertex := saved.vector.issued.vs1Data(i)(5,0)
    a.component := address(9,3); a.data := values(warp)(i)
  }
  io.result.ready := state === waitResult
  io.commit.valid := state === commit
  io.commit.bits := 0.U.asTypeOf(io.commit.bits)
  io.commit.bits.writeback.warpId := warp
  io.commit.bits.writeback.vd := Mux(isLoad, saved.vector.issued.decode.instruction(11,7), 0.U)
  io.commit.bits.writeback.data := Mux(status === 0.U, data, 0.U.asTypeOf(data))
  io.commit.bits.writesVd := status === 0.U && isLoad
  io.commit.bits.writesScalar := status === 0.U && (isParams || isStore)
  io.commit.bits.pc := saved.vector.issued.decode.pc
  io.commit.bits.warpActiveMask := saved.vector.issued.decode.activeMask
  when(io.kernelStart) {
    assert(state === idle, "patch kernel changed before instruction drain")
    valid.foreach(_ := 0.U)
  }
  when(io.warpStart.valid) {
    assert(state === idle || warp =/= io.warpStart.bits, "patch warp reused before instruction drain")
    valid(io.warpStart.bits) := 0.U
  }
  when(state =/= idle && state =/= commit && (io.cancel || io.terminal)) { cancelled := true.B }
  when(io.in.fire) {
    val q = io.in.bits; val active = q.vector.executionMask & q.vector.issued.decode.activeMask
    saved := q; selected := Mux(q.operation === TessellationControlVectorOperation.store.U, active & q.sideEffectMask, active)
    data := q.vector.issued.oldVdData; status := 0.U; cancelled := false.B; state := check
  }
  when(state === check) {
    val malformed = (0 until config.lanes).map(i => selected(i) &&
      (saved.vector.issued.vs1Data(i)(31,6).orR || saved.vector.issued.vs2Data(i)(31,10).orR)).reduce(_ || _)
    when(stopped) { status := 2.U; state := commit }
      .elsewhen(saved.operation > TessellationControlVectorOperation.store.U) { status := 1.U; state := commit }
      .elsewhen(isParams) {
        for (i <- 0 until config.lanes) { when(selected(i)) { values(warp)(i) := saved.vector.issued.vs1Data(i) } }
        valid(warp) := valid(warp) | selected; state := commit
      }.elsewhen(malformed || (isStore && (selected & ~valid(warp)).orR)) { status := 1.U; state := commit }
      .elsewhen(!selected.orR) { state := commit }
      .otherwise { state := offer }
  }
  // Do not withdraw an offered instruction on cancellation.
  when(io.request.fire) { state := waitResult }
  // A terminal patch result closes the receiver; only an unaccepted offer may
  // be retired locally. An accepted request still owns its scheduler result.
  when(state === offer && io.terminal && !io.request.fire) { status := 2.U; state := commit }
  when(io.result.fire) {
    val bad = io.result.bits.warp =/= warp || io.result.bits.retired =/= selected || io.result.bits.status =/= 0.U
    when(stopped || io.result.bits.status === 2.U) { status := 2.U }
      .elsewhen(bad) { status := 1.U }
      .otherwise { for (i <- 0 until config.lanes) { when(selected(i)) { data(i) := io.result.bits.data(i) } } }
    state := commit
  }
  when(io.commit.fire) { state := idle }
}
