#pragma once
// GNJ2 sample-owner transport. The fixed entry never accepts native-v1 captures.
#include "owner_factory.hpp"
#include <array>

namespace native_owner {
struct SampleCapture : Capture {
  static std::vector<Command> profile() {
    return owner_factory_words(OwnerFactory::geometry_compute_sample);
  }
  static constexpr std::array<uint32_t,5> auxiliary{{
    GPU_REG_ID,GPU_REG_CAPABILITIES,GPU_REG_BIN_CAPABILITY_PTR,0x320,GPU_REG_SHADER_CONFIG}};
  static void validate_discovery(const std::vector<Command> &words) {
    const auto expected=profile();
    require(expected.size()==112 && words.size()==117,"sample capture requires 112 discovery and five actual auxiliary words");
    for(size_t n=0;n<expected.size();++n)
      require(words[n].address==expected[n].address && words[n].value==expected[n].value,
        "sample capture differs from its exact ordered factory");
    for(size_t n=0;n<auxiliary.size();++n)
      require(words[112+n].address==auxiliary[n],"sample auxiliary observations are incomplete or reordered");
    const auto id=words[112].value,caps=words[113].value,shader=words[116].value;
    require((id&0xffff0000u)==0x47550000u && (id&0xffffu)>=GPU_ABI_COMPUTE_RESOURCE_VERSION &&
      (caps&required_owner_caps)==required_owner_caps && words[114].value==GPU_REG_BIN_CAPABILITIES &&
      words[115].value==1048576,"sample auxiliary observations contradict the complete protected owner");
    require_owner_shader_profile(caps,shader);
  }
  void validate(bool prefix_only=false) const {
    require(command_id>0,"sample capture requires a live nonzero command identity");
    validate_discovery(discovery);
    validate_contract(profile(),prefix_only);
    // This qualification entry admits successful sample shader submissions,
    // not fault captures which happen to carry a sample factory label.
    for(const auto &c:checks) {
      if(c.address==GPU_REG_UCMD_COMPLETION)
        require(c.expected==(0x8000u|(GPU_UCMD_OP_NATIVE_BINNED_RENDER<<8)|command_id),
          "sample capture must require a successful exact native completion");
      if(c.address==0x184 || c.address==0x190 || c.address==0x194 ||
          c.address==0x1a4 || c.address==0x1a8 || c.address==0x1ac)
        require(c.mask==0xffffffffu && !c.expected,"sample capture must reject every GNX fault or reserved bit");
    }
    require(checks.size()==13,"sample capture requires exactly the native completion and twelve GNX checks");
    for(const auto &a:trace) if(a.kind==Kind::write) {
      const bool command=a.address>=GPU_REG_UCMD_ID && a.address<=GPU_REG_UCMD_SAMPLE_MODE &&
        a.address!=GPU_REG_UCMD_DMA_DEPENDENCY && a.address!=GPU_REG_UCMD_RESET &&
        a.address!=GPU_REG_UCMD_COMPLETION && a.address!=GPU_REG_UCMD_COMPLETION_BYTES_LO &&
        a.address!=GPU_REG_UCMD_COMPLETION_BYTES_HI;
      require(command || a.address==GPU_REG_UCMD_VECTOR_SATP || a.address==GPU_REG_UCMD_INSTRUCTION_SATP ||
        a.address==GPU_REG_UCMD_TLB_FLUSH || a.address==GPU_REG_IRQ,
        "sample capture writes outside native command/Sv32 control");
    }
  }
  static SampleCapture read(std::istream &in,bool prefix_only=false) {
    magic(in,"OGCAP002");SampleCapture sample;
    static_cast<Capture &>(sample)=Capture::payload(in);
    sample.validate(prefix_only);return sample;
  }
  static SampleCapture read(const std::string &path,bool prefix_only=false) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open sample owner capture");return read(in,prefix_only);
  }
};
} // namespace native_owner
