#pragma once
#include "native_owner_capture.hpp"

namespace native_owner {
enum class OwnerFactory { v1, compute_stage, geometry_compute, geometry_compute_sample, image_float_stage };
inline constexpr bool owner_factory_has_sample(OwnerFactory factory) {
  return factory==OwnerFactory::geometry_compute_sample || factory==OwnerFactory::image_float_stage;
}
inline const char *owner_factory_id(OwnerFactory factory) {
  require(factory==OwnerFactory::v1 || factory==OwnerFactory::compute_stage ||
    factory==OwnerFactory::geometry_compute || owner_factory_has_sample(factory),"unknown owner factory");
  return factory==OwnerFactory::v1 ? "two-cu-vga60-owner-v1" :
    factory==OwnerFactory::compute_stage ? "two-cu-vga60-owner-v2-compute-stage" :
    factory==OwnerFactory::geometry_compute ? "two-cu-vga60-owner-v2" :
    factory==OwnerFactory::geometry_compute_sample ? "two-cu-vga60-owner-v2-sample-stage" :
      "two-cu-vga60-owner-v2-image-float-stage";
}
inline const char *owner_factory_stage(OwnerFactory factory) {
  owner_factory_id(factory);require(factory!=OwnerFactory::v1,"frozen v1 has no resource stage");
  return factory==OwnerFactory::compute_stage ? "compute" :
    factory==OwnerFactory::geometry_compute ? "geometry-compute" :
    factory==OwnerFactory::geometry_compute_sample ? "geometry-compute-sample" :
      "geometry-compute-sample-image-float";
}
inline std::vector<Command> owner_factory_words(OwnerFactory factory) {
  owner_factory_id(factory);
  auto words=Capture::profile();
  if(factory!=OwnerFactory::v1) {
    auto flags=std::find_if(words.begin(),words.end(),[](const auto &w){return w.address==0x368;});
    require(flags!=words.end() && flags->value==0x3ffff,"frozen owner factory changed");
    flags->value=0x7ffff;
  }
  if(factory==OwnerFactory::geometry_compute || owner_factory_has_sample(factory)) {
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
  if(owner_factory_has_sample(factory)) {
    for(auto &word:words)if(word.address==0x368)word.value=0x1fffff;
    uint32_t address=0x520;
    for(uint32_t value:{0x47535301u,8u,1u,31u,0xc0u,0x4d041004u,0x7ffu,7u}) {
      words.push_back({address,value});address+=4;
    }
  }
  if(factory==OwnerFactory::image_float_stage)
    for(auto &word:words)if(word.address==0x368)word.value=0x3fffff;
  return words;
}
// Each executable has exactly one source-selected factory. Runtime discovery
// must match it; a v1 binary never accepts a shared-enabled model or capture.
inline constexpr OwnerFactory compute_owner_factory() {
#if (defined(OPENGPU_OWNER_IMAGE_FLOAT_STAGE) + defined(OPENGPU_OWNER_GEOMETRY_COMPUTE_SAMPLE) + defined(OPENGPU_OWNER_GEOMETRY_COMPUTE) + defined(OPENGPU_OWNER_COMPUTE_STAGE)) > 1
#error owner factory selections are mutually exclusive
#endif
#if defined(OPENGPU_OWNER_IMAGE_FLOAT_STAGE)
  static_assert(OPENGPU_OWNER_IMAGE_FLOAT_STAGE==1,"invalid image-float-stage selection");
  return OwnerFactory::image_float_stage;
#elif defined(OPENGPU_OWNER_GEOMETRY_COMPUTE_SAMPLE)
  static_assert(OPENGPU_OWNER_GEOMETRY_COMPUTE_SAMPLE==1,"invalid geometry-compute-sample selection");
  return OwnerFactory::geometry_compute_sample;
#elif defined(OPENGPU_OWNER_GEOMETRY_COMPUTE)
  static_assert(OPENGPU_OWNER_GEOMETRY_COMPUTE==1,"invalid geometry-compute selection");
  return OwnerFactory::geometry_compute;
#elif defined(OPENGPU_OWNER_COMPUTE_STAGE)
  static_assert(OPENGPU_OWNER_COMPUTE_STAGE==1,"invalid compute-stage selection");
  return OwnerFactory::compute_stage;
#else
  return OwnerFactory::v1;
#endif
}
inline OwnerFactory sample_owner_factory() {
  const auto selected=compute_owner_factory();
  require(selected==OwnerFactory::v1 || owner_factory_has_sample(selected),"not a complete sample factory");
  return selected==OwnerFactory::v1 ? OwnerFactory::geometry_compute_sample : selected;
}
inline constexpr const char *compute_capture_magic() {
  return compute_owner_factory()==OwnerFactory::geometry_compute ||
    owner_factory_has_sample(compute_owner_factory()) ? "OGCMP002" : "OGCMP001";
}
} // namespace native_owner
