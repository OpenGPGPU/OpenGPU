package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Broadcast an explicit graphics staging invalidation to the shared CUs,
  * while preserving each CU's ordinary L2 coherence channel. Acknowledgements
  * follow the accepted request order and return to their original owner.
  */
class SharedCuCacheInvalidation(config: GpuConfig, numComputeUnits: Int) extends Module {
  require(numComputeUnits > 0)
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new CacheLineInvalidate(config)))
    val done = Decoupled(new CacheLineInvalidate(config))
    val l2Request = Vec(numComputeUnits, Flipped(Decoupled(new CacheLineInvalidate(config))))
    val l2Done = Vec(numComputeUnits, Decoupled(new CacheLineInvalidate(config)))
    val cuRequest = Vec(numComputeUnits, Decoupled(new CacheLineInvalidate(config)))
    val cuDone = Vec(numComputeUnits, Flipped(Decoupled(new CacheLineInvalidate(config))))
    val drained = Output(Bool())
  })
  private val active = RegInit(false.B)
  private val address = Reg(new CacheLineInvalidate(config))
  private val send = RegInit(VecInit(Seq.fill(numComputeUnits)(false.B)))
  private val waiting = RegInit(VecInit(Seq.fill(numComputeUnits)(false.B)))
  io.drained := !active
  io.request.ready := !active
  when(io.request.fire) {
    active := true.B
    address := io.request.bits
    send.foreach(_ := true.B)
    waiting.foreach(_ := true.B)
  }
  io.done.valid := active && !waiting.asUInt.orR
  io.done.bits := address
  when(io.done.fire) { active := false.B }

  for (cu <- 0 until numComputeUnits) {
    val arbiter = Module(new RRArbiter(new CacheLineInvalidate(config), 2))
    val owners = Module(new Queue(Bool(), 4))
    arbiter.io.in(0) <> io.l2Request(cu)
    arbiter.io.in(1).valid := active && send(cu)
    arbiter.io.in(1).bits := address
    io.cuRequest(cu).valid := arbiter.io.out.valid && owners.io.enq.ready
    io.cuRequest(cu).bits := arbiter.io.out.bits
    arbiter.io.out.ready := io.cuRequest(cu).ready && owners.io.enq.ready
    owners.io.enq.valid := io.cuRequest(cu).fire
    owners.io.enq.bits := arbiter.io.chosen === 1.U
    when(arbiter.io.in(1).fire) { send(cu) := false.B }

    val graphicsAck = owners.io.deq.valid && owners.io.deq.bits
    io.l2Done(cu).valid := io.cuDone(cu).valid && owners.io.deq.valid && !graphicsAck
    io.l2Done(cu).bits := io.cuDone(cu).bits
    io.cuDone(cu).ready := owners.io.deq.valid &&
      Mux(graphicsAck, active && waiting(cu), io.l2Done(cu).ready)
    owners.io.deq.ready := io.cuDone(cu).fire
    when(io.cuDone(cu).fire && graphicsAck) {
      assert(!send(cu), "graphics invalidation acknowledged before request acceptance")
      assert(io.cuDone(cu).bits.lineAddress === address.lineAddress,
        "shared CU acknowledged the wrong graphics staging line")
      waiting(cu) := false.B
    }
  }
}
