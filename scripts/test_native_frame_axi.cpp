#include "native_frame_axi.hpp"
#include <filesystem>
#include <iostream>

using namespace native_frame;

static Memory backing() {
  Memory memory;
  memory.ranges.push_back({0x1000, 0x4000, 0xa7});
  return memory;
}
static Responses tick(Agent &agent, uint64_t cycle, const Requests &requests = {}) {
  const auto response = agent.drive(cycle);
  agent.observe(cycle, requests, response);
  return response;
}
static Requests write_request(uint64_t address, uint32_t id, uint8_t value = 0x55) {
  Requests request;
  request.awvalid = request.wvalid = request.wlast = true;
  request.awaddr = address; request.awid = id;
  request.wstrb = 0xffffffff; request.wdata.fill(value);
  return request;
}
template <typename F> static void rejects(F f) {
  bool failed = false;
  try { f(); } catch (const std::runtime_error &) { failed = true; }
  require(failed, "invalid fixture/protocol was accepted");
}

int main(int argc, char **argv) {
  try {
    const auto directory = argc == 2 ? std::filesystem::path(argv[1]) :
      std::filesystem::path(".artifacts/native-frame-axi-unit");
    std::filesystem::create_directories(directory);
    unsigned cases = 0;
    {
      auto memory = backing(); memory.byte(0x1033, 0x77); Agent agent(memory);
      Requests r; r.arvalid = true; r.araddr = 0x1020; r.arid = 7;
      tick(agent, 0, r);
      require(!agent.drive(1).rvalid && !agent.drive(2).rvalid, "read delay shortened");
      const auto held = agent.drive(3);
      require(held.rvalid && held.rlast && held.rid == 7 && held.rdata[19] == 0x77, "upper half read shifted incorrectly");
      memory.byte(0x1033, 0x99);
      require(agent.drive(5).rdata == held.rdata, "blocked read was not snapshotted");
      r = {}; r.rready = true; tick(agent, 6, r);
      require(agent.drained() && agent.traffic.read_bus_bytes == 32, "one beat read did not drain");
      ++cases;
    }
    {
      auto memory = backing(); for (unsigned i = 0; i < 64; ++i) memory.byte(0x1000 + i, i);
      Agent agent(memory); Requests r; r.arvalid = true; r.araddr = 0x1000; r.arlen = 1;
      tick(agent, 0, r); r = {}; r.rready = true;
      const auto first = tick(agent, 3, r), last = tick(agent, 4, r);
      require(first.rvalid && !first.rlast && first.rdata[31] == 31, "first read beat differs");
      require(last.rvalid && last.rlast && last.rdata[0] == 32 && last.rdata[31] == 63, "last read beat differs");
      require(agent.drained() && agent.traffic.read_bus_bytes == 64, "burst read did not drain");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory); auto w = write_request(0x1020, 5);
      w.wvalid = false; tick(agent, 0, w);
      require(!agent.drive(1).awready && agent.drive(1).wready, "AW was not retained");
      w.awvalid = false; w.wvalid = true; w.wstrb = 0x80000001;
      for (unsigned i = 0; i < 32; ++i) w.wdata[i] = uint8_t(0xf0 + i);
      tick(agent, 1, w);
      require(memory.byte(0x1020) == 0xf0 && memory.byte(0x103f) == 0x0f && memory.byte(0x1021) == 0xa7,
              "upper-half masked write changed a neighbor");
      require(!agent.drive(7).bvalid, "write acknowledged before seven cycles");
      require(agent.drive(8).bvalid && agent.drive(8).bid == 5, "write acknowledgement missing");
      require(agent.drive(10).bid == 5, "blocked acknowledgement changed owner");
      Requests r; r.bready = true; tick(agent, 10, r);
      require(agent.drained() && agent.traffic.enabled_write_bytes == 2 && agent.traffic.acknowledgements == 1,
              "masked write counters differ");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory); auto w = write_request(0x1020, 9, 0x34);
      w.awvalid = false; tick(agent, 0, w);
      require(agent.drive(1).awready && !agent.drive(1).wready, "W-before-AW deadlocked the address channel");
      w.awvalid = true; w.wvalid = false; tick(agent, 1, w);
      require(memory.byte(0x1020) == 0x34 && memory.byte(0x1000) == 0xa7, "W-before-AW data lost");
      Requests r; r.bready = true; require(tick(agent, 8, r).bid == 9 && agent.drained(), "W-before-AW did not drain");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory);
      tick(agent, 0, write_request(0x1000, 1)); tick(agent, 1, write_request(0x1040, 2));
      require(agent.drive(7).bvalid && agent.drive(7).bid == 1 && agent.drive(8).bid == 1,
              "a later write reordered a blocked B response");
      Requests r; r.bready = true; tick(agent, 9, r);
      require(tick(agent, 10, r).bid == 2 && agent.drained() && agent.traffic.acknowledgements == 2,
              "overlapped writes did not drain");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory); auto both = write_request(0x1000, 3, 0x11);
      both.arvalid = true; both.araddr = 0x1000;
      tick(agent, 0, both);
      Requests r; r.rready = true; const auto data = tick(agent, 3, r);
      require(data.rdata[0] == 0xa7 && memory.byte(0x1000) == 0x11,
              "a concurrent write changed the accepted read snapshot");
      r = {}; r.bready = true; tick(agent, 7, r); require(agent.drained(), "concurrent read/write leaked traffic");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory); Requests r;
      r.arvalid = true; r.araddr = 0x1000;
      for (unsigned i = 0; i < 16; ++i) { r.arid = i; tick(agent, i, r); }
      require(!agent.drive(16).arready, "read queue exceeded sixteen credits");
      r = {}; r.rready = true; require(tick(agent, 16, r).rid == 0, "read FIFO changed order");
      require(agent.drive(17).arready, "read credit was not released");
      r.arvalid = true; r.araddr = 0x1000; r.arid = 16; tick(agent, 17, r);
      r.arvalid = false;
      for (unsigned cycle = 18; !agent.drained() && cycle < 40; ++cycle) tick(agent, cycle, r);
      require(agent.drained() && agent.traffic.reads == 17 && agent.traffic.read_beats == 17,
              "read credit refill lost a transaction");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory); auto request = write_request(0x8000, 13);
      request.arvalid = true; request.araddr = 0x8000; tick(agent, 0, request);
      Requests r; r.rready = true; require(tick(agent, 3, r).rresp == 2, "unmapped read did not fault");
      require(memory.byte(0x8000) == 0, "faulted write changed unmapped storage");
      memory.ranges.push_back({0x8000, 0x9000, 0});
      r = {}; r.bready = true;
      require(tick(agent, 7, r).bresp == 2 && agent.drained(), "write fault changed while B was pending");
      require(agent.traffic.read_faults == 1 && agent.traffic.write_faults == 1, "fault counters differ");
      ++cases;
    }
    {
      auto memory = backing();
      rejects([&] { Agent agent(memory); Requests r; r.arvalid = true; r.araddr = 0x1020; r.arlen = 1; tick(agent, 0, r); });
      rejects([&] { Agent agent(memory); auto r = write_request(0x1000, 1); r.awlen = 1; tick(agent, 0, r); });
      rejects([&] { Agent agent(memory); auto r = write_request(0x1000, 1); r.awsize = 6; tick(agent, 0, r); });
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory);
      auto w = write_request(0x103c, 21, 0);
      w.awsize = 2; w.wstrb = 0xf0000000;
      for (unsigned b = 28; b < 32; ++b) w.wdata[b] = uint8_t(0xc0 + b - 28);
      tick(agent, 0, w);
      require(memory.byte(0x103b) == 0xa7 && memory.byte(0x103c) == 0xc0 && memory.byte(0x103f) == 0xc3,
              "narrow word write used the address offset twice");
      Requests r; r.arvalid = true; r.araddr = 0x103c; r.arsize = 2;
      tick(agent, 1, r); r = {}; r.rready = true;
      const auto data = tick(agent, 4, r);
      require(data.rdata[28] == 0xc0 && data.rdata[31] == 0xc3, "narrow word read did not return its containing beat");
      r = {}; r.bready = true; tick(agent, 7, r);
      require(agent.drained() && agent.traffic.enabled_write_bytes == 4, "narrow word leaked traffic");
      ++cases;
    }
    {
      auto memory = backing(); memory.ranges.push_back({0xfffff000, Memory::limit, 0x83});
      memory.byte(0x1001, 7); memory.byte(0xffffffff, 0x5e);
      const auto path = (directory / "sparse.bin").string(); memory.write(path);
      const auto restored = Memory::read(path);
      require(restored.byte(0x1001) == 7 && restored.byte(0x1fff) == 0xa7 &&
              restored.byte(0xfffff010) == 0x83 && restored.byte(0xffffffff) == 0x5e,
              "sparse round trip changed bytes");
      require(std::filesystem::file_size(path) == 16 + 2 * 24 + 2 * 4104, "sparse fills expanded");
      { std::fstream corrupt(path, std::ios::binary | std::ios::in | std::ios::out); corrupt.seekp(36); corrupt.put(1); }
      rejects([&] { Memory::read(path); });
      ++cases;
    }
    std::cout << "PASS native frame AXI cases=" << cases << "\n";
    return 0;
  } catch (const std::exception &e) {
    std::cerr << "native frame AXI test failed: " << e.what() << "\n";
    return 1;
  }
}
