package opengpu.graphics.tessellation

import opengpu.graphics._

import chisel3._
import chisel3.util._

/** Own a patch context and serialize each warp's access and barrier events.
  * Warp and invocation mappings must stay stable during the patch. Accepted
  * results and resumes must be consumed before the corresponding warp can
  * issue again; close also waits for all of them to drain.
  */
class TessellationControlWarpScheduler(warps: Int, lanes: Int) extends Module {
  require(warps >= 2 && isPow2(warps) && lanes >= 1 && lanes <= 32)
  val io = IO(new Bundle {
    val begin = Flipped(Decoupled(new TessellationPatchContextTask)); val opened = Decoupled(UInt(2.W))
    val load = Flipped(Decoupled(new TessellationPatchAccess)); val seal = Flipped(Decoupled(Bool()))
    val access = Flipped(Decoupled(new TessellationWarpPatchRequest(warps, lanes)))
    val accessResult = Decoupled(new TessellationWarpPatchResult(warps, lanes))
    val park = Flipped(Decoupled(new TessellationWarpPark(warps, lanes)))
    val resume = Decoupled(new TessellationWarpResume(warps))
    val finish = Flipped(Decoupled(UInt(5.W))); val control = Decoupled(new TessellationBarrierResult)
    val inspect = Flipped(Decoupled(new TessellationPatchAccess)); val reply = Decoupled(new TessellationContextReply)
    val close = Flipped(Decoupled(Bool())); val cancel = Input(Bool()); val busy = Output(Bool())
  })
  private val context = Module(new TessellationPatchContext)
  private val access = Module(new TessellationWarpPatchAccess(warps, lanes))
  private val barrier = Module(new TessellationWarpBarrier(warps, lanes))
  private val active = RegInit(false.B); private val running = RegInit(false.B); private val stopping = RegInit(false.B)
  private val owned = RegInit(VecInit(Seq.fill(warps)(false.B)))
  private val invocations = Reg(Vec(warps, UInt(32.W)))
  context.io.begin.valid := !active && io.begin.valid && !io.cancel; context.io.begin.bits := io.begin.bits
  io.begin.ready := !active && !io.cancel && context.io.begin.ready
  when(io.begin.fire) { active := true.B; running := false.B; stopping := false.B; owned.foreach(_ := false.B) }
  io.opened <> context.io.opened
  when(io.opened.fire && io.opened.bits =/= 0.U) { active := false.B }
  context.io.load <> io.load; context.io.sealInput <> io.seal
  when(io.seal.fire && !io.cancel && !stopping) { running := true.B }
  when(active && io.cancel) { stopping := true.B }
  private val accepting = active && running && !stopping && !io.cancel
  private val a = io.access.bits.warp; private val p = io.park.bits.warp
  private val accessAllowed = accepting && !owned(a)
  private val parkAllowed = accepting && !owned(p) && !(io.access.valid && accessAllowed && a === p)
  access.io.request.valid := io.access.valid && accessAllowed; access.io.request.bits := io.access.bits
  io.access.ready := accessAllowed && access.io.request.ready
  barrier.io.park.valid := io.park.valid && parkAllowed; barrier.io.park.bits := io.park.bits
  io.park.ready := parkAllowed && barrier.io.park.ready
  io.accessResult <> access.io.result; io.resume <> barrier.io.resume
  for (w <- 0 until warps) {
    when(io.access.fire && a === w.U) {
      owned(w) := true.B; invocations(w) := (io.access.bits.mask.pad(64) << io.access.bits.base)(31,0)
    }
    when(io.park.fire && p === w.U) {
      owned(w) := true.B; invocations(w) := (io.park.bits.lanes.pad(64) << io.park.bits.base)(31,0)
    }
    when((io.accessResult.fire && io.accessResult.bits.warp === w.U) ||
      (io.resume.fire && io.resume.bits.warp === w.U)) { owned(w) := false.B }
  }
  context.io.access <> access.io.access
  access.io.reply.valid := context.io.reply.valid && context.io.reply.bits.source === 1.U
  access.io.reply.bits := context.io.reply.bits
  io.reply.valid := context.io.reply.valid && context.io.reply.bits.source =/= 1.U; io.reply.bits := context.io.reply.bits
  context.io.reply.ready := Mux(context.io.reply.bits.source === 1.U, access.io.reply.ready, io.reply.ready)
  context.io.arrive <> barrier.io.arrive; barrier.io.release <> context.io.release
  private val heldInvocations = (0 until warps).map(w => Mux(owned(w), invocations(w), 0.U(32.W))).reduce(_ | _)
  // An access mask can be predicated, so it cannot identify all live lanes of
  // its warp. Hold finishes globally until that instruction has retired.
  private val incomingPark = (io.park.bits.lanes.pad(64) << io.park.bits.base)(31,0)
  private val finishMask = UIntToOH(io.finish.bits, 32)
  private val finishAllowed = !access.io.busy && !(io.access.valid && accessAllowed) && !(heldInvocations & finishMask).orR &&
    !(io.park.valid && (incomingPark & finishMask).orR)
  context.io.finish.valid := io.finish.valid && finishAllowed; context.io.finish.bits := io.finish.bits
  io.finish.ready := finishAllowed && context.io.finish.ready
  io.control <> context.io.controlResult
  private val terminal = io.control.fire && io.control.bits.status =/= 0.U
  when(io.control.fire) { running := false.B; stopping := true.B }
  access.io.cancel := active && io.cancel; barrier.io.cancel := active && io.cancel
  access.io.terminal := terminal; barrier.io.terminal := terminal
  context.io.cancel := active && io.cancel
  context.io.inspect <> io.inspect
  private val drained = !owned.asUInt.orR && !access.io.busy && !barrier.io.busy
  context.io.close.valid := io.close.valid && drained; context.io.close.bits := io.close.bits
  io.close.ready := drained && context.io.close.ready
  when(io.close.fire) { active := false.B; stopping := false.B }
  io.busy := active || context.io.busy || !drained
}
