#include "native_layered_product_test_support.hpp"

int main(int argc,char **argv) {
  if(argc==2 && std::string(argv[1])=="--profile") {
    std::cout<<"[";unsigned index=0;
    for(const auto &r:SampleLayeredProductFixture::profile())
      std::cout<<(index++?",":"")<<"{\"address\":"<<r.address<<",\"value\":"<<r.value<<"}";
    std::cout<<"]\n";return 0;
  }
  if(argc==3 && std::string(argv[1])=="--fixtures") {
    const auto directory=std::filesystem::path(argv[2]);
    std::ifstream suite(directory/"suite.txt");require(bool(suite),"missing sample producer suite");
    std::vector<std::string> names;std::string name;
    while(std::getline(suite,name))names.push_back(name);
    require(names==layered_product_scenes(),"sample producer scene order differs");
    unsigned index=0;uint32_t width=0,height=0;
    for(const auto &id:names) {
      const auto f=SampleLayeredProductFixture::read((directory/(id+".commands")).string());
      require(f.id==++index,"sample producer scene ID differs");
      if(index==1) {width=f.width;height=f.height;}
      require(f.width==width && f.height==height,"mixed sample producer scene extents");
      layered_product_input(f,Memory::read((directory/(id+".memory")).string()));
      rejects([&]{LayeredProductFixture::read((directory/(id+".commands")).string());});
    }
    std::cout<<"native sample layered producer containers: 6 accepted (CPU only)\n";return 0;
  }
  require(argc==2,"sample test needs a temporary path");const std::string path=argv[1];
  const auto old=fixture();SampleLayeredProductFixture f{};static_cast<Fixture&>(f)=old;
  f.resources=SampleLayeredProductFixture::profile();f.validate();
  require(f.resources.size()==112 && f.resources[2].value==0x1fffff,"missing sample contract");
  write_fixture(path,f,"OGLAY002");require(SampleLayeredProductFixture::read(path).resources.size()==112,"sample parse failed");
  rejects([&]{LayeredProductFixture::read(path);});rejects([&]{Fixture::read(path);});
  write_fixture(path,old,"OGLAY001");LayeredProductFixture::read(path);
  rejects([&]{SampleLayeredProductFixture::read(path);});
  write_fixture(path,old,"OGLAY002");rejects([&]{SampleLayeredProductFixture::read(path);});
  write_fixture(path,f,"OGLAY001");rejects([&]{LayeredProductFixture::read(path);});
  rejects([&]{SampleLayeredProductFixture::read(path);});
  for(unsigned n=0;n<112;++n)for(unsigned bit=0;bit<32;++bit) {
    auto bad=f;bad.resources[n].value^=uint32_t(1)<<bit;rejects([&]{bad.validate();});
    bad=f;bad.resources[n].address^=uint32_t(1)<<bit;rejects([&]{bad.validate();});
  }
  for(unsigned n=0;n<112;++n) {
    auto bad=f;bad.resources.erase(bad.resources.begin()+n);rejects([&]{bad.validate();});
    bad=f;bad.resources.push_back(bad.resources[n]);rejects([&]{bad.validate();});
    bad=f;std::swap(bad.resources[n],bad.resources[(n+1)%112]);rejects([&]{bad.validate();});
  }
  write_fixture(path,f,"OGLAY002");
  {std::ifstream in(path,std::ios::binary);std::string bytes((std::istreambuf_iterator<char>(in)),{});
    for(unsigned length=0;length<bytes.size();++length) {
      {std::ofstream out(path,std::ios::binary);out.write(bytes.data(),length);}
      rejects([&]{SampleLayeredProductFixture::read(path);});
    }
  }
  write_fixture(path,f,"OGLAY002");
  {std::ofstream out(path,std::ios::binary|std::ios::app);out.put(0);}
  rejects([&]{SampleLayeredProductFixture::read(path);});
  const auto input=layered_product_input(f,backing());
  require(input.expected_gnx[6]==0x12345678 && input.expected_gnx[7]==0x87654321 &&
    input.expected_gnx[8]==0xabcdef01 && input.completion_base==0x4040,"sample job identity changed");
  auto live=control(f);const auto observed=read_layered_product_profile(live,f);
  require(observed.discovery.size()==112 && live.reads.size()==118,"sample preflight skipped actual discovery");
  for(unsigned n=0;n<112;++n) {
    require(observed.discovery[n].address==f.resources[n].address &&
      observed.discovery[n].value==f.resources[n].value,"sample discovery row changed");
    live=control(f);live.registers[f.resources[n].address]^=1;
    rejects([&]{read_layered_product_profile(live,f);});
  }
  std::cout<<"native sample layered product parser and profile isolation: PASS\n";
}
