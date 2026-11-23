// SPDX-License-Identifier: MIT
// Time a fixed number of ARTI ticks with the scanout disabled or enabled.
#include "arti_rtl_model.h"
#include "gpu_abi.h"
#include <array>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <string_view>

static void write_register(unsigned offset, uint32_t value)
{
    assert(arti_rtl_model_write(offset, value, 4) == 0);
}

static uint64_t read_register(unsigned offset)
{
    uint64_t value = 0;
    assert(arti_rtl_model_read(offset, &value, 4) == 0);
    return value;
}

static std::array<uint64_t, 4> counters()
{
    std::array<uint64_t, 4> values{};
    arti_rtl_model_skip_stats(&values[0], &values[1], &values[2], &values[3]);
    return values;
}

int main(int argc, char **argv)
{
    assert(argc == 2);
    const std::string_view mode(argv[1]);
    assert(mode == "idle" || mode == "scanout");
    arti_rtl_model_init();
    assert((read_register(GPU_REG_ID) & 0xffff0000u) == 0x47550000u);
    write_register(GPU_REG_SCANOUT_CONTROL, 0);
    assert(!(read_register(GPU_REG_SCANOUT_STATUS) & GPU_SCANOUT_ACTIVE));
    if (mode == "scanout") {
        write_register(GPU_REG_SCANOUT_BASE, 0x40000000);
        write_register(GPU_REG_SCANOUT_STRIDE, 256);
        write_register(GPU_REG_SCANOUT_WIDTH, 64);
        write_register(GPU_REG_SCANOUT_HEIGHT, 64);
        write_register(GPU_REG_SCANOUT_PERIOD, 10000);
        write_register(GPU_REG_SCANOUT_CONTROL, GPU_SCANOUT_ENABLE | GPU_SCANOUT_VBLANK_EN);
        assert(read_register(GPU_REG_SCANOUT_STATUS) & GPU_SCANOUT_ACTIVE);
    }
    arti_rtl_model_tick_n(200000);
    const auto before = counters();
    constexpr unsigned ticks = 8000000;
    const auto start = std::chrono::steady_clock::now();
    arti_rtl_model_tick_n(ticks);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    const auto after = counters();
    assert(after[0] - before[0] == ticks);
    std::printf("%.4f", std::chrono::duration<double, std::nano>(elapsed).count() / ticks);
    for (unsigned i = 0; i < before.size(); ++i)
        std::printf(" %llu", static_cast<unsigned long long>(after[i] - before[i]));
    std::putchar('\n');
}
