// In-process AXI stimulus only. Every shader/raster/pixel operation executes
// in the complete generated RTL. Stored images are verified separately by Scala.
#include "native_frame_fixture.hpp"
#include "VGpuHostSystemAxi.h"
#include "verilated.h"
#include <chrono>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <memory>
#include <set>

using namespace native_frame;

class Simulation {
public:
  VerilatedContext context;
  std::unique_ptr<VGpuHostSystemAxi> model;
  Simulation(int argc, char **argv) {
    context.commandArgs(argc, argv);
    model = std::make_unique<VGpuHostSystemAxi>(&context);
    model->io_s_axi_aclk = 0;
  }
  void settle() { model->eval(); }
  void tick() {
    model->io_s_axi_aclk = 0; settle(); context.timeInc(5);
    model->io_s_axi_aclk = 1; settle(); context.timeInc(5);
    model->io_s_axi_aclk = 0; settle();
    require(!context.gotFinish(), "RTL simulation stopped before qualification completed");
  }
  void pause_memory() {
    auto &d = *model;
    d.io_m_axi_arready = d.io_m_axi_rvalid = d.io_m_axi_rlast = 0;
    d.io_m_axi_awready = d.io_m_axi_wready = d.io_m_axi_bvalid = 0;
    settle();
  }
  void reset() {
    auto &d = *model;
    d.io_s_axi_awvalid = d.io_s_axi_wvalid = d.io_s_axi_wlast = d.io_s_axi_bready = 0;
    d.io_s_axi_arvalid = d.io_s_axi_rready = 0;
    d.io_m_axi_bid = d.io_m_axi_bresp = d.io_m_axi_rid = d.io_m_axi_rresp = 0;
    for (unsigned w = 0; w < 8; ++w) d.io_m_axi_rdata[w] = 0;
    pause_memory(); d.io_s_axi_aresetn = 0; tick(); d.io_s_axi_aresetn = 1; tick();
    require(d.macroModelsPresent, "characterized SRAM simulation resources missing");
  }
  void write(uint32_t address, uint32_t value) {
    auto &d = *model;
    d.io_s_axi_awaddr = address; d.io_s_axi_awlen = 0; d.io_s_axi_awsize = 2;
    d.io_s_axi_awburst = 0; d.io_s_axi_awvalid = 1;
    d.io_s_axi_wdata = value; d.io_s_axi_wstrb = 15; d.io_s_axi_wlast = 1;
    d.io_s_axi_wvalid = 1; d.io_s_axi_bready = 1;
    bool aw = false, w = false, b = false;
    for (unsigned n = 0; !b && n < 64; ++n) {
      settle();
      if (!aw && d.io_s_axi_awready) aw = true;
      if (!w && d.io_s_axi_wready) w = true;
      if (d.io_s_axi_bvalid) { require(d.io_s_axi_bresp == 0, "control write fault"); b = true; }
      tick(); if (aw) d.io_s_axi_awvalid = 0; if (w) d.io_s_axi_wvalid = 0;
    }
    require(b, "control write timed out");
    d.io_s_axi_awvalid = d.io_s_axi_wvalid = d.io_s_axi_wlast = d.io_s_axi_bready = 0;
    settle();
  }
  uint32_t read(uint32_t address) {
    auto &d = *model;
    d.io_s_axi_araddr = address; d.io_s_axi_arlen = 0; d.io_s_axi_arsize = 2;
    d.io_s_axi_arburst = 0; d.io_s_axi_arvalid = d.io_s_axi_rready = 1;
    bool ar = false, r = false; uint32_t result = 0;
    for (unsigned n = 0; !r && n < 64; ++n) {
      settle(); if (!ar && d.io_s_axi_arready) ar = true;
      if (d.io_s_axi_rvalid) {
        require(d.io_s_axi_rresp == 0, "control read fault"); result = d.io_s_axi_rdata; r = true;
      }
      tick(); if (ar) d.io_s_axi_arvalid = 0;
    }
    require(r, "control read timed out"); d.io_s_axi_arvalid = d.io_s_axi_rready = 0;
    settle(); return result;
  }
  void drive(const Responses &r) {
    auto &d = *model;
    d.io_m_axi_arready = r.arready; d.io_m_axi_awready = r.awready; d.io_m_axi_wready = r.wready;
    d.io_m_axi_rvalid = r.rvalid; d.io_m_axi_rlast = r.rlast;
    if (r.rvalid) {
      d.io_m_axi_rid = r.rid; d.io_m_axi_rresp = r.rresp;
      for (unsigned w = 0; w < 8; ++w) {
        uint32_t value = 0;
        for (unsigned b = 0; b < 4; ++b) value |= uint32_t(r.rdata[w * 4 + b]) << (b * 8);
        d.io_m_axi_rdata[w] = value;
      }
    }
    d.io_m_axi_bvalid = r.bvalid; d.io_m_axi_bid = r.bid; d.io_m_axi_bresp = r.bresp;
    settle();
  }
  Requests requests() const {
    const auto &d = *model; Requests r;
    r.arvalid = d.io_m_axi_arvalid; r.araddr = d.io_m_axi_araddr; r.arid = d.io_m_axi_arid;
    r.arlen = d.io_m_axi_arlen; r.arsize = d.io_m_axi_arsize; r.arburst = d.io_m_axi_arburst;
    r.awvalid = d.io_m_axi_awvalid; r.awaddr = d.io_m_axi_awaddr; r.awid = d.io_m_axi_awid;
    r.awlen = d.io_m_axi_awlen; r.awsize = d.io_m_axi_awsize; r.awburst = d.io_m_axi_awburst;
    r.wvalid = d.io_m_axi_wvalid; r.wlast = d.io_m_axi_wlast; r.wstrb = d.io_m_axi_wstrb;
    for (unsigned w = 0; w < 8; ++w)
      for (unsigned b = 0; b < 4; ++b) r.wdata[w * 4 + b] = uint8_t(d.io_m_axi_wdata[w] >> (b * 8));
    r.rready = d.io_m_axi_rready; r.bready = d.io_m_axi_bready;
    return r;
  }
};

int main(int argc, char **argv) {
  try {
    require(argc == 3, "usage: native_product_driver FIXTURE_DIR OUTPUT_DIR");
    const auto fixtures = std::filesystem::path(argv[1]), output = std::filesystem::path(argv[2]);
    std::filesystem::create_directories(output);
    std::ifstream suite(fixtures / "suite.txt"); require(bool(suite), "missing complete native suite");
    std::vector<std::string> names; std::set<std::string> unique; std::string name;
    while (std::getline(suite, name)) {
      require(!name.empty() && std::all_of(name.begin(), name.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
      }), "invalid native scene name");
      require(unique.insert(name).second, "duplicate native scene"); names.push_back(name);
    }
    require(names.size() == 6, "all six native scenes must execute together");
    Simulation sim(argc, argv); unsigned index = 0; uint32_t width = 0, height = 0;
    for (const auto &id : names) {
      const auto fixture = Fixture::read((fixtures / (id + ".commands")).string());
      require(fixture.id == ++index, "native scene order changed");
      if (index == 1) { width = fixture.width; height = fixture.height; }
      require(fixture.width == width && fixture.height == height, "mixed native resolution profiles");
      auto memory = Memory::read((fixtures / (id + ".memory")).string());
      sim.reset();
      for (const auto &r : fixture.resources) require(sim.read(r.address) == r.value, "native hardware resource contract differs");
      for (const auto &w : fixture.writes) sim.write(w.address, w.value);
      Agent agent(memory); uint64_t cycles = 0;
      const auto start = std::chrono::steady_clock::now();
      while (!sim.model->io_m_irq && cycles < fixture.max_cycles) {
        const auto response = agent.drive(cycles);
        sim.drive(response); const auto request = sim.requests();
        agent.observe(cycles, request, response); sim.tick(); ++cycles;
      }
      const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
      sim.pause_memory(); memory.write((output / (id + ".memory")).string());
      require(cycles < fixture.max_cycles, "native scene exceeded its cycle bound");
      require(agent.drained(), "native completion leaked external traffic");
      require(agent.traffic.read_faults == 0 && agent.traffic.write_faults == 0, "native scene accessed an unmapped line");
      for (const auto &check : fixture.checks)
        require((sim.read(check.address) & check.mask) == check.expected, "native completion or GNX1 check failed");
      auto &d = *sim.model;
      const std::array<uint64_t, 6> phases{d.io_performance_fragmentPhaseWorkerCycles_0,
        d.io_performance_fragmentPhaseWorkerCycles_1, d.io_performance_fragmentPhaseWorkerCycles_2,
        d.io_performance_fragmentPhaseWorkerCycles_3, d.io_performance_fragmentPhaseWorkerCycles_4,
        d.io_performance_fragmentPhaseWorkerCycles_5};
      std::cout << "{\"scene\":\"" << id << "\",\"frameWidth\":" << width
        << ",\"frameHeight\":" << height << ",\"samples\":" << fixture.samples
        << ",\"serviceCycles\":" << cycles << ",\"gpuCycles\":" << d.io_performance_system_cycles
        << ",\"readBusBytes\":" << agent.traffic.read_bus_bytes
        << ",\"writeBusBytes\":" << agent.traffic.write_bus_bytes
        << ",\"enabledWriteBytes\":" << agent.traffic.enabled_write_bytes
        << ",\"simulationSeconds\":" << std::fixed << std::setprecision(6) << seconds
        << ",\"phaseWorkerCycles\":[";
      for (unsigned n = 0; n < phases.size(); ++n) std::cout << (n ? "," : "") << phases[n];
      std::cout << "],\"trafficDrained\":true,\"imageVerificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}" << std::endl;
      for (const auto &w : fixture.finish) sim.write(w.address, w.value);
    }
    sim.model->final(); return 0;
  } catch (const std::exception &e) {
    std::cerr << "native product execution failed: " << e.what() << "\n"; return 1;
  }
}
