package opengpu.graphics

import chisel3._
import chisel3.util._

/** Four independently owned raw quads prepare weights/depth/sample state while
  * one four-lane component bank evaluates their attributes. Credits cover the
  * entire raw-input to completion lifetime, including held output offers.
  * Preparation may finish out of order; prepared transfer and retirement stay
  * in submission order. Cancellation stops fresh admission and drains every
  * reserved preparation result through the component owner.
  */
class Fp32StreamingFragmentInterpolator(p: VertexShaderStageConfig = VertexShaderStageConfig(),
    g: GraphicsConfig = GraphicsConfig(), contexts: Int = 4) extends Module {
  require(contexts >= 2 && contexts <= 8 && isPow2(contexts))
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FragmentInterpolationRequest(p, g)))
    val out = Decoupled(new Fp32InterpolatedQuad(p, g))
    val completion = Decoupled(new FragmentInterpolationCompletion)
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val preparation = Seq.fill(contexts)(Module(new Fp32FragmentPreparation(p, g)))
  private val components = Module(new Fp32PreparedQuadInterpolator(p, g, contexts, reservedInputs = true))
  private val allocate = RegInit(0.U(log2Ceil(contexts).W))
  private val transfer = RegInit(0.U(log2Ceil(contexts).W))
  private val owned = RegInit(0.U(log2Ceil(contexts + 1).W))
  private val cancelling = RegInit(false.B)
  private val stop = io.cancel || cancelling
  private val preparationReady = VecInit(preparation.map(_.io.in.ready))
  private val preparedValid = VecInit(preparation.map(_.io.out.valid))
  private val preparedBits = VecInit(preparation.map(_.io.out.bits))
  io.in.ready := owned < contexts.U && preparationReady(allocate) && !stop
  io.busy := owned =/= 0.U
  components.io.in.valid := preparedValid(transfer)
  components.io.in.bits := preparedBits(transfer)
  components.io.cancel := stop
  io.out <> components.io.out
  io.completion <> components.io.completion
  for ((stage, i) <- preparation.zipWithIndex) {
    stage.io.in.valid := io.in.valid && owned < contexts.U && allocate === i.U && !stop
    stage.io.in.bits := io.in.bits
    stage.io.out.ready := components.io.in.ready && transfer === i.U
    stage.io.cancel := stop
  }
  when(io.in.fire) { allocate := allocate + 1.U }
  when(components.io.in.fire) { transfer := transfer + 1.U }
  switch(Cat(io.in.fire, io.completion.fire)) {
    is("b10".U) { owned := owned + 1.U }
    is("b01".U) { owned := owned - 1.U }
  }
  when(!io.busy) { cancelling := false.B }
  when(io.cancel && io.busy) { cancelling := true.B }
  when(!io.busy) {
    assert(!components.io.busy && !VecInit(preparation.map(_.io.busy)).asUInt.orR,
      "fragment owner released credit before preparation/component drain")
  }
  when(io.completion.fire) { assert(owned =/= 0.U, "fragment completion has no raw-input owner") }
}
