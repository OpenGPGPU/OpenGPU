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
    unsigned index = 0; uint32_t width = 0, height = 0;
    bool two_cu_vga = false, alignment_v2 = false, owner_profile = false;
    for (const auto &id : names) {
      const auto fixture = Fixture::read((fixtures / (id + ".commands")).string());
      require(fixture.id == ++index, "native scene order changed");
      if (index == 1) {
        width = fixture.width; height = fixture.height;
        two_cu_vga = fixture.two_cu_vga(); alignment_v2 = fixture.alignment_v2();
        owner_profile = fixture.owner_profile();
      }
      require(fixture.width == width && fixture.height == height &&
        fixture.two_cu_vga() == two_cu_vga && fixture.alignment_v2() == alignment_v2 &&
        fixture.owner_profile() == owner_profile,
        "mixed native resolution or execution profiles");
      if (!selected.empty() && id != selected) continue;
      auto memory = Memory::read((fixtures / (id + ".memory")).string());
      const auto expected_completion = expected_product_completion(fixture, memory);
      const auto frame_begin = sim.clock_cycles;
      sim.reset();
      std::array<Command,5> profile_registers{};
      if (owner_profile) profile_registers=read_product_owner_profile(sim,fixture);
      else for (const auto &r : fixture.resources)
        require(sim.read(r.address) == r.value, "native hardware resource contract differs");
      for (const auto &w : fixture.writes) sim.write(w.address, w.value);
      Agent agent(memory); uint64_t cycles = 0;
      const auto service_begin_gpu_cycles = sim.model->io_performance_system_cycles;
      const auto start = std::chrono::steady_clock::now();
      auto counters = [&]() { return product_progress_counters(cycles, *sim.model, agent.traffic); };
      progress.begin(id, index, names.size(), fixture.max_cycles, counters(), start);
      while (!sim.model->io_m_irq && cycles < fixture.max_cycles) {
        sim.step(agent, cycles); ++cycles;
        if ((cycles & 255u) == 0) progress.update(counters());
      }
      const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
      const auto irq_gpu_cycles = sim.model->io_performance_system_cycles;
      const auto requests = sim.requests();
      require(!requests.arvalid && !requests.awvalid && !requests.wvalid,
              "product completion left external requests offered");
      sim.pause_memory(); memory.write((output / (id + ".memory")).string());
      require(cycles < fixture.max_cycles, "native scene exceeded its cycle bound");
      require(agent.drained(), "native completion leaked external traffic");
      require(agent.traffic.read_faults == 0 && agent.traffic.write_faults == 0, "native scene accessed an unmapped line");
      const auto gnx = read_product_completion(sim, fixture, expected_completion);
      auto &d = *sim.model;
      // Retain the historical gpuCycles sampling point after thirteen reads.
      const auto checked_gpu_cycles = d.io_performance_system_cycles;
      const std::array<uint64_t, 6> phases{d.io_performance_fragmentPhaseWorkerCycles_0,
        d.io_performance_fragmentPhaseWorkerCycles_1, d.io_performance_fragmentPhaseWorkerCycles_2,
        d.io_performance_fragmentPhaseWorkerCycles_3, d.io_performance_fragmentPhaseWorkerCycles_4,
        d.io_performance_fragmentPhaseWorkerCycles_5};
      const auto final_counters = counters();
      retire_product_completion(sim, fixture);
      require(agent.drained(), "product retirement leaked external traffic");
      const auto frame_cycles = sim.clock_cycles - frame_begin;
      std::cout << "{\"scene\":\"" << id << "\",\"frameWidth\":" << width
        << ",\"frameHeight\":" << height << ",\"samples\":" << fixture.samples
        << ",\"serviceCycles\":" << cycles << ",\"gpuCycles\":" << checked_gpu_cycles
        << ",\"cycleScopeVersion\":2,\"serviceCycleScope\":\"post-submit-to-irq\""
        << ",\"frameCycleScope\":\"reset-through-verified-retirement\",\"frameCycles\":" << frame_cycles
        << ",\"gpuCyclesAtServiceBegin\":" << service_begin_gpu_cycles
        << ",\"gpuCyclesAtIrq\":" << irq_gpu_cycles
        << ",\"submittedCommands\":1,\"successfulCompletions\":1,\"retiredCompletions\":1"
        << ",\"completionCountSource\":\"observed-command-retirement\",\"drawCompletionCountAvailable\":false"
        << ",\"primitiveCompletionCountAvailable\":false"
        << ",\"gnx\":[";
      for (unsigned n = 0; n < gnx.size(); ++n) std::cout << (n ? "," : "") << gnx[n];
      std::cout << "]"
        << ",\"readBusBytes\":" << agent.traffic.read_bus_bytes
        << ",\"writeBusBytes\":" << agent.traffic.write_bus_bytes
        << ",\"enabledWriteBytes\":" << agent.traffic.enabled_write_bytes
        << ",\"simulationSeconds\":" << std::fixed << std::setprecision(6) << seconds
        << ",\"phaseWorkerCycles\":[";
      for (unsigned n = 0; n < phases.size(); ++n) std::cout << (n ? "," : "") << phases[n];
      std::cout << "]";
      if (two_cu_vga) std::cout << ",\"profile\":\""
        << (owner_profile ? "two-cu-vga60-owner-v1" : fixture.alignment_v2() ? "two-cu-vga60-v2" : "two-cu-vga60-v1")
        << "\",\"computeUnits\":2,\"nativeEngines\":4,\"nativeMaxWidth\":2048,\"nativeMaxHeight\":2048";
      if (fixture.alignment_v2() || owner_profile)
        std::cout << ",\"resourceDiscoveryWords\":" << fixture.resources.size()
          << ",\"computeFeatureFlags\":" << fixture.resources[2].value;
      if (owner_profile) {
        std::cout << ",\"liveProfilePreflight\":true,\"idleBeforeSubmit\":true"
          << ",\"shaderInputSource\":\"native-product-hand-encoded\",\"profileRegisters\":[";
        for (unsigned n=0;n<profile_registers.size();++n)
          std::cout << (n ? "," : "") << "{\"address\":" << profile_registers[n].address
            << ",\"value\":" << profile_registers[n].value << "}";
        std::cout << "]";
      }
      std::cout << ",\"trafficDrained\":true,\"imageVerificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}" << std::endl;
      progress.finish(final_counters);
    }
    sim.model->final(); return 0;
  } catch (const std::exception &e) {
    std::cerr << "native product execution failed: " << e.what() << "\n"; return 1;
  }
}
