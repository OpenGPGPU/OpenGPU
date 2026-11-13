#pragma once
#include "native_owner_capture.hpp"

namespace compute_owner {
using namespace native_owner;

inline bool completion_word(uint32_t address) {
  return address==GPU_REG_UCMD_COMPLETION || address==GPU_REG_UCMD_COMPLETION_BYTES_LO ||
    address==GPU_REG_UCMD_COMPLETION_BYTES_HI;
}
inline bool status_word(uint32_t address) {
  return address==GPU_REG_STATUS || address==GPU_REG_UCMD_STATUS || address==GPU_REG_IRQ;
}
// Distinct opcode-0 wire protocol. A native GNJ/GNX capture is never accepted.
struct ComputeCapture {
  uint64_t max_cycles=0;
  uint32_t hold_cycles=0,command_id=0;
  std::vector<Command> discovery;
  std::vector<Writable> writable;
  std::vector<Action> trace;
  static std::vector<Command> transports() {
    return {{0x494,0x47544d01},{0x43c,0x47534d01},{0x3fc,0x47494d01},
            {0x4bc,0x47414d01},{0x460,0x47554d01}};
  }
  static bool writable_register(uint32_t address) {
    return (address>=GPU_REG_UCMD_ID && address<=GPU_REG_UCMD_SAMPLE_MODE &&
            address!=GPU_REG_UCMD_DMA_DEPENDENCY && address!=GPU_REG_UCMD_RESET &&
            !completion_word(address)) || address==GPU_REG_UCMD_VECTOR_SATP ||
      address==GPU_REG_UCMD_INSTRUCTION_SATP || address==GPU_REG_UCMD_TLB_FLUSH ||
      address==GPU_REG_IRQ || (address>=0x420 && address<0x43c) ||
      (address>=0x440 && address<0x460) || (address>=0x480 && address<0x494) ||
      (address>=0x3e0 && address<0x3fc) || (address>=0x4a0 && address<0x4bc);
  }
  void validate() const {
    require(max_cycles && max_cycles<=1000000000 && hold_cycles>=8 && hold_cycles<=4096 && command_id<256,
      "invalid compute capture execution bounds");
    require(discovery.size()==106,"compute capture requires the actual 96+10 profile");
    std::map<uint32_t,uint32_t> found;
    for(const auto &r:discovery) {
      Capture::address(r.address);require(found.emplace(r.address,r.value).second,"duplicate compute discovery word");
    }
    auto exact=[&](const Command &r) {
      const auto at=found.find(r.address);
      require(at!=found.end() && at->second==r.value,"compute source/transport contract differs");
    };
    for(const auto &r:Capture::profile())exact(r);
    for(const auto &r:transports())exact(r);
    for(uint32_t address:{GPU_REG_ID,GPU_REG_CAPABILITIES,GPU_REG_BIN_CAPABILITY_PTR,0x320,GPU_REG_SHADER_CONFIG})
      require(found.count(address),"missing actual compute auxiliary observation");
    require_owner_shader_profile(found.at(GPU_REG_CAPABILITIES),found.at(GPU_REG_SHADER_CONFIG));
    uint64_t previous=0;
    require(!writable.empty(),"compute capture lacks original writable views");
    for(const auto &r:writable) {
      require(previous<=r.first && r.first<r.end && r.end<=Memory::limit,
        "invalid or overlapping compute writable intervals");previous=r.end;
    }
    require(!trace.empty() && trace.size()<=65536,"invalid compute enqueue action count");
    std::map<uint32_t,uint32_t> staged;unsigned kernels=0;bool pending=false,witnessed=false;
    for(size_t n=0;n<trace.size();n++) {
      const auto &a=trace[n];Capture::address(a.address);
      if(a.kind!=Kind::write) {
        require(a.kind==Kind::poll || a.kind==Kind::check,"invalid compute capture action");
        Capture::predicate(a.mask,a.value);
        require(status_word(a.address) || completion_word(a.address) || found.count(a.address),
          "compute capture reads outside its observed interface");
        if(pending && a.address==GPU_REG_UCMD_STATUS && (a.mask&GPU_UCMD_STATUS_COMPLETION) &&
            (a.value&GPU_UCMD_STATUS_COMPLETION))witnessed=true;
        continue;
      }
      require(!a.mask && writable_register(a.address),"compute capture writes outside its command interface");
      if(a.address==GPU_REG_UCMD_COMPLETION_POP) {
        require(pending && witnessed && a.value==1,"compute prefix POP lacks an observed invalidate completion");
        pending=false;witnessed=false;
      }else if(a.address==GPU_REG_UCMD_SUBMIT) {
        require(a.value==1 && !pending,"compute submit crosses a pending command");
        auto field=[&](uint32_t key) {
          auto at=staged.find(key);require(at!=staged.end(),"missing captured compute launch field");return at->second;
        };
        const auto opcode=field(GPU_REG_UCMD_OPCODE);
        if(opcode==GPU_UCMD_OP_KERNEL) {
          require(++kernels==1 && n+1==trace.size(),"kernel enqueue must end compute capture");
          require(field(GPU_REG_UCMD_ID)==command_id,"compute command ID differs");
          require(!(field(GPU_REG_UCMD_KERNEL_PC)&3u) && !(field(GPU_REG_UCMD_KERNARG)&3u),
            "unaligned captured compute entry or kernarg");
          require(!field(GPU_REG_UCMD_FLAGS) && !field(GPU_REG_UCMD_WAIT_EVENT) && !field(GPU_REG_UCMD_SIGNAL_EVENT),
            "compute capture has unsupported command events");
          uint64_t items=1;
          for(unsigned axis=0;axis<3;axis++) {
            const auto local=field(GPU_REG_UCMD_LOCAL_X+4*axis);
            require(field(GPU_REG_UCMD_GRID_X+4*axis) && local && local<=128,
              "invalid compute grid/local axis");items*=local;
          }
          require(items<=128,"compute local product exceeds actual CU resources");
          const auto satp=field(GPU_REG_UCMD_VECTOR_SATP);
          require((satp&GPU_SATP_ENABLE) && ((satp>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK) &&
            satp==field(GPU_REG_UCMD_INSTRUCTION_SATP),"compute requires its actual isolated shared Sv32 context");
        }else require(opcode==GPU_UCMD_OP_INVALIDATE && !kernels,"unexpected pre-compute opcode");
        pending=true;witnessed=false;
      }else if(a.address==GPU_REG_IRQ)require(!(a.value&~7u),"invalid compute IRQ control");
      else if(a.address==GPU_REG_UCMD_STATUS)
        require(a.value && !(a.value&~(GPU_UCMD_STATUS_OVERFLOW|GPU_UCMD_STATUS_RESET_REJECTED)),
          "invalid compute status acknowledgment");
      staged[a.address]=a.value;
    }
    require(kernels==1 && pending,"compute capture has no held kernel");
  }
  static ComputeCapture read(std::istream &in) {
    magic(in,"OGCMP001");ComputeCapture c;
    c.max_cycles=read_le(in,8);c.hold_cycles=read_le(in,4);c.command_id=read_le(in,4);
    auto count=[&](uint32_t maximum) {const auto n=read_le(in,4);require(n<=maximum,"oversized compute capture section");return n;};
    auto word=[&]() {return uint32_t(read_le(in,4));};
    auto n=count(106);for(uint64_t i=0;i<n;i++){const auto a=word(),v=word();c.discovery.push_back({a,v});}
    n=count(4096);for(uint64_t i=0;i<n;i++){const auto a=read_le(in,8),b=read_le(in,8);c.writable.push_back({a,b});}
    n=count(65536);for(uint64_t i=0;i<n;i++) {
      const auto k=word(),a=word(),v=word(),m=word();c.trace.push_back({Kind(k),a,v,m});
    }
    require(in.peek()==EOF,"trailing compute capture bytes");c.validate();return c;
  }
  static ComputeCapture read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open compute capture");return read(in);
  }
};

struct ComputeRetirement {
  std::vector<Action> actions;
  void validate() const {
    require(!actions.empty() && actions.size()<=1024,"invalid compute retirement count");
    unsigned pops=0;bool witnessed=false;std::set<uint32_t> words;
    for(const auto &a:actions) {
      Capture::address(a.address);
      if(a.kind==Kind::write) {
        require(!a.mask,"compute retirement write has a predicate");
        if(a.address==GPU_REG_UCMD_COMPLETION_POP) {
          require(!pops && a.value==1 && witnessed && words.size()==3,
            "compute POP lacks actual completion/telemetry reads");++pops;
        }else require(a.address==GPU_REG_IRQ && !(a.value&~7u),"invalid compute retirement write");
      }else {
        require(a.kind==Kind::poll || a.kind==Kind::check,"invalid compute retirement action");
        Capture::predicate(a.mask,a.value);
        require(status_word(a.address) || completion_word(a.address),"invalid compute retirement read");
        if(completion_word(a.address)) {
          require(!pops && a.mask==0xffffffffu,"compute telemetry read follows POP or loses raw bits");
          words.insert(a.address);
        }
        if(a.address==GPU_REG_UCMD_STATUS && (a.mask&GPU_UCMD_STATUS_COMPLETION) &&
            (a.value&GPU_UCMD_STATUS_COMPLETION))witnessed=true;
      }
    }
    require(pops==1,"compute retirement must POP exactly one command");
  }
  static ComputeRetirement read(std::istream &in) {
    magic(in,"OGCMT001");ComputeRetirement r;const auto count=read_le(in,4);
    require(count<=1024,"oversized compute retirement");
    for(uint64_t n=0;n<count;n++) {
      const auto k=read_le(in,4),a=read_le(in,4),v=read_le(in,4),m=read_le(in,4);
      r.actions.push_back({Kind(k),uint32_t(a),uint32_t(v),uint32_t(m)});
    }
    require(in.peek()==EOF,"trailing compute retirement bytes");r.validate();return r;
  }
  static ComputeRetirement read(const std::string &path) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open compute retirement");return read(in);
  }
};
} // namespace compute_owner
