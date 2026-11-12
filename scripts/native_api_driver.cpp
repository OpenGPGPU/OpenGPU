// In-process AXI stimulus only. Every shader/raster/pixel operation executes
// in the complete generated RTL. Stored images are verified separately by Scala.
#include "native_host_simulation.hpp"
#include "native_api_frame_fixture.hpp"
#include "native_execution_progress.hpp"
#include <chrono>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <set>

using namespace native_frame;


int main(int argc, char **argv) {
  try {
    require(argc == 4 || argc == 6,
      "usage: native_api_driver --smoke|--api-extent-product FIXTURE_DIR OUTPUT_DIR [--scene SCENE]");
    const std::string mode(argv[1]);
    require(mode == "--smoke" || mode == "--api-extent-product", "invalid native API mode");
    const std::string selected = argc == 6 ? argv[5] : "";
    require(argc == 4 || (std::string(argv[4]) == "--scene" &&
      (selected == "native-api-extent-1x" || selected == "native-api-extent-4x")),
      "invalid native API scene selection");
    const bool product = mode == "--api-extent-product";
    const auto fixtures = std::filesystem::path(argv[2]), output = std::filesystem::path(argv[3]);
    std::filesystem::create_directories(output);
    std::ifstream suite(fixtures / "suite.txt"); require(bool(suite), "missing complete native suite");
    std::vector<std::string> names; std::set<std::string> unique; std::string name;
    while (std::getline(suite, name)) {
      require(!name.empty() && std::all_of(name.begin(), name.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
      }), "invalid native scene name");
      require(unique.insert(name).second, "duplicate native scene"); names.push_back(name);
    }
    require(names == std::vector<std::string>{"native-api-extent-1x", "native-api-extent-4x"},
      "both native API frames must execute in order");
    Simulation sim(argc, argv); ExecutionProgress progress(output);
    unsigned index = 0; uint32_t width = 0, height = 0;
    for (const auto &id : names) {
      ++index;
      if (!selected.empty() && id != selected) continue;
      const auto fixture = ApiFixture::read((fixtures / (id + ".commands")).string(), product);
      require(fixture.id == index, "native scene order changed");
      if (width == 0) { width = fixture.width; height = fixture.height; }
      require(fixture.width == width && fixture.height == height, "mixed native resolution profiles");
      auto memory = Memory::read((fixtures / (id + ".memory")).string());
      sim.reset();
      for (const auto &r : fixture.resources) require(sim.read(r.address) == r.value, "native hardware resource contract differs");
      for (const auto &w : fixture.writes) sim.write(w.address, w.value);
      Agent agent(memory); uint64_t cycles = 0;
      const auto start = std::chrono::steady_clock::now();
      auto counters = [&]() { return progress_counters(cycles, sim.model->io_performance_system_cycles, agent.traffic); };
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
      std::vector<uint32_t> check_values;
      bool checks_passed = true;
      for (const auto &check : fixture.checks) {
        const uint32_t value = sim.read(check.address);
        check_values.push_back(value);
        checks_passed &= (value & check.mask) == check.expected;
      }
      if (!checks_passed) {
        std::cerr << "native API completion diagnostics scene=" << id << " serviceCycles=" << cycles << '\n';
        for (size_t n = 0; n < fixture.checks.size(); ++n) {
          const auto &check = fixture.checks[n];
          std::cerr << "MMIO 0x" << std::hex << check.address << " actual=0x" << check_values[n]
            << " mask=0x" << check.mask << " expected=0x" << check.expected << std::dec << '\n';
        }
      }
      require(checks_passed, "native completion or GNX1 check failed");
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
      const auto final_counters = counters();
      for (const auto &w : fixture.finish) sim.write(w.address, w.value);
      progress.finish(final_counters);
    }
    sim.model->final(); return 0;
  } catch (const std::exception &e) {
    std::cerr << "native API execution failed: " << e.what() << "\n"; return 1;
  }
}
