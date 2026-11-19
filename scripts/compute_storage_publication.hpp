#pragma once
// Continuous same-GEM SSBO publication on one float DUT/RAM. Later phases use
// append_retained so producer-written data/flag bytes survive into reload.
#include "compute_owner_phase.hpp"
namespace compute_owner {
inline std::set<Extent> read_retained_extents(const std::filesystem::path &path) {
  std::ifstream input(path,std::ios::binary);require(input,"missing retained publication extents");
  std::set<Extent> retained;
  while(input.peek()!=EOF) {
    const auto start=read_le(input,8),end=read_le(input,8);
    require(start<end && !((start|end)&4095) && end<=Memory::limit,"invalid retained publication extent");
    require(retained.emplace(start,end).second,"duplicate retained publication extent");
  }
  require(!retained.empty(),"publication retained no original SSBO extents");
  return retained;
}

inline void active_storage_publication(int argc,char **argv,const std::filesystem::path &output,
    const std::filesystem::path &session,std::chrono::milliseconds timeout) {
  constexpr unsigned rounds=3;constexpr uint64_t base=0x01000000;
  require(compute_owner_factory()==OwnerFactory::image_float_stage,
    "storage publication requires the complete float factory");
  require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session) &&
    !std::filesystem::exists(output),"invalid storage publication session/output");
  const auto plan_path=session/"storage-publication.plan";
  wait_input(plan_path,session,timeout);
  std::ifstream plan(plan_path,std::ios::binary);magic(plan,"OGCSPM01");
  require(read_le(plan,4)==rounds && read_le(plan,4)==1u && read_le(plan,8)==base && plan.peek()==EOF,
    "foreign storage publication matrix");
  const auto first=session/"round-0";authorize_recovery_round(first,0,timeout);
  wait_input(first/"union.memory",first,timeout);
  Memory memory=Memory::read((first/"union.memory").string());
  require(std::filesystem::create_directory(output),"cannot create storage publication output");
  Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
  uint32_t command_id=0;std::set<Extent> retained;
  for(unsigned index=0;index<rounds;index++) {
    const auto local=session/("round-"+std::to_string(index));
    if(index) {
      authorize_recovery_round(local,index,timeout);wait_input(local/"union.memory",local,timeout);
      require(agent.drained(),"new publication upload preceded prior AXI drain");
      wait_input(local/"retained.extents",local,timeout);
      retained=read_retained_extents(local/"retained.extents");
      append_retained(memory,Memory::read((local/"union.memory").string()),retained);
    }
    wait_input(local/"record-0.capture",local,timeout);
    const auto capture=ComputeCapture::read((local/"record-0.capture").string());
    if(index)require(capture.command_id==command_id,"storage publication changed its original command ID");
    else command_id=capture.command_id;
    const auto destination=output/("round-"+std::to_string(index));
    require(std::filesystem::create_directory(destination),"storage publication output exists");
    const auto start=bus.cycles;
    active_compute(sim,agent,bus,memory,capture,destination,local,timeout);
    const auto completed=agent.traffic;
    require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY &&
      bus.read(GPU_REG_IRQ)==GPU_IRQ_ENABLE && !sim.model->io_m_irq && agent.drained() &&
      !bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP),
      "storage publication retained its original contexts, IRQ or traffic");
    traffic_unchanged(completed,agent.traffic);
    memory.write((destination/"record-0.cleaned.memory").string());
    const char *role=index==0?"warm":index==1?"producer":"reload";
    atomic_json(local/"executor.done",[&](auto &out) {
      out<<"{\"schema\":\"opengpu-compute-storage-publication-round-v1\",\"phase\":"<<index
        <<",\"role\":\""<<role<<"\",\"commandId\":"<<command_id
        <<",\"resetCount\":1,\"ramReplaced\":false,\"externalDrained\":true"
          ",\"sourceQualificationRequired\":true,\"independentBytesRequired\":true"
        <<",\"startCycle\":"<<start<<",\"endCycle\":"<<bus.cycles<<"}\n";
    });
  }
  sim.model->final();
}
} // namespace compute_owner
