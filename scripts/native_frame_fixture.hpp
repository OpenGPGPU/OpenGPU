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

  bool two_cu_vga() const { return resources.size() >= 4 && resources[3].value == 2; }
  bool alignment_v2() const { return resources.size() == 72; }

  void validate_resources() const {
    if (resources.size() == 24 || alignment_v2()) {
      for (unsigned n = 0; n < 8; ++n)
        require(resources[16 + n].address == 0x160 + 4 * n,
          "invalid combined native capability register sequence");
      require(resources[16].value == 0x474e4303 && resources[17].value == 8 &&
        resources[21].value == 0x08000800, "invalid combined native API capacity");
    }
    if (two_cu_vga()) {
      require((resources.size() == 24 || alignment_v2()),
        "two-CU VGA requires full native API capacity");
      const std::array<uint32_t, 16> compute{0x47435201,16,alignment_v2() ? 0x7fffu : 15u,2,16,8,128,128,
        0xffffffff,16384,16,8,0x10000000,32,131072,4};
      for (unsigned n = 0; n < compute.size(); ++n)
        require(resources[n].address == 0x360 + 4 * n && resources[n].value == compute[n],
          "incomplete two-CU compute resource profile");
      const std::array<uint32_t, 8> native{0x474e4303,8,0x474e4a01,0x08000200,
        0xc2100800,0x08000800,0xe4908000,0x8813d902};
      for (unsigned n = 0; n < native.size(); ++n)
        require(resources[16 + n].value == native[n], "incomplete two-CU native API resource profile");
    }
    if (alignment_v2()) {
      require(two_cu_vga(), "integrated alignment requires two CUs");
      const std::array<std::pair<uint32_t, std::array<uint32_t, 8>>, 6> blocks{{
        {0x340, {0x47434901,8,0x7f,14,12,4,0xffffffff,128}},
        {0x240, {0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31}},
        {0x260, {0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255}},
        {0x280, {0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63}},
        {0x3a0, {0x47554301,16,1,0x7f,3,12,24,16384}},
        {0x3c0, {1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31}}
      }};
      unsigned index = 24;
      for (const auto &block : blocks) for (unsigned n = 0; n < block.second.size(); ++n, ++index)
        require(resources[index].address == block.first + 4*n &&
          resources[index].value == block.second[n], "incomplete integrated alignment capability block");
    }
  }

  static std::vector<Command> commands(std::istream &in) {
    const auto count = read_le(in, 4); require(count <= 128, "too many fixture commands");
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
    require(((f.width == 32 && f.height == 32) || (f.width == 640 && f.height == 480) ||
      (f.width == 1280 && f.height == 720)) &&
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
    require((f.resources.size() == 16 || f.resources.size() == 24 || f.alignment_v2()) &&
      f.writes.size() == 8 && f.finish.size() == 2,
      "incomplete native launch or resource contract");
    require(!f.two_cu_vga() || f.width != 1280, "two-CU VGA requires a VGA or smoke frame");
    f.validate_resources();
    require(f.width != 640 || f.two_cu_vga(), "VGA product fixtures require the two-CU profile");
    require(in.peek() == EOF, "trailing native command data");
    return f;
  }
};
} // namespace native_frame
