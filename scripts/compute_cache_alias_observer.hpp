#pragma once
// Read-only observations of the complete two-CU factory. Source arithmetic
// remains in the independent Python oracle; no RTL data values are consumed.
#include <array>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <ostream>
#include <stdexcept>
#include <type_traits>
#include <utility>

namespace compute_cache_alias {
inline void check(bool value,const char *message) {
  if(!value)throw std::runtime_error(message);
}
struct Transfer {
  bool fire=false;
  uint32_t id=0;
  uint64_t address=0;
  uint8_t response=0;
  bool last=false;
  uint8_t length=0,size=0;
};
struct Snapshot {
  uint8_t l1_mask=0,invalidate_mask=0,ack_mask=0;
  bool l2=false;
  Transfer aw,b,ar,r;
};

#define OGPU_CACHE_INDICES(X) \
  X(0),X(1),X(2),X(3),X(4),X(5),X(6),X(7),X(8),X(9),X(10),X(11),X(12),X(13),X(14),X(15), \
  X(16),X(17),X(18),X(19),X(20),X(21),X(22),X(23),X(24),X(25),X(26),X(27),X(28),X(29),X(30),X(31), \
  X(32),X(33),X(34),X(35),X(36),X(37),X(38),X(39),X(40),X(41),X(42),X(43),X(44),X(45),X(46),X(47), \
  X(48),X(49),X(50),X(51),X(52),X(53),X(54),X(55),X(56),X(57),X(58),X(59),X(60),X(61),X(62),X(63)
#define OGPU_CACHE_VALID_0(N) &Root::GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__valid_0_##N
#define OGPU_CACHE_VALID_1(N) &Root::GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__valid_1_##N
template<class Root>
bool l1_present(const Root &root,uint32_t physical) {
  static_assert(sizeof(root.GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__tags_0_ext__DOT__Memory)==64*sizeof(uint32_t),
    "cache observer requires all 64 L1 sets in both ways");
  static_assert(sizeof(root.GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__tags_1_ext__DOT__Memory)==64*sizeof(uint32_t),
    "cache observer requires all 64 L1 sets in both ways");
  using Valid=decltype(Root::GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__valid_0_0);
  static const std::array<Valid Root::*,64> valid0{{OGPU_CACHE_INDICES(OGPU_CACHE_VALID_0)}};
  static const std::array<Valid Root::*,64> valid1{{OGPU_CACHE_INDICES(OGPU_CACHE_VALID_1)}};
  const auto index=(physical>>6)&63u,tag=physical>>12;
  return ((root.*valid0[index]) &&
      (root.GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__tags_0_ext__DOT__Memory[index]&0xfffffu)==tag) ||
    ((root.*valid1[index]) &&
      (root.GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__tags_1_ext__DOT__Memory[index]&0xfffffu)==tag);
}
#undef OGPU_CACHE_VALID_0
#undef OGPU_CACHE_VALID_1
#undef OGPU_CACHE_INDICES

#define OGPU_L2_VALID(B,W) GpuHostSystemAxi__DOT__system__DOT__l2__DOT__slices_##B##__DOT__valid_##W
#define OGPU_L2_TAG(B,W) GpuHostSystemAxi__DOT__system__DOT__l2__DOT__slices_##B##__DOT__tags_##W##__DOT__memory__DOT__mem
#define OGPU_L2_MATCH(B,W) \
  static_assert(sizeof(root.OGPU_L2_VALID(B,W))==4*sizeof(uint32_t),"full 128-set L2 valid map required"); \
  static_assert(sizeof(root.OGPU_L2_TAG(B,W))==256*sizeof(uint32_t),"characterized 256-row tag macro required"); \
  if(bank==B && ((root.OGPU_L2_VALID(B,W)[index>>5]>>(index&31u))&1u) && \
      (root.OGPU_L2_TAG(B,W)[index]&0x7ffffu)==tag)present=true;
template<class Root>
bool l2_present(const Root &root,uint32_t physical) {
  // selectBank preserves each slice's original index/tag decomposition.
  const auto bank=((physical>>13)^(physical>>6))&3u,index=(physical>>6)&127u,tag=physical>>13;
  bool present=false;
  OGPU_L2_MATCH(0,0) OGPU_L2_MATCH(0,1) OGPU_L2_MATCH(0,2) OGPU_L2_MATCH(0,3)
  OGPU_L2_MATCH(1,0) OGPU_L2_MATCH(1,1) OGPU_L2_MATCH(1,2) OGPU_L2_MATCH(1,3)
  OGPU_L2_MATCH(2,0) OGPU_L2_MATCH(2,1) OGPU_L2_MATCH(2,2) OGPU_L2_MATCH(2,3)
  OGPU_L2_MATCH(3,0) OGPU_L2_MATCH(3,1) OGPU_L2_MATCH(3,2) OGPU_L2_MATCH(3,3)
  return present;
}
#undef OGPU_L2_MATCH
#undef OGPU_L2_TAG
#undef OGPU_L2_VALID

template<class Top,class Cu>
class View {
  const Top &top;
  std::array<const Cu*,2> units;
public:
  explicit View(const Top &model):top(model),units{{nullptr,nullptr}} {
    check(top.rootp,"cache observer has no complete top root");
    static_assert(sizeof(uintptr_t)==8,"bound hierarchical handles require a 64-bit host");
    units={{reinterpret_cast<const Cu*>(uintptr_t(top.rootp->GpuHostSystemAxi__DOT__system__DOT__computeUnits_0__DOT__handle___05FV)),
      reinterpret_cast<const Cu*>(uintptr_t(top.rootp->GpuHostSystemAxi__DOT__system__DOT__computeUnits_1__DOT__handle___05FV))}};
    check(units[0] && units[1] && units[0]!=units[1],"cache observer requires both actual CU handles");
    check(units[0]->rootp && units[1]->rootp && units[0]->rootp!=units[1]->rootp,
      "cache observer requires independent full CU roots");
    for(const auto *cu:units)check(cu->contextp()==top.contextp() && !std::strcmp(cu->modelName(),"VGpuComputeUnit"),
      "cache observer CU handle belongs to another model/context");
  }
  Snapshot read(uint32_t line) const {
    check(line && !(line&63u),"cache observer requires an aligned physical line");
    Snapshot out;
    for(unsigned index=0;index<units.size();++index) {
      const auto &cu=*units[index];
      if(l1_present(*cu.rootp,line))out.l1_mask|=uint8_t(1u<<index);
      if(cu.io_l1Invalidate_valid && cu.io_l1Invalidate_ready && cu.io_l1Invalidate_bits_lineAddress==line)
        out.invalidate_mask|=uint8_t(1u<<index);
      if(cu.io_l1InvalidateDone_valid && cu.io_l1InvalidateDone_ready && cu.io_l1InvalidateDone_bits_lineAddress==line)
        out.ack_mask|=uint8_t(1u<<index);
    }
    out.l2=l2_present(*top.rootp,line);
    out.aw={bool(top.io_m_axi_awvalid && top.io_m_axi_awready),uint32_t(top.io_m_axi_awid),uint64_t(top.io_m_axi_awaddr),0,false,
      uint8_t(top.io_m_axi_awlen),uint8_t(top.io_m_axi_awsize)};
    out.b={bool(top.io_m_axi_bvalid && top.io_m_axi_bready),uint32_t(top.io_m_axi_bid),0,uint8_t(top.io_m_axi_bresp),false};
    out.ar={bool(top.io_m_axi_arvalid && top.io_m_axi_arready),uint32_t(top.io_m_axi_arid),uint64_t(top.io_m_axi_araddr),0,false,
      uint8_t(top.io_m_axi_arlen),uint8_t(top.io_m_axi_arsize)};
    out.r={bool(top.io_m_axi_rvalid && top.io_m_axi_rready),uint32_t(top.io_m_axi_rid),0,uint8_t(top.io_m_axi_rresp),bool(top.io_m_axi_rlast)};
    return out;
  }
};

class Tracker {
  struct Write {bool target;uint64_t ordinal;};
  struct Read {bool refetch;uint64_t uncached_request;};
  uint32_t target;
  bool fault,uncached,seen_cycle=false,warm=false,invalidating=false,writing=false,error=false;
  uint64_t last=0,warm_cycle=0,inv_cycle=0,ack_cycle=0,write_cycle=0,error_cycle=0;
  uint64_t invalid_cycle=0,read_cycle=0,response_cycle=0,refill_cycle=0;
  uint64_t writes=0,responses=0,errors=0;
  uint64_t pre_atomic_invalid=0,authoritative_request=0,authoritative_response=0;
  uint64_t candidate_request=0,candidate_response=0,last_target_response=0;
  uint64_t uncached_reads=0,nonallocating_responses=0,nonallocating_retired=0,check_retired=0;
  uint8_t invalidated=0,acked=0,refilled=0;
  bool refilled_l2=false;
  std::map<uint32_t,std::deque<Write>> outstanding_writes;
  std::map<uint32_t,std::deque<Read>> outstanding_reads;
public:
  Tracker(uint64_t line,bool accepted_error,bool uncached_alias=false):
      target(uint32_t(line)),fault(accepted_error),uncached(uncached_alias) {
    check(line && line<=0xffffffc0u && !(line&63u),"cache observer target must be a complete physical u32 line");
  }
  uint32_t line() const {return target;}
  void observe(uint64_t cycle,const Snapshot &s) {
    check(!seen_cycle || cycle>last,"cache observation clock repeated or restarted");
    check(!((s.l1_mask|s.invalidate_mask|s.ack_mask)&~3u),"cache observation lost the two-CU shape");
    seen_cycle=true;last=cycle;
    if(uncached) {
      if(check_retired) {
        check(cycle>check_retired && !s.l1_mask && !s.l2,
          "UC target write allocated a cache line on retirement");
        ++nonallocating_retired;check_retired=0;
      }
      // A cached refill cannot authorize an uncached RMW. Its own lower
      // read must complete after the preceding target write response, with
      // the line absent until the new write is offered.
      if(s.l2 || s.l1_mask)candidate_request=candidate_response=0;
      if(warm && !invalidating && !s.l2 && s.l1_mask==3 && !pre_atomic_invalid)
        pre_atomic_invalid=cycle;
    }
    if(!writing && !warm && s.l1_mask==3 && s.l2) {warm=true;warm_cycle=cycle;}
    if(!writing && warm && s.invalidate_mask) {
      if(!invalidating) {
        if(uncached)check(s.l1_mask==3 && !s.l2 && warm_cycle<pre_atomic_invalid && pre_atomic_invalid<=cycle,
          "first UC target invalidation lacks prior two-CU/L2 warmth and the early L2 drop");
        else check(s.l1_mask==3 && s.l2,"first target invalidation did not observe both warm L1s and L2");
        invalidating=true;inv_cycle=cycle;
      }
      invalidated|=s.invalidate_mask;
    }
    // Prefix host maintenance may invalidate this same allocation while it
    // is cold. Its acknowledgements cannot satisfy the first warmed AMO.
    if(!writing && invalidating && cycle>inv_cycle && s.ack_mask) {
      check(!(s.ack_mask&~invalidated),"target invalidate ACK preceded its observed request");
      acked|=s.ack_mask;ack_cycle=cycle;
    }
    if(s.aw.fire) {
      const bool match=(s.aw.address&~uint64_t(63))==target;
      if(match) {
        check(s.aw.address==target && s.aw.length==1 && s.aw.size==5,"target lower write lost the original full-line transfer");
        check(!s.l1_mask,"a target lower write preceded actual L1 invalidation");
        if(uncached) {
          check(!s.l2 && candidate_request>last_target_response && candidate_request<candidate_response &&
            candidate_response<cycle,"UC target write lacks its own nonallocating authoritative lower read");
          if(!uncached_reads) {
            authoritative_request=candidate_request;authoritative_response=candidate_response;
          }
          ++uncached_reads;candidate_request=candidate_response=0;
        }
        if(!writing) {
          check(warm && invalidating && invalidated==3 && acked==3 && ack_cycle<cycle,
            "first target write lacks both warm caches and completed invalidate ACKs");
          writing=true;write_cycle=cycle;
        }
        ++writes;
      }
      outstanding_writes[s.aw.id].push_back({match,match?writes:0});
    }
    if(s.b.fire) {
      auto found=outstanding_writes.find(s.b.id);
      check(found!=outstanding_writes.end() && !found->second.empty(),"cache observer received an unmatched B response");
      const auto transaction=found->second.front();found->second.pop_front();
      if(found->second.empty())outstanding_writes.erase(found);
      if(transaction.target) {
        ++responses;
        if(uncached) {
          check(!s.l1_mask && !s.l2,"UC target B response retained or allocated a cached alias");
          ++nonallocating_responses;last_target_response=cycle;check_retired=cycle;
        }
      }
      if(s.b.response) {
        check(fault && transaction.target && transaction.ordinal==1 && s.b.response==2 && !error,
          "cache observation has a foreign or repeated write error");
        error=true;++errors;error_cycle=cycle;
      }
    }
    if(error && cycle>error_cycle && !s.l2 && !invalid_cycle)invalid_cycle=cycle;
    if(s.ar.fire) {
      const bool match=(s.ar.address&~uint64_t(63))==target;
      const bool refetch=match && error && cycle>error_cycle;
      const bool selected=refetch && !read_cycle;
      if(selected) {
        check(s.ar.address==target && s.ar.length==1 && s.ar.size==5,"target lower refetch lost the original full-line transfer");
        check(!s.l2 && invalid_cycle,"post-error target refetch did not observe the invalidated L2 line");
        read_cycle=cycle;
      }
      uint64_t uc_request=0;
      if(uncached && match && acked==3 && cycle>ack_cycle && cycle>last_target_response && !s.l1_mask && !s.l2) {
        check(s.ar.address==target && s.ar.length==1 && s.ar.size==5,
          "UC authoritative read lost the original full-line transfer");
        uc_request=cycle;
      }
      outstanding_reads[s.ar.id].push_back({selected,uc_request});
    }
    if(s.r.fire) {
      auto found=outstanding_reads.find(s.r.id);
      check(found!=outstanding_reads.end() && !found->second.empty() && !s.r.response,
        "cache observer received an unmatched or failed R response");
      if(s.r.last) {
        const auto request=found->second.front();
        if(request.refetch && !response_cycle)response_cycle=cycle;
        if(uncached && request.uncached_request>last_target_response && !s.l1_mask && !s.l2) {
          candidate_request=request.uncached_request;candidate_response=cycle;
        }
        found->second.pop_front();if(found->second.empty())outstanding_reads.erase(found);
      }
    }
    if(writing && (!fault || (response_cycle && cycle>response_cycle)) && s.l1_mask && s.l2) {
      refilled|=s.l1_mask;refilled_l2=true;if(!refill_cycle)refill_cycle=cycle;
    }
  }
  void finish(uint64_t target_attempts) const {
    check(warm && invalidating && writing && invalidated==3 && acked==3,
      "cache observation lacks actual two-CU/L2 warmth and invalidation");
    check(writes==target_attempts && responses==writes && outstanding_writes.empty() && outstanding_reads.empty(),
      "cache observation target attempts or external drain differ");
    check(fault?(target_attempts==3 || target_attempts==5 || target_attempts==7):target_attempts==8,
      "cache observation does not retain the original caller AMOs");
    check(refilled_l2 && (fault?refilled!=0:refilled==3),"ordinary source reads did not refill the required L1/L2 caches");
    if(uncached)check(warm_cycle<pre_atomic_invalid && pre_atomic_invalid<=inv_cycle &&
      ack_cycle<authoritative_request && authoritative_request<authoritative_response && authoritative_response<write_cycle &&
      uncached_reads==writes && nonallocating_responses==writes && nonallocating_retired==writes && !check_retired,
      "UC cache observation lacks authoritative reads or nonallocating write retirement");
    if(fault)check(error && errors==1 && write_cycle<error_cycle && error_cycle<invalid_cycle &&
        invalid_cycle<=read_cycle && read_cycle<response_cycle && response_cycle<refill_cycle,
      "committed-write error lacks an invalidated L2, real lower refetch and subsequent cache refill");
    else check(!error && !errors,"healthy cache observation acquired a write error");
  }
  void json(std::ostream &out,uint32_t command_id,unsigned phase,uint64_t target_attempts,
      uint64_t start_cycle,uint64_t end_cycle) const {
    finish(target_attempts);
    check(start_cycle<warm_cycle && last<end_cycle,"cache observation left its original command clock window");
    out<<"{\"schema\":\""<<(uncached?"opengpu-compute-uc-cache-alias-observation-v1":
      "opengpu-compute-cache-alias-observation-v1")<<"\",\"commandId\":"<<command_id
      <<",\"startCycle\":"<<start_cycle<<",\"endCycle\":"<<end_cycle
      <<",\"phase\":"<<phase<<",\"targetLine\":"<<target<<",\"acceptedWriteError\":"<<(fault?"true":"false")
      <<",\"warmL1Mask\":3,\"warmL2\":true,\"invalidateMask\":"<<unsigned(invalidated)
      <<",\"invalidateAckMask\":"<<unsigned(acked)<<",\"targetWrites\":"<<writes
      <<",\"targetWriteResponses\":"<<responses<<",\"targetWriteErrors\":"<<errors
      <<",\"refetchedL1Mask\":"<<unsigned(refilled)<<",\"refetchedL2\":"<<(refilled_l2?"true":"false")
      <<",\"warmCycle\":"<<warm_cycle<<",\"invalidateCycle\":"<<inv_cycle
      <<",\"invalidateAckCycle\":"<<ack_cycle<<",\"firstTargetWriteCycle\":"<<write_cycle
      <<",\"writeErrorCycle\":"<<error_cycle<<",\"l2InvalidatedCycle\":"<<invalid_cycle
      <<",\"refetchRequestCycle\":"<<read_cycle<<",\"refetchResponseCycle\":"<<response_cycle
      <<",\"refillCycle\":"<<refill_cycle;
    if(uncached)out<<",\"preAtomicL2InvalidatedCycle\":"<<pre_atomic_invalid
      <<",\"firstAuthoritativeReadRequestCycle\":"<<authoritative_request
      <<",\"firstAuthoritativeReadResponseCycle\":"<<authoritative_response
      <<",\"uncachedReadBeforeWrites\":"<<uncached_reads
      <<",\"nonAllocatingWriteResponses\":"<<nonallocating_responses
      <<",\"nonAllocatingRetiredWrites\":"<<nonallocating_retired;
    out<<"}\n";
  }
};

template<class Base,class CacheView,class Observer=Tracker>
class ObservedSimulation:public Base {
  Observer *tracker=nullptr;
  std::unique_ptr<CacheView> view;
public:
  using Base::Base;
  void attach(Observer &value) {
    check(!tracker,"a cache observation is already active");
    view=std::make_unique<CacheView>(*this->model);tracker=&value;
  }
  void detach() {tracker=nullptr;view.reset();}
  template<class Agent> void step(Agent &agent,uint64_t cycle) {
    step(agent,cycle,[](const auto &){});
  }
  template<class Agent,class ControlObserver>
  void step(Agent &agent,uint64_t cycle,ControlObserver control) {
    Base::step(agent,cycle,[&](const auto &model) {
      if(tracker)tracker->observe(cycle,view->read(tracker->line()));
      control(model);
    });
  }
};
} // namespace compute_cache_alias
