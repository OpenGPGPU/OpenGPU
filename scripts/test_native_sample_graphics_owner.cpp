// CPU MMIO model only. Neither two records nor injected receipts prove a GPU.
#include "native_sample_graphics_runtime.hpp"
#define main sample_transport_test_entry
#include "test_native_sample_owner.cpp"
#undef main
#define main sample_graphics_driver_entry
#include "native_sample_graphics_driver.cpp"
#undef main

class GraphicsSeriesPeer:public SamplePeer {
public:
  unsigned resets=0,submitted=0;
  GraphicsSeriesPeer(int argc,char **argv):SamplePeer(argc,argv) {}
  void reset(){++resets;SamplePeer::reset();}
  template<class Observe> void step(Agent &agent,uint64_t cycle,Observe observe) {
    bool submitted_now=false;
    SamplePeer::step(agent,cycle,[&](const auto &m){
      submitted_now=m.io_s_axi_awvalid && m.io_s_axi_awready &&
        m.io_s_axi_wvalid && m.io_s_axi_wready && m.io_s_axi_awaddr==GPU_REG_UCMD_SUBMIT;
      observe(m);
    });
    if(submitted_now) {
      ++submitted;
      model->registers[GPU_REG_UCMD_COMPLETION]=0x8700u|model->registers[GPU_REG_UCMD_ID];
    }
  }
};
static SampleCapture graphics_capture(unsigned record) {
  auto capture=fixture();capture.command_id=41+record;
  capture.writable={{0x7000,0x7040}};
  for(auto &action:capture.trace)if(action.kind==Kind::write) {
    if(action.address==GPU_REG_UCMD_VECTOR_SATP || action.address==GPU_REG_UCMD_INSTRUCTION_SATP)
      action.value=0x80400001;
    if(action.address==GPU_REG_UCMD_ID)action.value=capture.command_id;
    if(action.address==GPU_REG_UCMD_SOURCE)action.value=0x1000000+4096*record;
    if(action.address==GPU_REG_UCMD_DESTINATION)action.value=0x1000200+4096*record;
  }
  capture.checks[0].expected=0x8700u|capture.command_id;
  capture.validate(true);return capture;
}
static void graphics_retirement(const std::filesystem::path &path,const SampleCapture &capture) {
  Retirement retirement;retirement.actions.push_back({Kind::poll,GPU_REG_UCMD_STATUS,2,2});
  for(unsigned n=0;n<12;n++)retirement.actions.push_back({Kind::check,0x180+4*n,
    n==2?0x12345678u:n==3?0x89abcdefu:capture.checks[n+1].expected,0xffffffffu});
  for(auto word:std::array<Command,3>{{{GPU_REG_UCMD_COMPLETION,0x8700u|capture.command_id},
      {GPU_REG_UCMD_COMPLETION_BYTES_LO,64},{GPU_REG_UCMD_COMPLETION_BYTES_HI,1}}})
    retirement.actions.push_back({Kind::check,word.address,word.value,0xffffffffu});
  retirement.actions.push_back({Kind::write,GPU_REG_UCMD_COMPLETION_POP,1,0});
  retirement.actions.push_back({Kind::write,GPU_REG_IRQ,3,0});retirement.validate();
  atomic_json(path,[&](auto &out){out.write("OGRTR001",8);write_le(out,retirement.actions.size(),4);
    for(auto a:retirement.actions)for(uint32_t v:{uint32_t(a.kind),a.address,a.value,a.mask})write_le(out,v,4);
  });
}
static void graphics_series(int argc,char **argv,int fault,bool json) {
  const auto directory=std::filesystem::path(std::getenv("TMPDIR"))/("ordinary112-"+std::to_string(fault));
  require(std::filesystem::create_directory(directory),"cannot create ordinary112 CPU fixture");
  const auto output=directory/"output";std::filesystem::create_directory(output);
  auto first=graphics_capture(0),second=graphics_capture(1);
  if(fault==1) { // Replaying the same command cannot count as another owner.
    second.command_id=first.command_id;second.checks[0].expected=first.checks[0].expected;
    for(auto &a:second.trace)if(a.address==GPU_REG_UCMD_ID)a.value=first.command_id;
  }
  for(auto &a:second.trace)if(a.kind==Kind::write) {
    if(fault==2 && a.address==GPU_REG_UCMD_SOURCE)a.value=0x1000000;
    if(fault==3 && (a.address==GPU_REG_UCMD_VECTOR_SATP || a.address==GPU_REG_UCMD_INSTRUCTION_SATP))a.value=0x80800001;
    if(fault==4 && a.address==GPU_REG_UCMD_FLAGS)a.value=GPU_UCMD_FLAG_FEEDBACK_CAPTURE;
  }
  second.validate(true);
  if(fault!=6)atomic_json(directory/"record-1.capture",[&](auto &out){
    auto bytes=encode(second);if(fault==5)bytes[7]='1';out.write(bytes.data(),bytes.size());
  });
  graphics_retirement(directory/"record-0.retire",first);
  graphics_retirement(directory/"record-1.retire",second);
  Memory memory;memory.ranges={{0x1000,0x9000,0}};
  auto word=[&](uint64_t at,uint32_t value){for(unsigned n=0;n<4;n++)memory.byte(at+n,uint8_t(value>>(8*n)));};
  word(0x1010,0x801);word(0x2000,0x1403);word(0x2004,0x1803);
  GraphicsSeriesPeer sim(argc,argv);sim.reset();Agent agent(memory);
  ReplayBus<GraphicsSeriesPeer> bus(sim,agent,100000);PtePages pte;std::ostringstream report;
  auto execute=[&]{active_sample_graphics_records(sim,agent,bus,memory,first,output,directory,pte,report,
    std::chrono::seconds(1));};
  if(fault) {
    rejects(execute);
    require(sim.submitted==1 && sim.resets==1 &&
      std::filesystem::exists(directory/"record-0.retired.json") &&
      !std::filesystem::exists(directory/"record-1.held.json") &&
      !std::filesystem::exists(directory/"record-1.retired.json") &&
      !std::filesystem::exists(directory/"cleanup.json"),"invalid second owner executed or authorized cleanup");
  }else {
    execute();
    require(sim.submitted==2 && sim.resets==1 && agent.drained() &&
      pte.required_revocations.size()==2,"ordinary112 lost RAM/context/PTE continuity");
    require(!std::filesystem::exists(directory/"cleanup.json"),"execution alone authorized cleanup");
    if(json) {
      const auto profile=probe_sample_profile(sim,agent,bus);sample_profile_json(std::cout,profile);
      for(unsigned n=0;n<2;n++)for(const char *phase:{"held","retired"}) {
        std::ifstream in(directory/("record-"+std::to_string(n)+"."+phase+".json"));
        require(bool(in),"missing ordinary112 receipt");std::cout<<in.rdbuf();
      }
    }
  }
  std::filesystem::remove_all(directory);
}
int main(int argc,char **argv) {
  const bool json=argc==2 && std::string(argv[1])=="--json";
  const char *previous=std::getenv("OPENGPU_NATIVE_HOST_TIMEOUT");
  const bool present=previous!=nullptr;const std::string saved=previous?previous:"";
  unsetenv("OPENGPU_NATIVE_HOST_TIMEOUT");
  require(sample_graphics_response_timeout()==std::chrono::minutes(5),"ordinary112 default timeout changed");
  for(const char *text:{"1","300","86400","0001"}) {
    setenv("OPENGPU_NATIVE_HOST_TIMEOUT",text,1);
    require(sample_graphics_response_timeout()==std::chrono::seconds(std::stoul(text)),"ordinary112 configured timeout changed");
  }
  for(const char *text:{"","0","-1","1x","86401","4294967297","18446744073709551616"}) {
    setenv("OPENGPU_NATIVE_HOST_TIMEOUT",text,1);rejects([&]{sample_graphics_response_timeout();});
    std::ostringstream error;auto *old=std::cerr.rdbuf(error.rdbuf());
    const auto result=sample_graphics_driver_entry(argc,argv);std::cerr.rdbuf(old);
    require(result==1 && error.str().find("timeout")!=std::string::npos,"ordinary112 driver accepted invalid timeout");
  }
  if(present)setenv("OPENGPU_NATIVE_HOST_TIMEOUT",saved.c_str(),1);else unsetenv("OPENGPU_NATIVE_HOST_TIMEOUT");
  graphics_series(argc,argv,0,json);
  for(int fault=1;fault<=6;fault++)graphics_series(argc,argv,fault,false);
  rejects([&]{sample_graphics_receipt_json(std::cout,RecordResult{},2,41,0);});
  if(!json)std::cout<<"ordinary112 two-owner CPU MMIO/guard checks: PASS\n";
}
