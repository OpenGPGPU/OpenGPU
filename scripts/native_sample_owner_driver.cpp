// Source-selected 112-word sample host. No CPU shader or output substitution.
#include "native_sample_owner_runtime.hpp"
#include <cstdlib>

static std::chrono::milliseconds sample_response_timeout() {
  const auto value=std::getenv("OPENGPU_NATIVE_HOST_TIMEOUT");
  if(!value)return std::chrono::minutes(5);
  native_owner::require(*value,"empty native sample response timeout");
  unsigned seconds=0;
  for(auto p=value;*p;++p) {
    native_owner::require(*p>='0' && *p<='9',"native sample timeout must be decimal seconds");
    const auto digit=unsigned(*p-'0');
    native_owner::require(seconds<=(86400u-digit)/10u,"native sample timeout exceeds 86400 seconds");
    seconds=seconds*10+digit;
  }
  native_owner::require(seconds,"native sample timeout must be positive");
  return std::chrono::seconds(seconds);
}
int main(int argc,char **argv) {
  try {
    using namespace native_owner;
    const auto timeout=sample_response_timeout();
    if(argc==3 && std::string(argv[1])=="--probe-profile") {
      require(!std::filesystem::exists(argv[2]),"sample profile output already exists");
      Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);
      ReplayBus<Simulation> bus(sim,agent,100000);
      const auto observed=probe_sample_profile(sim,agent,bus);
      atomic_json(argv[2],[&](auto &out){sample_profile_json(out,observed);});
      sim.model->final();return 0;
    }
    require(argc==5 && std::string(argv[1])=="--active-owner",
      "usage: native_sample_owner_driver --probe-profile NEW.json | --active-owner INPUT.memory NEW_OUTPUT SESSION");
    const std::filesystem::path output=argv[3],session=argv[4];
    require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session),"invalid native sample session");
    require(!std::filesystem::exists(output),"native sample output already exists");
    wait_input(session/"record-0.capture",session,timeout);
    const auto capture=SampleCapture::read((session/"record-0.capture").string(),true);
    Memory memory=Memory::read(argv[2]);verify_readonly(memory,memory,capture.writable);
    require(std::filesystem::create_directory(output),"cannot create native sample output");
    Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
    active_sample_owner(sim,agent,bus,memory,capture,output,session,timeout);
    sim.model->final();return 0;
  }catch(const std::exception &error) {
    std::cerr<<"native sample owner execution failed: "<<error.what()<<'\n';return 1;
  }
}
