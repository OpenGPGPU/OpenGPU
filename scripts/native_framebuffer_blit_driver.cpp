// Bounded actual-caller shader execution on an existing complete 96-word DUT.
// native_owner_runtime.hpp must come from the explicitly qualified owner root;
// this executable is a host fixture, not an original Linux MMIO capture replay.
#include "native_owner_runtime.hpp"
#include "native_framebuffer_blit_lease.hpp"
#include "../driver/opengpu_storage_command_abi.h"

namespace framebuffer_blit {
using Memory=native_frame::Memory;
using HostLease=Lease<Memory>;
struct Completion {uint32_t word,status,irq;uint64_t bytes,cycles;};

template<class Sim>
Completion complete(Sim &sim,native_frame::Agent &agent,native_owner::ReplayBus<Sim> &bus,
    uint32_t id,uint32_t opcode,uint64_t bytes,uint64_t start) {
  while(!sim.model->io_m_irq)bus.step();
  require(agent.drained(),"blit completion preceded last external ACK");
  const auto traffic=agent.traffic;
  Completion result{bus.read(GPU_REG_UCMD_COMPLETION),bus.read(GPU_REG_UCMD_STATUS),
    bus.read(GPU_REG_IRQ),0,0};
  const auto lo=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO),hi=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI);
  result.bytes=(uint64_t(hi)<<32)|lo;
  require(result.word==(0x8000u|(opcode<<8)|id) && result.bytes==bytes &&
    result.status==(GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY) &&
    result.irq==(GPU_IRQ_ENABLE|GPU_IRQ_PENDING),"blit completion identity/status/bytes differ");
  for(unsigned n=0;n<32;n++) {
    require(sim.model->io_m_irq,"blit held IRQ disappeared");bus.step();
    require(agent.drained(),"blit held completion allowed late memory work");
  }
  require(bus.read(GPU_REG_UCMD_COMPLETION)==result.word && bus.read(GPU_REG_UCMD_STATUS)==result.status &&
    bus.read(GPU_REG_IRQ)==result.irq && bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO)==lo &&
    bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI)==hi,"blit held completion changed");
  native_owner::traffic_unchanged(traffic,agent.traffic);
  bus.write(GPU_REG_UCMD_COMPLETION_POP,1);bus.write(GPU_REG_IRQ,GPU_IRQ_ENABLE|GPU_IRQ_PENDING);
  for(unsigned n=0;n<8;n++)bus.step();
  require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && bus.read(GPU_REG_IRQ)==GPU_IRQ_ENABLE &&
    !sim.model->io_m_irq && agent.drained(),"blit retirement did not release completion/IRQ");
  native_owner::traffic_unchanged(traffic,agent.traffic);result.cycles=bus.cycles-start;return result;
}

template<class Sim>
Completion invalidate(Sim &sim,native_frame::Agent &agent,native_owner::ReplayBus<Sim> &bus,
    uint32_t id,uint32_t address,uint32_t bytes) {
  const auto start=bus.cycles;
  bus.write(GPU_REG_UCMD_ID,id);bus.write(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_INVALIDATE);
  bus.write(GPU_REG_UCMD_SOURCE,address);bus.write(GPU_REG_UCMD_BYTES,bytes);
  bus.write(GPU_REG_UCMD_FLAGS,0);bus.write(GPU_REG_UCMD_DMA_DEPENDENCY,0);
  bus.write(GPU_REG_UCMD_WAIT_EVENT,0);bus.write(GPU_REG_UCMD_SIGNAL_EVENT,0);
  bus.write(GPU_REG_UCMD_SUBMIT,1);
  return complete(sim,agent,bus,id,GPU_UCMD_OP_INVALIDATE,bytes,start);
}
} // namespace framebuffer_blit

int main(int argc,char **argv) {
  try {
    using namespace framebuffer_blit;
    require(argc>=3,"usage: native_framebuffer_blit_driver NEW.json ORIGINAL_INPUT.blit [...]");
    const std::filesystem::path output=argv[1];require(!std::filesystem::exists(output),"blit output already exists");
    // All packets/oracles are admitted before a model is created.
    std::vector<Packet> inputs;for(int n=2;n<argc;n++)inputs.push_back(Packet::read(argv[n]));
    require(inputs.size()<=37,"too many actual-caller blit inputs");
    native_frame::Simulation sim(argc,argv);sim.reset();Memory memory;
    native_frame::Agent agent(memory);native_owner::ReplayBus<native_frame::Simulation> bus(sim,agent,100000);
    const auto profile=native_owner::probe_profile(sim,agent,bus,true);
    require(profile.words.size()==96 && profile.words.at(2).value==0x3ffff,
      "blit requires the complete 96-word owner profile");
    std::vector<std::array<uint64_t,3>> cycles;
    for(size_t epoch=0;epoch<inputs.size();epoch++) {
      const auto &input=inputs[epoch];HostLease lease(input,unsigned(epoch));
      require(agent.drained() && !sim.model->io_m_irq,"blit changes VM before prior drain");
      memory=lease.memory;const auto initial=memory;
      bus.begin_record(10000000);bus.write(GPU_REG_IRQ,GPU_IRQ_ENABLE|GPU_IRQ_PENDING);
      const auto before=invalidate(sim,agent,bus,1,lease.physical_base[2],(input.views[2].limit+63)&~63u);
      bus.write(GPU_REG_UCMD_VECTOR_SATP,lease.vector_satp());
      bus.write(GPU_REG_UCMD_INSTRUCTION_SATP,lease.instruction_satp());bus.write(GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_FULL);
      const std::array<std::pair<uint32_t,uint32_t>,7> storage{{
        {OPENGPU_STORAGE_COMMAND_REG_CONTROL,1u|(1u<<OPENGPU_STORAGE_COMMAND_VERSION_SHIFT)},
        {OPENGPU_STORAGE_COMMAND_REG_TABLE_BASE,HostLease::table},
        {OPENGPU_STORAGE_COMMAND_REG_TABLE_LIMIT_LO,HostLease::table+256},
        {OPENGPU_STORAGE_COMMAND_REG_TABLE_LIMIT_HI,0},{OPENGPU_STORAGE_COMMAND_REG_CONTEXT,input.context},
        {OPENGPU_STORAGE_COMMAND_REG_PASS,7},{OPENGPU_STORAGE_COMMAND_REG_GENERATION,1}}};
      for(const auto &word:storage)bus.write(word.first,word.second);
      const auto start=bus.cycles;
      bus.write(GPU_REG_UCMD_ID,2);bus.write(GPU_REG_UCMD_OPCODE,0);
      bus.write(GPU_REG_UCMD_KERNEL_PC,HostLease::pc);bus.write(GPU_REG_UCMD_KERNARG,HostLease::kernarg);
      for(unsigned a=0;a<3;a++) {
        bus.write(GPU_REG_UCMD_GRID_X+a*4,input.grid[a]);bus.write(GPU_REG_UCMD_LOCAL_X+a*4,input.local[a]);
      }
      bus.write(GPU_REG_UCMD_SUBMIT,1);
      const auto kernel=complete(sim,agent,bus,2,0,0,start);
      const auto after=invalidate(sim,agent,bus,3,lease.physical_base[1],(input.views[1].limit+63)&~63u);
      lease.memory=memory;lease.verify(initial,input,true);
      require(!agent.traffic.read_faults && !agent.traffic.write_faults,"blit encountered a physical memory fault");
      // Drop authority only after final publication completion retirement and
      // all AXI responses. Revoke every leaf before shootdown/bare SATP.
      bus.write(OPENGPU_STORAGE_COMMAND_REG_CONTROL,0);
      for(const auto &level:lease.levels)for(unsigned n=0;n<1024;n++)HostLease::put(memory,level.second+n*4,0);
      bus.write(GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_FULL);
      bus.write(GPU_REG_UCMD_VECTOR_SATP,0);bus.write(GPU_REG_UCMD_INSTRUCTION_SATP,0);
      require(!bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP) &&
        bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && agent.drained() && !sim.model->io_m_irq,
        "blit context teardown did not become idle/bare");
      cycles.push_back({before.cycles,kernel.cycles,after.cycles});
    }
    native_owner::atomic_json(output,[&](auto &out) {
      out<<"{\"schema\":\"opengpu-framebuffer-blit-host-v1\",\"resetCount\":1,\"cases\":[";
      for(size_t n=0;n<cycles.size();n++)out<<(n?",":"")<<"{\"inputIndex\":"<<n
        <<",\"coordinateInvalidateCycles\":"<<cycles[n][0]<<",\"computeThroughRetirementCycles\":"<<cycles[n][1]
        <<",\"publicationInvalidateCycles\":"<<cycles[n][2]<<"}";
      out<<"],\"originalCallerInputs\":true,\"originalLinuxTrace\":false,\"independentBytes\":true,"
        "\"vmAndReadOnlyGuards\":true,\"full96Profile\":true,\"sourceQualificationRequired\":true,"
        "\"clockQualified\":false,\"fpsQualified\":false}\n";
    });sim.model->final();return 0;
  }catch(const std::exception &error){std::cerr<<"framebuffer blit host failed: "<<error.what()<<'\n';return 1;}
}
