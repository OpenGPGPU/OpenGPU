#pragma once
// CPU-only canonical producer check. The executor consumes the original Packet
// and never substitutes this comparison program or regenerated maps.
#include "native_framebuffer_blit_input.hpp"
#include "../userspace/opengpu_framebuffer_blit.h"

namespace framebuffer_blit {
struct Input : Packet {
  opengpu_framebuffer_blit_plan plan{};
  static Input read(const std::string &path) {
    Input f;static_cast<Packet &>(f)=Packet::read(path);
    opengpu_framebuffer_blit descriptor;
    static_assert(sizeof(descriptor)==sizeof(f.descriptor),"producer descriptor ABI differs");
    std::memcpy(&descriptor,&f.descriptor,sizeof(descriptor));
    auto load=[](const auto &data,size_t offset) {
      require(offset+4<=data.size(),"short blit word");uint32_t value=0;
      for(unsigned b=0;b<4;b++)value|=uint32_t(data[offset+b])<<(8*b);return value;
    };
    const auto capacity=32+(size_t(descriptor.destination_width)+descriptor.destination_height)*4;
    require(capacity<=32+8*OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS,"unbounded blit map size");
    std::vector<uint32_t> maps(capacity,0);
    require(!opengpu_framebuffer_blit_plan_make(&descriptor,f.source.size(),f.destination.size(),
      &f.plan,maps.data(),maps.size()),"captured descriptor admission failed");
    require(f.plan.width==f.grid[0] && f.plan.height==f.grid[1] &&
      f.plan.source_bytes==f.views[0].limit && f.plan.destination_bytes==f.views[1].limit &&
      uint64_t(f.plan.map_words)*4==f.views[2].limit,"captured views/grid differ from descriptor");
    for(unsigned n=0;n<f.plan.map_words;n++)
      require(load(f.coordinates,n*4)==maps[n],"captured coordinate map differs");
    const auto program=opengpu_framebuffer_blit_program(&descriptor,&f.plan);
    require(!program.overflow && program.words>30 && program.words<=f.shader.size(),"blit program extent overflow");
    for(size_t n=0;n<f.shader.size();n++)
      require(f.shader[n]==(n<program.words?program.code[n]:0),"captured program differs from real caller");
    if(f.views[0].handle==f.views[1].handle) {
      require(descriptor.version>=2 && f.source==f.destination &&
        opengpu_framebuffer_blit_disjoint(&descriptor,&f.plan,maps.data()),"same-GEM ownership is not disjoint");
    }
    return f;
  }
};
} // namespace framebuffer_blit
