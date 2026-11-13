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
static std::string encode(const Cleanup &c) {
  std::ostringstream out;out.write("OGCLN001",8);write_le(out,c.actions.size(),4);
  for(const auto &a:c.actions){write_le(out,uint32_t(a.kind),4);write_le(out,a.address,4);write_le(out,a.value,4);write_le(out,a.mask,4);}
  return out.str();
}
static void memory_word(Memory &memory,uint64_t address,uint32_t value) {
  for(unsigned n=0;n<4;n++)memory.byte(address+n,uint8_t(value>>(8*n)));
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
    model->registers[GPU_REG_CAPABILITIES]=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_NATIVE_PIXEL_FORMATS|(128u<<GPU_CAP_FRAGMENT_BATCH_SHIFT);
    model->registers[GPU_REG_BIN_CAPABILITY_PTR]=GPU_REG_BIN_CAPABILITIES;model->registers[0x320]=128;model->registers[GPU_REG_SHADER_CONFIG]=0x02040810;
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
  c.checks[3]={0x188,0u,0u};c.validate();
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
  require(profile.words.size()==96 && profile.registers.size()==5 && !sim.submitted && sim.resets==1,
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
  for(uint32_t shader:{0u,0x02040800u,0x02040010u,0x02000810u,0x02010810u,0x02030810u,0x00040810u,0x04040810u}) {
    ControlPeer bad(argc,argv,memory,captures);bad.reset();bad.model->issue_read=false;
    bad.model->registers[GPU_REG_SHADER_CONFIG]=shader;Agent peer(memory);
    ReplayBus<ControlPeer> control(bad,peer,1000);rejects([&]{probe_profile(bad,peer,control);});
  }
  for(uint32_t batch:{0u,64u,127u,255u}) {
    ControlPeer bad(argc,argv,memory,captures);bad.reset();bad.model->issue_read=false;
    auto &caps=bad.model->registers[GPU_REG_CAPABILITIES];
    caps=(caps&~GPU_CAP_FRAGMENT_BATCH_MASK)|(batch<<GPU_CAP_FRAGMENT_BATCH_SHIFT);
    Agent peer(memory);ReplayBus<ControlPeer> control(bad,peer,1000);
    rejects([&]{probe_profile(bad,peer,control);});require(!bad.submitted,"invalid batch submitted work");
  }
  for(uint32_t flag:{GPU_CAP_UNIFIED_COMMANDS,GPU_CAP_PROTECTED_VM_STATE,GPU_CAP_COMPUTE_SCALAR_FPU,
                     GPU_CAP_SHADER_CONFIG_INFO,GPU_CAP_NATIVE_PIXEL_FORMATS}) {
    ControlPeer bad(argc,argv,memory,captures);bad.reset();bad.model->issue_read=false;
    bad.model->registers[GPU_REG_CAPABILITIES]&=~flag;
    Agent peer(memory);ReplayBus<ControlPeer> control(bad,peer,1000);
    rejects([&]{probe_profile(bad,peer,control);});
    require(bad.read_addresses==std::vector<uint32_t>({GPU_REG_ID,GPU_REG_CAPABILITIES}),
      "profile read dependent windows without required capabilities");
  }
  for(uint32_t shader:{0x02020810u,0x02100810u}) {
    ControlPeer valid(argc,argv,memory,captures);valid.reset();valid.model->issue_read=false;
    valid.model->registers[GPU_REG_SHADER_CONFIG]=shader;
    Agent peer(memory);ReplayBus<ControlPeer> control(valid,peer,1000);
    require(probe_profile(valid,peer,control).registers.back().value==shader && !valid.submitted,
      "profile changed a legal raw bank count");
  }
  ControlPeer no_shader(argc,argv,memory,captures);no_shader.reset();no_shader.model->issue_read=false;
  no_shader.model->registers[GPU_REG_CAPABILITIES]&=~GPU_CAP_SHADER_CONFIG_INFO;
  Agent no_shader_agent(memory);ReplayBus<ControlPeer> no_shader_bus(no_shader,no_shader_agent,1000);
  rejects([&]{probe_profile(no_shader,no_shader_agent,no_shader_bus);});
  require(no_shader.read_addresses==std::vector<uint32_t>({GPU_REG_ID,GPU_REG_CAPABILITIES}),
    "profile probe read shader resources without their advertised capability");
  // Unexpected real external-memory work cannot be called profile-only.
  ControlPeer work(argc,argv,memory,captures);work.reset();Agent busy(memory);
  ReplayBus<ControlPeer> active(work,busy,1000);rejects([&]{probe_profile(work,busy,active);});

  const std::array<Command,5> transports{{{0x494,0x47544d01},{0x43c,0x47534d01},
    {0x3fc,0x47494d01},{0x4bc,0x47414d01},{0x460,0x47554d01}}};
  for(unsigned broken=0;broken<=transports.size();broken++) {
    ControlPeer compute(argc,argv,memory,captures);compute.reset();compute.model->issue_read=false;
    for(const auto &transport:transports)compute.model->registers[transport.address]=transport.value;
    if(broken<transports.size())compute.model->registers[transports[broken].address]^=1;
    Agent peer(memory);ReplayBus<ControlPeer> control(compute,peer,1000);
    if(broken<transports.size()) {rejects([&]{probe_profile(compute,peer,control,true);});continue;}
    const auto result=probe_profile(compute,peer,control,true);
    require(result.words.size()==96 && result.registers.size()==10 && !compute.submitted && compute.resets==1,
      "compute probe submitted work or lost raw observations");
    for(unsigned n=0;n<transports.size();n++)
      require(result.registers[5+n].address==transports[n].address &&
        result.registers[5+n].value==compute.model->registers[transports[n].address] &&
        compute.read_addresses[compute.read_addresses.size()-6+n]==transports[n].address,
        "compute profile did not preserve actual transport reads");
    std::ostringstream json;profile_json(json,result,true);
    require(json.str().find("opengpu-compute-owner-profile-v1")!=std::string::npos &&
      json.str().find("\"gpuExecution\":false")!=std::string::npos,"compute profile lost scope");
    rejects([&]{profile_json(json,result);});
  }
  ControlPeer unadvertised(argc,argv,memory,captures);unadvertised.reset();unadvertised.model->issue_read=false;
  unadvertised.model->registers[0x368]&=~0x1f0u;
  Agent peer(memory);ReplayBus<ControlPeer> control(unadvertised,peer,1000);
  rejects([&]{probe_profile(unadvertised,peer,control,true);});
  require(unadvertised.read_addresses==std::vector<uint32_t>({GPU_REG_ID,GPU_REG_CAPABILITIES,0x360,0x364,0x368}),
    "unadvertised compute commands were probed");
}
static void active_series(int argc,char **argv,bool cleanup=false,bool close_context=true) {
  auto a=fixture(),b=a;a.finish.clear();b.finish.clear();
  for(Capture *c:{&a,&b}) {
    c->discovery.push_back({GPU_REG_ID,GPU_ABI_COMPUTE_RESOURCE_VERSION});
    c->discovery.push_back({GPU_REG_CAPABILITIES,GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_NATIVE_PIXEL_FORMATS|(128u<<GPU_CAP_FRAGMENT_BATCH_SHIFT)});
    c->discovery.push_back({GPU_REG_BIN_CAPABILITY_PTR,GPU_REG_BIN_CAPABILITIES});c->discovery.push_back({0x320,128});
    c->discovery.push_back({GPU_REG_SHADER_CONFIG,0x02040810});
    c->validate(true);
  }
  rejects([&]{a.validate();});auto bad=a;bad.finish={{GPU_REG_UCMD_COMPLETION_POP,1}};rejects([&]{bad.validate(true);});
  a.writable={{0x2000,0x2040}};b.writable={{0x3000,0x3040}};
  b.command_id=42;b.trace[11].value=42;b.checks[0].expected=0x872a;
  Memory memory;memory.ranges={{0x1000,cleanup?0x7000u:0x4000u,0xa7}};
  if(cleanup) {
    // Explicit CPU protocol page tables; this peer executes no GPU shader.
    for(uint64_t address=0x1000;address<0x2000;address+=4)memory_word(memory,address,0);
    for(uint64_t address=0x2000;address<0x3000;address+=4)memory_word(memory,address,0);
    for(Capture *c:{&a,&b})c->trace[9].value=c->trace[10].value=0x80400001;
    b.trace[13].value=0x1001000;b.trace[15].value=0x1001200;
    memory_word(memory,0x1010,0x801);memory_word(memory,0x2000,0x1403);memory_word(memory,0x2004,0x1803);
    a.writable={{0x3000,0x3040}};b.writable={{0x4000,0x4040}};
  }
  const std::vector<Capture> captures{a,b};
  ControlPeer sim(argc,argv,memory,captures);sim.reset();sim.model->issue_read=false;
  Agent agent(memory);ReplayBus<ControlPeer> bus(sim,agent,100000);std::ostringstream report;
  const auto base=std::filesystem::path(std::getenv("TMPDIR"))/(cleanup?"active-owner-cleanup":"active-owner");
  require(std::filesystem::create_directory(base),"cannot create active CPU test directory");
  const auto output=base/"output";std::filesystem::create_directory(output);
  auto producer=std::async(std::launch::async,[&] {
    for(unsigned n=0;n<2;n++) {
      const auto stem="record-"+std::to_string(n);
      wait_input(base/(stem+".held.json"),base,std::chrono::seconds(5));
      std::ifstream held(base/(stem+".held.json"));std::string json((std::istreambuf_iterator<char>(held)),{});
      require(json.find("\"phase\":\"held\"")!=std::string::npos && json.find("\"heldLegacyStatus\":0")!=std::string::npos && json.find("retired")==std::string::npos,
        "held receipt falsely claims retirement");
      atomic_json(base/(stem+".retire"),[&](auto &out){out<<encode(retirement_for(captures[n]));});
      wait_input(base/(stem+".retired.json"),base,std::chrono::seconds(5));
      if(!n)atomic_json(base/"record-1.capture",[&](auto &out){out<<encode(b);});
    }
    if(cleanup) {
      atomic_json(base/"cleanup.begin",[](auto &out){out.write("OGCLB001",8);write_le(out,2,4);});
      for(unsigned n=0;n<(close_context?3u:2u);n++) {
        Cleanup batch;batch.actions={{Kind::pte_clear,0x2000+4*n,0,n?0x1803u:0x1403u},
          {Kind::write,GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_ASID|(1u<<GPU_TLB_FLUSH_ASID_SHIFT),0}};
        if(n==2)batch.actions={{Kind::write,GPU_REG_UCMD_VECTOR_SATP,0,0},
          {Kind::write,GPU_REG_UCMD_INSTRUCTION_SATP,0,0},{Kind::write,GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_FULL,0}};
        batch.validate();const auto stem="cleanup-"+std::to_string(n);
        atomic_json(base/(stem+".trace"),[&](auto &out){out<<encode(batch);});
        wait_input(base/(stem+".observed"),base,std::chrono::seconds(5));
        std::ifstream ack(base/(stem+".observed"),std::ios::binary);magic(ack,"OGCLA001");
        require(read_le(ack,4)==n && read_le(ack,4)==GPU_UCMD_STATUS_READY &&
          read_le(ack,4)==GPU_IRQ_ENABLE && !read_le(ack,4) && read_le(ack,4)==1 && ack.peek()==EOF,
          "cleanup acknowledged expected rather than actual idle state");
      }
      atomic_json(base/"cleanup.done",[&](auto &out){out.write("OGCLD001",8);write_le(out,close_context?3:2,4);});
    }
  });
  PtePages pte;
  try {
    active_records(sim,agent,bus,memory,output,base,a,2,report,std::chrono::seconds(5),cleanup?&pte:nullptr);
    if(cleanup)cleanup_records(sim,agent,bus,memory,base,pte,2,std::chrono::seconds(5));
  }
  catch(...){producer.get();std::filesystem::remove_all(base);throw;}
  producer.get();
  require(sim.resets==1 && sim.submitted==2 && memory.byte(a.writable[0].first)==0x51 && memory.byte(b.writable[0].first)==0x52,
    "active handshake reset, replaced RAM or failed to execute both records");
  const auto first=Memory::read((output/"record-0.memory").string()),second=Memory::read((output/"record-1.memory").string());
  require(first.byte(a.writable[0].first)==0x51 && first.byte(b.writable[0].first)==0xa7 &&
    second.byte(a.writable[0].first)==0x51 && second.byte(b.writable[0].first)==0x52,
    "active per-record output memories lost prior outputs");
  if(cleanup) {
    pte.revoked(memory);require(!PtePages::word(memory,0x2000) && !PtePages::word(memory,0x2004),"cleanup did not preserve actual PTE stores");
    std::ifstream in(base/"cleanup.json");std::string final((std::istreambuf_iterator<char>(in)),{});
    require(final.find("\"ownerCleanupObserved\":true")!=std::string::npos &&
      final.find("\"contextCleanupObserved\":true")!=std::string::npos &&
      final.find("\"pteClears\":2")!=std::string::npos && final.find("\"fpsQualified\":false")!=std::string::npos,
      "cleanup JSON lost observed cleanup or overstated FPS");
  }
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
static void cleanup_parser() {
  Cleanup c;c.actions={{Kind::pte_clear,0x2000,0,0x1403},
    {Kind::write,GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_ASID|8u,0}};c.validate();
  const auto wire=encode(c);std::istringstream good(wire);require(Cleanup::read(good).actions.size()==2,"cleanup parser lost actual stores");
  auto bad=c;bad.actions.pop_back();rejects([&]{bad.validate();});
  for(uint32_t address:{GPU_REG_UCMD_SUBMIT,GPU_REG_UCMD_COMPLETION_POP,GPU_REG_UCMD_RESET,GPU_REG_IRQ}) {
    bad=c;bad.actions[1].address=address;rejects([&]{bad.validate();});
  }
  bad=c;bad.actions[0].value=1;rejects([&]{bad.validate();});
  bad=c;bad.actions[0].address++;rejects([&]{bad.validate();});
  bad=c;bad.actions[0].mask=0;rejects([&]{bad.validate();});
  bad=c;bad.actions[1].value=GPU_TLB_FLUSH_VPN;rejects([&]{bad.validate();});
  bad=c;bad.actions.push_back(c.actions[1]);rejects([&]{bad.validate();});
  bad=c;std::reverse(bad.actions.begin(),bad.actions.end());rejects([&]{bad.validate();});
  bad=c;bad.actions[0]={Kind::write,GPU_REG_UCMD_VECTOR_SATP,0x80400001,0};bad.validate();
  bad.actions.pop_back();rejects([&]{bad.validate();});
  for(size_t size:{size_t(0),size_t(8),wire.size()-1}) {std::istringstream truncated(wire.substr(0,size));rejects([&]{Cleanup::read(truncated);});}
  std::istringstream trailing(wire+"x");rejects([&]{Cleanup::read(trailing);});
  Memory memory;memory.ranges={{0x1000,0x7000,0}};auto capture=fixture();
  capture.trace[9].value=capture.trace[10].value=0x80400001;
  memory_word(memory,0x1010,0x801);memory_word(memory,0x2000,0x1403);
  PtePages pte;pte.observe(memory,capture);require(pte.pages.size()==2,"actual SATP page discovery lost tables");
  pte.validate_scope(c);bad=c;bad.actions[1].value=GPU_TLB_FLUSH_ASID|16u;
  rejects([&]{pte.validate_scope(bad);}); // Actual ASID-1 PTEs cannot be flushed as ASID 2.
  auto shared=pte;auto other=capture;other.trace[9].value=other.trace[10].value=0x80800001;
  shared.observe(memory,other);rejects([&]{shared.validate_scope(c);});
  bad=c;bad.actions[1].value=GPU_TLB_FLUSH_FULL;shared.validate_scope(bad);
  // Actual context teardown restores the captured ASID-0 root before flushing
  // the retiring ASID-1 mappings. Selecting ASID 0 does not change PTE owners.
  auto global_memory=memory;memory_word(global_memory,0x3000,7);
  auto global_capture=capture;global_capture.trace.insert(global_capture.trace.begin(),
    {{Kind::write,GPU_REG_UCMD_VECTOR_SATP,0x80000003,0},
     {Kind::write,GPU_REG_UCMD_INSTRUCTION_SATP,0x80000003,0}});
  PtePages restored;restored.observe(global_memory,global_capture);
  auto restore=c;restore.actions.insert(restore.actions.begin()+1,
    {{Kind::write,GPU_REG_UCMD_VECTOR_SATP,0x80000003,0},
     {Kind::write,GPU_REG_UCMD_INSTRUCTION_SATP,0x80000003,0}});
  restored.validate_scope(restore);rejects([&]{pte.validate_scope(restore);});
  auto wrong_scope=restore;wrong_scope.actions.back().value=GPU_TLB_FLUSH_ASID;
  rejects([&]{restored.validate_scope(wrong_scope);});
  rejects([&]{auto aliased=restored;aliased.observe(global_memory,other);aliased.validate_scope(restore);});
  auto code=memory;memory_word(code,0x2004,0x180b);PtePages full_owner;full_owner.observe(code,capture);
  full_owner.clear(code,c.actions[0]);rejects([&]{full_owner.revoked(code);}); // GNJ-only cleanup cannot hide a retained RX leaf.
  full_owner.clear(code,{Kind::pte_clear,0x2004,0,0x180b});full_owner.revoked(code);
  auto global=memory;memory_word(global,0x2004,0x182b);rejects([&]{PtePages forbidden;forbidden.observe(global,capture);});
  global=memory;memory_word(global,0x1010,0x821);rejects([&]{PtePages forbidden;forbidden.observe(global,capture);});
  rejects([&]{pte.revoked(memory);});
  for(auto a:std::vector<Action>{{Kind::pte_clear,0x3000,0,0x1403},{Kind::pte_clear,0x2000,0,0x1803},
      {Kind::pte_clear,0x2000,1,0x1403},{Kind::pte_clear,0x2001,0,0x1403}})rejects([&]{pte.clear(memory,a);});
  pte.clear(memory,c.actions[0]);pte.revoked(memory);rejects([&]{pte.clear(memory,c.actions[0]);});
  Cleanup close;close.actions={{Kind::write,GPU_REG_UCMD_VECTOR_SATP,0,0},
    {Kind::write,GPU_REG_UCMD_INSTRUCTION_SATP,0,0},{Kind::write,GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_FULL,0}};
  close.validate();require(close.closes_context(),"lost final bare SATP shutdown");
  rejects([&]{pte.validate_scope(close);});pte.validate_scope(close,&memory);
  auto live=memory;memory_word(live,0x2000,0x1403);rejects([&]{pte.validate_scope(close,&live);});
  for(unsigned n=0;n<3;n++) {auto partial=close;partial.actions.erase(partial.actions.begin()+n);
    rejects([&]{pte.validate_scope(partial,&memory);});}
  auto partial=close;partial.actions[2].value=GPU_TLB_FLUSH_ASID|8u;
  rejects([&]{pte.validate_scope(partial,&memory);});
  partial=close;partial.actions.insert(partial.actions.begin(),c.actions[0]);
  rejects([&]{pte.validate_scope(partial,&memory);});
  partial=close;partial.actions.insert(partial.actions.begin(),close.actions[0]);
  rejects([&]{pte.validate_scope(partial,&memory);});
  auto corrupt=memory;memory_word(corrupt,0x1010,0x400001);rejects([&]{PtePages missing;missing.observe(corrupt,capture);});
  corrupt=memory;memory_word(corrupt,0x1010,0x1407);rejects([&]{PtePages leaf;leaf.observe(corrupt,capture);});
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
  q=c;q.checks[2].mask=0;rejects([&]{q.validate();});
  q=c;q.checks[3].mask=0;q.checks[3].expected=1;rejects([&]{q.validate();});
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
  retirement_parser();cleanup_parser();active_series(argc,argv);
  active_series(argc,argv,true);
  rejects([&]{active_series(argc,argv,true,false);});
  std::cout<<"native owner capture CPU protocol/guard checks: PASS\n";
}
