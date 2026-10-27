#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <fstream>
#include <map>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace native_frame {
inline void require(bool value, const char *message) {
  if (!value) throw std::runtime_error(message);
}
inline uint64_t read_le(std::istream &in, unsigned bytes) {
  uint64_t value = 0;
  for (unsigned i = 0; i < bytes; ++i) {
    const int b = in.get(); require(b != EOF, "truncated native fixture");
    value |= uint64_t(uint8_t(b)) << (8 * i);
  }
  return value;
}
inline void write_le(std::ostream &out, uint64_t value, unsigned bytes) {
  for (unsigned i = 0; i < bytes; ++i) out.put(char(value >> (8 * i)));
  require(bool(out), "cannot write native fixture");
}
inline void magic(std::istream &in, const char *expected) {
  for (unsigned i = 0; i < 8; ++i)
    require(in.get() == uint8_t(expected[i]), "unknown native fixture format");
}

class Memory {
public:
  static constexpr uint64_t limit = uint64_t(1) << 32;
  static constexpr unsigned page_bytes = 4096;
  struct Range { uint64_t start, end; uint8_t fill; };
  std::vector<Range> ranges;
  std::map<uint64_t, std::array<uint8_t, page_bytes>> pages;

  uint8_t default_byte(uint64_t address) const {
    for (const auto &r : ranges)
      if (r.start <= address && address < r.end) return r.fill;
    return 0;
  }
  uint8_t byte(uint64_t address) const {
    require(address < limit, "memory read outside RV32");
    const auto p = pages.find(address & ~(uint64_t(page_bytes) - 1));
    return p == pages.end() ? default_byte(address) : p->second[address & (page_bytes - 1)];
  }
  void byte(uint64_t address, uint8_t value) {
    require(address < limit, "memory write outside RV32");
    const uint64_t base = address & ~(uint64_t(page_bytes) - 1);
    auto found = pages.find(base);
    if (found == pages.end()) {
      std::array<uint8_t, page_bytes> data;
      for (unsigned b = 0; b < page_bytes; ++b) data[b] = default_byte(base + b);
      found = pages.emplace(base, std::move(data)).first;
    }
    found->second[address - base] = value;
  }
  bool contains_line(uint64_t address) const {
    if ((address & 63) || address > limit - 64) return false;
    for (const auto &r : ranges)
      if (address >= r.start && address + 64 <= r.end) return true;
    return false;
  }
  static Memory read(const std::string &path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    require(bool(in), "cannot open sparse memory");
    const auto length = in.tellg(); require(length >= 16, "short sparse memory header");
    in.seekg(0); magic(in, "OGMEM001");
    const uint64_t ranges = read_le(in, 4), pages = read_le(in, 4);
    require(ranges <= 0x7fffffff && pages <= limit / page_bytes, "invalid sparse memory counts");
    require(uint64_t(length) == 16 + ranges * 24 + pages * (page_bytes + 8), "sparse memory size mismatch");
    Memory result;
    uint64_t previous_end = 0;
    for (uint64_t n = 0; n < ranges; ++n) {
      const uint64_t start = read_le(in, 8), end = read_le(in, 8), fill = read_le(in, 4);
      require(read_le(in, 4) == 0, "nonzero sparse memory reserved field");
      require(start >= previous_end && start < end && end <= limit && fill <= 255,
              "invalid sparse memory allocation");
      result.ranges.push_back({start, end, uint8_t(fill)}); previous_end = end;
    }
    uint64_t previous = 0;
    for (uint64_t n = 0; n < pages; ++n) {
      const uint64_t base = read_le(in, 8);
      require((base & (page_bytes - 1)) == 0 && base <= limit - page_bytes &&
              (n == 0 || base > previous), "invalid or duplicate sparse page");
      std::array<uint8_t, page_bytes> data;
      in.read(reinterpret_cast<char *>(data.data()), data.size());
      require(bool(in), "truncated sparse page");
      result.pages.emplace(base, std::move(data)); previous = base;
    }
    require(in.peek() == EOF, "trailing sparse memory data");
    return result;
  }
  void write(const std::string &path) const {
    std::ofstream out(path, std::ios::binary);
    require(bool(out), "cannot create sparse memory");
    out.write("OGMEM001", 8); write_le(out, ranges.size(), 4); write_le(out, pages.size(), 4);
    for (const auto &r : ranges) {
      write_le(out, r.start, 8); write_le(out, r.end, 8);
      write_le(out, r.fill, 4); write_le(out, 0, 4);
    }
    for (const auto &p : pages) {
      write_le(out, p.first, 8);
      out.write(reinterpret_cast<const char *>(p.second.data()), p.second.size());
    }
    out.flush();
    require(bool(out), "cannot write sparse memory");
  }
};

// The product host has a 256-bit external AXI port and 64-byte memory lines.
struct Requests {
  bool arvalid = false, awvalid = false, wvalid = false, wlast = false;
  bool rready = false, bready = false;
  uint64_t araddr = 0, awaddr = 0;
  uint32_t arid = 0, awid = 0, arlen = 0, awlen = 0;
  uint32_t arsize = 5, awsize = 5, arburst = 1, awburst = 1;
  std::array<uint8_t, 32> wdata{};
  uint32_t wstrb = 0;
};
struct Responses {
  bool arready = false, awready = false, wready = false;
  bool rvalid = false, rlast = false, bvalid = false;
  uint32_t rid = 0, bid = 0, rresp = 0, bresp = 0;
  std::array<uint8_t, 32> rdata{};
};
struct Traffic {
  uint64_t reads = 0, read_beats = 0, read_bus_bytes = 0;
  uint64_t writes = 0, write_beats = 0, write_bus_bytes = 0, enabled_write_bytes = 0;
  uint64_t read_faults = 0, write_faults = 0, acknowledgements = 0;
};

/** Two-phase AXI service: drive every input, settle the RTL, then observe all
  * handshakes at the same pre-edge snapshot. Read snapshots precede writes.
  * A response remains stable through stalls. W may arrive before AW.
  */
class Agent {
  struct Read {
    uint64_t due; uint32_t id, beats, beat, offset, response;
    std::array<uint8_t, 64> data;
  };
  struct Ack { uint64_t due; uint32_t id, response; };
  Memory &memory;
  std::deque<Read> reads;
  std::deque<Ack> acks;
  bool have_aw = false, have_last = false;
  uint64_t write_address = 0;
  uint32_t write_id = 0, expected_beats = 0, write_beats = 0;
  std::array<uint8_t, 64> write_data{};
  uint64_t write_mask = 0;
public:
  Traffic traffic;
  explicit Agent(Memory &backing) : memory(backing) {}

  Responses drive(uint64_t cycle) const {
    Responses out;
    out.arready = reads.size() < 16;
    out.awready = !have_aw;
    out.wready = !have_last;
    if (!reads.empty() && reads.front().due <= cycle) {
      const auto &r = reads.front();
      out.rvalid = true; out.rlast = r.beat + 1 == r.beats;
      out.rid = r.id; out.rresp = r.response;
      std::copy_n(r.data.begin() + r.offset + r.beat * 32, 32, out.rdata.begin());
    }
    if (!acks.empty()) {
      out.bvalid = acks.front().due <= cycle;
      out.bid = acks.front().id; out.bresp = acks.front().response;
    }
    return out;
  }

  void observe(uint64_t cycle, const Requests &in, const Responses &out) {
    // AR snapshots the backing before a concurrent W changes any byte.
    if (in.arvalid && out.arready) {
      require(in.arsize <= 5 && in.arburst == 1 && in.arlen <= 1 &&
              in.araddr < Memory::limit &&
              (in.arlen == 0 || (in.arsize == 5 && (in.araddr & 63) == 0)),
              "unsupported product AXI read burst");
      const uint64_t base = in.araddr & ~uint64_t(63);
      const bool fault = !memory.contains_line(base);
      Read r{cycle + 3, in.arid, in.arlen + 1, 0, uint32_t(in.araddr & 32), uint32_t(fault ? 2 : 0), {}};
      for (unsigned b = 0; b < 64; ++b) r.data[b] = memory.byte(base + b);
      reads.push_back(r); ++traffic.reads; traffic.read_faults += fault;
    }
    if (in.awvalid && out.awready) {
      require(in.awsize <= 5 && in.awburst == 1 && in.awlen <= 1 &&
              in.awaddr < Memory::limit &&
              (in.awlen == 0 || (in.awsize == 5 && (in.awaddr & 63) == 0)),
              "unsupported product AXI write burst");
      have_aw = true; write_address = in.awaddr; write_id = in.awid;
      expected_beats = in.awlen + 1; ++traffic.writes;
    }
    if (in.wvalid && out.wready) {
      require(write_beats < 2, "too many AXI write beats before WLAST");
      std::copy(in.wdata.begin(), in.wdata.end(), write_data.begin() + write_beats * 32);
      write_mask |= uint64_t(in.wstrb) << (write_beats * 32);
      ++write_beats; have_last = in.wlast;
      ++traffic.write_beats; traffic.write_bus_bytes += 32;
      traffic.enabled_write_bytes += __builtin_popcount(in.wstrb);
    }
    if (have_aw && have_last) {
      require(write_beats == expected_beats, "AWLEN and WLAST disagree");
      const unsigned offset = write_address & 32;
      require(offset + write_beats * 32 <= 64, "write burst crosses a memory line");
      const uint64_t base = write_address & ~uint64_t(63);
      const bool fault = !memory.contains_line(base);
      if (!fault)
        for (unsigned b = 0; b < write_beats * 32; ++b)
          if ((write_mask >> b) & 1) memory.byte(base + offset + b, write_data[b]);
      acks.push_back({cycle + 7, write_id, uint32_t(fault ? 2 : 0)});
      traffic.write_faults += fault;
      have_aw = false; have_last = false; write_beats = 0; write_mask = 0;
    }
    if (out.rvalid && in.rready) {
      auto &r = reads.front();
      ++traffic.read_beats; traffic.read_bus_bytes += 32;
      if (++r.beat == r.beats) reads.pop_front();
    }
    if (out.bvalid && in.bready) {
      acks.pop_front(); ++traffic.acknowledgements;
    }
  }

  bool drained() const { return reads.empty() && acks.empty() && !have_aw && !have_last && write_beats == 0; }
};
} // namespace native_frame
