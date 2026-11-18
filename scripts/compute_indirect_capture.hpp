#pragma once
#include "compute_owner_capture.hpp"
#include "../driver/opengpu_kernarg_va.h"
#include <array>

namespace compute_indirect {
using namespace compute_owner;

inline bool result_status(uint32_t status) {
  return status==GPU_UCMD_RESULT_SUCCESS || status==GPU_UCMD_KERNEL_EXECUTION_FAILED ||
    status==GPU_INDIRECT_RESULT_BOUNDS || status==GPU_INDIRECT_RESULT_READ_FAILED;
}
inline uint32_t completion(uint32_t command,uint32_t status=0) {
  require(command && command<256 && result_status(status),"invalid indirect completion fields");
  // Opcode bit3 lives in bit16, independently of status[14:11].
  constexpr uint32_t opcode=GPU_UCMD_OP_KERNEL_INDIRECT;
  return ((opcode&7u)<<8)|((opcode>>3)<<16)|(status<<11)|command|(status?0:0x8000u);
}

struct Arguments {
  uint32_t version=0,flags=0,source_handle=0,context=0,snapshot_va=0,snapshot_offset=0;
  uint64_t source_physical=0,source_bytes=0,source_offset=0,snapshot_physical=0,snapshot_bytes=0;
  uint64_t original_kernarg_physical=0,original_kernarg_bytes=0;
  std::array<uint32_t,3> max_groups{},grid{};
  void validate() const {
    require(version==2 && flags==1 && source_handle && source_handle<512 && context && context<512,
      "indirect capture requires v2 count-kernarg and original source identity");
    auto extent=[](uint64_t pa,uint64_t bytes) {
      return pa && bytes && pa<Memory::limit && bytes<=Memory::limit-pa;
    };
    require(extent(source_physical,source_bytes) && !((source_physical|source_bytes)&4095u) &&
      !(source_offset&3) && source_offset<=source_bytes && 12<=source_bytes-source_offset,
      "indirect source escapes its original page-backed GEM");
    require(extent(snapshot_physical,snapshot_bytes) && !(snapshot_physical&4095u) &&
      snapshot_offset==(source_offset&4095u) && snapshot_bytes==((snapshot_offset+12+4095u)&~4095u) &&
      snapshot_va==OPENGPU_COMMAND_VA_BASE+
        8u*OPENGPU_COMMAND_VA_STRIDE,
      "indirect snapshot differs from its exact command window");
    require(extent(original_kernarg_physical,original_kernarg_bytes) &&
      !(original_kernarg_physical&3) && original_kernarg_bytes>=12,
      "indirect capture lacks the original kernarg guard");
    auto separate=[](uint64_t a,uint64_t n,uint64_t b,uint64_t m){return a+n<=b || b+m<=a;};
    require(separate(source_physical,source_bytes,snapshot_physical,snapshot_bytes) &&
      separate(source_physical,source_bytes,original_kernarg_physical,original_kernarg_bytes) &&
      separate(snapshot_physical,snapshot_bytes,original_kernarg_physical,original_kernarg_bytes),
      "indirect source/private/original-kernarg allocations overlap");
    for(auto maximum:max_groups)require(maximum,"indirect caller bound is zero");
    if(std::all_of(grid.begin(),grid.end(),[](auto n){return n!=0;})) {
      uint64_t total=1;
      for(unsigned i=0;i<3;i++) {
        require(grid[i]<=max_groups[i] && total<=UINT64_MAX/grid[i],"indirect admitted grid exceeds its bound");
        total*=grid[i];
      }
    }
  }
};

// OGCI0001 is an opcode14 contract. It never accepts OGCMP or native graphics.
struct IndirectCapture {
  uint64_t max_cycles=0;
  uint32_t hold_cycles=0,command_id=0;
  Arguments arguments;
  std::vector<Command> discovery;
  std::vector<Writable> writable;
  std::vector<ComputeCapture::EmptyContext> empty_contexts;
  std::vector<Action> trace;
  std::map<uint32_t,uint32_t> staged() const {
    std::map<uint32_t,uint32_t> out;
    for(const auto &a:trace)if(a.kind==Kind::write)out[a.address]=a.value;
    return out;
  }
  void validate() const {
    require(owner_factory_has_sample(compute_owner_factory()),"indirect capture needs the current complete factory");
    arguments.validate();
    require(max_cycles && max_cycles<=1000000000 && hold_cycles>=8 && hold_cycles<=4096 &&
      command_id && command_id<256,"invalid indirect execution bounds");
    require(discovery.size()==owner_factory_words(compute_owner_factory()).size()+10,
      "indirect capture lacks complete factory/auxiliary observations");
    std::map<uint32_t,uint32_t> found;
    for(const auto &r:discovery) {
      Capture::address(r.address);require(found.emplace(r.address,r.value).second,"duplicate indirect discovery");
    }
    for(const auto &r:owner_factory_words(compute_owner_factory()))
      require(found.count(r.address) && found.at(r.address)==r.value,"indirect complete factory differs");
    for(const auto &r:ComputeCapture::transports())
      require(found.count(r.address) && found.at(r.address)==r.value,"indirect resource transport differs");
    for(uint32_t address:{GPU_REG_ID,GPU_REG_CAPABILITIES,GPU_REG_BIN_CAPABILITY_PTR,0x320,GPU_REG_SHADER_CONFIG})
      require(found.count(address),"missing indirect auxiliary observation");
    require_owner_shader_profile(found.at(GPU_REG_CAPABILITIES),found.at(GPU_REG_SHADER_CONFIG));
    // GCI1 is already in the complete factory record. Verify all eight words;
    // no new fabricated capability bit or duplicate observation is appended.
    uint32_t address=0x340;
    for(uint32_t value:{0x47434901u,8u,0x7fu,14u,12u,4u,UINT32_MAX,128u}) {
      require(found.at(address)==value,"indirect GCI1 observation differs");address+=4;
    }
    uint64_t previous=0;
    for(const auto &r:writable) {
      require(previous<=r.first && r.first<r.end && r.end<=Memory::limit,"invalid indirect writable view");
      for(const auto &ro:std::vector<Writable>{
          {arguments.source_physical,arguments.source_physical+arguments.source_bytes},
          {arguments.snapshot_physical,arguments.snapshot_physical+arguments.snapshot_bytes},
          {arguments.original_kernarg_physical,arguments.original_kernarg_physical+arguments.original_kernarg_bytes}})
        require(r.end<=ro.first || ro.end<=r.first,"indirect writable view aliases count/kernarg guards");
      previous=r.end;
    }
    uint32_t context=0;require(empty_contexts.size()<512,"oversized indirect inactive context set");
    for(const auto &c:empty_contexts) {
      require(c.context>context && c.context<512 && c.context!=arguments.context &&
        (c.satp&GPU_SATP_ENABLE) && ((c.satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK),
        "invalid indirect inactive context identity");context=c.context;
    }
    require(!trace.empty() && trace.size()<=65536,"invalid indirect enqueue trace");
    std::map<uint32_t,uint32_t> fields;bool pending=false,witnessed=false;
    unsigned kernels=0,snapshot_invalidates=0;
    for(size_t n=0;n<trace.size();n++) {
      const auto &a=trace[n];Capture::address(a.address);
      if(a.kind!=Kind::write) {
        require(a.kind==Kind::poll || a.kind==Kind::check,"invalid indirect action");
        Capture::predicate(a.mask,a.value);
        require(status_word(a.address) || completion_word(a.address) || found.count(a.address),
          "indirect capture reads outside its observed interface");
        if(pending && a.address==GPU_REG_UCMD_STATUS &&
            (a.mask&GPU_UCMD_STATUS_COMPLETION) && (a.value&GPU_UCMD_STATUS_COMPLETION))witnessed=true;
        continue;
      }
      require(!a.mask && ComputeCapture::writable_register(a.address),"invalid indirect command write");
      if(a.address==GPU_REG_UCMD_COMPLETION_POP) {
        require(pending && witnessed && a.value==1,"indirect prefix POP lacks its invalidate receipt");
        pending=false;witnessed=false;
      }else if(a.address==GPU_REG_UCMD_SUBMIT) {
        require(a.value==1 && !pending,"indirect enqueue crosses a pending command");
        auto field=[&](uint32_t key) {
          require(fields.count(key),"missing indirect launch field");return fields.at(key);
        };
        const auto opcode=field(GPU_REG_UCMD_OPCODE);
        if(opcode==GPU_UCMD_OP_KERNEL_INDIRECT) {
          require(++kernels==1 && n+1==trace.size() && snapshot_invalidates==1,
            "indirect kernel must end capture after its exact snapshot invalidate");
          require(field(GPU_REG_UCMD_ID)==command_id && !(field(GPU_REG_UCMD_KERNEL_PC)&3u) &&
            field(GPU_REG_UCMD_KERNEL_PC) && field(GPU_REG_UCMD_SOURCE)==arguments.snapshot_va &&
            field(GPU_REG_UCMD_BYTES)==arguments.snapshot_bytes &&
            field(GPU_REG_UCMD_WIDTH)==arguments.snapshot_offset &&
            field(GPU_REG_UCMD_KERNARG)==arguments.snapshot_va+arguments.snapshot_offset,
            "indirect MMIO differs from the retained private count snapshot");
          require(!field(GPU_REG_UCMD_FLAGS) && !field(GPU_REG_UCMD_WAIT_EVENT) &&
            !field(GPU_REG_UCMD_SIGNAL_EVENT),"unsupported indirect command events");
          uint64_t items=1;
          for(unsigned axis=0;axis<3;axis++) {
            const auto local=field(GPU_REG_UCMD_LOCAL_X+4*axis);
            require(field(GPU_REG_UCMD_GRID_X+4*axis)==arguments.grid[axis] && local && local<=128,
              "indirect local/grid differs");items*=local;
          }
          require(items<=128,"indirect local shape exceeds actual CU resources");
          const auto satp=field(GPU_REG_UCMD_VECTOR_SATP);
          require((satp&GPU_SATP_ENABLE) && ((satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK) &&
            satp==field(GPU_REG_UCMD_INSTRUCTION_SATP),"indirect kernel lacks its isolated shared Sv32 context");
        }else {
          require(opcode==GPU_UCMD_OP_INVALIDATE && !kernels,"unexpected indirect prefix opcode");
          if(field(GPU_REG_UCMD_SOURCE)==arguments.snapshot_physical) {
            require(field(GPU_REG_UCMD_BYTES)==arguments.snapshot_bytes,"partial indirect snapshot invalidate");
            ++snapshot_invalidates;
          }
        }
        pending=true;witnessed=false;
      }else if(a.address==GPU_REG_IRQ)require(!(a.value&~7u),"invalid indirect IRQ control");
      else if(a.address==GPU_REG_UCMD_STATUS)
        require(a.value && !(a.value&~(GPU_UCMD_STATUS_OVERFLOW|GPU_UCMD_STATUS_RESET_REJECTED)),
          "invalid indirect status acknowledgment");
      fields[a.address]=a.value;
    }
    require(kernels==1 && pending,"indirect capture lacks its held opcode14 job");
  }
  void observe_contexts(const Memory &memory,PtePages &pte) const {
    validate();pte.observe_tables(memory,trace);
    auto allocation=[&](uint64_t pa,uint64_t bytes,bool exact) {
      require(std::any_of(memory.ranges.begin(),memory.ranges.end(),[&](const auto &r) {
        return exact ? r.start==pa && r.end==pa+bytes : r.start<=pa && pa+bytes<=r.end;
      }),"indirect capture lost an original physical allocation");
    };
    allocation(arguments.source_physical,arguments.source_bytes,true);
    allocation(arguments.snapshot_physical,arguments.snapshot_bytes,true);
    allocation(arguments.original_kernarg_physical,arguments.original_kernarg_bytes,false);
    for(unsigned i=0;i<3;i++)
      require(PtePages::word(memory,arguments.snapshot_physical+arguments.snapshot_offset+4*i)==arguments.grid[i],
        "indirect private bytes differ from the admitted grid");
    for(uint64_t i=0;i<arguments.snapshot_bytes;i++)
      if(i<arguments.snapshot_offset || i>=arguments.snapshot_offset+12)
        require(!memory.byte(arguments.snapshot_physical+i),"indirect snapshot has nonzero private padding");
    const auto fields=staged();const auto satp=fields.at(GPU_REG_UCMD_VECTOR_SATP);
    const uint64_t root=uint64_t(satp&0x3fffffu)<<12;
    auto leaf=[&](uint32_t va) {
      const auto link=PtePages::word(memory,root+4*uint64_t(va>>22));
      require((link&15)==1,"indirect snapshot lost its Sv32 root link");
      return PtePages::word(memory,(uint64_t(link>>10)<<12)+4*((va>>12)&1023));
    };
    auto readonly_uncached=[](uint32_t entry) {
      require((entry&15)==3 && !(entry&0x20) && ((entry>>8)&3)==2,
        "indirect snapshot has writable/executable/global/cached translation");
    };
    for(uint64_t offset=0;offset<arguments.snapshot_bytes;offset+=4096) {
      const auto entry=leaf(arguments.snapshot_va+offset);readonly_uncached(entry);
      require((uint64_t(entry>>10)<<12)==arguments.snapshot_physical+offset,
        "indirect snapshot translation points at different physical bytes");
    }
    // No writable/executable alias in the active kernel context may reach a
    // private count page. ASID0's separate identity policy is not a CU alias.
    for(unsigned n=0;n<1024;n++) {
      const auto link=PtePages::word(memory,root+4*n);if(!(link&1))continue;
      require((link&15)==1,"indirect context contains a superpage");
      const uint64_t child=uint64_t(link>>10)<<12;
      for(unsigned k=0;k<1024;k++) {
        const auto entry=PtePages::word(memory,child+4*k);if(!(entry&1))continue;
        const uint64_t pa=uint64_t(entry>>10)<<12;
        if(pa>=arguments.snapshot_physical && pa<arguments.snapshot_physical+arguments.snapshot_bytes)
          readonly_uncached(entry);
      }
    }
    for(const auto &c:empty_contexts) {
      const uint64_t empty_root=uint64_t(c.satp&0x3fffffu)<<12;
      require(std::none_of(writable.begin(),writable.end(),[&](const auto &r) {
        return empty_root<r.end && r.first<empty_root+4096;
      }),"indirect inactive root aliases a writable view");pte.observe_empty(memory,c.satp);
    }
  }
  static IndirectCapture read(std::istream &in) {
    magic(in,"OGCI0001");IndirectCapture c;
    c.max_cycles=read_le(in,8);c.hold_cycles=read_le(in,4);c.command_id=read_le(in,4);
    auto word=[&](){return uint32_t(read_le(in,4));};
    auto count=[&](uint32_t max){const auto n=word();require(n<=max,"oversized indirect section");return n;};
    auto n=count(122);for(uint32_t i=0;i<n;i++){const auto a=word(),v=word();c.discovery.push_back({a,v});}
    auto &a=c.arguments;a.version=word();a.flags=word();a.source_handle=word();a.context=word();
    a.source_physical=read_le(in,8);a.source_bytes=read_le(in,8);a.source_offset=read_le(in,8);
    a.snapshot_physical=read_le(in,8);a.snapshot_bytes=read_le(in,8);a.snapshot_va=word();a.snapshot_offset=word();
    a.original_kernarg_physical=read_le(in,8);a.original_kernarg_bytes=read_le(in,8);
    for(auto &v:a.max_groups)v=word();
    for(auto &v:a.grid)v=word();
    n=count(4096);for(uint32_t i=0;i<n;i++){const auto first=read_le(in,8),end=read_le(in,8);c.writable.push_back({first,end});}
    n=count(511);for(uint32_t i=0;i<n;i++){const auto id=word(),satp=word();c.empty_contexts.push_back({id,satp});}
    n=count(65536);for(uint32_t i=0;i<n;i++) {
      const auto k=word(),address=word(),value=word(),mask=word();c.trace.push_back({Kind(k),address,value,mask});
    }
    require(in.peek()==EOF,"trailing indirect capture bytes");c.validate();return c;
  }
  static IndirectCapture read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open indirect capture");return read(in);
  }
};

struct IndirectRetirement {
  uint32_t command_id=0,status=0;
  std::vector<Action> actions;
  void validate() const {
    const auto expected=completion(command_id,status);
    require(!actions.empty() && actions.size()<=1024,"invalid indirect retirement count");
    unsigned pops=0;bool witnessed=false;std::set<uint32_t> telemetry;
    for(const auto &a:actions) {
      Capture::address(a.address);
      if(a.kind==Kind::write) {
        require(!a.mask,"indirect retirement write has a predicate");
        if(a.address==GPU_REG_UCMD_COMPLETION_POP) {
          require(!pops && a.value==1 && witnessed && telemetry.size()==3,"indirect POP lacks exact completion reads");++pops;
        }else require(a.address==GPU_REG_IRQ && !(a.value&~7u),"invalid indirect retirement write");
      }else {
        require(a.kind==Kind::poll || a.kind==Kind::check,"invalid indirect retirement action");
        Capture::predicate(a.mask,a.value);
        require(status_word(a.address) || completion_word(a.address),"invalid indirect retirement read");
        if(completion_word(a.address)) {
          require(!pops && a.mask==UINT32_MAX && a.value==(a.address==GPU_REG_UCMD_COMPLETION?expected:0),
            "indirect completion opcode/status/ID/telemetry differs");telemetry.insert(a.address);
        }
        if(a.address==GPU_REG_UCMD_STATUS && (a.mask&GPU_UCMD_STATUS_COMPLETION) &&
            (a.value&GPU_UCMD_STATUS_COMPLETION))witnessed=true;
      }
    }
    require(pops==1,"indirect retirement must POP exactly one command");
  }
  static IndirectRetirement read(std::istream &in) {
    magic(in,"OGCIT001");IndirectRetirement r;r.command_id=read_le(in,4);r.status=read_le(in,4);
    const auto count=read_le(in,4);require(count<=1024,"oversized indirect retirement");
    for(uint64_t n=0;n<count;n++) {
      const auto k=read_le(in,4),a=read_le(in,4),v=read_le(in,4),m=read_le(in,4);
      r.actions.push_back({Kind(k),uint32_t(a),uint32_t(v),uint32_t(m)});
    }
    require(in.peek()==EOF,"trailing indirect retirement bytes");r.validate();return r;
  }
  static IndirectRetirement read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open indirect retirement");return read(in);
  }
};
} // namespace compute_indirect
