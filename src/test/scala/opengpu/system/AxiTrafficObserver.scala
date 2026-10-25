package opengpu.system

import chisel3.Record
import opengpu.testutil.AxiTrafficCounters
import opengpu.testutil.GpuSim._

/** Read-only opt-in sampling from serviceMemoryMaster's pre-edge onCycle hook.
  * The stimulus has driven all channels before this hook. It must never be
  * called during unrelated MMIO clocks or used to drive DUT inputs.
  */
private[opengpu] final class AxiTrafficObserver(signals: Record, counts: AxiTrafficCounters) {
  private val port = new AxiMemoryMasterPort(signals)
  private val busBytes = port.m_axi_wstrb.getWidth

  def sample(): Unit = {
    if (port.m_axi_arvalid.peek().litToBoolean && port.m_axi_arready.peek().litToBoolean)
      counts.readAddress(port.m_axi_arlen.peek().litValue.toInt + 1)
    if (port.m_axi_awvalid.peek().litToBoolean && port.m_axi_awready.peek().litToBoolean)
      counts.writeAddress()
    if (port.m_axi_wvalid.peek().litToBoolean && port.m_axi_wready.peek().litToBoolean)
      counts.writeBeat(busBytes, port.m_axi_wstrb.peek().litValue, port.m_axi_wlast.peek().litToBoolean)
    if (port.m_axi_rvalid.peek().litToBoolean && port.m_axi_rready.peek().litToBoolean)
      counts.readBeat(busBytes, port.m_axi_rlast.peek().litToBoolean)
    if (port.m_axi_bvalid.peek().litToBoolean && port.m_axi_bready.peek().litToBoolean)
      counts.writeReply()
  }
}
