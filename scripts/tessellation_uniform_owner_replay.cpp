#define OPENGPU_OWNER_IMAGE_FLOAT_STAGE 1
#include "native_sample_owner_runtime.hpp"
#include "../driver/opengpu_tessellation_uniform_abi.h"
#ifndef OPENGPU_FLASHSIM_NATIVE
#error "This qualification uses the native FlashSim model."
#endif

namespace {
using namespace native_owner;

struct Phase {
  uint32_t id, opcode, completion_mask, completion, read_delay, write_delay, stall_limit, status_address, upload;
  std::vector<Command> submit, finish;
  std::set<uint64_t> read_lines;
};

struct Guard {
  uint64_t first, end;
  uint32_t fill;
};

struct Plan {
  uint32_t budget, completion_reg;
  std::vector<Command> initialize, retire, expected_memory;
  std::vector<uint32_t> details;
  std::vector<Writable> writable;
  std::vector<Guard> guards;
  std::vector<Phase> phases;

  static Plan read(const std::filesystem::path &path) {
    std::ifstream in(path, std::ios::binary);
    require(bool(in), "missing checked-uniform replay plan");
    magic(in, "OGTUF001");
    auto word = [&] { return uint32_t(read_le(in, 4)); };
    auto count = [&] (unsigned limit) {
      auto size = word();
      require(size <= limit, "oversized checked-uniform replay section");
      return size;
    };
    auto commands = [&] {
      std::vector<Command> result;
      for (auto size = count(64); size; --size) result.push_back({word(), word()});
      return result;
    };
    Plan plan{};
    plan.budget = word();
    plan.completion_reg = word();
    require(plan.budget == 1000000 && plan.completion_reg == GPU_REG_UCMD_COMPLETION,
      "invalid checked-uniform cycle or completion contract");
    plan.initialize = commands();
    plan.retire = commands();
    for (auto size = count(12); size; --size) plan.details.push_back(word());
    for (auto size = count(16); size; --size) plan.writable.push_back({word(), word()});
    plan.expected_memory = commands();
    for (auto size = count(8); size; --size) plan.guards.push_back({word(), word(), word()});
    for (auto size = count(16); size; --size) {
      Phase phase{word(), word(), word(), word(), word(), word(), word(), word(), word()};
      phase.submit = commands();
      phase.finish = commands();
      for (auto reads = count(32); reads; --reads) {
        const auto address = word();
        require(!(address & 63) && phase.read_lines.insert(address).second,
          "invalid required checked-uniform AXI read");
      }
      require(phase.upload <= 1 && !phase.submit.empty() &&
        phase.submit.back().address == GPU_REG_UCMD_SUBMIT && phase.submit.back().value == 1,
        "invalid checked-uniform command");
      const auto invalidate = phase.opcode == GPU_UCMD_OP_INVALIDATE;
      require((!invalidate && phase.read_delay == 3 && phase.write_delay == 7) ||
        (invalidate && !phase.read_delay && !phase.write_delay && phase.read_lines.empty()),
        "unrecognized checked-uniform response cadence");
      plan.phases.push_back(std::move(phase));
    }
    require(in.peek() == EOF && plan.details.size() == 12 && plan.writable.size() == 4 &&
      plan.expected_memory.size() == 25 && plan.guards.size() == 2 &&
      (plan.phases.size() == 6 || plan.phases.size() == 10), "incomplete checked-uniform replay plan");
    return plan;
  }
};


struct UniformRead {
  uint32_t root, default_binding, default_line;
};

using UniformReads = std::array<std::array<UniformRead, 2>, 3>;

UniformReads read_uniform_targets(const std::filesystem::path &path, const Plan &plan) {
  std::ifstream in(path, std::ios::binary);
  require(bool(in) && plan.phases.size() == 6, "missing three-domain recovery inputs");
  magic(in, "OGTUR001");
  UniformReads targets{};
  std::set<uint32_t> seen;
  for (size_t domain = 0; domain < targets.size(); ++domain) {
    const auto &phase = plan.phases[domain + 1];
    require(phase.opcode == GPU_UCMD_OP_TESSELLATION && phase.upload, "recovery requires a staged draw");
    for (auto &target : targets[domain]) {
      target = {uint32_t(read_le(in, 4)), uint32_t(read_le(in, 4)), uint32_t(read_le(in, 4))};
      for (auto line : {target.root, target.default_binding, target.default_line})
        require(!(line & 63) && phase.read_lines.count(line) && seen.insert(line).second,
          "recovery target is outside its checked-uniform stage");
    }
  }
  require(in.peek() == EOF, "trailing uniform recovery targets");
  return targets;
}

void set_word(Memory &memory, uint64_t address, uint32_t value) {
  for (unsigned i = 0; i < 4; ++i) memory.byte(address + i, value >> (8 * i));
}

void verify_uniform_reads(const Memory &before, const Memory &after, const std::set<uint64_t> &lines) {
  for (const auto address : lines)
    for (uint64_t offset = 0; offset < 64; ++offset)
      require(before.byte(address + offset) == after.byte(address + offset),
        "checked-uniform execution changed a uniform input");
}

void upload(Memory &memory, const std::filesystem::path &path) {
  const auto patch = Memory::read(path.string());
  uint64_t total = 0;
  for (const auto &range : patch.ranges) {
    total += range.end - range.start;
    require(total <= 256 * 1024, "oversized checked-uniform upload");
    require(std::any_of(memory.ranges.begin(), memory.ranges.end(), [&](const auto &backing) {
      return backing.start <= range.start && range.end <= backing.end;
    }), "checked-uniform upload escaped its physical allocation");
    for (auto address = range.start; address < range.end; ++address) memory.byte(address, patch.byte(address));
  }
}

struct ObservedSimulation : Simulation {
  using Simulation::Simulation;
  std::set<uint64_t> pending_reads;
  uint64_t last_progress = 0;

  template<class Observe>
  void step(Agent &agent, uint64_t cycle, Observe control) {
    Simulation::step(agent, cycle, [&](const auto &dut) {
      control(dut);
      const bool read_address = dut.io_m_axi_arvalid && dut.io_m_axi_arready;
      if (read_address) pending_reads.erase(dut.io_m_axi_araddr & ~uint64_t(63));
      if (read_address || (dut.io_m_axi_rvalid && dut.io_m_axi_rready) ||
          (dut.io_m_axi_awvalid && dut.io_m_axi_awready) || (dut.io_m_axi_wvalid && dut.io_m_axi_wready) ||
          (dut.io_m_axi_bvalid && dut.io_m_axi_bready)) last_progress = cycle;
    });
  }
};

void replay(int argc, char **argv) {
  require(argc == 3, "usage: checked-uniform-replay FIXTURE_DIRECTORY CASE");
  const std::filesystem::path fixture = argv[1];
  const std::string name = argv[2];
  const bool failed_reads = name == "read-fault", malformed = name == "malformed", cancel = name == "cancel";
  const bool recovery = failed_reads || malformed || cancel;
  require(recovery || name == "distinct" || name == "capacity" || name == "invalidate", "unknown tessellation case");
  const std::string replay_case = recovery ? "capacity" : name;
  const auto plan = Plan::read(fixture / (replay_case + ".commands"));
  const auto targets = recovery ? read_uniform_targets(fixture / "capacity.resources", plan) : UniformReads{};
  Memory memory;
  for (const auto &range : plan.writable) {
    require(range.first < range.end && (memory.ranges.empty() || range.first >= memory.ranges.back().end),
      "invalid checked-uniform physical allocation");
    memory.ranges.push_back({range.first, range.end, 0});
  }
  upload(memory, fixture / "initial.memory");

  ObservedSimulation sim(argc, argv);
  sim.reset();
  Agent agent(memory);
  ReplayBus<ObservedSimulation> bus(sim, agent, plan.budget);
  const auto profile = probe_sample_profile(sim, agent, bus);
  require(profile.words.size() == 112 && profile.registers.size() == 5,
    "wrong full owner for checked-uniform replay");
  const auto write = [&](const std::vector<Command> &commands) {
    for (const auto &command : commands) bus.write(command.address, command.value);
  };
  write(plan.initialize);

  const auto execute = [&](const Phase &phase, const std::set<uint64_t> &reads, uint32_t expected_completion) {
    require(agent.drained() && !sim.model->io_m_irq, "prior checked-uniform owner is still live");
    bus.begin_record(plan.budget);
    sim.last_progress = bus.cycles;
    sim.pending_reads = reads;
    write(phase.submit);
    while (!sim.model->io_m_irq) {
      bus.step();
      if (phase.stall_limit) require(bus.cycles - sim.last_progress <= phase.stall_limit,
        "checked-uniform command stopped making external progress");
    }
    require(agent.drained(), "completion arrived before physical memory drained");
    const auto complete = agent.traffic;
    const auto status = bus.read(GPU_REG_UCMD_STATUS);
    const auto irq = bus.read(GPU_REG_IRQ);
    const auto completion = bus.read(plan.completion_reg);
    if (!(status & GPU_UCMD_STATUS_COMPLETION) || irq != (GPU_IRQ_ENABLE | GPU_IRQ_PENDING) ||
        (completion & phase.completion_mask) != expected_completion || GPU_UCMD_COMPLETION_ID(completion) != phase.id ||
        GPU_UCMD_COMPLETION_OPCODE(completion) != phase.opcode) {
      std::cerr << "unexpected completion 0x" << std::hex << completion
                << " status 0x" << status << " IRQ 0x" << irq << std::dec << '\n';
      throw std::runtime_error("checked-uniform completion mismatch");
    }
    for (const auto missing : sim.pending_reads) std::cerr << "missing physical read 0x" << std::hex << missing << std::dec << '\n';
    require(sim.pending_reads.empty(), "checked-uniform command skipped a required AXI read");
    if (phase.status_address)
      require(PtePages::word(memory, phase.status_address) == 0, "GTF1 checkpoint reported failure");

    std::vector<uint32_t> held;
    for (const auto address : plan.details) held.push_back(bus.read(address));
    for (unsigned cycle = 0; cycle < 8; ++cycle) {
      require(sim.model->io_m_irq && agent.drained(), "held completion lost ownership");
      bus.step();
    }
    require(bus.read(plan.completion_reg) == completion && bus.read(GPU_REG_UCMD_STATUS) == status &&
      bus.read(GPU_REG_IRQ) == irq && sim.model->io_m_irq, "held completion or IRQ changed");
    for (size_t i = 0; i < plan.details.size(); ++i)
      require(bus.read(plan.details[i]) == held[i], "held native result details changed");
    write(plan.retire);
    require(bus.read(GPU_REG_UCMD_STATUS) == GPU_UCMD_STATUS_READY &&
      bus.read(GPU_REG_IRQ) == GPU_IRQ_ENABLE && !sim.model->io_m_irq && agent.drained(),
      "checked-uniform command did not fully retire");
    for (const auto address : plan.details)
      require(bus.read(address) == 0, "retired native result details persisted");
    traffic_unchanged(complete, agent.traffic);
    return complete;
  };

  const auto stage_upload = [&](size_t index) {
    upload(memory, fixture / (replay_case + "-" + std::to_string(index) + ".memory"));
  };
  const std::vector<Writable> scratch{plan.writable[0], plan.writable[1]};
  // A domain switch may checkpoint feedback state without appending output.
  const std::vector<Writable> fault_writable{plan.writable[0], plan.writable[1], plan.writable[2]};
  unsigned injected_faults = 0, rejected_records = 0, cancelled_commands = 0;
  if (cancel) {
    // A command reset invalidates live feedback objects. Start the healthy
    // feedback capture after all cancelled draws on the same model and RAM.
    const auto vector_satp = bus.read(GPU_REG_UCMD_VECTOR_SATP);
    const auto instruction_satp = bus.read(GPU_REG_UCMD_INSTRUCTION_SATP);
    for (size_t domain = 0; domain < targets.size(); ++domain) {
      const auto &phase = plan.phases[domain + 1];
      stage_upload(domain + 1);
      for (size_t stage = 0; stage < targets[domain].size(); ++stage) {
        const auto line = targets[domain][stage].default_line;
        const Memory initial = memory;
        const auto first_hold = agent.held_read_cycles();
        bus.begin_record(plan.budget);
        agent.arm_response_hold(ResponseChannel::read, line, uint64_t(line) + 64);
        write(phase.submit);
        while (agent.held_read_cycles() == first_hold) {
          require(!sim.model->io_m_irq, "tessellation completed before the selected read was held");
          bus.step();
        }
        bus.write(GPU_REG_UCMD_RESET, GPU_UCMD_RESET_REQUEST);
        bus.write(GPU_REG_UCMD_VECTOR_SATP, 0, 2);
        for (unsigned i = 0; i < 16; ++i) {
          const auto status = bus.read(GPU_REG_UCMD_STATUS);
          require((status & GPU_UCMD_STATUS_RESET_BUSY) && !(status & GPU_UCMD_STATUS_COMPLETION) &&
            !sim.model->io_m_irq && !agent.drained() && bus.read(GPU_REG_UCMD_VECTOR_SATP) == vector_satp,
            "tessellation reset acknowledged while a physical read was held");
        }
        const auto held = agent.held_read_cycles() - first_hold;
        agent.release_response_hold();
        while (!sim.model->io_m_irq) bus.step();
        require(bus.read(GPU_REG_UCMD_STATUS) == GPU_UCMD_STATUS_READY && agent.drained() &&
          bus.read(GPU_REG_UCMD_VECTOR_SATP) == vector_satp &&
          bus.read(GPU_REG_UCMD_INSTRUCTION_SATP) == instruction_satp &&
          bus.read(GPU_REG_IRQ) == (GPU_IRQ_ENABLE | GPU_IRQ_PENDING),
          "tessellation command reset did not drain to its acknowledgement");
        agent.disarm_response_hold();
        bus.write(GPU_REG_IRQ, GPU_IRQ_ENABLE | GPU_IRQ_PENDING);
        for (const auto address : plan.details) require(!bus.read(address), "cancelled tessellation result persisted");
        require(bus.read(GPU_REG_IRQ) == GPU_IRQ_ENABLE && !sim.model->io_m_irq &&
          !agent.traffic.read_faults && !agent.traffic.write_faults,
          "tessellation cancellation left an interrupt or physical fault");
        verify_readonly(initial, memory, scratch);
        verify_uniform_reads(initial, memory, phase.read_lines);
        ++cancelled_commands;
        std::cout << "cancel domain=" << domain << " stage=" << stage << " holdCycles=" << held << '\n';
      }
    }
  }

  for (size_t n = 0; n < plan.phases.size(); ++n) {
    const auto &phase = plan.phases[n];
    std::cout << name << " beginning command=" << phase.id << " opcode=" << phase.opcode << std::endl;
    if (phase.upload) stage_upload(n);
    if ((failed_reads || malformed) && n >= 1 && n <= targets.size()) {
      const size_t domain = n - 1;
      // Failed tessellation work retains the command and opcode with success cleared.
      const uint32_t failure = (phase.completion & ~(1u << 15)) | (2u << 11);
      for (size_t stage = 0; stage < targets[domain].size(); ++stage) {
        const auto &target = targets[domain][stage];
        const Memory initial = memory;
        const auto before = agent.traffic;
        if (failed_reads) {
          const auto injected_before = agent.injected_read_faults();
          const auto line = target.default_line;
          agent.arm_read_faults(line, uint64_t(line) + 64);
          const auto complete = execute(phase, {line}, failure);
          agent.disarm_read_faults();
          require(agent.injected_read_faults() == injected_before + 1 &&
            complete.read_faults == before.read_faults + 1 && complete.write_faults == before.write_faults,
            "checked-uniform service did not consume exactly one physical read fault");
          verify_readonly(initial, memory, fault_writable);
          verify_uniform_reads(initial, memory, phase.read_lines);
          ++injected_faults;
        } else {
          const uint32_t wrong_stage = stage == 0 ? OPENGPU_TESSELLATION_UNIFORM_EVALUATION_STAGE :
            OPENGPU_TESSELLATION_UNIFORM_CONTROL_STAGE;
          const Command invalid[] = {
            {target.root + 4 * OPENGPU_TESSELLATION_UNIFORM_ROOT_STAGE_WORD, wrong_stage},
            {target.root + 2 * 4, OPENGPU_TESSELLATION_UNIFORM_VERSION - 1},
            {target.root + 4 * OPENGPU_TESSELLATION_UNIFORM_ROOT_MAPLOW_WORD,
              OPENGPU_TESSELLATION_UNIFORM_BINDINGS},
            {target.default_binding + 4 * OPENGPU_TESSELLATION_UNIFORM_BINDING_STAGEMASK_WORD, 1u << wrong_stage},
          };
          for (const auto &change : invalid) {
            const auto previous = PtePages::word(memory, change.address);
            require(previous != change.value, "malformed case did not change its descriptor");
            set_word(memory, change.address, change.value);
            const auto complete = execute(phase, {change.address & ~uint64_t(63)}, failure);
            require(complete.read_faults == before.read_faults && complete.write_faults == before.write_faults,
              "malformed descriptor generated a physical AXI fault");
            set_word(memory, change.address, previous);
            verify_readonly(initial, memory, fault_writable);
            verify_uniform_reads(initial, memory, phase.read_lines);
            ++rejected_records;
          }
        }
      }
    }
    const Memory initial = memory;
    const auto before = agent.traffic;
    const auto first_cycle = bus.cycles;
    const auto complete = execute(phase, phase.read_lines, phase.completion);
    require(complete.read_faults == before.read_faults && complete.write_faults == before.write_faults,
      "healthy checked-uniform command incurred a physical memory fault");
    if (phase.opcode == GPU_UCMD_OP_INVALIDATE) traffic_unchanged(before, complete);
    verify_uniform_reads(initial, memory, phase.read_lines);
    write(phase.finish);
    std::cout << name << " retired command=" << phase.id << " cycles=" << bus.cycles - first_cycle
              << " AR=" << complete.reads - before.reads << " AW=" << complete.writes - before.writes
              << " required_reads=" << phase.read_lines.size() << std::endl;
  }
  for (const auto &expected : plan.expected_memory) {
    if (PtePages::word(memory, expected.address) == expected.value) continue;
    std::cerr << "unexpected checked-uniform result at 0x" << std::hex << expected.address << '\n';
    throw std::runtime_error("checked-uniform feedback differs");
  }
  for (const auto &guard : plan.guards) {
    require(guard.first < guard.end && guard.end - guard.first <= Memory::page_bytes &&
      guard.fill <= 255, "invalid feedback guard");
    for (auto address = guard.first; address < guard.end; ++address)
      require(memory.byte(address) == guard.fill, "checked-uniform feedback damaged a buffer guard");
  }
  sim.model->final();
  std::cout << "CHECKED_UNIFORM_REPLAY_PASS case=" << name << " commands=" << plan.phases.size()
            << " cycles=" << bus.cycles << " resetCount=1 feedbackWords=22 derivedCounters=3"
            << " injectedReadFaults=" << injected_faults << " rejectedRecords=" << rejected_records
            << " commandResets=" << cancelled_commands << '\n';
}
} // namespace

int main(int argc, char **argv) try {
  replay(argc, argv);
  return 0;
} catch (const std::exception &error) {
  std::cerr << "checked-uniform replay failed: " << error.what() << '\n';
  return 1;
}
