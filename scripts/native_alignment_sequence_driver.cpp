// Command coexistence qualification on the complete, assertion-enabled v2 DUT.
// The independent Scala oracle checks both saved native images and all compute
// words/guards. There is one reset per scene, never between its four commands.
#include "native_host_simulation.hpp"
#include <cstring>
#include <filesystem>
#include <iostream>
#include <set>

using namespace native_frame;
#ifdef OPENGPU_FLASHSIM_NATIVE
static_assert(GpuHostSystemAxiDut::assertion_count > 0, "sequence model has no RTL assertions");
#endif

namespace alignment_sequence {
constexpr uint32_t low_start = 0x1000, low_end = 0x70000;
constexpr uint32_t irq = 0x0c, command_id = 0xc4, opcode = 0xc8;
constexpr uint32_t completion = 0x124, completion_pop = 0x130;
constexpr uint32_t groups = 4, items = 128, guard = 0xa5a5a5a5;

uint32_t float_bits(float value) {
  uint32_t bits; std::memcpy(&bits, &value, sizeof(bits)); return bits;
}
void put(Memory &memory, uint32_t address, uint32_t value) {
  for (unsigned b = 0; b < 4; ++b) memory.byte(address + b, uint8_t(value >> (8*b)));
}
uint32_t word(const Memory &memory, uint32_t address) {
  uint32_t value = 0;
  for (unsigned b = 0; b < 4; ++b) value |= uint32_t(memory.byte(address + b)) << (8*b);
  return value;
}
uint32_t add(unsigned rd, unsigned a, unsigned b) {
  return (b << 20) | (a << 15) | (rd << 7) | 0x33;
}
uint32_t shift(unsigned rd, unsigned source, unsigned amount) {
  return (amount << 20) | (source << 15) | (1 << 12) | (rd << 7) | 0x13;
}
uint32_t output(unsigned epoch) { require(epoch < 2, "invalid compute epoch"); return 0x20000 + epoch*0x10000; }
uint32_t pc(unsigned epoch) { require(epoch < 2, "invalid compute epoch"); return 0x1000 + epoch*0x1000; }
std::vector<uint32_t> program(unsigned epoch) {
  require(epoch < 2, "invalid compute epoch");
  return {shift(10,2,9), shift(11,8,2), add(10,10,11), add(10,10,1),
    0x0104f057, ((3 + 8*epoch) << 20) | (2 << 15) | (12 << 7) | 0x13,
    (1 << 25) | (1 << 20) | (12 << 15) | (4 << 12) | (2 << 7) | 0x57,
    (1 << 25) | (10 << 15) | (6 << 12) | (2 << 7) | 0x27,
    0x00020737, add(14,14,10), (14 << 15) | (2 << 12) | (1 << 7) | 7,
    0x00108153, 0x000016b7, add(15,10,13), (2 << 20) | (15 << 15) | (2 << 12) | 0x27,
    0x30500073};
}
void seed(Memory &memory) {
  for (const auto &range : memory.ranges)
    require(range.end <= low_start || range.start >= low_end, "compute fixture aliases native allocations");
  memory.ranges.insert(memory.ranges.begin(), {low_start, low_end, 0xa5});
  std::sort(memory.ranges.begin(), memory.ranges.end(),
    [](const Memory::Range &a, const Memory::Range &b) { return a.start < b.start; });
  for (unsigned epoch = 0; epoch < 2; ++epoch) {
    const auto code = program(epoch);
    for (unsigned n = 0; n < code.size(); ++n) put(memory, pc(epoch) + 4*n, code[n]);
    for (unsigned group = 0; group < groups; ++group)
      for (unsigned warp = 0; warp < 8; ++warp)
        put(memory, output(epoch) + 0x20000 + group*512 + warp*64,
          float_bits(1.0f + float(group*8 + warp + epoch*32)/16.0f));
  }
}
void verify(const Memory &memory, const Memory &initial, unsigned completed) {
  require(completed >= 1 && completed <= 2, "invalid completed epoch count");
  for (unsigned epoch = 0; epoch < 2; ++epoch) {
    for (int item = -1; item <= int(groups*items); ++item) {
      const bool active = epoch < completed && item >= 0 && item < int(groups*items);
      const uint32_t expected = active ? uint32_t(item%128 + item/128 + 3 + epoch*8) : guard;
      require(word(memory, output(epoch) + item*4) == expected, "compute vector result or guard differs");
      const uint32_t fp = active && item%16 == 0 ?
        float_bits(2.0f + float((item/128)*8 + (item%128)/16 + epoch*32)/8.0f) : guard;
      require(word(memory, output(epoch) + 0x1000 + item*4) == fp, "compute FP result or guard differs");
    }
  }
  for (uint32_t a = low_start; a < low_end; ++a) {
    bool written = false;
    for (unsigned epoch = 0; epoch < completed; ++epoch) {
      const uint32_t base = output(epoch);
      written |= a >= base && a < base + groups*items*4;
      written |= a >= base + 0x1000 && a < base + 0x1000 + groups*items*4;
    }
    if (!written) require(memory.byte(a) == initial.byte(a), "compute code/input/read-only guard changed");
  }
}
void drain_idle(Simulation &sim) {
  sim.pause_memory();
  require(!sim.model->io_m_irq, "IRQ remained after completion POP");
  require(!sim.model->io_m_axi_arvalid && !sim.model->io_m_axi_awvalid && !sim.model->io_m_axi_wvalid,
    "retired command retained an unaccepted AXI offer");
}
uint64_t execute(Simulation &sim, Memory &memory, uint64_t maximum, bool require_two_cus) {
  Agent agent(memory); uint64_t cycle = 0; unsigned peak = 0;
  while (!sim.model->io_m_irq && cycle < maximum) {
    sim.step(agent, cycle++);
#ifdef OPENGPU_FLASHSIM_NATIVE
    sim.model->eval_io_performance_activeComputeUnits();
#endif
    peak = std::max(peak, unsigned(sim.model->io_performance_activeComputeUnits));
  }
  sim.pause_memory();
  require(cycle < maximum, "coenabled host command exceeded its cycle bound");
  require(agent.drained(), "coenabled host completion leaked AXI traffic");
  require(!agent.traffic.read_faults && !agent.traffic.write_faults, "coenabled command accessed unmapped backing");
  require(!require_two_cus || peak == 2, "compute did not execute concurrently on both CUs");
  return cycle;
}
uint64_t compute(Simulation &sim, Memory &memory, unsigned epoch) {
  require(!sim.model->io_m_irq, "stale IRQ before compute");
  const std::vector<Command> commands{{command_id,41 + epoch},{opcode,0},{0xcc,pc(epoch)},
    {0xd0,output(epoch)},{0xd4,groups},{0xd8,1},{0xdc,1},{0xe0,items},{0xe4,1},{0xe8,1},
    {0xec,0},{0xf0,0},{0x114,0},{0x118,0},{0x11c,1}};
  for (const auto &entry : commands) sim.write(entry.address, entry.value);
  const auto cycles = execute(sim, memory, 100000, true);
  const uint32_t expected = 0x8000 | (41 + epoch);
  for (unsigned n = 0; n < 5; ++n)
    require((sim.read(completion) & 0x1ffff) == expected, "compute completion changed or failed");
  require(!sim.read(0x128) && !sim.read(0x12c), "compute completion reported DMA bytes");
  sim.write(completion_pop,1); sim.write(irq,3); drain_idle(sim);
  return cycles;
}
} // namespace alignment_sequence

int main(int argc, char **argv) {
  try {
    require(argc == 3, "usage: native_alignment_sequence_driver V2_SMOKE_FIXTURES OUTPUT_DIR");
    const auto fixtures = std::filesystem::path(argv[1]), output = std::filesystem::path(argv[2]);
    std::filesystem::create_directories(output);
    std::ifstream suite(fixtures / "suite.txt"); require(bool(suite), "missing native fixture suite");
    const std::vector<std::string> expected{"desktop-composition","textured-3d","opaque-overdraw",
      "transparent-layers","small-triangles","msaa-4x"};
    std::vector<std::string> names; std::string name;
    while (std::getline(suite,name)) names.push_back(name);
    require(names == expected, "complete ordered six-scene suite required");
    Simulation sim(argc,argv);
    for (unsigned index = 0; index < names.size(); ++index) {
      const auto &id = names[index];
      const auto fixture = Fixture::read((fixtures / (id + ".commands")).string());
      require(fixture.alignment_v2() && fixture.width == 32 && fixture.height == 32 && fixture.id == index+1,
        "command coexistence requires the frozen complete v2 smoke profile");
      auto memory = Memory::read((fixtures / (id + ".memory")).string());
      alignment_sequence::seed(memory); const Memory initial = memory;
      sim.reset();
      for (const auto &r : fixture.resources) require(sim.read(r.address) == r.value, "v2 discovery contract differs");
      sim.write(alignment_sequence::irq,1);
      for (unsigned epoch = 0; epoch < 2; ++epoch) {
        const auto compute_cycles = alignment_sequence::compute(sim,memory,epoch);
        alignment_sequence::verify(memory,initial,epoch+1);
        for (const auto &entry : fixture.writes) sim.write(entry.address,entry.value);
        const auto native_cycles = alignment_sequence::execute(sim,memory,fixture.max_cycles,false);
        for (const auto &check : fixture.checks)
          require((sim.read(check.address) & check.mask) == check.expected,"native completion/GNX1 failed");
        alignment_sequence::verify(memory,initial,epoch+1);
        memory.write((output / (id + ".after-native-" + std::to_string(epoch) + ".memory")).string());
        for (const auto &entry : fixture.finish) sim.write(entry.address,entry.value);
        alignment_sequence::drain_idle(sim);
        std::cout << "{\"scene\":\"" << id << "\",\"epoch\":" << epoch
          << ",\"computeCycles\":" << compute_cycles << ",\"nativeCycles\":" << native_cycles
          << ",\"resetBetweenCommands\":false,\"independentImagesRequired\":true,\"fpsQualified\":false}" << std::endl;
      }
    }
    sim.model->final(); return 0;
  } catch (const std::exception &e) {
    std::cerr << "native alignment sequence failed: " << e.what() << "\n"; return 1;
  }
}
