package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.graphics._

/** Bind one fragment draw to its geometry raster stream. The draw and bridge
  * identity are accepted atomically; raster and fragment completion consumers
  * retain their independent ready/valid contracts.
  */
class GeometryFragmentIngress(p: FragmentShaderConfig, g: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new FragmentShaderDraw(p)))
    val fragmentDraw = Decoupled(new FragmentShaderDraw(p))
    val rasterQuad = Flipped(Decoupled(new GeometryRasterizedQuad(p.vertices,g)))
    val rasterCompletion = Flipped(Decoupled(new GeometryPrimitiveRasterResult))
    val fragmentQuad = Decoupled(new Fp32RasterizedQuad(p.vertices,g))
    val fragmentLayer = Output(Valid(UInt(32.W)))
    val fragmentEnd = Decoupled(new FragmentInputCompletion)
    val rasterReport = Decoupled(new GeometryPrimitiveRasterResult)
    val busy = Output(Bool())
  })
  private val bridge = Module(new GeometryFragmentBridge(p.vertices,g))
  io.fragmentDraw.valid := io.draw.valid && bridge.io.start.ready
  io.fragmentDraw.bits := io.draw.bits
  io.draw.ready := io.fragmentDraw.ready && bridge.io.start.ready
  bridge.io.start.valid := io.draw.valid && io.fragmentDraw.ready
  bridge.io.start.bits := io.draw.bits.identity
  bridge.io.in <> io.rasterQuad
  bridge.io.rasterCompletion <> io.rasterCompletion
  io.fragmentQuad <> bridge.io.out
  io.fragmentLayer := bridge.io.layer
  io.fragmentEnd <> bridge.io.inputEnd
  io.rasterReport <> bridge.io.report
  io.busy := bridge.io.busy
}
