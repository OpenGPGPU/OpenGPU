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

inline uint32_t product_word(const Memory &memory, uint32_t base, unsigned word) {
  const uint64_t address = uint64_t(base) + 4 * word;
  require(address + 4 <= Memory::limit, "product job word escapes RV32");
  uint32_t value = 0;
  for (unsigned n = 0; n < 4; ++n) value |= uint32_t(memory.byte(address + n)) << (8 * n);
  return value;
}

inline uint32_t product_job_address(const Fixture &fixture) {
  unsigned sources = 0, submits = 0, identifiers = 0, opcodes = 0;
  uint32_t base = 0;
  for (const auto &write : fixture.writes) {
    if (write.address == 0x0f4) { ++sources; base = write.value; }
    if (write.address == 0x11c) { ++submits; require(write.value == 1, "invalid product submit"); }
    if (write.address == 0x0c4) { ++identifiers; require(write.value == fixture.id, "wrong product command id"); }
    if (write.address == 0x0c8) { ++opcodes; require(write.value == 7, "wrong product opcode"); }
  }
  require(sources == 1 && submits == 1 && identifiers == 1 && opcodes == 1 && !(base & 63),
          "product launch requires exactly one identified native command");
  return base;
}

inline std::array<uint32_t, 12> expected_product_completion(const Fixture &fixture,
                                                          const Memory &initial) {
  const auto base = product_job_address(fixture);
  require(initial.contains_line(base) && initial.contains_line(uint64_t(base) + 64),
          "product job identity is outside its original allocation");
  require(product_word(initial, base, 0) == 0x474e4a01 && product_word(initial, base, 1) == 512,
          "product completion requires the original GNJ1 input");
  std::array<uint32_t, 12> expected{};
  expected[0] = 0x474e5801;
  // Published GNJ1 words: context=24, generation=25, pass=26.
  expected[6] = product_word(initial, base, 24);
  expected[7] = product_word(initial, base, 26);
  expected[8] = product_word(initial, base, 25);
  return expected;
}

// GNX1 contains fault/identity fields, not completed draw/primitive counters.
template<class Sim>
std::array<uint32_t, 12> read_product_completion(Sim &sim, const Fixture &fixture,
                                               const std::array<uint32_t, 12> &expected) {
  require(fixture.checks.size() == 13, "product completion check set is incomplete");
  std::array<uint32_t, 12> words{};
  for (unsigned n = 0; n < 13; ++n) {
    const auto &check = fixture.checks[n];
    require(check.address == (n == 0 ? 0x124u : 0x180u + 4 * (n - 1)),
            "product completion check order changed");
    const auto value = sim.read(check.address);
    require((value & check.mask) == check.expected, "native completion or GNX1 check failed");
    if (n == 0) require(value == (0x8700u | fixture.id), "unexpected native completion metadata");
    else words[n - 1] = value;
  }
  require(words == expected, "native GNX1 fault or job identity differs");
  return words;
}

template<class Sim>
void retire_product_completion(Sim &sim, const Fixture &fixture) {
  require(fixture.finish.size() == 2 && fixture.finish[0].address == 0x130 &&
          fixture.finish[0].value == 1 && fixture.finish[1].address == 0x00c &&
          fixture.finish[1].value == 3, "product retirement requires exactly one pop and IRQ acknowledgement");
  for (const auto &write : fixture.finish) sim.write(write.address, write.value);
  require(!(sim.read(0x120) & 2) && !sim.model->io_m_irq,
          "product completion did not retire exactly once");
  const auto requests = sim.requests();
  require(!requests.arvalid && !requests.awvalid && !requests.wvalid,
          "product retirement offered new external traffic");
}
} // namespace native_frame
