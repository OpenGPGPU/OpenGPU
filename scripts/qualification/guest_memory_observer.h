/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_QUALIFICATION_GUEST_MEMORY_OBSERVER_H
#define OPENGPU_QUALIFICATION_GUEST_MEMORY_OBSERVER_H

/* Qualification-only, pre-edge observation. This class never drives a pin,
 * advances a clock, writes guest memory, or supplies a device response. */
#include <array>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace opengpu_qualification {

struct Address {
    bool valid = false, ready = false;
    uint64_t address = 0, id = 0;
    unsigned length = 0, size = 0, burst = 1, cache = 0, protection = 0, qos = 0;
    bool lock = false;
    bool operator==(const Address &b) const {
        return address == b.address && id == b.id && length == b.length &&
               size == b.size && burst == b.burst && cache == b.cache &&
               protection == b.protection && qos == b.qos && lock == b.lock;
    }
};
struct Write {
    bool valid = false, ready = false, last = true;
    uint32_t strobe = 0;
    std::array<uint8_t, 32> data{};
    bool operator==(const Write &b) const { return last == b.last && strobe == b.strobe && data == b.data; }
};
struct Reply {
    bool valid = false, ready = false, last = true;
    uint64_t id = 0;
    unsigned response = 0;
    std::array<uint8_t, 32> data{};
    bool operator==(const Reply &b) const {
        return id == b.id && response == b.response && last == b.last && data == b.data;
    }
};
struct Bus { Address ar, aw; Write w; Reply r, b; };
struct TablePage { uint32_t region = 0; uint64_t physical = 0; std::array<uint8_t, 4096> bytes{}; };
struct Snapshot {
    uint64_t ordinal = 0;
    uint32_t vector_satp = 0, instruction_satp = 0;
    std::map<uint32_t, uint32_t> staging;
    std::array<uint8_t, 4096> root{};
    std::vector<TablePage> tables;
};
struct Counts {
    uint64_t mmio_writes = 0, mmio_b = 0, mmio_reads = 0, mmio_r = 0;
    uint64_t ar = 0, r = 0, aw = 0, w = 0, b = 0;
    uint64_t dut_read_bytes = 0, dut_write_bytes = 0, observer_read_bytes = 0;
    uint64_t private_submits = 0, global_submits = 0, pops = 0;
    uint64_t stalled_mmio = 0, stalled_lower = 0;
};
struct Event {
    const char *kind;
    uint64_t cycle;
    Address address{};
    Write write{};
    Reply reply{};
};

class Observer {
public:
    using ReadRam = std::function<bool(uint64_t, uint8_t *, unsigned)>;
    using Save = std::function<void(const Snapshot &)>;
    using Trace = std::function<void(const Event &)>;
    static constexpr uint64_t page_bytes = 4096, physical_limit = UINT64_C(1) << 32;
    Observer(uint64_t ram_base, uint64_t ram_end, ReadRam read, Save save, Trace trace = {})
        : base_(ram_base), end_(ram_end), read_(read), save_(save), trace_(trace) {
        require(base_ < end_ && end_ <= physical_limit && !(base_ & 4095) && !(end_ & 4095), "RAM extent");
    }
    const Counts &counts() const { return counts_; }
    bool drained() const {
        return mm_aw_.empty() && mm_w_.empty() && mm_b_.empty() && mm_ar_.empty() &&
               reads_.empty() && writes_.empty() && write_data_.empty() && write_replies_.empty();
    }
    void finish() const { require(drained() && !pending_offer_, "unaccepted or outstanding transaction at terminal"); }

    /* Call once after low-clock evaluation and before the rising edge or the
     * ARTI bridge consumes/pops any accepted channel. API return values play
     * no part in acceptance. */
    void edge(const Bus &mmio, const Bus &memory) {
        hold(mmio.ar, mm_ar_hold_); hold(mmio.aw, mm_aw_hold_);
        hold(mmio.w, mm_w_hold_); hold(mmio.r, mm_r_hold_); hold(mmio.b, mm_b_hold_);
        hold(memory.ar, ar_hold_); hold(memory.aw, aw_hold_);
        hold(memory.w, w_hold_); hold(memory.r, r_hold_); hold(memory.b, b_hold_);
        cycle_++;
        bool pending_mmio = stalled(mmio);
        pending_lower_ = stalled(memory);
        pending_offer_ = pending_mmio || pending_lower_;
        counts_.stalled_mmio += pending_mmio; counts_.stalled_lower += pending_lower_;
        lower(memory);
        control(mmio);
        unsigned nr = 0, nb = 0;
        for (const auto &r : reads_) nr += unsigned(r.second.size());
        for (const auto &b : write_replies_) nb += b.second;
        require(mm_aw_.size() <= 64 && mm_w_.size() <= 64 && mm_b_.size() <= 64 && mm_ar_.size() <= 64 &&
                nr <= 256 && nb <= 256 && writes_.size() <= 256 && write_data_.size() <= 256, "observer queue bound");
    }
    bool pending_offer() const { return pending_offer_; }

    static uint32_t word(const uint8_t *p) {
        return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
    }
    /* Independent full-table translation used by the offline CPU checks and
     * future PA/PFN join. It does not issue a device request. */
    static uint64_t translate(const Snapshot &s, uint32_t va) {
        uint32_t region = va >> 22, root = word(s.root.data() + region * 4);
        require((root & 0x3ff) == 1 && !(root >> 30), "private root is not a level0 link");
        uint64_t table = uint64_t(root >> 10) << 12;
        for (const auto &p : s.tables) if (p.region == region && p.physical == table) {
            uint32_t pte = word(p.bytes.data() + ((va >> 12) & 1023) * 4);
            require(valid_leaf(pte), "unmapped or malformed private leaf");
            return (uint64_t((pte >> 10) & 0xfffff) << 12) | (va & 4095);
        }
        throw std::runtime_error("missing owned level0 table");
    }

private:
    template<class T> struct Held { bool held = false; T bits{}; };
    template<class T> static void hold(const T &v, Held<T> &old) {
        if (old.held) require(v.valid && v == old.bits, "irrevocable offer changed while stalled");
        old.held = v.valid && !v.ready;
        if (old.held) old.bits = v;
    }
    static void require(bool ok, const char *why) { if (!ok) throw std::runtime_error(why); }
    static bool fire(const Address &v) { return v.valid && v.ready; }
    static bool fire(const Write &v) { return v.valid && v.ready; }
    static bool fire(const Reply &v) { return v.valid && v.ready; }
    static bool stalled(const Bus &b) {
        return (b.ar.valid && !b.ar.ready) || (b.aw.valid && !b.aw.ready) || (b.w.valid && !b.w.ready) ||
               (b.r.valid && !b.r.ready) || (b.b.valid && !b.b.ready);
    }
    void trace(const char *kind, const Address &a = {}, const Write &w = {}, const Reply &r = {}) {
        if (trace_) trace_({kind, cycle_, a, w, r});
    }
    bool ram(uint64_t a, uint64_t n) const { return n && a >= base_ && a < end_ && n <= end_ - a; }
    static bool valid_leaf(uint32_t p) {
        return (p & 1) && (p & (2 | 8)) && !((p & 4) && !(p & 2)) &&
               !(p >> 30) && ((p >> 8) & 3) <= 2 && (p & 64) && (!(p & 4) || (p & 128));
    }
    void read_page(uint64_t a, std::array<uint8_t, 4096> &data) {
        require(!(a & 4095) && ram(a, page_bytes), "table outside actual RAM");
        for (unsigned i = 0; i < page_bytes; i += 64) {
            require(read_ && read_(a + i, data.data() + i, 64), "observer RAM read failed");
            counts_.observer_read_bytes += 64;
        }
    }
    void snapshot() {
        uint32_t v = staging_[0x14c], i = staging_[0x150];
        require(v == i && (v >> 31), "compute SATP pair mismatch or Bare mode");
        if (!((v >> 22) & 511)) { counts_.global_submits++; return; }
        require(!(v & (3u << 20)), "reserved SATP bits");
        Snapshot s; s.ordinal = ++counts_.private_submits;
        s.vector_satp = v; s.instruction_satp = i; s.staging = staging_;
        uint64_t root = uint64_t(v & 0xfffff) << 12;
        read_page(root, s.root);
        std::map<uint64_t, unsigned> owned;
        owned[root] = 1024;
        for (unsigned region = 0; region < 1024; region++) {
            uint32_t p = word(s.root.data() + region * 4);
            if (!p) continue;
            require((p & 0x3ff) == 1 && !(p >> 30), "private root non-link or unsupported PPN");
            uint64_t physical = uint64_t(p >> 10) << 12;
            require(!owned.count(physical) && s.tables.size() < 384, "duplicate/cyclic/excessive private tables");
            owned[physical] = region;
            TablePage t; t.region = region; t.physical = physical;
            read_page(physical, t.bytes);
            s.tables.push_back(t);
        }
        for (const auto &t : s.tables) for (unsigned n = 0; n < 1024; n++) {
            uint32_t p = word(t.bytes.data() + n * 4);
            if (!p) continue;
            uint64_t physical = uint64_t((p >> 10) & 0xfffff) << 12;
            require(valid_leaf(p) && ram(physical, page_bytes), "invalid or out-of-RAM private leaf");
            require(!owned.count(physical), "table page exposed as shader data");
        }
        if (save_) save_(s);
    }
    void control(const Bus &b) {
        if (fire(b.aw)) {
            require(b.aw.address <= 0xffc && !(b.aw.address & 3) && b.aw.size == 2 &&
                    b.aw.length == 0 && b.aw.burst == 1, "MMIO address or shape");
            trace("mmio_aw", b.aw); mm_aw_.push_back(b.aw);
        }
        if (fire(b.w)) { trace("mmio_w", {}, b.w); mm_w_.push_back(b.w); }
        while (!mm_aw_.empty() && !mm_w_.empty()) {
            auto a = mm_aw_.front(); auto w = mm_w_.front(); mm_aw_.pop_front(); mm_w_.pop_front();
            require(w.strobe == 15 && w.last, "qualification MMIO requires complete32bit write");
            uint32_t value = word(w.data.data()), address = uint32_t(a.address);
            if ((address == 0x14c || address == 0x150) && staging_[address] != value)
                require(reads_.empty() && writes_.empty() && write_data_.empty() && write_replies_.empty() &&
                        !pending_lower_, "SATP changed with lower traffic outstanding");
            staging_[address] = value; counts_.mmio_writes++; mm_b_.push_back(a);
            trace("mmio_write", a, w);
            if (address == 0x11c && (value & 1) && (staging_[0xc8] == 0 || staging_[0xc8] == 14)) snapshot();
            if (address == 0x130 && (value & 1)) counts_.pops++;
        }
        if (fire(b.b)) {
            require(!mm_b_.empty(), "unsolicited MMIO B"); trace("mmio_b", mm_b_.front(), {}, b.b);
            require(b.b.response == 0, "failed MMIO B"); mm_b_.pop_front(); counts_.mmio_b++;
        }
        if (fire(b.ar)) {
            require(b.ar.address <= 0xffc && !(b.ar.address & 3) && b.ar.size == 2 &&
                    b.ar.length == 0 && b.ar.burst == 1, "MMIO read address or shape");
            trace("mmio_ar", b.ar); mm_ar_.push_back(b.ar); counts_.mmio_reads++;
        }
        if (fire(b.r)) {
            require(!mm_ar_.empty(), "unsolicited MMIO R"); trace("mmio_r", mm_ar_.front(), {}, b.r);
            require(b.r.response == 0 && b.r.last, "failed MMIO R"); mm_ar_.pop_front(); counts_.mmio_r++;
        }
    }
    struct Burst { Address a; unsigned beat = 0; };
    static uint64_t beat_address(const Address &a, unsigned n) {
        uint64_t size = UINT64_C(1) << a.size;
        // address() first rejects a base outside 32bit RAM. n <= 255, so the
        // following addition cannot overflow uint64 even at the 4GiB edge.
        return a.burst && n ? (a.address & ~(size - 1)) + uint64_t(n) * size : a.address;
    }
    void address(const Address &a) {
        require(a.size <= 5 && a.length < 256 && a.burst <= 1 && a.address < physical_limit,
                "unsupported32byte AXI shape");
        uint64_t size = UINT64_C(1) << a.size;
        for (unsigned n = 0; n <= a.length; n++) {
            uint64_t p = beat_address(a, n);
            require(ram(p, size - (p & (size - 1))), "AXI transfer outside actual RAM");
        }
    }
    void lower(const Bus &b) {
        if (fire(b.ar)) { address(b.ar); trace("lower_ar", b.ar); reads_[b.ar.id].push_back({b.ar, 0}); counts_.ar++; }
        if (fire(b.r)) {
            auto found = reads_.find(b.r.id);
            require(found != reads_.end() && !found->second.empty() && b.r.response == 0, "unsolicited/failed lower R");
            auto &q = found->second; Burst &r = q.front();
            trace("lower_r", r.a, {}, b.r);
            require(b.r.last == (r.beat == r.a.length), "lower RLAST");
            uint64_t size = UINT64_C(1) << r.a.size;
            counts_.dut_read_bytes += size - (beat_address(r.a, r.beat) & (size - 1)); counts_.r++;
            if (++r.beat > r.a.length) q.pop_front();
            if (q.empty()) reads_.erase(found);
        }
        if (fire(b.aw)) { address(b.aw); trace("lower_aw", b.aw); writes_.push_back({b.aw, 0}); counts_.aw++; }
        if (fire(b.w)) { trace("lower_w", {}, b.w); write_data_.push_back(b.w); counts_.w++; }
        while (!writes_.empty() && !write_data_.empty()) {
            Burst &a = writes_.front(); Write w = write_data_.front(); write_data_.pop_front();
            require(w.last == (a.beat == a.a.length), "lower WLAST");
            uint64_t size = UINT64_C(1) << a.a.size, physical = beat_address(a.a, a.beat);
            unsigned lane = unsigned(physical & 31), bytes = unsigned(size - (physical & (size - 1)));
            uint64_t mask = ((UINT64_C(1) << bytes) - 1) << lane;
            require(!(uint64_t(w.strobe) & ~mask), "lower WSTRB outside AXI transfer lanes");
            for (unsigned bit = 0; bit < 32; bit++) if (w.strobe & (1u << bit)) counts_.dut_write_bytes++;
            if (++a.beat > a.a.length) { write_replies_[a.a.id]++; writes_.pop_front(); }
        }
        if (fire(b.b)) {
            auto p = write_replies_.find(b.b.id);
            trace("lower_b", {}, {}, b.b);
            require(p != write_replies_.end() && p->second && b.b.response == 0, "unsolicited/failed lower B");
            if (!--p->second) write_replies_.erase(p);
            counts_.b++;
        }
    }
    uint64_t base_, end_;
    ReadRam read_; Save save_; Trace trace_; Counts counts_; uint64_t cycle_ = 0;
    std::map<uint32_t, uint32_t> staging_;
    std::deque<Address> mm_aw_, mm_ar_, mm_b_;
    std::deque<Write> mm_w_, write_data_;
    std::map<uint64_t, std::deque<Burst>> reads_;
    std::deque<Burst> writes_;
    std::map<uint64_t, unsigned> write_replies_;
    Held<Address> mm_ar_hold_, mm_aw_hold_, ar_hold_, aw_hold_;
    Held<Write> mm_w_hold_, w_hold_;
    Held<Reply> mm_r_hold_, mm_b_hold_, r_hold_, b_hold_;
    bool pending_offer_ = false, pending_lower_ = false;
};

} // namespace opengpu_qualification
#endif
