#pragma once
// Continuous same-GEM host-visible DMA publication on one float DUT/RAM. Later
// phases keep producer-written data/flag bytes by retaining exact physical
// extents. After the producer fence/cleanup path the DUT memory dump proves
// host visibility of retained GEM words; the reload GPU consumer models a
// host-mapped view of the same GEM and supplies fabric reload AR.
#include "compute_dma_publication_cache.hpp"
namespace compute_owner {
using DmaPublicationExtent=std::pair<uint64_t,uint64_t>;

inline std::set<DmaPublicationExtent> read_dma_publication_retained_extents(
    const std::filesystem::path &path) {
  std::ifstream input(path,std::ios::binary);require(bool(input),"missing retained dma publication extents");
  std::set<DmaPublicationExtent> retained;
  while(input.peek()!=EOF) {
    const auto start=read_le(input,8),end=read_le(input,8);
    require(start<end && !((start|end)&4095) && end<=Memory::limit,"invalid retained dma publication extent");
    require(retained.emplace(start,end).second,"duplicate retained dma publication extent");
  }
  require(!retained.empty(),"dma publication retained no original SSBO extents");
  return retained;
}

inline void append_dma_publication_retained(Memory &live,const Memory &next,
    const std::set<DmaPublicationExtent> &retained) {
  require(&live!=&next && !next.ranges.empty(),"empty or aliased retained dma publication upload");
  Memory additions;std::set<uint64_t> new_pages;std::set<DmaPublicationExtent> replaced;
  uint64_t previous=0;
  for(const auto &r:next.ranges) {
    require(previous<=r.start && r.start<r.end && r.end<=Memory::limit &&
      !((r.start|r.end)&4095),"invalid retained dma publication upload allocation");previous=r.end;
    const Memory::Range *overlap=nullptr;
    for(const auto &old:live.ranges)if(r.start<old.end && old.start<r.end) {
      require(!overlap,"retained dma publication upload crosses old allocation boundaries");overlap=&old;
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
    require(replaced.emplace(r.start,r.end).second,"duplicate replaced dma publication allocation");
    for(uint64_t base=r.start;base<r.end;base+=4096)live.pages.erase(base);
    additions.ranges.push_back(r);
    for(uint64_t base=r.start;base<r.end;base+=4096)new_pages.insert(base);
  }
  for(const auto &page:next.pages) {
    require(!(page.first&4095) && std::any_of(next.ranges.begin(),next.ranges.end(),[&](const auto &r) {
      return page.first>=r.start && page.first+4096<=r.end;
    }),"retained dma publication upload has an orphan page");
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

inline void active_dma_publication(int argc,char **argv,const std::filesystem::path &output,
    const std::filesystem::path &session,std::chrono::milliseconds timeout) {
#ifndef OPENGPU_NATIVE_CACHE_STATE
  (void)argc;(void)argv;(void)output;(void)session;(void)timeout;
  require_dma_publication_cache_support();
#else
  require_dma_publication_cache_support();
  constexpr unsigned rounds=3;constexpr uint64_t base=0x01000000;
  require(compute_owner_factory()==OwnerFactory::image_float_stage,
    "dma publication requires the complete float factory");
  require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session) &&
    !std::filesystem::exists(output),"invalid dma publication session/output");
  const auto plan_path=session/"dma-publication.plan";
  wait_input(plan_path,session,timeout);
  std::ifstream plan(plan_path,std::ios::binary);magic(plan,"OGCDPM01");
  require(read_le(plan,4)==rounds && read_le(plan,4)==1u && read_le(plan,8)==base && plan.peek()==EOF,
    "foreign dma publication matrix");
  wait_input(session/"publication.cache-target",session,timeout);
  std::ifstream target_file(session/"publication.cache-target",std::ios::binary);magic(target_file,"OGCDMT01");
  const auto data_line=read_le(target_file,8);
  require(target_file.peek()==EOF && data_line && !(data_line&63) && data_line>=base && data_line+64<=base+0x01000000,
    "foreign publication cache target line");
  const auto first=session/"round-0";authorize_recovery_round(first,0,timeout);
  wait_input(first/"union.memory",first,timeout);
  Memory memory=Memory::read((first/"union.memory").string());
  require(std::filesystem::create_directory(output),"cannot create dma publication output");
  using View=compute_cache_alias::View<VGpuHostSystemAxi,VGpuComputeUnit>;
  using CacheSimulation=compute_cache_alias::ObservedSimulation<Simulation,View,DmaPublicationCacheTracker>;
  CacheSimulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<CacheSimulation> bus(sim,agent,100000);
  DmaPublicationCacheTracker tracker;tracker.target=uint32_t(data_line);sim.attach(tracker);
  uint32_t command_id=0;Traffic previous{};std::set<DmaPublicationExtent> retained;
  const auto session_start=bus.cycles;
  for(unsigned index=0;index<rounds;index++) {
    const auto local=session/("round-"+std::to_string(index));
    if(index) {
      authorize_recovery_round(local,index,timeout);wait_input(local/"union.memory",local,timeout);
      require(agent.drained(),"new publication upload preceded prior AXI drain");
      wait_input(local/"retained.extents",local,timeout);
      retained=read_dma_publication_retained_extents(local/"retained.extents");
      append_dma_publication_retained(memory,Memory::read((local/"union.memory").string()),retained);
    }
    wait_input(local/"record-0.capture",local,timeout);
    const auto capture=ComputeCapture::read((local/"record-0.capture").string());
    if(index)require(capture.command_id==command_id,"dma publication changed its original command ID");
    else command_id=capture.command_id;
    const auto destination=output/("round-"+std::to_string(index));
    require(std::filesystem::create_directory(destination),"dma publication output exists");
    tracker.begin_phase(index);
    const auto start=bus.cycles;
    active_compute(sim,agent,bus,memory,capture,destination,local,timeout,
      ImageFailure::none,0,index?&previous:nullptr);
    const auto completed=agent.traffic;
    require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY &&
      bus.read(GPU_REG_IRQ)==GPU_IRQ_ENABLE && !sim.model->io_m_irq && agent.drained() &&
      !bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP),
      "dma publication retained its original contexts, IRQ or traffic");
    traffic_unchanged(completed,agent.traffic);
    memory.write((destination/"record-0.cleaned.memory").string());
    if(index==1) {
      require(retained.size()==1,"dma publication host visibility requires the retained SSBO extent");
      const auto target=retained.begin()->first;
      // Host-visible GEM bytes come from the pre-cleanup DUT memory dump.
      const Memory published=Memory::read((destination/"record-0.memory").string());
      uint32_t data=0,flag=0;
      for(unsigned n=0;n<4;n++) {
        data|=uint32_t(published.byte(target+4+n))<<(8*n);
        flag|=uint32_t(published.byte(target+68+n))<<(8*n);
      }
      tracker.note_host_visible(data,flag);
    }
    const char *role=index==0?"warm":index==1?"producer":"reload";
    atomic_json(local/"executor.done",[&](auto &out) {
      out<<"{\"schema\":\"opengpu-compute-dma-publication-round-v1\",\"phase\":"<<index
        <<",\"role\":\""<<role<<"\",\"commandId\":"<<command_id
        <<",\"resetCount\":1,\"ramReplaced\":false,\"externalDrained\":true"
          ",\"sourceQualificationRequired\":true,\"independentBytesRequired\":true"
        <<",\"startCycle\":"<<start<<",\"endCycle\":"<<bus.cycles<<"}\n";
    });
    previous=agent.traffic;
  }
  atomic_json(session/"publication.cache-observation.json",[&](auto &out) {
    tracker.json(out,command_id,session_start,bus.cycles);
  });
  sim.detach();
  sim.model->final();
#endif
}
} // namespace compute_owner
