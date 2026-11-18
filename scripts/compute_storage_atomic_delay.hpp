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
  bool global_mode=false,old_values_held=false;
  uint64_t old_first=0,old_end=0;
  uint32_t old_guard=0;
  static StorageAckDelay read(std::istream &in) {
    StorageAckDelay plan;
    std::string tag(8,'\0');in.read(tag.data(),8);
    native_frame::require(in && (tag=="OGCSACK1" || tag=="OGCGACK1"),"invalid atomic ACK source kind");
    plan.global_mode=tag=="OGCGACK1";
    plan.first=native_frame::read_le(in,8);plan.end=native_frame::read_le(in,8);
    plan.requested_cycles=native_frame::read_le(in,4);
    if(plan.global_mode) {
      plan.old_first=native_frame::read_le(in,8);plan.old_end=native_frame::read_le(in,8);
      plan.old_guard=native_frame::read_le(in,4);
    }
    native_frame::require(in.peek()==EOF && plan.first && plan.first<native_frame::Memory::limit &&
      !(plan.first&63) && plan.end==plan.first+64 && plan.requested_cycles==(plan.global_mode?257u:37u),
      "invalid original storage ACK delay plan");
    if(plan.global_mode)native_frame::require(plan.old_first && !(plan.old_first&3) &&
      plan.old_end==plan.old_first+32 && plan.old_end<=native_frame::Memory::limit &&
      (plan.old_end<=plan.first || plan.end<=plan.old_first) && plan.old_guard==0xdeadbeefu,
      "invalid original global old-value observation window");
    return plan;
  }
  void check_old_values(const native_frame::Memory *memory) const {
    if(!global_mode)return;
    native_frame::require(memory,"global old-value memory observation missing");
    for(uint64_t address=old_first;address<old_end;++address)
      native_frame::require(memory->byte(address)==uint8_t(old_guard>>(8*((address-old_first)&3))),
        "global AMO returned old values before target ACK release");
  }
  void arm(native_frame::Agent &agent,const native_frame::Memory *memory=nullptr) {
    native_frame::require(!armed && requested_cycles==(global_mode?257u:37u),"storage ACK gate already armed or invalid");
    check_old_values(memory);
    agent.arm_response_hold(native_frame::ResponseChannel::write,first,end);
    start_cycles=agent.held_write_cycles();armed=true;
  }
  template<class Bus> void withhold(Bus &bus,native_frame::Agent &agent,const native_frame::Memory *memory=nullptr) {
    native_frame::require(armed && !finished,"storage ACK gate is not active");
    check_old_values(memory);
    // MMIO observations and steps share the original capture's cycle deadline.
    // Keep the gate closed through the final status read, then release it.
    while(agent.held_write_cycles()-start_cycles<requested_cycles) {
      status_before_release=bus.read(GPU_REG_UCMD_STATUS);
      check_old_values(memory);
      native_frame::require(!(status_before_release&GPU_UCMD_STATUS_COMPLETION),
        "storage atomic completed before its withheld ACK");
      bus.step();
      check_old_values(memory);
    }
    status_before_release=bus.read(GPU_REG_UCMD_STATUS);
    check_old_values(memory);old_values_held=global_mode;
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
    native_frame::require(!global_mode || old_values_held,"global old-value guard was not observed");
    out<<"{\"schema\":\""<<(global_mode?"opengpu-compute-global-ack-delay-v1":"opengpu-compute-storage-ack-delay-v1")<<"\",\"commandId\":"<<command_id
      <<",\"targetLineStart\":"<<first<<",\"targetLineEnd\":"<<end
      <<",\"requestedHoldCycles\":"<<requested_cycles<<",\"heldWriteCycles\":"<<held_cycles
      <<",\"statusBeforeRelease\":"<<status_before_release<<",\"externalDrained\":true";
    if(global_mode)out<<",\"oldOutputStart\":"<<old_first<<",\"oldOutputEnd\":"<<old_end
      <<",\"oldOutputGuard\":"<<old_guard<<",\"oldValuesHeldUntilAck\":true";
    out<<"}\n";
  }
};
} // namespace compute_owner
