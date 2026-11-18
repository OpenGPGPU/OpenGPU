// Execute original Linux opcode-0 captures on the complete owner DUT. The
// producer supplies the original memory; an independent byte oracle is required.
#include "compute_owner_capture.hpp"
#include "native_owner_runtime.hpp"
#include "compute_owner_reset.hpp"
#include "compute_storage_atomic_delay.hpp"
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
  uint64_t physical_read_faults=0,injected_read_faults=0;
  bool held_pin=false,retired_pin=false,drained=false;
};
enum class ImageFailure { none, source_read, permission };
struct ComputeHeld {
  ComputeResult result;
  Memory initial;
  Traffic start,completed;
  uint64_t start_cycles=0;
  uint64_t prior_read_faults=0;
  ImageFailure failure=ImageFailure::none;
};
template<class Sim,class CaptureType>
ComputeHeld hold_compute_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,const CaptureType &capture,
    uint32_t expected_completion,
    ImageFailure failure=ImageFailure::none,uint64_t prior_read_faults=0,const Traffic *prior_traffic=nullptr,
    StorageAckDelay *ack_delay=nullptr) {
  require(failure==ImageFailure::none || failure==ImageFailure::source_read || failure==ImageFailure::permission,
    "unknown compute failure mode");
  verify_compute_upload(sim,agent,bus,memory,capture,prior_read_faults,prior_traffic);
  ComputeHeld held;held.initial=memory;held.start=agent.traffic;held.start_cycles=bus.cycles;held.failure=failure;
  held.prior_read_faults=prior_read_faults;
  bus.begin_record(capture.max_cycles);
  for(const auto &a:capture.trace)bus.action(a);
  if(ack_delay)ack_delay->withhold(bus,agent);
  // A polled prefix invalidate can leave the sticky IRQ pending after its
  // completion was popped. Its old window is invalid until COMPLETION rises
  // for the final kernel; the IRQ pin alone does not establish that receipt.
  while(!sim.model->io_m_irq || !(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION))bus.step();
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
  if(r.completion!=expected_completion || r.processed_bytes)
    throw std::runtime_error("compute completion identity, success or telemetry differs: expected="+
      std::to_string(expected_completion)+" actual="+std::to_string(r.completion)+
      " processedBytes="+std::to_string(r.processed_bytes)+" legacy="+std::to_string(r.legacy)+
      " status="+std::to_string(r.status)+" irq="+std::to_string(r.irq)+
      " elapsedCycles="+std::to_string(bus.cycles-held.start_cycles)+
      " readFaults="+std::to_string(agent.traffic.read_faults)+
      " writeFaults="+std::to_string(agent.traffic.write_faults));
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
  if(failure!=ImageFailure::none) {
    if(failure==ImageFailure::source_read)
      require(agent.injected_read_faults()>prior_read_faults && agent.traffic.read_faults==agent.injected_read_faults(),
        "failed source-read compute lacks exact injected faults");
    else require(!prior_read_faults && !agent.traffic.read_faults && !agent.injected_read_faults(),
      "source-view failure encountered physical read errors");
    require(!agent.traffic.write_faults && agent.traffic.writes==held.start.writes,
      "failed compute issued physical writes");
    verify_readonly(held.initial,memory,{});
  }else {
    require(agent.traffic.read_faults==prior_read_faults && agent.injected_read_faults()==prior_read_faults &&
      !agent.traffic.write_faults,"compute encountered new physical memory faults");
    verify_readonly(held.initial,memory,capture.writable);
  }
  r.drained=agent.drained();r.cycles=bus.cycles-held.start_cycles;
  r.read_bytes=agent.traffic.read_bus_bytes-held.start.read_bus_bytes;
  r.write_bytes=agent.traffic.write_bus_bytes-held.start.write_bus_bytes;
  r.physical_read_faults=agent.traffic.read_faults;r.injected_read_faults=agent.injected_read_faults();
  if(ack_delay)ack_delay->finish(agent);
  held.initial=memory;return held;
}
template<class Sim>
ComputeHeld hold_compute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,const ComputeCapture &capture,
    ImageFailure failure=ImageFailure::none,uint64_t prior_read_faults=0,const Traffic *prior_traffic=nullptr,
    StorageAckDelay *ack_delay=nullptr) {
  const auto expected=(failure!=ImageFailure::none?(GPU_UCMD_KERNEL_EXECUTION_FAILED<<11):0x8000u)|capture.command_id;
  return hold_compute_record(sim,agent,bus,memory,capture,expected,failure,prior_read_faults,prior_traffic,ack_delay);
}
template<class Sim,class RetirementType>
ComputeResult retire_compute_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    ComputeHeld &held,const RetirementType &retirement) {
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
  require(agent.traffic.read_faults==held.completed.read_faults && !agent.traffic.write_faults &&
      (held.failure!=ImageFailure::none || agent.traffic.read_faults==held.prior_read_faults),
      "compute retirement encountered unexpected physical memory faults");
  verify_readonly(held.initial,memory,{}); // No RAM changes are permitted after held completion.
  r.drained=agent.drained();r.cycles=bus.cycles-held.start_cycles;
  r.read_bytes=agent.traffic.read_bus_bytes-held.start.read_bus_bytes;
  r.write_bytes=agent.traffic.write_bus_bytes-held.start.write_bus_bytes;return r;
}
template<class Sim>
ComputeResult retire_compute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    ComputeHeld &held,const ComputeRetirement &retirement) {
  return retire_compute_record(sim,agent,bus,memory,held,retirement);
}
inline void compute_json(std::ostream &out,const ComputeResult &r,uint32_t command_id,bool retired=false,bool failed=false) {
  out<<"{\"schema\":\"opengpu-compute-owner-"<<(failed?"failed-":"")<<(retired?"retired":"held")<<"-v1\",\"phase\":\""
    <<(retired?"retired":"held")<<"\",\"record\":0,\"commandId\":"<<command_id
    <<",\"completion\":"<<r.completion<<",\"processedBytes\":"<<r.processed_bytes
    <<",\"heldLegacyStatus\":"<<r.legacy<<",\"heldStatus\":"<<r.status<<",\"heldIrq\":"<<r.irq
    <<",\"heldIrqAsserted\":"<<(r.held_pin?"true":"false")
    <<",\"heldCompletionDrained\":"<<(r.drained?"true":"false");
  if(retired)out<<",\"retiredStatus\":"<<r.retired_status<<",\"retiredIrq\":"<<r.retired_irq
    <<",\"retiredIrqAsserted\":"<<(r.retired_pin?"true":"false")
    <<",\"externalDrained\":"<<(r.drained?"true":"false");
  if(failed)out<<",\"physicalReadFaults\":"<<r.physical_read_faults
    <<",\"injectedReadFaults\":"<<r.injected_read_faults<<",\"physicalWriteFaults\":0";
  out<<",\"serviceCycles\":"<<r.cycles<<",\"readBusBytes\":"<<r.read_bytes<<",\"writeBusBytes\":"<<r.write_bytes
    <<",\"readOnlyGuards\":true,\"sourceQualificationRequired\":true,\"independentBytesRequired\":true,"
      "\"resetCount\":1,\"clockQualified\":false,\"fpsQualified\":false}\n";
}
template<class Sim>
void active_compute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const ComputeCapture &capture,const std::filesystem::path &output,const std::filesystem::path &session,
    std::chrono::milliseconds timeout=compute_response_timeout(),ImageFailure failure=ImageFailure::none,
    uint64_t prior_read_faults=0,const Traffic *prior_traffic=nullptr,StorageAckDelay *ack_delay=nullptr) {
  PtePages pte;capture.observe_contexts(memory,pte);
  require(!ack_delay || failure==ImageFailure::none,"failed compute cannot use a successful atomic ACK plan");
  auto held=hold_compute(sim,agent,bus,memory,capture,failure,prior_read_faults,prior_traffic,ack_delay);
  if(ack_delay)atomic_json(session/"record-0.ack-delay.json",[&](auto &out){ack_delay->json(out,capture.command_id);});
  // Keep the original post-completion RAM image, including writable outputs,
  // fixed while the actual producer emits its Linux IRQ/drain/POP trace.
  const bool failed=failure!=ImageFailure::none;
  atomic_json(session/"record-0.held.json",[&](auto &out){compute_json(out,held.result,capture.command_id,false,failed);});
  wait_input(session/"record-0.retire",session,timeout);
  const auto retirement=ComputeRetirement::read((session/"record-0.retire").string());
  const auto result=retire_compute(sim,agent,bus,memory,held,retirement);
  memory.write((output/"record-0.memory").string());
  atomic_json(session/"record-0.retired.json",[&](auto &out){compute_json(out,result,capture.command_id,true,failed);});
  // The producer must independently verify all returned bytes before it emits
  // cleanup.begin. The executor then observes real owner and context teardown.
  cleanup_records(sim,agent,bus,memory,session,pte,1,timeout,held.completed.read_faults);
}

inline void arm_source_fault(Agent &agent,const std::filesystem::path &session,
    std::chrono::milliseconds timeout) {
  wait_input(session/"record-0.fault-plan",session,timeout);
  std::ifstream plan(session/"record-0.fault-plan",std::ios::binary);magic(plan,"OGCFS001");
  const auto first=read_le(plan,8),end=read_le(plan,8);
  require(plan.peek()==EOF,"trailing source fault plan");agent.arm_read_faults(first,end);
}
inline void authorize_recovery_round(const std::filesystem::path &session,unsigned index,
    std::chrono::milliseconds timeout) {
  wait_input(session/"BEGIN_EXECUTION",session,timeout);
  std::ifstream in(session/"BEGIN_EXECUTION",std::ios::binary);magic(in,"OGCMEX01");
  require(read_le(in,4)==index && in.peek()==EOF,"wrong recovery round authorization");
}
inline void active_image_recovery(int argc,char **argv,const std::filesystem::path &output,
    const std::filesystem::path &session,std::chrono::milliseconds timeout,bool permission=false,unsigned reset=0) {
  require(compute_owner_factory()==OwnerFactory::image_float_stage,
    "image recovery requires the distinct complete float factory");
  require(reset<=2 && !(reset && permission),"ambiguous image recovery mode");
  require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session) &&
    !std::filesystem::exists(output),"invalid recovery session or existing output");
  const auto first_session=session/"round-0";
  authorize_recovery_round(first_session,0,timeout);
  wait_input(first_session/"union.memory",first_session,timeout);
  Memory memory=Memory::read((first_session/"union.memory").string());
  require(std::filesystem::create_directory(output),"cannot create recovery output");
  // One execution DUT, one reset, one backing RAM and one cumulative AXI agent.
  Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
  uint32_t command_id=0;uint64_t prior_faults=0;Traffic prior_traffic{};
  for(unsigned index=0;index<2;index++) {
    const auto local=session/("round-"+std::to_string(index));
    if(index) {
      authorize_recovery_round(local,index,timeout);
      wait_input(local/"union.memory",local,timeout);
      require(agent.drained(),"new owner arrived before old AXI traffic drained");
      memory.append_disjoint(Memory::read((local/"union.memory").string()));
    }
    wait_input(local/"record-0.capture",local,timeout);
    const auto capture=ComputeCapture::read((local/"record-0.capture").string());
    if(index)require(capture.command_id==command_id,"healthy recovery changed the original command ID");
    else command_id=capture.command_id;
    const auto destination=output/("round-"+std::to_string(index));
    require(std::filesystem::create_directory(destination),"recovery output round already exists");
    const auto start=bus.cycles;
    if(!index && !permission && !reset)arm_source_fault(agent,local,timeout);
    const auto failure=index?ImageFailure::none:permission?ImageFailure::permission:ImageFailure::source_read;
    if(!index && reset)
      active_cancel_compute(sim,agent,bus,memory,capture,destination,local,
        reset==1?ResponseChannel::read:ResponseChannel::write,timeout);
    else active_compute(sim,agent,bus,memory,capture,destination,local,timeout,failure,prior_faults,index?&prior_traffic:nullptr);
    if(!index && !permission && !reset)agent.disarm_read_faults();
    const auto completed_traffic=agent.traffic;
    const auto status=bus.read(GPU_REG_UCMD_STATUS),irq=bus.read(GPU_REG_IRQ);
    require(status==GPU_UCMD_STATUS_READY && irq==GPU_IRQ_ENABLE && !sim.model->io_m_irq && agent.drained() &&
      !bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP),
      "recovery owner did not release its actual contexts and IRQ");
    traffic_unchanged(completed_traffic,agent.traffic);
    const auto faults=agent.injected_read_faults();
    require(((permission || reset)?faults==0:faults>0) && agent.traffic.read_faults==faults && !agent.traffic.write_faults &&
      (!index || faults==prior_faults),"recovery lost physical fault history or incurred a new error");
    memory.write((destination/"record-0.cleaned.memory").string());
    atomic_json(local/"executor.done",[&](auto &out) {
      out<<"{\"schema\":\"opengpu-compute-image-"<<(reset?"reset-recovery":"recovery")<<"-round-v1\",\"round\":"<<index
        <<",\"commandId\":"<<command_id<<",\"resetCount\":1,\"ramReplaced\":false,\"externalDrained\":true"
        <<",\"sourceQualificationRequired\":true,\"independentBytesRequired\":true"
        <<",\"physicalReadFaults\":"<<agent.traffic.read_faults<<",\"injectedReadFaults\":"<<faults
        <<",\"physicalWriteFaults\":0,\"startCycle\":"<<start<<",\"endCycle\":"<<bus.cycles;
      if(reset)out<<",\"channel\":\""<<(reset==1?"read":"write")<<"\",\"unifiedResetRequests\":1"
        <<",\"heldReadCycles\":"<<agent.held_read_cycles()<<",\"heldWriteCycles\":"<<agent.held_write_cycles();
      out<<"}\n";
    });
    prior_faults=faults;prior_traffic=agent.traffic;
  }
  sim.model->final();
}
} // namespace compute_owner

#ifndef OPENGPU_COMPUTE_OWNER_LIBRARY
int main(int argc,char **argv) {
  try {
    using namespace compute_owner;
    const auto response_timeout=compute_response_timeout();
    if(argc==3 && std::string(argv[1])=="--probe-profile") {
      require(!std::filesystem::exists(argv[2]),"compute profile output already exists");
      Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
      const auto observed=probe_profile(sim,agent,bus,true,compute_owner_factory());
      atomic_json(argv[2],[&](auto &out){profile_json(out,observed,true);});sim.model->final();return 0;
    }
    if(argc==4 && (std::string(argv[1])=="--active-image-source-recovery" ||
        std::string(argv[1])=="--active-image-permission-recovery")) {
      active_image_recovery(argc,argv,argv[2],argv[3],response_timeout,
        std::string(argv[1])=="--active-image-permission-recovery");return 0;
    }
    if(argc==5 && std::string(argv[1])=="--active-image-reset-recovery") {
      const std::string channel=argv[4];require(channel=="read" || channel=="write","unknown image reset response channel");
      active_image_recovery(argc,argv,argv[2],argv[3],response_timeout,false,channel=="read"?1:2);return 0;
    }
    const bool source_fault=argc==5 && std::string(argv[1])=="--active-image-source-fault";
    const bool storage_view_fault=argc==5 && std::string(argv[1])=="--active-storage-view-fault";
    const bool storage_ack_delay=argc==5 && std::string(argv[1])=="--active-storage-delayed-ack";
    require(argc==5 && (std::string(argv[1])=="--active-owner" || source_fault || storage_view_fault || storage_ack_delay),
      "usage: compute_owner_driver --probe-profile NEW.json | --active-owner INPUT.memory NEW_OUTPUT_DIR SESSION_DIR | --active-storage-view-fault INPUT.memory NEW_OUTPUT_DIR SESSION_DIR | --active-storage-delayed-ack INPUT.memory NEW_OUTPUT_DIR SESSION_DIR | --active-image-source-fault INPUT.memory NEW_OUTPUT_DIR SESSION_DIR | --active-image-source-recovery NEW_OUTPUT_DIR SESSION_DIR | --active-image-permission-recovery NEW_OUTPUT_DIR SESSION_DIR");
    require(!(source_fault || storage_view_fault || storage_ack_delay) || compute_owner_factory()==OwnerFactory::image_float_stage,
      "selected fault/ACK mode requires the distinct complete float factory");
    const std::filesystem::path output=argv[3],session=argv[4];
    require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session),"invalid compute session directory");
    require(!std::filesystem::exists(output),"compute output already exists");
    wait_input(session/"record-0.capture",session,response_timeout);
    const auto capture=ComputeCapture::read((session/"record-0.capture").string());
    Memory memory=Memory::read(argv[2]);verify_readonly(memory,memory,capture.writable);
    require(std::filesystem::create_directory(output),"cannot create compute output directory");
    Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
    if(source_fault)arm_source_fault(agent,session,response_timeout);
    StorageAckDelay delay;
    if(storage_ack_delay) {
      wait_input(session/"record-0.ack-delay-plan",session,response_timeout);
      std::ifstream input(session/"record-0.ack-delay-plan",std::ios::binary);
      delay=StorageAckDelay::read(input);delay.arm(agent);
    }
    active_compute(sim,agent,bus,memory,capture,output,session,response_timeout,
      source_fault?ImageFailure::source_read:storage_view_fault?ImageFailure::permission:ImageFailure::none,
      0,nullptr,storage_ack_delay?&delay:nullptr);
    if(source_fault)agent.disarm_read_faults();
    sim.model->final();return 0;
  }catch(const std::exception &e) {std::cerr<<"compute owner replay failed: "<<e.what()<<'\n';return 1;}
}
#endif
