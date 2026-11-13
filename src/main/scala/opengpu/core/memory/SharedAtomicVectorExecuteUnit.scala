package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}

/** Checked CUSTOM-1 local atomics. Parameters capture replacement/compare per
  * warp and lane; operations add per-lane byte offsets to a scalar shared base.
  * An rs2 field of zero means a literal zero offset (not vector register v0).
  * Scalar RV32A encodings and their once-per-warp semantics are unchanged.
  */
object SharedAtomicVectorOpcode {
  private def function(i: UInt, n: Int): Bool = i(6,0) === "h2b".U && i(31,26) === n.U
  def parameters(i: UInt): Bool = function(i,30) && i(14,12) === 0.U && i(11,7) === 0.U
  def atomic(i: UInt): Bool = (function(i,31) || (function(i,32) && i(14,12) < 2.U))
  def operation(i: UInt): UInt = Mux(function(i,32), 8.U + i(14,12), i(14,12))
  def legal(i: UInt): Bool = parameters(i) || atomic(i)
}

class SharedAtomicVectorRequest(config: GpuConfig) extends VectorTextureRequest(config) {
  val sideEffectMask = UInt(config.lanes.W)
}

/** One irrevocable SRAM request at a time. Earlier lanes are not rolled back
  * on a later fault, but a failed instruction publishes no vector result.
  * The existing vector issue/scoreboard owns the warp until this commit.
  */
class SharedAtomicVectorExecuteUnit(config: GpuConfig = GpuConfig()) extends Module {
  require(BigInt(config.sharedMemoryBase) >= 0 &&
    BigInt(config.sharedMemoryBase) + config.sharedMemoryBytes <= (BigInt(1) << 32))
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SharedAtomicVectorRequest(config)))
    val commit = Decoupled(new VectorCommitRequest(config))
    val fault = Valid(new VectorMemoryFault(config))
    val request = Decoupled(new SharedAtomicRequest(config))
    val response = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val warpStart = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private object State extends ChiselEnum { val idle, check, next, offer, waitResult, commit = Value }
  private val state = RegInit(State.idle)
  private val values = Reg(Vec(config.warps, Vec(config.lanes, UInt(32.W))))
  private val comparisons = Reg(Vec(config.warps, Vec(config.lanes, UInt(32.W))))
  private val valid = RegInit(VecInit(Seq.fill(config.warps)(0.U(config.lanes.W))))
  private val instruction = Reg(UInt(32.W))
  private val warp = Reg(UInt(config.warpIdWidth.W))
  private val pc = Reg(UInt(32.W))
  private val active = Reg(UInt(config.lanes.W))
  private val mask = Reg(UInt(config.lanes.W))
  private val remaining = Reg(UInt(config.lanes.W))
  private val lane = Reg(UInt(math.max(1, log2Ceil(config.lanes)).W))
  private val addresses = Reg(Vec(config.lanes, UInt(33.W)))
  private val second = Reg(Vec(config.lanes, UInt(32.W)))
  private val data = Reg(Vec(config.lanes, UInt(32.W)))
  private val failed = RegInit(false.B)
  private val faultMask = Reg(UInt(config.lanes.W))
  private val owned = RegInit(false.B)
  private val isParams = SharedAtomicVectorOpcode.parameters(instruction)
  private val isAtomic = SharedAtomicVectorOpcode.atomic(instruction)
  private val stopped = failed || io.cancel

  io.in.ready := state === State.idle && !io.warpStart.valid
  io.busy := state =/= State.idle
  io.request.valid := state === State.offer
  io.request.bits.warpId := warp
  io.request.bits.address := addresses(lane)(31,0)
  io.request.bits.operand := values(warp)(lane)
  io.request.bits.compareValue := comparisons(warp)(lane)
  io.request.bits.operation := SharedAtomicVectorOpcode.operation(instruction)
  io.request.bits.cachePolicy := 0.U // SRAM; never routed to global translation.
  io.response.ready := state === State.waitResult
  io.commit.valid := state === State.commit
  io.commit.bits := 0.U.asTypeOf(io.commit.bits)
  io.commit.bits.writeback.warpId := warp
  io.commit.bits.writeback.vd := Mux(isAtomic, instruction(11,7), 0.U)
  io.commit.bits.writeback.data := Mux(failed, 0.U.asTypeOf(data), data)
  io.commit.bits.writesVd := !failed && isAtomic
  io.commit.bits.writesScalar := !failed && isParams
  io.commit.bits.pc := pc
  io.commit.bits.warpActiveMask := active
  io.fault.valid := io.commit.valid && failed
  io.fault.bits := 0.U.asTypeOf(io.fault.bits)
  io.fault.bits.warpId := warp
  io.fault.bits.pc := pc
  io.fault.bits.warpActiveMask := active
  io.fault.bits.faultMask := faultMask
  io.fault.bits.vd := instruction(11,7)
  io.fault.bits.writeVd := isAtomic
  io.fault.bits.isStore := isAtomic
  io.fault.bits.addresses := VecInit(addresses.map(_(31,0)))

  when(io.warpStart.valid) {
    assert(state === State.idle || warp =/= io.warpStart.bits,
      "shared atomic warp slot reused before instruction drain")
    valid(io.warpStart.bits) := 0.U
  }
  when(state =/= State.idle && state =/= State.commit && io.cancel) { failed := true.B }
  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        val q = io.in.bits; val d = q.issued.decode
        assert(d.warpId < config.warps.U)
        instruction := d.instruction; warp := d.warpId; pc := d.pc; active := d.activeMask
        val execution = q.executionMask & d.activeMask
        val selected = Mux(SharedAtomicVectorOpcode.atomic(d.instruction), execution & q.sideEffectMask, execution)
        mask := selected; remaining := selected; faultMask := selected
        for(l <- 0 until config.lanes) {
          addresses(l) := Mux(SharedAtomicVectorOpcode.parameters(d.instruction),
            q.issued.vs1Data(l), q.issued.scalarRs1Data +& Mux(d.instruction(24,20) === 0.U, 0.U, q.issued.vs2Data(l)))
        }
        second := q.issued.vs2Data; data := q.issued.oldVdData
        failed := io.cancel; owned := false.B
        state := State.check
      }
    }
    is(State.check) {
      when(stopped) { state := State.commit }
        .elsewhen(!SharedAtomicVectorOpcode.legal(instruction)) { failed := true.B; state := State.commit }
        .elsewhen(isParams) {
          for(l <- 0 until config.lanes) { when(mask(l)) {
            values(warp)(l) := addresses(l)(31,0)
            comparisons(warp)(l) := second(l)
          } }
          valid(warp) := valid(warp) | mask
          state := State.commit
        }.elsewhen((mask & ~valid(warp)).orR) { failed := true.B; state := State.commit }
        .otherwise { state := State.next }
    }
    is(State.next) {
      when(stopped || !remaining.orR) { state := State.commit }
        .otherwise {
          val selected = PriorityEncoder(remaining)
          val address = addresses(selected)
          lane := selected
          when(address(1,0).orR || address < config.sharedMemoryBase.U ||
              (address +& 4.U) > (BigInt(config.sharedMemoryBase) + config.sharedMemoryBytes).U(33.W)) {
            failed := true.B; faultMask := UIntToOH(selected, config.lanes); state := State.commit
          }.otherwise { state := State.offer }
        }
    }
    // An offered request stays immutable even if cancellation arrives.
    is(State.offer) { when(io.request.fire) { owned := true.B; state := State.waitResult } }
    is(State.waitResult) {
      when(io.response.fire) {
        assert(owned && io.response.bits.warpId === warp, "shared atomic response lost its owner")
        owned := false.B
        when(io.response.bits.fault) {
          failed := true.B; faultMask := UIntToOH(lane, config.lanes); state := State.commit
        }.elsewhen(stopped) { state := State.commit }
          .otherwise {
            data(lane) := io.response.bits.oldValue
            remaining := remaining & ~UIntToOH(lane, config.lanes)
            state := State.next
          }
      }
    }
    is(State.commit) {
      assert(!owned, "shared atomic instruction escaped an accepted SRAM request")
      when(io.commit.fire) { state := State.idle }
    }
  }
}
