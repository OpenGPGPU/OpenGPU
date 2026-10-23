package opengpu.graphics

import chisel3._
import chisel3.util._

/** Privately owns one raw quad through weight, depth, sample and location
  * preparation. Smooth/flat component evaluation is delegated to the spatial
  * owner. A failed or cancelled request still transfers one immutable prepared
  * result, retaining its identity and arithmetic accounting for ordered drain.
  */
class Fp32FragmentPreparation(p: VertexShaderStageConfig = VertexShaderStageConfig(),
    g: GraphicsConfig = GraphicsConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FragmentInterpolationRequest(p, g)))
    val out = Decoupled(new PreparedFragmentInterpolation(p, g))
    val cancel = Input(Bool())
    val busy = Output(Bool())
  })
  private val preparation = Module(new Fp32FragmentInterpolator(p, g, prepareOnly = true))
  preparation.io.in <> io.in
  io.out <> preparation.io.prepared.get
  preparation.io.out.ready := false.B
  preparation.io.completion.ready := false.B
  preparation.io.cancel := io.cancel
  io.busy := preparation.io.busy
}
