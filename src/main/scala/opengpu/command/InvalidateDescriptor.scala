package opengpu.command

import chisel3._
import opengpu.config.GpuConfig

/** One physical L2 line-invalidate range: the engine validates its exclusive
  * end, then drops every intersecting 64-byte line in `[address, address + bytes)`
  * and snoops its L1 holders, without any
  * lower-memory traffic.  A driver uses it to make CPU-written memory visible
  * to later GPU reads, since the CPU does not write the GPU L2.
  */
class InvalidateDescriptor(config: GpuConfig, val commandIdWidth: Int)
    extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  val address = UInt(config.xLen.W)
  /** Byte extent; the engine rounds both range endpoints to cache lines. */
  val bytes = UInt(32.W)
}

object InvalidateStatus {
  val width = 4
  val success = 0.U(width.W)
  val addressOverflow = 1.U(width.W)
}

class InvalidateCompletion(val commandIdWidth: Int) extends Bundle {
  val descriptorId = UInt(commandIdWidth.W)
  val status = UInt(InvalidateStatus.width.W)
  val success = Bool()
  /** Aligned bytes whose lines were invalidated. */
  val bytesInvalidated = UInt(64.W)
}
