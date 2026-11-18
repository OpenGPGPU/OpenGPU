// Continuous original-GEM producer/indirect consumers on one complete DUT/RAM.
#define OPENGPU_OWNER_IMAGE_FLOAT_STAGE 1
#define OPENGPU_COMPUTE_OWNER_LIBRARY 1
#include "compute_owner_driver.cpp"
#include "compute_owner_phase.hpp"
#include <type_traits>

namespace indirect_owner {
using namespace compute_phase;
constexpr unsigned phase_count=10;
struct Mode {
  uint32_t index=0,opcode=0,status=0,context=0,final=0;
  static Mode read(const std::filesystem::path &path,unsigned expected) {
    std::ifstream in(path,std::ios::binary);require(bool(in),"cannot open continuous phase mode");magic(in,"OGCIM001");
    Mode m;m.index=read_le(in,4);m.opcode=read_le(in,4);m.status=read_le(in,4);m.context=read_le(in,4);m.final=read_le(in,4);
    const bool producer=expected==0 || expected==4 || expected==6 || expected==8;
    require(m.index==expected && m.opcode==(producer?0u:14u) && m.status==(expected==2?13u:0u) &&
      m.context && m.context<512 && m.final==uint32_t(expected+1==phase_count) && in.peek()==EOF,
      "continuous phase lost its original producer/consumer/fault/zero recipe");return m;
  }
};
inline void authorize(const std::filesystem::path &session,unsigned index,std::chrono::milliseconds timeout) {
  wait_input(session/"BEGIN_EXECUTION",session,timeout);
  std::ifstream in(session/"BEGIN_EXECUTION",std::ios::binary);magic(in,"OGCIEX01");
  require(read_le(in,4)==index && in.peek()==EOF,"wrong continuous execution authorization");
}
inline void indirect_json(std::ostream &out,const ComputeResult &r,const Mode &m,uint32_t command,bool retired) {
  out<<"{\"schema\":\"opengpu-compute-indirect-"<<(retired?"retired":"held")<<"-v1\",\"phase\":\""
    <<(retired?"retired":"held")<<"\",\"record\":0,\"opcode\":14,\"commandId\":"<<command
    <<",\"resultStatus\":"<<m.status<<",\"completion\":"<<r.completion<<",\"processedBytes\":"<<r.processed_bytes
    <<",\"heldLegacyStatus\":"<<r.legacy<<",\"heldStatus\":"<<r.status<<",\"heldIrq\":"<<r.irq
    <<",\"heldIrqAsserted\":"<<(r.held_pin?"true":"false")<<",\"heldCompletionDrained\":"<<(r.drained?"true":"false");
  if(retired)out<<",\"retiredStatus\":"<<r.retired_status<<",\"retiredIrq\":"<<r.retired_irq
    <<",\"retiredIrqAsserted\":"<<(r.retired_pin?"true":"false")<<",\"externalDrained\":"<<(r.drained?"true":"false");
  out<<",\"serviceCycles\":"<<r.cycles<<",\"readBusBytes\":"<<r.read_bytes<<",\"writeBusBytes\":"<<r.write_bytes
    <<",\"physicalReadFaults\":"<<r.physical_read_faults<<",\"injectedReadFaults\":"<<r.injected_read_faults
    <<",\"physicalWriteFaults\":0,\"privateCountsRequired\":true,\"readOnlyGuards\":true,"
      "\"sourceQualificationRequired\":true,\"independentBytesRequired\":true,\"resetCount\":1,"
      "\"clockQualified\":false,\"fpsQualified\":false}\n";
}
inline void arm_fault(Agent &agent,const IndirectCapture &capture,const std::filesystem::path &session,
    std::chrono::milliseconds timeout) {
  wait_input(session/"record-0.fault-plan",session,timeout);
  std::ifstream in(session/"record-0.fault-plan",std::ios::binary);magic(in,"OGCIF001");
  const auto first=read_le(in,8),end=read_le(in,8);const auto &a=capture.arguments;
  require(first==a.snapshot_physical+(a.snapshot_offset&~uint64_t(63)) &&
    end==a.snapshot_physical+((a.snapshot_offset+12+63)&~uint64_t(63)) && in.peek()==EOF,
    "indirect fault must target precisely its actual private count cache lines");
  agent.arm_read_faults(first,end);
}
struct State {PtePages policy;std::set<Extent> retained;Traffic traffic;uint64_t faults=0;};

template<class Sim,class CaptureType>
void phase_cleanup(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,const CaptureType &capture,
    const Originals &originals,const PtePages &pte,const Lease &lease,const Mode &mode,
    const std::filesystem::path &session,const std::filesystem::path &output,std::chrono::milliseconds timeout) {
  wait_input(session/"cleanup.begin",session,timeout);
  std::ifstream authorization(session/"cleanup.begin",std::ios::binary);const auto begin=Begin::read(authorization);
  begin.bind(originals,mode.context);
  require(agent.drained() && !sim.model->io_m_irq,"phase cleanup preceded actual retirement drain");
  Memory expected=memory;const auto traffic=agent.traffic;
  unsigned sequence=0,clears=0,shootdowns=0,publications=0;std::vector<Action> terminal;
  for(;;) {
    const auto stem="cleanup-"+std::to_string(sequence);
    const auto trace=session/(stem+".trace"),done=session/"cleanup.done";
    const auto deadline=std::chrono::steady_clock::now()+timeout;
    while(!std::filesystem::exists(trace) && !std::filesystem::exists(done)) {
      require(!std::filesystem::exists(session/"error"),"continuous source cleanup failed");
      require(std::chrono::steady_clock::now()<deadline,"continuous source cleanup timed out");
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if(std::filesystem::exists(done)) {
      require(!std::filesystem::exists(trace),"phase completion crosses an unacknowledged batch");
      wait_input(done,session,timeout);std::ifstream in(done,std::ios::binary);const auto complete=Done::read(in);
      complete.verify(begin,sequence,capture,pte,memory,lease,terminal);break;
    }
    wait_input(trace,session,timeout);const auto batch=Batch::read(trace.string());
    const auto scope=batch.revocations(originals,capture.writable);compute_phase::validate_scope(pte,scope,memory);
    require(agent.drained() && !sim.model->io_m_irq,"phase batch starts with active GPU traffic/IRQ");
    bus.begin_record(capture.max_cycles);
    for(const auto &a:batch.actions) {
      if(a.kind==Kind::pte_clear){pte.clear(expected,a);pte.clear(memory,a);++clears;}
      else {
        bus.action(a);
        shootdowns+=a.kind==Kind::write && a.address==GPU_REG_UCMD_TLB_FLUSH;
        publications+=a.kind==Kind::write && a.address==GPU_REG_UCMD_SUBMIT;
      }
    }
    for(unsigned n=0;n<8;n++)bus.step();
    const auto status=bus.read(GPU_REG_UCMD_STATUS),irq=bus.read(GPU_REG_IRQ);
    if(status!=GPU_UCMD_STATUS_READY || irq!=GPU_IRQ_ENABLE || sim.model->io_m_irq || !agent.drained())
      throw std::runtime_error("phase publication/revocation did not finish idle and drained: phase="+
        std::to_string(mode.index)+" batch="+std::to_string(sequence)+" status="+std::to_string(status)+
        " irq="+std::to_string(irq)+" pin="+std::to_string(bool(sim.model->io_m_irq))+
        " drained="+std::to_string(agent.drained()));
    traffic_unchanged(traffic,agent.traffic);verify_readonly(expected,memory,{});
    atomic_json(session/(stem+".observed"),[&](auto &out) {
      out.write("OGCPA001",8);for(uint32_t v:{sequence,status,irq,0u,1u})write_le(out,v,4);
    });
    terminal=batch.actions;require(sequence<65535,"too many phase cleanup batches");++sequence;
  }
  require(clears && shootdowns && (!(mode.opcode==0 || mode.index==1 || mode.index==3) || publications),
    "continuous phase lacks private revocation or successful-write publication");
  const auto vector=bus.read(GPU_REG_UCMD_VECTOR_SATP),instruction=bus.read(GPU_REG_UCMD_INSTRUCTION_SATP);
  require(vector==instruction && (vector&GPU_SATP_ENABLE) &&
    !((vector>>GPU_SATP_ASID_SHIFT)&GPU_SATP_ASID_MASK) && pte.satps.count(vector),
    "phase did not retain the original actual global MMU policy");
  for(unsigned n=0;n<32;n++){bus.step();require(agent.drained() && !sim.model->io_m_irq,"late phase traffic or IRQ");}
  traffic_unchanged(traffic,agent.traffic);verify_readonly(expected,memory,{});
  memory.write((output/"cleanup.memory").string());
  atomic_json(session/"cleanup.json",[&](auto &out) {
    out<<"{\"schema\":\"opengpu-compute-phase-cleanup-v1\",\"phase\":\"phase-cleanup\",\"commandId\":"<<originals.command
      <<",\"contextId\":"<<mode.context<<",\"epoch\":"<<mode.index<<",\"batches\":"<<sequence
      <<",\"status\":1,\"irq\":1,\"irqAsserted\":false,\"externalDrained\":true,\"quietCycles\":32,"
        "\"readOnlyGuards\":true,\"privateMappingsRevoked\":true,\"namespaceRetired\":true,\"resetCount\":1,"
        "\"ramReplaced\":false,\"sourceQualificationRequired\":true,\"clockQualified\":false,\"fpsQualified\":false,"
        "\"pteClears\":"<<clears<<",\"shootdowns\":"<<shootdowns<<",\"publications\":"<<publications<<"}\n";
  });
}
template<class Sim,class CaptureType>
void execute(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,const CaptureType &capture,
    const Mode &mode,const std::filesystem::path &session,const std::filesystem::path &output,
    State &state,std::chrono::milliseconds timeout) {
  const auto originals=Originals::read((session/"originals.bytes").string());
  originals.verify(memory,capture.command_id,mode.opcode,mode.index);
  PtePages pte=state.policy;capture.observe_contexts(memory,pte);
  const auto lease=Lease::observe(originals,pte,memory,capture.trace);
  require(std::none_of(capture.empty_contexts.begin(),capture.empty_contexts.end(),[&](const auto &c) {
    return c.context==mode.context;
  }),"active phase claims an original empty context");
  if constexpr(std::is_same_v<CaptureType,IndirectCapture>) {
    const auto &source=originals.object(capture.arguments.source_handle);
    require(mode.context==capture.arguments.context && source.physical==capture.arguments.source_physical &&
      source.bytes.size()==capture.arguments.source_bytes,"indirect source lost its actual original GEM");
    if(mode.status)arm_fault(agent,capture,session,timeout);
  }
  const auto expected=mode.opcode==14?compute_indirect::completion(capture.command_id,mode.status):0x8000u|capture.command_id;
  auto held=hold_compute_record(sim,agent,bus,memory,capture,expected,
    mode.status?ImageFailure::source_read:ImageFailure::none,state.faults,mode.index?&state.traffic:nullptr);
  auto report=[&](auto &out,const ComputeResult &r,bool retired) {
    if(mode.opcode==14)indirect_json(out,r,mode,capture.command_id,retired);
    else compute_json(out,r,capture.command_id,retired);
  };
  atomic_json(session/"record-0.held.json",[&](auto &out){report(out,held.result,false);});
  wait_input(session/"record-0.retire",session,timeout);ComputeResult result;
  if constexpr(std::is_same_v<CaptureType,IndirectCapture>) {
    const auto retirement=IndirectRetirement::read((session/"record-0.retire").string());
    require(retirement.command_id==capture.command_id && retirement.status==mode.status,
      "indirect retirement changed the actual held identity/result");
    result=retire_compute_record(sim,agent,bus,memory,held,retirement);
  }else result=retire_compute(sim,agent,bus,memory,held,ComputeRetirement::read((session/"record-0.retire").string()));
  if(mode.status)agent.disarm_read_faults();
  memory.write((output/"record-0.memory").string());
  atomic_json(output/"record-0.originals",[&](auto &out){originals.write_actual(out,memory);});
  atomic_json(session/"record-0.retired.json",[&](auto &out){report(out,result,true);});
  if(mode.final) {
    cleanup_records(sim,agent,bus,memory,session,pte,1,timeout,held.completed.read_faults);
    const auto traffic=agent.traffic;const auto before=memory;
    for(unsigned n=0;n<32;n++){bus.step();require(agent.drained() && !sim.model->io_m_irq,"late final traffic or IRQ");}
    traffic_unchanged(traffic,agent.traffic);verify_readonly(before,memory,{});
  }else {
    phase_cleanup(sim,agent,bus,memory,capture,originals,pte,lease,mode,session,output,timeout);
    state.retained=originals.retained(memory,pte);state.policy=global_policy(pte);
  }
  state.faults=agent.traffic.read_faults;state.traffic=agent.traffic;
}
} // namespace indirect_owner

int main(int argc,char **argv) {
  try {
    using namespace indirect_owner;
    const auto timeout=compute_response_timeout();
    if(argc==3 && std::string(argv[1])=="--probe-profile") {
      require(!std::filesystem::exists(argv[2]),"indirect probe output already exists");
      Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
      const auto observed=probe_profile(sim,agent,bus,true,compute_owner_factory());
      atomic_json(argv[2],[&](auto &out){profile_json(out,observed,true);});sim.model->final();return 0;
    }
    require(argc==4 && std::string(argv[1])=="--active-indirect",
      "usage: compute_indirect_owner_driver --probe-profile NEW.json | --active-indirect NEW_OUTPUT SESSION");
    const std::filesystem::path output=argv[2],session=argv[3];
    require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session) &&
      !std::filesystem::exists(output),"invalid continuous session/output");
    authorize(session/"phase-0",0,timeout);wait_input(session/"phase-0/union.memory",session/"phase-0",timeout);
    Memory memory=Memory::read((session/"phase-0/union.memory").string());
    require(std::filesystem::create_directory(output),"cannot create continuous output");
    Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);State state;
    for(unsigned index=0;index<phase_count;index++) {
      const auto local=session/("phase-"+std::to_string(index)),result=output/("phase-"+std::to_string(index));
      if(index) {
        authorize(local,index,timeout);wait_input(local/"union.memory",local,timeout);
        require(agent.drained() && !sim.model->io_m_irq,"new phase preceded prior drain");
        append_retained(memory,Memory::read((local/"union.memory").string()),state.retained);
      }
      wait_input(local/"mode",local,timeout);const auto mode=Mode::read(local/"mode",index);
      require(std::filesystem::create_directory(result),"continuous phase output already exists");
      wait_input(local/"record-0.capture",local,timeout);
      if(mode.opcode==14)execute(sim,agent,bus,memory,IndirectCapture::read((local/"record-0.capture").string()),
        mode,local,result,state,timeout);
      else execute(sim,agent,bus,memory,ComputeCapture::read((local/"record-0.capture").string()),
        mode,local,result,state,timeout);
    }
    atomic_json(output/"execution.json",[&](auto &out) {
      out<<"{\"schema\":\"opengpu-compute-indirect-continuous-v1\",\"phases\":10,\"resetCount\":1,"
        "\"ramReplaced\":false,\"externalDrained\":true,\"physicalReadFaults\":"<<state.faults
        <<",\"sourceQualificationRequired\":true,\"independentBytesRequired\":true,\"clockQualified\":false,\"fpsQualified\":false}\n";
    });
    sim.model->final();return 0;
  }catch(const std::exception &e){std::cerr<<"indirect owner execution failed: "<<e.what()<<'\n';return 1;}
}
