// Fixed ordinary112 two-owner executor. No instruction VM or output injection.
#include "native_sample_graphics_runtime.hpp"
#include <cstdlib>

static std::chrono::milliseconds sample_graphics_response_timeout() {
  const auto value=std::getenv("OPENGPU_NATIVE_HOST_TIMEOUT");
  if(!value)return std::chrono::minutes(5);
  native_owner::require(*value,"empty ordinary112 response timeout");
  unsigned seconds=0;
  for(auto p=value;*p;++p) {
    native_owner::require(*p>='0' && *p<='9',"ordinary112 timeout must be decimal seconds");
    const auto digit=unsigned(*p-'0');
    native_owner::require(seconds<=(86400u-digit)/10u,"ordinary112 timeout exceeds 86400 seconds");
    seconds=seconds*10+digit;
  }
  native_owner::require(seconds,"ordinary112 timeout must be positive");
  return std::chrono::seconds(seconds);
}
int main(int argc,char **argv) {
  try {
    using namespace native_owner;
    const auto timeout=sample_graphics_response_timeout();
    if(argc==3 && std::string(argv[1])=="--probe-profile") {
      require(!std::filesystem::exists(argv[2]),"ordinary112 profile output already exists");
      Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);
      ReplayBus<Simulation> bus(sim,agent,100000);
      const auto profile=probe_sample_profile(sim,agent,bus);
      atomic_json(argv[2],[&](auto &out){sample_profile_json(out,profile);});
      sim.model->final();return 0;
    }
    require(argc==5 && std::string(argv[1])=="--active-owners",
      "usage: native_sample_graphics_driver --probe-profile NEW.json | --active-owners INPUT.memory NEW_OUTPUT SESSION");
    const std::filesystem::path output=argv[3],session=argv[4];
    require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session),"invalid ordinary112 session");
    require(!std::filesystem::exists(output),"ordinary112 output already exists");
    wait_input(session/"record-0.capture",session,timeout);
    const auto first=SampleCapture::read((session/"record-0.capture").string(),true);
    Memory memory=Memory::read(argv[2]);verify_readonly(memory,memory,first.writable);
    require(std::filesystem::create_directory(output),"cannot create ordinary112 output");
    Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
    PtePages pte;
    active_sample_graphics_records(sim,agent,bus,memory,first,output,session,pte,std::cout,timeout);
    cleanup_records(sim,agent,bus,memory,session,pte,2,timeout);
    sim.model->final();return 0;
  }catch(const std::exception &error) {
    std::cerr<<"ordinary112 owner execution failed: "<<error.what()<<'\n';return 1;
  }
}
