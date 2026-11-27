package chisel3.simulator

import chisel3.RawModule

/** Read-only adapter to the pinned Chisel simulator's package-scoped port map. No library class is replaced and no port
  * ordering is reconstructed.
  */
object GpuSimModelPortABI {
  final case class Port(id: Int, name: String, width: Int, settable: Boolean, gettable: Boolean)
  final case class Snapshot(module: String, ports: Seq[Port])
  def capture[T <: RawModule](simulated: SimulatedModule[T]): Snapshot =
    Snapshot(
      simulated.wrapped.name,
      simulated.elaboratedModule.ports.zipWithIndex.map { case ((data, port), id) =>
        require(data.getWidth > 0, "retained model port width is unknown")
        Port(id, port.name, data.getWidth, port.isSettable, port.isGettable)
      }
    )
}
