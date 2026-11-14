#pragma once
#include "native_layered_product_fixture.hpp"
#include <filesystem>
#include <iostream>
#include <memory>

using namespace native_frame;
template<class F> void rejects(F action) {
  bool failed=false; try { action(); } catch(const std::runtime_error &) { failed=true; }
  require(failed,"malformed layered product was accepted");
}
LayeredProductFixture fixture() {
  LayeredProductFixture f{}; f.width=640;f.height=480;f.samples=1;f.id=1;f.max_cycles=1000000000;
  f.resources=LayeredProductFixture::profile();
  f.writes={{0xc,1},{0xc4,1},{0xc8,7},{0xf4,0x1000},{0xf8,0x1200},{0xfc,512},{0x100,0},{0x11c,1}};
  f.checks.push_back({0x124,0xffff,0x8701});
  for(unsigned n=0;n<12;++n)f.checks.push_back({0x180+4*n,
    (n==0 || n==1 || n==11)?0xffffffffu:0u,n==0?0x474e5801u:0u});
  f.finish={{0x130,1},{0xc,3}};f.validate();return f;
}
void write_fixture(const std::string &path,const Fixture &f,const char *tag) {
  std::ofstream out(path,std::ios::binary);out.write(tag,8);
  for(auto value:{f.width,f.height,f.samples,f.id})write_le(out,value,4);
  write_le(out,f.max_cycles,8);
  const auto commands=[&](const auto &values) {
    write_le(out,values.size(),4);
    for(const auto &v:values) {write_le(out,v.address,4);write_le(out,v.value,4);}
  };
  commands(f.resources);commands(f.writes);write_le(out,f.checks.size(),4);
  for(const auto &c:f.checks)for(auto v:{c.address,c.mask,c.expected})write_le(out,v,4);
  commands(f.finish);
}
void put(Memory &memory,uint32_t base,unsigned word,uint32_t value) {
  for(unsigned n=0;n<4;++n)memory.byte(uint64_t(base)+4*word+n,value>>(8*n));
}
Memory backing() {
  Memory m;m.ranges={{0x1000,0x1200,0},{0x2000,0x2800,0},{0x4000,0x8080,0x6b}};
  put(m,0x1000,0,0x474e4a02);put(m,0x1000,1,512);put(m,0x1000,2,2);
  put(m,0x1000,21,0x2000);put(m,0x1000,22,0x2800);
  put(m,0x1000,24,0x12345678);put(m,0x1000,25,0xabcdef01);put(m,0x1000,26,0x87654321);
  put(m,0x1000,48,0x4040);put(m,0x1000,49,0x8040);
  put(m,0x1000,51,0x4000);put(m,0x1000,52,0x8080);put(m,0x1000,54,1);
  put(m,0x2000,0,0x474e4603);put(m,0x2000,1,2048);put(m,0x2000,2,3);
  return m;
}
struct Control {
  struct Model { bool io_m_irq=false; };
  std::unique_ptr<Model> model=std::make_unique<Model>();
  std::map<uint32_t,uint32_t> registers;
  std::vector<uint32_t> reads;
  Requests offered;
  Requests requests() const {return offered;}
  uint32_t read(uint32_t address) {reads.push_back(address);return registers[address];}
};
template<class ProductFixture> Control control(const ProductFixture &f) {
  Control c;c.registers[0]=2;c.registers[0x60]=product_owner_caps|(128u<<8);
  for(const auto &r:f.resources)c.registers[r.address]=r.value;
  c.registers[0x68]=0x160;c.registers[0x320]=1048576;
  c.registers[0x158]=16|(8u<<8)|(32u<<16)|(2u<<24);return c;
}
