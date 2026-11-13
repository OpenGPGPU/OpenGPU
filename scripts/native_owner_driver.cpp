// Replay only actual production-owner captures. No shader, descriptor, VA or
// output byte is generated here. An independent image oracle remains required.
#include "native_owner_capture.hpp"
#include "native_host_simulation.hpp"
#include <filesystem>
#include <iostream>
#include <limits>

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
struct RecordResult { uint64_t cycles, read_bytes, write_bytes; };
template<class Sim>
RecordResult replay_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,
                          Memory &memory,const Capture &capture) {
  capture.validate();
  require(agent.drained() && !sim.model->io_m_irq,"owner record starts before prior retirement/drain");
  const Memory initial=memory;
  verify_readonly(initial,memory,capture.writable);
  const auto start_cycles=bus.cycles;const auto start_traffic=agent.traffic;
  bus.begin_record(capture.max_cycles);
  for(const auto &r:capture.discovery) require(bus.read(r.address)==r.value,"actual DUT differs from captured discovery");
  require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION),
    "owner record starts with an unretired completion");
  for(const auto &a:capture.trace)bus.action(a);
  while(!sim.model->io_m_irq)bus.step();
  require(agent.drained(),"native completion preceded external memory drain");
  const auto completed=agent.traffic;
  const auto completion=bus.read(GPU_REG_UCMD_COMPLETION);
  std::vector<uint32_t> held;
  for(const auto &c:capture.checks) {
    const auto value=bus.read(c.address);
    require((value&c.mask)==c.expected,"native completion/GNX check differs");held.push_back(value);
  }
  for(uint32_t n=0;n<capture.hold_cycles;++n) {
    require(sim.model->io_m_irq,"native completion IRQ disappeared while held");bus.step();
    require(agent.drained(),"held native completion leaked external traffic");
  }
  require(bus.read(GPU_REG_UCMD_COMPLETION)==completion,"held native completion changed");
  for(size_t n=0;n<capture.checks.size();++n)
    require(bus.read(capture.checks[n].address)==held[n],"held native completion/GNX changed");
  require(sim.model->io_m_irq,"native completion IRQ disappeared before retirement");
  traffic_unchanged(completed,agent.traffic);
  for(const auto &w:capture.finish)bus.write(w.address,w.value);
  require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION),"native completion POP did not retire the slot");
  for(unsigned n=0;n<8;++n)bus.step();
  traffic_unchanged(completed,agent.traffic);
  require(agent.drained() && !agent.traffic.read_faults && !agent.traffic.write_faults,
    "owner replay faulted or left external traffic");
  require(!sim.model->io_m_irq,"native completion retirement left an asserted IRQ");
  verify_readonly(initial,memory,capture.writable);
  return {bus.cycles-start_cycles,agent.traffic.read_bus_bytes-start_traffic.read_bus_bytes,
    agent.traffic.write_bus_bytes-start_traffic.write_bus_bytes};
}
inline void report_record(const RecordResult &r,size_t index,uint32_t command_id,uint64_t cycles) {
  std::cout<<"{\"ownerReplay\":true,\"record\":"<<index<<",\"commandId\":"<<command_id
    <<",\"serviceCycles\":"<<r.cycles<<",\"cumulativeServiceCycles\":"<<cycles
    <<",\"readBusBytes\":"<<r.read_bytes<<",\"writeBusBytes\":"<<r.write_bytes
    <<",\"resets\":1,\"resetsBetweenRecords\":false,\"heldCompletionDrained\":true,"
      "\"readOnlyGuards\":true,\"independentImagesRequired\":true,"
      "\"sourceQualificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}\n";
}
} // namespace native_owner

int main(int argc,char **argv) {
  try {
    using namespace native_owner;
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
