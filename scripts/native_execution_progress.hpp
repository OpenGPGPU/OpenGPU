#pragma once

#include "native_frame_axi.hpp"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <string>
#include <system_error>

namespace native_frame {
struct ProgressCounters {
  uint64_t service_cycles, gpu_cycles, read_bytes, write_bytes, enabled_bytes;
};

inline ProgressCounters progress_counters(uint64_t cycles, uint64_t gpu_cycles, const Traffic &traffic) {
  return {cycles, gpu_cycles, traffic.read_bus_bytes, traffic.write_bus_bytes, traffic.enabled_write_bytes};
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
        << ",\"elapsedMilliseconds\":" << elapsed << ",\"updatedUnixSeconds\":" << updated << "}\n";
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
