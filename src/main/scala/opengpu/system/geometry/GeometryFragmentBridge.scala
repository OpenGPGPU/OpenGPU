package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

/** Connect one geometry raster task to the fragment stage's quad and EOF inputs.
  * The enclosing draw owner starts the bridge with the fragment work identity
  * and retains the raster report until it has consumed both report and EOF.
  */
class GeometryFragmentBridge(p: VertexShaderStageConfig, g: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val start = Flipped(Decoupled(new GraphicsWorkIdentity))
    val in = Flipped(Decoupled(new GeometryRasterizedQuad(p,g)))
    val rasterCompletion = Flipped(Decoupled(new GeometryPrimitiveRasterResult))
    val out = Decoupled(new Fp32RasterizedQuad(p,g))
    val layer = Output(Valid(UInt(32.W)))
    val inputEnd = Decoupled(new FragmentInputCompletion)
    val report = Decoupled(new GeometryPrimitiveRasterResult)
    val busy = Output(Bool())
  })
  private val active = RegInit(false.B)
  private val completionHeld = RegInit(false.B)
  private val endSent = RegInit(false.B)
  private val reportSent = RegInit(false.B)
  private val identity = Reg(new GraphicsWorkIdentity)
  private val count = RegInit(0.U(99.W))
  private val result = Reg(new GeometryPrimitiveRasterResult)
  private val end = Reg(new FragmentInputCompletion)

  io.start.ready := !active
  io.busy := active
  when(io.start.fire) {
    active := true.B; completionHeld := false.B; endSent := false.B; reportSent := false.B
    identity := io.start.bits; count := 0.U
  }
  io.out.valid := active && !completionHeld && io.in.valid
  io.out.bits := io.in.bits.quad
  io.layer.valid := io.out.valid
  io.layer.bits := io.in.bits.layer
  io.in.ready := active && !completionHeld && io.out.ready
  when(io.out.fire) { count := count + 1.U }

  // A producer may offer completion alongside its last held quad. Accept it
  // only after that quad has been consumed, so the EOF count includes it.
  io.rasterCompletion.ready := active && !completionHeld && !io.in.valid
  when(io.rasterCompletion.fire) {
    completionHeld := true.B
    result := io.rasterCompletion.bits
    end.identity := identity
    end.quads := count
    end.status := Mux(io.rasterCompletion.bits.status =/= 0.U, io.rasterCompletion.bits.status,
      Mux(io.rasterCompletion.bits.outputQuads =/= count, 6.U, 0.U))
  }
  io.inputEnd.valid := completionHeld && !endSent
  io.inputEnd.bits := end
  io.report.valid := completionHeld && !reportSent
  io.report.bits := result
  when(io.inputEnd.fire) { endSent := true.B }
  when(io.report.fire) { reportSent := true.B }
  when(completionHeld && (endSent || io.inputEnd.fire) && (reportSent || io.report.fire)) {
    active := false.B; completionHeld := false.B
  }
}
