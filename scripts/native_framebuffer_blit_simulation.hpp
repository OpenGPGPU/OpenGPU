#pragma once
// Include after the explicit owner's native_owner_runtime.hpp. Selecting the
// local checkout's simulation header would silently mix owner source roots.
#include <deque>

namespace framebuffer_blit {
// The original model and memory agent remain in use. Only external AXI
// response timing/status changes: accepted transactions still drain through
// the real agent, including responses held across a unified reset request.
class Simulation : public native_frame::Simulation {
  struct Read {uint64_t address;uint32_t id,beats,beat;};
  std::deque<Read> reads;
public:
  using native_frame::Simulation::Simulation;
  bool hold_reads=false,hold_writes=false;
  uint64_t fault_begin=0,fault_end=0;
  uint64_t fault_reads=0,held_read_cycles=0,held_write_cycles=0;

  template<class Observer>
  void step(native_frame::Agent &agent,uint64_t cycle,Observer observer) {
    native_frame::require(model->io_s_axi_aclk==0,"blit AXI step requires a low clock");
    auto response=agent.drive(cycle);
    if(response.rvalid && hold_reads){response.rvalid=false;++held_read_cycles;}
    if(response.bvalid && hold_writes){response.bvalid=false;++held_write_cycles;}
    bool fault=false;
    if(response.rvalid) {
      native_frame::require(!reads.empty() && reads.front().id==response.rid,
        "blit response lost original accepted read identity");
      const auto &r=reads.front();
      fault=r.address<fault_end && r.address+uint64_t(r.beats)*32>fault_begin;
      if(fault)response.rresp=2; // Actual SLVERR on the original source read.
    }
    drive(response);const auto request=requests();observer(*model);
    if(request.arvalid && response.arready)
      reads.push_back({request.araddr,request.arid,request.arlen+1,0});
    if(response.rvalid && request.rready) {
      auto &r=reads.front();if(fault && !r.beat)++fault_reads;
      if(++r.beat==r.beats)reads.pop_front();
    }
    agent.observe(cycle,request,response);tick();
  }
  void step(native_frame::Agent &agent,uint64_t cycle) {step(agent,cycle,[](const auto &){});}
  bool drained(const native_frame::Agent &agent) const {return reads.empty() && agent.drained();}
};
} // namespace framebuffer_blit
