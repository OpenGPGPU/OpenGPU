#pragma once
#include "compute_reservation_idle.hpp"
namespace compute_owner {
inline void active_reservation_wait(int argc,char **argv,const std::filesystem::path &output,
    const std::filesystem::path &session,std::chrono::milliseconds timeout) {
  require(compute_owner_factory()==OwnerFactory::image_float_stage,"reservation wait requires the complete float factory");
  require(std::filesystem::is_directory(session) && !std::filesystem::is_symlink(session) &&
    !std::filesystem::exists(output),"invalid reservation session/output");
  wait_input(session/"reservation.plan",session,timeout);
  std::ifstream plan(session/"reservation.plan",std::ios::binary);magic(plan,"OGCRWP01");
  const auto phase=read_le(plan,4),cached=read_le(plan,4),quiet=read_le(plan,4),reserved=read_le(plan,4);
  const auto physical=read_le(plan,8),producer_context=read_le(plan,8),producer_seq=read_le(plan,8),
    consumer_seq=read_le(plan,8),context=read_le(plan,8),bytes=read_le(plan,8);
  const auto base=0x01000000ull+phase*0x01000000ull;
  require(phase<48 && cached==(phase&1) && quiet==257 && !reserved && physical<=0xffffe000ull && !(physical&4095) &&
    physical>=base && physical+8192<=base+0x01000000ull && producer_context && producer_seq==1 &&
    consumer_seq==1 && context==1 && bytes==8192 && plan.peek()==EOF,"foreign original reservation plan");
  require(!std::filesystem::exists(session/"READY") && !std::filesystem::exists(session/"union.memory"),
    "consumer was released before native idle observation");
  Memory memory;Simulation sim(argc,argv);sim.reset();Agent agent(memory);ReplayBus<Simulation> bus(sim,agent,100000);
  const auto interval=reservation_idle(sim,agent,bus);
  atomic_json(session/"reservation.idle.json",[&](auto &out) {
    out<<"{\"schema\":\"opengpu-compute-reservation-idle-v1\",\"phase\":"<<phase
      <<",\"cachedKernarg\":"<<(cached?"true":"false")<<",\"producerKind\":\"external-cpu\",\"physical\":"<<physical
      <<",\"producerContext\":"<<producer_context<<",\"producerSequence\":"<<producer_seq
      <<",\"consumerSequence\":"<<consumer_seq<<",\"context\":"<<context<<",\"bytes\":"<<bytes
      <<",\"resetCount\":1,\"ramUploadedBeforeRelease\":false,\"readyBefore\":true,\"readyAfter\":true,\"irqAsserted\":false,\"externalRequests\":0"
      <<",\"startCycle\":"<<interval.first<<",\"endCycle\":"<<interval.second
      <<",\"quietCycles\":"<<interval.second-interval.first<<"}\n";
  });
  wait_input(session/"READY",session,timeout);wait_input(session/"union.memory",session,timeout);
  wait_input(session/"record-0.capture",session,timeout);
  // The CPU producer may finish before independent source/PTE admission.
  // Its completion is never permission to execute unverified RAM/capture.
  wait_input(session/"BEGIN_EXECUTION",session,timeout);
  std::ifstream authorization(session/"BEGIN_EXECUTION",std::ios::binary);magic(authorization,"OGCRGO01");
  require(read_le(authorization,4)==phase && authorization.peek()==EOF,"foreign reservation execution authorization");
  require(agent.drained(),"reservation RAM upload preceded external drain");
  memory=Memory::read((session/"union.memory").string());
  const auto capture=ComputeCapture::read((session/"record-0.capture").string());
  require(capture.writable.size()==1 && capture.writable[0].first==physical &&
    capture.writable[0].end==physical+8192,"released consumer changed its original GEM");
  verify_readonly(memory,memory,capture.writable);
  require(std::filesystem::create_directory(output),"cannot create reservation execution output");
  active_compute(sim,agent,bus,memory,capture,output,session,timeout);
  require(bus.read(GPU_REG_UCMD_STATUS)==GPU_UCMD_STATUS_READY && bus.read(GPU_REG_IRQ)==GPU_IRQ_ENABLE &&
    !sim.model->io_m_irq && agent.drained() && !bus.read(GPU_REG_UCMD_VECTOR_SATP) &&
    !bus.read(GPU_REG_UCMD_INSTRUCTION_SATP),"reservation consumer retained contexts, IRQ or external traffic");
  memory.write((output/"record-0.cleaned.memory").string());
  atomic_json(session/"reservation.done.json",[&](auto &out) {
    out<<"{\"schema\":\"opengpu-compute-reservation-done-v1\",\"phase\":"<<phase
      <<",\"resetCount\":1,\"readyAfterCleanup\":true,\"irqAsserted\":false,\"externalDrained\":true,"
        "\"vectorSatp\":0,\"instructionSatp\":0,\"physicalReadFaults\":0,\"physicalWriteFaults\":0"
      <<",\"startCycle\":"<<interval.first<<",\"endCycle\":"<<bus.cycles<<"}\n";
  });
  sim.model->final();
}
} // namespace compute_owner
