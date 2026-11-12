#pragma once
#include "native_frame_fixture.hpp"

namespace native_frame {
// Keep the product fixture parser's strict six-scene 32x32/720p contract.
struct ApiFixture : Fixture {
  static ApiFixture read(const std::string &path, bool product) {
    std::ifstream in(path, std::ios::binary); require(bool(in), "cannot read native API commands");
    magic(in, "OGAPI001"); ApiFixture f;
    f.width = read_le(in, 4); f.height = read_le(in, 4);
    f.samples = read_le(in, 4); f.id = read_le(in, 4); f.max_cycles = read_le(in, 8);
    const uint32_t width = product ? 640u : 32u;
    const uint32_t height = product ? 480u : 32u;
    require(f.width == width && f.height == height && f.id >= 1 && f.id <= 2 &&
      f.samples == (f.id == 1 ? 1u : 4u), "unsupported native API qualification frame");
    require(f.max_cycles == (product ? 1000000000u : 10000000u), "unexpected native API cycle bound");
    f.resources = commands(in); f.writes = commands(in);
    const auto count = read_le(in, 4); require(count == 13, "native API completion checks missing");
    for (uint64_t n = 0; n < count; ++n) {
      const uint32_t address = read_le(in, 4), mask = read_le(in, 4), expected = read_le(in, 4);
      f.checks.push_back({address, mask, expected});
    }
    f.finish = commands(in);
    require(f.resources.size() == 24 && f.writes.size() == 8 && f.finish.size() == 2,
      "incomplete native API launch or GCR1/GNC3 resource contract");
    require(in.peek() == EOF, "trailing native API command data");
    return f;
  }
};
} // namespace native_frame
