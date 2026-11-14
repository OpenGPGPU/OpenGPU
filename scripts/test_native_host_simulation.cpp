#include "native_host_simulation.hpp"
#include <iostream>

using namespace native_frame;

int main(int argc, char **argv) {
  try {
    Simulation sim(argc, argv);
    Memory memory;
    memory.ranges.push_back({0x1000, 0x1040, 0});
    memory.byte(0x1000, 0x5a);
    Agent agent(memory);
    sim.step(agent, 0);
    require(sim.clock_cycles == 1, "driver frame clock did not count the rising edge");
    const auto &events = sim.model->events;
    require(events.size() == 3, "AXI step did not evaluate exactly three phases");
    require(!events[0].clock && events[1].clock && !events[2].clock, "wrong clock order");
    require(events[0].time == 0 && events[1].time == 5 && events[2].time == 10, "wrong phase times");
    require(events[0].read_ready && events[0].read_valid, "read handshake was not settled before the edge");
    require(agent.traffic.reads == 1, "read handshake was not observed exactly once");
    const auto reply = agent.drive(3);
    require(reply.rvalid && reply.rid == 7 && reply.rdata[0] == 0x5a, "read snapshot changed");

    sim.model->events.clear();
    sim.model->io_s_axi_aresetn = 0;
    sim.tick();
    require(sim.clock_cycles == 2, "driver frame clock omitted a control/reset edge");
    require(sim.model->events.size() == 3 && !sim.model->events[0].reset,
            "control tick did not settle new inputs before the edge");
    require(!sim.model->events[0].clock && sim.model->events[1].clock && !sim.model->events[2].clock,
            "control tick changed clock order");

    sim.model->io_s_axi_aclk = 1;
    const auto previous_events = sim.model->events.size();
    bool rejected = false;
    try { sim.step(agent, 4); } catch (const std::runtime_error &) { rejected = true; }
    require(rejected && sim.clock_cycles == 2 && sim.model->events.size() == previous_events && agent.traffic.reads == 1,
            "high-clock step was not rejected before changing traffic");
    std::cout << "native host AXI cycle phases: PASS\n";
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
