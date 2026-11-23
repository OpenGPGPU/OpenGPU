#pragma once

#include "native_sample_owner_runtime.hpp"
#include "../driver/opengpu_transform_feedback_abi.h"

namespace native_owner {
struct GeometryFeedbackPlan {
  static constexpr uint32_t context=0x71000007, cookie=101, first_id=192, record_offset=512;
  uint32_t pool, physical, root, program;

  static GeometryFeedbackPlan read(const std::filesystem::path &path,const SampleCapture &capture) {
    std::ifstream in(path,std::ios::binary);
    require(bool(in),"missing geometry feedback plan");
    magic(in,"OGGTF001");
    auto word=[&]{return uint32_t(read_le(in,4));};
    const GeometryFeedbackPlan plan{word(),word(),word(),word()};
    require(in.peek()==EOF && !(plan.pool&4095) && !(plan.physical&4095) &&
      (plan.root&GPU_SATP_ENABLE) && plan.program && !(plan.program&3),
      "invalid geometry feedback plan");
    require(capture.command_id<first_id || capture.command_id>=first_id+6,
      "geometry feedback reuses the original command identity");
    require(std::any_of(capture.writable.begin(),capture.writable.end(),[&](const auto &range) {
      return range.first<=plan.physical && uint64_t(plan.physical)+4096<=range.end;
    }),"geometry feedback pool is outside the original mapped resource");
    return plan;
  }
};

struct GeometryFeedbackReceipt {
  uint32_t command, opcode, completion, data, held_status, held_irq, retired_status, retired_irq;
};

template<class Sim>
GeometryFeedbackReceipt finish_geometry_feedback(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,
    const GeometryFeedbackPlan &plan,uint32_t id,uint32_t opcode,
    const SampleCapture &capture,uint32_t expected_control_flags=0) {
  while(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION))bus.step();
  require(agent.drained() && sim.model->io_m_irq,"geometry feedback completion before real drain or IRQ");
  const auto traffic=agent.traffic;
  const auto status=bus.read(GPU_REG_UCMD_STATUS),irq=bus.read(GPU_REG_IRQ);
  const auto completion=bus.read(GPU_REG_UCMD_COMPLETION),data=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO);
  const auto data_hi=bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI);
  std::array<uint32_t,12> gnx{};
  require(GPU_UCMD_COMPLETION_ID(completion)==id && GPU_UCMD_COMPLETION_OPCODE(completion)==opcode &&
    GPU_UCMD_COMPLETION_SUCCESS(completion) && !GPU_UCMD_COMPLETION_STATUS(completion) &&
    irq==(GPU_IRQ_ENABLE|GPU_IRQ_PENDING),"wrong geometry feedback held result");
  for(size_t i=0;i<gnx.size();++i)gnx[i]=bus.read(0x180+4*i);
  if(opcode==OPENGPU_TF_OPCODE) {
    require(data==expected_control_flags && !data_hi &&
      std::all_of(gnx.begin(),gnx.end(),[](uint32_t word){return word==0;}),
      "GTF1 completion data or render-detail scope changed");
  } else {
    for(const auto &field:capture.checks)
      if(field.address>=0x180 && field.address<0x1b0)
        require((gnx[(field.address-0x180)/4]&field.mask)==field.expected,
          "geometry feedback render lost its original GNX identity or succeeded with a fault");
  }

  for(unsigned n=0;n<capture.hold_cycles;n++) {
    require(sim.model->io_m_irq && agent.drained(),"held geometry feedback released the IRQ or reopened memory");
    bus.step();
  }
  bus.write(GPU_REG_UCMD_VECTOR_SATP,0,2);
  require(bus.read(GPU_REG_UCMD_VECTOR_SATP)==plan.root &&
    bus.read(GPU_REG_UCMD_INSTRUCTION_SATP)==plan.root &&
    bus.read(GPU_REG_UCMD_COMPLETION)==completion && bus.read(GPU_REG_UCMD_STATUS)==status &&
    bus.read(GPU_REG_IRQ)==irq && bus.read(GPU_REG_UCMD_COMPLETION_BYTES_LO)==data &&
    bus.read(GPU_REG_UCMD_COMPLETION_BYTES_HI)==data_hi,"held geometry feedback ownership changed");
  for(size_t i=0;i<gnx.size();++i)
    require(bus.read(0x180+4*i)==gnx[i],"held geometry feedback detail changed");
  bus.write(GPU_REG_UCMD_COMPLETION_POP,1);
  bus.write(GPU_REG_IRQ,GPU_IRQ_ENABLE|GPU_IRQ_PENDING);
  for(unsigned n=0;n<8;n++)bus.step();
  const auto retired_status=bus.read(GPU_REG_UCMD_STATUS),retired_irq=bus.read(GPU_REG_IRQ);
  require(retired_status==GPU_UCMD_STATUS_READY && retired_irq==GPU_IRQ_ENABLE &&
    !sim.model->io_m_irq && agent.drained(),"geometry feedback retirement left pending ownership");
  traffic_unchanged(traffic,agent.traffic);
  return {id,opcode,completion,data,status,irq,retired_status,retired_irq};
}

template<class Sim>
void run_geometry_feedback(Sim &sim,Agent &agent,ReplayBus<Sim> &bus,Memory &memory,
    const SampleCapture &capture,const std::filesystem::path &output,
    const std::filesystem::path &session,std::chrono::milliseconds timeout) {
  const auto path=session/"geometry-feedback.plan";
  wait_input(path,session,timeout);
  const auto plan=GeometryFeedbackPlan::read(path,capture);
  const Memory before=memory;
  const auto initial_traffic=agent.traffic;
  std::map<uint32_t,uint32_t> stages;
  for(const auto &action:capture.trace)
    if(action.kind==Kind::write)stages[action.address]=action.value;
  const auto job=stages.at(GPU_REG_UCMD_SOURCE),limit=stages.at(GPU_REG_UCMD_DESTINATION);
  const auto limit_hi=stages.at(GPU_REG_UCMD_PATTERN);
  require(stages.at(GPU_REG_UCMD_VECTOR_SATP)==plan.root &&
    stages.at(GPU_REG_UCMD_INSTRUCTION_SATP)==plan.root &&
    !stages.at(GPU_REG_UCMD_FLAGS) && stages.at(GPU_REG_UCMD_BYTES)==512 &&
    bus.read(GPU_REG_UCMD_VECTOR_SATP)==plan.root &&
    bus.read(GPU_REG_UCMD_INSTRUCTION_SATP)==plan.root,
    "feedback replay lost the original retained geometry address space");
  std::vector<GeometryFeedbackReceipt> receipts;
  auto stage=[&](uint32_t opcode,uint32_t source,uint32_t destination,uint32_t pattern,
                 uint32_t flags,uint32_t height,uint32_t stride) {
    require(!sim.model->io_m_irq && agent.drained(),"geometry feedback command crossed earlier retirement");
    bus.begin_record(capture.max_cycles);
    const auto id=GeometryFeedbackPlan::first_id+uint32_t(receipts.size());
    for(const auto &word:std::array<Command,10>{{
        {GPU_REG_UCMD_ID,id},{GPU_REG_UCMD_OPCODE,opcode},{GPU_REG_UCMD_SOURCE,source},
        {GPU_REG_UCMD_DESTINATION,destination},{GPU_REG_UCMD_PATTERN,pattern},{GPU_REG_UCMD_BYTES,512},
        {GPU_REG_UCMD_WIDTH,GeometryFeedbackPlan::context},{GPU_REG_UCMD_HEIGHT,height},
        {GPU_REG_UCMD_SOURCE_STRIDE,stride},{GPU_REG_UCMD_FLAGS,flags}}})
      bus.write(word.address,word.value);
    bus.write(GPU_REG_UCMD_SUBMIT,1);
    return id;
  };
  uint64_t ack_hold=0;
  auto control=[&](uint32_t slot,uint32_t action) {
    const auto offset=GeometryFeedbackPlan::record_offset+slot*OPENGPU_TF_RECORD_BYTES;
    const auto address=plan.pool+offset;
    const auto physical=uint64_t(plan.physical)+offset;
    require(PtePages::word(memory,physical+4*OPENGPU_TF_ACTION_WORD)==action,
      "geometry feedback control packet changed action");
    const bool end=action==OPENGPU_TF_ACTION_END;
    const auto prior_hold=agent.held_write_cycles();
    const auto prior_traffic=agent.traffic;
    if(end)agent.arm_response_hold(ResponseChannel::write,physical+128,physical+192);
    const auto id=stage(OPENGPU_TF_OPCODE,address,address+OPENGPU_TF_RECORD_BYTES,0,0,
      GeometryFeedbackPlan::cookie,plan.program);
    if(end) {
      while(agent.held_write_cycles()==prior_hold) {
        require(!sim.model->io_m_irq,"GTF1 end completed before the selected final ACK");
        bus.step();
      }
      require(agent.traffic.writes-prior_traffic.writes==8 &&
        agent.traffic.acknowledgements-prior_traffic.acknowledgements==7,
        "GTF1 commit line preceded acknowledgement of the other checkpoint lines");
      bus.write(GPU_REG_UCMD_VECTOR_SATP,0,2);
      for(unsigned n=0;n<17;n++) {
        require(!(bus.read(GPU_REG_UCMD_STATUS)&GPU_UCMD_STATUS_COMPLETION) && !sim.model->io_m_irq,
          "GTF1 end published while its commit-line ACK was withheld");
        bus.step();
      }
      ack_hold=agent.held_write_cycles()-prior_hold;
      agent.release_response_hold();
    }
    const auto flags=OPENGPU_TF_DATA_RECORD_COMPLETE|OPENGPU_TF_DATA_APPLIED|(end?0:OPENGPU_TF_DATA_ACTIVE);
    receipts.push_back(finish_geometry_feedback(sim,agent,bus,plan,id,OPENGPU_TF_OPCODE,capture,flags));
    if(end)agent.disarm_response_hold();
  };
  auto draw=[&](bool discard) {
    const auto flags=OPENGPU_TF_RENDER_CAPTURE|(discard?OPENGPU_TF_RENDER_RASTER_DISCARD:0);
    const auto id=stage(GPU_UCMD_OP_NATIVE_BINNED_RENDER,job,limit,limit_hi,flags,
      plan.program,GeometryFeedbackPlan::cookie);
    receipts.push_back(finish_geometry_feedback(sim,agent,bus,plan,id,GPU_UCMD_OP_NATIVE_BINNED_RENDER,capture));
  };

  control(0,OPENGPU_TF_ACTION_BEGIN);
  draw(true);
  memory.write((output/"feedback-discard.memory").string());
  control(1,OPENGPU_TF_ACTION_PAUSE);
  control(2,OPENGPU_TF_ACTION_RESUME);
  draw(false);
  control(3,OPENGPU_TF_ACTION_END);
  require(!agent.traffic.read_faults && !agent.traffic.write_faults &&
    agent.traffic.reads>initial_traffic.reads && agent.traffic.writes>initial_traffic.writes,
    "geometry feedback did not perform fault-free physical traffic");
  verify_readonly(before,memory,capture.writable);
  memory.write((output/"feedback-render.memory").string());
  atomic_json(session/"geometry-feedback.json",[&](auto &out) {
    out<<"{\"schema\":\"opengpu-native-geometry-feedback-v1\",\"resetCount\":1,"
      "\"commitAckHeldCycles\":"<<ack_hold<<",\"receipts\":[";
    for(size_t i=0;i<receipts.size();++i) {
      const auto &r=receipts[i];
      out<<(i?",":"")<<"{\"command\":"<<r.command<<",\"opcode\":"<<r.opcode
        <<",\"completion\":"<<r.completion<<",\"data\":"<<r.data
        <<",\"heldStatus\":"<<r.held_status<<",\"heldIrq\":"<<r.held_irq
        <<",\"retiredStatus\":"<<r.retired_status<<",\"retiredIrq\":"<<r.retired_irq<<'}';
    }
    out<<"]}\n";
  });
}
} // namespace native_owner
