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
static bool same_memory(const Memory &a, const Memory &b) {
  if (a.pages != b.pages || a.ranges.size() != b.ranges.size()) return false;
  for (size_t i = 0; i < a.ranges.size(); ++i)
    if (a.ranges[i].start != b.ranges[i].start || a.ranges[i].end != b.ranges[i].end ||
        a.ranges[i].fill != b.ranges[i].fill) return false;
  return true;
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
    {
      auto memory = backing(); memory.byte(0x1033, 0x77); const auto old = memory;
      Memory extra; extra.ranges.push_back({0x10000, 0x11000, 0x9b}); extra.byte(0x10001, 0x53);
      const auto upload = extra;
      memory.append_disjoint(extra);
      require(memory.ranges.size() == 2 && memory.pages.size() == 2 && same_memory(extra, upload),
        "disjoint append changed an original upload");
      for (uint64_t a = 0x1000; a < 0x4000; ++a)
        require(memory.byte(a) == old.byte(a), "disjoint append replaced prior owner bytes");
      for (uint64_t a = 0x10000; a < 0x11000; ++a)
        require(memory.byte(a) == upload.byte(a), "disjoint append changed new input bytes");
      rejects([&] { memory.append_disjoint(memory); });
      const auto before = memory;
      for (unsigned variant = 0; variant < 6; ++variant) {
        auto bad = extra;
        if (variant == 0) { bad.ranges[0] = {0x2000, 0x3000, 0}; bad.pages.clear(); }
        if (variant == 1) bad.ranges = {{0x30000, 0x31000, 0}, {0x20000, 0x21000, 0}};
        if (variant == 2) bad.ranges[0] = {0x30000, 0x30000, 0};
        if (variant == 3) bad.ranges[0] = {Memory::limit, Memory::limit + 4096, 0};
        if (variant == 4) { bad.ranges[0] = {0x30000, 0x31000, 0}; bad.pages.clear(); bad.byte(0x50000, 1); }
        if (variant == 5) { bad.ranges.clear(); bad.pages.clear(); }
        rejects([&] { memory.append_disjoint(bad); });
        require(same_memory(memory, before), "rejected memory append partially changed RAM");
      }
      ++cases;
    }
    {
      Memory old, next;
      old.ranges.push_back({0x1000, 0x1800, 0x25});
      next.ranges.push_back({0x1800, 0x2000, 0x61}); next.byte(0x1801, 0x72);
      const auto before = old;
      rejects([&] { old.append_disjoint(next); });
      require(same_memory(old, before), "new sparse page shadowed old fill bytes");
      next.pages.clear(); old.byte(0x1001, 0x91); const auto written = old;
      rejects([&] { old.append_disjoint(next); });
      require(same_memory(old, written), "old sparse page shadowed new fill bytes");
      ++cases;
    }
    {
      auto memory = backing(); memory.byte(0x1033, 0x77); const auto before = memory;
      Agent agent(memory); agent.arm_read_faults(0x1000, 0x1080);
      rejects([&] { agent.arm_read_faults(0x2000, 0x2040); });
      Requests r; r.arvalid = true; r.araddr = 0x1000; r.arlen = 1; r.arid = 7;
      tick(agent, 0, r);
      rejects([&] { agent.disarm_read_faults(); });
      const auto first = agent.drive(3), held = agent.drive(8);
      require(first.rvalid && !first.rlast && first.rresp == 2 && first.rid == 7 &&
        first.rdata == held.rdata && held.rresp == 2 && held.rid == 7,
        "injected fault response changed under R backpressure");
      r = {}; r.rready = true; tick(agent, 8, r);
      const auto last = tick(agent, 9, r);
      require(last.rvalid && last.rlast && last.rresp == 2 && last.rdata[19] == 0x77 &&
        agent.drained() && same_memory(memory, before), "injected read fault changed original RAM or lost its second beat");
      require(agent.traffic.read_faults == 1 && agent.injected_read_faults() == 1 &&
        !agent.traffic.write_faults, "injected fault accounting differs");
      agent.disarm_read_faults();
      r = {}; r.arvalid = true; r.araddr = 0x1000; r.arlen = 1; r.arid = 7; tick(agent, 10, r);
      r = {}; r.rready = true;
      require(tick(agent, 13, r).rresp == 0 && tick(agent, 14, r).rresp == 0 && agent.drained(),
        "same-agent healthy ID reuse retained the physical read fault");
      require(agent.traffic.read_faults == 1 && agent.injected_read_faults() == 1 &&
        agent.traffic.reads == 2 && agent.traffic.read_bus_bytes == 128 && same_memory(memory, before),
        "healthy reuse cleared old fault history or changed original bytes");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory);
      for (const auto &range : std::vector<std::pair<uint64_t, uint64_t>>{
          {0x1001, 0x1040}, {0x1000, 0x1041}, {0x1040, 0x1000}, {0x8000, 0x8040},
          {Memory::limit - 64, Memory::limit + 64}})
        rejects([&] { agent.arm_read_faults(range.first, range.second); });
      rejects([&] { agent.disarm_read_faults(); });
      agent.arm_read_faults(0x1000, 0x1040);
      Requests r; r.arvalid = true; r.araddr = 0x2000; tick(agent, 0, r);
      rejects([&] { agent.disarm_read_faults(); });
      r = {}; r.rready = true; require(tick(agent, 3, r).rresp == 0, "read outside fault interval was injected");
      tick(agent, 4, write_request(0x1000, 9));
      rejects([&] { agent.disarm_read_faults(); });
      r = {}; r.bready = true; require(tick(agent, 11, r).bresp == 0 && agent.drained(),
        "read fault interval incorrectly faulted a physical write");
      agent.disarm_read_faults();
      r = {}; r.arvalid = true; r.araddr = 0x8000; tick(agent, 12, r);
      r = {}; r.rready = true;
      require(tick(agent, 15, r).rresp == 2 && agent.traffic.read_faults == 1 &&
        agent.injected_read_faults() == 0 && !agent.traffic.write_faults,
        "unmapped physical fault was confused with an injected read error");
      ++cases;
    }
    {
      auto memory = backing(); memory.byte(0x1001, 0x36); memory.byte(0x1033, 0x77); Agent agent(memory);
      agent.arm_response_hold(ResponseChannel::read, 0x1000, 0x1080);
      Requests r; r.arvalid = true; r.araddr = 0x2000; r.arid = 1; tick(agent, 0, r);
      r.araddr = 0x1000; r.arid = 7; r.arlen = 1; tick(agent, 1, r);
      r = {}; r.rready = true;
      require(tick(agent, 3, r).rid == 1, "read hold delayed an earlier unselected response");
      for (unsigned cycle = 4; cycle < 36; ++cycle)
        require(!tick(agent, cycle, r).rvalid && !agent.drained(), "selected read escaped its hold");
      require(agent.held_read_cycles() == 32 && !agent.held_write_cycles() && agent.traffic.read_beats == 1,
        "held read was consumed or its ready cycles were lost");
      memory.byte(0x1001, 0x88); memory.byte(0x1033, 0x99);
      agent.release_response_hold();
      const auto held = agent.drive(36);
      require(held.rvalid && held.rid == 7 && !held.rlast && held.rdata[1] == 0x36,
        "released read lost its owner or original snapshot");
      require(agent.drive(37).rdata == held.rdata, "released VALID changed under backpressure");
      rejects([&] { agent.release_response_hold(); });
      rejects([&] { agent.disarm_response_hold(); });
      rejects([&] { agent.arm_response_hold(ResponseChannel::write, 0x2000, 0x2040); });
      tick(agent, 38, r); const auto last = tick(agent, 39, r);
      require(last.rlast && last.rid == 7 && last.rdata[19] == 0x77 && agent.drained() &&
        agent.traffic.read_bus_bytes == 96 && !agent.traffic.read_faults, "released burst did not drain exactly");
      agent.disarm_response_hold();
      agent.arm_response_hold(ResponseChannel::read, 0x2000, 0x2040);
      r = {}; r.arvalid = true; r.araddr = 0x2000; tick(agent, 40, r);
      rejects([&] { agent.release_response_hold(); });
      tick(agent, 42); // Before the accepted request's response is due.
      rejects([&] { agent.release_response_hold(); });
      tick(agent, 43); // The master may wait for VALID before asserting READY.
      agent.release_response_hold();
      require(agent.drive(44).rvalid, "release incorrectly waited for master READY");
      r = {}; r.rready = true; tick(agent, 45, r);
      require(agent.drained() && agent.held_read_cycles() == 33, "rearmed hold cleared or reused old ready cycles");
      agent.disarm_response_hold(); ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory);
      agent.arm_response_hold(ResponseChannel::write, 0x1000, 0x1080);
      tick(agent, 0, write_request(0x2000, 1, 0x33));
      auto w = write_request(0x1000, 9, 0x55); w.awvalid = false; tick(agent, 1, w);
      w.awvalid = true; w.wvalid = false; tick(agent, 2, w);
      Requests r; r.bready = true;
      require(tick(agent, 7, r).bid == 1 && !tick(agent, 8, r).bvalid,
        "write hold delayed an earlier unselected ACK or shortened latency");
      for (unsigned cycle = 9; cycle < 41; ++cycle) {
        r = {}; r.bready = r.rready = true;
        if (cycle == 10) { r.arvalid = true; r.araddr = 0x3000; r.arid = 12; }
        const auto response = tick(agent, cycle, r);
        require(!response.bvalid && !agent.drained(), "held write ACK escaped or falsely drained");
        if (cycle == 13) require(response.rvalid && response.rid == 12 && response.rdata[0] == 0xa7,
          "B hold blocked the independent read channel");
      }
      require(agent.held_write_cycles() == 32 && !agent.held_read_cycles() &&
        memory.byte(0x1000) == 0x55 && memory.byte(0x1020) == 0xa7 && agent.traffic.acknowledgements == 1,
        "B hold did not retain committed W-before-AW data and its pending ACK");
      agent.release_response_hold();
      require(agent.drive(41).bvalid && agent.drive(41).bid == 9 && agent.drive(41).bresp == 0,
        "released B response lost its owner/status");
      tick(agent, 42, write_request(0x1040, 10, 0x77));
      require(agent.drive(43).bvalid && agent.drive(43).bid == 9, "new ACK replaced a stalled released ACK");
      rejects([&] { agent.release_response_hold(); }); rejects([&] { agent.disarm_response_hold(); });
      r = {}; r.bready = true; tick(agent, 44, r);
      const auto last = tick(agent, 49, r);
      require(last.bvalid && last.bid == 10 && agent.drained() && agent.traffic.acknowledgements == 3 &&
        agent.traffic.writes == 3 && !agent.traffic.write_faults && agent.held_write_cycles() == 32,
        "released write plan lost or repeatedly held a later ACK");
      agent.disarm_response_hold(); ++cases;
    }
    {
      auto memory = backing(); const auto before = memory; Agent agent(memory);
      agent.arm_read_faults(0x1000, 0x1040); agent.arm_response_hold(ResponseChannel::read, 0x1000, 0x1040);
      Requests r; r.arvalid = true; r.araddr = 0x1000; r.arlen = 1; r.arid = 15; tick(agent, 0, r);
      tick(agent, 3); require(agent.held_read_cycles() == 1, "hold detection incorrectly waited for READY");
      rejects([&] { agent.disarm_read_faults(); }); rejects([&] { agent.disarm_response_hold(); });
      r = {}; r.rready = true;
      for (unsigned cycle = 4; cycle < 8; ++cycle) require(!tick(agent, cycle, r).rvalid, "held error leaked VALID");
      agent.release_response_hold(); const auto first = tick(agent, 8, r), last = tick(agent, 9, r);
      require(first.rresp == 2 && !first.rlast && last.rresp == 2 && last.rlast && last.rid == 15 &&
        agent.drained() && same_memory(memory, before), "delayed error snapshot changed or lost a beat");
      agent.disarm_response_hold(); agent.disarm_read_faults();
      r = {}; r.arvalid = true; r.araddr = 0x1000; r.arlen = 1; r.arid = 15; tick(agent, 10, r);
      r = {}; r.rready = true;
      require(tick(agent, 13, r).rresp == 0 && tick(agent, 14, r).rresp == 0 && agent.drained() &&
        agent.held_read_cycles() == 5 && agent.traffic.read_faults == 1 && agent.injected_read_faults() == 1 &&
        agent.traffic.read_bus_bytes == 128, "healthy ID reuse changed held/error history");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory);
      rejects([&] { agent.release_response_hold(); }); rejects([&] { agent.disarm_response_hold(); });
      rejects([&] { agent.arm_response_hold(static_cast<ResponseChannel>(99), 0x1000, 0x1040); });
      for (auto channel : {ResponseChannel::read, ResponseChannel::write})
        for (const auto &range : std::vector<std::pair<uint64_t, uint64_t>>{
            {0x1001, 0x1040}, {0x1000, 0x1041}, {0x1080, 0x1000}, {0x1000, 0x1000},
            {0x8000, 0x8040}, {Memory::limit - 64, Memory::limit + 64}})
          rejects([&] { agent.arm_response_hold(channel, range.first, range.second); });
      agent.arm_response_hold(ResponseChannel::read, 0x1000, 0x1040);
      rejects([&] { agent.arm_response_hold(ResponseChannel::write, 0x2000, 0x2040); });
      agent.disarm_response_hold();
      auto w = write_request(0x1000, 5); w.wvalid = false; tick(agent, 0, w);
      rejects([&] { agent.arm_response_hold(ResponseChannel::read, 0x1000, 0x1040); });
      w.awvalid = false; w.wvalid = true; tick(agent, 1, w);
      Requests r; r.bready = true; tick(agent, 8, r);
      agent.arm_response_hold(ResponseChannel::write, 0x1000, 0x1040);
      r = {}; r.arvalid = true; r.araddr = 0x2000; tick(agent, 9, r);
      rejects([&] { agent.disarm_response_hold(); });
      const auto held = agent.drive(12);
      rejects([&] { agent.arm_response_hold(ResponseChannel::read, 0x2000, 0x2040); });
      require(held.rvalid && agent.drive(13).rvalid && held.rdata == agent.drive(13).rdata,
        "busy plan mutation withdrew an already exposed VALID");
      r = {}; r.rready = true; tick(agent, 13, r); agent.disarm_response_hold();
      require(agent.drained() && !agent.held_read_cycles() && !agent.held_write_cycles(), "unused plan invented a hold");
      ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory); agent.arm_response_hold(ResponseChannel::read, 0x1000, 0x1040);
      Requests r; r.arvalid = r.rready = true; r.araddr = 0x1000;
      for (unsigned cycle = 0; cycle < 16; ++cycle) { r.arid = cycle; tick(agent, cycle, r); }
      require(!agent.drive(16).arready && !agent.drive(16).rvalid && !agent.drained(), "held read queue lost its credits");
      r = {}; r.rready = true; tick(agent, 16, r); agent.release_response_hold();
      for (unsigned cycle = 17; cycle < 33; ++cycle) {
        const auto response = tick(agent, cycle, r);
        require(response.rvalid && response.rid == cycle - 17 && response.rlast,
          "released full read queue lost FIFO/ID order");
      }
      require(agent.drained() && agent.traffic.reads == 16 && agent.traffic.read_beats == 16 &&
        agent.held_read_cycles() == 14, "released read credits or hold history differ");
      agent.disarm_response_hold(); ++cases;
    }
    {
      auto memory = backing(); Agent agent(memory); agent.arm_response_hold(ResponseChannel::write, 0x1000, 0x1040);
      tick(agent, 0, write_request(0x8000, 11)); Requests r; r.bready = true;
      const auto error = tick(agent, 7, r);
      require(error.bvalid && error.bid == 11 && error.bresp == 2 && agent.drained() &&
        !agent.held_write_cycles() && agent.traffic.write_faults == 1, "hold changed an unrelated fault ACK");
      agent.disarm_response_hold(); tick(agent, 8, write_request(0x1000, 11));
      require(tick(agent, 15, r).bresp == 0 && agent.drained() && agent.traffic.write_faults == 1,
        "unused hold changed later healthy ID reuse");
      ++cases;
    }
    for (auto channel : {ResponseChannel::read, ResponseChannel::write}) {
      auto memory = backing(); memory.ranges.push_back({0xfffff000, Memory::limit, 0x83}); Agent agent(memory);
      agent.arm_response_hold(channel, Memory::limit - 64, Memory::limit); Requests r;
      if (channel == ResponseChannel::read) {
        r.arvalid = true; r.araddr = Memory::limit - 32; r.arid = 17; tick(agent, 0, r);
        r = {}; r.rready = true; require(!tick(agent, 3, r).rvalid, "top RV32 read escaped hold");
        agent.release_response_hold(); const auto response = tick(agent, 4, r);
        require(response.rvalid && response.rid == 17 && response.rdata[31] == 0x83 &&
          agent.held_read_cycles() == 1, "top RV32 read address/snapshot truncated");
      } else {
        tick(agent, 0, write_request(Memory::limit - 32, 18, 0x5a)); r.bready = true;
        require(!tick(agent, 7, r).bvalid, "top RV32 write escaped hold"); agent.release_response_hold();
        const auto response = tick(agent, 8, r);
        require(response.bvalid && response.bid == 18 && !response.bresp && memory.byte(Memory::limit - 1) == 0x5a &&
          memory.byte(Memory::limit - 33) == 0x83 && agent.held_write_cycles() == 1, "top RV32 write address/strobes truncated");
      }
      require(agent.drained() && !agent.traffic.read_faults && !agent.traffic.write_faults, "top RV32 response did not drain");
      agent.disarm_response_hold(); ++cases;
    }
    {
      auto memory=backing();Agent agent(memory);
      for(const auto &range:std::vector<std::pair<uint64_t,uint64_t>>{
          {0x1001,0x1040},{0x1000,0x1000},{0x4000,0x4040}})
        rejects([&]{agent.arm_first_write_fault(range.first,range.second);});
      agent.arm_first_write_fault(0x1000,0x1080);
      rejects([&]{agent.arm_first_write_fault(0x1000,0x1040);});
      rejects([&]{agent.disarm_first_write_fault();});
      tick(agent,0,write_request(0x2000,1,0x33));
      auto w=write_request(0x1000,9,0x55);w.awvalid=false;tick(agent,1,w);
      w.awvalid=true;w.wvalid=false;tick(agent,2,w);
      tick(agent,3,write_request(0x1040,10,0x77));
      Requests r;r.bready=r.rready=true;
      require(tick(agent,8,r).bvalid && agent.traffic.acknowledgements==1,
        "first-write fault affected an earlier unrelated write");
      for(unsigned cycle=9;cycle<39;cycle++) {
        r.arvalid=cycle==10;r.araddr=0x1000;
        const auto out=tick(agent,cycle,r);
        require(!out.bvalid,"injected B error escaped its actual delayed response");
        if(cycle==13)require(out.rvalid && !out.rresp && out.rdata[0]==0xa7,
          "write error changed original backing or independent read service");
      }
      r={};
      const auto first=tick(agent,39,r),stalled=tick(agent,40,r);
      require(first.bvalid && first.bid==9 && first.bresp==2 &&
        stalled.bvalid && stalled.bid==first.bid && stalled.bresp==first.bresp,
        "stalled B error lost its actual accepted owner/status");
      require(memory.byte(0x1000)==0xa7 && memory.byte(0x1040)==0x77 &&
        agent.traffic.write_faults==1 && agent.injected_write_faults()==1 && agent.first_write_attempts()==2,
        "one-shot write fault changed backing or poisoned the following write");
      rejects([&]{agent.disarm_first_write_fault();});
      r.bready=true;tick(agent,42,r);
      require(tick(agent,43,r).bid==10 && agent.drained(),"healthy ACK did not follow the fault drain");
      agent.disarm_first_write_fault();
      agent.arm_first_write_fault(0x1000,0x1040);tick(agent,44,write_request(0x1000,11));
      require(tick(agent,81,r).bresp==2 && agent.drained() && agent.injected_write_faults()==2 &&
        agent.traffic.write_faults==2 && agent.first_write_attempts()==1 &&
        !agent.traffic.read_faults,"write fault history was cleared or duplicated");
      agent.disarm_first_write_fault();++cases;
    }
    {
      auto memory=backing();Agent agent(memory);
      agent.arm_first_write_fault(0x1000,0x1040);
      agent.arm_response_hold(ResponseChannel::write,0x1000,0x1040);
      tick(agent,0,write_request(0x1000,7));
      Requests r;r.bready=true;
      for(unsigned cycle=1;cycle<74;cycle++)require(!tick(agent,cycle,r).bvalid,
        "held injected write error escaped its B gate");
      require(agent.held_write_cycles()==37 && !agent.drained() && memory.byte(0x1000)==0xa7,
        "first-write error did not retain its backed data and due-response hold");
      rejects([&]{agent.disarm_first_write_fault();});
      agent.release_response_hold();const auto out=tick(agent,74,r);
      require(out.bvalid && out.bid==7 && out.bresp==2 && agent.drained(),
        "released B error changed identity or failed to drain");
      agent.disarm_response_hold();agent.disarm_first_write_fault();++cases;
    }
    {
      auto memory=backing();Agent agent(memory);
      agent.arm_accepted_write_error(0x1000,0x1040);
      rejects([&]{agent.arm_first_write_fault(0x1000,0x1040);});
      auto w=write_request(0x1000,13,0x55);w.wstrb=0x80000005u;
      w.awvalid=false;tick(agent,0,w);
      require(memory.byte(0x1000)==0xa7 && !agent.accepted_write_faults(),
        "accepted error committed before its actual AW");
      w.awvalid=true;w.wvalid=false;tick(agent,1,w);
      require(memory.byte(0x1000)==0x55 && memory.byte(0x1001)==0xa7 &&
        memory.byte(0x1002)==0x55 && memory.byte(0x101f)==0x55 && memory.byte(0x1020)==0xa7 &&
        agent.accepted_write_faults()==1 && agent.injected_write_faults()==1,
        "accepted error did not commit exactly the original enabled W bytes");
      rejects([&]{agent.disarm_first_write_fault();});
      Requests r;r.rready=true;r.arvalid=true;r.araddr=0x1000;tick(agent,2,r);
      auto healthy=write_request(0x1000,14,0x77);healthy.wstrb=0x10;tick(agent,3,healthy);
      r.arvalid=false;tick(agent,4,r);const auto read=tick(agent,5,r);
      require(read.rvalid && !read.rresp && read.rdata[0]==0x55 && read.rdata[1]==0xa7 &&
        read.rdata[2]==0x55 && read.rdata[4]==0xa7 && read.rdata[31]==0x55 && !read.bvalid,
        "read did not snapshot the committed failed write before its delayed B response");
      for(unsigned cycle=6;cycle<38;++cycle)require(!tick(agent,cycle,r).bvalid,
        "accepted error escaped its actual delayed B response");
      const auto first=tick(agent,38,r),stalled=tick(agent,39,r);
      require(first.bvalid && first.bid==13 && first.bresp==2 && stalled.bvalid &&
        stalled.bid==13 && stalled.bresp==2 && memory.byte(0x1004)==0x77 &&
        agent.first_write_attempts()==2 && agent.traffic.write_faults==1 && agent.accepted_write_faults()==1,
        "stalled accepted error lost commit, identity or one-shot accounting");
      r.bready=true;tick(agent,40,r);
      require(tick(agent,41,r).bid==14 && agent.drained(),"healthy write did not drain after accepted error");
      agent.disarm_first_write_fault();agent.arm_first_write_fault(0x1000,0x1040);
      tick(agent,42,write_request(0x1000,15,0x99));
      require(tick(agent,79,r).bresp==2 && agent.drained() && memory.byte(0x1000)==0x55 &&
        agent.accepted_write_faults()==1 && agent.injected_write_faults()==2 && agent.traffic.write_faults==2,
        "rearmed suppressed error retained the commit policy or cleared accepted history");
      agent.disarm_first_write_fault();++cases;
    }
    std::cout << "PASS native frame AXI cases=" << cases << "\n";
    return 0;
  } catch (const std::exception &e) {
    std::cerr << "native frame AXI test failed: " << e.what() << "\n";
    return 1;
  }
}
