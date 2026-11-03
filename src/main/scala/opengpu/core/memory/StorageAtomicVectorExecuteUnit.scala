package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}

/** Experimental CUSTOM-1 storage atomics. Parameter capture supplies the
  * per-lane replacement and comparison words. Atomic instructions use vs1 as
  * binding and vs2 as byte offset. They are not enabled in the CU decoder. */
object StorageAtomicVectorOpcode {
  private def function(i: UInt, n: Int): Bool = i(6, 0) === "h2b".U && i(31, 26) === n.U
  def parameters(i: UInt): Bool = function(i, 24) && i(14, 12) === 0.U && i(11, 7) === 0.U
  def atomic(i: UInt): Bool = function(i, 25) || (function(i, 26) && i(14, 12) < 2.U)
  def operation(i: UInt): UInt = Mux(function(i, 26), 8.U + i(14, 12), i(14, 12))
  def legal(i: UInt): Bool = parameters(i) || atomic(i)
}

class StorageAtomicVectorRequest(config: GpuConfig) extends VectorTextureRequest(config) {
  val sideEffectMask = UInt(config.lanes.W)
}

/** Executes lanes through one checked storage atomic at a time. Successful
  * prior lanes may have modified memory if a later lane faults; no vector-wide
  * atomicity is implied. Results publish only when the instruction succeeds.
  */
class StorageAtomicVectorExecuteUnit(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new StorageAtomicVectorRequest(config)))
    val commit = Decoupled(new VectorCommitRequest(config))
    val request = Decoupled(new StorageAtomicInstructionRequest(config))
    val response = Flipped(Decoupled(new StorageAtomicResult))
    val enabled = Input(Bool())
    val kernelStart = Input(Bool())
    val warpStart = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val cancel = Input(Bool())
    val busy = Output(Bool())
    val status = Output(UInt(3.W))
    val faultAddressValid = Output(Bool())
    val faultAddress = Output(UInt(32.W))
    val faultMask = Output(UInt(config.lanes.W))
    val pageFault = Output(Bool())
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
  private val indices = Reg(Vec(config.lanes, UInt(32.W)))
  private val offsets = Reg(Vec(config.lanes, UInt(32.W)))
  private val data = Reg(Vec(config.lanes, UInt(32.W)))
  private val status = RegInit(0.U(3.W))
  private val faultValid = RegInit(false.B)
  private val faultAddress = Reg(UInt(32.W))
  private val faultMask = RegInit(0.U(config.lanes.W))
  private val pageFault = RegInit(false.B)
  private val owned = RegInit(false.B)
  private val isParams = StorageAtomicVectorOpcode.parameters(instruction)
  private val isAtomic = StorageAtomicVectorOpcode.atomic(instruction)
  private val stopped = status =/= 0.U || io.cancel

  io.in.ready := state === State.idle && !io.kernelStart
  io.busy := state =/= State.idle
  io.status := status
  io.faultAddressValid := faultValid
  io.faultAddress := Mux(faultValid, faultAddress, 0.U)
  io.faultMask := faultMask
  io.pageFault := pageFault
  io.request.valid := state === State.offer
  io.request.bits.bindingIndex := indices(lane)
  io.request.bits.offset := offsets(lane)
  io.request.bits.operation := StorageAtomicVectorOpcode.operation(instruction)
  io.request.bits.operand := values(warp)(lane)
  io.request.bits.compareValue := comparisons(warp)(lane)
  io.request.bits.warpId := warp
  io.request.bits.tag := lane
  io.response.ready := state === State.waitResult
  io.commit.valid := state === State.commit
  io.commit.bits := 0.U.asTypeOf(io.commit.bits)
  io.commit.bits.writeback.warpId := warp
  io.commit.bits.writeback.vd := Mux(isAtomic, instruction(11, 7), 0.U)
  io.commit.bits.writeback.data := Mux(status === 0.U, data, 0.U.asTypeOf(data))
  io.commit.bits.writesVd := status === 0.U && isAtomic
  io.commit.bits.writesScalar := status === 0.U && isParams
  io.commit.bits.pc := pc
  io.commit.bits.warpActiveMask := active
  when(io.kernelStart) {
    assert(state === State.idle, "storage atomic launch changed before instruction drain")
    valid.foreach(_ := 0.U)
  }
  when(io.warpStart.valid) {
    assert(state === State.idle || warp =/= io.warpStart.bits,
      "storage atomic warp slot reused before instruction drain")
    valid(io.warpStart.bits) := 0.U
  }
  when(state =/= State.idle && state =/= State.commit && io.cancel && status === 0.U) {
    status := StorageAtomicStatus.cancelled.U
  }
  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        val q = io.in.bits; val d = q.issued.decode
        assert(d.warpId < config.warps.U)
        instruction := d.instruction; warp := d.warpId; pc := d.pc; active := d.activeMask
        val execution = q.executionMask & d.activeMask
        faultMask := execution
        val selected = Mux(StorageAtomicVectorOpcode.atomic(d.instruction), execution & q.sideEffectMask, execution)
        mask := selected; remaining := selected
        indices := q.issued.vs1Data; offsets := q.issued.vs2Data; data := q.issued.oldVdData
        status := Mux(!io.enabled, StorageAtomicStatus.invalid.U,
          Mux(io.cancel, StorageAtomicStatus.cancelled.U, 0.U))
        faultValid := false.B; faultAddress := 0.U; pageFault := false.B; owned := false.B
        state := State.check
      }
    }
    is(State.check) {
      when(stopped) { state := State.commit }
        .elsewhen(!StorageAtomicVectorOpcode.legal(instruction)) { status := StorageAtomicStatus.invalid.U; state := State.commit }
        .elsewhen(isParams) {
          for (l <- 0 until config.lanes) { when(mask(l)) { values(warp)(l) := indices(l); comparisons(warp)(l) := offsets(l) } }
          valid(warp) := valid(warp) | mask
          state := State.commit
        }.elsewhen(isAtomic && (mask & ~valid(warp)).orR) {
          status := StorageAtomicStatus.invalid.U; state := State.commit
        }.otherwise { state := State.next }
    }
    is(State.next) {
      when(stopped || !remaining.orR) { state := State.commit }
        .otherwise { lane := PriorityEncoder(remaining); state := State.offer }
    }
    // Once offered, the service must accept and drain even if cancel arrives.
    is(State.offer) { when(io.request.fire) { owned := true.B; state := State.waitResult } }
    is(State.waitResult) {
      when(io.response.fire) {
        val r = io.response.bits
        assert(owned && r.tag === lane)
        owned := false.B
        when(r.status =/= 0.U && r.status =/= StorageAtomicStatus.cancelled.U) {
          status := r.status; faultValid := r.faultAddressValid; faultAddress := r.faultAddress
          faultMask := UIntToOH(lane, config.lanes)
          pageFault := r.pageFault
        }.elsewhen(status === 0.U && !io.cancel) { status := r.status }
        when(r.status === 0.U && !stopped) {
          when(isAtomic) { data(lane) := r.value }
          remaining := remaining & ~UIntToOH(lane, config.lanes)
          state := State.next
        }.otherwise { state := State.commit }
      }
    }
    is(State.commit) {
      assert(!owned, "storage atomic instruction escaped an accepted request")
      when(io.commit.fire) { state := State.idle }
    }
  }
}
