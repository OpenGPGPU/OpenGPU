package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics.{ShaderPayloadConfig, ShaderPayloadControl, ShaderPayloadProducerPort}

/** Exclusive handoff of the existing payload producer ports between two
  * serialized render owners. A handoff cannot retire accepted replies or
  * successful allocations belonging to the previous owner.
  */
class TessellationHostPayloadOwner(p: ShaderPayloadConfig) extends Module {
  val io = IO(new Bundle {
    /** False selects legacy; true selects tessellation. */
    val acquire = Flipped(Decoupled(Bool()))
    val end = Flipped(Decoupled(Bool()))
    val legacy = Vec(p.owners, Flipped(new ShaderPayloadProducerPort(p)))
    val tessellation = Vec(p.owners, Flipped(new ShaderPayloadProducerPort(p)))
    val pool = Vec(p.owners, new ShaderPayloadProducerPort(p))
    val active = Output(Bool())
    val drained = Output(Bool())
  })
  private val active = RegInit(false.B)
  private val selected = RegInit(false.B)
  private val controlLive = RegInit(VecInit(Seq.fill(p.owners)(false.B)))
  private val flightWidth = math.max(1, log2Ceil(p.responseDepth + 1))
  private val dataLive = RegInit(VecInit(Seq.fill(p.owners)(0.U(flightWidth.W))))
  // A released slot may be allocated again while its previous release reply
  // is held. Count accepted public allocation/release replies, not slot bits.
  private val maxLeases = p.slots + p.owners
  private val leaseWidth = math.max(1, log2Ceil(maxLeases + 1))
  private val leases = RegInit(0.U(leaseWidth.W))

  io.acquire.ready := !active
  when(io.acquire.fire) { active := true.B; selected := io.acquire.bits }
  val allocationReplies = Wire(Vec(p.owners, Bool()))
  val releaseReplies = Wire(Vec(p.owners, Bool()))
  val offers = Wire(Vec(p.owners, Bool()))
  val replies = Wire(Vec(p.owners, Bool()))
  for (i <- 0 until p.owners) {
    val old = io.legacy(i); val tess = io.tessellation(i); val pool = io.pool(i)
    val oldSelected = active && !selected; val tessSelected = active && selected
    pool.control.valid := Mux(selected, tess.control.valid, old.control.valid) && active
    pool.control.bits := Mux(selected, tess.control.bits, old.control.bits)
    old.control.ready := oldSelected && pool.control.ready
    tess.control.ready := tessSelected && pool.control.ready
    old.controlResponse.valid := oldSelected && pool.controlResponse.valid
    tess.controlResponse.valid := tessSelected && pool.controlResponse.valid
    old.controlResponse.bits := pool.controlResponse.bits; tess.controlResponse.bits := pool.controlResponse.bits
    pool.controlResponse.ready := active && Mux(selected, tess.controlResponse.ready, old.controlResponse.ready)
    pool.request.valid := Mux(selected, tess.request.valid, old.request.valid) && active
    pool.request.bits := Mux(selected, tess.request.bits, old.request.bits)
    old.request.ready := oldSelected && pool.request.ready
    tess.request.ready := tessSelected && pool.request.ready
    old.response.valid := oldSelected && pool.response.valid
    tess.response.valid := tessSelected && pool.response.valid
    old.response.bits := pool.response.bits; tess.response.bits := pool.response.bits
    pool.response.ready := active && Mux(selected, tess.response.ready, old.response.ready)

    val controlIn = pool.control.fire; val controlOut = pool.controlResponse.fire
    when(controlIn) { assert(!controlLive(i) || controlOut, "payload control reply capacity exceeded") }
    when(pool.controlResponse.valid) { assert(active && (controlLive(i) || controlIn), "payload control reply lacks owner") }
    when(controlIn =/= controlOut) { controlLive(i) := controlIn }
    val dataIn = pool.request.fire; val dataOut = pool.response.fire
    when(dataIn) { assert(dataLive(i) < p.responseDepth.U || dataOut, "payload data reply capacity exceeded") }
    when(pool.response.valid) { assert(active && (dataLive(i) =/= 0.U || dataIn), "payload data reply lacks owner") }
    when(dataIn =/= dataOut) { dataLive(i) := Mux(dataIn, dataLive(i) + 1.U, dataLive(i) - 1.U) }
    allocationReplies(i) := controlOut && pool.controlResponse.bits.operation === ShaderPayloadControl.allocate.U &&
      pool.controlResponse.bits.resident && !pool.controlResponse.bits.fault
    releaseReplies(i) := controlOut && pool.controlResponse.bits.operation === ShaderPayloadControl.release.U &&
      !pool.controlResponse.bits.fault
    offers(i) := Mux(selected, tess.control.valid || tess.request.valid, old.control.valid || old.request.valid)
    replies(i) := pool.controlResponse.valid || pool.response.valid
  }
  private val allocated = PopCount(allocationReplies)
  private val released = PopCount(releaseReplies)
  private val beforeRelease = leases +& allocated
  when(allocated =/= 0.U || released =/= 0.U) {
    assert(beforeRelease >= released, "payload lease count underflow")
    assert(beforeRelease - released <= maxLeases.U, "payload lease count overflow")
    leases := beforeRelease - released
  }
  io.end.ready := active && !controlLive.asUInt.orR && !dataLive.map(_.orR).reduce(_ || _) &&
    leases === 0.U && !offers.asUInt.orR && !replies.asUInt.orR
  when(io.end.fire) { active := false.B }
  io.active := active
  io.drained := !active && !io.acquire.valid && !replies.asUInt.orR &&
    !io.legacy.map(p => p.control.valid || p.request.valid).reduce(_ || _) &&
    !io.tessellation.map(p => p.control.valid || p.request.valid).reduce(_ || _)
}
