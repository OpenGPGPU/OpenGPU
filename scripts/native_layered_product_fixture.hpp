#pragma once
#include "native_frame_fixture.hpp"
#include "../driver/opengpu_native_layered_abi.h"

namespace native_frame {
inline std::vector<std::string> layered_product_scenes() {
  return {"desktop-composition","textured-3d","opaque-overdraw",
    "transparent-layers","small-triangles","msaa-4x"};
}
// A different container and a different factory: never accepted by Fixture::read.
template<bool Sample> struct BasicLayeredProductFixture : Fixture {
  static constexpr unsigned discovery_words=Sample?112:104;
  static constexpr uint32_t compute_flags=Sample?0x1fffff:0xfffff;
  static const char *container_magic() {return Sample?"OGLAY002":"OGLAY001";}
  static const char *profile_id() {return Sample?"two-cu-vga60-owner-v2-sample-stage":"two-cu-vga60-owner-v2";}
  static const char *stage_id() {return Sample?"geometry-compute-sample":"geometry-compute";}
  static std::vector<Command> profile() {
    std::vector<Command> out;
    const auto block = [&](uint32_t base, std::initializer_list<uint32_t> words) {
      for (auto word : words) { out.push_back({base, word}); base += 4; }
    };
    block(0x360,{0x47435201,16,0xfffff,2,16,8,128,128,0xffffffff,16384,16,8,0x10000000,32,131072,4});
    block(0x160,{0x474e4305,8,0x474e4a02,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813db02});
    block(0x340,{0x47434901,8,0x7f,14,12,4,0xffffffff,128});
    block(0x240,{0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31});
    block(0x260,{0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255});
    block(0x280,{0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63});
    block(0x3a0,{0x47554301,16,1,0x7f,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31});
    block(0x4c0,{0x47414201,8,8,0x7fff,0xf00001ff,15,7,0});
    block(0x4e0,{0x474e5001,8,512,2048,2048,512,15,0});
    block(0x300,{0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138});
    block(0x500,{0x47475301,8,31,0x474e4a02,0x474e4603,0x474e5902,0xff840206,0x04001000});
    if constexpr(Sample) {
      require(out.size()==104 && out[2].address==0x368 && out[2].value==0xfffff,
              "frozen layered owner contract changed");
      out[2].value=0x1fffff;
      block(0x520,{0x47535301,8,1,31,0xc0,0x4d041004,0x7ff,7});
    }
    return out;
  }
  void validate_resources() const {
    const auto expected = profile();
    require(resources.size() == expected.size(), "layered product requires its exact complete discovery table");
    for (unsigned n = 0; n < expected.size(); ++n)
      require(resources[n].address == expected[n].address && resources[n].value == expected[n].value,
              "layered product requires the exact ordered geometry-compute factory");
  }
  uint32_t job_address() const {
    require(writes.size() == 8, "layered product launch requires exactly eight writes");
    const auto base = writes[3].value;
    require(!(base & 63) && uint64_t(base) + 512 < Memory::limit,
            "layered product job requires an RV32 exclusive view");
    const std::array<Command,8> expected{{{0x00c,1},{0x0c4,id},{0x0c8,7},{0x0f4,base},
      {0x0f8,base+512},{0x0fc,512},{0x100,0},{0x11c,1}}};
    for (unsigned n = 0; n < expected.size(); ++n)
      require(writes[n].address == expected[n].address && writes[n].value == expected[n].value,
              "layered product launch identity, order or exclusive view differs");
    return base;
  }
  void validate() const {
    require(((width == 32 && height == 32) || (width == 640 && height == 480)) &&
      id >= 1 && id <= 6 && samples == (id == 6 ? 4u : 1u),
      "unsupported layered product scene");
    require(max_cycles == (width == 32 ? 10000000 : 1000000000), "unexpected layered cycle bound");
    validate_resources(); job_address();
    require(checks.size() == 13, "layered product requires all thirteen completion checks");
    for (unsigned n = 0; n < checks.size(); ++n) {
      const auto &c = checks[n];
      const auto mask = n == 0 ? 0xffffu : (n == 1 || n == 2 || n == 12) ? 0xffffffffu : 0u;
      const auto value = n == 0 ? 0x8700u | id : n == 1 ? 0x474e5801u : 0u;
      require(c.address == (n == 0 ? 0x124u : 0x180u + 4 * (n - 1)) &&
        c.mask == mask && c.expected == value, "layered product completion contract differs");
    }
    require(finish.size() == 2 && finish[0].address == 0x130 && finish[0].value == 1 &&
      finish[1].address == 0x00c && finish[1].value == 3, "layered product retirement contract differs");
  }
  static BasicLayeredProductFixture read(const std::string &path) {
    std::ifstream in(path, std::ios::binary); require(bool(in), "cannot read layered product commands");
    magic(in, container_magic()); BasicLayeredProductFixture f;
    f.width=read_le(in,4); f.height=read_le(in,4); f.samples=read_le(in,4);
    f.id=read_le(in,4); f.max_cycles=read_le(in,8);
    f.resources=commands(in); f.writes=commands(in);
    const auto count=read_le(in,4); require(count==13,"layered product completion checks missing");
    for (unsigned n=0;n<count;++n) {
      const uint32_t address=read_le(in,4),mask=read_le(in,4),value=read_le(in,4);
      f.checks.push_back({address,mask,value});
    }
    f.finish=commands(in); require(in.peek()==EOF,"trailing layered product commands");
    f.validate(); return f;
  }
};

using LayeredProductFixture=BasicLayeredProductFixture<false>;
using SampleLayeredProductFixture=BasicLayeredProductFixture<true>;

struct LayeredProductInput {
  std::array<uint32_t,12> expected_gnx{};
  uint32_t completion_base;
};

template<unsigned Words>
std::array<uint32_t,Words> layered_product_record(const Memory &memory, uint32_t base) {
  require(!(base&63) && uint64_t(base)+4*Words<=Memory::limit,"layered record exceeds RV32");
  for (unsigned offset=0;offset<4*Words;offset+=64)
    require(memory.contains_line(uint64_t(base)+offset),"layered record leaves original backing");
  std::array<uint32_t,Words> words{};
  for (unsigned n=0;n<Words;++n) words[n]=product_word(memory,base,n);
  return words;
}

template<bool Sample>
LayeredProductInput layered_product_input(const BasicLayeredProductFixture<Sample> &fixture, const Memory &memory) {
  fixture.validate();
  const auto base=fixture.job_address();
  const auto job=layered_product_record<128>(memory,base);
  require(opengpu_native_layered_wire_valid(0,job.data(),job.size()),"noncanonical GNJ2 input");
  const auto frame=layered_product_record<512>(memory,job[21]);
  require(opengpu_native_layered_wire_valid(1,frame.data(),frame.size()),"noncanonical GNF3 input");
  const auto wide=[&](unsigned word) {return uint64_t(job[word])|(uint64_t(job[word+1])<<32);};
  require(uint64_t(job[21])+2048<=wide(22) && wide(22)<=Memory::limit,
          "GNF3 exceeds its original view");
  const uint64_t completion=job[48], limit=wide(49), allocation=job[51], allocation_limit=wide(52);
  require(job[54]==1 && !(completion&63) && allocation<=completion &&
    completion+16384==limit && limit<=allocation_limit && allocation_limit<=Memory::limit,
    "GNY2 lacks a complete writable publication view");
  require(std::any_of(memory.ranges.begin(),memory.ranges.end(),[&](const auto &range) {
    return range.start<=allocation && allocation_limit<=range.end;
  }),"GNY2 allocation metadata leaves its original backing");
  require(completion+16384<=base || completion>=uint64_t(base)+512,
          "GNY2 aliases the submitted GNJ2");
  require(completion+16384<=job[21] || completion>=uint64_t(job[21])+2048,
          "GNY2 aliases the original GNF3");
  layered_product_record<4096>(memory,uint32_t(completion));
  LayeredProductInput input{}; input.completion_base=uint32_t(completion);
  input.expected_gnx[0]=0x474e5801;
  input.expected_gnx[6]=job[24]; input.expected_gnx[7]=job[26]; input.expected_gnx[8]=job[25];
  return input;
}

inline void read_layered_product_publication(const Memory &memory, const LayeredProductInput &input) {
  const auto words=layered_product_record<4096>(memory,input.completion_base);
  require(opengpu_native_layered_wire_valid(2,words.data(),words.size()),
          "GNY2 publication is incomplete or noncanonical");
  // Success, identities, all wide counters, restart state and each stored sample
  // are independently checked by NativeLayeredProductFrameInputs.verify.
}

struct LayeredProductProfile {
  std::array<Command,5> registers;
  std::vector<Command> discovery;
};
template<class Sim,bool Sample>
LayeredProductProfile read_layered_product_profile(Sim &sim,const BasicLayeredProductFixture<Sample> &fixture) {
  fixture.validate_resources();
  LayeredProductProfile observed{{{{0,sim.read(0)},{0x60,sim.read(0x60)},{0x68,0},{0x320,0},{0x158,0}}},{}};
  require((observed.registers[0].value&0xffff)>=2 &&
    (observed.registers[1].value&product_owner_caps)==product_owner_caps,
    "layered preflight requires protected unified commands");
  for (const auto &word:fixture.resources) {
    const auto value=sim.read(word.address);
    require(value==word.value,"actual layered factory discovery differs");
    observed.discovery.push_back({word.address,value});
  }
  for (unsigned n=2;n<observed.registers.size();++n)
    observed.registers[n].value=sim.read(observed.registers[n].address);
  require(observed.registers[2].value==0x160 && observed.registers[3].value,
          "layered preflight lacks discovery or live quota");
  require_product_owner_shader_profile(observed.registers[1].value,observed.registers[4].value);
  require(!(sim.read(0x120)&2) && !sim.model->io_m_irq,"layered preflight found a prior completion");
  const auto requests=sim.requests();
  require(!requests.arvalid && !requests.awvalid && !requests.wvalid,"layered preflight found external work");
  return observed;
}
} // namespace native_frame
