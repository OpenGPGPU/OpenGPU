package opengpu.graphics

import chisel3._
import chisel3.util._

class QuadTexUVRequest(config: GraphicsConfig) extends Bundle {
  val fragment = new FragmentQuad(config)
  val depths = Vec(4, Vec(config.maxSampleCount, UInt(30.W)))
  val inverseW = Vec(3, SInt(32.W))
  val coordinates = Vec(3, new TexUV)
}

class QuadTexUVResult(config: GraphicsConfig) extends Bundle {
  val fragment = new FragmentQuad(config)
  val depths = Vec(4, Vec(config.maxSampleCount, UInt(30.W)))
  val coordinates = Vec(4, new TexUV)
  val divideByZero = Vec(4, Bool())
}

/** Four exact perspective streams with one shared, ordered metadata store.
  * Every helper lane is interpolated, including uncovered lanes used by
  * derivatives. Input and output handshakes are atomic across all four lanes;
  * live draw attributes cannot replace a queued fragment or its sample depths.
  */
class PipelinedQuadTexUV(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new QuadTexUVRequest(config)))
    val out = Decoupled(new QuadTexUVResult(config))
    val drained = Output(Bool())
  })
  private val lanes = Seq.fill(4)(Module(new PipelinedTexUVInterpolator(config)))
  val latency: Int = lanes.head.latency
  private val capacity = latency + 2
  private val metadata = Module(new Queue(new Bundle {
    val fragment = new FragmentQuad(config)
    val depths = Vec(4, Vec(config.maxSampleCount, UInt(30.W)))
  }, capacity))
  private val laneReady = VecInit(lanes.map(_.io.in.ready)).asUInt.andR
  io.in.ready := metadata.io.enq.ready && laneReady
  metadata.io.enq.valid := io.in.valid && laneReady
  metadata.io.enq.bits.fragment := io.in.bits.fragment
  metadata.io.enq.bits.depths := io.in.bits.depths
  for ((lane, k) <- lanes.zipWithIndex) {
    lane.io.in.valid := io.in.fire
    val fragment = io.in.bits.fragment.lanes(k)
    lane.io.in.bits.edges := VecInit(Seq(fragment.e0, fragment.e1, fragment.e2))
    lane.io.in.bits.inverseW := io.in.bits.inverseW
    lane.io.in.bits.coordinates := io.in.bits.coordinates
    lane.io.in.bits.payload := 0.U
    assert(lane.io.in.ready === lanes.head.io.in.ready)
    assert(lane.io.out.valid === lanes.head.io.out.valid)
    lane.io.out.ready := io.out.ready && metadata.io.deq.valid &&
      VecInit(lanes.map(_.io.out.valid)).asUInt.andR
    io.out.bits.coordinates(k) := lane.io.out.bits.coordinates
    io.out.bits.divideByZero(k) := lane.io.out.bits.divideByZero
  }
  io.out.valid := metadata.io.deq.valid && VecInit(lanes.map(_.io.out.valid)).asUInt.andR
  io.out.bits.fragment := metadata.io.deq.bits.fragment
  io.out.bits.depths := metadata.io.deq.bits.depths
  metadata.io.deq.ready := io.out.ready && VecInit(lanes.map(_.io.out.valid)).asUInt.andR
  private val owned = RegInit(0.U(log2Ceil(capacity + 1).W))
  when(io.in.fire =/= io.out.fire) {
    when(io.in.fire) { assert(owned < capacity.U); owned := owned + 1.U }
      .otherwise { assert(owned =/= 0.U); owned := owned - 1.U }
  }
  io.drained := owned === 0.U
}
