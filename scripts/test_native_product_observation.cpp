#include "native_frame_fixture.hpp"
#include "../driver/gpu_abi.h"
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
  auto control=[&](){Control c;c.registers[0x124]=0x8706;c.registers[0x120]=2;
    for(unsigned n=0;n<12;++n)c.registers[0x180+4*n]=expected[n];return c;};
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
  std::cout<<"native product completion observations: PASS\n";
}
