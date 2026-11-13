#include "native_frame_fixture.hpp"
#include "../driver/gpu_abi.h"
#include "native_owner_capture.hpp"
#include <iostream>
#include <memory>

using namespace native_frame;
static_assert(GPU_REG_UCMD_SOURCE == 0xf4 && GPU_REG_UCMD_SUBMIT == 0x11c &&
  GPU_REG_UCMD_COMPLETION == 0x124 && GPU_REG_UCMD_COMPLETION_POP == 0x130 &&
  GPU_REG_UCMD_STATUS == 0x120 && GPU_REG_IRQ == 0xc, "product MMIO contract changed");

template<class F> void rejects(F action) {
  bool bad=false; try { action(); } catch (const std::runtime_error &) { bad=true; }
  require(bad,"incorrect product observation was accepted");
}
struct Control {
  struct Model { bool io_m_irq=true; };
  std::unique_ptr<Model> model=std::make_unique<Model>();
  std::map<uint32_t,uint32_t> registers;
  std::vector<uint32_t> reads;
  std::vector<Command> writes;
  bool refuse_pop=false, keep_irq=false;
  Requests offered;
  Requests requests() const { return offered; }
  uint32_t read(uint32_t address) { reads.push_back(address); return registers[address]; }
  void write(uint32_t address,uint32_t value) {
    writes.push_back({address,value});
    if(address==0x130 && !refuse_pop) registers[0x120]=0;
    if(address==0xc && !keep_irq) model->io_m_irq=false;
  }
};
int main() {
  static_assert(product_owner_caps == native_owner::required_owner_caps,
    "owner preflight capability mask differs from the published ABI");
  static_assert(GPU_REG_ID==0 && GPU_REG_CAPABILITIES==0x60 && GPU_REG_BIN_CAPABILITY_PTR==0x68 &&
    GPU_REG_BIN_CAPABILITIES==0x160 && GPU_REG_SHADER_CONFIG==0x158 &&
    GPU_CAP_FRAGMENT_BATCH_SHIFT==8 && GPU_CAP_FRAGMENT_BATCH_MASK==(255u<<8) &&
    GPU_SHADER_CONFIG_LANES_SHIFT==0 && GPU_SHADER_CONFIG_WARPS_SHIFT==8 &&
    GPU_SHADER_CONFIG_BANKS_SHIFT==16 && GPU_SHADER_CONFIG_CUS_SHIFT==24 &&
    GPU_SHADER_CONFIG_FIELD_MASK==255 && GPU_ABI_COMPUTE_RESOURCE_VERSION==2,
    "owner preflight wire fields differ from the published ABI");
  Fixture fixture{};fixture.id=6;
  fixture.writes={{0xc4,6},{0xc8,7},{0xf4,0x1000},{0x11c,1}};
  fixture.finish={{0x130,1},{0xc,3}};
  fixture.checks.push_back({0x124,0xffff,0x8706});
  for(unsigned n=0;n<12;++n)fixture.checks.push_back({0x180+4*n,0,0});
  Memory initial;initial.ranges.push_back({0x1000,0x1200,0});
  const auto put=[&](unsigned word,uint32_t value){for(unsigned n=0;n<4;++n)initial.byte(0x1000+4*word+n,value>>(8*n));};
  put(0,0x474e4a01);put(1,512);put(24,0x12345678);put(25,0xabcdef01);put(26,0x87654321);
  const auto expected=expected_product_completion(fixture,initial);
  require(expected[6]==0x12345678 && expected[7]==0x87654321 && expected[8]==0xabcdef01,
    "job identity was reordered or truncated");
  auto control=[&](){
    Control c;c.registers[0x124]=0x8706;c.registers[0x120]=2;
    for(unsigned n=0;n<12;++n)c.registers[0x180+4*n]=expected[n];
    return c;
  };
  auto c=control();require(read_product_completion(c,fixture,expected)==expected,"valid completion differs");
  require(c.reads.size()==13 && c.reads.front()==0x124 && c.reads.back()==0x1ac,"historical read interval changed");
  // Snapshot the original identity before a later memory mutation.
  put(24,0);require(read_product_completion(c,fixture,expected)==expected,"original identity was not retained");
  for(unsigned word=0;word<12;++word)for(unsigned bit=0;bit<32;++bit) {
    auto bad=control();bad.registers[0x180+4*word]^=uint32_t(1)<<bit;
    rejects([&](){read_product_completion(bad,fixture,expected);});
  }
  for(uint32_t wrong:{0x8705u,0x8f06u,0x00008706u|0x10000u,0x0706u}) {
    auto bad=control();bad.registers[0x124]=wrong;
    rejects([&](){read_product_completion(bad,fixture,expected);});
  }
  for(unsigned n=0;n<13;++n) {auto bad=fixture;bad.checks[n].address^=4;
    rejects([&](){read_product_completion(c,bad,expected);});}
  for(auto address:{0xc4u,0xc8u,0xf4u,0x11cu}) {
    auto bad=fixture;bad.writes.push_back({address,1});rejects([&](){product_job_address(bad);});
  }
  for(unsigned n=0;n<fixture.writes.size();++n) {auto bad=fixture;bad.writes.erase(bad.writes.begin()+n);
    rejects([&](){product_job_address(bad);});}
  auto bad_memory=initial;bad_memory.ranges.clear();rejects([&](){expected_product_completion(fixture,bad_memory);});
  put(0,0);rejects([&](){expected_product_completion(fixture,initial);});
  c=control();retire_product_completion(c,fixture);
  require(c.writes.size()==2 && c.reads.size()==1 && c.reads[0]==0x120,"retirement was not observed exactly once");
  c=control();c.refuse_pop=true;rejects([&](){retire_product_completion(c,fixture);});
  c=control();c.keep_irq=true;rejects([&](){retire_product_completion(c,fixture);});
  for(unsigned n=0;n<3;++n) {
    c=control();
    if(n==0)c.offered.arvalid=true;
    if(n==1)c.offered.awvalid=true;
    if(n==2)c.offered.wvalid=true;
    rejects([&](){retire_product_completion(c,fixture);});
  }
  auto bad_finish=fixture;bad_finish.finish.push_back({0x130,1});
  rejects([&](){retire_product_completion(c,bad_finish);});
  Fixture owner{};owner.resources=native_owner::Capture::profile();
  const auto profile=[&](){Control live;live.model->io_m_irq=false;
    live.registers[GPU_REG_ID]=GPU_ABI_COMPUTE_RESOURCE_VERSION;
    live.registers[GPU_REG_CAPABILITIES]=product_owner_caps | (128u<<GPU_CAP_FRAGMENT_BATCH_SHIFT);
    for(const auto &word:owner.resources)live.registers[word.address]=word.value;
    live.registers[GPU_REG_BIN_CAPABILITY_PTR]=GPU_REG_BIN_CAPABILITIES;
    live.registers[0x320]=1048576;
    live.registers[GPU_REG_SHADER_CONFIG]=16 | (8u<<8) | (32u<<16) | (2u<<24);
    return live;};
  auto live=profile();const auto observed=read_product_owner_profile(live,owner);
  require(observed.size()==5 && live.reads.size()==102 && live.reads[0]==GPU_REG_ID &&
    live.reads[1]==GPU_REG_CAPABILITIES && live.reads[2]==0x360 && live.reads.back()==0x120,
    "owner profile did not retain actual prerequisite-first reads");
  for(const auto &word:owner.resources) {live=profile();live.registers[word.address]^=1;
    rejects([&](){read_product_owner_profile(live,owner);});}
  for(auto address:{GPU_REG_ID,GPU_REG_CAPABILITIES,GPU_REG_BIN_CAPABILITY_PTR,0x320,GPU_REG_SHADER_CONFIG}) {
    live=profile();live.registers[address]=0;rejects([&](){read_product_owner_profile(live,owner);});
    if(address==GPU_REG_ID || address==GPU_REG_CAPABILITIES)
      require(live.reads.size()==2,"rejected prerequisites probed unsupported capability windows");
  }
  for(unsigned bank=0;bank<256;++bank) {
    live=profile();live.registers[GPU_REG_SHADER_CONFIG]=16 | (8u<<8) | (bank<<16) | (2u<<24);
    if(bank>=2 && !(bank&1))read_product_owner_profile(live,owner);
    else rejects([&](){read_product_owner_profile(live,owner);});
  }
  for(uint32_t bit:{GPU_CAP_UNIFIED_COMMANDS,GPU_CAP_COMPUTE_SCALAR_FPU,GPU_CAP_SHADER_CONFIG_INFO,
      GPU_CAP_NATIVE_PIXEL_FORMATS,GPU_CAP_PROTECTED_VM_STATE}) {
    live=profile();live.registers[GPU_REG_CAPABILITIES]&=~bit;
    rejects([&](){read_product_owner_profile(live,owner);});
  }
  for(uint32_t config:{0u,15u|(8u<<8)|(32u<<16)|(2u<<24),16u|(7u<<8)|(32u<<16)|(2u<<24),
      16u|(8u<<8)|(32u<<16)|(1u<<24)}) {
    live=profile();live.registers[GPU_REG_SHADER_CONFIG]=config;
    rejects([&](){read_product_owner_profile(live,owner);});
  }
  live=profile();live.registers[GPU_REG_CAPABILITIES]^=1u<<GPU_CAP_FRAGMENT_BATCH_SHIFT;
  rejects([&](){read_product_owner_profile(live,owner);});
  live=profile();live.registers[0x120]=2;rejects([&](){read_product_owner_profile(live,owner);});
  live=profile();live.model->io_m_irq=true;rejects([&](){read_product_owner_profile(live,owner);});
  live=profile();live.offered.arvalid=true;rejects([&](){read_product_owner_profile(live,owner);});
  std::cout<<"native product completion observations: PASS\n";
}
