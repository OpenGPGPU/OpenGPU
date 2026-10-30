package opengpu.graphics

import chisel3._
import chisel3.util._

/** A warp parks its participating lanes at one control-shader barrier.
  * Lane i owns invocation base+i. Masks from simultaneously parked warps must
  * be disjoint and fit within the patch's 32 invocation limit.
  */
class TessellationWarpPark(warps: Int, laneCount: Int) extends Bundle {
  val warp = UInt(log2Ceil(warps).W)
  val base = UInt(5.W)
  val lanes = UInt(laneCount.W)
  val barrierId = UInt(32.W)
}
class TessellationWarpResume(warps: Int) extends Bundle {
  val warp = UInt(log2Ceil(warps).W)
  val barrierId = UInt(32.W)
  val epoch = UInt(32.W)
  /** 0 released, 1 invalid mask/overlap, 2 canceled. */
  val status = UInt(2.W)
}

/** Converts independently parked SIMT warps into per-invocation barrier
  * handshakes. All parked warps can make progress concurrently, so a patch
  * larger than one warp does not require one CU execution slot per invocation.
  * The enclosing scheduler must stop issuing work when cancel is asserted and
  * must propagate an accepted terminal result to retire parked warps.
  */
class TessellationWarpBarrier(warps: Int, lanes: Int) extends Module {
  require(warps >= 2 && isPow2(warps) && lanes >= 1 && lanes <= 32)
  val io = IO(new Bundle {
    val park = Flipped(Decoupled(new TessellationWarpPark(warps, lanes)))
    val arrive = Decoupled(new TessellationBarrierArrival)
    val release = Flipped(Decoupled(new TessellationBarrierRelease))
    val resume = Decoupled(new TessellationWarpResume(warps))
    val cancel = Input(Bool())
    /** The context has accepted a terminal result and cannot accept arrivals. */
    val terminal = Input(Bool())
    val busy = Output(Bool())
  })
  private val valid = RegInit(VecInit(Seq.fill(warps)(false.B)))
  private val mask = Reg(Vec(warps, UInt(32.W)))
  private val pending = Reg(Vec(warps, UInt(32.W)))
  private val remaining = Reg(Vec(warps, UInt(32.W)))
  private val id = Reg(Vec(warps, UInt(32.W)))
  private val epoch = Reg(Vec(warps, UInt(32.W)))
  private val epochSet = RegInit(VecInit(Seq.fill(warps)(false.B)))
  private val done = RegInit(VecInit(Seq.fill(warps)(false.B)))
  private val status = Reg(Vec(warps, UInt(2.W)))
  private val aborting = RegInit(false.B)
  private val sendValid = RegInit(false.B)
  private val sendWarp = Reg(UInt(log2Ceil(warps).W))
  private val sendInvocation = Reg(UInt(5.W))
  private val sendId = Reg(UInt(32.W))
  private val resumeValid = RegInit(false.B)
  private val resumeBits = Reg(new TessellationWarpResume(warps))

  private val occupied = valid.asUInt.orR
  io.busy := occupied || sendValid || resumeValid || aborting
  when(io.cancel || io.terminal) { aborting := true.B }
  when(aborting && !io.cancel && !io.terminal && !occupied && !sendValid && !resumeValid) { aborting := false.B }

  private val shifted = (io.park.bits.lanes.pad(64) << io.park.bits.base)(63, 0)
  private val requested = shifted(31, 0)
  private val used = (0 until warps).map(i => Mux(valid(i), mask(i), 0.U(32.W))).reduce(_ | _)
  private val bad = !io.park.bits.lanes.orR || shifted(63, 32).orR || (requested & used).orR
  io.park.ready := !io.cancel && !io.terminal && !aborting && !valid(io.park.bits.warp)
  when(io.park.fire) {
    val w = io.park.bits.warp
    valid(w) := true.B; mask(w) := Mux(bad, 0.U, requested)
    pending(w) := Mux(bad, 0.U, requested); remaining(w) := Mux(bad, 0.U, requested)
    id(w) := io.park.bits.barrierId; epoch(w) := 0.U; epochSet(w) := false.B
    done(w) := bad; status(w) := Mux(bad, 1.U, 0.U)
  }

  private val eligible = VecInit((0 until warps).map(i => valid(i) && !done(i) && pending(i).orR))
  private val nextWarp = PriorityEncoder(eligible.asUInt)
  when(!sendValid && !aborting && !io.cancel && !io.terminal && eligible.asUInt.orR) {
    sendValid := true.B; sendWarp := nextWarp; sendInvocation := PriorityEncoder(pending(nextWarp)); sendId := id(nextWarp)
  }
  io.arrive.valid := sendValid
  io.arrive.bits.invocation := sendInvocation; io.arrive.bits.barrierId := sendId
  when(io.arrive.fire) {
    sendValid := false.B
    pending(sendWarp) := pending(sendWarp) & ~UIntToOH(sendInvocation, 32)
  }
  when(io.terminal) { sendValid := false.B }

  private val releaseBit = UIntToOH(io.release.bits.invocation, 32)
  private val matches = VecInit((0 until warps).map(i => valid(i) && !done(i) && !pending(i).orR && (remaining(i) & releaseBit).orR))
  private val releaseWarp = PriorityEncoder(matches.asUInt)
  io.release.ready := aborting || io.cancel || io.terminal || matches.asUInt.orR
  when(io.release.fire && !aborting && !io.cancel && !io.terminal && matches.asUInt.orR) {
    val w = releaseWarp
    val next = remaining(w) & ~releaseBit
    remaining(w) := next
    when(!epochSet(w)) { epochSet(w) := true.B; epoch(w) := io.release.bits.epoch }
    when(io.release.bits.barrierId =/= id(w) || (epochSet(w) && io.release.bits.epoch =/= epoch(w))) {
      done(w) := true.B; status(w) := 1.U
    }.elsewhen(!next.orR) { done(w) := true.B; status(w) := 0.U }
  }

  // Complete cancellation only after an already offered arrival is consumed.
  for (i <- 0 until warps) {
    when((aborting || io.cancel || io.terminal) && valid(i) && !(resumeValid && resumeBits.warp === i.U) && (!sendValid || io.terminal)) {
      done(i) := true.B; status(i) := 2.U
    }
  }
  private val completed = VecInit((0 until warps).map(i => valid(i) && done(i)))
  private val completedWarp = PriorityEncoder(completed.asUInt)
  when(!resumeValid && !io.cancel && !io.terminal && completed.asUInt.orR) {
    resumeValid := true.B; resumeBits.warp := completedWarp; resumeBits.barrierId := id(completedWarp)
    resumeBits.epoch := epoch(completedWarp); resumeBits.status := status(completedWarp)
  }
  io.resume.valid := resumeValid; io.resume.bits := resumeBits
  when(io.resume.fire) { valid(resumeBits.warp) := false.B; done(resumeBits.warp) := false.B; resumeValid := false.B }
}
