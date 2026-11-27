package opengpu.graphics

import chisel3._

/** An internal subrange of an original draw, captured with its descriptor. Ordinal and instance indices remain relative
  * to the original draw; index and instanced-attribute addresses must not be rebased to the slice. This is not a
  * serialized native descriptor or a capability advertisement.
  */
class VertexInputSlice extends Bundle {
  val version = UInt(8.W)
  val firstOrdinal = UInt(32.W); val count = UInt(32.W)
  val firstInstance = UInt(32.W); val instances = UInt(32.W)
}
object VertexInputSlice {
  def legal(s: VertexInputSlice, d: VertexInputDraw): Bool =
    s.version === 1.U && s.firstOrdinal.pad(33) +& s.count <= d.count.pad(34) &&
      s.firstInstance.pad(33) +& s.instances <= d.instances.pad(34)
}
