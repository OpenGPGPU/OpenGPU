#pragma once
#include "native_frame_fixture.hpp"
#include "../driver/gpu_abi.h"
#include <set>

namespace native_owner {
using namespace native_frame;
enum class Kind : uint32_t { write=1, poll=2, check=3 };
struct Action { Kind kind; uint32_t address, value, mask; };
struct Writable { uint64_t first, end; };
struct Capture {
  uint64_t max_cycles;
  uint32_t hold_cycles, command_id;
  std::vector<Command> discovery, finish;
  std::vector<Writable> writable;
  std::vector<Action> trace;
  std::vector<Check> checks;

  // Qualification profile, not a fabricated substitute for runtime discovery.
  // The real captured values must contain and match every required word; the
  // executor then reads all captured words from the actual complete DUT.
  static std::vector<Command> profile() {
    std::vector<Command> out;
    auto block=[&](uint32_t base, std::initializer_list<uint32_t> values) {
      for(auto value:values) {out.push_back({base,value});base+=4;}
    };
    block(0x360,{0x47435201,16,0x3ffff,2,16,8,128,128,0xffffffff,16384,16,8,0x10000000,32,131072,4});
    block(0x160,{0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813d902});
    block(0x340,{0x47434901,8,0x7f,14,12,4,0xffffffff,128});
    block(0x240,{0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31});
    block(0x260,{0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255});
    block(0x280,{0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63});
    block(0x3a0,{0x47554301,16,1,0x7f,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31});
    block(0x4c0,{0x47414201,8,8,0x7fff,0xf00001ff,15,7,0});
    block(0x4e0,{0x474e5001,8,512,2048,2048,512,15,0});
    block(0x300,{0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138});
    return out;
  }
  static void address(uint32_t value) {
    require(!(value&3) && value<0x1000,"invalid captured MMIO address");
  }
  static void predicate(uint32_t mask,uint32_t expected) {
    require(mask && !(expected&~mask),"empty or noncanonical captured predicate");
  }
  void validate() const {
    require(max_cycles>0 && max_cycles<=1000000000 && hold_cycles>=8 && hold_cycles<=4096 && command_id<256,
      "invalid capture execution bounds");
    std::map<uint32_t,uint32_t> found;
    for(const auto &r:discovery) {address(r.address);require(found.emplace(r.address,r.value).second,"duplicate discovery word");}
    for(const auto &r:profile()) {
      auto at=found.find(r.address);
      require(at!=found.end() && at->second==r.value,"captured owner requires the complete two-CU production profile");
    }
    uint64_t previous=0;
    for(const auto &r:writable) {
      require(previous<=r.first && r.first<r.end && r.end<=Memory::limit,"invalid or overlapping physical writable intervals");
      previous=r.end;
    }
    require(!trace.empty(),"empty captured enqueue trace");
    std::map<uint32_t,uint32_t> staged;
    bool pending=false, witnessed=false; unsigned native=0;
    for(size_t i=0;i<trace.size();++i) {
      const auto &a=trace[i];address(a.address);
      require(a.kind==Kind::write || a.kind==Kind::poll || a.kind==Kind::check,"unknown capture trace action");
      if(a.kind!=Kind::write) {
        predicate(a.mask,a.value);
        if(pending && a.address==GPU_REG_UCMD_STATUS && (a.mask&GPU_UCMD_STATUS_COMPLETION) &&
           (a.value&GPU_UCMD_STATUS_COMPLETION)) witnessed=true;
        continue;
      }
      require(!a.mask && a.address!=GPU_REG_UCMD_RESET,"capture cannot reset or mask MMIO writes");
      if(a.address==GPU_REG_UCMD_COMPLETION_POP) {
        require(a.value==1 && pending && witnessed,"completion POP lacks an observed prior completion");
        pending=false;witnessed=false;
      } else if(a.address==GPU_REG_UCMD_SUBMIT) {
        require(a.value==1 && !pending,"captured submission crosses an unretired command");
        const auto op=staged.find(GPU_REG_UCMD_OPCODE);
        require(op!=staged.end(),"captured submission has no opcode");
        if(op->second==GPU_UCMD_OP_NATIVE_BINNED_RENDER) {
          require(++native==1 && i+1==trace.size(),"native enqueue must end the capture trace");
          auto field=[&](uint32_t key) {auto at=staged.find(key);require(at!=staged.end(),"missing native command field");return at->second;};
          require(field(GPU_REG_UCMD_ID)==command_id && field(GPU_REG_UCMD_BYTES)==512 &&
            !(field(GPU_REG_UCMD_SOURCE)&63),"native GNJ command identity or bounds differ");
          const uint32_t pattern=field(GPU_REG_UCMD_PATTERN);
          const uint64_t limit=(uint64_t(pattern&1)<<32)|field(GPU_REG_UCMD_DESTINATION);
          require(pattern<=1 && limit<=Memory::limit &&
            uint64_t(field(GPU_REG_UCMD_SOURCE))+512<=limit,
            "native GNJ command has an invalid exclusive view limit");
          require(!(field(GPU_REG_UCMD_FLAGS)&~(GPU_UCMD_FLAG_RASTER_DISCARD|GPU_UCMD_FLAG_FEEDBACK_CAPTURE)),
            "native GNJ command has unsupported DMA/event or reserved flags");
          require((field(GPU_REG_UCMD_VECTOR_SATP)&GPU_SATP_ENABLE) &&
            field(GPU_REG_UCMD_VECTOR_SATP)==field(GPU_REG_UCMD_INSTRUCTION_SATP),
            "production native capture requires the real shared Sv32 context");
        } else require(op->second==GPU_UCMD_OP_INVALIDATE && !native,"unexpected pre-native command");
        pending=true;witnessed=false;
      }
      staged[a.address]=a.value;
    }
    require(native==1 && pending,"capture has no held final native command");
    std::map<uint32_t,Check> checked;
    for(const auto &c:checks) {address(c.address);predicate(c.mask,c.expected);
      require(checked.emplace(c.address,c).second,"duplicate held-completion check");}
    auto final=checked.find(GPU_REG_UCMD_COMPLETION);
    require(final!=checked.end() && final->second.mask==0xffffffffu &&
      GPU_UCMD_COMPLETION_ID(final->second.expected)==command_id &&
      GPU_UCMD_COMPLETION_OPCODE(final->second.expected)==GPU_UCMD_OP_NATIVE_BINNED_RENDER,"missing exact native completion identity");
    for(unsigned n=0;n<12;++n) require(checked.count(0x180+4*n),"incomplete GNX held-completion checks");
    require(checked.at(0x180).mask==0xffffffffu && checked.at(0x180).expected==0x474e5801,
      "missing GNX completion magic");
    for(unsigned n:{1u,5u,6u,7u,8u}) require(checked.at(0x180+4*n).mask==0xffffffffu,
      "GNX fault status and identity checks must cover the complete words");
    unsigned pops=0;
    for(const auto &w:finish) {
      address(w.address);
      require((w.address==GPU_REG_UCMD_COMPLETION_POP && w.value==1) ||
        (w.address==GPU_REG_IRQ && !(w.value&~7u)),"invalid captured completion retirement");
      pops+=w.address==GPU_REG_UCMD_COMPLETION_POP;
    }
    require(pops==1,"captured finish must retire exactly one native completion");
  }
  static Capture read(std::istream &in) {
    magic(in,"OGCAP001");
    Capture c;c.max_cycles=read_le(in,8);c.hold_cycles=read_le(in,4);c.command_id=read_le(in,4);
    auto count=[&](unsigned maximum) {auto n=read_le(in,4);require(n<=maximum,"oversized capture section");return n;};
    auto word=[&]() {return uint32_t(read_le(in,4));};
    auto commands=[&]() {std::vector<Command> v;auto n=count(256);for(uint64_t i=0;i<n;++i) {
      auto a=word(),b=word();v.push_back({a,b});}return v;};
    c.discovery=commands();
    auto n=count(4096);for(uint64_t i=0;i<n;++i) {auto a=read_le(in,8),b=read_le(in,8);c.writable.push_back({a,b});}
    n=count(65536);for(uint64_t i=0;i<n;++i) {auto k=word(),a=word(),v=word(),m=word();c.trace.push_back({Kind(k),a,v,m});}
    n=count(256);for(uint64_t i=0;i<n;++i) {auto a=word(),m=word(),v=word();c.checks.push_back({a,m,v});}
    c.finish=commands();require(in.peek()==EOF,"trailing owner capture bytes");c.validate();return c;
  }
  static Capture read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open owner capture");return read(in);
  }
};

inline void verify_readonly(const Memory &before,const Memory &after,const std::vector<Writable> &writable) {
  require(before.ranges.size()==after.ranges.size(),"capture changed physical allocation count");
  for(size_t i=0;i<before.ranges.size();++i) {
    const auto &a=before.ranges[i],&b=after.ranges[i];
    require(a.start==b.start && a.end==b.end && a.fill==b.fill,"capture changed physical allocation identity");
  }
  for(const auto &r:writable) require(std::any_of(before.ranges.begin(),before.ranges.end(),[&](const auto &a) {
    return r.first>=a.start && r.end<=a.end;}),"writable view escapes its original physical allocation");
  std::set<uint64_t> pages;
  for(const auto &p:before.pages)pages.insert(p.first);
  for(const auto &p:after.pages)pages.insert(p.first);
  for(auto page:pages) for(unsigned i=0;i<Memory::page_bytes;++i) {
    const uint64_t address=page+i;
    if(before.byte(address)==after.byte(address))continue;
    require(std::any_of(writable.begin(),writable.end(),[&](const auto &r) {
      return r.first<=address && address<r.end;}),"capture changed an original read-only byte or guard");
  }
}
} // namespace native_owner
