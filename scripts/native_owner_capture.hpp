#pragma once
#include "native_frame_fixture.hpp"
#include "../driver/gpu_abi.h"
#include <set>

namespace native_owner {
using namespace native_frame;
enum class Kind : uint32_t { write=1, poll=2, check=3, pte_clear=4 };
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
  void validate(bool prefix_only=false) const {
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
    for(const auto &c:checks) {address(c.address);
      // Successful GNX retains raw reader address/draw fields. Their values
      // are observations, not predeclared predictions; retirement still checks
      // every raw word with a full mask before POP.
      if(!c.mask)require((c.address==0x188 || c.address==0x18c) && !c.expected,
        "only raw GNX reader observations may omit an expected predicate");
      else predicate(c.mask,c.expected);
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
    if(prefix_only){require(finish.empty(),"active prefix cannot predeclare a driver retirement");return;}
    unsigned pops=0;
    for(const auto &w:finish) {
      address(w.address);
      require((w.address==GPU_REG_UCMD_COMPLETION_POP && w.value==1) ||
        (w.address==GPU_REG_IRQ && !(w.value&~7u)),"invalid captured completion retirement");
      pops+=w.address==GPU_REG_UCMD_COMPLETION_POP;
    }
    require(pops==1,"captured finish must retire exactly one native completion");
  }
  static Capture read(std::istream &in,bool prefix_only=false) {
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
    c.finish=commands();require(in.peek()==EOF,"trailing owner capture bytes");c.validate(prefix_only);return c;
  }
  static Capture read(const std::string &path,bool prefix_only=false) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open owner capture");return read(in,prefix_only);
  }
};

// Emitted only after the original driver's drain consumes a real held receipt.
// Expected retirement actions cannot be supplied before that execution point.
struct Retirement {
  std::vector<Action> actions;
  void validate() const {
    require(!actions.empty() && actions.size()<=1024,"invalid native retirement action count");
    unsigned pops=0;bool witnessed=false;std::set<uint32_t> read_before_pop;
    for(const auto &a:actions) {
      Capture::address(a.address);
      if(a.kind==Kind::write) {
        require(!a.mask,"retirement write has a predicate mask");
        if(a.address==GPU_REG_UCMD_COMPLETION_POP) {
          require(!pops && a.value==1 && witnessed,"retirement POP lacks an observed held completion");
          for(uint32_t address:{GPU_REG_UCMD_COMPLETION,GPU_REG_UCMD_COMPLETION_BYTES_LO,GPU_REG_UCMD_COMPLETION_BYTES_HI})
            require(read_before_pop.count(address),"retirement lost command/telemetry before POP");
          for(unsigned n=0;n<12;n++)require(read_before_pop.count(0x180+4*n),"retirement lost GNX before POP");
          pops++;
        }else require(a.address==GPU_REG_IRQ && !(a.value&~7u),"retirement may only POP and acknowledge IRQ");
      }else {
        require(a.kind==Kind::poll || a.kind==Kind::check,"unknown retirement action");
        Capture::predicate(a.mask,a.value);
        const bool window=a.address==GPU_REG_UCMD_COMPLETION ||
          a.address==GPU_REG_UCMD_COMPLETION_BYTES_LO || a.address==GPU_REG_UCMD_COMPLETION_BYTES_HI ||
          (a.address>=0x180 && a.address<0x1b0);
        require(window || a.address==GPU_REG_STATUS || a.address==GPU_REG_UCMD_STATUS || a.address==GPU_REG_IRQ,
          "retirement read is outside the completion interface");
        require(!pops || !window,"retirement reads cleared completion data after POP");
        if(window){require(a.mask==0xffffffffu,"retirement must preserve complete raw words");read_before_pop.insert(a.address);}
        if(a.address==GPU_REG_UCMD_STATUS && (a.mask&GPU_UCMD_STATUS_COMPLETION) &&
           (a.value&GPU_UCMD_STATUS_COMPLETION))witnessed=true;
      }
    }
    require(pops==1,"retirement must POP exactly one command");
  }
  static Retirement read(std::istream &in) {
    magic(in,"OGRTR001");const auto count=read_le(in,4);
    require(count<=1024,"oversized retirement trace");Retirement retirement;
    for(uint64_t n=0;n<count;n++) {
      const auto kind=read_le(in,4),address=read_le(in,4),value=read_le(in,4),mask=read_le(in,4);
      retirement.actions.push_back({Kind(kind),uint32_t(address),uint32_t(value),uint32_t(mask)});
    }
    require(in.peek()==EOF,"trailing retirement bytes");retirement.validate();return retirement;
  }
  static Retirement read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open driver retirement trace");return read(in);
  }
};

// CPU coherency stores come only from actual dma_wmb-observed PTE mutations.
// They modify the existing RAM image, never replace it or stage GPU outputs.
struct Cleanup {
  std::vector<Action> actions;
  bool closes_context() const {
    unsigned bare=0;bool full=false;
    for(const auto &a:actions)if(a.kind==Kind::write) {
      if((a.address==GPU_REG_UCMD_VECTOR_SATP || a.address==GPU_REG_UCMD_INSTRUCTION_SATP) && !a.value) {
        const unsigned bit=a.address==GPU_REG_UCMD_VECTOR_SATP?1u:2u;
        require(!(bare&bit),"cleanup repeats a bare SATP publication");bare|=bit;
      }
      if(a.address==GPU_REG_UCMD_TLB_FLUSH && a.value==GPU_TLB_FLUSH_FULL)full=true;
    }
    if(!bare)return false;
    require(bare==3 && full,"context shutdown requires both bare SATPs and a full shootdown");
    for(const auto &a:actions) {
      require(a.kind!=Kind::pte_clear,"context shutdown precedes completed owner PTE revocation");
      if(a.kind==Kind::write && a.address!=GPU_REG_UCMD_TLB_FLUSH)
        require(!a.value,"context shutdown restores a translated SATP");
    }
    return true;
  }
  void validate() const {
    require(actions.size()<=65536,"oversized owner cleanup batch");
    bool pending=false;unsigned flushes=0;
    for(const auto &a:actions) {
      if(a.kind==Kind::pte_clear) {
        require(!(a.address&3) && !a.value && (a.mask&1),"cleanup may only clear a real valid PTE");
        require(!flushes,"PTE mutation follows its batch shootdown");pending=true;continue;
      }
      Capture::address(a.address);
      if(a.kind==Kind::write) {
        require(!a.mask,"cleanup MMIO write has a predicate");
        if(a.address==GPU_REG_UCMD_TLB_FLUSH) {
          require(++flushes==1,"cleanup batch crosses a shootdown boundary");
          require(a.value==GPU_TLB_FLUSH_FULL ||
            ((a.value&GPU_TLB_FLUSH_ASID) && !(a.value&~(GPU_TLB_FLUSH_ASID|(GPU_TLB_FLUSH_ASID_MASK<<GPU_TLB_FLUSH_ASID_SHIFT)))),
            "cleanup shootdown is not full or ASID scoped");pending=false;
        }else {
          require(!flushes && (a.address==GPU_REG_UCMD_VECTOR_SATP || a.address==GPU_REG_UCMD_INSTRUCTION_SATP),
            "cleanup write is outside SATP/shootdown control");pending=true;
        }
      }else {
        require(a.kind==Kind::poll || a.kind==Kind::check,"unknown cleanup action");
        Capture::predicate(a.mask,a.value);
        require(a.address==GPU_REG_STATUS || a.address==GPU_REG_UCMD_STATUS || a.address==GPU_REG_IRQ,
          "cleanup read is outside idle/status control");
      }
    }
    require(!pending,"cleanup PTE mutation has no subsequent shootdown");
  }
  static Cleanup read(std::istream &in) {
    magic(in,"OGCLN001");const auto count=read_le(in,4);
    require(count<=65536,"oversized owner cleanup batch");Cleanup c;
    for(uint64_t n=0;n<count;n++) {
      const auto kind=read_le(in,4),address=read_le(in,4),value=read_le(in,4),mask=read_le(in,4);
      c.actions.push_back({Kind(kind),uint32_t(address),uint32_t(value),uint32_t(mask)});
    }
    require(in.peek()==EOF,"trailing cleanup bytes");c.validate();return c;
  }
  static Cleanup read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open owner cleanup batch");return read(in);
  }
};
struct PtePages {
  std::set<uint64_t> pages;
  std::set<uint64_t> required_revocations;
  std::set<uint32_t> satps;
  std::map<uint64_t,std::set<uint32_t>> page_asids;
  static uint32_t word(const Memory &memory,uint64_t address) {
    require(!(address&3) && address<=Memory::limit-4 && std::any_of(memory.ranges.begin(),memory.ranges.end(),
      [&](const auto &r){return address>=r.start && address+4<=r.end;}),"PTE escapes original physical allocation");
    uint32_t value=0;for(unsigned n=0;n<4;n++)value|=uint32_t(memory.byte(address+n))<<(n*8);return value;
  }
  void page(const Memory &memory,uint64_t base,uint32_t asid) {
    require(!(base&4095) && base<=Memory::limit-4096 && std::any_of(memory.ranges.begin(),memory.ranges.end(),
      [&](const auto &r){return base>=r.start && base+4096<=r.end;}),"PTE page escapes original physical allocation");
    pages.insert(base);page_asids[base].insert(asid);
  }
  void observe_tables(const Memory &memory,const std::vector<Action> &trace) {
    for(const auto &a:trace)if(a.kind==Kind::write &&
        (a.address==GPU_REG_UCMD_VECTOR_SATP || a.address==GPU_REG_UCMD_INSTRUCTION_SATP) && (a.value&GPU_SATP_ENABLE)) {
      satps.insert(a.value);const uint32_t asid=(a.value>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK;
      const uint64_t root=uint64_t(a.value&0x3fffffu)<<12;page(memory,root,asid);
      for(unsigned n=0;n<1024;n++) {
        const auto link=word(memory,root+4*n);
        if(!(link&1))continue;
        require(!(link&0x20),"captured owner root cannot grant global translations");
        if((link&15)!=1) {
          require(!((a.value>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK) &&
            (link&15)==7 && (link>>10)==n*1024,"owner root contains an unexpected leaf");
          continue; // Actual ASID-0 identity superpage, never a child table.
        }
        const uint64_t child=uint64_t(link>>10)<<12;page(memory,child,asid);
        for(unsigned entry=0;entry<1024;entry++) {
          const uint64_t address=child+4*entry;const auto leaf=word(memory,address);
          if(!(leaf&1))continue;
          require((leaf&3)==3 && !(leaf&0x20),"captured owner has an invalid or global leaf");
          // The active bridge creates a fresh isolated VM: all context leaves
          // belong to its prepared owners, including code, uniforms and frame
          // resources. ASID-0 identity policy pages intentionally remain live.
          if(asid)required_revocations.insert(address);
        }
      }
    }
  }
  void observe(const Memory &memory,const Capture &capture) {
    observe_tables(memory,capture.trace);
    std::map<uint32_t,uint32_t> staged;
    for(const auto &a:capture.trace)if(a.kind==Kind::write)staged[a.address]=a.value;
    const auto satp=staged.at(GPU_REG_UCMD_VECTOR_SATP),va=staged.at(GPU_REG_UCMD_SOURCE);
    const uint64_t root=uint64_t(satp&0x3fffffu)<<12;
    const auto link=word(memory,root+4*uint64_t(va>>22));
    require((link&15)==1,"captured owner GNJ lacks its actual root link");
    const uint64_t leaf=(uint64_t(link>>10)<<12)+4*((va>>12)&1023);
    require((word(memory,leaf)&15)==3,"captured owner GNJ lacks its original RO/NX mapping");
    require(((satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK)!=0,"protected owner must use its isolated nonzero ASID");
    required_revocations.insert(leaf);
  }
  void validate_scope(const Cleanup &batch,const Memory *memory=nullptr) const {
    batch.validate();
    if(batch.closes_context()) {
      require(memory,"context shutdown requires the current owner PTE image");
      revoked(*memory);return;
    }
    std::set<uint32_t> affected;
    for(const auto &a:batch.actions) {
      if(a.kind==Kind::pte_clear) {
        const auto owners=page_asids.find(uint64_t(a.address)&~uint64_t(4095));
        require(owners!=page_asids.end() && !owners->second.empty(),"cleanup PTE has no captured ASID owner");
        affected.insert(owners->second.begin(),owners->second.end());
      }else if(a.kind==Kind::write) {
        if(a.address==GPU_REG_UCMD_TLB_FLUSH) {
          if(a.value!=GPU_TLB_FLUSH_FULL) {
            const auto asid=(a.value>>GPU_TLB_FLUSH_ASID_SHIFT)&GPU_TLB_FLUSH_ASID_MASK;
            require(std::all_of(affected.begin(),affected.end(),[&](auto owner){return owner==asid;}),
              "cleanup shootdown does not cover every revoked PTE ASID");
            require(std::any_of(satps.begin(),satps.end(),[&](auto satp) {
              return ((satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK)==asid;
            }),"cleanup shootdown names an uncaptured ASID");
          }
          affected.clear();
        }else {
          require(satps.count(a.value),"cleanup attempted a context absent from the real capture");
          // Restoring the captured global root selects the next context; it
          // does not revoke a PTE belonging to that root. The shootdown must
          // cover the owners of the cleared PTEs, including the retiring VM.
        }
      }
    }
    require(affected.empty(),"cleanup left an ASID without its shootdown");
  }
  void clear(Memory &memory,const Action &a) const {
    require(a.kind==Kind::pte_clear && !(a.address&3) && !a.value && (a.mask&1) &&
      pages.count(uint64_t(a.address)&~uint64_t(4095)),"cleanup CPU store is outside the actual SATP page tables");
    require(word(memory,a.address)==a.mask,"cleanup PTE old value differs from live RAM");
    for(unsigned n=0;n<4;n++)memory.byte(uint64_t(a.address)+n,0);
  }
  void revoked(const Memory &memory) const {
    require(!required_revocations.empty(),"cleanup has no captured owner mappings");
    for(auto address:required_revocations)require(!(word(memory,address)&1),"cleanup retained a captured owner mapping");
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
