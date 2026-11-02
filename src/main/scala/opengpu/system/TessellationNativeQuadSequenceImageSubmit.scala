package opengpu.system

import chisel3._
import opengpu.graphics.VertexInputView

class TessellationNativeQuadSequenceImageSubmit(idWidth:Int) extends Bundle {
  val commandId=UInt(idWidth.W);val view=new VertexInputView
}
