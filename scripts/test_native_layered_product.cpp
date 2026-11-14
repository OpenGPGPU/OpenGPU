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
Control control(const LayeredProductFixture &f) {
  Control c;c.registers[0]=2;c.registers[0x60]=product_owner_caps|(128u<<8);
  for(const auto &r:f.resources)c.registers[r.address]=r.value;
  c.registers[0x68]=0x160;c.registers[0x320]=1048576;
  c.registers[0x158]=16|(8u<<8)|(32u<<16)|(2u<<24);return c;
}
int main(int argc,char **argv) {
  if(argc==3 && std::string(argv[1])=="--fixtures") {
    const auto directory=std::filesystem::path(argv[2]);
    std::ifstream suite(directory/"suite.txt");require(bool(suite),"missing layered producer suite");
    std::vector<std::string> names;std::string name;
    while(std::getline(suite,name))names.push_back(name);
    require(names==layered_product_scenes(),"producer scene order differs");
    unsigned index=0;uint32_t width=0,height=0;
    for(const auto &id:names) {
      const auto f=LayeredProductFixture::read((directory/(id+".commands")).string());
      require(f.id==++index,"producer scene ID differs");
      if(index==1) {width=f.width;height=f.height;}
      require(f.width==width && f.height==height,"mixed producer scene extents");
      layered_product_input(f,Memory::read((directory/(id+".memory")).string()));
    }
    std::cout<<"native layered producer containers: 6 accepted (CPU only)\n";return 0;
  }
  if(argc==2 && std::string(argv[1])=="--profile") {
    std::cout<<"[";unsigned index=0;
    for(const auto &r:LayeredProductFixture::profile())
      std::cout<<(index++?",":"")<<"{\"address\":"<<r.address<<",\"value\":"<<r.value<<"}";
    std::cout<<"]\n";return 0;
  }
  require(argc==2,"test needs a temporary command path");const std::string path=argv[1];
  const auto original=fixture();
  write_fixture(path,original,"OGLAY001");
  const auto parsed=LayeredProductFixture::read(path);require(parsed.resources.size()==104,"missing full factory");
  rejects([&]{Fixture::read(path);});
  write_fixture(path,original,"OGNAT001");rejects([&]{LayeredProductFixture::read(path);});
  // The old parser still accepts its exact GNJ1/96 profile and rejects 104 words.
  Fixture old=original;old.resources.resize(96);
  for(auto &r:old.resources) {
    if(r.address==0x368)r.value=0x3ffff;
    if(r.address==0x160)r.value=0x474e4303;
    if(r.address==0x168)r.value=0x474e4a01;
    if(r.address==0x17c)r.value=0x8813d902;
  }
  write_fixture(path,old,"OGNAT001");require(Fixture::read(path).owner_profile(),"old profile changed");
  write_fixture(path,original,"OGNAT001");rejects([&]{Fixture::read(path);});
  for(unsigned n=0;n<104;++n)for(unsigned bit=0;bit<32;++bit) {
    auto bad=original;bad.resources[n].value^=uint32_t(1)<<bit;rejects([&]{bad.validate();});
    bad=original;bad.resources[n].address^=uint32_t(1)<<bit;rejects([&]{bad.validate();});
  }
  for(unsigned n=0;n<104;++n) {
    auto bad=original;bad.resources.erase(bad.resources.begin()+n);rejects([&]{bad.validate();});
    bad=original;bad.resources.push_back(bad.resources[n]);rejects([&]{bad.validate();});
  }
  auto bad=original;std::swap(bad.resources[0],bad.resources[1]);rejects([&]{bad.validate();});
  for(unsigned n=0;n<8;++n) {
    bad=original;bad.writes[n].address^=4;rejects([&]{bad.validate();});
    bad=original;bad.writes[n].value^=1;rejects([&]{bad.validate();});
    bad=original;bad.writes.erase(bad.writes.begin()+n);rejects([&]{bad.validate();});
  }
  for(unsigned n=0;n<13;++n) {
    bad=original;bad.checks[n].address^=4;rejects([&]{bad.validate();});
    bad=original;bad.checks[n].mask^=1;rejects([&]{bad.validate();});
    bad=original;bad.checks[n].expected^=1;rejects([&]{bad.validate();});
  }
  bad=original;bad.width=1280;bad.height=720;rejects([&]{bad.validate();});
  bad=original;bad.samples=4;rejects([&]{bad.validate();});
  bad=original;bad.id=6;bad.samples=1;rejects([&]{bad.validate();});
  write_fixture(path,original,"OGLAY001");
  {std::ofstream out(path,std::ios::binary|std::ios::app);out.put(0);}
  rejects([&]{LayeredProductFixture::read(path);});
  write_fixture(path,original,"OGLAY001");
  {std::ifstream in(path,std::ios::binary);std::string bytes((std::istreambuf_iterator<char>(in)),{});
    for(unsigned length=0;length<bytes.size();++length) {
      {std::ofstream out(path,std::ios::binary);out.write(bytes.data(),length);}
      rejects([&]{LayeredProductFixture::read(path);});
    }
  }
  auto memory=backing();const auto input=layered_product_input(original,memory);
  require(input.completion_base==0x4040 && input.expected_gnx[6]==0x12345678 &&
    input.expected_gnx[7]==0x87654321 && input.expected_gnx[8]==0xabcdef01,"GNJ2 identity truncated/reordered");
  rejects([&]{read_layered_product_publication(memory,input);}); // untouched sentinel cannot publish success
  for(const auto &change:std::vector<std::pair<unsigned,uint32_t>>{{0,0x474e4a01},{2,1},{8,1},{21,0x2004},
      {22,0x27ff},{48,0x4044},{49,0x8000},{50,1},{51,0x4044},{52,0x9000},{54,0}}) {
    auto m=memory;put(m,0x1000,change.first,change.second);rejects([&]{layered_product_input(original,m);});
  }
  {auto m=memory;put(m,0x2000,0,0x474e4601);rejects([&]{layered_product_input(original,m);});}
  {auto m=memory;m.ranges.pop_back();rejects([&]{layered_product_input(original,m);});}
  // Canonical-format checks only: a zero-field capture still needs the real
  // coarse completion and the independent Scala semantic/frame oracle.
  for(unsigned n=0;n<4096;++n)put(memory,input.completion_base,n,0);
  put(memory,input.completion_base,0,0x474e5902);put(memory,input.completion_base,1,16384);
  put(memory,input.completion_base,2,2);read_layered_product_publication(memory,input);
  for(unsigned n:{0u,1u,2u,4095u}) {
    auto m=memory;put(m,input.completion_base,n,product_word(m,input.completion_base,n)^1);
    rejects([&]{read_layered_product_publication(m,input);});
  }
  auto live=control(original);const auto observed=read_layered_product_profile(live,original);
  require(observed.discovery.size()==104 && live.reads.size()==110 && live.reads[0]==0 && live.reads[1]==0x60,
          "actual factory preflight missed prerequisite-first reads");
  for(unsigned n=0;n<104;++n) {
    require(observed.discovery[n].address==original.resources[n].address &&
      observed.discovery[n].value==original.resources[n].value,"observed discovery changed");
    live=control(original);live.registers[original.resources[n].address]^=1;
    rejects([&]{read_layered_product_profile(live,original);});
  }
  for(auto address:{0u,0x60u,0x68u,0x158u}) {
    live=control(original);live.registers[address]=0;rejects([&]{read_layered_product_profile(live,original);});
    if(address==0 || address==0x60)require(live.reads.size()==2,"invalid prerequisites probed full factory");
  }
  live=control(original);live.registers[0x120]=2;rejects([&]{read_layered_product_profile(live,original);});
  live=control(original);live.model->io_m_irq=true;rejects([&]{read_layered_product_profile(live,original);});
  for(unsigned n=0;n<3;++n) {
    live=control(original);
    if(n==0)live.offered.arvalid=true;
    if(n==1)live.offered.awvalid=true;
    if(n==2)live.offered.wvalid=true;
    rejects([&]{read_layered_product_profile(live,original);});
  }
  std::cout<<"native layered product parser and observations: PASS\n";
}
