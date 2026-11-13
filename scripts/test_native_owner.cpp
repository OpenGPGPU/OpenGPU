// CPU protocol/guard tests only. The model stub does not execute GPU work.
#define main owner_driver_entry
#include "native_owner_driver.cpp"
#undef main
#include <sstream>

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
  write(GPU_REG_UCMD_ID,41);write(GPU_REG_UCMD_OPCODE,GPU_UCMD_OP_RENDER);
  write(GPU_REG_UCMD_SOURCE,0x1000000);write(GPU_REG_UCMD_BYTES,512);write(GPU_REG_UCMD_SUBMIT,1);
  c.checks.push_back({GPU_REG_UCMD_COMPLETION,0xffffffffu,0x8629});
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
// A CPU-only control peer injects completion and scratch bytes. It executes no
// GPU instruction; it tests retirement, record budgets and persistent RAM using
// the same MMIO/AXI clock service as the real executor.
class ControlPeer:public Simulation {
  Memory &memory;
  const std::vector<Capture> &captures;
public:
  size_t submitted=0;unsigned resets=0;bool corrupt_previous=false,unstable_gnx=false;
  uint64_t completion_cycle=0;
  ControlPeer(int argc,char **argv,Memory &m,const std::vector<Capture> &c)
    :Simulation(argc,argv),memory(m),captures(c) {}
  void reset() {
    ++resets;Simulation::reset();model->mmio_enabled=true;
    for(const auto &r:Capture::profile())model->registers[r.address]=r.value;
  }
  template<class Observe> void step(Agent &agent,uint64_t cycle,Observe observe) {
    bool written=false;Command write{};
    Simulation::step(agent,cycle,[&](const auto &m) {
      if(m.io_s_axi_awvalid && m.io_s_axi_awready && m.io_s_axi_wvalid && m.io_s_axi_wready) {
        written=true;write={m.io_s_axi_awaddr,m.io_s_axi_wdata};
      }
      observe(m);
    });
    auto &r=model->registers;
    if(unstable_gnx && submitted && cycle==completion_cycle+35)r[0x184]^=1;
    if(!written)return;
    if(write.address==GPU_REG_UCMD_SUBMIT) {
      r[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION;
      r[GPU_REG_UCMD_COMPLETION]=0x8000|(r[GPU_REG_UCMD_OPCODE]<<8)|r[GPU_REG_UCMD_ID];
      if(r[GPU_REG_UCMD_OPCODE]==GPU_UCMD_OP_RENDER) {
        require(submitted<captures.size(),"unexpected CPU peer native command");
        if(submitted)require(memory.byte(captures[0].writable[0].first)==0x51,
          "next record replaced prior output RAM");
        const auto &c=captures[submitted];
        for(const auto &check:c.checks)r[check.address]=check.expected;
        memory.byte(c.writable[0].first,uint8_t(0x51+submitted));
        if(submitted && corrupt_previous)memory.byte(captures[0].writable[0].first,0xff);
        ++submitted;completion_cycle=cycle;model->io_m_irq=1;
      }
    } else if(write.address==GPU_REG_UCMD_COMPLETION_POP) {
      r[GPU_REG_UCMD_STATUS]=GPU_UCMD_STATUS_READY;model->io_m_irq=0;
    } else if(write.address==GPU_REG_IRQ)model->io_m_irq=0;
  }
};
static void record_series(int argc,char **argv,bool corrupt) {
  auto a=fixture(),b=a;a.max_cycles=b.max_cycles=400;
  a.writable={{0x2000,0x2040}};b.writable={{0x3000,0x3040}};
  b.command_id=42;b.trace[11].value=42;b.checks[0].expected=0x862a;
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
int main() {
  const auto c=fixture();
  require(c.discovery.size()==88,"complete native capture requires all 88 mandatory discovery words");
  std::istringstream valid(encode(c));const auto parsed=Capture::read(valid);
  require(parsed.trace.size()==c.trace.size() && parsed.discovery.size()==88,"capture decoding lost records");
  for(size_t i=0;i<c.discovery.size();++i) {
    auto q=c;q.discovery[i].value^=1;rejects([&]{q.validate();});
    q=c;q.discovery.erase(q.discovery.begin()+i);rejects([&]{q.validate();});
  }
  auto q=c;q.discovery.push_back(q.discovery[0]);rejects([&]{q.validate();});
  q=c;q.discovery[2].value=0x7fff;rejects([&]{q.validate();}); // Frozen v2 lacks GNP and advanced transport.
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
  std::cout<<"native owner capture CPU protocol/guard checks: PASS\n";
}
