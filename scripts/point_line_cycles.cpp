// Matched common-domain cycle probe for default PointLineRasterizer emissions.
#include "VPointLineRasterizer.h"
#include "verilated.h"
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <vector>

static uint32_t bits(float value) {
    uint32_t result;
    std::memcpy(&result, &value, sizeof(result));
    return result;
}

static void tick(VPointLineRasterizer &d) {
    d.clock = 0; d.eval();
    d.clock = 1; d.eval();
    d.clock = 0; d.eval();
}

static std::vector<uint64_t> quad(const VPointLineRasterizer &d) {
    std::vector<uint64_t> result;
#define LANE(N) \
    result.insert(result.end(), {d.io_out_bits_lanes_##N##_x, \
        d.io_out_bits_lanes_##N##_y, d.io_out_bits_lanes_##N##_e0, \
        d.io_out_bits_lanes_##N##_e1, d.io_out_bits_lanes_##N##_e2, \
        d.io_out_bits_lanes_##N##_area, d.io_out_bits_lanes_##N##_covered, \
        d.io_out_bits_lanes_##N##_coverageMask});
    LANE(0) LANE(1) LANE(2) LANE(3)
#undef LANE
    return result;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    try {
        for (bool point : {false, true}) {
            const std::vector<float> sizes = point ? std::vector<float>{3.25f, 64.f}
                                                   : std::vector<float>{1.f, 2.5f, 64.f};
            for (float size : sizes) for (unsigned mode = 0; mode < 3; ++mode) {
                VPointLineRasterizer d;
                d.io_in_valid = 0; d.io_out_ready = 1; d.io_cancel = 0;
                d.io_in_bits_point = point;
                d.io_in_bits_x0 = point ? 896 : 128;
                d.io_in_bits_y0 = point ? 1024 : 128;
                d.io_in_bits_x1 = point ? 896 : 1792;
                d.io_in_bits_y1 = point ? 1024 : 1280;
                d.io_in_bits_state_version = 1;
                d.io_in_bits_state_screenW = d.io_in_bits_state_screenH = 8;
                d.io_in_bits_state_cullMode = 0;
                d.io_in_bits_state_frontFaceCCW = 1;
                d.io_in_bits_state_sampleMode = mode;
                d.io_in_bits_state_pointSize = bits(point ? size : 1.f);
                d.io_in_bits_state_lineWidth = bits(point ? 1.f : size);
                d.io_in_bits_state_scissorEnable = 0;
                d.io_in_bits_state_scissorMinX = d.io_in_bits_state_scissorMinY = 0;
                d.io_in_bits_state_scissorMaxX = d.io_in_bits_state_scissorMaxY = 8;
                d.reset = 1;
                for (int i = 0; i < 3; ++i) tick(d);
                d.reset = 0; d.eval();
                if (!d.io_in_ready) throw std::runtime_error("input not ready after reset");
                d.io_in_valid = 1; tick(d); d.io_in_valid = 0; d.eval();
                unsigned cycles = 1, first = 0;
                std::vector<std::vector<uint64_t>> outputs;
                while (d.io_busy) {
                    if (cycles > 200000) throw std::runtime_error("primitive did not retire");
                    if (d.io_out_valid) {
                        if (!first) first = cycles;
                        outputs.push_back(quad(d));
                    }
                    tick(d); ++cycles;
                }
                if (d.io_fault || outputs.empty()) throw std::runtime_error("fault or empty fixture");
                std::cout << "{\"point\":" << (point ? "true" : "false")
                          << ",\"size_fp32\":" << bits(size) << ",\"sample_mode\":" << mode
                          << ",\"cycles_including_acceptance\":" << cycles
                          << ",\"first_offer_after_acceptance\":" << first << ",\"quads\":[";
                for (size_t q = 0; q < outputs.size(); ++q) {
                    if (q) std::cout << ',';
                    std::cout << '[';
                    for (size_t v = 0; v < outputs[q].size(); ++v) {
                        if (v) std::cout << ',';
                        std::cout << outputs[q][v];
                    }
                    std::cout << ']';
                }
                std::cout << "]}\n";
                d.final();
            }
        }
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
