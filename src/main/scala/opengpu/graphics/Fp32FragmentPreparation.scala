package opengpu.graphics

import chisel3._
import chisel3.util._

/** Privately owns one raw quad through weight, depth, sample and location
  * preparation. Smooth/flat component evaluation is delegated to the spatial
  * owner. A failed or cancelled request still transfers one immutable prepared
  * result, retaining its identity and arithmetic accounting for ordered drain.
  * Four private lane services prepare the quad concurrently. Setup accounting
  * is included once and lane prefixes preserve scalar first-error ordering.
  */
class Fp32FragmentPreparation(p: VertexShaderStageConfig = VertexShaderStageConfig(),
    g: GraphicsConfig = GraphicsConfig(), parallelLanes: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FragmentInterpolationRequest(p, g)))
    val out = Decoupled(new PreparedFragmentInterpolation(p, g))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  if(!parallelLanes) {
    val preparation = Module(new Fp32FragmentInterpolator(p, g, prepareOnly = true))
    preparation.io.in <> io.in
    io.out <> preparation.io.prepared.get
    preparation.io.out.ready := false.B
    preparation.io.completion.ready := false.B
    preparation.io.cancel := io.cancel
    io.busy := preparation.io.busy
  } else {
    // All four lanes admit and retire together. Capture the immutable raw
    // request once; each lane first consumes it in edgeCheck on the next cycle.
    val snapshot = Reg(new FragmentInterpolationRequest(p,g))
    when(io.in.fire) {
      assert(!io.busy, "shared preparation input changed while lanes still owned it")
      snapshot := io.in.bits
    }
    val lanes = Seq.tabulate(4)(i => Module(new Fp32FragmentInterpolator(p, g,
      prepareOnly = true, preparationLane = Some(i), sharedPreparationInput = true)))
    val ready = VecInit(lanes.map(_.io.in.ready)).asUInt.andR
    val valid = VecInit(lanes.map(_.io.prepared.get.valid)).asUInt.andR
    val prepared = lanes.map(_.io.prepared.get.bits)
    io.in.ready := ready
    io.out.valid := valid
    io.busy := VecInit(lanes.map(_.io.busy)).asUInt.orR
    for(stage <- lanes) {
      stage.io.in.valid := io.in.valid && ready
      stage.io.in.bits := io.in.bits
      stage.io.sharedInput.get := snapshot
      stage.io.prepared.get.ready := io.out.ready && valid
      stage.io.out.ready := false.B
      stage.io.completion.ready := false.B
      stage.io.cancel := io.cancel
      assert(stage.io.in.fire === io.in.fire, "quad preparation lanes lost common admission")
      assert(stage.io.prepared.get.fire === io.out.fire, "quad preparation released a lane before join")
    }
    io.out.bits := prepared.head
    for(i <- 0 until 4) {
      io.out.bits.quad.lanes(i) := prepared(i).quad.lanes(i)
      io.out.bits.weights(i) := prepared(i).weights(i)
      io.out.bits.denominators(i) := prepared(i).denominators(i)
      io.out.bits.centroidWeights(i) := prepared(i).centroidWeights(i)
      io.out.bits.centroidDenominators(i) := prepared(i).centroidDenominators(i)
      io.out.bits.laneOperations(i) := prepared(i).laneOperations(i)
      io.out.bits.laneArithmeticStatus(i) := prepared(i).laneArithmeticStatus(i)
      io.out.bits.lanePackingStatus(i) := prepared(i).lanePackingStatus(i)
    }
    io.out.bits.centroidActive := VecInit(prepared.zipWithIndex.map { case (q,i) => q.centroidActive(i) }).asUInt
    io.out.bits.invalidHelpers := prepared.map(_.invalidHelpers).reduce(_ | _)
    val failures = VecInit(prepared.map(_.preparationStatus =/= 0.U)).asUInt
    val first = PriorityEncoder(failures)
    val results = VecInit(prepared)
    io.out.bits.preparationStatus := Mux(failures.orR, results(first).preparationStatus, 0.U)
    io.out.bits.preparationFaultLane := Mux(failures.orR, results(first).preparationFaultLane, 0.U)
  }
}
