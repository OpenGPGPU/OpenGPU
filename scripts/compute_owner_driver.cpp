// Execute original Linux opcode-0 captures on the complete owner DUT. The
// producer supplies the original memory; an independent byte oracle is required.
#include "compute_owner_capture.hpp"
#include "native_owner_runtime.hpp"
#include <cstdlib>

namespace compute_owner {
// Match the live producer/controller wall-clock contract. This is separate
// from the captured DUT cycle budget and does not relax a command or oracle.
inline std::chrono::milliseconds compute_response_timeout(
    const char *value=std::getenv("OPENGPU_COMPUTE_HOST_TIMEOUT")) {
  if(!value)return std::chrono::seconds(300);
  require(*value,"empty compute host timeout");
  uint32_t seconds=0;
  for(;*value;value++) {
    require(*value>='0' && *value<='9',"compute host timeout must be decimal seconds");
    const auto digit=uint32_t(*value-'0');
    require(seconds<=(604800u-digit)/10u,"compute host timeout exceeds 604800 seconds");
    seconds=seconds*10u+digit;
  }
  require(seconds,"compute host timeout must be positive");
  return std::chrono::seconds(seconds);
}
struct ComputeResult {
  uint32_t completion=0,legacy=0,status=0,irq=0,retired_status=0,retired_irq=0;
  uint64_t processed_bytes=0,cycles=0,read_bytes=0,write_bytes=0;
  bool held_pin=false,retired_pin=false,drained=false;
};
struct ComputeHeld {
  ComputeResult result;
  Memory initial;
  Traffic start,completed;
  uint64_t start_cycles=0;
};
template<class Sim>
ComputeHeld hold_compute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,const ComputeCapture &capture) {
  capture.validate();
  require(agent.drained() && !sim.model->io_m_irq,"compute starts before prior drain");
  const auto profile=probe_profile(sim,agent,bus,true);
  std::map<uint32_t,uint32_t> captured;
  for(const auto &r:capture.discovery)captured.emplace(r.address,r.value);
  for(const auto *words:{&profile.words,&profile.registers})for(const auto &r:*words)
    require(captured.at(r.address)==r.value,"compute capture differs from actual DUT observations");
  verify_readonly(memory,memory,capture.writable);
  ComputeHeld held;held.initial=memory;held.start=agent.traffic;held.start_cycles=bus.cycles;
  bus.begin_record(capture.max_cycles);
  for(const auto &a:capture.trace)bus.action(a);
  while(!sim.model->io_m_irq)bus.step();
  require(agent.drained(),"compute completion preceded external drain");held.completed=agent.traffic;
  auto &r=held.result;
  auto observe=[&]() {
    ComputeResult now;
    now.legacy=bus.read(GPU_REG_STATUS);now.status=bus.read(GPU_REG_UCMD_STATUS);now.irq=bus.read(GPU_REG_IRQ);
    now.completion=bus.read(GPU_REG_UCMD_COMPLETION);
    const auto lo=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO),hi=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI);
    now.processed_bytes=(uint64_t(hi)<<32)|lo;now.held_pin=bool(sim.model->io_m_irq);return now;
  };
  r=observe();
  require(r.completion==(0x8000u|capture.command_id) && !r.processed_bytes,
    "compute completion identity, success or telemetry differs");
  require(!r.legacy && (r.status&GPU_UCMD_STATUS_COMPLETION) &&
      !(r.status&(GPU_UCMD_STATUS_OVERFLOW|GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_RESET_REJECTED)) &&
      r.irq==(GPU_IRQ_ENABLE|GPU_IRQ_PENDING) && r.held_pin,"compute held status/IRQ differs");
  for(uint32_t n=0;n<capture.hold_cycles;n++) {
    require(sim.model->io_m_irq,"compute held IRQ disappeared");bus.step();
    require(agent.drained(),"held compute leaked external traffic");
  }
  const auto again=observe();
  require(r.legacy==again.legacy && r.status==again.status && r.irq==again.irq &&
      r.completion==again.completion && r.processed_bytes==again.processed_bytes && again.held_pin,
    "held compute completion or telemetry changed");
  traffic_unchanged(held.completed,agent.traffic);
  require(!agent.traffic.read_faults && !agent.traffic.write_faults,"compute encountered physical memory faults");
  verify_readonly(held.initial,memory,capture.writable);
  r.drained=agent.drained();r.cycles=bus.cycles-held.start_cycles;
  r.read_bytes=agent.traffic.read_bus_bytes-held.start.read_bus_bytes;
  r.write_bytes=agent.traffic.write_bus_bytes-held.start.write_bus_bytes;
  held.initial=memory;return held;
}
template<class Sim>
ComputeResult retire_compute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    ComputeHeld &held,const ComputeRetirement &retirement) {
  retirement.validate();
  require(sim.model->io_m_irq && agent.drained(),"compute retirement lost its held receipt");
  traffic_unchanged(held.completed,agent.traffic);
  for(const auto &a:retirement.actions)bus.action(a);
  auto &r=held.result;r.retired_status=bus.read(GPU_REG_UCMD_STATUS);
  require(r.retired_status==GPU_UCMD_STATUS_READY,"compute POP did not leave an idle ready slot");
  for(unsigned n=0;n<8;n++)bus.step();
  r.retired_irq=bus.read(GPU_REG_IRQ);r.retired_pin=bool(sim.model->io_m_irq);
  require(r.retired_irq==GPU_IRQ_ENABLE && !r.retired_pin && agent.drained(),"compute retirement did not drain IRQ");
  traffic_unchanged(held.completed,agent.traffic);
  require(!agent.traffic.read_faults && !agent.traffic.write_faults,"compute retirement encountered physical memory faults");
  verify_readonly(held.initial,memory,{}); // No RAM changes are permitted after held completion.
  r.drained=agent.drained();r.cycles=bus.cycles-held.start_cycles;
  r.read_bytes=agent.traffic.read_bus_bytes-held.start.read_bus_bytes;
  r.write_bytes=agent.traffic.write_bus_bytes-held.start.write_bus_bytes;return r;
}
inline void compute_json(std::ostream &out,const ComputeResult &r,uint32_t command_id,bool retired=false) {
  out<<"{\"schema\":\"opengpu-compute-owner-"<<(retired?"retired":"held")<<"-v1\",\"phase\":\""
    <<(retired?"retired":"held")<<"\",\"record\":0,\"commandId\":"<<command_id
    <<",\"completion\":"<<r.completion<<",\"processedBytes\":"<<r.processed_bytes
    <<",\"heldLegacyStatus\":"<<r.legacy<<",\"heldStatus\":"<<r.status<<",\"heldIrq\":"<<r.irq
    <<",\"heldIrqAsserted\":"<<(r.held_pin?"true":"false")
    <<",\"heldCompletionDrained\":"<<(r.drained?"true":"false");
  if(retired)out<<",\"retiredStatus\":"<<r.retired_status<<",\"retiredIrq\":"<<r.retired_irq
    <<",\"retiredIrqAsserted\":"<<(r.retired_pin?"true":"false")
    <<",\"externalDrained\":"<<(r.drained?"true":"false");
  out<<",\"serviceCycles\":"<<r.cycles<<",\"readBusBytes\":"<<r.read_bytes<<",\"writeBusBytes\":"<<r.write_bytes
    <<",\"readOnlyGuards\":true,\"sourceQualificationRequired\":true,\"independentBytesRequired\":true,"
      "\"resetCount\":1,\"clockQualified\":false,\"fpsQualified\":false}\n";
}
template<class Sim>
void active_compute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const ComputeCapture &capture,const std::filesystem::path &output,const std::filesystem::path &session,
    std::chrono::milliseconds timeout=compute_response_timeout()) {
  PtePages pte;pte.observe_tables(memory,capture.trace);
  auto held=hold_compute(sim,agent,bus,memory,capture);
  // Keep the original post-completion RAM image, including writable outputs,
  // fixed while the actual producer emits its Linux IRQ/drain/POP trace.
  atomic_json(session/"record-0.held.json",[&](auto &out){compute_json(out,held.result,capture.command_id);});
  wait_input(session/"record-0.retire",session,timeout);
  const auto retirement=ComputeRetirement::read((session/"record-0.retire").string());
  const auto result=retire_compute(sim,agent,bus,memory,held,retirement);
  memory.write((output/"record-0.memory").string());
  atomic_json(session/"record-0.retired.json",[&](auto &out){compute_json(out,result,capture.command_id,true);});
  // The producer must independently verify all returned bytes before it emits
  // cleanup.begin. The executor then observes real owner and context teardown.
  cleanup_records(sim,agent,bus,memory,session,pte,1,timeout);
}
} // namespace compute_owner

int main(int argc,char **argv) {
  try {
    using namespace compute_owner;
    const auto response_timeout=compute_response_timeout();
    if(argc==3 && std::string(argv[1])=="--probe-profile") {
      require(!std::filesystem::exists(argv[2]),"compute profile output already exists");
      Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
      const auto observed=probe_profile(sim,agent,bus,true);
      atomic_json(argv[2],[&](auto &out){profile_json(out,observed,true);});sim.model->final();return 0;
    }
    require(argc==5 && std::string(argv[1])=="--active-owner",
      "usage: compute_owner_driver --probe-profile NEW.json | --active-owner INPUT.memory NEW_OUTPUT_DIR SESSION_DIR");
    const std::filesystem::path output=argv[3],session=argv[4];
    require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session),"invalid compute session directory");
    require(!std::filesystem::exists(output),"compute output already exists");
    wait_input(session/"record-0.capture",session,response_timeout);
    const auto capture=ComputeCapture::read((session/"record-0.capture").string());
    Memory memory=Memory::read(argv[2]);verify_readonly(memory,memory,capture.writable);
    require(std::filesystem::create_directory(output),"cannot create compute output directory");
    Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
    active_compute(sim,agent,bus,memory,capture,output,session,response_timeout);sim.model->final();return 0;
  }catch(const std::exception &e) {std::cerr<<"compute owner replay failed: "<<e.what()<<'\n';return 1;}
}
