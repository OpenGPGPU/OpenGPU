package opengpu.elaboration

import chisel3._
import circt.stage.ChiselStage
import opengpu.graphics._

/** Registered endpoints for measuring viewport arithmetic; not GPU staging. */
class GeometryTiming extends Module {
  private val config = GraphicsConfig(screenWidth = 1280, screenHeight = 720)
  val io = IO(new Bundle {
    val clip = Input(Vec(3, new ClipVertex))
    val color = Input(Vec(3, new Varyings))
    val screenW = Input(UInt(16.W))
    val screenH = Input(UInt(16.W))
    val out = Output(Vec(3, new ScreenVertex(config)))
  })
  private val geometry = Module(new GeometryStage(config))
  geometry.io.clip := RegNext(io.clip)
  geometry.io.color := RegNext(io.color)
  geometry.io.screenW := RegNext(io.screenW)
  geometry.io.screenH := RegNext(io.screenH)
  io.out := RegNext(geometry.io.out)
}

object EmitGeometryExperiment {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "usage: EmitGeometryExperiment <target-dir>")
    ChiselStage.emitSystemVerilogFile(new GeometryTiming,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
