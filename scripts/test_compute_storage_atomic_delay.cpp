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
std::string encoded_global(uint64_t old_first=0x1080,uint64_t old_end=0x10a0,
    uint32_t guard=0xdeadbeef,uint32_t cycles=257) {
  std::ostringstream out;out<<"OGCGACK1";
  write_le(out,0x1000,8);write_le(out,0x1040,8);write_le(out,cycles,4);
  write_le(out,old_first,8);write_le(out,old_end,8);write_le(out,guard,4);return out.str();
}
StorageAckDelay decode(const std::string &bytes) {
  std::istringstream in(bytes);return StorageAckDelay::read(in);
}
struct Bus {
  Agent &agent;
  uint64_t cycle=1;
  bool premature=false,bready=false;
  Memory *corrupt_memory=nullptr;
  void step() {
    require(cycle<1024,"bounded capture deadline");
    if(corrupt_memory && cycle==20)corrupt_memory->byte(0x1080,0x55);
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
Memory global_backing() {
  auto memory=backing();
  for(uint64_t p=0x1080;p<0x10a0;++p)memory.byte(p,uint8_t(0xdeadbeefu>>(8*((p-0x1080)&3))));
  return memory;
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
    for(const auto &bytes:{encoded_global(0x1081,0x10a1),encoded_global(0x1080,0x10a4),
        encoded_global(0x1000,0x1020),encoded_global(0x1080,0x10a0,0xa7a7a7a7),
        encoded_global(0x1080,0x10a0,0xdeadbeef,37),encoded_global()+"x",encoded_global().substr(0,47)})
      rejects([&]{decode(bytes);});
    {
      auto memory=global_backing();Agent agent(memory);auto plan=decode(encoded_global());
      rejects([&]{plan.arm(agent);});plan.arm(agent,&memory);write(agent,0x1000);Bus bus{agent};
      rejects([&]{plan.withhold(bus,agent);});plan.withhold(bus,agent,&memory);
      require(plan.global_mode && plan.held_cycles>=257 && plan.old_values_held && !agent.drained(),
        "global ACK did not preserve pending original old values");
      for(uint64_t p=0x1080;p<0x10a0;++p)require(memory.byte(p)==uint8_t(0xdeadbeefu>>(8*((p-0x1080)&3))),
        "global output changed before ACK release");
      bus.bready=true;bus.step();plan.finish(agent);plan.json(std::cout,43);
    }
    {
      auto memory=global_backing();memory.byte(0x1080,0);Agent agent(memory);auto plan=decode(encoded_global());
      rejects([&]{plan.arm(agent,&memory);});
    }
    {
      auto memory=global_backing();Agent agent(memory);auto plan=decode(encoded_global());plan.arm(agent,&memory);
      write(agent,0x1000);Bus bus{agent};bus.corrupt_memory=&memory;
      rejects([&]{plan.withhold(bus,agent,&memory);});
      require(!agent.drive(bus.cycle).bvalid && !agent.drained(),"early old-word store released the global ACK");
      rejects([&]{plan.finish(agent);});
    }
    std::cout<<"PASS storage ACK guard negatives and real AXI gate\n";return 0;
  }catch(const std::exception &e) {std::cerr<<e.what()<<'\n';return 1;}
}
