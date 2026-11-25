#pragma once
// Replay only actual production-owner captures. No shader, descriptor, VA or
// output byte is generated here. An independent image oracle remains required.
#include "owner_factory.hpp"
#include "native_execution_progress.hpp"
#include "native_host_simulation.hpp"
#include <array>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <limits>
#include <optional>
#include <sstream>
#include <thread>
#include <utility>

#ifdef OPENGPU_FLASHSIM_NATIVE
static_assert(GpuHostSystemAxiDut::assertion_count > 0,"assertion-free owner replay DUT");
#ifdef OPENGPU_EXPECTED_ASSERTIONS
static_assert(GpuHostSystemAxiDut::assertion_count == OPENGPU_EXPECTED_ASSERTIONS,"wrong owner replay assertion model");
#endif
#endif

namespace native_owner {
template<class Sim> class ReplayBus {
  static constexpr unsigned control_response_limit=1024;
  Sim &sim;
  Agent &agent;
  uint64_t limit;
  void check_response(uint32_t response,const char *operation,uint32_t address,uint32_t value,
      uint32_t expected=0) const {
    if(response==expected)return;
    std::ostringstream message;
    message<<"captured MMIO "<<operation<<" fault: address=0x"<<std::hex<<address
      <<" value=0x"<<value<<" response=0x"<<response;
    if(expected)message<<" expected=0x"<<expected;
    message<<std::dec<<" cycles="<<cycles;
    throw std::runtime_error(message.str());
  }
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
  void wait_before_status_poll(unsigned interval) {
    // Keep polling at the original cadence near the record deadline.
    const unsigned steps=limit-cycles>uint64_t(interval)+control_response_limit?interval:1;
    for(unsigned n=0;n<steps;++n)step();
  }
  uint32_t read(uint32_t address) {
    auto &d=*sim.model;uint32_t value=0;bool ar=false,r=false;
    d.io_s_axi_araddr=address;d.io_s_axi_arlen=0;d.io_s_axi_arsize=2;d.io_s_axi_arburst=0;
    d.io_s_axi_arvalid=d.io_s_axi_rready=1;
    for(unsigned n=0;n<control_response_limit && !r;++n) {
      step([&](const auto &m) {
        if(m.io_s_axi_arvalid && m.io_s_axi_arready)ar=true;
        if(m.io_s_axi_rvalid && m.io_s_axi_rready) {
          require(ar,"captured MMIO response preceded its read address");
          check_response(m.io_s_axi_rresp,"read",address,m.io_s_axi_rdata);
          value=m.io_s_axi_rdata;r=true;
        }
      });
      if(ar)d.io_s_axi_arvalid=0;
    }
    d.io_s_axi_arvalid=d.io_s_axi_rready=0;
    require(r,"captured MMIO read handshake timed out");return value;
  }
  void write(uint32_t address,uint32_t value,uint32_t expected=0) {
    auto &d=*sim.model;bool aw=false,w=false,b=false;
    d.io_s_axi_awaddr=address;d.io_s_axi_awlen=0;d.io_s_axi_awsize=2;d.io_s_axi_awburst=0;
    d.io_s_axi_wdata=value;d.io_s_axi_wstrb=15;d.io_s_axi_wlast=1;
    d.io_s_axi_awvalid=d.io_s_axi_wvalid=d.io_s_axi_bready=1;
    for(unsigned n=0;n<control_response_limit && !b;++n) {
      step([&](const auto &m) {
        if(m.io_s_axi_awvalid && m.io_s_axi_awready)aw=true;
        if(m.io_s_axi_wvalid && m.io_s_axi_wready)w=true;
        if(m.io_s_axi_bvalid && m.io_s_axi_bready) {
          require(aw && w,"captured MMIO response preceded its write request");
          check_response(m.io_s_axi_bresp,"write",address,value,expected);b=true;
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
  uint64_t physical_read_faults=0, physical_read_fault_line=0, physical_write_faults=0;
  uint32_t completion=0, held_legacy_status=0, held_status=0, held_irq=0, retired_status=0, retired_irq=0;
  std::array<uint32_t,12> gnx{};
  std::array<uint32_t,OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS> gnz2{};
  bool joint_completion=false;
  bool held_irq_asserted=false, retired_irq_asserted=false, external_drained=false;
};
struct ProfileObservation {
  OwnerFactory factory=OwnerFactory::v1;
  std::vector<Command> words,registers;
};
template<class Sim>
ProfileObservation probe_profile(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,bool compute=false,
    OwnerFactory factory=OwnerFactory::v1,const Traffic *prior_traffic=nullptr) {
  require(compute || factory==OwnerFactory::v1,"resource factory requires compute observations");
  const auto initial_traffic=agent.traffic;
  if(prior_traffic) {
    require(agent.drained(),"reused profile probe began before external drain");
    traffic_unchanged(*prior_traffic,initial_traffic);
  }
  ProfileObservation observed;observed.factory=factory;
  const auto id=bus.read(GPU_REG_ID),caps=bus.read(GPU_REG_CAPABILITIES);
  require((id&0xffffu)>=GPU_ABI_COMPUTE_RESOURCE_VERSION && (caps&required_owner_caps)==required_owner_caps,
    "profile probe requires a protected unified-command ABI");
  observed.registers={{GPU_REG_ID,id},{GPU_REG_CAPABILITIES,caps}};
  // The first three reads establish GCR presence/version/protected sources.
  // Never probe dependent GUC/GNC/GSE windows after a rejected prerequisite.
  for(const auto &required:owner_factory_words(factory)) {
    const auto value=bus.read(required.address);
    require(value==required.value,"actual DUT lacks the complete native source profile");
    observed.words.push_back({required.address,value});
  }
  const auto pointer=bus.read(GPU_REG_BIN_CAPABILITY_PTR),quota=bus.read(0x320);
  require(pointer==GPU_REG_BIN_CAPABILITIES && quota,"profile probe lacks native discovery/live quota");
  observed.registers.push_back({GPU_REG_BIN_CAPABILITY_PTR,pointer});observed.registers.push_back({0x320,quota});
  const auto shader=bus.read(GPU_REG_SHADER_CONFIG);
  require_owner_shader_profile(caps,shader);
  observed.registers.push_back({GPU_REG_SHADER_CONFIG,shader});
  if(compute) {
    // GCR bits 4..8 advertise these five command transports. These are
    // expected wire contracts; append only the actual MMIO observations.
    require((observed.words.at(2).value&0x1f0u)==0x1f0u,
      "compute transport probe requires advertised command support");
    const std::array<Command,5> transports{{{0x494,0x47544d01},{0x43c,0x47534d01},
      {0x3fc,0x47494d01},{0x4bc,0x47414d01},{0x460,0x47554d01}}};
    for(const auto &transport:transports) {
      const auto value=bus.read(transport.address);
      require(value==transport.value,"actual DUT lacks an advertised compute command transport");
      observed.registers.push_back({transport.address,value});
    }
  }
  require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION) && !sim.model->io_m_irq,
    "profile-only probe encountered an active completion");
  traffic_unchanged(initial_traffic,agent.traffic);
  require(agent.drained() && (prior_traffic || (!agent.traffic.reads && !agent.traffic.writes)),
    "profile-only probe encountered external memory work");
  return observed;
}
inline void profile_json(std::ostream &out,const ProfileObservation &profile,bool compute=false) {
  const bool stage=profile.factory==OwnerFactory::compute_stage;
  const bool full=profile.factory==OwnerFactory::geometry_compute || owner_factory_has_sample(profile.factory);
  require(compute || (!stage && !full),"resource observations cannot use a native profile schema");
  const auto expected=owner_factory_words(profile.factory);
  require(profile.words.size()==expected.size() && profile.registers.size()==(compute?10u:5u),
    "profile observations do not match their selected schema");
  for(size_t n=0;n<expected.size();++n)
    require(profile.words[n].address==expected[n].address && profile.words[n].value==expected[n].value,
      "profile observations do not match their exact factory");
  out<<"{\"schema\":\""<<(full?"opengpu-compute-owner-profile-v3":stage?"opengpu-compute-owner-profile-v2":compute?"opengpu-compute-owner-profile-v1":"opengpu-native-owner-profile-v1")<<'"';
  if(stage || full)out<<",\"ownerProfile\":\""<<owner_factory_id(profile.factory)
    <<"\",\"ownerResourceVersion\":2,\"ownerResourceStage\":\""<<owner_factory_stage(profile.factory)
    <<"\",\"fullResourceQualification\":false";
  out<<",\"words\":[";
  const auto &words=profile.words;
  for(size_t n=0;n<words.size();++n)
    out<<(n?",":"")<<"{\"address\":"<<words[n].address<<",\"value\":"<<words[n].value<<"}";
  out<<"],\"registers\":[";
  for(size_t n=0;n<profile.registers.size();++n)
    out<<(n?",":"")<<"{\"address\":"<<profile.registers[n].address<<",\"value\":"<<profile.registers[n].value<<"}";
  out<<"],\"resetCount\":1,\"sourceQualificationRequired\":true,\"gpuExecution\":false,"
    "\"clockQualified\":false,\"fpsQualified\":false}\n";
}
template<class Sim>
std::vector<Command> probe_joint_profile(Sim &sim,Agent &agent,ReplayBus<Sim> &bus) {
  require(agent.drained() && !sim.model->io_m_irq,"joint profile probe requires idle memory and IRQ");
  const auto initial=agent.traffic;
  const auto id=bus.read(GPU_REG_ID),caps=bus.read(GPU_REG_CAPABILITIES);
  require((id&0xffff0000u)==0x47550000u && (id&0xffffu)>=GPU_ABI_COMPUTE_RESOURCE_VERSION &&
    (caps&required_owner_caps)==required_owner_caps && bus.read(0x360)==0x47435201 &&
    bus.read(0x364)==16 && bus.read(0x368)==JointCapture::resource_flags,
    "actual DUT lacks the protected joint discovery guarantee");
  std::vector<Command> words;
  for(const auto address:JointCapture::discovery_addresses())words.push_back({address,bus.read(address)});
  JointCapture::validate_discovery(words);
  require(agent.drained() && !sim.model->io_m_irq &&
    !(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION),"joint profile probe encountered active work");
  traffic_unchanged(initial,agent.traffic);
  return words;
}
inline void joint_profile_json(std::ostream &out,const std::vector<Command> &words) {
  JointCapture::validate_discovery(words);
  const auto boundary=words.size()-JointCapture::auxiliary_words;
  out<<"{\"schema\":\"opengpu-protected-joint-owner-profile-v1\",\"words\":[";
  for(size_t n=0;n<words.size();++n) {
    if(n==boundary)out<<"],\"registers\":[";
    else if(n)out<<',';
    out<<"{\"address\":"<<words[n].address<<",\"value\":"<<words[n].value<<'}';
  }
  out<<"],\"resetCount\":1,\"gpuExecution\":false}\n";
}
struct HeldRecord {
  RecordResult result;
  Memory initial;
  Traffic start_traffic,completed;
  uint64_t start_cycles;
};
template<class Sim>
void replay_enqueue_prefix(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,const Capture &capture) {
  require(agent.drained() && !sim.model->io_m_irq,"owner record starts before prior retirement/drain");
  bus.begin_record(capture.max_cycles);
  // GCR's explicit protected-source presence guarantee must be checked before
  // probing GUC/GSE/GNC. A standalone GNP host need not map those windows.
  for(uint32_t address=0x360;address<0x3a0;address+=4) {
    auto word=std::find_if(capture.discovery.begin(),capture.discovery.end(),
      [address](const auto &item){return item.address==address;});
    require(word!=capture.discovery.end() && bus.read(address)==word->value,
      "actual DUT lacks the captured source profile");
  }
  for(const auto &r:capture.discovery) if(r.address<0x360 || r.address>=0x3a0)
    require(bus.read(r.address)==r.value,"actual DUT differs from captured discovery");
  require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION),
    "owner record starts with an unretired completion");
  for(auto it=capture.trace.begin();it!=capture.trace.end()-1;++it)bus.action(*it);
}
template<class Sim,class CaptureType>
HeldRecord hold_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,
                       Memory &memory,const CaptureType &capture,bool prefix_only=false,
                       native_frame::ExecutionProgress *progress=nullptr,uint64_t read_fault_line=0) {
  capture.validate(prefix_only);
  HeldRecord held_record;held_record.initial=memory;
  verify_readonly(held_record.initial,memory,capture.writable);
  held_record.start_cycles=bus.cycles;held_record.start_traffic=agent.traffic;
  replay_enqueue_prefix(sim,agent,bus,capture);
  const auto initial_injected_reads=agent.injected_read_faults();
  const auto initial_injected_writes=agent.injected_write_faults();
  if constexpr(CaptureType::joint_completion) {
    require(bool(read_fault_line)==capture.uniform_read_fault,"joint uniform fault is missing its exact read line");
    if(read_fault_line)agent.arm_read_faults(read_fault_line,read_fault_line+64);
    if(capture.write_fault) {
      const auto address=capture.write_fault->physical;
      agent.arm_first_write_fault(address,address+64);
    }
  }
  bus.action(capture.trace.back());
  // IRQ is sticky and may still describe a preceding invalidation whose
  // result was drained by polling. Observe the actual unified slot before
  // treating this record as complete; polling continues to service AXI.
  auto last_progress=bus.cycles;
  while(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION)) {
    bus.wait_before_status_poll(CaptureType::joint_completion?32:1);
    if(progress && bus.cycles-last_progress>=256) {
      progress->update(native_frame::progress_counters(bus.cycles-held_record.start_cycles,bus.cycles,agent.traffic));
      last_progress=bus.cycles;
    }
  }
  require(sim.model->io_m_irq,"native unified completion has no asserted IRQ");
  require(agent.drained(),"native completion preceded external memory drain");
  const auto completed=agent.traffic;
  const auto completion=bus.read(GPU_REG_UCMD_COMPLETION);
  auto &result=held_record.result;result.completion=completion;
  result.joint_completion=CaptureType::joint_completion;
  if constexpr(CaptureType::joint_completion) {
    const unsigned expected_reads=bool(read_fault_line);
    const unsigned expected_writes=bool(capture.write_fault);
    require(completed.read_faults==held_record.start_traffic.read_faults+expected_reads &&
      completed.write_faults==held_record.start_traffic.write_faults+expected_writes &&
      agent.injected_read_faults()==initial_injected_reads+expected_reads &&
      agent.injected_write_faults()==initial_injected_writes+expected_writes,
      "protected joint completion has unexpected physical fault traffic");
    if(read_fault_line)agent.disarm_read_faults();
    if(capture.write_fault) {
      require(agent.first_write_attempts()==1,"protected joint store retried a failed physical line");
      agent.disarm_first_write_fault();
    }
    result.physical_read_faults=expected_reads;result.physical_read_fault_line=read_fault_line;
    result.physical_write_faults=expected_writes;
  }
  result.held_legacy_status=bus.read(GPU_REG_STATUS);
  result.held_status=bus.read(GPU_REG_UCMD_STATUS);
  result.held_irq=bus.read(GPU_REG_IRQ);result.held_irq_asserted=bool(sim.model->io_m_irq);
  const auto append_lo=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO);
  const auto append_hi=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI);
  result.append_read_bytes=(uint64_t(append_hi)<<32)|append_lo;
  if(!(result.held_status&GPU_UCMD_STATUS_COMPLETION)) {
    std::ostringstream message;
    message<<"native completion status missing after IRQ: cycles="
      <<bus.cycles-held_record.start_cycles<<std::hex
      <<" completion=0x"<<completion<<" legacy_status=0x"<<result.held_legacy_status
      <<" ucmd_status=0x"<<result.held_status<<" irq=0x"<<result.held_irq
      <<" irq_asserted="<<result.held_irq_asserted;
    throw std::runtime_error(message.str());
  }
  std::vector<uint32_t> held;
  for(const auto &c:capture.checks) {
    const auto value=bus.read(c.address);
    if((value&c.mask)!=c.expected) {
      std::ostringstream message;
      message<<(CaptureType::joint_completion?"joint completion/GNZ2 check differs: address=0x":
        "native completion/GNX check differs: address=0x")<<std::hex<<c.address
        <<" actual=0x"<<value<<" mask=0x"<<c.mask<<" expected=0x"<<c.expected;
      if constexpr(CaptureType::joint_completion) {
        message<<" GNZ2=";
        for(unsigned word=0;word<result.gnz2.size();++word)
          message<<(word?",":"")<<bus.read(joint_detail_address(word));
        message<<std::dec<<" metadataReadBytes="<<result.append_read_bytes
          <<" readBusBytes="<<completed.read_bus_bytes-held_record.start_traffic.read_bus_bytes
          <<" writeBusBytes="<<completed.write_bus_bytes-held_record.start_traffic.write_bus_bytes;
      }
      throw std::runtime_error(message.str());
    }
    held.push_back(value);
    if constexpr(CaptureType::joint_completion) {
      for(unsigned word=0;word<result.gnz2.size();++word)
        if(c.address==joint_detail_address(word))result.gnz2[word]=value;
    } else if(c.address>=0x180 && c.address<0x1b0)result.gnx[(c.address-0x180)/4]=value;
  }
  for(uint32_t n=0;n<capture.hold_cycles;++n) {
    require(sim.model->io_m_irq,"native completion IRQ disappeared while held");bus.step();
    require(agent.drained(),"held native completion leaked external traffic");
  }
  require(bus.read(GPU_REG_UCMD_COMPLETION)==completion,"held native completion changed");
  for(size_t n=0;n<capture.checks.size();++n)
    require(bus.read(capture.checks[n].address)==held[n],
      CaptureType::joint_completion?"held joint completion/GNZ2 changed":"held native completion/GNX changed");
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
template<class Sim,class CaptureType>
RecordResult retire_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,
    Memory &memory,const CaptureType &capture,HeldRecord &held_record,const Retirement *retirement=nullptr) {
  require(sim.model->io_m_irq && agent.drained(),"driver retirement lost held completion ownership");
  traffic_unchanged(held_record.completed,agent.traffic);
  auto &result=held_record.result;
  if constexpr(CaptureType::joint_completion)
    require(retirement,"protected joint owner requires an observed driver retirement");
  if(retirement){retirement->validate(CaptureType::joint_completion);for(const auto &a:retirement->actions)bus.action(a);}
  else {capture.validate();for(const auto &w:capture.finish)bus.write(w.address,w.value);}
  result.retired_status=bus.read(GPU_REG_UCMD_STATUS);
  require(!(result.retired_status&GPU_UCMD_STATUS_COMPLETION),"native completion POP did not retire the slot");
  for(unsigned n=0;n<8;++n)bus.step();
  result.retired_irq=bus.read(GPU_REG_IRQ);result.retired_irq_asserted=bool(sim.model->io_m_irq);
  traffic_unchanged(held_record.completed,agent.traffic);
  require(agent.drained(),"owner replay left external traffic");
  if constexpr(!CaptureType::joint_completion)
    require(!agent.traffic.read_faults && !agent.traffic.write_faults,"owner replay faulted");
  require(!sim.model->io_m_irq,"native completion retirement left an asserted IRQ");
  verify_readonly(held_record.initial,memory,capture.writable);
  result.cycles=bus.cycles-held_record.start_cycles;
  result.read_bytes=agent.traffic.read_bus_bytes-held_record.start_traffic.read_bus_bytes;
  result.write_bytes=agent.traffic.write_bus_bytes-held_record.start_traffic.write_bus_bytes;
  result.external_drained=agent.drained();return result;
}
template<class Sim,class CaptureType>
RecordResult replay_record(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,const CaptureType &capture) {
  auto held=hold_record(sim,agent,bus,memory,capture);
  return retire_record(sim,agent,bus,memory,capture,held);
}
inline void completion_detail_json(std::ostream &out,const RecordResult &result) {
  out<<(result.joint_completion?",\"gnz2\":[":",\"gnx\":[");
  if(result.joint_completion) {
    for(size_t n=0;n<result.gnz2.size();++n)out<<(n?",":"")<<result.gnz2[n];
  }else {
    for(size_t n=0;n<result.gnx.size();++n)out<<(n?",":"")<<result.gnx[n];
  }
  out<<']';
  if(result.joint_completion)out<<",\"physicalWriteFaults\":"<<result.physical_write_faults
    <<",\"physicalReadFaults\":"<<result.physical_read_faults
    <<",\"physicalReadFaultLine\":"<<result.physical_read_fault_line;
}
inline void held_json(std::ostream &out,const RecordResult &r,size_t index,uint32_t command_id) {
  out<<"{\"phase\":\"held\",\"record\":"<<index<<",\"commandId\":"<<command_id
    <<",\"completion\":"<<r.completion;
  completion_detail_json(out,r);
  out<<(r.joint_completion?",\"metadataReadBytes\":":",\"appendReadBytes\":")<<r.append_read_bytes
    <<",\"heldLegacyStatus\":"<<r.held_legacy_status<<",\"heldStatus\":"<<r.held_status<<",\"heldIrq\":"<<r.held_irq
    <<",\"heldIrqAsserted\":"<<(r.held_irq_asserted?"true":"false")
    <<",\"heldCompletionDrained\":"<<(r.external_drained?"true":"false")
    <<",\"sourceQualificationRequired\":true,\"readOnlyGuards\":true}\n";
}
inline void report_record(const RecordResult &r,size_t index,uint32_t command_id,uint64_t cycles,std::ostream &out=std::cout) {
  out<<"{\"phase\":\"retired\",\"ownerReplay\":true,\"record\":"<<index<<",\"commandId\":"<<command_id
    <<",\"serviceCycles\":"<<r.cycles<<",\"cumulativeServiceCycles\":"<<cycles
    <<",\"readBusBytes\":"<<r.read_bytes<<",\"writeBusBytes\":"<<r.write_bytes
    <<",\"completion\":"<<r.completion;
  completion_detail_json(out,r);
  out<<(r.joint_completion?",\"metadataReadBytes\":":",\"appendReadBytes\":")<<r.append_read_bytes
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
inline uint64_t joint_read_line(std::istream &plan,const JointCapture &capture) {
  const auto physical=read_le(plan,8);
  require(physical && physical<Memory::limit && !(physical&63) && plan.peek()==EOF &&
    std::none_of(capture.writable.begin(),capture.writable.end(),[physical](const auto &r) {
      return physical<r.end && r.first<physical+64;
    }),"joint read plan requires one aligned read-only physical line");
  return physical;
}
template<class Sim>
void cancel_joint_read(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const JointCapture &capture,const std::filesystem::path &output,const std::filesystem::path &session,
    uint64_t index,std::chrono::milliseconds timeout) {
  const auto stem="record-"+std::to_string(index);
  const auto reset_stem=stem+".reset.";
  const auto plan_path=session/(reset_stem+"plan");
  wait_input(plan_path,session,timeout);
  std::ifstream plan(plan_path,std::ios::binary);
  std::string format(8,'\0');
  plan.read(format.data(),format.size());
  require(format=="OGJCP001" || format=="OGJCP002","unknown joint read hold plan");
  const auto physical=joint_read_line(plan,capture);

  const auto initial=memory;
  const auto traffic=agent.traffic;
  const auto start=bus.cycles;
  const auto previous_holds=agent.held_read_cycles();
  replay_enqueue_prefix(sim,agent,bus,capture);
  const auto satp=bus.read(GPU_REG_UCMD_VECTOR_SATP);
  auto verify_vm=[&] {
    require((satp&GPU_SATP_ENABLE) && bus.read(GPU_REG_UCMD_VECTOR_SATP)==satp &&
      bus.read(GPU_REG_UCMD_INSTRUCTION_SATP)==satp,"joint reset changed the original Sv32 context");
  };
  verify_vm();
  agent.arm_response_hold(ResponseChannel::read,physical,physical+64);
  bus.action(capture.trace.back());
  while(agent.held_read_cycles()==previous_holds) {
    bus.step();
    if((bus.cycles-start)%64==0)
      require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION),
        "joint job completed before the selected read response was held");
  }
  auto held_cycles=[&] {return agent.held_read_cycles()-previous_holds;};
  struct ResetState {
    uint32_t legacy,status,irq;
    bool pin,drained;
  };
  auto observe=[&] {
    const auto legacy=bus.read(GPU_REG_STATUS);
    const auto status=bus.read(GPU_REG_UCMD_STATUS);
    const auto irq=bus.read(GPU_REG_IRQ);
    return ResetState{legacy,status,irq,bool(sim.model->io_m_irq),agent.drained()};
  };
  auto publish=[&](const char *phase,const ResetState &s,uint64_t reset_holds) {
    atomic_json(session/(reset_stem+phase+".json"),[&](auto &out) {
      out<<"{\"phase\":\""<<phase<<"\",\"record\":"<<index<<",\"commandId\":"<<capture.command_id
        <<",\"legacyStatus\":"<<s.legacy<<",\"status\":"<<s.status<<",\"irq\":"<<s.irq
        <<",\"irqAsserted\":"<<(s.pin?"true":"false")<<",\"externalDrained\":"<<(s.drained?"true":"false")
        <<(format=="OGJCP001"?",\"physicalIndexLine\":":",\"physicalReadLine\":")<<physical
        <<",\"satp\":"<<satp<<",\"heldReadCycles\":"<<held_cycles()
        <<",\"resetHeldReadCycles\":"<<reset_holds<<",\"serviceCycles\":"<<bus.cycles-start<<"}\n";
    });
  };
  const auto held=observe();
  require(!held.legacy && !held.drained && (held.status&GPU_UCMD_STATUS_VM_BUSY) &&
    !(held.status&~(GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_VM_BUSY)) && (held.irq&GPU_IRQ_ENABLE) &&
    !(held.irq&~(GPU_IRQ_ENABLE|GPU_IRQ_PENDING)) && held.pin==bool(held.irq&GPU_IRQ_PENDING),
    "joint read response was not held by a live command");
  auto last_completion=[&] {
    return std::array{bus.read(GPU_REG_UCMD_COMPLETION),bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO),
      bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI)};
  };
  const auto previous_completion=last_completion();
  publish("held",held,0);
  auto replay=[&](const char *suffix,ResetPhase phase) {
    const auto path=session/(reset_stem+suffix);
    wait_input(path,session,timeout);
    const auto trace=OwnerResetTrace::read(path,phase,capture.command_id);
    for(const auto &a:trace.actions)bus.action(a);
  };
  replay("request",ResetPhase::request_with_irq);
  const auto before_reset_hold=held_cycles();
  for(unsigned cycle=0;cycle<capture.hold_cycles;++cycle) {
    bus.step();
    require((bus.read(GPU_REG_UCMD_STATUS)&~GPU_UCMD_STATUS_READY)==
      (GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_VM_BUSY) && !agent.drained(),
      "joint reset released the owner before its held read response drained");
  }
  verify_vm();
  const auto reset_holds=held_cycles()-before_reset_hold;
  require(reset_holds>=capture.hold_cycles,"joint reset lost its withheld read response");
  agent.release_response_hold();
  while(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_RESET_BUSY)bus.step();
  require(agent.drained(),"joint reset acknowledged before external drain");
  agent.disarm_response_hold();
  verify_vm();
  // The raw completion registers retain the last result after its valid bit clears.
  require(last_completion()==previous_completion,"cancelled joint job replaced the last completion");
  for(unsigned word=0;word<OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS;++word)
    require(!bus.read(joint_detail_address(word)),"cancelled joint job exposed stale GNZ2");
  require(agent.traffic.read_faults==traffic.read_faults && agent.traffic.write_faults==traffic.write_faults,
    "joint cancellation encountered an unexpected AXI fault");
  const auto completed=agent.traffic;
  for(unsigned cycle=0;cycle<32;++cycle)bus.step();
  const auto ack=observe();
  require(!ack.legacy && ack.status==GPU_UCMD_STATUS_READY && ack.irq==(GPU_IRQ_ENABLE|GPU_IRQ_PENDING) &&
    ack.pin && ack.drained,"joint reset did not acknowledge an idle drained command path");
  traffic_unchanged(completed,agent.traffic);
  verify_readonly(initial,memory,capture.writable);
  memory.write((output/(stem+".memory")).string());
  publish("ack",ack,reset_holds);

  replay("irq",ResetPhase::retire);
  const auto retired=observe();
  require(!retired.legacy && retired.status==GPU_UCMD_STATUS_READY && retired.irq==GPU_IRQ_ENABLE &&
    !retired.pin && retired.drained,"original joint reset IRQ did not retire idle and drained");
  verify_vm();
  traffic_unchanged(completed,agent.traffic);
  publish("retired",retired,reset_holds);
}
template<class Sim,class CaptureType>
std::pair<uint64_t,uint64_t> active_records(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const std::filesystem::path &output,const std::filesystem::path &session,
    const CaptureType &first,uint64_t count,std::ostream &report=std::cout,
    std::chrono::milliseconds response_timeout=std::chrono::minutes(5),PtePages *pte=nullptr) {
  ProfileObservation profile;
  if constexpr(!CaptureType::joint_completion)profile=probe_profile(sim,agent,bus);
  std::optional<native_frame::ExecutionProgress> progress;
  if constexpr(CaptureType::joint_completion)progress.emplace(output);
  uint64_t completed_read_faults=0, completed_write_faults=0;
  for(uint64_t n=0;n<count;n++) {
    const auto stem="record-"+std::to_string(n);
    if(n)wait_input(session/(stem+".capture"),session,response_timeout);
    const auto capture=n?CaptureType::read((session/(stem+".capture")).string(),true):first;
    if constexpr(CaptureType::joint_completion) {
      require(std::equal(first.discovery.begin(),first.discovery.end(),capture.discovery.begin(),capture.discovery.end(),
        [](const auto &a,const auto &b){return a.address==b.address && a.value==b.value;}),
        "joint capture changed the bound device discovery within a session");
    }else match_profile(capture,profile);
    verify_readonly(memory,memory,capture.writable);
    if(pte)pte->observe(memory,capture);
    uint64_t read_fault_line=0;
    if constexpr(CaptureType::joint_completion) {
      if(capture.cancel_read) {
        cancel_joint_read(sim,agent,bus,memory,capture,output,session,n,response_timeout);
        continue;
      }
      if(capture.uniform_read_fault) {
        const auto path=session/(stem+".uniform-fault.plan");
        wait_input(path,session,response_timeout);
        std::ifstream plan(path,std::ios::binary);
        magic(plan,"OGJFP001");
        read_fault_line=joint_read_line(plan,capture);
      }
    }
    if(progress)progress->begin(stem,n+1,count,capture.max_cycles,
      native_frame::progress_counters(0,bus.cycles,agent.traffic));
    auto held=hold_record(sim,agent,bus,memory,capture,true,progress?&*progress:nullptr,read_fault_line);
    atomic_json(session/(stem+".held.json"),[&](auto &out){held_json(out,held.result,n,capture.command_id);});
    wait_input(session/(stem+".retire"),session,response_timeout);
    const auto retirement=Retirement::read((session/(stem+".retire")).string(),CaptureType::joint_completion);
    const auto result=retire_record(sim,agent,bus,memory,capture,held,&retirement);
    completed_read_faults+=result.physical_read_faults;
    completed_write_faults+=result.physical_write_faults;
    memory.write((output/(stem+".memory")).string());
    atomic_json(session/(stem+".retired.json"),[&](auto &out){report_record(result,n,capture.command_id,bus.cycles,out);});
    report_record(result,n,capture.command_id,bus.cycles,report);
    if(progress)progress->finish(native_frame::progress_counters(result.cycles,bus.cycles,agent.traffic));
  }
  return {completed_read_faults,completed_write_faults};
}
template<class Sim>
void cleanup_records(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const std::filesystem::path &session,const PtePages &pte,uint64_t count,
    std::chrono::milliseconds timeout=std::chrono::minutes(5),uint64_t completed_read_faults=0,
    uint64_t completed_write_faults=0) {
  require(agent.traffic.read_faults==completed_read_faults && agent.traffic.write_faults==completed_write_faults,
    "cleanup began with unexpected physical fault history");
  wait_input(session/"cleanup.begin",session,timeout);
  {
    std::ifstream in(session/"cleanup.begin",std::ios::binary);magic(in,"OGCLB001");
    require(read_le(in,4)==count && in.peek()==EOF,"cleanup authorization lost the complete record set");
  }
  Memory expected=memory;const auto traffic=agent.traffic;
  unsigned sequence=0;uint64_t clears=0,shootdowns=0;bool context_closed=false;
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
    require(!context_closed,"cleanup issued work after its final context shutdown");
    wait_input(trace,session,timeout);const auto batch=Cleanup::read(trace.string());pte.validate_scope(batch,&memory);
    const bool closing=batch.closes_context();
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
    if(closing) {
      require(!bus.read(GPU_REG_UCMD_VECTOR_SATP) && !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP),
        "final context shutdown did not publish both bare SATPs on the actual DUT");
      context_closed=true;
    }
    traffic_unchanged(traffic,agent.traffic);
    require(agent.traffic.read_faults==completed_read_faults && agent.traffic.write_faults==completed_write_faults,
      "cleanup encountered new external memory faults");
    atomic_json(session/(stem+".observed"),[&](auto &out) {
      out.write("OGCLA001",8);for(uint32_t v:{sequence,status,irq,0u,1u})write_le(out,v,4);
    });
    require(sequence<65535,"cleanup exceeded its batch bound");++sequence;
  }
  require(clears && shootdowns && context_closed,"cleanup lacks real owner revocation or final context shutdown");
  pte.revoked(memory);verify_readonly(expected,memory,{});
  atomic_json(session/"cleanup.json",[&](auto &out) {
    out<<"{\"phase\":\"cleanup\",\"ownerCleanupObserved\":true,\"contextCleanupObserved\":true,\"records\":"<<count
      <<",\"batches\":"<<sequence<<",\"pteClears\":"<<clears<<",\"shootdowns\":"<<shootdowns
      <<",\"resetCount\":1,\"ramReplaced\":false,\"imagesPreserved\":true,\"externalDrained\":true,"
        "\"sourceQualificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}\n";
  });
}
} // namespace native_owner
