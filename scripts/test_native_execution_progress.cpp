#include "native_execution_progress.hpp"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>

using namespace native_frame;
namespace fs = std::filesystem;

struct ProfileModel {
  uint64_t io_performance_system_cycles = 510;
  uint64_t io_performance_system_activeCuCycles = 0x100000002ULL;
  uint64_t io_performance_fragmentPhaseWorkerCycles_0 = 11, io_performance_fragmentPhaseWorkerCycles_1 = 22;
  uint64_t io_performance_fragmentPhaseWorkerCycles_2 = 33, io_performance_fragmentPhaseWorkerCycles_3 = 44;
  uint64_t io_performance_fragmentPhaseWorkerCycles_4 = 55, io_performance_fragmentPhaseWorkerCycles_5 = 66;
  uint64_t io_performance_stagingOutstandingCycles = 77, io_performance_stagingBlockedCycles = 88;
  uint64_t io_performance_fragmentOutputBlockedCycles = 99, io_performance_payloadSpills = 101;
};

static std::string read(const fs::path &path) {
  std::ifstream in(path);
  assert(in);
  return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

int main(int argc, char **argv) {
  assert(argc == 2);
  const fs::path dir(argv[1]);
  const auto t = ExecutionProgress::Clock::time_point(std::chrono::seconds(100));
  ExecutionProgress progress(dir);
  Traffic traffic;
  auto counters = progress_counters(0, 10, traffic);
  progress.begin("first-scene", 1, 6, 1000000000, counters, t);
  const auto initial = read(dir / "progress.json");
  std::cout << initial;
  counters.service_cycles = 500; counters.gpu_cycles = 510;
  traffic.read_bus_bytes = 64; traffic.write_bus_bytes = 32; traffic.enabled_write_bytes = 17;
  ProfileModel model;
  counters = product_progress_counters(500, model, traffic);
  progress.update(counters, t + std::chrono::seconds(14));
  assert(read(dir / "progress.json") == initial);
  progress.update(counters, t + std::chrono::seconds(15));
  std::cout << read(dir / "progress.json");
  counters.service_cycles = 600; counters.gpu_cycles = 610;
  progress.finish(counters, t + std::chrono::seconds(16));
  std::cout << read(dir / "progress.json");
  progress.begin("next-scene", 2, 6, 0x100000001ULL, counters, t + std::chrono::seconds(20));
  std::cout << read(dir / "progress.json");
  assert(!fs::exists(dir / "progress.json.tmp"));

  // Monitoring failures are not execution failures and never leave a partial
  // replacement in the private output directory.
  const auto failed = dir / "failed";
  fs::create_directories(failed / "progress.json");
  ExecutionProgress unavailable(failed);
  unavailable.begin("first-scene", 1, 6, 100, counters, t);
  assert(fs::is_directory(failed / "progress.json"));
  assert(!fs::exists(failed / "progress.json.tmp"));
  unavailable.update(counters, t + std::chrono::seconds(30));
  assert(fs::is_directory(failed / "progress.json"));
}
