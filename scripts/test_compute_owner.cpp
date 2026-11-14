// CPU transport peer only. No instruction executes and no DUT is qualified.
#define main compute_owner_driver_entry
#include "compute_owner_driver.cpp"
#undef main
#include <sstream>
#include <future>

using namespace compute_owner;
template<class F> static void rejects(F action) {
  bool rejected=false;try {action();}catch(const std::runtime_error &){rejected=true;}
  require(rejected,"malformed compute owner input was accepted");
}
static ComputeCapture fixture() {
  ComputeCapture c;c.max_cycles=100000;c.hold_cycles=64;c.command_id=17;
  c.discovery=owner_factory_words(compute_owner_factory());
  for(const auto &r:ComputeCapture::transports())c.discovery.push_back(r);
  for(const auto &r:std::vector<Command>{{GPU_REG_ID,0x47550002},
      {GPU_REG_CAPABILITIES,GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_NATIVE_PIXEL_FORMATS|(128u<<GPU_CAP_FRAGMENT_BATCH_SHIFT)},
      {GPU_REG_BIN_CAPABILITY_PTR,GPU_REG_BIN_CAPABILITIES},{0x320,1048576},{GPU_REG_SHADER_CONFIG,0x02040810}})
    c.discovery.push_back(r);
  c.writable={{0x6000,0x6040}};
  auto write=[&](uint32_t a,uint32_t v){c.trace.push_back({Kind::write,a,v,0});};
  write(GPU_REG_IRQ,GPU_IRQ_ENABLE);
  write(GPU_REG_UCMD_VECTOR_SATP,0x80000003);write(GPU_REG_UCMD_INSTRUCTION_SATP,0x80000003);
  write(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_INVALIDATE);write(GPU_REG_UCMD_ID,16);
  write(GPU_REG_UCMD_SOURCE,0x5000);write(GPU_REG_UCMD_BYTES,4096);write(GPU_REG_UCMD_SUBMIT,1);
  c.trace.push_back({Kind::poll,GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_COMPLETION,GPU_UCMD_STATUS_COMPLETION});
  c.trace.push_back({Kind::check,GPU_REG_UCMD_COMPLETION,0x8510,0xffffffffu});
  write(GPU_REG_UCMD_COMPLETION_POP,1);write(GPU_REG_IRQ,3);
  write(GPU_REG_UCMD_VECTOR_SATP,0x80400001);write(GPU_REG_UCMD_INSTRUCTION_SATP,0x80400001);
  write(GPU_REG_UCMD_OPCODE,0);write(GPU_REG_UCMD_ID,17);write(GPU_REG_UCMD_KERNEL_PC,0x1000000);
  write(GPU_REG_UCMD_KERNARG,0x1001000);
  for(unsigned axis=0;axis<3;axis++) {write(GPU_REG_UCMD_GRID_X+4*axis,axis?1:4);write(GPU_REG_UCMD_LOCAL_X+4*axis,axis?1:128);}
  write(GPU_REG_UCMD_FLAGS,0);write(GPU_REG_UCMD_WAIT_EVENT,0);write(GPU_REG_UCMD_SIGNAL_EVENT,0);
  write(GPU_REG_UCMD_SUBMIT,1);c.validate();return c;
}
static std::string encode(const ComputeCapture &c) {
  std::ostringstream out;out.write("OGCMP001",8);write_le(out,c.max_cycles,8);write_le(out,c.hold_cycles,4);write_le(out,c.command_id,4);
  write_le(out,c.discovery.size(),4);for(const auto &r:c.discovery){write_le(out,r.address,4);write_le(out,r.value,4);}
  write_le(out,c.writable.size(),4);for(const auto &r:c.writable){write_le(out,r.first,8);write_le(out,r.end,8);}
  write_le(out,c.trace.size(),4);for(const auto &a:c.trace) {
    write_le(out,uint32_t(a.kind),4);write_le(out,a.address,4);write_le(out,a.value,4);write_le(out,a.mask,4);
  }return out.str();
}
static ComputeRetirement retirement() {
  ComputeRetirement r;r.actions={{Kind::poll,GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_COMPLETION,GPU_UCMD_STATUS_COMPLETION},
    {Kind::check,GPU_REG_UCMD_COMPLETION,0x8011,0xffffffffu},
    {Kind::check,GPU_REG_UCMD_COMPLETION_BYTES_LO,0,0xffffffffu},
    {Kind::check,GPU_REG_UCMD_COMPLETION_BYTES_HI,0,0xffffffffu},
    {Kind::write,GPU_REG_UCMD_COMPLETION_POP,1,0},{Kind::write,GPU_REG_IRQ,3,0}};r.validate();return r;
}
static std::string encode(const ComputeRetirement &r) {
  std::ostringstream out;out.write("OGCMT001",8);write_le(out,r.actions.size(),4);
  for(const auto &a:r.actions) {write_le(out,uint32_t(a.kind),4);write_le(out,a.address,4);write_le(out,a.value,4);write_le(out,a.mask,4);}
  return out.str();
}
static void put(Memory &m,uint64_t address,uint32_t value) {
  for(unsigned n=0;n<4;n++)m.byte(address+n,value>>(8*n));
}
static Memory memory_fixture() {
  Memory m;m.ranges={{0x1000,0x7000,0}};put(m,0x1010,0x801);put(m,0x2000,0x140f);put(m,0x2004,0x1807);
  put(m,0x3000,7);return m;
}
class ComputePeer:public Simulation {
  Memory &memory;
public:
  unsigned submitted=0,resets=0;
  uint64_t completed_at=0;
  bool wrong_completion=false,nonzero_bytes=false,corrupt_readonly=false,unstable=false,late_write=false;
  std::vector<uint32_t> reads;
  ComputePeer(int argc,char **argv,Memory &m):Simulation(argc,argv),memory(m) {}
  void reset() {
    ++resets;Simulation::reset();model->mmio_enabled=true;model->issue_read=false;
    for(const auto &r:fixture().discovery)model->registers[r.address]=r.value;
    model->registers[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_READY;
  }
  template<class Observe> void step(Agent &agent,uint64_t cycle,Observe observe) {
    bool written=false;Command write{};
    Simulation::step(agent,cycle,[&](const auto &m) {
      if(m.io_s_axi_arvalid && m.io_s_axi_arready)reads.push_back(m.io_s_axi_araddr);
      if(m.io_s_axi_awvalid && m.io_s_axi_awready && m.io_s_axi_wvalid && m.io_s_axi_wready) {
        written=true;write={m.io_s_axi_awaddr,m.io_s_axi_wdata};
      }observe(m);
    });
    auto &r=model->registers;
    if(submitted && unstable && cycle==completed_at+40)r[GPU_REG_UCMD_COMPLETION_BYTES_HI]^=1;
    if(submitted && late_write && cycle==completed_at+90)memory.byte(0x6000,0xee);
    if(!written)return;
    if(write.address==GPU_REG_UCMD_SUBMIT) {
      r[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY;
      r[GPU_REG_UCMD_COMPLETION]=0x8000u|(r[GPU_REG_UCMD_OPCODE]<<8)|r[GPU_REG_UCMD_ID];
      if(r[GPU_REG_UCMD_OPCODE]==GPU_UCMD_OP_KERNEL) {
        ++submitted;completed_at=cycle;model->io_m_irq=1;r[GPU_REG_IRQ]=GPU_IRQ_ENABLE|GPU_IRQ_PENDING;
        if(wrong_completion)r[GPU_REG_UCMD_COMPLETION]^=1;
        if(nonzero_bytes)r[GPU_REG_UCMD_COMPLETION_BYTES_LO]=4;
        memory.byte(corrupt_readonly?0x5000:0x6000,0x55); // CPU-only protocol peer.
      }
    }else if(write.address==GPU_REG_UCMD_COMPLETION_POP) {
      r[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_READY;r[GPU_REG_UCMD_COMPLETION]=0;model->io_m_irq=0;
    }else if(write.address==GPU_REG_IRQ)r[GPU_REG_IRQ]=write.value&1u;
  }
};
static void deadline_checks() {
  require(compute_response_timeout(nullptr)==std::chrono::seconds(300),"changed standalone compute timeout default");
  for(const auto &item:std::vector<std::pair<const char *,unsigned>>{
      {"1",1},{"0001",1},{"3600",3600},{"604800",604800}})
    require(compute_response_timeout(item.first)==std::chrono::seconds(item.second),"lost compute phase timeout");
  for(const char *value:{"","0","604801","99999999999999999999999999999","-1","+1"," 1","1 ","1x","1.0"})
    rejects([&]{compute_response_timeout(value);});
  const char *inherited=std::getenv("OPENGPU_COMPUTE_HOST_TIMEOUT");
  const bool had_value=inherited;const std::string saved=inherited?inherited:"";
  require(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT","3600",1),"cannot configure compute timeout test");
  require(compute_response_timeout()==std::chrono::hours(1),"executor ignored the actual producer timeout environment");
  require(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT","0",1),"cannot configure rejected timeout");
  std::string program="compute-owner",mode="--probe-profile";
  std::string profile=(std::filesystem::path(std::getenv("TMPDIR"))/"rejected-timeout-profile.json").string();
  char *arguments[]={program.data(),mode.data(),profile.data()};
  std::ostringstream error;auto *previous=std::cerr.rdbuf(error.rdbuf());
  const auto status=compute_owner_driver_entry(3,arguments);std::cerr.rdbuf(previous);
  require(status==1 && error.str().find("timeout")!=std::string::npos && !std::filesystem::exists(profile),
    "malformed timeout reached the DUT profile/reset phase");
  if(had_value)require(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT",saved.c_str(),1),"cannot restore compute timeout");
  else require(!unsetenv("OPENGPU_COMPUTE_HOST_TIMEOUT"),"cannot restore absent compute timeout");
}
static void parser_checks() {
  const auto c=fixture();const auto wire=encode(c);std::istringstream input(wire);
  require(ComputeCapture::read(input).discovery.size()==106,"lost compute 96+10 profile");
  for(unsigned n=0;n<96;n++) {
    auto bad=c;bad.discovery[n].value^=1;rejects([&]{bad.validate();});
  }
  auto wrong_factory=c;wrong_factory.discovery[2].value^=1u<<18;
  rejects([&]{wrong_factory.validate();});
  auto mutate=[&](uint32_t address,uint32_t value) {
    auto bad=c;bool found=false;
    for(auto &a:bad.trace)if(a.kind==Kind::write && a.address==address){a.value=value;found=true;}
    require(found,"missing test mutation target");rejects([&]{bad.validate();});
  };
  for(const auto &r:ComputeCapture::transports()) {
    auto bad=c;for(auto &w:bad.discovery)if(w.address==r.address)w.value^=1;rejects([&]{bad.validate();});
  }
  for(uint32_t batch:{0u,64u,127u,255u}) {
    auto bad=c;for(auto &w:bad.discovery)if(w.address==GPU_REG_CAPABILITIES)
      w.value=(w.value&~GPU_CAP_FRAGMENT_BATCH_MASK)|(batch<<GPU_CAP_FRAGMENT_BATCH_SHIFT);
    rejects([&]{bad.validate();});
  }
  for(uint32_t flag:{GPU_CAP_UNIFIED_COMMANDS,GPU_CAP_PROTECTED_VM_STATE,GPU_CAP_COMPUTE_SCALAR_FPU,
                     GPU_CAP_SHADER_CONFIG_INFO,GPU_CAP_NATIVE_PIXEL_FORMATS}) {
    auto bad=c;for(auto &w:bad.discovery)if(w.address==GPU_REG_CAPABILITIES)w.value&=~flag;
    rejects([&]{bad.validate();});
  }
  for(uint32_t shader:{0x02000810u,0x02010810u,0x02030810u,0x04040810u}) {
    auto bad=c;bad.discovery.back().value=shader;rejects([&]{bad.validate();});
  }
  for(uint32_t shader:{0x02020810u,0x02100810u}) {
    auto valid=c;valid.discovery.back().value=shader;valid.validate();
    std::istringstream raw(encode(valid));
    require(ComputeCapture::read(raw).discovery.back().value==shader,"capture changed a legal raw bank count");
  }
  auto bad=c;bad.discovery.pop_back();rejects([&]{bad.validate();});
  bad=c;bad.discovery.back()=bad.discovery.front();rejects([&]{bad.validate();});
  bad=c;bad.discovery[2].value=0x7fff;rejects([&]{bad.validate();});
  bad=c;bad.writable.push_back({0x6008,0x6100});rejects([&]{bad.validate();});
  bad=c;bad.trace.push_back({Kind::write,GPU_REG_IRQ,3,0});rejects([&]{bad.validate();});
  bad=c;bad.trace.insert(bad.trace.begin(),{Kind::write,GPU_REG_UCMD_RESET,1,0});rejects([&]{bad.validate();});
  bad=c;bad.trace.insert(bad.trace.begin(),{Kind::check,0x180,0,0xffffffffu});rejects([&]{bad.validate();});
  mutate(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_NATIVE_BINNED_RENDER);mutate(GPU_REG_UCMD_KERNEL_PC,3);
  mutate(GPU_REG_UCMD_GRID_X,0);mutate(GPU_REG_UCMD_LOCAL_Y,2);mutate(GPU_REG_UCMD_FLAGS,1);
  mutate(GPU_REG_UCMD_VECTOR_SATP,0);mutate(GPU_REG_UCMD_INSTRUCTION_SATP,0x80000001);
  for(size_t size:{size_t(0),size_t(8),wire.size()-1}) {
    std::istringstream short_input(wire.substr(0,size));rejects([&]{ComputeCapture::read(short_input);});
  }
  std::istringstream extra(wire+"x");rejects([&]{ComputeCapture::read(extra);});
  auto native_wire=wire;native_wire.replace(0,8,"OGCAP001");std::istringstream wrong(native_wire);
  rejects([&]{ComputeCapture::read(wrong);});
  const auto r=retirement();const auto rw=encode(r);std::istringstream rin(rw);ComputeRetirement::read(rin);
  for(unsigned n=0;n<r.actions.size();n++) {auto invalid=r;invalid.actions.erase(invalid.actions.begin()+n);
    if(n!=5)rejects([&]{invalid.validate();});}
  auto invalid=r;invalid.actions.insert(invalid.actions.begin()+1,{Kind::check,0x180,0,0xffffffffu});
  rejects([&]{invalid.validate();});invalid=r;invalid.actions.push_back(r.actions[1]);rejects([&]{invalid.validate();});
  invalid=r;invalid.actions.push_back(r.actions[4]);rejects([&]{invalid.validate();});
  auto native_retirement=rw;native_retirement.replace(0,8,"OGRTR001");std::istringstream wrong_retire(native_retirement);
  rejects([&]{ComputeRetirement::read(wrong_retire);});
}
static void profile_checks(int argc,char **argv) {
  auto memory=memory_fixture();ComputePeer sim(argc,argv,memory);sim.reset();
  Agent agent(memory);ReplayBus<ComputePeer> bus(sim,agent,100000);
  const auto profile=probe_profile(sim,agent,bus,true,compute_owner_factory());
  require(!sim.submitted && profile.words.size()==96 && profile.registers.size()==10,
    "compute profile probe submitted work or lost actual observations");
  require(std::vector<uint32_t>(sim.reads.begin(),sim.reads.begin()+5)==
    std::vector<uint32_t>({GPU_REG_ID,GPU_REG_CAPABILITIES,0x360,0x364,0x368}),
    "compute probe read dependent windows before factory presence");
  const auto legacy=Capture::profile(),selected=owner_factory_words(compute_owner_factory());
  for(size_t n=0;n<selected.size();n++)require(selected[n].address==legacy[n].address &&
    selected[n].value==(legacy[n].value|(n==2 && compute_owner_factory()==OwnerFactory::compute_stage?1u<<18:0u)),
    "compute-stage selection changed the frozen native factory");
  // Python consumes this actual CPU MMIO peer observation through the same
  // strict profile parser used by the live controller. No GPU executes here.
  atomic_json(std::filesystem::path(std::getenv("TMPDIR"))/"profile.json",
    [&](auto &out){profile_json(out,profile,true);});
  for(unsigned n=0;n<selected.size();n++) {
    auto m=memory_fixture();ComputePeer peer(argc,argv,m);peer.reset();
    peer.model->registers[selected[n].address]^=1;
    Agent a(m);ReplayBus<ComputePeer> b(peer,a,100000);
    rejects([&]{probe_profile(peer,a,b,true,compute_owner_factory());});
    require(!peer.submitted,"wrong factory profile submitted a kernel");
  }
  auto m=memory_fixture();ComputePeer peer(argc,argv,m);peer.reset();
  peer.model->registers[0x368]^=1u<<18;Agent a(m);ReplayBus<ComputePeer> b(peer,a,100000);
  rejects([&]{probe_profile(peer,a,b,true,compute_owner_factory());});
  require(!peer.submitted && peer.reads==std::vector<uint32_t>({GPU_REG_ID,GPU_REG_CAPABILITIES,0x360,0x364,0x368}),
    "opposite factory was accepted or probed dependent windows");
}
static void execution_checks(int argc,char **argv) {
  const auto c=fixture();auto memory=memory_fixture();ComputePeer sim(argc,argv,memory);sim.reset();
  Agent agent(memory);ReplayBus<ComputePeer> bus(sim,agent,100000);PtePages pte;pte.observe_tables(memory,c.trace);
  require(pte.required_revocations.size()==2,"compute lost original code/data PTEs");
  auto held=hold_compute(sim,agent,bus,memory,c);
  require(sim.resets==1 && sim.submitted==1 && memory.byte(0x6000)==0x55,"CPU control peer lost output or reset count");
  std::ostringstream json;compute_json(json,held.result,c.command_id);
  require(json.str().find("opengpu-compute-owner-held-v1")!=std::string::npos &&
    json.str().find("\"processedBytes\":0")!=std::string::npos && json.str().find("gnx")==std::string::npos,
    "compute receipt borrowed native fields");
  const auto result=retire_compute(sim,agent,bus,memory,held,retirement());
  require(result.retired_status==GPU_UCMD_STATUS_READY && result.retired_irq==GPU_IRQ_ENABLE && !result.retired_pin,
    "compute retirement lost raw observations");
  require(std::none_of(sim.reads.begin(),sim.reads.end(),[](auto address){return address>=0x180 && address<0x1b0;}),
    "compute executor read native GNX");
  for(unsigned scenario=0;scenario<6;scenario++) {
    auto m=memory_fixture();ComputePeer peer(argc,argv,m);peer.reset();Agent a(m);ReplayBus<ComputePeer> b(peer,a,100000);
    peer.wrong_completion=scenario==0;peer.nonzero_bytes=scenario==1;peer.corrupt_readonly=scenario==2;
    peer.unstable=scenario==3;peer.late_write=scenario==4;
    if(scenario==5)peer.model->registers[0x494]^=1;
    rejects([&]{auto h=hold_compute(peer,a,b,m,c);retire_compute(peer,a,b,m,h,retirement());});
    if(scenario==5)require(!peer.submitted,"bad compute transport executed a kernel");
  }
}
static void phase_deadline_checks(int argc,char **argv) {
  for(unsigned scenario=0;scenario<7;scenario++) {
    const auto session=std::filesystem::path(std::getenv("TMPDIR"))/("compute-timeout-"+std::to_string(scenario));
    require(std::filesystem::create_directory(session),"cannot create compute deadline session");
    const auto output=session/"output";std::filesystem::create_directory(output);
    const auto timeout=std::chrono::milliseconds(20);
    rejects([&]{wait_input(session/"record-0.capture",session,timeout);});
    if(scenario)atomic_json(session/"record-0.retire",[&](auto &out) {
      if(scenario==1){out.write("OGCMT001",8);write_le(out,1,4);}else out<<encode(retirement());
    });
    if(scenario>=3)atomic_json(session/"cleanup.begin",[&](auto &out) {
      out.write("OGCLB001",8);if(scenario!=3)write_le(out,1,4);
    });
    if(scenario==5)atomic_json(session/"cleanup-0.trace",[](auto &out) {
      out.write("OGCLN001",8);write_le(out,1,4);
    });
    if(scenario==6)atomic_json(session/"cleanup.done",[](auto &out){out.write("OGCLD001",8);});
    auto memory=memory_fixture();ComputePeer sim(argc,argv,memory);sim.reset();
    Agent agent(memory);ReplayBus<ComputePeer> bus(sim,agent,100000);
    rejects([&]{active_compute(sim,agent,bus,memory,fixture(),output,session,timeout);});
    require(!std::filesystem::exists(session/"cleanup.json") && sim.resets==1 &&
      memory.byte(0x6000)==0x55 && PtePages::word(memory,0x2000)==0x140f && PtePages::word(memory,0x2004)==0x1807,
      "missing/truncated IPC authorized cleanup, replaced RAM or reset the compute owner");
    std::filesystem::remove_all(session);
  }
}
static void active_protocol(int argc,char **argv) {
  auto memory=memory_fixture();const auto capture=fixture();ComputePeer sim(argc,argv,memory);sim.reset();
  Agent agent(memory);ReplayBus<ComputePeer> bus(sim,agent,100000);
  const auto session=std::filesystem::path(std::getenv("TMPDIR"))/"compute-active";
  require(std::filesystem::create_directory(session),"cannot create compute CPU session");
  const auto output=session/"output";std::filesystem::create_directory(output);
  const auto timeout=std::chrono::seconds(5);
  auto producer=std::async(std::launch::async,[&] {
    wait_input(session/"record-0.held.json",session,timeout);
    atomic_json(session/"record-0.retire",[&](auto &out){out<<encode(retirement());});
    wait_input(session/"record-0.retired.json",session,timeout);
    const auto returned=Memory::read((output/"record-0.memory").string());
    require(returned.byte(0x6000)==0x55,"CPU protocol export lost the original output");
    atomic_json(session/"cleanup.begin",[](auto &out){out.write("OGCLB001",8);write_le(out,1,4);});
    for(unsigned n=0;n<3;n++) {
      Cleanup c;
      if(!n)c.actions={{Kind::pte_clear,0x2000,0,0x140f},{Kind::pte_clear,0x2004,0,0x1807},
        {Kind::write,GPU_REG_UCMD_TLB_FLUSH,GPU_TLB_FLUSH_ASID|8u,0}};
      else c.actions={{Kind::write,GPU_REG_UCMD_VECTOR_SATP,n==1?0x80000003u:0u,0},
        {Kind::write,GPU_REG_UCMD_INSTRUCTION_SATP,n==1?0x80000003u:0u,0},
        {Kind::write,GPU_REG_UCMD_TLB_FLUSH,n==1?(GPU_TLB_FLUSH_ASID|8u):GPU_TLB_FLUSH_FULL,0}};
      c.validate();const auto stem="cleanup-"+std::to_string(n);
      atomic_json(session/(stem+".trace"),[&](auto &out) {
        out.write("OGCLN001",8);write_le(out,c.actions.size(),4);
        for(const auto &a:c.actions) {write_le(out,uint32_t(a.kind),4);write_le(out,a.address,4);write_le(out,a.value,4);write_le(out,a.mask,4);}
      });
      wait_input(session/(stem+".observed"),session,timeout);
      std::ifstream ack(session/(stem+".observed"),std::ios::binary);magic(ack,"OGCLA001");
      require(read_le(ack,4)==n && read_le(ack,4)==GPU_UCMD_STATUS_READY && read_le(ack,4)==GPU_IRQ_ENABLE &&
        !read_le(ack,4) && read_le(ack,4)==1 && ack.peek()==EOF,"compute cleanup lost actual ACK fields");
    }
    atomic_json(session/"cleanup.done",[](auto &out){out.write("OGCLD001",8);write_le(out,3,4);});
  });
  try {active_compute(sim,agent,bus,memory,capture,output,session,timeout);producer.get();}
  catch(...) {if(producer.valid())producer.get();std::filesystem::remove_all(session);throw;}
  std::ifstream final(session/"cleanup.json");std::string json((std::istreambuf_iterator<char>(final)),{});
  require(json.find("\"contextCleanupObserved\":true")!=std::string::npos &&
    json.find("\"records\":1")!=std::string::npos && sim.resets==1 && sim.submitted==1 &&
    memory.byte(0x6000)==0x55 && !PtePages::word(memory,0x2000) && !PtePages::word(memory,0x2004),
    "compute cleanup lost context, retained PTEs or replaced output RAM");
  std::filesystem::remove_all(session);
}
int main(int argc,char **argv) {
  if(argc==3) {const auto m=Memory::read(argv[1]);const auto c=ComputeCapture::read(argv[2]);
    verify_readonly(m,m,c.writable);PtePages p;p.observe_tables(m,c.trace);return 0;}
  deadline_checks();parser_checks();profile_checks(argc,argv);execution_checks(argc,argv);phase_deadline_checks(argc,argv);active_protocol(argc,argv);
  std::cout<<"compute owner CPU protocol/guard checks: PASS\n";
}
