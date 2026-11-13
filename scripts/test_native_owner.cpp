// CPU protocol/guard tests only. The model stub does not execute GPU work.
#define main owner_driver_entry
#include "native_owner_driver.cpp"
#undef main
#include <sstream>
#include <future>

using namespace native_owner;
template<class F> static void rejects(F action) {
  bool rejected=false;try {action();}catch(const std::runtime_error &){rejected=true;}
  require(rejected,"malformed owner capture was accepted");
}
static Capture fixture() {
  Capture c;c.max_cycles=1000000;c.hold_cycles=16;c.command_id=41;c.discovery=Capture::profile();
  c.writable={{0x2000,0x2040},{0xfffffffc,Memory::limit}};
  auto write=[&](uint32_t a,uint32_t v){c.trace.push_back({Kind::write,a,v,0});};
  write(GPU_REG_UCMD_ID,40);write(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_INVALIDATE);
  write(GPU_REG_UCMD_SOURCE,0x10000);write(GPU_REG_UCMD_BYTES,4096);write(GPU_REG_UCMD_SUBMIT,1);
  c.trace.push_back({Kind::poll,GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_COMPLETION,GPU_UCMD_STATUS_COMPLETION});
  c.trace.push_back({Kind::check,GPU_REG_UCMD_COMPLETION,0x8528,0xffffffffu});
  write(GPU_REG_UCMD_COMPLETION_POP,1);write(GPU_REG_IRQ,3);
  write(GPU_REG_UCMD_VECTOR_SATP,0x80000400);write(GPU_REG_UCMD_INSTRUCTION_SATP,0x80000400);
  write(GPU_REG_UCMD_ID,41);write(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_NATIVE_BINNED_RENDER);
  write(GPU_REG_UCMD_SOURCE,0x1000000);write(GPU_REG_UCMD_BYTES,512);
  write(GPU_REG_UCMD_DESTINATION,0x1000200);write(GPU_REG_UCMD_PATTERN,0);
  write(GPU_REG_UCMD_FLAGS,0);write(GPU_REG_UCMD_SUBMIT,1);
  c.checks.push_back({GPU_REG_UCMD_COMPLETION,0xffffffffu,0x8729});
  for(unsigned n=0;n<12;++n)c.checks.push_back({0x180+4*n,0xffffffffu,n==0?0x474e5801u:0u});
  c.finish={{GPU_REG_UCMD_COMPLETION_POP,1},{GPU_REG_IRQ,3}};c.validate();return c;
}
static std::string encode(const Capture &c) {
  std::ostringstream out;out.write("OGCAP001",8);
  write_le(out,c.max_cycles,8);write_le(out,c.hold_cycles,4);write_le(out,c.command_id,4);
  auto commands=[&](const auto &values) {write_le(out,values.size(),4);
    for(const auto &v:values){write_le(out,v.address,4);write_le(out,v.value,4);}};
  commands(c.discovery);write_le(out,c.writable.size(),4);
  for(const auto &r:c.writable){write_le(out,r.first,8);write_le(out,r.end,8);}
  write_le(out,c.trace.size(),4);for(const auto &a:c.trace) {
    write_le(out,uint32_t(a.kind),4);write_le(out,a.address,4);write_le(out,a.value,4);write_le(out,a.mask,4);
  }
  write_le(out,c.checks.size(),4);for(const auto &a:c.checks) {
    write_le(out,a.address,4);write_le(out,a.mask,4);write_le(out,a.expected,4);
  }
  commands(c.finish);return out.str();
}
static Retirement retirement_for(const Capture &capture) {
  Retirement r;r.actions.push_back({Kind::poll,GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_COMPLETION,GPU_UCMD_STATUS_COMPLETION});
  for(const auto &c:capture.checks)r.actions.push_back({Kind::check,c.address,c.expected,0xffffffffu});
  for(uint32_t address:{GPU_REG_UCMD_COMPLETION_BYTES_LO,GPU_REG_UCMD_COMPLETION_BYTES_HI})
    r.actions.push_back({Kind::check,address,0,0xffffffffu});
  r.actions.push_back({Kind::write,GPU_REG_UCMD_COMPLETION_POP,1,0});
  r.actions.push_back({Kind::write,GPU_REG_IRQ,3,0});r.validate();return r;
}
static std::string encode(const Retirement &r) {
  std::ostringstream out;out.write("OGRTR001",8);write_le(out,r.actions.size(),4);
  for(const auto &a:r.actions){write_le(out,uint32_t(a.kind),4);write_le(out,a.address,4);write_le(out,a.value,4);write_le(out,a.mask,4);}
  return out.str();
}
// A CPU-only control peer injects completion and scratch bytes. It executes no
// GPU instruction; it tests retirement, record budgets and persistent RAM using
// the same MMIO/AXI clock service as the real executor.
class ControlPeer:public Simulation {
  Memory &memory;
  const std::vector<Capture> &captures;
public:
  size_t submitted=0;unsigned resets=0;
  bool corrupt_previous=false,unstable_gnx=false,raw_observations=false,unstable_telemetry=false;
  uint64_t completion_cycle=0;
  std::vector<uint32_t> read_addresses;
  ControlPeer(int argc,char **argv,Memory &m,const std::vector<Capture> &c)
    :Simulation(argc,argv),memory(m),captures(c) {}
  void reset() {
    ++resets;Simulation::reset();model->mmio_enabled=true;
    for(const auto &r:Capture::profile())model->registers[r.address]=r.value;
    model->registers[GPU_REG_ID]=GPU_ABI_COMPUTE_RESOURCE_VERSION;
    model->registers[GPU_REG_CAPABILITIES]=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE;
    model->registers[GPU_REG_BIN_CAPABILITY_PTR]=GPU_REG_BIN_CAPABILITIES;model->registers[0x320]=128;
  }
  template<class Observe> void step(Agent &agent,uint64_t cycle,Observe observe) {
    bool written=false;Command write{};
    Simulation::step(agent,cycle,[&](const auto &m) {
      if(m.io_s_axi_arvalid && m.io_s_axi_arready)read_addresses.push_back(m.io_s_axi_araddr);
      if(m.io_s_axi_awvalid && m.io_s_axi_awready && m.io_s_axi_wvalid && m.io_s_axi_wready) {
        written=true;write={m.io_s_axi_awaddr,m.io_s_axi_wdata};
      }
      observe(m);
    });
    auto &r=model->registers;
    if(unstable_gnx && submitted && cycle==completion_cycle+35)r[0x184]^=1;
    if(unstable_telemetry && submitted && cycle==completion_cycle+35)r[GPU_REG_UCMD_COMPLETION_BYTES_LO]^=64;
    if(!written)return;
    if(write.address==GPU_REG_UCMD_SUBMIT) {
      r[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION;
      r[GPU_REG_UCMD_COMPLETION]=0x8000|(r[GPU_REG_UCMD_OPCODE]<<8)|r[GPU_REG_UCMD_ID];
      if(r[GPU_REG_UCMD_OPCODE]==GPU_UCMD_OP_NATIVE_BINNED_RENDER) {
        require(submitted<captures.size(),"unexpected CPU peer native command");
        if(submitted)require(memory.byte(captures[0].writable[0].first)==0x51,
          "next record replaced prior output RAM");
        const auto &c=captures[submitted];
        for(const auto &check:c.checks)r[check.address]=check.expected;
        if(raw_observations) {
          r[0x188]=0x12345678;
          r[GPU_REG_UCMD_COMPLETION_BYTES_LO]=64;r[GPU_REG_UCMD_COMPLETION_BYTES_HI]=1;
        }
        r[GPU_REG_IRQ]=GPU_IRQ_ENABLE|GPU_IRQ_PENDING;
        memory.byte(c.writable[0].first,uint8_t(0x51+submitted));
        if(submitted && corrupt_previous)memory.byte(captures[0].writable[0].first,0xff);
        ++submitted;completion_cycle=cycle;model->io_m_irq=1;
      }
    } else if(write.address==GPU_REG_UCMD_COMPLETION_POP) {
      r[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_READY;model->io_m_irq=0;
      r[GPU_REG_UCMD_COMPLETION_BYTES_LO]=r[GPU_REG_UCMD_COMPLETION_BYTES_HI]=0;
      for(unsigned n=0;n<12;++n)r[0x180+4*n]=0;
    } else if(write.address==GPU_REG_IRQ) {r[GPU_REG_IRQ]=write.value&GPU_IRQ_ENABLE;model->io_m_irq=0;}
  }
};
static void record_series(int argc,char **argv,bool corrupt) {
  auto a=fixture(),b=a;a.max_cycles=b.max_cycles=400;
  a.writable={{0x2000,0x2040}};b.writable={{0x3000,0x3040}};
  b.command_id=42;b.trace[11].value=42;b.checks[0].expected=0x872a;
  const std::vector<Capture> captures{a,b};
  Memory memory;memory.ranges={{0x1000,0x4000,0xa7}};
  ControlPeer sim(argc,argv,memory,captures);sim.reset();sim.corrupt_previous=corrupt;
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,a.max_cycles);
  const auto first=replay_record(sim,agent,bus,memory,a);const auto first_cycles=bus.cycles;
  require(memory.byte(0x2000)==0x51 && memory.byte(0x3000)==0xa7,"first record scratch byte differs");
  if(corrupt) {
    rejects([&]{replay_record(sim,agent,bus,memory,b);});return;
  }
  const auto second=replay_record(sim,agent,bus,memory,b);
  require(sim.resets==1 && sim.submitted==2 && first_cycles==first.cycles &&
    bus.cycles==first.cycles+second.cycles && bus.cycles>a.max_cycles,
    "owner series reset its model, clock or per-record budget");
  require(memory.byte(0x2000)==0x51 && memory.byte(0x3000)==0x52,
    "owner series discarded prior output memory");
  sim.model->io_m_irq=1;rejects([&]{replay_record(sim,agent,bus,memory,b);});
}
static void unstable_completion(int argc,char **argv) {
  auto c=fixture();c.writable={{0x2000,0x2040}};c.hold_cycles=64;
  const std::vector<Capture> captures{c};
  Memory memory;memory.ranges={{0x1000,0x4000,0xa7}};
  ControlPeer sim(argc,argv,memory,captures);sim.reset();sim.unstable_gnx=true;
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,c.max_cycles);
  rejects([&]{replay_record(sim,agent,bus,memory,c);});
}
static void actual_observations(int argc,char **argv) {
  auto c=fixture();c.writable={{0x2000,0x2040}};
  c.checks[3]={0x188,0xffff0000u,0x12340000u};c.validate();
  const std::vector<Capture> captures{c};Memory memory;memory.ranges={{0x1000,0x4000,0xa7}};
  ControlPeer sim(argc,argv,memory,captures);sim.reset();sim.raw_observations=true;
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,c.max_cycles);
  const auto result=replay_record(sim,agent,bus,memory,c);
  require(result.completion==0x8729 && result.gnx[0]==0x474e5801 && result.gnx[2]==0x12345678 &&
    result.append_read_bytes==UINT64_C(0x100000040) &&
    result.held_status==(GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION) &&
    result.held_irq==(GPU_IRQ_ENABLE|GPU_IRQ_PENDING) && result.held_irq_asserted &&
    result.retired_status==GPU_UCMD_STATUS_READY && result.retired_irq==GPU_IRQ_ENABLE &&
    !result.retired_irq_asserted && result.external_drained,
    "replay reported expected predicates, truncated telemetry or post-POP data as held observations");
  std::ostringstream report;auto *previous=std::cout.rdbuf(report.rdbuf());
  report_record(result,0,c.command_id,bus.cycles);std::cout.rdbuf(previous);
  require(report.str().find("\"gnx\":[1196316673,0,305419896,")!=std::string::npos &&
    report.str().find("\"appendReadBytes\":4294967360")!=std::string::npos &&
    report.str().find("\"heldIrqAsserted\":true")!=std::string::npos &&
    report.str().find("\"retiredIrqAsserted\":false")!=std::string::npos,
    "replay result JSON discarded actual completion observations");
}
static void unstable_append_telemetry(int argc,char **argv) {
  auto c=fixture();c.writable={{0x2000,0x2040}};c.hold_cycles=64;
  const std::vector<Capture> captures{c};Memory memory;memory.ranges={{0x1000,0x4000,0xa7}};
  ControlPeer sim(argc,argv,memory,captures);sim.reset();sim.unstable_telemetry=true;
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,c.max_cycles);
  rejects([&]{replay_record(sim,agent,bus,memory,c);});
}
static void missing_source_profile(int argc,char **argv) {
  auto c=fixture();c.writable={{0x2000,0x2040}};
  std::reverse(c.discovery.begin(),c.discovery.end()); // Capture order must not permit unsafe probes.
  const std::vector<Capture> captures{c};
  Memory memory;memory.ranges={{0x1000,0x4000,0xa7}};
  ControlPeer sim(argc,argv,memory,captures);sim.reset();
  sim.model->registers[0x368]=0x1ffff; // Valid GNP, without source-presence guarantee.
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,c.max_cycles);
  rejects([&]{replay_record(sim,agent,bus,memory,c);});
  require(sim.read_addresses==std::vector<uint32_t>({0x360,0x364,0x368}),
    "owner executor probed dependent windows before protected-source presence");
}
static void profile_probe(int argc,char **argv) {
  const std::vector<Capture> captures;
  Memory memory;ControlPeer sim(argc,argv,memory,captures);sim.reset();
  sim.model->issue_read=false; // CPU control peer only; no synthetic AXI work.
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,1000);
  const auto profile=probe_profile(sim,agent,bus);
  require(profile.words.size()==96 && profile.registers.size()==4 && !sim.submitted && sim.resets==1,
    "profile probe submitted work or lost words");
  require(sim.read_addresses[0]==GPU_REG_ID && sim.read_addresses[1]==GPU_REG_CAPABILITIES &&
    sim.read_addresses[2]==0x360 && sim.read_addresses[3]==0x364 && sim.read_addresses[4]==0x368,
    "profile probe accessed dependent capability windows first");
  std::ostringstream out;profile_json(out,profile);
  require(out.str().find("\"schema\":\"opengpu-native-owner-profile-v1\"")!=std::string::npos &&
    out.str().find("{\"address\":872,\"value\":262143}")!=std::string::npos &&
    out.str().find("\"gpuExecution\":false")!=std::string::npos,"profile JSON lost actual words or probe scope");
  for(const auto &r:Capture::profile()) {
    ControlPeer bad(argc,argv,memory,captures);bad.reset();bad.model->issue_read=false;
    bad.model->registers[r.address]^=1;Agent peer(memory);ReplayBus<ControlPeer> control(bad,peer,1000);
    rejects([&]{probe_profile(bad,peer,control);});
    if(r.address==0x368)require(bad.read_addresses==std::vector<uint32_t>({GPU_REG_ID,GPU_REG_CAPABILITIES,0x360,0x364,0x368}),
      "rejected source presence still probed other windows");
  }
  for(uint32_t address:{GPU_REG_ID,GPU_REG_CAPABILITIES,GPU_REG_BIN_CAPABILITY_PTR,0x320}) {
    ControlPeer bad(argc,argv,memory,captures);bad.reset();bad.model->issue_read=false;
    bad.model->registers[address]=0;Agent peer(memory);ReplayBus<ControlPeer> control(bad,peer,1000);
    rejects([&]{probe_profile(bad,peer,control);});
  }
  // Unexpected real external-memory work cannot be called profile-only.
  ControlPeer work(argc,argv,memory,captures);work.reset();Agent busy(memory);
  ReplayBus<ControlPeer> active(work,busy,1000);rejects([&]{probe_profile(work,busy,active);});
}
static void active_series(int argc,char **argv) {
  auto a=fixture(),b=a;a.finish.clear();b.finish.clear();
  for(Capture *c:{&a,&b}) {
    c->discovery.push_back({GPU_REG_ID,GPU_ABI_COMPUTE_RESOURCE_VERSION});
    c->discovery.push_back({GPU_REG_CAPABILITIES,GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE});
    c->discovery.push_back({GPU_REG_BIN_CAPABILITY_PTR,GPU_REG_BIN_CAPABILITIES});c->discovery.push_back({0x320,128});
    c->validate(true);
  }
  rejects([&]{a.validate();});auto bad=a;bad.finish={{GPU_REG_UCMD_COMPLETION_POP,1}};rejects([&]{bad.validate(true);});
  a.writable={{0x2000,0x2040}};b.writable={{0x3000,0x3040}};
  b.command_id=42;b.trace[11].value=42;b.checks[0].expected=0x872a;
  const std::vector<Capture> captures{a,b};Memory memory;memory.ranges={{0x1000,0x4000,0xa7}};
  ControlPeer sim(argc,argv,memory,captures);sim.reset();sim.model->issue_read=false;
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,100000);std::ostringstream report;
  const auto base=std::filesystem::path(std::getenv("TMPDIR"))/"active-owner";
  require(std::filesystem::create_directory(base),"cannot create active CPU test directory");
  const auto output=base/"output";std::filesystem::create_directory(output);
  auto producer=std::async(std::launch::async,[&] {
    for(unsigned n=0;n<2;n++) {
      const auto stem="record-"+std::to_string(n);
      wait_input(base/(stem+".held.json"),base,std::chrono::seconds(5));
      std::ifstream held(base/(stem+".held.json"));std::string json((std::istreambuf_iterator<char>(held)),{});
      require(json.find("\"phase\":\"held\"")!=std::string::npos && json.find("retired")==std::string::npos,
        "held receipt falsely claims retirement");
      atomic_json(base/(stem+".retire"),[&](auto &out){out<<encode(retirement_for(captures[n]));});
      wait_input(base/(stem+".retired.json"),base,std::chrono::seconds(5));
      if(!n)atomic_json(base/"record-1.capture",[&](auto &out){out<<encode(b);});
    }
  });
  try {active_records(sim,agent,bus,memory,output,base,a,2,report,std::chrono::seconds(5));}
  catch(...){producer.get();std::filesystem::remove_all(base);throw;}
  producer.get();
  require(sim.resets==1 && sim.submitted==2 && memory.byte(0x2000)==0x51 && memory.byte(0x3000)==0x52,
    "active handshake reset, replaced RAM or failed to execute both records");
  const auto first=Memory::read((output/"record-0.memory").string()),second=Memory::read((output/"record-1.memory").string());
  require(first.byte(0x2000)==0x51 && first.byte(0x3000)==0xa7 && second.byte(0x2000)==0x51 && second.byte(0x3000)==0x52,
    "active per-record output memories lost prior outputs");
  require(report.str().find("\"phase\":\"retired\"")!=std::string::npos,"active final receipts were not retired");
  rejects([&]{atomic_json(base/"record-0.held.json",[](auto &out){out<<"stale";});});
  std::ofstream(base/"error")<<"driver failure";
  rejects([&]{wait_input(base/"missing",base,std::chrono::seconds(5));});
  std::filesystem::remove(base/"error");
  rejects([&]{wait_input(base/"missing",base,std::chrono::milliseconds(1));});
  std::filesystem::remove_all(base);
}
static void retirement_parser() {
  const auto original=retirement_for(fixture());const auto wire=encode(original);
  std::istringstream input(wire);require(Retirement::read(input).actions.size()==original.actions.size(),"lost retirement actions");
  for(unsigned n=0;n<original.actions.size();n++) {
    auto bad=original;bad.actions.erase(bad.actions.begin()+n);
    // Every read, the unique POP and the completion witness are mandatory;
    // acknowledging the IRQ can be omitted by this wire contract.
    if(n+1!=original.actions.size())rejects([&]{bad.validate();});
  }
  auto bad=original;bad.actions.push_back({Kind::write,GPU_REG_UCMD_COMPLETION_POP,1,0});rejects([&]{bad.validate();});
  bad=original;bad.actions.push_back({Kind::check,0x180,0x474e5801,0xffffffffu});rejects([&]{bad.validate();});
  bad=original;bad.actions[1].mask=0xff;bad.actions[1].value&=0xff;rejects([&]{bad.validate();});
  for(uint32_t address:{GPU_REG_UCMD_SUBMIT,GPU_REG_UCMD_RESET,GPU_REG_UCMD_TLB_FLUSH}) {
    bad=original;bad.actions.push_back({Kind::write,address,1,0});rejects([&]{bad.validate();});
  }
  bad=original;bad.actions[0].kind=Kind(4);rejects([&]{bad.validate();});
  for(size_t size:{size_t(0),size_t(8),wire.size()-1}) {std::istringstream truncated(wire.substr(0,size));rejects([&]{Retirement::read(truncated);});}
  std::istringstream trailing(wire+"x");rejects([&]{Retirement::read(trailing);});
}
int main() {
  const auto c=fixture();
  require(c.discovery.size()==96,"complete native capture requires all 96 mandatory discovery words");
  std::istringstream valid(encode(c));const auto parsed=Capture::read(valid);
  require(parsed.trace.size()==c.trace.size() && parsed.discovery.size()==96,"capture decoding lost records");
  for(size_t i=0;i<c.discovery.size();++i) {
    auto q=c;q.discovery[i].value^=1;rejects([&]{q.validate();});
    q=c;q.discovery.erase(q.discovery.begin()+i);rejects([&]{q.validate();});
  }
  auto q=c;q.discovery.push_back(q.discovery[0]);rejects([&]{q.validate();});
  q=c;q.discovery[2].value=0x7fff;rejects([&]{q.validate();}); // Frozen v2 lacks GNP and advanced transport.
  q=c;q.discovery[2].value=0x1ffff;rejects([&]{q.validate();}); // GNP alone does not guarantee protected sources.
  q=c;q.max_cycles=0;rejects([&]{q.validate();});q=c;q.hold_cycles=0;rejects([&]{q.validate();});
  q=c;q.command_id=256;rejects([&]{q.validate();});
  q=c;q.trace[0].address|=1;rejects([&]{q.validate();});
  q=c;q.trace[0].mask=1;rejects([&]{q.validate();});
  q=c;q.trace[0].kind=Kind(9);rejects([&]{q.validate();});
  q=c;q.trace[5].mask=0;rejects([&]{q.validate();});
  q=c;q.trace.erase(q.trace.begin()+5);rejects([&]{q.validate();}); // No prior-completion witness before POP.
  q=c;q.trace.erase(q.trace.begin()+7);rejects([&]{q.validate();}); // No POP before the next command.
  q=c;q.trace.push_back(q.trace[0]);rejects([&]{q.validate();});
  q=c;q.trace[9].value=0;rejects([&]{q.validate();}); // Bare is not a protected owner context.
  q=c;q.trace[10].value^=1;rejects([&]{q.validate();});
  q=c;q.trace[14].value=256;rejects([&]{q.validate();});
  q=c;q.trace[12].value=GPU_UCMD_OP_RENDER;rejects([&]{q.validate();}); // Legacy render is not GNJ1.
  q=c;q.checks[0].expected=0x8629;rejects([&]{q.validate();});
  q=c;q.trace[15].value-=1;rejects([&]{q.validate();});
  q=c;q.trace[16].value=2;rejects([&]{q.validate();});
  for(uint32_t flag:{GPU_UCMD_FLAG_WAIT_DMA,GPU_UCMD_FLAG_WAIT_EVENT,GPU_UCMD_FLAG_SIGNAL_EVENT,32u}) {
    q=c;q.trace[17].value=flag;rejects([&]{q.validate();});
  }
  q=c;q.trace[17].value=GPU_UCMD_FLAG_RASTER_DISCARD|GPU_UCMD_FLAG_FEEDBACK_CAPTURE;q.validate();
  q=c;q.trace[13].value=0xfffffe00;q.trace[15].value=0;q.trace[16].value=1;q.validate();
  q.trace[13].value+=64;rejects([&]{q.validate();}); // Full GNJ would cross RV32.
  for(unsigned n:{15u,16u,17u}) {q=c;q.trace.erase(q.trace.begin()+n);rejects([&]{q.validate();});}
  q=c;q.checks[0].expected^=1;rejects([&]{q.validate();});
  for(unsigned n=0;n<13;++n) {q=c;q.checks.erase(q.checks.begin()+n);rejects([&]{q.validate();});}
  q=c;q.checks[2].mask=1;rejects([&]{q.validate();});
  q=c;q.finish[0].address=GPU_REG_UCMD_RESET;rejects([&]{q.validate();});
  q=c;q.finish.push_back(q.finish[0]);rejects([&]{q.validate();});
  q=c;q.writable[0].end=Memory::limit+1;rejects([&]{q.validate();});
  q=c;q.writable[1].first=q.writable[0].first;rejects([&]{q.validate();});
  const auto wire=encode(c);
  for(size_t n:{size_t(0),size_t(7),size_t(20),wire.size()-1}) {
    std::istringstream truncated(wire.substr(0,n));rejects([&]{Capture::read(truncated);});
  }
  std::istringstream trailing(wire+"x");rejects([&]{Capture::read(trailing);});
  auto oversized=wire;for(unsigned n=24;n<28;++n)oversized[n]=char(255);
  std::istringstream badcount(oversized);rejects([&]{Capture::read(badcount);});

  Memory before;before.ranges={{0,Memory::limit,0xa7}};Memory after=before;
  after.byte(0x2000,0x55);after.byte(0xffffffff,0x88);verify_readonly(before,after,c.writable);
  for(uint64_t a:{UINT64_C(0x1fff),UINT64_C(0x2040),UINT64_C(0xfffffffb)}) {
    auto corrupt=after;corrupt.byte(a,0);rejects([&]{verify_readonly(before,corrupt,c.writable);});
  }
  auto changed=after;changed.ranges[0].fill=0;rejects([&]{verify_readonly(before,changed,c.writable);});
  Memory bounded;bounded.ranges={{0x2000,0x3000,0}};
  rejects([&]{verify_readonly(bounded,bounded,c.writable);});
  Traffic traffic;auto late=traffic;late.writes=1;rejects([&]{traffic_unchanged(traffic,late);});

  int argc=1;char program[]="owner-cpu-test";char *argv[]={program};
  Simulation sim(argc,argv);sim.reset();sim.model->mmio_enabled=true;
  Memory backing;backing.ranges={{0x1000,0x2000,0}};Agent agent(backing);
  ReplayBus<Simulation> bus(sim,agent,1000);
  bus.write(0x100,0x12345678);require(bus.read(0x100)==0x12345678,"MMIO write/read changed captured bits");
  bus.action({Kind::poll,0x100,0x78,0xff});bus.action({Kind::check,0x100,0x12345678,0xffffffffu});
  require(agent.traffic.reads==1 && agent.traffic.read_beats==2 && agent.drained(),
    "MMIO polling did not service both external AXI read beats");
  rejects([&]{bus.action({Kind::check,0x100,0,0xffffffffu});});
  ReplayBus<Simulation> timed(sim,agent,3);
  rejects([&]{timed.action({Kind::poll,0x100,0,0xffffffffu});});
  record_series(argc,argv,false);record_series(argc,argv,true);
  unstable_completion(argc,argv);
  actual_observations(argc,argv);unstable_append_telemetry(argc,argv);
  missing_source_profile(argc,argv);
  profile_probe(argc,argv);
  retirement_parser();active_series(argc,argv);
  std::cout<<"native owner capture CPU protocol/guard checks: PASS\n";
}
