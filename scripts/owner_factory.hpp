#pragma once
#include "native_owner_capture.hpp"

namespace native_owner {
enum class OwnerFactory { v1, compute_stage };
inline const char *owner_factory_id(OwnerFactory factory) {
  require(factory==OwnerFactory::v1 || factory==OwnerFactory::compute_stage,"unknown owner factory");
  return factory==OwnerFactory::v1 ? "two-cu-vga60-owner-v1" : "two-cu-vga60-owner-v2-compute-stage";
}
inline std::vector<Command> owner_factory_words(OwnerFactory factory) {
  owner_factory_id(factory);
  auto words=Capture::profile();
  if(factory==OwnerFactory::compute_stage) {
    auto flags=std::find_if(words.begin(),words.end(),[](const auto &w){return w.address==0x368;});
    require(flags!=words.end() && flags->value==0x3ffff,"frozen owner factory changed");
    flags->value=0x7ffff;
  }
  return words;
}
// Each executable has exactly one source-selected factory. Runtime discovery
// must match it; a v1 binary never accepts a shared-enabled model or capture.
inline constexpr OwnerFactory compute_owner_factory() {
#ifdef OPENGPU_OWNER_COMPUTE_STAGE
  static_assert(OPENGPU_OWNER_COMPUTE_STAGE==1,"invalid compute-stage selection");
  return OwnerFactory::compute_stage;
#else
  return OwnerFactory::v1;
#endif
}
} // namespace native_owner
