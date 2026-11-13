// In-process AXI stimulus only. Every shader/raster/pixel operation executes
// in the complete generated RTL. Stored images are verified separately by Scala.
#include "native_host_simulation.hpp"
#include "native_execution_progress.hpp"
#include <chrono>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <set>

using namespace native_frame;
#if defined(OPENGPU_FLASHSIM_NATIVE) && defined(OPENGPU_EXPECTED_ASSERTIONS)
static_assert(GpuHostSystemAxiDut::assertion_count == OPENGPU_EXPECTED_ASSERTIONS,
              "wrong native assertion model");
#endif


int main(int argc, char **argv) {
  try {
    require(argc == 3 || (argc == 5 && std::string(argv[3]) == "--scene"),
      "usage: native_product_driver FIXTURE_DIR OUTPUT_DIR [--scene NAME]");
    const std::string selected = argc == 5 ? argv[4] : "";
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
    require(names.size() == 6, "native qualification requires the complete six-scene fixture suite");
    require(selected.empty() || unique.count(selected) == 1, "selected native scene is absent from the complete suite");
    Simulation sim(argc, argv); ExecutionProgress progress(output);
    unsigned index = 0; uint32_t width = 0, height = 0; bool two_cu_vga = false;
    for (const auto &id : names) {
      const auto fixture = Fixture::read((fixtures / (id + ".commands")).string());
      require(fixture.id == ++index, "native scene order changed");
      if (index == 1) { width = fixture.width; height = fixture.height; two_cu_vga = fixture.two_cu_vga(); }
      require(fixture.width == width && fixture.height == height && fixture.two_cu_vga() == two_cu_vga,
        "mixed native resolution or CU profiles");
      if (!selected.empty() && id != selected) continue;
      auto memory = Memory::read((fixtures / (id + ".memory")).string());
      sim.reset();
      for (const auto &r : fixture.resources) require(sim.read(r.address) == r.value, "native hardware resource contract differs");
      for (const auto &w : fixture.writes) sim.write(w.address, w.value);
      Agent agent(memory); uint64_t cycles = 0;
      const auto start = std::chrono::steady_clock::now();
      auto counters = [&]() { return product_progress_counters(cycles, *sim.model, agent.traffic); };
      progress.begin(id, index, names.size(), fixture.max_cycles, counters(), start);
      while (!sim.model->io_m_irq && cycles < fixture.max_cycles) {
        sim.step(agent, cycles); ++cycles;
        if ((cycles & 255u) == 0) progress.update(counters());
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
      std::cout << "]";
      if (two_cu_vga) std::cout << ",\"profile\":\"two-cu-vga60-v1\",\"computeUnits\":2,\"nativeEngines\":4,\"nativeMaxWidth\":2048,\"nativeMaxHeight\":2048";
      std::cout << ",\"trafficDrained\":true,\"imageVerificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}" << std::endl;
      const auto final_counters = counters();
      for (const auto &w : fixture.finish) sim.write(w.address, w.value);
      progress.finish(final_counters);
    }
    sim.model->final(); return 0;
  } catch (const std::exception &e) {
    std::cerr << "native product execution failed: " << e.what() << "\n"; return 1;
  }
}
