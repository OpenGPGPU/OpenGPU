package opengpu.core.memory

import chisel3._
import opengpu.config.GpuConfig

/** Internal host operation, distinct from the unchanged single-line L1 probe.
  * Line mode uses endExclusive=0. Range mode carries aligned physical bounds;
  * the extra end bit preserves the legal exclusive end of the address space.
  */
class L2HostInvalidate(config: GpuConfig) extends Bundle {
  val lineAddress = UInt(config.xLen.W)
  val endExclusive = UInt((config.xLen + 1).W)
  val isRange = Bool()
}
