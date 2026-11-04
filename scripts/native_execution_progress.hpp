#pragma once

#include "native_frame_axi.hpp"
#include <array>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <string>
#include <system_error>

namespace native_frame {
struct ProgressCounters {
  uint64_t service_cycles, gpu_cycles, read_bytes, write_bytes, enabled_bytes;
  bool has_profile = false;
  uint64_t busy_cu_cycles = 0;
  std::array<uint64_t, 6> fragment_phases{};
  uint64_t staging_outstanding_cycles = 0, staging_blocked_cycles = 0;
  uint64_t fragment_output_blocked_cycles = 0, payload_spills = 0;
};

inline ProgressCounters progress_counters(uint64_t cycles, uint64_t gpu_cycles, const Traffic &traffic) {
  return {cycles, gpu_cycles, traffic.read_bus_bytes, traffic.write_bus_bytes, traffic.enabled_write_bytes};
}

template<class Model>
inline ProgressCounters product_progress_counters(uint64_t cycles, Model &d, const Traffic &traffic) {
#ifdef OPENGPU_FLASHSIM_NATIVE
  d.sample_performance();
#endif
  auto result = progress_counters(cycles, d.io_performance_system_cycles, traffic);
  result.has_profile = true;
  result.busy_cu_cycles = d.io_performance_system_activeCuCycles;
  result.fragment_phases = {d.io_performance_fragmentPhaseWorkerCycles_0,
    d.io_performance_fragmentPhaseWorkerCycles_1, d.io_performance_fragmentPhaseWorkerCycles_2,
    d.io_performance_fragmentPhaseWorkerCycles_3, d.io_performance_fragmentPhaseWorkerCycles_4,
    d.io_performance_fragmentPhaseWorkerCycles_5};
  result.staging_outstanding_cycles = d.io_performance_stagingOutstandingCycles;
  result.staging_blocked_cycles = d.io_performance_stagingBlockedCycles;
  result.fragment_output_blocked_cycles = d.io_performance_fragmentOutputBlockedCycles;
  result.payload_spills = d.io_performance_payloadSpills;
  return result;
}

// Best-effort monitoring only: execution metrics and image verification are
// still the qualification gates. The runner creates a private output directory.
class ExecutionProgress {
public:
  using Clock = std::chrono::steady_clock;
  explicit ExecutionProgress(const std::filesystem::path &directory)
      : target(directory / "progress.json"), temporary(directory / "progress.json.tmp") {}

  void begin(const std::string &name, unsigned index, unsigned total, uint64_t bound,
             const ProgressCounters &counters, Clock::time_point now = Clock::now()) {
    scene = name; scene_index = index; scene_count = total; max_cycles = bound;
    started = now; next = now + interval;
    publish("running", counters, now);
  }

  void update(const ProgressCounters &counters, Clock::time_point now = Clock::now()) {
    if (now >= next) {
      next = now + interval;
      publish("running", counters, now);
    }
  }

  void finish(const ProgressCounters &counters, Clock::time_point now = Clock::now()) {
    publish("scene-complete", counters, now);
  }

private:
  static constexpr auto interval = std::chrono::seconds(15);
  std::filesystem::path target, temporary;
  std::string scene;
  unsigned scene_index = 0, scene_count = 0;
  uint64_t max_cycles = 0;
  Clock::time_point started{}, next{};
  bool available = true;

  void publish(const char *state, const ProgressCounters &c, Clock::time_point now) {
    if (!available) return;
    std::ofstream out(temporary, std::ios::trunc);
    if (!out) { available = false; return; }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - started).count();
    const auto updated = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    // Scene names are checked against [a-z0-9-]+ by both callers.
    out << "{\"state\":\"" << state << "\",\"scene\":\"" << scene
        << "\",\"sceneIndex\":" << scene_index << ",\"sceneCount\":" << scene_count
        << ",\"serviceCycles\":" << c.service_cycles << ",\"maxServiceCycles\":" << max_cycles
        << ",\"gpuCycles\":" << c.gpu_cycles << ",\"readBusBytes\":" << c.read_bytes
        << ",\"writeBusBytes\":" << c.write_bytes << ",\"enabledWriteBytes\":" << c.enabled_bytes
        << ",\"elapsedMilliseconds\":" << elapsed << ",\"updatedUnixSeconds\":" << updated;
    if (c.has_profile) {
      out << ",\"busyCuCycles\":" << c.busy_cu_cycles << ",\"fragmentPhaseWorkerCycles\":[";
      for (unsigned i = 0; i < c.fragment_phases.size(); ++i)
        out << (i ? "," : "") << c.fragment_phases[i];
      out << "],\"stagingOutstandingCycles\":" << c.staging_outstanding_cycles
          << ",\"stagingBlockedCycles\":" << c.staging_blocked_cycles
          << ",\"fragmentOutputBlockedCycles\":" << c.fragment_output_blocked_cycles
          << ",\"payloadSpills\":" << c.payload_spills;
    }
    out << "}\n";
    out.close();
    std::error_code error;
    if (!out) {
      available = false;
      std::filesystem::remove(temporary, error);
      return;
    }
    std::filesystem::rename(temporary, target, error);
    if (error) {
      available = false;
      std::filesystem::remove(temporary, error);
    }
  }
};
} // namespace native_frame
