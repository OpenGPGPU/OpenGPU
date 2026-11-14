#pragma once
#include "native_framebuffer_blit_input.hpp"
#include <map>
#include <set>

namespace framebuffer_blit {
// A bounded host qualification VM, not a claim to reproduce Linux's original
// DMA addresses. GEM identity, bytes, logical bounds and permissions come from
// the actual caller. The original shader/maps/kernarg are never rewritten.
template<class Memory> struct Lease {
  static constexpr uint32_t pc=0x01000000,kernarg=pc+0x2000,table=pc+0x3000;
  uint32_t instruction_root,vector_root,code_physical,kernarg_physical,table_physical;
  Memory memory;
  std::array<uint32_t,3> virtual_base{0x10000000,0x20000000,0x30000000};
  std::array<uint32_t,3> physical_base{};
  std::set<uint32_t> pte_pages;
  std::map<std::pair<uint32_t,uint32_t>,uint32_t> levels;
  uint32_t asid;

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
    // Adjacent page-table pages share one already allocated 64KiB arena.
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
  explicit Lease(const Packet &input,unsigned epoch=0):asid(input.context) {
    require(epoch<64,"fixture epoch exceeds bounded physical layout");
    instruction_root=0x10000+epoch*0x40000;vector_root=instruction_root+0x10000;
    code_physical=0x100000+epoch*0x200000;kernarg_physical=code_physical+0x2000;table_physical=code_physical+0x4000;
    // Page-table arenas are adjacent, so allocate their guarded envelope once.
    memory.ranges.push_back({instruction_root-64,vector_root+65536+64,0});
    page(instruction_root);page(vector_root);
    allocation(code_physical,4096,0);allocation(kernarg_physical,4096,0);allocation(table_physical,4096,0);
    for(unsigned n=0;n<input.shader.size();n++)put(memory,code_physical+4*n,input.shader[n]);
    for(size_t n=0;n<input.kernarg.size();n++)memory.byte(kernarg_physical+n,input.kernarg[n]);
    map_page(instruction_root,pc,code_physical,0x4b);
    map_page(vector_root,kernarg,kernarg_physical,0x43);map_page(vector_root,table,table_physical,0x43);
    std::map<uint32_t,uint32_t> handles;
    const std::array<const std::vector<uint8_t> *,3> bytes{&input.source,&input.destination,&input.coordinates};
    for(unsigned i=0;i<3;i++) {
      const auto &view=input.views[i];auto at=handles.find(view.handle);
      if(at==handles.end()) {
        const uint32_t physical=0x10000000+epoch*0x1000000+uint32_t(handles.size())*0x20000;
        const auto rounded=(view.allocation+4095)&~4095u;allocation(physical,rounded);
        for(size_t n=0;n<bytes[i]->size();n++)memory.byte(uint64_t(physical)+n,bytes[i]->at(n));
        at=handles.emplace(view.handle,physical).first;
      }
      physical_base[i]=at->second;
      for(uint32_t n=0;n<view.allocation;n+=4096)
        map_page(vector_root,virtual_base[i]+n,physical_base[i]+n,view.access&2?0xc7:0x43);
      const uint32_t limit=virtual_base[i]+view.limit;
      // Exact GSB1 wire fields. CPU checks compare all 16 words against the
      // real kernel encoder; no C producer header is selected by this runtime.
      const uint32_t record[16]={0x47534201,64,1,input.context,7,1,i,1,
        uint32_t(bool(view.access&1)),uint32_t(bool(view.access&2)),virtual_base[i],limit,0,
        virtual_base[i],(limit+63)&~63u,0};
      for(unsigned n=0;n<16;n++)put(memory,table_physical+i*64+n*4,record[n]);
    }
    // Fourth storage binding remains all zero/unbound, as in the actual call.
    std::sort(memory.ranges.begin(),memory.ranges.end(),[](auto a,auto b){return a.start<b.start;});
  }
  uint32_t instruction_satp() const {return 0x80000000u|(asid<<22)|(instruction_root>>12);}
  uint32_t vector_satp() const {return 0x80000000u|(asid<<22)|(vector_root>>12);}

  void verify(const Memory &initial,const Packet &input,bool completed) const {
    require(initial.ranges.size()==memory.ranges.size(),"fixture physical map changed");
    const uint64_t start=physical_base[1],end=start+input.destination.size();
    for(const auto &r:initial.ranges)for(uint64_t a=r.start;a<r.end;a++) {
      const uint8_t expected=completed && a>=start && a<end?input.expected.at(a-start):initial.byte(a);
      require(memory.byte(a)==expected,"GPU blit stored bytes or read-only/page/transport guard differ");
    }
    for(const auto &p:memory.pages)require(std::any_of(initial.ranges.begin(),initial.ranges.end(),[&](auto r){
      return p.first<r.end && p.first+4096>r.start;}),"GPU blit created an out-of-allocation page");
  }
};
} // namespace framebuffer_blit
