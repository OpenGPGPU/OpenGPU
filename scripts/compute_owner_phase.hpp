#pragma once
#include "compute_indirect_capture.hpp"
#include <limits>

// Continuous ownership has its own protocol. The opcode0/14 capture readers
// and whole-session cleanup parser retain their existing strict contracts.
namespace compute_phase {
using namespace compute_indirect;
using Extent=std::pair<uint64_t,uint64_t>;

inline uint64_t remaining(std::istream &in) {
  const auto begin=in.tellg();in.seekg(0,std::ios::end);const auto end=in.tellg();
  require(begin>=0 && end>=begin,"phase input must be a bounded seekable file");
  in.seekg(begin);require(bool(in),"cannot restore phase input position");return uint64_t(end-begin);
}
struct Original {
  uint32_t handle=0;uint64_t physical=0;std::vector<uint8_t> bytes;
  uint64_t end() const {return physical+bytes.size();}
};
struct Originals {
  uint32_t command=0,opcode=0,epoch=0;
  std::vector<Original> objects;
  static Originals read(std::istream &in) {
    magic(in,"OGCOB001");Originals result;
    result.command=read_le(in,4);result.opcode=read_le(in,4);result.epoch=read_le(in,4);
    const auto count=read_le(in,4);
    require(result.command && result.command<256 && (result.opcode==0 || result.opcode==14) &&
      count && count<512,"invalid phase original header");
    uint32_t previous=0;uint64_t total=0;
    for(uint64_t n=0;n<count;n++) {
      Original o;o.handle=read_le(in,4);const auto pad=read_le(in,4);
      o.physical=read_le(in,8);const auto size=read_le(in,8);
      require(o.handle>previous && o.handle<512 && !pad && o.physical && size &&
        !((o.physical|size)&4095) && o.physical<Memory::limit && size<=Memory::limit-o.physical &&
        size<=Memory::limit-total && size<=remaining(in),"invalid or truncated original GEM identity");
      for(const auto &old:result.objects)
        require(o.physical>=old.end() || old.physical>=o.physical+size,"phase originals alias physical storage");
      o.bytes.resize(size);in.read(reinterpret_cast<char *>(o.bytes.data()),size);
      require(bool(in),"truncated original GEM bytes");previous=o.handle;total+=size;
      result.objects.push_back(std::move(o));
    }
    require(in.peek()==EOF,"trailing phase original bytes");return result;
  }
  static Originals read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open phase originals");return read(in);
  }
  const Original &object(uint32_t handle) const {
    for(const auto &o:objects)if(o.handle==handle)return o;
    throw std::runtime_error("phase lost an original GEM handle");
  }
  void verify(const Memory &memory,uint32_t expected_command,uint32_t expected_opcode,uint32_t expected_epoch) const {
    require(command==expected_command && opcode==expected_opcode && epoch==expected_epoch,
      "phase original command/opcode/epoch differs");
    for(const auto &o:objects) {
      require(std::any_of(memory.ranges.begin(),memory.ranges.end(),[&](const auto &r) {
        return r.start==o.physical && r.end==o.end();
      }),"original GEM lacks its exact physical allocation");
      for(size_t n=0;n<o.bytes.size();n++)require(memory.byte(o.physical+n)==o.bytes[n],
        "original GEM upload bytes differ from live RAM");
    }
  }
  // Return actual RAM only. The producer/controller still independently checks
  // shader results before authorizing source import and phase cleanup.
  void write_actual(std::ostream &out,const Memory &memory) const {
    out.write("OGCOB001",8);
    for(uint32_t v:{command,opcode,epoch,uint32_t(objects.size())})write_le(out,v,4);
    for(const auto &o:objects) {
      write_le(out,o.handle,4);write_le(out,0,4);write_le(out,o.physical,8);write_le(out,o.bytes.size(),8);
      require(std::any_of(memory.ranges.begin(),memory.ranges.end(),[&](const auto &r) {
        return r.start==o.physical && r.end==o.end();
      }),"returned original GEM changed physical identity");
      for(uint64_t address=o.physical;address<o.end();address++)out.put(char(memory.byte(address)));
    }
    require(bool(out),"cannot return actual original GEM bytes");
  }
  bool publication(uint64_t first,uint64_t bytes,const std::vector<Writable> &writable) const {
    if(!bytes || ((first|bytes)&63) || first>=Memory::limit || bytes>Memory::limit-first)return false;
    for(const auto &o:objects)if(first>=o.physical && first+bytes<=o.end()) {
      uint64_t covered=first;
      for(const auto &r:writable) {
        if(covered>=first+bytes)break;
        if(r.first<o.physical || r.end>o.end())continue;
        const auto begin=r.first&~uint64_t(63),end=(r.end+63)&~uint64_t(63);
        if(end<=covered)continue;
        if(begin>covered)return false;
        covered=end;
      }
      return covered>=first+bytes;
    }
    return false;
  }
  std::set<Extent> retained(const Memory &memory,const PtePages &pte) const {
    std::set<Extent> result;
    for(const auto &o:objects)result.emplace(o.physical,o.end());
    auto table=[&](uint64_t base) {
      require(std::any_of(memory.ranges.begin(),memory.ranges.end(),[&](const auto &r) {
        return r.start==base && r.end==base+4096;
      }),"retained MMU page lacks its separate original allocation");
      result.emplace(base,base+4096);
    };
    for(const auto &entry:pte.page_asids)if(entry.second.count(0))table(entry.first);
    for(const auto &entry:pte.empty_roots)table(entry.second);
    return result;
  }
};

inline void append_retained(Memory &live,const Memory &next,const std::set<Extent> &retained) {
  require(&live!=&next && !next.ranges.empty(),"empty or aliased retained upload");
  Memory additions;std::set<uint64_t> new_pages;
  uint64_t previous=0;
  for(const auto &r:next.ranges) {
    require(previous<=r.start && r.start<r.end && r.end<=Memory::limit &&
      !((r.start|r.end)&4095),"invalid retained upload allocation");previous=r.end;
    const Memory::Range *overlap=nullptr;
    for(const auto &old:live.ranges)if(r.start<old.end && old.start<r.end) {
      require(!overlap,"retained upload crosses old allocation boundaries");overlap=&old;
    }
    if(!overlap) {
      additions.ranges.push_back(r);
      for(uint64_t base=r.start;base<r.end;base+=4096)new_pages.insert(base);
      continue;
    }
    require(r.start==overlap->start && r.end==overlap->end && r.fill==overlap->fill &&
      retained.count({r.start,r.end}),"upload overlaps an unauthorized retained allocation");
    for(uint64_t base=r.start;base<r.end;base+=4096) {
      const auto a=live.pages.find(base);const auto b=next.pages.find(base);
      if(a!=live.pages.end() && b!=next.pages.end())require(a->second==b->second,
        "retained upload would replace DUT-produced bytes");
      else if(a!=live.pages.end())require(std::all_of(a->second.begin(),a->second.end(),
        [&](uint8_t v){return v==next.default_byte(base);}),"retained upload loses DUT-produced bytes");
      else if(b!=next.pages.end())require(std::all_of(b->second.begin(),b->second.end(),
        [&](uint8_t v){return v==live.default_byte(base);}),"retained upload changes original fill bytes");
      else require(live.default_byte(base)==next.default_byte(base),"retained fill differs");
    }
  }
  for(const auto &page:next.pages) {
    require(!(page.first&4095) && std::any_of(next.ranges.begin(),next.ranges.end(),[&](const auto &r) {
      return page.first>=r.start && page.first+4096<=r.end;
    }),"retained upload has an orphan page");
    if(new_pages.count(page.first))additions.pages.emplace(page);
  }
  if(!additions.ranges.empty())live.append_disjoint(additions);
}

// Reuse only the originally observed ASID0 identity policy. Each new capture
// must independently prove its protected VM and the original empty contexts.
inline PtePages global_policy(const PtePages &old) {
  PtePages next;
  for(auto satp:old.satps)if(!((satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK))next.satps.insert(satp);
  require(!next.satps.empty(),"continuous owner lost its original global MMU policy");
  for(const auto &entry:old.page_asids)if(entry.second.count(0)) {
    next.pages.insert(entry.first);next.page_asids[entry.first].insert(0);
  }
  return next;
}
inline void validate_scope(const PtePages &pte,const Cleanup &scope,const Memory &memory) {
  for(const auto &a:scope.actions)if(a.kind==Kind::pte_clear) {
    const auto at=pte.page_asids.find(uint64_t(a.address)&~uint64_t(4095));
    require(at!=pte.page_asids.end() && !at->second.count(0),"phase cannot revoke the retained global MMU policy");
  }
  pte.validate_scope(scope,&memory);
}

struct Lease {
  uint32_t satp=0;
  std::set<uint64_t> private_ptes;
  static Lease observe(const Originals &originals,const PtePages &pte,const Memory &memory,
      const std::vector<Action> &trace) {
    Lease lease;uint32_t instruction=0;
    for(const auto &a:trace)if(a.kind==Kind::write) {
      if(a.address==GPU_REG_UCMD_VECTOR_SATP)lease.satp=a.value;
      if(a.address==GPU_REG_UCMD_INSTRUCTION_SATP)instruction=a.value;
    }
    require(lease.satp==instruction && (lease.satp&GPU_SATP_ENABLE) &&
      ((lease.satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK) && pte.satps.count(lease.satp),
      "phase lost its original protected instruction/vector VM");
    for(auto address:pte.required_revocations) {
      const auto value=PtePages::word(memory,address);const auto pa=uint64_t(value>>10)<<12;
      require(value&1,"phase began with an invalid captured leaf");
      if(std::none_of(originals.objects.begin(),originals.objects.end(),[&](const auto &o) {
        return pa>=o.physical && pa+4096<=o.end();
      }))lease.private_ptes.insert(address);
    }
    require(!lease.private_ptes.empty(),"phase lacks private job snapshot mappings");return lease;
  }
  void retired(const PtePages &pte,const Memory &memory,const std::vector<Action> &terminal) const {
    // Production vm_destroy evicts the ASID and switches both SATPs to the
    // original global root before freeing page tables. Original-GEM leaves
    // need not be zeroed in those now-dead tables. Private job backings were
    // freed earlier and therefore still require their exact PTE revocations.
    for(auto address:private_ptes)require(!(PtePages::word(memory,address)&1),
      "phase retained a private job backing mapping");
    require(!terminal.empty() && std::none_of(terminal.begin(),terminal.end(),[](const auto &a) {
      return a.kind==Kind::pte_clear;
    }),"final namespace retirement is an owner unmap rather than vm_destroy");
    uint32_t vector=0,instruction=0;
    for(const auto &a:terminal)if(a.kind==Kind::write) {
      if(a.address==GPU_REG_UCMD_VECTOR_SATP)vector=a.value;
      if(a.address==GPU_REG_UCMD_INSTRUCTION_SATP)instruction=a.value;
    }
    const auto &last=terminal.back();const auto asid=(satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK;
    require(last.kind==Kind::write && last.address==GPU_REG_UCMD_TLB_FLUSH && !last.mask &&
      last.value==(GPU_TLB_FLUSH_ASID|(asid<<GPU_TLB_FLUSH_ASID_SHIFT)) &&
      vector==instruction && (vector&GPU_SATP_ENABLE) &&
      !((vector>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK) && pte.satps.count(vector),
      "phase lacks terminal original global SATPs and exact retired-ASID shootdown");
  }
};

struct Begin {
  uint32_t command=0,context=0,opcode=0,epoch=0;
  static Begin read(std::istream &in) {
    magic(in,"OGCPB001");require(read_le(in,4)==1,"phase cleanup requires exactly one completed job");
    Begin b;b.command=read_le(in,4);b.context=read_le(in,4);b.opcode=read_le(in,4);b.epoch=read_le(in,4);
    require(b.command && b.command<256 && b.context && b.context<512 &&
      (b.opcode==0 || b.opcode==14) && in.peek()==EOF,"invalid phase cleanup authorization");return b;
  }
  void bind(const Originals &o,uint32_t expected_context) const {
    require(command==o.command && opcode==o.opcode && epoch==o.epoch && context==expected_context,
      "phase cleanup authorization changed its original owner");
  }
};
struct Done {
  uint32_t batches=0,command=0,context=0,epoch=0;
  std::vector<ComputeCapture::EmptyContext> empty_contexts;
  static Done read(std::istream &in) {
    magic(in,"OGCPD001");Done d;d.batches=read_le(in,4);d.command=read_le(in,4);
    d.context=read_le(in,4);d.epoch=read_le(in,4);const auto count=read_le(in,4);
    require(d.batches && d.batches<65536 && count<512,"invalid phase completion bounds");
    uint32_t previous=0;
    for(uint64_t n=0;n<count;n++) {
      const auto id=uint32_t(read_le(in,4)),satp=uint32_t(read_le(in,4));
      require(id>previous && id<512 && id!=d.context && (satp&GPU_SATP_ENABLE) &&
        ((satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK),"invalid retained empty context");
      d.empty_contexts.push_back({id,satp});previous=id;
    }
    require(in.peek()==EOF,"trailing phase completion bytes");return d;
  }
  template<class CaptureType>
  void verify(const Begin &b,unsigned sequence,const CaptureType &capture,const PtePages &pte,const Memory &memory,
      const Lease &lease,const std::vector<Action> &terminal) const {
    require(batches==sequence && command==b.command && context==b.context && epoch==b.epoch &&
      empty_contexts.size()==capture.empty_contexts.size(),"phase completion lost an owner/context/batch");
    for(size_t n=0;n<empty_contexts.size();n++) {
      const auto &c=empty_contexts[n];const auto asid=(c.satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK;
      const auto root=uint64_t(c.satp&0x3fffffu)<<12;
      require(c.context==capture.empty_contexts[n].context && c.satp==capture.empty_contexts[n].satp &&
        pte.empty_roots.count(asid) && pte.empty_roots.at(asid)==root,"phase replaced an original empty VM");
      for(unsigned k=0;k<1024;k++)require(!PtePages::word(memory,root+4*k),"retained VM gained a mapping");
    }
    lease.retired(pte,memory,terminal);
  }
};

struct Batch {
  std::vector<Action> actions;
  // Strip only independently validated publication traffic before applying the
  // existing exact PTE/ASID revocation policy. No new kernel or reset is legal.
  Cleanup revocations(const Originals &originals,const std::vector<Writable> &writable) const {
    require(!actions.empty() && actions.size()<=65536,"invalid phase cleanup batch size");
    Cleanup scope;std::map<uint32_t,uint32_t> staged;bool pending=false,witnessed=false;
    std::set<uint32_t> telemetry;uint32_t command=0,bytes=0;
    const std::set<uint32_t> controls={0x420,0x440,0x480,0x3e0,0x4a0};
    const std::set<uint32_t> zeros={GPU_REG_UCMD_KERNEL_PC,GPU_REG_UCMD_KERNARG,GPU_REG_UCMD_FLAGS,
      GPU_REG_UCMD_WAIT_EVENT,GPU_REG_UCMD_SIGNAL_EVENT,GPU_REG_UCMD_DESTINATION,GPU_REG_UCMD_PATTERN,
      GPU_REG_UCMD_HEIGHT,GPU_REG_UCMD_SOURCE_STRIDE,GPU_REG_UCMD_DEST_STRIDE,GPU_REG_UCMD_SAMPLE_MODE};
    const std::set<uint32_t> ones={GPU_REG_UCMD_GRID_X,GPU_REG_UCMD_GRID_Y,GPU_REG_UCMD_GRID_Z,
      GPU_REG_UCMD_LOCAL_X,GPU_REG_UCMD_LOCAL_Y,GPU_REG_UCMD_LOCAL_Z};
    for(const auto &a:actions) {
      if(a.kind==Kind::pte_clear) {
        require(!pending && staged.empty(),"PTE mutation crosses an unretired publication");scope.actions.push_back(a);continue;
      }
      Capture::address(a.address);
      if(a.kind==Kind::write) {
        require(!a.mask,"phase MMIO write has a predicate");
        if(a.address==GPU_REG_UCMD_VECTOR_SATP || a.address==GPU_REG_UCMD_INSTRUCTION_SATP ||
           a.address==GPU_REG_UCMD_TLB_FLUSH) {
          require(!pending && staged.empty(),"publication crosses a VM mutation");scope.actions.push_back(a);continue;
        }
        if(a.address==GPU_REG_IRQ) {require(!(a.value&~7u),"invalid phase IRQ control");continue;}
        if(a.address==GPU_REG_UCMD_STATUS) {
          require(a.value && !(a.value&~(GPU_UCMD_STATUS_OVERFLOW|GPU_UCMD_STATUS_RESET_REJECTED)),
            "invalid phase status acknowledgement");continue;
        }
        if(a.address==GPU_REG_UCMD_COMPLETION_POP) {
          require(a.value==1 && pending && witnessed && telemetry.size()==3,
            "phase POP lacks exact publication completion/telemetry");
          pending=false;witnessed=false;telemetry.clear();staged.clear();continue;
        }
        if(a.address==GPU_REG_UCMD_SUBMIT) {
          require(a.value==1 && !pending,"phase submit crosses a pending publication");
          auto field=[&](uint32_t key) {require(staged.count(key),"incomplete phase publication");return staged.at(key);};
          require(field(GPU_REG_UCMD_OPCODE)==GPU_UCMD_OP_INVALIDATE,"phase cannot execute a kernel or DMA write");
          command=field(GPU_REG_UCMD_ID);bytes=field(GPU_REG_UCMD_BYTES);
          require(command && command<256 && field(GPU_REG_UCMD_WIDTH)==bytes &&
            originals.publication(field(GPU_REG_UCMD_SOURCE),bytes,writable),"publication escapes original writable lines");
          for(auto key:controls)require(!field(key),"publication enables a shader service");
          for(auto key:zeros)require(!field(key),"publication has nonzero shader/event/DMA fields");
          for(auto key:ones)require(field(key)==1,"publication changes compute grid/local state");
          pending=true;continue;
        }
        require(!pending && (controls.count(a.address) || zeros.count(a.address) || ones.count(a.address) ||
          a.address==GPU_REG_UCMD_ID || a.address==GPU_REG_UCMD_OPCODE || a.address==GPU_REG_UCMD_SOURCE ||
          a.address==GPU_REG_UCMD_BYTES || a.address==GPU_REG_UCMD_WIDTH),"phase write is outside publication control");
        require(staged.emplace(a.address,a.value).second,"phase repeats a staged publication field");
      }else {
        require(a.kind==Kind::poll || a.kind==Kind::check,"unknown phase cleanup action");Capture::predicate(a.mask,a.value);
        require(status_word(a.address) || completion_word(a.address),"phase read is outside status/completion control");
        if(completion_word(a.address)) {
          const auto expected=a.address==GPU_REG_UCMD_COMPLETION?(0x8500u|command):
            a.address==GPU_REG_UCMD_COMPLETION_BYTES_LO?bytes:0;
          require(pending && a.mask==UINT32_MAX && a.value==expected,"phase publication result/telemetry differs");
          telemetry.insert(a.address);
        }
        if(pending && a.address==GPU_REG_UCMD_STATUS && (a.mask&GPU_UCMD_STATUS_COMPLETION) &&
           (a.value&GPU_UCMD_STATUS_COMPLETION))witnessed=true;
        if(status_word(a.address))scope.actions.push_back(a);
      }
    }
    require(!pending && staged.empty(),"phase leaves an incomplete publication");scope.validate();
    require(!scope.closes_context(),"phase cleanup cannot shut down the complete MMU");return scope;
  }
  static Batch read(std::istream &in) {
    magic(in,"OGCPC001");const auto count=read_le(in,4);
    require(count && count<=65536,"invalid phase cleanup action count");Batch b;
    for(uint64_t n=0;n<count;n++) {
      const auto k=read_le(in,4),a=read_le(in,4),v=read_le(in,4),m=read_le(in,4);
      b.actions.push_back({Kind(k),uint32_t(a),uint32_t(v),uint32_t(m)});
    }
    require(in.peek()==EOF,"trailing phase cleanup bytes");return b;
  }
  static Batch read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open phase cleanup batch");return read(in);
  }
};
} // namespace compute_phase
