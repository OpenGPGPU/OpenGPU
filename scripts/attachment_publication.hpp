#pragma once
// Continuous warm→producer→reload color-attachment publication on one float
// framebuffer-blit-sample DUT/RAM/reset. Retains the producer destination GEM
// (warm/reload source) at a fixed physical base.
#include "native_framebuffer_blit_lease.hpp"
#include "native_framebuffer_blit_simulation.hpp"
#include <fstream>
#include <map>
#include <set>

// Included inside namespace framebuffer_blit after Bus/submit/complete/invalidate.
constexpr uint64_t ATTACHMENT_PUBLICATION_BASE=0x01000000ull;

struct AttachmentPublicationTracker {
  uint64_t target=0,last=0,warm_read=0,warm_response=0,write_cycle=0,reload_read=0,reload_response=0;
  unsigned phase=0;bool seen=false,writing=false;
  void begin_phase(unsigned index){phase=index;}
  void on_ar(uint64_t cycle,uint64_t address) {
    require(!seen || cycle>=last,"attachment publication fabric clock went backwards");
    seen=true;last=cycle;
    const uint64_t line=address&~uint64_t(63);
    if(phase==0 && line==target && !warm_read)warm_read=cycle;
    if(phase==2 && writing && line==target && !reload_read)reload_read=cycle;
  }
  void on_r(uint64_t cycle) {
    if(phase==0 && warm_read && !warm_response)warm_response=cycle;
    if(phase==2 && reload_read && !reload_response)reload_response=cycle;
  }
  void on_aw(uint64_t cycle,uint64_t address) {
    require(!seen || cycle>=last,"attachment publication fabric clock went backwards");
    seen=true;last=cycle;
    if(phase==1 && warm_response && (address&~uint64_t(63))==target && !writing) {
      writing=true;write_cycle=cycle;
    }
  }
  void finish(uint64_t start_cycle,uint64_t end_cycle) const {
    require(start_cycle<warm_read && warm_read<=warm_response,
      "attachment publication warm phase never read the retained attachment line");
    require(warm_response<write_cycle && writing,
      "attachment publication producer never wrote the retained attachment line");
    require(write_cycle<reload_read && reload_read<=reload_response && reload_response<=end_cycle,
      "attachment publication reload never re-read the retained attachment line");
  }
  void json(std::ostream &out,uint32_t command_id,uint64_t start_cycle,uint64_t end_cycle) const {
    finish(start_cycle,end_cycle);
    out<<"{\"schema\":\"opengpu-attachment-publication-fabric-observation-v1\",\"commandId\":"<<command_id
      <<",\"startCycle\":"<<start_cycle<<",\"endCycle\":"<<end_cycle
      <<",\"targetLine\":"<<target
      <<",\"warmReadRequestCycle\":"<<warm_read<<",\"warmReadResponseCycle\":"<<warm_response
      <<",\"firstTargetWriteCycle\":"<<write_cycle
      <<",\"reloadReadRequestCycle\":"<<reload_read<<",\"reloadReadResponseCycle\":"<<reload_response
      <<",\"uncachedAttachmentPath\":true,\"ordinaryL1AliasRequired\":false}\n";
  }
};

// Like Lease, but the retained attachment handle is pinned at BASE.
template<class Memory>
struct PublicationLease {
  static constexpr uint32_t pc=0x01000000,kernarg=pc+0x2000,table=pc+0x3000;
  uint32_t instruction_root,vector_root,code_physical,kernarg_physical,table_physical,asid;
  Memory memory;
  std::array<uint32_t,3> virtual_base{0x10000000,0x20000000,0x30000000};
  std::array<uint32_t,3> physical_base{};
  std::set<uint32_t> pte_pages;
  std::map<std::pair<uint32_t,uint32_t>,uint32_t> levels;
  uint32_t attachment_handle=0;

  static void put(Memory &m,uint32_t a,uint32_t value) {
    for(unsigned b=0;b<4;b++)m.byte(uint64_t(a)+b,uint8_t(value>>(8*b)));
  }
  static uint32_t get(const Memory &m,uint32_t a) {
    uint32_t value=0;for(unsigned b=0;b<4;b++)value|=uint32_t(m.byte(uint64_t(a)+b))<<(8*b);return value;
  }
  void allocation(uint32_t base,uint32_t bytes,uint8_t fill=0xa5) {
    require(!(base&4095) && bytes && !(bytes&4095),"unaligned fixture allocation");
    const uint64_t start=base-64,end=uint64_t(base)+bytes+64;
    for(const auto &r:memory.ranges)require(end<=r.start || start>=r.end,"fixture physical allocations overlap");
    memory.ranges.push_back({start,end,fill});
  }
  void page(uint32_t base) {
    require(!(base&4095) && pte_pages.insert(base).second,"duplicate fixture PTE page");
    for(unsigned b=0;b<4096;b++)memory.byte(uint64_t(base)+b,0);
  }
  void map_page(uint32_t root,uint32_t va,uint32_t pa,uint32_t flags) {
    require(!(va&4095) && !(pa&4095) && (flags&1) && !(flags&0x20),"invalid fixture PTE");
    const auto key=std::make_pair(root,va>>22);
    auto at=levels.find(key);
    if(at==levels.end()) {
      unsigned count=0;for(const auto &entry:levels)count+=entry.first.first==root;
      require(count<15,"fixture page-table arena exceeded");
      const auto level=root+4096*(count+1);page(level);at=levels.emplace(key,level).first;
      put(memory,root+4*(va>>22),((level>>12)<<10)|1);
    }
    const auto pte=at->second+4*((va>>12)&1023);
    require(!get(memory,pte),"fixture virtual pages overlap");put(memory,pte,((pa>>12)<<10)|flags);
  }
  PublicationLease(const Packet &input,unsigned epoch,uint32_t attachment):asid(input.context),attachment_handle(attachment) {
    require(epoch<64 && attachment,"invalid attachment publication lease");
    instruction_root=0x10000+epoch*0x40000;vector_root=instruction_root+0x10000;
    code_physical=0x100000+epoch*0x200000;kernarg_physical=code_physical+0x2000;table_physical=code_physical+0x4000;
    memory.ranges.push_back({instruction_root-64,vector_root+65536+64,0});
    page(instruction_root);page(vector_root);
    allocation(code_physical,4096,0);allocation(kernarg_physical,4096,0);allocation(table_physical,4096,0);
    for(unsigned n=0;n<input.shader.size();n++)put(memory,code_physical+4*n,input.shader[n]);
    for(size_t n=0;n<input.kernarg.size();n++)memory.byte(kernarg_physical+n,input.kernarg[n]);
    map_page(instruction_root,pc,code_physical,0x4b);
    map_page(vector_root,kernarg,kernarg_physical,0x43);map_page(vector_root,table,table_physical,0x43);
    std::map<uint32_t,uint32_t> handles;
    const std::array<const std::vector<uint8_t>*,3> bytes{&input.source,&input.destination,&input.coordinates};
    for(unsigned i=0;i<3;i++) {
      const auto &view=input.views[i];auto at=handles.find(view.handle);
      if(at==handles.end()) {
        const uint32_t physical=view.handle==attachment_handle?uint32_t(ATTACHMENT_PUBLICATION_BASE):
          (0x12000000+epoch*0x1000000+uint32_t(handles.size())*0x20000);
        const auto rounded=(view.allocation+4095)&~4095u;allocation(physical,rounded);
        for(size_t n=0;n<bytes[i]->size();n++)memory.byte(uint64_t(physical)+n,bytes[i]->at(n));
        at=handles.emplace(view.handle,physical).first;
      }
      physical_base[i]=at->second;
      for(uint32_t n=0;n<view.allocation;n+=4096)
        map_page(vector_root,virtual_base[i]+n,physical_base[i]+n,view.access&2?0xc7:0x43);
      const uint32_t limit=virtual_base[i]+view.limit;
      const uint32_t record[16]={0x47534201,64,1,input.context,7,1,i,1,
        uint32_t(bool(view.access&1)),uint32_t(bool(view.access&2)),virtual_base[i],limit,0,
        virtual_base[i],(limit+63)&~63u,0};
      for(unsigned n=0;n<16;n++)put(memory,table_physical+i*64+n*4,record[n]);
    }
    std::sort(memory.ranges.begin(),memory.ranges.end(),[](auto a,auto b){return a.start<b.start;});
  }
  uint32_t instruction_satp() const {return 0x80000000u|(asid<<22)|(instruction_root>>12);}
  uint32_t vector_satp() const {return 0x80000000u|(asid<<22)|(vector_root>>12);}
};

inline void merge_retained_attachment(native_frame::Memory &live,const native_frame::Memory &upload,
    uint64_t attach_start,uint64_t attach_end) {
  require(attach_start<attach_end && !((attach_start|attach_end)&4095),"invalid retained attachment");
  native_frame::Memory next=upload;
  // Restore measured attachment bytes from live into the new upload image.
  for(uint64_t a=attach_start;a<attach_end;a++)next.byte(a,live.byte(a));
  live=next;
}

inline uint64_t publication_bind(Simulation &sim,native_frame::Agent &agent,Bus &bus,
    const PublicationLease<native_frame::Memory> &lease,const Packet &input) {
  bus.begin_record(10000000);bus.write(GPU_REG_IRQ,GPU_IRQ_ENABLE|GPU_IRQ_PENDING);
  const auto before=invalidate(sim,agent,bus,1,lease.physical_base[2],(input.views[2].limit+63)&~63u);
  bus.write(GPU_REG_UCMD_VECTOR_SATP,lease.vector_satp());
  bus.write(GPU_REG_UCMD_INSTRUCTION_SATP,lease.instruction_satp());bus.write(GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_FULL);
  const std::array<std::pair<uint32_t,uint32_t>,7> storage{{
    {OPENGPU_STORAGE_COMMAND_REG_CONTROL,1u|(1u<<OPENGPU_STORAGE_COMMAND_VERSION_SHIFT)},
    {OPENGPU_STORAGE_COMMAND_REG_TABLE_BASE,PublicationLease<native_frame::Memory>::table},
    {OPENGPU_STORAGE_COMMAND_REG_TABLE_LIMIT_LO,PublicationLease<native_frame::Memory>::table+256},
    {OPENGPU_STORAGE_COMMAND_REG_TABLE_LIMIT_HI,0},{OPENGPU_STORAGE_COMMAND_REG_CONTEXT,input.context},
    {OPENGPU_STORAGE_COMMAND_REG_PASS,7},{OPENGPU_STORAGE_COMMAND_REG_GENERATION,1}}};
  for(const auto &word:storage)bus.write(word.first,word.second);
  return before.cycles;
}

inline void publication_revoke(Simulation &sim,native_frame::Agent &agent,Bus &bus,
    native_frame::Memory &memory,const PublicationLease<native_frame::Memory> &lease) {
  require(sim.drained(agent) && !sim.model->io_m_irq,"attachment revoke before drain");
  bus.write(OPENGPU_STORAGE_COMMAND_REG_CONTROL,0);
  for(const auto &level:lease.levels)for(unsigned n=0;n<1024;n++)
    PublicationLease<native_frame::Memory>::put(memory,level.second+n*4,0);
  bus.write(GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_FULL);
  while(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_VM_BUSY){}
  bus.write(GPU_REG_UCMD_VECTOR_SATP,0);bus.write(GPU_REG_UCMD_INSTRUCTION_SATP,0);
  require(!bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP) &&
    bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && sim.drained(agent) && !sim.model->io_m_irq,
    "attachment publication context teardown did not become idle/bare");
}

inline void active_attachment_publication(int argc,char **argv) {
  require(argc==4,"usage: executor --active-attachment-publication OUTPUT SESSION");
#ifndef OPENGPU_FRAMEBUFFER_BLIT_SAMPLE_OWNER
  require(false,"attachment publication requires framebuffer-blit-sample");
#else
  const std::filesystem::path output=argv[2],session=argv[3];
  require(std::filesystem::is_directory(session) && !std::filesystem::exists(output),
    "invalid attachment publication session/output");
  std::ifstream plan((session/"attachment-publication.plan").string(),std::ios::binary);
  require(bool(plan),"missing attachment publication plan");
  for(unsigned n=0;n<8;n++)require(plan.get()=="OGAPPM01"[n],"foreign attachment publication plan");
  require(read_word(plan)==3 && read_word(plan)==1,"foreign attachment publication rounds");
  uint64_t base=0;for(unsigned b=0;b<8;b++){auto byte=plan.get();require(byte!=EOF,"short plan");base|=uint64_t(uint8_t(byte))<<(8*b);}
  require(base==ATTACHMENT_PUBLICATION_BASE && plan.peek()==EOF,"attachment publication base differs");
  std::ifstream target_file((session/"publication.cache-target").string(),std::ios::binary);
  require(bool(target_file),"missing attachment publication cache target");
  for(unsigned n=0;n<8;n++)require(target_file.get()=="OGAPPT01"[n],"foreign attachment cache target");
  uint64_t data_line=0;for(unsigned b=0;b<8;b++){auto byte=target_file.get();require(byte!=EOF,"short target");data_line|=uint64_t(uint8_t(byte))<<(8*b);}
  require(target_file.peek()==EOF && data_line && !(data_line&63),"foreign attachment cache target line");

  Packet packets[3];
  for(unsigned i=0;i<3;i++)
    packets[i]=Packet::read((session/("round-"+std::to_string(i)+".blit")).string());
  const uint32_t attachment_handle=packets[1].views[1].handle;
  require(packets[0].views[0].handle==attachment_handle && packets[2].views[0].handle==attachment_handle,
    "warm/reload lost the retained attachment handle");
  require(std::filesystem::create_directory(output),"cannot create attachment publication output");

  Simulation sim(argc,argv);sim.reset();
  native_frame::Memory memory;
  // Seed memory before constructing the agent (reference stays live).
  {
    PublicationLease<native_frame::Memory> seed(packets[0],0,attachment_handle);
    memory=seed.memory;
  }
  native_frame::Agent agent(memory);Bus bus(sim,agent,100000);
  const auto profile=native_owner::probe_sample_profile(sim,agent,bus);
  require(profile.factory==native_owner::sample_owner_factory() && profile.words.size()==112,
    "attachment publication requires the complete float sample owner");
  AttachmentPublicationTracker tracker;tracker.target=data_line;
  const uint32_t command_id=2;const auto session_start=bus.cycles;
  const uint64_t attach_start=ATTACHMENT_PUBLICATION_BASE;
  const uint64_t attach_end=attach_start+((packets[1].views[1].allocation+4095ull)&~4095ull);

  for(unsigned index=0;index<3;index++) {
    PublicationLease<native_frame::Memory> lease(packets[index],index,attachment_handle);
    if(index)merge_retained_attachment(memory,lease.memory,attach_start,attach_end);
    else memory=lease.memory;
    lease.memory=memory;
    tracker.begin_phase(index);
    const auto start=bus.cycles;
    publication_bind(sim,agent,bus,lease,packets[index]);
    const auto submit_start=submit(bus,packets[index],command_id);
    while(!sim.model->io_m_irq) {
      const auto req=sim.requests();
      if(req.arvalid)tracker.on_ar(bus.cycles,req.araddr);
      if(req.awvalid)tracker.on_aw(bus.cycles,req.awaddr);
      bus.step([&](const auto &){});
      if(tracker.phase==0 && tracker.warm_read && !tracker.warm_response)tracker.on_r(bus.cycles);
      if(tracker.phase==2 && tracker.reload_read && !tracker.reload_response)tracker.on_r(bus.cycles);
    }
    complete(sim,agent,bus,command_id,0,0,submit_start);
    invalidate(sim,agent,bus,command_id+1,lease.physical_base[1],(packets[index].views[1].limit+63)&~63u);
    lease.memory=memory;
    {
      const uint64_t start_pa=lease.physical_base[1],end_pa=start_pa+packets[index].destination.size();
      for(uint64_t a=start_pa;a<end_pa;a++)
        require(memory.byte(a)==packets[index].expected.at(a-start_pa),
          "attachment publication destination bytes differ from oracle");
    }
    publication_revoke(sim,agent,bus,memory,lease);
    const auto destination=output/("round-"+std::to_string(index));
    require(std::filesystem::create_directory(destination),"attachment publication output exists");
    memory.write((destination/"record-0.memory").string());
    const char *role=index==0?"warm":index==1?"producer":"reload";
    native_owner::atomic_json(session/("executor.round-"+std::to_string(index)+".json"),[&](auto &out) {
      out<<"{\"schema\":\"opengpu-attachment-publication-round-v1\",\"phase\":"<<index
        <<",\"role\":\""<<role<<"\",\"commandId\":"<<command_id
        <<",\"resetCount\":1,\"ramReplaced\":false,\"externalDrained\":true"
          ",\"sourceQualificationRequired\":true,\"independentBytesRequired\":true"
        <<",\"startCycle\":"<<start<<",\"endCycle\":"<<bus.cycles<<"}\n";
    });
  }
  native_owner::atomic_json(session/"publication.cache-observation.json",[&](auto &out) {
    tracker.json(out,command_id,session_start,bus.cycles);
  });
  sim.model->final();
#endif
}
