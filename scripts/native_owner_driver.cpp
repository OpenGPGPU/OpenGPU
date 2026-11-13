// Execute actual native owner captures using the shared owner AXI runtime.
#include "native_owner_runtime.hpp"

int main(int argc,char **argv) {
  try {
    using namespace native_owner;
    const bool compute_probe=argc>1 && std::string(argv[1])=="--probe-compute-profile";
    if(argc>1 && (std::string(argv[1])=="--probe-profile" || compute_probe)) {
      require(argc==3,"usage: native_owner_driver --probe[-compute]-profile NEW_OUTPUT.json");
      require(!std::filesystem::exists(argv[2]),"profile output already exists");
      Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);
      ReplayBus<Simulation> bus(sim,agent,100000);
      const auto words=probe_profile(sim,agent,bus,compute_probe);
      std::ofstream output(argv[2],std::ios::binary);require(bool(output),"cannot create profile output");
      profile_json(output,words,compute_probe);output.close();require(bool(output),"cannot write profile output");
      sim.model->final();return 0;
    }
    if(argc>1 && (std::string(argv[1])=="--active-series" || std::string(argv[1])=="--active-owner-series")) {
      const bool cleanup=std::string(argv[1])=="--active-owner-series";
      require(argc==6,"usage: native_owner_driver --active[-owner]-series UNION.memory NEW_OUTPUT_DIR SESSION_DIR COUNT");
      size_t used=0;const auto count=std::stoull(argv[5],&used);
      require(used==std::string(argv[5]).size() && count>=2 && count<=0xffffffffu,"invalid active record count");
      const std::filesystem::path output=argv[3],session=argv[4];
      require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session),"invalid active session directory");
      require(!std::filesystem::exists(output),"owner replay output already exists");
      Memory memory=Memory::read(argv[2]);
      wait_input(session/"record-0.capture",session);
      auto first=Capture::read((session/"record-0.capture").string(),true);
      verify_readonly(memory,memory,first.writable);
      require(std::filesystem::create_directory(output),"cannot create active output directory");
      Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
      PtePages pte;
      active_records(sim,agent,bus,memory,output,session,first,count,std::cout,std::chrono::minutes(5),cleanup?&pte:nullptr);
      if(cleanup)cleanup_records(sim,agent,bus,memory,session,pte,count);
      sim.model->final();return 0;
    }
    const bool series=argc>1 && std::string(argv[1])=="--series";
    require(series?argc>=6:argc==4,
      "usage: native_owner_driver INPUT.memory INPUT.capture OUTPUT.memory | --series INPUT.memory OUTPUT_DIR CAPTURE... (at least two)");
    const std::filesystem::path output=argv[3];
    require(!std::filesystem::exists(output),"owner replay output already exists");
    std::vector<Capture> captures;
    if(series) for(int n=4;n<argc;++n)captures.push_back(Capture::read(argv[n]));
    else captures.push_back(Capture::read(argv[2]));
    Memory memory=Memory::read(argv[series?2:1]);
    for(const auto &c:captures)verify_readonly(memory,memory,c.writable);
    // All owners must already be prepared and remain mapped/leased in this
    // single exported physical memory. Never load another RAM image or reset
    // between records: either would erase prior outputs or cache/coherence state.
    if(series)require(std::filesystem::create_directory(output),"cannot create owner replay output directory");
    Simulation sim(argc,argv);sim.reset();Agent agent(memory);
    ReplayBus<Simulation> bus(sim,agent,captures.front().max_cycles);
    for(size_t n=0;n<captures.size();++n) {
      const auto result=replay_record(sim,agent,bus,memory,captures[n]);
      memory.write(series?(output/("record-"+std::to_string(n)+".memory")).string():output.string());
      report_record(result,n,captures[n].command_id,bus.cycles);
    }
    sim.model->final();
    return 0;
  } catch(const std::exception &e) {
    std::cerr<<"native owner replay failed: "<<e.what()<<'\n';return 1;
  }
}
