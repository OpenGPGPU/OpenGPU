#pragma once
// Uncached shared→SSBO publication fabric witness for continuous
// warm→producer→reload. Shared SRAM stays private to the producer workgroup;
// publication lands in an original SSBO whose word path bypasses private L1/L2.
// The complete-owner observation therefore accounts exact lower AW/AR traffic
// on the retained data line. Full workgroup shared publication remains the
// separate GALLIUM_SHARED_PUBLICATION component contract.
namespace compute_owner {
#ifndef OPENGPU_NATIVE_CACHE_STATE
inline void require_shared_publication_cache_support() {
  require(false,"shared publication cache observation requires bound complete-float Verilator cache headers");
}
#else
inline void require_shared_publication_cache_support() {}

struct SharedPublicationCacheTracker {
  using Snapshot=compute_cache_alias::Snapshot;
  uint32_t target=0;
  unsigned phase=0;
  bool seen=false;
  uint64_t last=0,warm_read=0,write_cycle=0,reload_read=0,reload_response=0;
  uint64_t warm_response=0;
  bool writing=false;
  uint32_t line() const {return target;}
  void begin_phase(unsigned index) {phase=index;}
  void observe(uint64_t cycle,const Snapshot &s) {
    require(!seen || cycle>last,"publication cache observation clock repeated");
    require(!((s.l1_mask|s.invalidate_mask|s.ack_mask)&~3u),"publication cache lost the two-CU shape");
    seen=true;last=cycle;
    if(phase==0) {
      if(s.ar.fire && (s.ar.address&~uint64_t(63))==target && !warm_read)
        warm_read=cycle;
      if(warm_read && s.r.fire && !warm_response)warm_response=cycle;
    }
    if(phase==1 && warm_response) {
      if(s.aw.fire && (s.aw.address&~uint64_t(63))==target && !writing) {
        writing=true;write_cycle=cycle;
      }
    }
    if(phase==2 && writing) {
      if(s.ar.fire && (s.ar.address&~uint64_t(63))==target && !reload_read)
        reload_read=cycle;
      if(reload_read && s.r.fire && !reload_response)reload_response=cycle;
    }
  }
  void finish(uint64_t start_cycle,uint64_t end_cycle) const {
    require(start_cycle<warm_read && warm_read<warm_response,
      "publication warm phase never read the retained data line on the fabric");
    require(warm_response<write_cycle && writing,
      "publication producer never wrote the retained data line on the fabric");
    require(write_cycle<reload_read && reload_read<reload_response && reload_response<end_cycle,
      "publication reload never re-read the retained data line on the fabric");
    require(last<end_cycle,"publication fabric observation left its command clock window");
  }
  void json(std::ostream &out,uint32_t command_id,uint64_t start_cycle,uint64_t end_cycle) const {
    finish(start_cycle,end_cycle);
    out<<"{\"schema\":\"opengpu-compute-shared-publication-cache-observation-v1\",\"commandId\":"<<command_id
      <<",\"startCycle\":"<<start_cycle<<",\"endCycle\":"<<end_cycle
      <<",\"targetLine\":"<<target
      <<",\"warmReadRequestCycle\":"<<warm_read<<",\"warmReadResponseCycle\":"<<warm_response
      <<",\"firstTargetWriteCycle\":"<<write_cycle
      <<",\"reloadReadRequestCycle\":"<<reload_read<<",\"reloadReadResponseCycle\":"<<reload_response
      <<",\"uncachedSharedPublicationPath\":true,\"ordinaryL1AliasRequired\":false}\n";
  }
};
#endif
} // namespace compute_owner
