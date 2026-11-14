// Bounded actual-caller shader execution on an existing complete 96-word DUT.
// native_owner_runtime.hpp must come from the explicitly qualified owner root;
// this executable is a host fixture, not an original Linux MMIO capture replay.
#ifdef OPENGPU_FRAMEBUFFER_BLIT_SAMPLE_OWNER
#if OPENGPU_FRAMEBUFFER_BLIT_SAMPLE_OWNER != 1
#error "112-word blit owner selection must be explicitly one"
#endif
#endif
#include "native_owner_runtime.hpp"
#include "native_framebuffer_blit_lease.hpp"
#include "native_framebuffer_blit_simulation.hpp"
#include "../driver/opengpu_storage_command_abi.h"

namespace framebuffer_blit {
using Memory=native_frame::Memory;
using HostLease=Lease<Memory>;
struct Completion {uint32_t word,status,irq;uint64_t bytes,cycles;};

template<class Sim>
Completion complete(Sim &sim,native_frame::Agent &agent,native_owner::ReplayBus<Sim> &bus,
    uint32_t id,uint32_t opcode,uint64_t bytes,uint64_t start,uint32_t failure=0) {
  while(!sim.model->io_m_irq)bus.step();
  require(agent.drained(),"blit completion preceded last external ACK");
  const auto traffic=agent.traffic;
  Completion result{bus.read(GPU_REG_UCMD_COMPLETION),bus.read(GPU_REG_UCMD_STATUS),
    bus.read(GPU_REG_IRQ),0,0};
  const auto lo=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO),hi=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI);
  result.bytes=(uint64_t(hi)<<32)|lo;
  require(result.word==((failure?failure<<11:0x8000u)|(opcode<<8)|id) && result.bytes==bytes &&
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

using Bus=native_owner::ReplayBus<Simulation>;

uint64_t bind(Simulation &sim,native_frame::Agent &agent,Bus &bus,const HostLease &lease,const Packet &input) {
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
  return before.cycles;
}
uint64_t submit(Bus &bus,const Packet &input,uint32_t id=2) {
  const auto start=bus.cycles;
  bus.write(GPU_REG_UCMD_ID,id);bus.write(GPU_REG_UCMD_OPCODE,0);
  bus.write(GPU_REG_UCMD_KERNEL_PC,HostLease::pc);bus.write(GPU_REG_UCMD_KERNARG,HostLease::kernarg);
  for(unsigned a=0;a<3;a++) {
    bus.write(GPU_REG_UCMD_GRID_X+a*4,input.grid[a]);bus.write(GPU_REG_UCMD_LOCAL_X+a*4,input.local[a]);
  }
  bus.write(GPU_REG_UCMD_SUBMIT,1);return start;
}
void revoke(Simulation &sim,native_frame::Agent &agent,Bus &bus,Memory &memory,const HostLease &lease) {
  require(sim.drained(agent) && !sim.model->io_m_irq,"blit revokes before retirement/ACK drain");
  bus.write(OPENGPU_STORAGE_COMMAND_REG_CONTROL,0);
  for(const auto &level:lease.levels)for(unsigned n=0;n<1024;n++)HostLease::put(memory,level.second+n*4,0);
  bus.write(GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_FULL);
  bus.write(GPU_REG_UCMD_VECTOR_SATP,0);bus.write(GPU_REG_UCMD_INSTRUCTION_SATP,0);
  require(!bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP) &&
    bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && sim.drained(agent) && !sim.model->io_m_irq,
    "blit context teardown did not become idle/bare");
}
std::array<uint64_t,3> healthy(Simulation &sim,native_frame::Agent &agent,Bus &bus,Memory &memory,
    const Packet &input,unsigned epoch) {
  require(sim.drained(agent) && !sim.model->io_m_irq,"blit changes VM before prior drain");
  HostLease lease(input,epoch);memory=lease.memory;const auto initial=memory;
  const auto before=bind(sim,agent,bus,lease,input);
  const auto kernel=complete(sim,agent,bus,2,0,0,submit(bus,input));
  const auto after=invalidate(sim,agent,bus,3,lease.physical_base[1],(input.views[1].limit+63)&~63u);
  lease.memory=memory;lease.verify(initial,input,true);
  require(!agent.traffic.read_faults && !agent.traffic.write_faults,"blit encountered an unplanned physical memory fault");
  revoke(sim,agent,bus,memory,lease);return {before,kernel.cycles,after.cycles};
}
struct Lifecycle {uint64_t fault_cycles,fault_reads,healthy_after_fault;
  std::array<uint64_t,2> reset_cycles,held_cycles,healthy_after_reset;};
Lifecycle lifecycle(Simulation &sim,native_frame::Agent &agent,Bus &bus,Memory &memory,
    const Packet &input,unsigned epoch) {
  Lifecycle result{};
  // Original first color packet, unchanged shader/kernarg/maps/bytes. Only
  // the source's physical AXI R status changes; all VM authority stays pinned.
  HostLease fault(input,epoch++);memory=fault.memory;const auto initial=memory;
  bind(sim,agent,bus,fault,input);
  sim.fault_begin=fault.physical_base[0];sim.fault_end=sim.fault_begin+input.views[0].allocation;
  const auto failed=complete(sim,agent,bus,2,0,0,submit(bus,input),GPU_UCMD_KERNEL_EXECUTION_FAILED);
  result.fault_cycles=failed.cycles;result.fault_reads=sim.fault_reads;
  require(result.fault_reads && sim.drained(agent),"blit fault never traversed/drained the real source AXI read");
  sim.fault_begin=sim.fault_end=0;
  invalidate(sim,agent,bus,3,fault.physical_base[1],(input.views[1].limit+63)&~63u);
  fault.memory=memory;fault.verify_cancelled(initial,input);revoke(sim,agent,bus,memory,fault);
  result.healthy_after_fault=healthy(sim,agent,bus,memory,input,epoch++)[1];
  for(unsigned mode=0;mode<2;mode++) {
    HostLease lease(input,epoch++);memory=lease.memory;const auto original=memory;
    bind(sim,agent,bus,lease,input);
    sim.hold_reads=mode==0;sim.hold_writes=mode==1;
    const auto held_before=mode?sim.held_write_cycles:sim.held_read_cycles;
    const auto start=submit(bus,input);
    while((mode?sim.held_write_cycles:sim.held_read_cycles)==held_before)bus.step();
    require(!sim.drained(agent) && !sim.model->io_m_irq,"blit never retained its delayed external response");
    bus.write(GPU_REG_UCMD_RESET,GPU_UCMD_RESET_REQUEST);
    require(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_RESET_BUSY,"blit reset ignored delayed external ACK");
    // A new SUBMIT races the real reset drain; it must never reach a CU.
    bus.write(GPU_REG_UCMD_ID,9);bus.write(GPU_REG_UCMD_SUBMIT,1);
    for(unsigned n=0;n<32;n++) {
      const auto status=bus.read(GPU_REG_UCMD_STATUS);
      require((status&(GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_RESET_REJECTED|GPU_UCMD_STATUS_VM_BUSY))==
        (GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_RESET_REJECTED|GPU_UCMD_STATUS_VM_BUSY) &&
        !(status&GPU_UCMD_STATUS_COMPLETION) && !sim.model->io_m_irq && !sim.drained(agent),
        "blit reset released completion/VM authority before delayed ACK drain");
    }
    result.held_cycles[mode]=(mode?sim.held_write_cycles:sim.held_read_cycles)-held_before;
    sim.hold_reads=sim.hold_writes=false;
    while(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_RESET_BUSY){}
    require(sim.drained(agent) && sim.model->io_m_irq &&
      bus.read(GPU_REG_UCMD_STATUS)==(GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_RESET_REJECTED) &&
      bus.read(GPU_REG_IRQ)==(GPU_IRQ_ENABLE|GPU_IRQ_PENDING),"blit reset did not retire drained with an empty completion slot");
    const auto traffic=agent.traffic;const auto stopped=memory;
    for(unsigned n=0;n<32;n++)bus.step();
    native_owner::traffic_unchanged(traffic,agent.traffic);
    // No stale completion can reappear, and no byte may change after reset ACK.
    require(bus.read(GPU_REG_UCMD_STATUS)==(GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_RESET_REJECTED),
      "blit reset leaked a discarded command completion");
    for(const auto &r:stopped.ranges)for(uint64_t a=r.start;a<r.end;a++)
      require(memory.byte(a)==stopped.byte(a),"blit wrote after reset acknowledgement");
    bus.write(GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_RESET_REJECTED);
    bus.write(GPU_REG_IRQ,GPU_IRQ_ENABLE|GPU_IRQ_PENDING);
    invalidate(sim,agent,bus,3,lease.physical_base[1],(input.views[1].limit+63)&~63u);
    lease.memory=memory;lease.verify_cancelled(original,input);revoke(sim,agent,bus,memory,lease);
    result.reset_cycles[mode]=bus.cycles-start;
    // Reuse the original command ID only after the reset and its IRQ retire.
    result.healthy_after_reset[mode]=healthy(sim,agent,bus,memory,input,epoch++)[1];
  }
  return result;
}
} // namespace framebuffer_blit

int main(int argc,char **argv) {
  try {
    using namespace framebuffer_blit;
    require(argc>=3,"usage: native_framebuffer_blit_driver NEW.json ORIGINAL_INPUT.blit [...]");
    const std::filesystem::path output=argv[1];require(!std::filesystem::exists(output),"blit output already exists");
    // All packets/oracles are admitted before a model is created.
    std::vector<Packet> inputs;for(int n=2;n<argc;n++)inputs.push_back(Packet::read(argv[n]));
    require(inputs.size()==43,"blit qualification requires all 43 actual-caller inputs");
    Simulation sim(argc,argv);sim.reset();Memory memory;
    native_frame::Agent agent(memory);Bus bus(sim,agent,100000);
#ifdef OPENGPU_FRAMEBUFFER_BLIT_SAMPLE_OWNER
    const auto profile=native_owner::probe_sample_profile(sim,agent,bus);
    require(profile.factory==native_owner::OwnerFactory::geometry_compute_sample &&
      profile.words.size()==112 && profile.words.at(2).value==0x1fffff,
      "blit requires the exact complete 112-word sample owner profile");
#else
    const auto profile=native_owner::probe_profile(sim,agent,bus,true);
    require(profile.words.size()==96 && profile.words.at(2).value==0x3ffff,
      "blit requires the complete 96-word owner profile");
#endif
    require(bus.read(GPU_REG_CAPABILITIES)&GPU_CAP_UNIFIED_RESET,"blit lifecycle requires drain-safe unified reset");
    std::vector<std::array<uint64_t,3>> cycles;
    for(size_t epoch=0;epoch<inputs.size();epoch++) {
      cycles.push_back(healthy(sim,agent,bus,memory,inputs[epoch],unsigned(epoch)));
    }
    const auto recovery=lifecycle(sim,agent,bus,memory,inputs.front(),unsigned(inputs.size()));
    native_owner::atomic_json(output,[&](auto &out) {
#ifdef OPENGPU_FRAMEBUFFER_BLIT_SAMPLE_OWNER
      out<<"{\"schema\":\"opengpu-framebuffer-blit-sample-host-v1\",\"resetCount\":1,\"cases\":[";
#else
      out<<"{\"schema\":\"opengpu-framebuffer-blit-host-v2\",\"resetCount\":1,\"cases\":[";
#endif
      for(size_t n=0;n<cycles.size();n++)out<<(n?",":"")<<"{\"inputIndex\":"<<n
        <<",\"coordinateInvalidateCycles\":"<<cycles[n][0]<<",\"computeThroughRetirementCycles\":"<<cycles[n][1]
        <<",\"publicationInvalidateCycles\":"<<cycles[n][2]<<"}";
      out<<"],\"lifecycle\":{\"sourceFaultReads\":"<<recovery.fault_reads
        <<",\"sourceFaultRetirementCycles\":"<<recovery.fault_cycles
        <<",\"healthyAfterFaultCycles\":"<<recovery.healthy_after_fault<<",\"resets\":[";
      for(unsigned n=0;n<2;n++)out<<(n?",":"")<<"{\"heldChannel\":\""<<(n?"B":"R")
        <<"\",\"heldResponseCycles\":"<<recovery.held_cycles[n]<<",\"resetThroughRevocationCycles\":"<<recovery.reset_cycles[n]
        <<",\"healthyIDReuseCycles\":"<<recovery.healthy_after_reset[n]<<"}";
      out<<"]},\"unifiedResetCount\":2,\"partialCancellationBytesChecked\":true,\"originalCallerInputs\":true,\"originalLinuxTrace\":false,\"independentBytes\":true,"
        "\"vmAndReadOnlyGuards\":true,";
#ifdef OPENGPU_FRAMEBUFFER_BLIT_SAMPLE_OWNER
      out<<"\"executorKind\":\"framebuffer-blit-sample\",\"profile\":\"two-cu-vga60-owner-v2-sample-stage\","
        "\"ownerResourceVersion\":2,\"ownerResourceStage\":\"geometry-compute-sample\","
        "\"resourceDiscoveryWords\":112,\"computeFeatureFlags\":2097151,\"full112Profile\":true,"
        "\"fullResourceQualification\":false,\"finalProductQualification\":false,";
#else
      out<<"\"full96Profile\":true,";
#endif
      out<<"\"sourceQualificationRequired\":true,"
        "\"clockQualified\":false,\"fpsQualified\":false}\n";
    });sim.model->final();return 0;
  }catch(const std::exception &error){std::cerr<<"framebuffer blit host failed: "<<error.what()<<'\n';return 1;}
}
