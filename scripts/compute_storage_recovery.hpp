#pragma once
// Included after the ordinary compute owner; all execution/cleanup paths stay
// shared. Only the controlled physical response errors and lifetime are new.
namespace compute_owner {
inline void active_storage_recovery_matrix(int argc,char **argv,const std::filesystem::path &output,
    const std::filesystem::path &session,std::chrono::milliseconds timeout) {
  constexpr unsigned rounds=60;
  constexpr uint64_t base=0x01000000,step=0x01000000;
  require(compute_owner_factory()==OwnerFactory::image_float_stage,
    "storage recovery requires the complete float factory");
  require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session) &&
    !std::filesystem::exists(output),"invalid storage recovery session/output");
  wait_input(session/"storage-recovery.plan",session,timeout);
  std::ifstream plan(session/"storage-recovery.plan",std::ios::binary);magic(plan,"OGCSRM01");
  require(read_le(plan,4)==rounds && read_le(plan,4)==4 && read_le(plan,8)==base &&
    read_le(plan,8)==step && plan.peek()==EOF,"foreign storage recovery matrix");
  const auto first=session/"round-0";authorize_recovery_round(first,0,timeout);
  wait_input(first/"union.memory",first,timeout);
  Memory memory=Memory::read((first/"union.memory").string());
  auto check_namespace=[&](const Memory &upload,unsigned index) {
    const auto start=base+index*step;
    require(!upload.ranges.empty() && upload.ranges.front().start==start &&
      std::all_of(upload.ranges.begin(),upload.ranges.end(),[&](const auto &r) {
        return start<=r.start && r.start<r.end && r.end<=start+step;
      }),"original storage upload lost its disjoint allocator namespace");
  };
  check_namespace(memory,0);
  require(std::filesystem::create_directory(output),"cannot create storage recovery output");
  Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
  Traffic previous{};uint64_t prior_reads=0,prior_writes=0;uint32_t command_id=0;
  for(unsigned index=0;index<rounds;index++) {
    const auto local=session/("round-"+std::to_string(index));
    if(index) {
      authorize_recovery_round(local,index,timeout);wait_input(local/"union.memory",local,timeout);
      require(agent.drained(),"new storage upload preceded prior AXI drain");
      const auto upload=Memory::read((local/"union.memory").string());check_namespace(upload,index);
      memory.append_disjoint(upload);
    }
    wait_input(local/"record-0.capture",local,timeout);
    const auto capture=ComputeCapture::read((local/"record-0.capture").string());
    if(index)require(capture.command_id==command_id,"storage recovery changed its original command ID");
    else command_id=capture.command_id;
    const auto destination=output/("round-"+std::to_string(index));
    require(std::filesystem::create_directory(destination),"storage recovery output exists");
    const auto start=bus.cycles;
    const unsigned kind=index%4==0?1:index%4==2?2:0;
    if(kind) {
      wait_input(local/"record-0.fault-plan",local,timeout);
      std::ifstream fault(local/"record-0.fault-plan",std::ios::binary);magic(fault,"OGCSF001");
      const auto begin=read_le(fault,8),end=read_le(fault,8);
      require(read_le(fault,4)==kind && fault.peek()==EOF && begin && begin<Memory::limit &&
        !(begin&63) && end==begin+64,"foreign storage physical fault plan");
      if(kind==1)agent.arm_read_faults(begin,end);
      else agent.arm_first_write_fault(begin,end);
    }
    active_compute(sim,agent,bus,memory,capture,destination,local,timeout,
      kind==1?ImageFailure::source_read:kind==2?ImageFailure::first_write:ImageFailure::none,
      prior_reads,index?&previous:nullptr);
    if(kind==1)agent.disarm_read_faults();
    if(kind==2)agent.disarm_first_write_fault();
    const auto completed=agent.traffic;
    require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY &&
      bus.read(GPU_REG_IRQ)==GPU_IRQ_ENABLE && !sim.model->io_m_irq && agent.drained() &&
      !bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP),
      "storage recovery retained its original contexts, IRQ or traffic");
    traffic_unchanged(completed,agent.traffic);
    const auto reads=agent.injected_read_faults(),writes=agent.injected_write_faults();
    require(reads==agent.traffic.read_faults && writes==agent.traffic.write_faults &&
      (kind==1?reads>prior_reads:reads==prior_reads) && writes==prior_writes+(kind==2?1:0),
      "storage recovery lost prior error history or incurred an unplanned response fault");
    memory.write((destination/"record-0.cleaned.memory").string());
    atomic_json(local/"executor.done",[&](auto &out) {
      out<<"{\"schema\":\"opengpu-compute-storage-recovery-round-v1\",\"phase\":"<<index
        <<",\"mode\":\""<<(kind==1?"read":kind==2?"write":"healthy")<<"\",\"commandId\":"<<command_id
        <<",\"resetCount\":1,\"ramReplaced\":false,\"externalDrained\":true"
          ",\"sourceQualificationRequired\":true,\"independentBytesRequired\":true"
        <<",\"physicalReadFaults\":"<<agent.traffic.read_faults<<",\"injectedReadFaults\":"<<reads
        <<",\"physicalWriteFaults\":"<<agent.traffic.write_faults<<",\"injectedWriteFaults\":"<<writes
        <<",\"startCycle\":"<<start<<",\"endCycle\":"<<bus.cycles<<"}\n";
    });
    prior_reads=reads;prior_writes=writes;previous=agent.traffic;
  }
  sim.model->final();
}
} // namespace compute_owner
