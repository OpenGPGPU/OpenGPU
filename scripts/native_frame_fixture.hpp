#pragma once
#include "native_frame_axi.hpp"

namespace native_frame {
struct Command { uint32_t address, value; };
struct Check { uint32_t address, mask, expected; };
struct Fixture {
  uint32_t width, height, samples, id;
  uint64_t max_cycles;
  std::vector<Command> resources, writes, finish;
  std::vector<Check> checks;

  static std::vector<Command> commands(std::istream &in) {
    const auto count = read_le(in, 4); require(count <= 64, "too many fixture commands");
    std::vector<Command> values;
    for (uint64_t n = 0; n < count; ++n) {
      const uint32_t address = read_le(in, 4), value = read_le(in, 4);
      values.push_back({address, value});
    }
    return values;
  }
  static Fixture read(const std::string &path) {
    std::ifstream in(path, std::ios::binary); require(bool(in), "cannot read native commands");
    magic(in, "OGNAT001"); Fixture f;
    f.width = read_le(in, 4); f.height = read_le(in, 4);
    f.samples = read_le(in, 4); f.id = read_le(in, 4); f.max_cycles = read_le(in, 8);
    require(((f.width == 32 && f.height == 32) || (f.width == 1280 && f.height == 720)) &&
      (f.samples == 1 || f.samples == 4) && f.id >= 1 && f.id <= 6,
      "unsupported native qualification scene");
    require(f.max_cycles == (f.width == 32 ? 10000000 : 1000000000), "unexpected native cycle bound");
    f.resources = commands(in); f.writes = commands(in);
    const auto count = read_le(in, 4); require(count == 13, "native completion checks missing");
    for (uint64_t n = 0; n < count; ++n) {
      const uint32_t address = read_le(in, 4), mask = read_le(in, 4), expected = read_le(in, 4);
      f.checks.push_back({address, mask, expected});
    }
    f.finish = commands(in);
    require(f.resources.size() == 16 && f.writes.size() == 8 && f.finish.size() == 2,
      "incomplete native launch or resource contract");
    require(in.peek() == EOF, "trailing native command data");
    return f;
  }
};
} // namespace native_frame
