// Replay only actual production-owner captures. No shader, descriptor, VA or
// output byte is generated here. An independent image oracle remains required.
#include "native_owner_capture.hpp"
#include "native_host_simulation.hpp"
#include <array>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <limits>
#include <thread>

#ifdef OPENGPU_FLASHSIM_NATIVE
static_assert(GpuHostSystemAxiDut::assertion_count > 0,"assertion-free owner replay DUT");
#ifdef OPENGPU_EXPECTED_ASSERTIONS
static_assert(GpuHostSystemAxiDut::assertion_count == OPENGPU_EXPECTED_ASSERTIONS,"wrong owner replay assertion model");
#endif
#endif

namespace native_owner {
template<class Sim> class ReplayBus {
  Sim &sim;
  Agent &agent;
  uint64_t limit;
public:
  uint64_t cycles=0;
  ReplayBus(Sim &s,Agent &a,uint64_t maximum):sim(s),agent(a),limit(maximum) {}
  void begin_record(uint64_t maximum) {
    require(maximum && maximum<=std::numeric_limits<uint64_t>::max()-cycles,
      "invalid captured record cycle budget");
    limit=cycles+maximum;
  }
  template<class Observe> void step(Observe observe) {
    require(cycles<limit,"captured owner exceeded its total cycle budget");
    sim.step(agent,cycles++,observe);
  }
  void step() {step([](const auto &) {});}
  uint32_t read(uint32_t address) {
    auto &d=*sim.model;uint32_t value=0;bool ar=false,r=false;
    d.io_s_axi_araddr=address;d.io_s_axi_arlen=0;d.io_s_axi_arsize=2;d.io_s_axi_arburst=0;
    d.io_s_axi_arvalid=d.io_s_axi_rready=1;
    for(unsigned n=0;n<1024 && !r;++n) {
      step([&](const auto &m) {
        if(m.io_s_axi_arvalid && m.io_s_axi_arready)ar=true;
        if(m.io_s_axi_rvalid && m.io_s_axi_rready) {
          require(ar,"captured MMIO response preceded its read address");
          require(m.io_s_axi_rresp==0,"captured MMIO read fault");value=m.io_s_axi_rdata;r=true;
        }
      });
      if(ar)d.io_s_axi_arvalid=0;
    }
    d.io_s_axi_arvalid=d.io_s_axi_rready=0;
    require(r,"captured MMIO read handshake timed out");return value;
  }
  void write(uint32_t address,uint32_t value) {
    auto &d=*sim.model;bool aw=false,w=false,b=false;
    d.io_s_axi_awaddr=address;d.io_s_axi_awlen=0;d.io_s_axi_awsize=2;d.io_s_axi_awburst=0;
    d.io_s_axi_wdata=value;d.io_s_axi_wstrb=15;d.io_s_axi_wlast=1;
    d.io_s_axi_awvalid=d.io_s_axi_wvalid=d.io_s_axi_bready=1;
    for(unsigned n=0;n<1024 && !b;++n) {
      step([&](const auto &m) {
        if(m.io_s_axi_awvalid && m.io_s_axi_awready)aw=true;
        if(m.io_s_axi_wvalid && m.io_s_axi_wready)w=true;
        if(m.io_s_axi_bvalid && m.io_s_axi_bready) {
          require(aw && w,"captured MMIO response preceded its write request");
          require(m.io_s_axi_bresp==0,"captured MMIO write fault");b=true;
        }
      });
      if(aw)d.io_s_axi_awvalid=0;
      if(w)d.io_s_axi_wvalid=d.io_s_axi_wlast=0;
    }
    d.io_s_axi_awvalid=d.io_s_axi_wvalid=d.io_s_axi_wlast=d.io_s_axi_bready=0;
    require(b,"captured MMIO write handshake timed out");
  }
  void action(const Action &a) {
    if(a.kind==Kind::write)write(a.address,a.value);
    else if(a.kind==Kind::poll) {while((read(a.address)&a.mask)!=a.value) {}}
    else require((read(a.address)&a.mask)==a.value,"captured MMIO check differs");
  }
};
inline void traffic_unchanged(const Traffic &a,const Traffic &b) {
  require(a.reads==b.reads && a.writes==b.writes && a.read_beats==b.read_beats &&
    a.write_beats==b.write_beats && a.acknowledgements==b.acknowledgements,
    "native completion allowed late external memory traffic");
}
// Observations from the actual held slot and its retirement. These may be
// returned to a still-live original driver session; expected capture values
// must never be substituted for MMIO read results when authorizing release.
struct RecordResult {
  uint64_t cycles=0, read_bytes=0, write_bytes=0, append_read_bytes=0;
  uint32_t completion=0, held_legacy_status=0, held_status=0, held_irq=0, retired_status=0, retired_irq=0;
  std::array<uint32_t,12> gnx{};
  bool held_irq_asserted=false, retired_irq_asserted=false, external_drained=false;
};
struct ProfileObservation {std::vector<Command> words,registers;};
template<class Sim>
ProfileObservation probe_profile(Sim &sim,Agent &agent,ReplayBus<Sim> &bus) {
  ProfileObservation observed;
  const auto id=bus.read(GPU_REG_ID),caps=bus.read(GPU_REG_CAPABILITIES);
  const uint32_t required_caps=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_SHADER_CONFIG_INFO;
  require((id&0xffffu)>=GPU_ABI_COMPUTE_RESOURCE_VERSION && (caps&required_caps)==required_caps,
    "profile probe requires a protected unified-command ABI");
  observed.registers={{GPU_REG_ID,id},{GPU_REG_CAPABILITIES,caps}};
  // The first three reads establish GCR presence/version/protected sources.
  // Never probe dependent GUC/GNC/GSE windows after a rejected prerequisite.
  for(const auto &required:Capture::profile()) {
    const auto value=bus.read(required.address);
    require(value==required.value,"actual DUT lacks the complete native source profile");
    observed.words.push_back({required.address,value});
  }
  const auto pointer=bus.read(GPU_REG_BIN_CAPABILITY_PTR),quota=bus.read(0x320);
  require(pointer==GPU_REG_BIN_CAPABILITIES && quota,"profile probe lacks native discovery/live quota");
  observed.registers.push_back({GPU_REG_BIN_CAPABILITY_PTR,pointer});observed.registers.push_back({0x320,quota});
  const auto shader=bus.read(GPU_REG_SHADER_CONFIG);
  require((shader&0xffu)==16 && ((shader>>8)&0xffu)==8 && ((shader>>16)&0xffu) && (shader>>24)==2,
    "profile probe lacks matching two-CU shader resources");
  observed.registers.push_back({GPU_REG_SHADER_CONFIG,shader});
  require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION) && !sim.model->io_m_irq,
    "profile-only probe encountered an active completion");
  require(agent.drained() && !agent.traffic.reads && !agent.traffic.writes,
    "profile-only probe encountered external memory work");
  return observed;
}
inline void profile_json(std::ostream &out,const ProfileObservation &profile) {
  out<<"{\"schema\":\"opengpu-native-owner-profile-v1\",\"words\":[";
  const auto &words=profile.words;
  for(size_t n=0;n<words.size();++n)
    out<<(n?",":"")<<"{\"address\":"<<words[n].address<<",\"value\":"<<words[n].value<<"}";
  out<<"],\"registers\":[";
  for(size_t n=0;n<profile.registers.size();++n)
    out<<(n?",":"")<<"{\"address\":"<<profile.registers[n].address<<",\"value\":"<<profile.registers[n].value<<"}";
  out<<"],\"resetCount\":1,\"sourceQualificationRequired\":true,\"gpuExecution\":false,"
    "\"clockQualified\":false,\"fpsQualified\":false}\n";
}
struct HeldRecord {
  RecordResult result;
  Memory initial;
  Traffic start_traffic,completed;
  uint64_t start_cycles;
};
template<class Sim>
HeldRecord hold_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,
                       Memory &memory,const Capture &capture,bool prefix_only=false) {
  capture.validate(prefix_only);
  require(agent.drained() && !sim.model->io_m_irq,"owner record starts before prior retirement/drain");
  HeldRecord held_record;held_record.initial=memory;
  verify_readonly(held_record.initial,memory,capture.writable);
  held_record.start_cycles=bus.cycles;held_record.start_traffic=agent.traffic;
  bus.begin_record(capture.max_cycles);
  // GCR's explicit protected-source presence guarantee must be checked before
  // probing GUC/GSE/GNC. A standalone GNP host need not map those windows.
  const auto profile=Capture::profile();
  for(unsigned n=0;n<16;++n)
    require(bus.read(profile[n].address)==profile[n].value,"actual DUT lacks the captured source profile");
  for(const auto &r:capture.discovery) if(r.address<0x360 || r.address>=0x3a0)
    require(bus.read(r.address)==r.value,"actual DUT differs from captured discovery");
  require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION),
    "owner record starts with an unretired completion");
  for(const auto &a:capture.trace)bus.action(a);
  while(!sim.model->io_m_irq)bus.step();
  require(agent.drained(),"native completion preceded external memory drain");
  const auto completed=agent.traffic;
  const auto completion=bus.read(GPU_REG_UCMD_COMPLETION);
  auto &result=held_record.result;result.completion=completion;
  result.held_legacy_status=bus.read(GPU_REG_STATUS);
  result.held_status=bus.read(GPU_REG_UCMD_STATUS);
  result.held_irq=bus.read(GPU_REG_IRQ);result.held_irq_asserted=bool(sim.model->io_m_irq);
  const auto append_lo=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO);
  const auto append_hi=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI);
  result.append_read_bytes=(uint64_t(append_hi)<<32)|append_lo;
  require(result.held_status&GPU_UCMD_STATUS_COMPLETION,"native completion status disappeared before capture");
  std::vector<uint32_t> held;
  for(const auto &c:capture.checks) {
    const auto value=bus.read(c.address);
    require((value&c.mask)==c.expected,"native completion/GNX check differs");held.push_back(value);
    if(c.address>=0x180 && c.address<0x1b0)result.gnx[(c.address-0x180)/4]=value;
  }
  for(uint32_t n=0;n<capture.hold_cycles;++n) {
    require(sim.model->io_m_irq,"native completion IRQ disappeared while held");bus.step();
    require(agent.drained(),"held native completion leaked external traffic");
  }
  require(bus.read(GPU_REG_UCMD_COMPLETION)==completion,"held native completion changed");
  for(size_t n=0;n<capture.checks.size();++n)
    require(bus.read(capture.checks[n].address)==held[n],"held native completion/GNX changed");
  require(bus.read(GPU_REG_UCMD_STATUS)==result.held_status && bus.read(GPU_REG_IRQ)==result.held_irq &&
    bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO)==append_lo && bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI)==append_hi,
    "held native completion status, IRQ or append telemetry changed");
  require(sim.model->io_m_irq,"native completion IRQ disappeared before retirement");
  traffic_unchanged(completed,agent.traffic);
  verify_readonly(held_record.initial,memory,capture.writable);
  held_record.completed=completed;result.external_drained=agent.drained();
  result.cycles=bus.cycles-held_record.start_cycles;
  result.read_bytes=agent.traffic.read_bus_bytes-held_record.start_traffic.read_bus_bytes;
  result.write_bytes=agent.traffic.write_bus_bytes-held_record.start_traffic.write_bus_bytes;
  return held_record;
}
template<class Sim>
RecordResult retire_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,
    Memory &memory,const Capture &capture,HeldRecord &held_record,const Retirement *retirement=nullptr) {
  require(sim.model->io_m_irq && agent.drained(),"driver retirement lost held completion ownership");
  traffic_unchanged(held_record.completed,agent.traffic);
  auto &result=held_record.result;
  if(retirement){retirement->validate();for(const auto &a:retirement->actions)bus.action(a);}
  else {capture.validate();for(const auto &w:capture.finish)bus.write(w.address,w.value);}
  result.retired_status=bus.read(GPU_REG_UCMD_STATUS);
  require(!(result.retired_status&GPU_UCMD_STATUS_COMPLETION),"native completion POP did not retire the slot");
  for(unsigned n=0;n<8;++n)bus.step();
  result.retired_irq=bus.read(GPU_REG_IRQ);result.retired_irq_asserted=bool(sim.model->io_m_irq);
  traffic_unchanged(held_record.completed,agent.traffic);
  require(agent.drained() && !agent.traffic.read_faults && !agent.traffic.write_faults,
    "owner replay faulted or left external traffic");
  require(!sim.model->io_m_irq,"native completion retirement left an asserted IRQ");
  verify_readonly(held_record.initial,memory,capture.writable);
  result.cycles=bus.cycles-held_record.start_cycles;
  result.read_bytes=agent.traffic.read_bus_bytes-held_record.start_traffic.read_bus_bytes;
  result.write_bytes=agent.traffic.write_bus_bytes-held_record.start_traffic.write_bus_bytes;
  result.external_drained=agent.drained();return result;
}
template<class Sim>
RecordResult replay_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,const Capture &capture) {
  auto held=hold_record(sim,agent,bus,memory,capture);
  return retire_record(sim,agent,bus,memory,capture,held);
}
inline void held_json(std::ostream &out,const RecordResult &r,size_t index,uint32_t command_id) {
  out<<"{\"phase\":\"held\",\"record\":"<<index<<",\"commandId\":"<<command_id
    <<",\"completion\":"<<r.completion<<",\"gnx\":[";
  for(size_t n=0;n<r.gnx.size();++n)out<<(n?",":"")<<r.gnx[n];
  out<<"],\"appendReadBytes\":"<<r.append_read_bytes
    <<",\"heldLegacyStatus\":"<<r.held_legacy_status<<",\"heldStatus\":"<<r.held_status<<",\"heldIrq\":"<<r.held_irq
    <<",\"heldIrqAsserted\":"<<(r.held_irq_asserted?"true":"false")
    <<",\"heldCompletionDrained\":"<<(r.external_drained?"true":"false")
    <<",\"sourceQualificationRequired\":true,\"readOnlyGuards\":true}\n";
}
inline void report_record(const RecordResult &r,size_t index,uint32_t command_id,uint64_t cycles,std::ostream &out=std::cout) {
  out<<"{\"phase\":\"retired\",\"ownerReplay\":true,\"record\":"<<index<<",\"commandId\":"<<command_id
    <<",\"serviceCycles\":"<<r.cycles<<",\"cumulativeServiceCycles\":"<<cycles
    <<",\"readBusBytes\":"<<r.read_bytes<<",\"writeBusBytes\":"<<r.write_bytes
    <<",\"completion\":"<<r.completion<<",\"gnx\":[";
  for(size_t n=0;n<r.gnx.size();++n)out<<(n?",":"")<<r.gnx[n];
  out<<"],\"appendReadBytes\":"<<r.append_read_bytes
    <<",\"heldLegacyStatus\":"<<r.held_legacy_status<<",\"heldStatus\":"<<r.held_status<<",\"heldIrq\":"<<r.held_irq
    <<",\"heldIrqAsserted\":"<<(r.held_irq_asserted?"true":"false")
    <<",\"retiredStatus\":"<<r.retired_status<<",\"retiredIrq\":"<<r.retired_irq
    <<",\"retiredIrqAsserted\":"<<(r.retired_irq_asserted?"true":"false")
    <<",\"externalDrained\":"<<(r.external_drained?"true":"false")
    <<",\"resets\":1,\"resetsBetweenRecords\":false,\"heldCompletionDrained\":true,"
      "\"readOnlyGuards\":true,\"independentImagesRequired\":true,"
      "\"sourceQualificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}\n";
}
template<class Write>
void atomic_json(const std::filesystem::path &path,Write write) {
  auto temporary=path;temporary+=".tmp";
  require(!std::filesystem::exists(path) && !std::filesystem::exists(temporary),"active response already exists");
  try {
    std::ofstream out(temporary,std::ios::binary);require(bool(out),"cannot create active response");
    write(out);out.close();require(bool(out),"cannot write active response");
    std::filesystem::rename(temporary,path);
  }catch(...){std::filesystem::remove(temporary);throw;}
}
inline void wait_input(const std::filesystem::path &path,const std::filesystem::path &session,
                       std::chrono::milliseconds timeout=std::chrono::minutes(5)) {
  require(!std::filesystem::exists(session/"error"),"original driver session reported an error");
  const auto deadline=std::chrono::steady_clock::now()+timeout;
  while(!std::filesystem::exists(path)) {
    require(!std::filesystem::exists(session/"error"),"original driver session reported an error");
    require(std::chrono::steady_clock::now()<deadline,"original driver session response timed out");
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }
  require(!std::filesystem::exists(session/"error"),"original driver session reported an error");
  require(std::filesystem::is_regular_file(path) && !std::filesystem::is_symlink(path),"active input is not a regular file");
}
inline void match_profile(const Capture &capture,const ProfileObservation &profile) {
  std::map<uint32_t,uint32_t> words;
  for(const auto &word:capture.discovery)words.emplace(word.address,word.value);
  for(const auto &word:profile.registers) {
    const auto at=words.find(word.address);
    require(at!=words.end() && at->second==word.value,"active capture differs from actual probe identity/quota");
  }
}
template<class Sim>
void active_records(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const std::filesystem::path &output,const std::filesystem::path &session,
    const Capture &first,uint64_t count,std::ostream &report=std::cout,
    std::chrono::milliseconds response_timeout=std::chrono::minutes(5),PtePages *pte=nullptr) {
  const auto profile=probe_profile(sim,agent,bus);
  for(uint64_t n=0;n<count;n++) {
    const auto stem="record-"+std::to_string(n);
    if(n)wait_input(session/(stem+".capture"),session,response_timeout);
    const auto capture=n?Capture::read((session/(stem+".capture")).string(),true):first;
    match_profile(capture,profile);verify_readonly(memory,memory,capture.writable);
    if(pte)pte->observe(memory,capture);
    auto held=hold_record(sim,agent,bus,memory,capture,true);
    atomic_json(session/(stem+".held.json"),[&](auto &out){held_json(out,held.result,n,capture.command_id);});
    wait_input(session/(stem+".retire"),session,response_timeout);
    const auto retirement=Retirement::read((session/(stem+".retire")).string());
    const auto result=retire_record(sim,agent,bus,memory,capture,held,&retirement);
    memory.write((output/(stem+".memory")).string());
    atomic_json(session/(stem+".retired.json"),[&](auto &out){report_record(result,n,capture.command_id,bus.cycles,out);});
    report_record(result,n,capture.command_id,bus.cycles,report);
  }
}
template<class Sim>
void cleanup_records(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const std::filesystem::path &session,const PtePages &pte,uint64_t count,
    std::chrono::milliseconds timeout=std::chrono::minutes(5)) {
  wait_input(session/"cleanup.begin",session,timeout);
  {
    std::ifstream in(session/"cleanup.begin",std::ios::binary);magic(in,"OGCLB001");
    require(read_le(in,4)==count && in.peek()==EOF,"cleanup authorization lost the complete record set");
  }
  Memory expected=memory;const auto traffic=agent.traffic;
  unsigned sequence=0;uint64_t clears=0,shootdowns=0;
  for(;;) {
    const auto stem="cleanup-"+std::to_string(sequence);
    const auto trace=session/(stem+".trace"),done=session/"cleanup.done";
    const auto deadline=std::chrono::steady_clock::now()+timeout;
    while(!std::filesystem::exists(trace) && !std::filesystem::exists(done)) {
      require(!std::filesystem::exists(session/"error"),"original driver cleanup failed");
      require(std::chrono::steady_clock::now()<deadline,"original driver cleanup batch timed out");
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if(std::filesystem::exists(done)) {
      wait_input(done,session,timeout);
      std::ifstream in(done,std::ios::binary);magic(in,"OGCLD001");
      require(read_le(in,4)==sequence && sequence && in.peek()==EOF,"cleanup done crossed an unacknowledged batch");
      require(!std::filesystem::exists(trace),"cleanup left a batch after its final marker");break;
    }
    wait_input(trace,session,timeout);const auto batch=Cleanup::read(trace.string());pte.validate_scope(batch);
    require(agent.drained() && !sim.model->io_m_irq,"cleanup starts before native retirement drained");
    bus.begin_record(1000000);
    for(const auto &a:batch.actions) {
      if(a.kind==Kind::pte_clear){pte.clear(expected,a);pte.clear(memory,a);++clears;}
      else {
        bus.action(a);
        shootdowns+=a.kind==Kind::write && a.address==GPU_REG_UCMD_TLB_FLUSH;
      }
    }
    for(unsigned n=0;n<8;n++)bus.step();
    const auto status=bus.read(GPU_REG_UCMD_STATUS),irq=bus.read(GPU_REG_IRQ);
    require(status==GPU_UCMD_STATUS_READY && irq==GPU_IRQ_ENABLE && !sim.model->io_m_irq && agent.drained(),
      "cleanup did not retire idle, drained and IRQ-free on the live DUT");
    traffic_unchanged(traffic,agent.traffic);
    require(!agent.traffic.read_faults && !agent.traffic.write_faults,"cleanup encountered external memory faults");
    atomic_json(session/(stem+".observed"),[&](auto &out) {
      out.write("OGCLA001",8);for(uint32_t v:{sequence,status,irq,0u,1u})write_le(out,v,4);
    });
    require(sequence<65535,"cleanup exceeded its batch bound");++sequence;
  }
  require(clears && shootdowns,"cleanup performed no real mapping revocation/shootdown");
  pte.revoked(memory);verify_readonly(expected,memory,{});
  atomic_json(session/"cleanup.json",[&](auto &out) {
    out<<"{\"phase\":\"cleanup\",\"ownerCleanupObserved\":true,\"records\":"<<count
      <<",\"batches\":"<<sequence<<",\"pteClears\":"<<clears<<",\"shootdowns\":"<<shootdowns
      <<",\"resetCount\":1,\"ramReplaced\":false,\"imagesPreserved\":true,\"externalDrained\":true,"
        "\"sourceQualificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}\n";
  });
}
} // namespace native_owner

int main(int argc,char **argv) {
  try {
    using namespace native_owner;
    if(argc>1 && std::string(argv[1])=="--probe-profile") {
      require(argc==3,"usage: native_owner_driver --probe-profile NEW_OUTPUT.json");
      require(!std::filesystem::exists(argv[2]),"profile output already exists");
      Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);
      ReplayBus<Simulation> bus(sim,agent,100000);
      const auto words=probe_profile(sim,agent,bus);
      std::ofstream output(argv[2],std::ios::binary);require(bool(output),"cannot create profile output");
      profile_json(output,words);output.close();require(bool(output),"cannot write profile output");
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
