#pragma once
// Cross-command ordinary L1 cached-alias publication witness.  A warmed
// cached vector alias must be invalidated (with ACK) before the checked UC
// producer writes the retained line, and the ordinary reload must refill it.
namespace compute_owner {
#ifndef OPENGPU_NATIVE_CACHE_STATE
inline void require_l1_publication_cache_support() {
  require(false,"L1 publication cache observation requires bound complete-float Verilator cache headers");
}
#else
inline void require_l1_publication_cache_support() {}

struct L1PublicationCacheTracker {
  using Snapshot=compute_cache_alias::Snapshot;
  uint32_t target=0;
  unsigned phase=0;
  bool seen=false;
  uint64_t last=0;
  uint64_t warm_read=0,warm_response=0,l2_drop=0,invalidate=0,ack=0;
  uint64_t write_cycle=0,reload_read=0,reload_response=0,refill=0;
  uint32_t warm_l1_mask=0,invalidate_mask=0,ack_mask=0,refilled_l1_mask=0;
  bool warm_l2=false,l2_dropped=false,warm=false,writing=false,refilled_l2=false;
  uint32_t line() const {return target;}
  void begin_phase(unsigned index) {
    phase=index;
    if(index==1) {
      require(warm && warm_l1_mask==3 && warm_l2,
        "L1 publication producer started without both warmed cached aliases");
    }
  }
  void observe(uint64_t cycle,const Snapshot &s) {
    require(!seen || cycle>last,"L1 publication cache observation clock repeated");
    require(!((s.l1_mask|s.invalidate_mask|s.ack_mask)&~3u),"L1 publication cache lost the two-CU shape");
    seen=true;last=cycle;
    if(phase==0) {
      if(s.ar.fire && (s.ar.address&~uint64_t(63))==target && !warm_read)warm_read=cycle;
      if(warm_read && s.r.fire && !warm_response)warm_response=cycle;
      if(s.l1_mask==3 && s.l2 && warm_response) {
        warm=true;warm_l1_mask=3;warm_l2=true;
      }
    }
    if(phase==1 && warm) {
      if(s.l2 && !l2_dropped) {/* still warm */}
      if(!s.l2 && !l2_dropped && warm_l2) {l2_dropped=true;l2_drop=cycle;}
      if(l2_dropped) {
        invalidate_mask|=s.invalidate_mask;
        if(s.invalidate_mask && !invalidate)invalidate=cycle;
        require(!(s.ack_mask&~invalidate_mask),"L1 publication ACK preceded cached-alias invalidation");
        if(s.ack_mask & ~ack_mask)ack=cycle;
        ack_mask|=s.ack_mask;
      }
      if(!writing && invalidate_mask==3 && ack_mask==3 && ack && !s.l1_mask &&
         s.aw.fire && (s.aw.address&~uint64_t(63))==target) {
        writing=true;write_cycle=cycle;
      }
    }
    if(phase==2 && writing) {
      if(s.ar.fire && (s.ar.address&~uint64_t(63))==target && !reload_read) {
        require(!s.l1_mask && !s.l2,
          "L1 publication reload reused a stale cached alias");
        reload_read=cycle;
      }
      if(reload_read && s.r.fire && !reload_response)reload_response=cycle;
      if(reload_response && s.l1_mask) {
        refilled_l1_mask|=s.l1_mask;
        if(s.l2)refilled_l2=true;
        if(refilled_l1_mask==3 && refilled_l2 && !refill)refill=cycle;
      }
    }
  }
  void finish(uint64_t start_cycle,uint64_t end_cycle) const {
    require(warm && warm_l1_mask==3 && warm_l2 &&
      start_cycle<warm_read && warm_read<warm_response,
      "L1 publication warm phase never warmed both ordinary cached aliases");
    require(l2_dropped && warm_response<=l2_drop && l2_drop<=invalidate &&
      invalidate<ack && ack<write_cycle && writing,
      "L1 publication producer wrote before cached aliases invalidated and acknowledged");
    require(write_cycle<reload_read && reload_read<reload_response &&
      reload_response<=refill && refill<end_cycle,
      "L1 publication reload never refilled both ordinary cached aliases");
    require(last<end_cycle,"L1 publication observation left its command clock window");
  }
  void json(std::ostream &out,uint32_t command_id,unsigned index,
            uint64_t start_cycle,uint64_t end_cycle) const {
    finish(start_cycle,end_cycle);
    out<<"{\"schema\":\"opengpu-compute-l1-publication-cache-observation-v1\",\"commandId\":"<<command_id
      <<",\"phase\":"<<index<<",\"startCycle\":"<<start_cycle<<",\"endCycle\":"<<end_cycle
      <<",\"targetLine\":"<<target
      <<",\"warmL1Mask\":"<<warm_l1_mask<<",\"warmL2\":"<<(warm_l2?"true":"false")
      <<",\"warmCycle\":"<<warm_response<<",\"l2DropCycle\":"<<l2_drop
      <<",\"invalidateMask\":"<<invalidate_mask<<",\"invalidateAckMask\":"<<ack_mask
      <<",\"invalidateCycle\":"<<invalidate<<",\"invalidateAckCycle\":"<<ack
      <<",\"firstTargetWriteCycle\":"<<write_cycle
      <<",\"reloadReadRequestCycle\":"<<reload_read<<",\"reloadReadResponseCycle\":"<<reload_response
      <<",\"refilledL1Mask\":"<<refilled_l1_mask<<",\"refilledL2\":"<<(refilled_l2?"true":"false")
      <<",\"refillCycle\":"<<refill
      <<",\"ordinaryL1AliasRequired\":true,\"uncachedStoragePath\":true}\n";
  }
};
#endif
} // namespace compute_owner
