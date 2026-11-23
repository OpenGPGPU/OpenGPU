#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <memory>

// The generated adapter supplies the DUT type and selects its clock input.
static std::unique_ptr<FlashSimDut> dut;
static bool clockHigh();

// The stock svsim driver expects these entry points when compiled without a
// simulator's DPI headers. FlashSim accesses its C++ model fields directly.
using svScope = void *;
static svScope svGetScope() { return nullptr; }
static svScope svSetScope(svScope) { return nullptr; }
static void simulation_initializeTrace(const char *) {}
static void simulation_enableTrace(int *success) { *success = 0; }
static void simulation_disableTrace(int *success) { *success = 1; }

#define DPI_TASK_RETURN_TYPE void
#define DPI_TASK_RETURN_VALUE
#include "simulation-driver.cpp"
#undef DPI_TASK_RETURN_VALUE
#undef DPI_TASK_RETURN_TYPE

// svsim transfers little-endian bytes. Copy just the significant bytes so
// scalar storage, 128-bit words and multi-limb fields use the same adapter.
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__,
              "FlashSim svsim requires a little-endian host");

template <typename Storage>
static void getBits(const Storage &value, uint8_t *bytes, std::size_t size) {
  std::memcpy(bytes, &value, size);
}

template <typename Storage>
static void setBits(Storage &value, const uint8_t *bytes, std::size_t size) {
  std::memset(&value, 0, sizeof(value));
  std::memcpy(&value, bytes, size);
}

static bool previousClockHigh = false;

extern "C" void simulation_main(int, const char **) {
  dut = std::make_unique<FlashSimDut>();
  initTestBenchScope();
  simulation_body();
  simulation_final();
}

extern "C" void run_simulation(int timesteps, int *finish) {
  if (timesteps < 0)
    failWithError("Negative FlashSim timestep");
  try {
    dut->poke_inputs();
    const bool high = clockHigh();
    if (!previousClockHigh && high)
      dut->tick_nba();
    previousClockHigh = high;
  } catch (const std::exception &error) {
    std::fprintf(stdout, "%s\n", error.what());
    std::fflush(stdout);
    failWithError("%s", error.what());
  }
  *finish = 0;
}
