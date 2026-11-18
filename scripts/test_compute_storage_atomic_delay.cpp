#include "compute_storage_atomic_delay.hpp"
#include <iostream>
#include <sstream>
using namespace native_frame;
using compute_owner::StorageAckDelay;

template<class F> void rejects(F f) {
  bool rejected=false;
  try { f(); } catch(const std::runtime_error &) { rejected=true; }
  require(rejected,"invalid delayed ACK sequence was accepted");
}
std::string encoded(uint64_t first=0x1000,uint64_t end=0x1040,uint32_t cycles=37) {
  std::ostringstream out;out<<"OGCSACK1";
  write_le(out,first,8);write_le(out,end,8);write_le(out,cycles,4);return out.str();
}
StorageAckDelay decode(const std::string &bytes) {
  std::istringstream in(bytes);return StorageAckDelay::read(in);
}
struct Bus {
  Agent &agent;
  uint64_t cycle=1;
  bool premature=false,bready=false;
  void step() {
    require(cycle<256,"bounded capture deadline");
    Requests requests;requests.bready=bready;requests.rready=true;
    agent.observe(cycle,requests,agent.drive(cycle));++cycle;
  }
  uint32_t read(uint32_t address) {
    require(address==GPU_REG_UCMD_STATUS,"unexpected ACK guard observation");step();
    return premature && agent.held_write_cycles()>=20?GPU_UCMD_STATUS_COMPLETION:0;
  }
};
Memory backing() {
  Memory result;result.ranges.push_back({0x1000,0x4000,0xa7});return result;
}
void write(Agent &agent,uint64_t address) {
  Requests w;w.awvalid=w.wvalid=w.wlast=true;w.awaddr=address;w.awid=9;
  w.wstrb=0xffffffff;w.wdata.fill(0x55);agent.observe(0,w,agent.drive(0));
}
int main() {
  try {
    for(const auto &bytes:{encoded(0x1001,0x1041),encoded(0x1000,0x1080),
        encoded(0x1000,0x1040,0),encoded(0x1000,0x1040,36),
        encoded(0x100000000ull,0x100000040ull),encoded()+"x",
        encoded().substr(0,27),std::string("BADSMAG1")+encoded().substr(8)})
      rejects([&]{decode(bytes);});
    {
      auto memory=backing();Agent agent(memory);auto plan=decode(encoded(0x4000,0x4040));
      rejects([&]{plan.arm(agent);});
    }
    {
      auto memory=backing();Agent agent(memory);auto plan=decode(encoded());plan.arm(agent);
      rejects([&]{plan.arm(agent);});write(agent,0x1000);Bus bus{agent};
      rejects([&]{plan.finish(agent);});plan.withhold(bus,agent);
      require(plan.held_cycles>=37 && !agent.drained() && memory.byte(0x1000)==0x55 &&
        memory.byte(0x1020)==0xa7 && !agent.traffic.acknowledgements,
        "delayed B did not retain actual committed bytes and pending ACK");
      const auto due=agent.drive(bus.cycle);
      require(due.bvalid && due.bid==9 && due.bresp==0,"release lost original B identity");
      for(unsigned i=0;i<5;i++) {
        bus.step();const auto stalled=agent.drive(bus.cycle);
        require(stalled.bvalid && stalled.bid==due.bid && stalled.bresp==due.bresp,
          "released B changed while its consumer stalled");
      }
      rejects([&]{plan.finish(agent);});bus.bready=true;bus.step();plan.finish(agent);
      require(agent.drained() && agent.traffic.acknowledgements==1 &&
        !agent.traffic.read_faults && !agent.traffic.write_faults,"ACK did not drain exactly once");
      rejects([&]{plan.finish(agent);});plan.json(std::cout,42);
    }
    {
      auto memory=backing();Agent agent(memory);auto plan=decode(encoded());plan.arm(agent);
      write(agent,0x1000);Bus bus{agent};bus.premature=true;
      rejects([&]{plan.withhold(bus,agent);});
      for(unsigned i=0;i<20;i++)bus.step();
      require(!agent.drive(bus.cycle).bvalid && !agent.drained(),
        "premature completion incorrectly authorized ACK release");
      rejects([&]{plan.finish(agent);});
    }
    {
      auto memory=backing();Agent agent(memory);auto plan=decode(encoded());plan.arm(agent);
      write(agent,0x2000);Bus bus{agent};bus.bready=true;
      rejects([&]{plan.withhold(bus,agent);});
      require(!agent.held_write_cycles() && agent.drained(),
        "unrelated write satisfied the original atomic ACK plan");
    }
    std::cout<<"PASS storage ACK guard negatives and real AXI gate\n";return 0;
  }catch(const std::exception &e) {std::cerr<<e.what()<<'\n';return 1;}
}
