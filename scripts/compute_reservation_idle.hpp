#pragma once
namespace compute_owner {
template<class Sim,class MemoryAgent,class Bus>
std::pair<uint64_t,uint64_t> reservation_idle(Sim &sim,MemoryAgent &agent,Bus &bus) {
  require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && !sim.model->io_m_irq,
    "reservation DUT was not idle before producer release");
  const auto before=agent.traffic;
  const auto start=bus.cycles;
  for(unsigned n=0;n<257;++n)bus.step([&](const auto &model) {
    require(!model.io_m_irq && !model.io_m_axi_arvalid && !model.io_m_axi_awvalid && !model.io_m_axi_wvalid,
      "pending reservation offered an IRQ or external memory request");
  });
  const auto end=bus.cycles;
  require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && !sim.model->io_m_irq && agent.drained(),
    "reservation DUT left idle before producer release");
  traffic_unchanged(before,agent.traffic);return {start,end};
}
} // namespace compute_owner
