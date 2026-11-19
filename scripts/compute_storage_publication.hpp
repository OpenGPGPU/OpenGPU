#pragma once
// Continuous same-GEM SSBO publication on one float DUT/RAM. Later phases keep
// producer-written data/flag bytes by retaining exact physical extents. CPU
// re-exports of those extents are not authoritative after the first warm seed.
namespace compute_owner {
using PublicationExtent=std::pair<uint64_t,uint64_t>;

inline std::set<PublicationExtent> read_retained_extents(const std::filesystem::path &path) {
  std::ifstream input(path,std::ios::binary);require(bool(input),"missing retained publication extents");
  std::set<PublicationExtent> retained;
  while(input.peek()!=EOF) {
    const auto start=read_le(input,8),end=read_le(input,8);
    require(start<end && !((start|end)&4095) && end<=Memory::limit,"invalid retained publication extent");
    require(retained.emplace(start,end).second,"duplicate retained publication extent");
  }
  require(!retained.empty(),"publication retained no original SSBO extents");
  return retained;
}

inline void append_publication_retained(Memory &live,const Memory &next,
    const std::set<PublicationExtent> &retained) {
  require(&live!=&next && !next.ranges.empty(),"empty or aliased retained publication upload");
  Memory additions;std::set<uint64_t> new_pages;std::set<PublicationExtent> replaced;
  uint64_t previous=0;
  for(const auto &r:next.ranges) {
    require(previous<=r.start && r.start<r.end && r.end<=Memory::limit &&
      !((r.start|r.end)&4095),"invalid retained publication upload allocation");previous=r.end;
    const Memory::Range *overlap=nullptr;
    for(const auto &old:live.ranges)if(r.start<old.end && old.start<r.end) {
      require(!overlap,"retained publication upload crosses old allocation boundaries");overlap=&old;
    }
    if(!overlap) {
      additions.ranges.push_back(r);
      for(uint64_t base=r.start;base<r.end;base+=4096)new_pages.insert(base);
      continue;
    }
    require(r.start==overlap->start && r.end==overlap->end && r.fill==overlap->fill,
      "upload overlaps a mismatched live allocation");
    if(retained.count({r.start,r.end})) {
      // Keep measured DUT bytes. A later Mesa launch may re-seed the same PA.
      continue;
    }
    require(replaced.emplace(r.start,r.end).second,"duplicate replaced publication allocation");
    for(uint64_t base=r.start;base<r.end;base+=4096)live.pages.erase(base);
    additions.ranges.push_back(r);
    for(uint64_t base=r.start;base<r.end;base+=4096)new_pages.insert(base);
  }
  for(const auto &page:next.pages) {
    require(!(page.first&4095) && std::any_of(next.ranges.begin(),next.ranges.end(),[&](const auto &r) {
      return page.first>=r.start && page.first+4096<=r.end;
    }),"retained publication upload has an orphan page");
    if(new_pages.count(page.first))additions.pages.emplace(page);
  }
  if(!replaced.empty()) {
    Memory kept;
    for(const auto &r:live.ranges)if(!replaced.count({r.start,r.end}))kept.ranges.push_back(r);
    for(const auto &page:live.pages)if(std::any_of(kept.ranges.begin(),kept.ranges.end(),[&](const auto &r) {
      return page.first>=r.start && page.first+4096<=r.end;
    }))kept.pages.emplace(page);
    live.ranges.swap(kept.ranges);live.pages.swap(kept.pages);
  }
  if(!additions.ranges.empty())live.append_disjoint(additions);
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
  uint32_t command_id=0;std::set<PublicationExtent> retained;
  for(unsigned index=0;index<rounds;index++) {
    const auto local=session/("round-"+std::to_string(index));
    if(index) {
      authorize_recovery_round(local,index,timeout);wait_input(local/"union.memory",local,timeout);
      require(agent.drained(),"new publication upload preceded prior AXI drain");
      wait_input(local/"retained.extents",local,timeout);
      retained=read_retained_extents(local/"retained.extents");
      append_publication_retained(memory,Memory::read((local/"union.memory").string()),retained);
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
