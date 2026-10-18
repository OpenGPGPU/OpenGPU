package opengpu.graphics

import chisel3._
import chisel3.util.MuxLookup
import circt.stage.ChiselStage

class ClipDistanceProofHarness extends Module {
  val io = IO(new Bundle {
    val x = Input(SInt(32.W))
    val y = Input(SInt(32.W))
    val z = Input(SInt(32.W))
    val w = Input(SInt(32.W))
    val near = Input(UInt(16.W))
    val plane = Input(UInt(3.W))
    val equivalent = Output(Bool())
  })
  val vertex = Wire(new ClipVertexColor(GraphicsConfig()))
  vertex := 0.U.asTypeOf(vertex)
  vertex.x := io.x; vertex.y := io.y; vertex.z := io.z; vertex.w := io.w
  val near = io.near.pad(34).asSInt
  val reference = MuxLookup(io.plane, io.w.pad(34) - near)(Seq(
    0.U -> (io.w.pad(34) - near),
    1.U -> (io.x.pad(34) + io.w.pad(34)),
    2.U -> (io.w.pad(34) - io.x.pad(34)),
    3.U -> (io.y.pad(34) + io.w.pad(34)),
    4.U -> (io.w.pad(34) - io.y.pad(34)),
    5.U -> (io.z.pad(34) + io.w.pad(34)),
    6.U -> (io.w.pad(34) - io.z.pad(34))))
  io.equivalent := ClipDistanceMath(vertex, io.plane, io.near) === reference
}

object EmitClipDistanceProof {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new ClipDistanceProofHarness,
      Array("--target-dir", args(0)),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))
  }
}
