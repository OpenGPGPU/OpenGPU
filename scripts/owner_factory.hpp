#pragma once
#include "native_owner_capture.hpp"

namespace native_owner {
enum class OwnerFactory { v1, compute_stage, geometry_compute, geometry_compute_sample };
inline const char *owner_factory_id(OwnerFactory factory) {
  require(factory==OwnerFactory::v1 || factory==OwnerFactory::compute_stage ||
    factory==OwnerFactory::geometry_compute || factory==OwnerFactory::geometry_compute_sample,"unknown owner factory");
  return factory==OwnerFactory::v1 ? "two-cu-vga60-owner-v1" :
    factory==OwnerFactory::compute_stage ? "two-cu-vga60-owner-v2-compute-stage" :
    factory==OwnerFactory::geometry_compute ? "two-cu-vga60-owner-v2" : "two-cu-vga60-owner-v2-sample-stage";
}
inline std::vector<Command> owner_factory_words(OwnerFactory factory) {
  owner_factory_id(factory);
  auto words=Capture::profile();
  if(factory!=OwnerFactory::v1) {
    auto flags=std::find_if(words.begin(),words.end(),[](const auto &w){return w.address==0x368;});
    require(flags!=words.end() && flags->value==0x3ffff,"frozen owner factory changed");
    flags->value=0x7ffff;
  }
  if(factory==OwnerFactory::geometry_compute || factory==OwnerFactory::geometry_compute_sample) {
    for(auto &word:words) switch(word.address) {
      case 0x368:word.value=0xfffff;break;
      case 0x160:word.value=0x474e4305;break;
      case 0x168:word.value=0x474e4a02;break;
      case 0x17c:word.value=0x8813db02;break;
    }
    uint32_t address=0x500;
    for(uint32_t value:{0x47475301u,8u,31u,0x474e4a02u,0x474e4603u,0x474e5902u,0xff840206u,0x04001000u}) {
      words.push_back({address,value});address+=4;
    }
  }
  if(factory==OwnerFactory::geometry_compute_sample) {
    for(auto &word:words)if(word.address==0x368)word.value=0x1fffff;
    uint32_t address=0x520;
    for(uint32_t value:{0x47535301u,8u,1u,31u,0xc0u,0x4d041004u,0x7ffu,7u}) {
      words.push_back({address,value});address+=4;
    }
  }
  return words;
}
// Each executable has exactly one source-selected factory. Runtime discovery
// must match it; a v1 binary never accepts a shared-enabled model or capture.
inline constexpr OwnerFactory compute_owner_factory() {
#if defined(OPENGPU_OWNER_GEOMETRY_COMPUTE_SAMPLE)
  static_assert(OPENGPU_OWNER_GEOMETRY_COMPUTE_SAMPLE==1,"invalid geometry-compute-sample selection");
#if defined(OPENGPU_OWNER_COMPUTE_STAGE) || defined(OPENGPU_OWNER_GEOMETRY_COMPUTE)
#error owner factory selections are mutually exclusive
#endif
  return OwnerFactory::geometry_compute_sample;
#elif defined(OPENGPU_OWNER_GEOMETRY_COMPUTE)
  static_assert(OPENGPU_OWNER_GEOMETRY_COMPUTE==1,"invalid geometry-compute selection");
#ifdef OPENGPU_OWNER_COMPUTE_STAGE
#error owner factory selections are mutually exclusive
#endif
  return OwnerFactory::geometry_compute;
#elif defined(OPENGPU_OWNER_COMPUTE_STAGE)
  static_assert(OPENGPU_OWNER_COMPUTE_STAGE==1,"invalid compute-stage selection");
  return OwnerFactory::compute_stage;
#else
  return OwnerFactory::v1;
#endif
}
inline constexpr const char *compute_capture_magic() {
  return compute_owner_factory()==OwnerFactory::geometry_compute ||
    compute_owner_factory()==OwnerFactory::geometry_compute_sample ? "OGCMP002" : "OGCMP001";
}
} // namespace native_owner
