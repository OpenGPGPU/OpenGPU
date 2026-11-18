// Actual C capture bytes and an independent CPU contract parser. No GPU.
#include "compute_indirect_capture.hpp"
#include <filesystem>
#include <iterator>
#include <sstream>
#include <iostream>

using namespace compute_indirect;
template<class F> static void rejects_at(unsigned line,F action) {
  bool rejected=false;try{action();}catch(const std::runtime_error &){rejected=true;}
  if(!rejected)throw std::runtime_error("malformed indirect input was accepted at line "+std::to_string(line));
}
#define rejects(...) rejects_at(__LINE__,__VA_ARGS__)
static void field(IndirectCapture &c,uint32_t address,uint32_t value) {
  for(auto it=c.trace.rbegin();it!=c.trace.rend();++it)
    if(it->kind==Kind::write && it->address==address){
      require(it->value!=value,"negative test did not change its launch field");it->value=value;return;
    }
  throw std::runtime_error("test launch field missing");
}
static void put(Memory &memory,uint64_t address,uint32_t value) {
  for(unsigned n=0;n<4;n++)memory.byte(address+n,uint8_t(value>>(8*n)));
}
static uint64_t leaf_address(const Memory &m,const IndirectCapture &c,uint32_t va) {
  const auto fields=c.staged();const auto satp=fields.at(GPU_REG_UCMD_VECTOR_SATP);
  const uint64_t root=uint64_t(satp&0x3fffffu)<<12;
  const auto link=PtePages::word(m,root+4*uint64_t(va>>22));
  return (uint64_t(link>>10)<<12)+4*((va>>12)&1023);
}
static std::string bytes(const std::filesystem::path &path) {
  std::ifstream in(path,std::ios::binary);require(bool(in),"cannot read actual C capture");
  return std::string(std::istreambuf_iterator<char>(in),std::istreambuf_iterator<char>());
}
int main(int argc,char **argv) {
  require(argc==2,"actual C capture directory required");
  static_assert(GPU_UCMD_OP_KERNEL_INDIRECT==14,"changed indirect opcode");
  require(completion(17)==0x18611 && GPU_UCMD_COMPLETION_OPCODE(completion(17))==14 &&
    GPU_UCMD_COMPLETION_STATUS(completion(17))==0,"lost extended opcode bit");
  for(uint32_t status:{0u,1u,12u,13u})
    require(GPU_UCMD_COMPLETION_OPCODE(completion(17,status))==14 &&
      GPU_UCMD_COMPLETION_STATUS(completion(17,status))==status &&
      GPU_UCMD_COMPLETION_SUCCESS(completion(17,status))==!status,"opcode/status namespaces overlap");
  rejects([]{completion(0);});rejects([]{completion(256);});rejects([]{completion(17,2);});
  for(unsigned test=0;test<14;test++) {
    const auto directory=std::filesystem::path(argv[1])/("indirect-"+std::to_string(test));
    const auto c=IndirectCapture::read((directory/"record-0.capture").string());
    const auto m=Memory::read((directory/"union.memory").string());PtePages pte;c.observe_contexts(m,pte);
    require(!pte.required_revocations.empty(),"indirect job lacks actual PTE cleanup scope");
    if(test==10)require(c.empty_contexts.size()==2 && pte.empty_roots.size()==2,
      "indirect capture lost its additional original empty VMs");
    const auto retirement=IndirectRetirement::read((directory/"record-0.retire").string());
    require(retirement.command_id==c.command_id && retirement.status==(test==11?1u:test==12?12u:test==13?13u:0u),
      "indirect retirement changed the admitted identity/result");
    require(PtePages::word(m,c.arguments.source_physical+c.arguments.source_offset)==0x11111111u,
      "source mutation after admission was not retained");
    require(PtePages::word(m,c.arguments.snapshot_physical+c.arguments.snapshot_offset)!=0x11111111u,
      "private counts followed mutable original source bytes");
    for(uint64_t k=0;k<c.arguments.original_kernarg_bytes;k++)
      require(m.byte(c.arguments.original_kernarg_physical+k)==0x73,"original kernarg changed");
    rejects([&]{ComputeCapture::read((directory/"record-0.capture").string());});
    rejects([&]{ComputeRetirement::read((directory/"record-0.retire").string());});
    auto raw=bytes(directory/"record-0.capture");
    rejects([&]{std::istringstream in(raw+"x");IndirectCapture::read(in);});
    rejects([&]{std::istringstream in(raw.substr(0,raw.size()-1));IndirectCapture::read(in);});
    rejects([&]{auto changed=raw;changed.replace(0,8,"OGCMP003");std::istringstream in(changed);IndirectCapture::read(in);});
    auto changed=c;changed.arguments.flags=0;rejects([&]{changed.validate();});
    changed=c;changed.arguments.version=1;rejects([&]{changed.validate();});
    changed=c;changed.arguments.source_offset=changed.arguments.source_bytes-8;rejects([&]{changed.validate();});
    changed=c;changed.arguments.source_physical=changed.arguments.snapshot_physical;rejects([&]{changed.validate();});
    changed=c;changed.arguments.max_groups[0]=0;rejects([&]{changed.validate();});
    changed=c;changed.arguments.snapshot_offset^=4;rejects([&]{changed.validate();});
    changed=c;changed.writable.push_back({c.arguments.snapshot_physical,c.arguments.snapshot_physical+4});
    rejects([&]{changed.validate();});
    changed=c;field(changed,GPU_REG_UCMD_OPCODE,0);rejects([&]{changed.validate();});
    changed=c;field(changed,GPU_REG_UCMD_SOURCE,c.arguments.snapshot_va+4096);rejects([&]{changed.validate();});
    changed=c;field(changed,GPU_REG_UCMD_KERNARG,c.arguments.snapshot_va+c.arguments.snapshot_offset+4);rejects([&]{changed.validate();});
    changed=c;field(changed,GPU_REG_UCMD_BYTES,c.arguments.snapshot_bytes-4);rejects([&]{changed.validate();});
    changed=c;field(changed,GPU_REG_UCMD_GRID_Z,c.arguments.grid[2]^1);rejects([&]{changed.validate();});
    changed=c;
    for(auto &r:changed.discovery)if(r.address==0x34c)r.value=0;
    rejects([&]{changed.validate();});
    for(unsigned bad=0;bad<6;bad++) {
      auto r=retirement;
      if(bad==0)r.command_id^=1;
      if(bad==1)r.status=r.status==1?13:1;
      for(auto &a:r.actions)if(a.address==GPU_REG_UCMD_COMPLETION && a.kind!=Kind::write) {
        if(bad==2)a.value&=~(1u<<16);
        if(bad==3)a.value=(r.status?r.status<<11:0x8000u)|(14u<<8)|r.command_id;
        if(bad==4)a.mask=0xffff;
      }
      if(bad==5)for(auto &a:r.actions)if(a.address==GPU_REG_UCMD_COMPLETION_BYTES_LO)a.value=1;
      rejects([&]{r.validate();});
    }
    if(test==3) {
      // Every cross-page translation and alias is checked in the real C-built
      // page tables, independently of the driver's own mapping verifier.
      for(unsigned bad=0;bad<5;bad++) {
        auto memory=m;const auto address=leaf_address(memory,c,c.arguments.snapshot_va+4096);
        auto entry=PtePages::word(memory,address);
        if(bad==0)entry|=4; // WRITE
        if(bad==1)entry|=8; // EXECUTE
        if(bad==2)entry&=~(3u<<8); // cached
        if(bad==3)entry^=1u<<10; // another physical page
        if(bad==4)entry|=0x20; // GLOBAL
        put(memory,address,entry);rejects([&]{PtePages p;c.observe_contexts(memory,p);});
      }
      auto memory=m;
      const auto first=leaf_address(memory,c,c.arguments.snapshot_va);
      const auto alias=leaf_address(memory,c,c.arguments.snapshot_va+8192);
      require(!PtePages::word(memory,alias),"test alias page already mapped");
      put(memory,alias,PtePages::word(memory,first)|4);
      rejects([&]{PtePages p;c.observe_contexts(memory,p);});
      memory=m;put(memory,c.arguments.snapshot_physical,1);
      rejects([&]{PtePages p;c.observe_contexts(memory,p);});
      memory=m;put(memory,c.arguments.snapshot_physical+c.arguments.snapshot_offset,3);
      rejects([&]{PtePages p;c.observe_contexts(memory,p);});
      ComputeCapture legacy;legacy.max_cycles=c.max_cycles;legacy.hold_cycles=c.hold_cycles;
      legacy.command_id=c.command_id;legacy.discovery=c.discovery;legacy.writable=c.writable;
      legacy.empty_contexts=c.empty_contexts;legacy.trace=c.trace;
      bool opcode_rejected=false;
      try{legacy.validate();}catch(const std::runtime_error &e){opcode_rejected=std::string(e.what()).find("unexpected pre-compute opcode")!=std::string::npos;}
      require(opcode_rejected,"legacy opcode0 validator lost its exact opcode boundary");
    }
  }
  std::cout<<"indirect actual C captures/private PTEs/extended completions and canonical rejection checks: PASS (CPU only)\n";
}
