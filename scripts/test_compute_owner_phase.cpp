// Actual C phase fixtures, independent C++ policy; explicit CPU peers only.
#include "compute_owner_phase.hpp"
#include <filesystem>
#include <iostream>
#include <sstream>
using namespace compute_phase;
template<class F>static void rejects_at(unsigned line,F fn) {
  bool rejected=false;try{fn();}catch(const std::runtime_error &){rejected=true;}
  if(!rejected)throw std::runtime_error("invalid phase accepted at line "+std::to_string(line));
}
#define rejects(...) rejects_at(__LINE__,__VA_ARGS__)
static std::string raw(const std::filesystem::path &path) {
  std::ifstream in(path,std::ios::binary);require(bool(in),"missing actual C phase input");
  return {std::istreambuf_iterator<char>(in),std::istreambuf_iterator<char>()};
}
template<class Type>static Type parsed(const std::filesystem::path &path) {
  std::ifstream in(path,std::ios::binary);require(bool(in),"missing actual C phase control input");return Type::read(in);
}
static bool same(const Memory &a,const Memory &b) {
  if(a.pages!=b.pages || a.ranges.size()!=b.ranges.size())return false;
  for(size_t n=0;n<a.ranges.size();n++)if(a.ranges[n].start!=b.ranges[n].start ||
    a.ranges[n].end!=b.ranges[n].end || a.ranges[n].fill!=b.ranges[n].fill)return false;
  return true;
}
static void append_tests() {
  Memory live;live.ranges={{0x1000,0x2000,0xa5},{0x3000,0x4000,0x73}};
  live.byte(0x1ffc,2);live.byte(0x3000,0x99);Memory next;
  next.ranges={{0x1000,0x2000,0xa5},{0x5000,0x6000,0x6b}};
  next.pages.emplace(0x1000,live.pages.at(0x1000));
  const std::set<Extent> allowed={{0x1000,0x2000}};const auto initial=live;
  rejects([&]{append_retained(live,next,{});});require(same(live,initial),"rejected append mutated live RAM");
  for(unsigned bad=0;bad<7;bad++) {
    auto changed=next;
    if(bad==0)changed.byte(0x1ffc,3);
    if(bad==1)changed.pages.clear();
    if(bad==2)changed.ranges[0].fill^=1;
    if(bad==3)changed.ranges[0].end=0x3000;
    if(bad==4)changed.ranges[1]={0x1800,0x2800,0};
    if(bad==5)changed.pages.emplace(0x7000,std::array<uint8_t,4096>{});
    if(bad==6)changed.ranges.push_back({0x4000,0x5000,0});
    rejects([&]{append_retained(live,changed,allowed);});
    require(same(live,initial),"malformed retained append partially committed");
  }
  rejects([&]{live.append_disjoint(next);}); // Old recovery remains disjoint-only.
  append_retained(live,next,allowed);
  require(live.ranges.size()==3 && live.byte(0x1ffc)==2 && live.byte(0x3000)==0x99 &&
    live.byte(0x5000)==0x6b,"verified append replaced prior RAM");
  rejects([&]{append_retained(live,live,allowed);});
}
template<class Type>static void round(unsigned index,const std::filesystem::path &directory,
    Memory &live,PtePages &policy,std::set<Extent> &retained) {
  const auto capture=Type::read((directory/"record-0.capture").string());
  const auto frame=Memory::read((directory/"union.memory").string());
  const auto originals=Originals::read((directory/"originals.bytes").string());
  const uint32_t opcode=index==0 || index==4?0:14;
  if(index)append_retained(live,frame,retained);else live=frame;
  originals.verify(live,capture.command_id,opcode,index);
  rejects([&]{originals.verify(live,capture.command_id,opcode,index+1);});
  const auto bytes=raw(directory/"originals.bytes");
  for(unsigned bad=0;bad<5;bad++) {
    auto changed=bytes;
    if(bad==0)changed[0]^=1;
    if(bad==1)changed[20]=0;
    if(bad==2)changed[24]=0;
    if(bad==3)changed.pop_back();
    if(bad==4)changed+='x';
    rejects([&]{std::istringstream in(changed);Originals::read(in);});
  }
  PtePages pte=policy;capture.observe_contexts(live,pte);
  const auto lease=Lease::observe(originals,pte,live,capture.trace);
  require(pte.empty_roots.size()==1 && !pte.required_revocations.empty(),"lost original graphics/protected VMs");
  const auto returned=Originals::read((directory/"returned.bytes").string());
  // Explicit modeled output from the C CPU peer, never a shader evaluator.
  const auto before=live;
  for(const auto &o:returned.objects)for(size_t n=0;n<o.bytes.size();n++)live.byte(o.physical+n,o.bytes[n]);
  verify_readonly(before,live,index==2?std::vector<Writable>{}:capture.writable);
  std::ostringstream actual;originals.write_actual(actual,live);
  require(actual.str()==raw(directory/"returned.bytes"),"original byte transport changed actual RAM/identity");
  Memory expected=live;
  if(index==5) {
    for(unsigned n=0;;n++) {
      const auto path=directory/("cleanup-"+std::to_string(n)+".trace");if(!std::filesystem::exists(path))break;
      const auto batch=Cleanup::read(path.string());pte.validate_scope(batch,&live);
      for(const auto &a:batch.actions)if(a.kind==Kind::pte_clear){pte.clear(live,a);pte.clear(expected,a);}
      rejects([&]{Batch::read(path.string());});
    }
    pte.revoked(live);verify_readonly(expected,live,{});return;
  }
  const auto begin=parsed<Begin>(directory/"cleanup.begin");begin.bind(originals,2);
  const auto done=parsed<Done>(directory/"cleanup.done");
  uint32_t vector=0,instruction=0;unsigned publications=0;std::vector<Action> terminal;
  for(unsigned n=0;n<done.batches;n++) {
    const auto path=directory/("cleanup-"+std::to_string(n)+".trace");const auto batch=Batch::read(path.string());
    const auto scope=batch.revocations(originals,capture.writable);validate_scope(pte,scope,live);
    terminal=batch.actions;
    rejects([&]{Cleanup::read(path.string());});
    for(const auto &a:batch.actions) {
      if(a.kind==Kind::pte_clear){pte.clear(live,a);pte.clear(expected,a);}
      if(a.kind==Kind::write) {
        if(a.address==GPU_REG_UCMD_VECTOR_SATP)vector=a.value;
        if(a.address==GPU_REG_UCMD_INSTRUCTION_SATP)instruction=a.value;
        if(a.address==GPU_REG_UCMD_SUBMIT)++publications;
      }
    }
    for(unsigned bad=0;bad<5;bad++) {
      auto changed=batch;bool modified=false;
      for(auto &a:changed.actions) {
        if(bad==0 && a.kind==Kind::write && a.address==GPU_REG_UCMD_OPCODE){a.value=0;modified=true;}
        if(bad==1 && a.kind==Kind::write && a.address==GPU_REG_UCMD_SOURCE){a.value=0;modified=true;}
        if(bad==2 && a.kind==Kind::write && a.address==GPU_REG_UCMD_KERNEL_PC){a.value=4;modified=true;}
        if(bad==3 && completion_word(a.address)){a.value^=1;modified=true;}
        if(bad==4 && a.kind==Kind::write && a.address==GPU_REG_UCMD_COMPLETION_POP){a.value=0;modified=true;}
      }
      if(modified)rejects([&]{changed.revocations(originals,capture.writable);});
    }
    if(n==0) {
      auto changed=batch;changed.actions.insert(changed.actions.begin(),{Kind::write,GPU_REG_UCMD_RESET,1,0});
      rejects([&]{changed.revocations(originals,capture.writable);});
    }
  }
  require(publications==1 && vector==instruction && (vector&GPU_SATP_ENABLE) &&
    !((vector>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK) && pte.satps.count(vector),
    "phase lost real publication or original global MMU activation");
  done.verify(begin,done.batches,capture,pte,live,lease,terminal);verify_readonly(expected,live,{});
  auto bad=done;++bad.epoch;rejects([&]{bad.verify(begin,done.batches,capture,pte,live,lease,terminal);});
  bad=done;bad.empty_contexts.clear();rejects([&]{bad.verify(begin,done.batches,capture,pte,live,lease,terminal);});
  auto tail=terminal;tail.pop_back();rejects([&]{lease.retired(pte,live,tail);});
  tail=terminal;tail.back().value^=1u<<GPU_TLB_FLUSH_ASID_SHIFT;rejects([&]{lease.retired(pte,live,tail);});
  for(auto address:lease.private_ptes) {
    auto changed=live;const auto old=PtePages::word(before,address);
    for(unsigned n=0;n<4;n++)changed.byte(address+n,uint8_t(old>>(8*n)));
    rejects([&]{lease.retired(pte,changed,terminal);});
  }
  retained=originals.retained(live,pte);policy=global_policy(pte);
  require(policy.required_revocations.empty() && policy.empty_roots.empty() && policy.satps.size()==1,
    "retained policy carries a retired protected VM");
}
int main(int argc,char **argv) {
  try {
    require(argc==2,"actual C phase directory required");append_tests();
    Memory live;PtePages policy;std::set<Extent> retained;
    for(unsigned index=0;index<6;index++) {
      const auto directory=std::filesystem::path(argv[1])/("phase-"+std::to_string(index));
      try {
        if(index==0 || index==4)round<ComputeCapture>(index,directory,live,policy,retained);
        else round<IndirectCapture>(index,directory,live,policy,retained);
      }catch(const std::exception &e){throw std::runtime_error("phase "+std::to_string(index)+": "+e.what());}
    }
    std::cout<<"actual C phase publication/PTE/original byte and retained RAM policy: PASS (CPU only)\n";return 0;
  }catch(const std::exception &e){std::cerr<<e.what()<<'\n';return 1;}
}
