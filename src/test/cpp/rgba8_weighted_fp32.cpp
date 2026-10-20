#include "VRgba8WeightedToFp32.h"
#include "verilated.h"
#include <array>
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <string>

static constexpr uint32_t denominator = 255u * 65536u;
static constexpr uint32_t count = denominator + 1;
static uint32_t numerator(uint32_t tag, unsigned lane) {
    return (uint64_t(tag) + uint64_t(lane) * 1234567u) % count;
}
// Exact rational significand division and nearest-even rounding. The RTL
// instead normalizes a 32-bit dividend and folds its bytes to divide by255.
static uint32_t reference(uint32_t n) {
    if (!n) return 0;
    int exponent = (31 - __builtin_clz(n)) - 23;
    if ((uint64_t(n) << -exponent) < denominator) --exponent;
    uint64_t scaled = uint64_t(n) << (23 - exponent);
    uint64_t quotient = scaled / denominator;
    uint64_t remainder = scaled % denominator;
    if (2 * remainder > denominator ||
        (2 * remainder == denominator && (quotient & 1))) ++quotient;
    if (quotient >> 24) { quotient >>= 1; ++exponent; }
    return (uint32_t(exponent + 127) << 23) | (quotient & 0x7fffffu);
}
static std::array<uint32_t, 6> output(const VRgba8WeightedToFp32& d) {
    return {d.io_out_bits_tag, d.io_out_bits_cancelled,
        d.io_out_bits_values_0, d.io_out_bits_values_1,
        d.io_out_bits_values_2, d.io_out_bits_values_3};
}
static void require(bool good, const std::string& message) {
    if (!good) throw std::runtime_error(message);
}
int main(int argc, char** argv) {
    VerilatedContext context;
    context.commandArgs(argc, argv);
    VRgba8WeightedToFp32 d{&context};
    uint64_t cycles = 0, heldCycles = 0;
    uint32_t sent = 0, retired = 0;
    bool held = false;
    std::array<uint32_t, 6> last{};
    auto edge = [&] { d.clock = 1; d.eval(); context.timeInc(1); };
    try {
        d.clock = 0; d.reset = 1; d.io_cancel = 0;
        d.io_in_valid = 0; d.io_out_ready = 0; d.io_in_bits_cancelled = 0;
        for (unsigned n = 0; n < 4; ++n) {
            d.clock = 0; d.eval(); context.timeInc(1); edge();
        }
        d.reset = 0;
        while (retired < count && cycles < uint64_t(count) * 2 + 100) {
            d.clock = 0;
            d.io_in_valid = sent < count;
            d.io_in_bits_tag = sent;
            d.io_in_bits_numerators_0 = numerator(sent, 0);
            d.io_in_bits_numerators_1 = numerator(sent, 1);
            d.io_in_bits_numerators_2 = numerator(sent, 2);
            d.io_in_bits_numerators_3 = numerator(sent, 3);
            d.io_out_ready = cycles % 37 < 29;
            d.eval(); context.timeInc(1);
            require(!context.gotFinish(), "RTL terminated before all results retired");
            const auto observed = output(d);
            if (held) {
                require(d.io_out_valid && observed == last, "held FP32 output changed");
                ++heldCycles;
            }
            held = d.io_out_valid && !d.io_out_ready;
            last = observed;
            const bool accept = d.io_in_valid && d.io_in_ready;
            const bool retire = d.io_out_valid && d.io_out_ready;
            if (retire) {
                require(retired < sent, "result has no accepted owner");
                require(observed[0] == retired && !observed[1], "result tag or poison mismatch");
                for (unsigned lane = 0; lane < 4; ++lane) {
                    const uint32_t n = numerator(retired, lane);
                    const uint32_t want = reference(n);
                    if (observed[2 + lane] != want) {
                        std::fprintf(stderr, "tag=%u lane=%u numerator=%u got=%08x want=%08x\n",
                            retired, lane, n, observed[2 + lane], want);
                        throw std::runtime_error("FP32 rational rounding mismatch");
                    }
                }
            }
            edge();
            sent += accept; retired += retire; ++cycles;
        }
        require(sent == count && retired == count, "exhaustive stream did not drain");
        require(heldCycles > 0, "backpressure did not hold any result");
        d.io_in_valid = 0; d.io_out_ready = 1; d.clock = 0; d.eval();
        require(!d.io_out_valid && !d.io_busy, "unexpected extra output or retained owner");
        d.final();
        std::printf("RGBA8_FP32_EXHAUSTIVE PASS groups=%u scalar_values=%llu cycles=%llu held_cycles=%llu all_legal_numerators_per_lane=true rational_oracle=true assertions_enabled=true\n",
            retired, static_cast<unsigned long long>(retired) * 4,
            static_cast<unsigned long long>(cycles), static_cast<unsigned long long>(heldCycles));
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "RGBA8_FP32_EXHAUSTIVE FAIL sent=%u retired=%u cycle=%llu: %s\n",
            sent, retired, static_cast<unsigned long long>(cycles), e.what());
        return 1;
    }
}
