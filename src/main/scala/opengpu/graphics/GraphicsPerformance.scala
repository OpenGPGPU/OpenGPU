package opengpu.graphics

import chisel3._

/** Worker-cycle events, with phases idle/write/launch/run/read/emit. */
class FragmentPerformanceEvents extends Bundle {
  val phaseWorkers = Vec(6, UInt(8.W))
  val stagingOutstanding = UInt(8.W)
  val stagingBlocked = Bool()
  val outputBlocked = Bool()
}

/** Event wires only: unused instrumentation is removed from production RTL. */
class GraphicsPerformanceEvents extends Bundle {
  val fragment = new FragmentPerformanceEvents
  val omStall = Bool()
  val omConflict = Bool()
  val rasterStall = Bool()
  val stagingReadBytes = UInt(8.W)
  val stagingWriteBytes = UInt(8.W)
}
