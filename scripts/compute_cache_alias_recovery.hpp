#pragma once
namespace compute_owner {
inline void active_cache_alias_recovery_matrix(int argc,char **argv,const std::filesystem::path &output,
    const std::filesystem::path &session,std::chrono::milliseconds timeout,bool uc_alias=false) {
#ifndef OPENGPU_NATIVE_CACHE_STATE
  (void)argc;(void)argv;(void)output;(void)session;(void)timeout;(void)uc_alias;
  require(false,"cache alias recovery requires bound complete-float Verilator cache headers");
#else
  require(compute_owner_factory()==OwnerFactory::image_float_stage,"cache alias recovery requires the complete float factory");
  require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session) &&
    !std::filesystem::exists(output),"invalid cache alias recovery session/output");
  constexpr unsigned rounds=48;
  constexpr uint64_t base=0x01000000,step=0x01000000;
  const auto plan_name=uc_alias?"uc-cache-alias-recovery.plan":"cache-alias-recovery.plan";
  wait_input(session/plan_name,session,timeout);
  std::ifstream plan(session/plan_name,std::ios::binary);magic(plan,uc_alias?"OGCUAM01":"OGCCAM01");
  require(read_le(plan,4)==rounds && read_le(plan,4)==2 && read_le(plan,8)==base &&
    read_le(plan,8)==step && plan.peek()==EOF,"foreign cache alias recovery matrix");
  auto check_namespace=[&](const Memory &upload,unsigned index) {
    const auto start=base+index*step;
    require(!upload.ranges.empty() && upload.ranges.front().start==start &&
      std::all_of(upload.ranges.begin(),upload.ranges.end(),[&](const auto &r) {
        return start<=r.start && r.start<r.end && r.end<=start+step;
      }),"cache alias upload lost the original disjoint physical allocator");
  };
  const auto first=session/"round-0";authorize_recovery_round(first,0,timeout);
  wait_input(first/"union.memory",first,timeout);
  Memory memory=Memory::read((first/"union.memory").string());check_namespace(memory,0);
  require(std::filesystem::create_directory(output),"cannot create cache alias recovery output");
  using View=compute_cache_alias::View<VGpuHostSystemAxi,VGpuComputeUnit>;
  using CacheSimulation=compute_cache_alias::ObservedSimulation<Simulation,View>;
  CacheSimulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<CacheSimulation> bus(sim,agent,100000);
  Traffic previous{};uint64_t prior_writes=0;uint32_t command_id=0;
  for(unsigned index=0;index<rounds;++index) {
    const auto local=session/("round-"+std::to_string(index));
    if(index) {
      authorize_recovery_round(local,index,timeout);wait_input(local/"union.memory",local,timeout);
      require(agent.drained(),"cache alias upload preceded prior AXI drain");
      const auto upload=Memory::read((local/"union.memory").string());check_namespace(upload,index);
      memory.append_disjoint(upload);
    }
    wait_input(local/"record-0.capture",local,timeout);
    const auto capture=ComputeCapture::read((local/"record-0.capture").string());
    if(index)require(capture.command_id==command_id,"cache alias recovery changed its original command ID");
    else command_id=capture.command_id;
    wait_input(local/"record-0.cache-target",local,timeout);
    std::ifstream target(local/"record-0.cache-target",std::ios::binary);magic(target,uc_alias?"OGCUL001":"OGCCL001");
    require(read_le(target,4)==index && read_le(target,4)==0,"foreign cache target phase");
    const auto line=read_le(target,8),start=bus.cycles;const bool failed=index%2==0;
    require(target.peek()==EOF && !(line&63) && line>=base+index*step && line+64<=base+(index+1)*step &&
      capture.writable.size()==1 && capture.writable[0].end-capture.writable[0].first==8192 &&
      capture.writable[0].first<=line && line+64<=capture.writable[0].end,
      "cache target escaped its original writable kernel argument pages");
    if(failed) {
      wait_input(local/"record-0.fault-plan",local,timeout);
      std::ifstream fault(local/"record-0.fault-plan",std::ios::binary);magic(fault,uc_alias?"OGCUF001":"OGCCF001");
      require(read_le(fault,8)==line && read_le(fault,8)==line+64 && read_le(fault,4)==3 && fault.peek()==EOF,
        "cache write fault differs from the observed target line");
      agent.arm_accepted_write_error(line,line+64);
    }
    const auto destination=output/("round-"+std::to_string(index));
    require(std::filesystem::create_directory(destination),"cache alias recovery output exists");
    compute_cache_alias::Tracker tracker(line,failed);sim.attach(tracker);
    active_compute(sim,agent,bus,memory,capture,destination,local,timeout,
      failed?(uc_alias?ImageFailure::uc_cache_alias_accepted_write:ImageFailure::global_accepted_write):
        ImageFailure::none,0,index?&previous:nullptr,nullptr,
      [&](const ComputeResult &result) {
        atomic_json(local/"record-0.cache-observation.json",[&](auto &out) {
          tracker.json(out,command_id,index,failed?result.fault_target_write_attempts:8,start,bus.cycles);
        });
        sim.detach();
      });
    if(failed)agent.disarm_first_write_fault();
    const auto completed=agent.traffic;
    require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && bus.read(GPU_REG_IRQ)==GPU_IRQ_ENABLE &&
      !sim.model->io_m_irq && agent.drained() && !bus.read(GPU_REG_UCMD_VECTOR_SATP) &&
      !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP),"cache alias recovery retained original contexts, IRQ or traffic");
    traffic_unchanged(completed,agent.traffic);
    const auto writes=agent.injected_write_faults();
    require(!agent.injected_read_faults() && !agent.traffic.read_faults && writes==agent.traffic.write_faults &&
      writes==prior_writes+(failed?1:0) && agent.accepted_write_faults()==writes,
      "cache alias recovery changed its committed-write error history");
    memory.write((destination/"record-0.cleaned.memory").string());
    atomic_json(local/"executor.done",[&](auto &out) {
      out<<"{\"schema\":\""<<(uc_alias?"opengpu-compute-uc-cache-alias-recovery-round-v1":
          "opengpu-compute-cache-alias-recovery-round-v1")<<"\",\"phase\":"<<index
        <<",\"mode\":\""<<(failed?"accepted-write":"healthy")<<"\",\"commandId\":"<<command_id
        <<",\"resetCount\":1,\"ramReplaced\":false,\"externalDrained\":true,\"sourceQualificationRequired\":true,\"independentBytesRequired\":true"
        <<",\"physicalReadFaults\":0,\"injectedReadFaults\":0,\"physicalWriteFaults\":"<<writes
        <<",\"injectedWriteFaults\":"<<writes<<",\"acceptedWriteFaults\":"<<agent.accepted_write_faults()
        <<",\"startCycle\":"<<start<<",\"endCycle\":"<<bus.cycles<<"}\n";
    });
    previous=agent.traffic;prior_writes=writes;
  }
  sim.model->final();
#endif
}
} // namespace compute_owner
