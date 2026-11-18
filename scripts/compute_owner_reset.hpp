#pragma once
// Original Linux reset traces over a retained original compute owner. No
// shader, result, completion slot or reset acknowledgement is synthesized.
#include "compute_owner_capture.hpp"
#include "native_owner_runtime.hpp"

namespace compute_owner {
template<class Sim,class CaptureType>
void verify_compute_upload(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const CaptureType &capture,uint64_t prior_read_faults=0,const Traffic *prior_traffic=nullptr) {
  capture.validate();
  require(agent.drained() && !sim.model->io_m_irq,"compute starts before prior drain");
  const auto prior_write_faults=prior_traffic?prior_traffic->write_faults:0;
  require(agent.traffic.read_faults==prior_read_faults && agent.traffic.write_faults==prior_write_faults &&
    agent.injected_read_faults()==prior_read_faults && agent.injected_write_faults()==prior_write_faults,
    "compute began with unexpected prior physical faults");
  const auto before=agent.traffic;
  if(prior_traffic)traffic_unchanged(*prior_traffic,before);
  const auto profile=probe_profile(sim,agent,bus,true,compute_owner_factory(),
    prior_traffic?prior_traffic:prior_read_faults?&before:nullptr);
  traffic_unchanged(before,agent.traffic);
  std::map<uint32_t,uint32_t> captured;
  for(const auto &r:capture.discovery)captured.emplace(r.address,r.value);
  for(const auto *words:{&profile.words,&profile.registers})for(const auto &r:*words)
    require(captured.at(r.address)==r.value,"compute capture differs from actual DUT observations");
  verify_readonly(memory,memory,capture.writable);
}

enum class ResetPhase { request,tail,retire };
struct ComputeResetTrace {
  uint32_t command_id=0;
  std::vector<Action> actions;
  void validate(ResetPhase phase,uint32_t expected) const {
    require(command_id==expected && command_id>0 && command_id<256,"reset trace changed command identity");
    const uint32_t status_mask=GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_OVERFLOW|
      GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_RESET_REJECTED|GPU_UCMD_STATUS_VM_BUSY;
    auto write=[&](size_t n,uint32_t address,uint32_t value) {
      require(n<actions.size() && actions[n].kind==Kind::write && actions[n].address==address &&
        actions[n].value==value && !actions[n].mask,"reset trace changed an original MMIO write");
    };
    auto read=[&](size_t n,Kind kind,uint32_t address,uint32_t value,uint32_t mask) {
      require(n<actions.size() && actions[n].kind==kind && actions[n].address==address &&
        actions[n].value==value && actions[n].mask==mask,"reset trace changed an original MMIO observation");
    };
    if(phase==ResetPhase::request) {
      require(actions.size()==3,"reset request must end at the original RESET write");
      write(0,GPU_REG_IRQ,0);
      const auto status=actions[1].value;
      require((status&GPU_UCMD_STATUS_VM_BUSY) && !(status&~(GPU_UCMD_STATUS_VM_BUSY|GPU_UCMD_STATUS_READY)),
        "abort status owns a completion or has already entered reset");
      read(1,Kind::poll,GPU_REG_UCMD_STATUS,status,status_mask);
      write(2,GPU_REG_UCMD_RESET,GPU_UCMD_RESET_REQUEST);
    }else if(phase==ResetPhase::tail) {
      require(actions.size()==1,"reset tail crossed the original IRQ enable");write(0,GPU_REG_IRQ,GPU_IRQ_ENABLE);
    }else {
      require(phase==ResetPhase::retire && actions.size()==6,"reset retirement differs from original IRQ path");
      read(0,Kind::check,GPU_REG_STATUS,0,0xffffffffu);
      read(1,Kind::check,GPU_REG_IRQ,GPU_IRQ_ENABLE|GPU_IRQ_PENDING,0xffffffffu);
      write(2,GPU_REG_IRQ,GPU_IRQ_ENABLE|GPU_IRQ_PENDING);write(3,GPU_REG_IRQ,GPU_IRQ_ENABLE);
      read(4,Kind::poll,GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_READY,status_mask);
      read(5,Kind::poll,GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_READY,status_mask);
    }
  }
  static ComputeResetTrace read(std::istream &in,ResetPhase phase,uint32_t command) {
    magic(in,phase==ResetPhase::request?"OGCRQ001":phase==ResetPhase::tail?"OGCRL001":"OGCRV001");
    ComputeResetTrace trace;trace.command_id=read_le(in,4);const auto count=read_le(in,4);
    require(count<=1024,"oversized original reset trace");
    for(uint64_t n=0;n<count;n++) {
      const auto k=read_le(in,4),a=read_le(in,4),v=read_le(in,4),m=read_le(in,4);
      trace.actions.push_back({Kind(k),uint32_t(a),uint32_t(v),uint32_t(m)});
    }
    require(in.peek()==EOF,"trailing original reset trace");trace.validate(phase,command);return trace;
  }
  static ComputeResetTrace read(const std::filesystem::path &path,ResetPhase phase,uint32_t command) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open original reset trace");return read(in,phase,command);
  }
};

struct ComputeResetPlan {
  ResponseChannel channel=ResponseChannel::read;
  uint64_t first=0,end=0;
  static ComputeResetPlan read(std::istream &in,ResponseChannel channel) {
    require(channel==ResponseChannel::read || channel==ResponseChannel::write,"unknown response-hold channel");
    magic(in,"OGCRP001");const auto selected=read_le(in,4);
    ComputeResetPlan plan;plan.channel=channel;plan.first=read_le(in,8);plan.end=read_le(in,8);
    require(selected==(channel==ResponseChannel::read?1u:2u) && plan.first<plan.end &&
      !(plan.first&63) && !(plan.end&63) && plan.end<=Memory::limit && in.peek()==EOF,
      "invalid original response-hold plan");return plan;
  }
};
struct ComputeResetResult {
  uint32_t command=0,legacy=0,status=0,irq=0;
  uint64_t cycles=0,read_holds=0,write_holds=0;
  bool pin=false,drained=false;
};
template<class Sim>
ComputeResetResult observe_compute_reset(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,uint32_t command,uint64_t start) {
  ComputeResetResult r;r.command=command;r.legacy=bus.read(GPU_REG_STATUS);
  r.status=bus.read(GPU_REG_UCMD_STATUS);r.irq=bus.read(GPU_REG_IRQ);
  r.pin=bool(sim.model->io_m_irq);r.drained=agent.drained();r.cycles=bus.cycles-start;
  r.read_holds=agent.held_read_cycles();r.write_holds=agent.held_write_cycles();
  require(!r.legacy && !(r.status&GPU_UCMD_STATUS_COMPLETION),"reset acquired an owned kernel completion");
  require(!agent.traffic.read_faults && !agent.traffic.write_faults && !agent.injected_read_faults(),
    "cancelled compute encountered physical memory errors");return r;
}
inline void compute_reset_json(std::ostream &out,const ComputeResetResult &r,const char *phase,ResponseChannel channel) {
  const std::string name=phase;const bool drained=name=="ack" || name=="retired";
  require(name=="held" || name=="busy" || drained,"unknown compute reset observation phase");
  out<<"{\"schema\":\"opengpu-compute-image-reset-"<<name<<"-v1\",\"phase\":\""<<name
    <<"\",\"record\":0,\"channel\":\""<<(channel==ResponseChannel::read?"read":"write")<<"\",\"commandId\":"<<r.command
    <<",\"legacyStatus\":"<<r.legacy<<",\"status\":"<<r.status<<",\"irq\":"<<r.irq
    <<",\"irqAsserted\":"<<(r.pin?"true":"false")<<",\"externalDrained\":"<<(r.drained?"true":"false")
    <<",\"completionAbsent\":true,\"resetCount\":1,\"unifiedResetRequests\":"<<(name=="held"?0:1)
    <<",\"resetRequestTraceReplayed\":"<<(name!="held"?"true":"false")
    <<",\"resetTailTraceReplayed\":"<<(drained?"true":"false")
    <<",\"resetIrqRetirementReplayed\":"<<(name=="retired"?"true":"false")
    <<",\"busySubmitRejected\":"<<(drained?"true":"false")
    <<",\"noLateTrafficCycles\":"<<(drained?32:0)<<",\"physicalReadFaults\":0,\"injectedReadFaults\":0,\"physicalWriteFaults\":0"
    <<",\"heldReadCycles\":"<<r.read_holds<<",\"heldWriteCycles\":"<<r.write_holds<<",\"serviceCycles\":"<<r.cycles
    <<",\"readOnlyGuards\":true,\"sourceQualificationRequired\":true,\"independentBytesRequired\":true"
    <<",\"clockQualified\":false,\"fpsQualified\":false}\n";
}

inline void verify_reset_readonly(const Memory &before,const Memory &after,const std::vector<Writable> &writable,const char *phase) {
  try {verify_readonly(before,after,writable);}
  catch(const std::runtime_error &error) {
    std::ostringstream message;message<<phase<<": "<<error.what();
    std::set<uint64_t> pages;for(const auto &p:before.pages)pages.insert(p.first);
    for(const auto &p:after.pages)pages.insert(p.first);
    for(auto page:pages)for(unsigned offset=0;offset<Memory::page_bytes;offset++) {
      const auto address=page+offset;const auto a=before.byte(address),b=after.byte(address);
      if(a!=b && !std::any_of(writable.begin(),writable.end(),[&](const auto &r){return r.first<=address && address<r.end;})) {
        message<<" physical=0x"<<std::hex<<address<<" before="<<unsigned(a)<<" after="<<unsigned(b);
        throw std::runtime_error(message.str());
      }
    }
    throw std::runtime_error(message.str());
  }
}
template<class Sim>
void active_cancel_compute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const ComputeCapture &capture,const std::filesystem::path &output,const std::filesystem::path &session,
    ResponseChannel channel,std::chrono::milliseconds timeout) {
  require(compute_owner_factory()==OwnerFactory::image_float_stage,"image cancellation requires the complete float owner");
  verify_compute_upload(sim,agent,bus,memory,capture);
  bool reset_capability=false;
  for(const auto &r:capture.discovery)if(r.address==GPU_REG_CAPABILITIES)reset_capability=r.value&GPU_CAP_UNIFIED_RESET;
  require(reset_capability,"actual compute discovery lacks unified reset");
  PtePages pte;capture.observe_contexts(memory,pte);
  wait_input(session/"record-0.reset.plan",session,timeout);
  std::ifstream input(session/"record-0.reset.plan",std::ios::binary);
  const auto plan=ComputeResetPlan::read(input,channel);
  require(!agent.held_read_cycles() && !agent.held_write_cycles(),"cancellation reused a preceding response-hold history");
  agent.arm_response_hold(channel,plan.first,plan.end);
  const auto initial=memory;const auto start=bus.cycles;
  bus.begin_record(capture.max_cycles);
  for(const auto &a:capture.trace)bus.action(a);
  auto holds=[&](){return channel==ResponseChannel::read?agent.held_read_cycles():agent.held_write_cycles();};
  while(!holds()) {
    bus.step();
    if((bus.cycles-start)%256==0)
      require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION),"kernel completed before selected response was held");
  }
  auto publish=[&](const ComputeResetResult &r,const char *phase) {
    atomic_json(session/(std::string("record-0.reset.")+phase+".json"),[&](auto &out){compute_reset_json(out,r,phase,channel);});
  };
  verify_reset_readonly(initial,memory,channel==ResponseChannel::read?std::vector<Writable>{}:capture.writable,"held before RESET");
  const auto held=observe_compute_reset(sim,agent,bus,capture.command_id,start);
  require((held.status&GPU_UCMD_STATUS_VM_BUSY) && !(held.status&~(GPU_UCMD_STATUS_VM_BUSY|GPU_UCMD_STATUS_READY)) &&
    (held.irq&GPU_IRQ_ENABLE) && !(held.irq&~(GPU_IRQ_ENABLE|GPU_IRQ_PENDING)) &&
    held.pin==bool(held.irq&GPU_IRQ_PENDING) && !held.drained,"held response lost actual VM or external ownership");
  publish(held,"held");
  auto replay=[&](const char *suffix,ResetPhase phase) {
    const auto path=session/(std::string("record-0.reset.")+suffix);wait_input(path,session,timeout);
    const auto trace=ComputeResetTrace::read(path,phase,capture.command_id);
    for(const auto &a:trace.actions)bus.action(a);
  };
  replay("request",ResetPhase::request);
  const auto busy=observe_compute_reset(sim,agent,bus,capture.command_id,start);
  // READY is queue capacity, independently of reset's admission rejection.
  if((busy.status&~GPU_UCMD_STATUS_READY)!=(GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_VM_BUSY) ||
      busy.drained || busy.pin || (busy.irq&~GPU_IRQ_PENDING))
    throw std::runtime_error("original RESET did not remain busy with accepted responses held: status="+std::to_string(busy.status)+
      " irq="+std::to_string(busy.irq)+" pin="+std::to_string(busy.pin)+" drained="+std::to_string(busy.drained));
  publish(busy,"busy");
  replay("tail",ResetPhase::tail);
  // A rejected SUBMIT must not launch the old staged kernel during drain.
  bus.write(GPU_REG_UCMD_SUBMIT,1);
  require((bus.read(GPU_REG_UCMD_STATUS)&~GPU_UCMD_STATUS_READY)==
      (GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_VM_BUSY|GPU_UCMD_STATUS_RESET_REJECTED),
    "unified reset accepted another kernel while memory responses were held");
  bus.write(GPU_REG_UCMD_STATUS,GPU_UCMD_STATUS_RESET_REJECTED);
  for(unsigned n=0;n<capture.hold_cycles;n++) {
    bus.step();
    require((bus.read(GPU_REG_UCMD_STATUS)&~GPU_UCMD_STATUS_READY)==(GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_VM_BUSY) && !agent.drained(),
      "reset released VM ownership before the selected response drained");
  }
  agent.release_response_hold();
  while(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_RESET_BUSY)bus.step();
  require(agent.drained(),"reset acknowledgement preceded external drain");agent.disarm_response_hold();
  const auto stopped=memory;const auto traffic=agent.traffic;
  for(unsigned n=0;n<32;n++)bus.step();
  const auto ack=observe_compute_reset(sim,agent,bus,capture.command_id,start);
  require(ack.status==GPU_UCMD_STATUS_READY && ack.irq==(GPU_IRQ_ENABLE|GPU_IRQ_PENDING) && ack.pin && ack.drained,
    "reset did not acknowledge an idle drained command path");
  traffic_unchanged(traffic,agent.traffic);verify_reset_readonly(stopped,memory,{},"after drained reset ACK");
  // Reset cancels the CU resource services, then waits for accepted work to
  // drain. The independent oracle checks the precise original cut states;
  // an accepted atomic can write even when vector commit is suppressed.
  verify_reset_readonly(initial,memory,capture.writable,"drained admitted kernel");
  memory.write((output/"record-0.memory").string());
  atomic_json(session/"record-0.reset.ack.json",[&](auto &out){compute_reset_json(out,ack,"ack",channel);});
  // The controller checks cancellation bytes before the original IRQ handler runs.
  replay("retire",ResetPhase::retire);
  const auto retired=observe_compute_reset(sim,agent,bus,capture.command_id,start);
  require(retired.status==GPU_UCMD_STATUS_READY && retired.irq==GPU_IRQ_ENABLE && !retired.pin && retired.drained,
    "original reset IRQ did not retire idle and drained");
  traffic_unchanged(traffic,agent.traffic);verify_reset_readonly(stopped,memory,{},"after original reset IRQ retirement");
  publish(retired,"retired");
  cleanup_records(sim,agent,bus,memory,session,pte,1,timeout);
}
} // namespace compute_owner
