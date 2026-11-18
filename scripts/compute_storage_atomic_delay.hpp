#pragma once
#include "native_frame_axi.hpp"
#include "../driver/gpu_abi.h"

namespace compute_owner {
// Withhold a real due B response through the existing AXI gate. Memory and
// atomic results still come from the original DUT and physical backing.
struct StorageAckDelay {
  uint64_t first=0,end=0,start_cycles=0,held_cycles=0;
  uint32_t requested_cycles=0,status_before_release=0;
  bool armed=false,finished=false;
  static StorageAckDelay read(std::istream &in) {
    native_frame::magic(in,"OGCSACK1");
    StorageAckDelay plan;
    plan.first=native_frame::read_le(in,8);plan.end=native_frame::read_le(in,8);
    plan.requested_cycles=native_frame::read_le(in,4);
    native_frame::require(in.peek()==EOF && plan.first && plan.first<native_frame::Memory::limit &&
      !(plan.first&63) && plan.end==plan.first+64 && plan.requested_cycles==37,
      "invalid original storage ACK delay plan");
    return plan;
  }
  void arm(native_frame::Agent &agent) {
    native_frame::require(!armed && requested_cycles==37,"storage ACK gate already armed or invalid");
    agent.arm_response_hold(native_frame::ResponseChannel::write,first,end);
    start_cycles=agent.held_write_cycles();armed=true;
  }
  template<class Bus> void withhold(Bus &bus,native_frame::Agent &agent) {
    native_frame::require(armed && !finished,"storage ACK gate is not active");
    // MMIO observations and steps share the original capture's cycle deadline.
    // Keep the gate closed through the final status read, then release it.
    while(agent.held_write_cycles()-start_cycles<requested_cycles) {
      status_before_release=bus.read(GPU_REG_UCMD_STATUS);
      native_frame::require(!(status_before_release&GPU_UCMD_STATUS_COMPLETION),
        "storage atomic completed before its withheld ACK");
      bus.step();
    }
    status_before_release=bus.read(GPU_REG_UCMD_STATUS);
    native_frame::require(!(status_before_release&GPU_UCMD_STATUS_COMPLETION) && !agent.drained(),
      "storage atomic completion or drain preceded ACK release");
    held_cycles=agent.held_write_cycles()-start_cycles;
    agent.release_response_hold();
  }
  void finish(native_frame::Agent &agent) {
    native_frame::require(armed && !finished && held_cycles>=requested_cycles &&
      agent.held_write_cycles()-start_cycles==held_cycles && agent.drained(),
      "storage atomic ACK was not held, released and drained");
    agent.disarm_response_hold();finished=true;
  }
  void json(std::ostream &out,uint32_t command_id) const {
    native_frame::require(finished,"unverified storage ACK delay report");
    out<<"{\"schema\":\"opengpu-compute-storage-ack-delay-v1\",\"commandId\":"<<command_id
      <<",\"targetLineStart\":"<<first<<",\"targetLineEnd\":"<<end
      <<",\"requestedHoldCycles\":"<<requested_cycles<<",\"heldWriteCycles\":"<<held_cycles
      <<",\"statusBeforeRelease\":"<<status_before_release<<",\"externalDrained\":true}\n";
  }
};
} // namespace compute_owner
