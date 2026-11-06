/* SPDX-License-Identifier: MIT */
/* Included only by the explicit ResourceGuest wrapper, after its ARTI globals
 * and pin definitions. No responder, pin assignment, clock or cache changes. */
#include "guest_memory_observer.h"
#include <cerrno>
#include <filesystem>

namespace {
using namespace opengpu_qualification;

uint64_t memory_proof_number(const char *name) {
    const char *p = std::getenv(name);
    if (!p || !*p || *p == '-' || *p == '+' || *p == ' ' || *p == '\t')
        throw std::runtime_error("missing or malformed observer RAM bound");
    char *end = nullptr; errno = 0;
    auto n = std::strtoull(p, &end, 0);
    if (errno || *end) throw std::runtime_error("malformed observer RAM bound");
    return n;
}

struct MemoryProof {
    std::filesystem::path directory;
    FILE *events = nullptr;
    std::unique_ptr<Observer> observer;
    explicit MemoryProof(const char *path) : directory(path) {
        // Bounds must be pinned against the actual guest DTB/argv by the
        // qualification launcher. This observer cannot infer QEMU RAM size.
        uint64_t base = memory_proof_number("ARTI_NATIVE_RAM_BASE");
        uint64_t end = memory_proof_number("ARTI_NATIVE_RAM_END");
        if (!std::filesystem::create_directory(directory))
            throw std::runtime_error("observer directory must be fresh");
        events = std::fopen((directory / "events.jsonl").c_str(), "wx");
        if (!events) throw std::runtime_error("cannot create observer events");
        observer.reset(new Observer(base, end,
            [](uint64_t address, uint8_t *bytes, unsigned n) {
                // Observer-only callback: no AXI request or tick. Observer
                // validates each page lies wholly in RAM before calling it.
                return g_mem_read_cb && !g_mem_read_cb(address, bytes, n, 0);
            }, [this](const Snapshot &s) { snapshot(s); },
               [this](const Event &e) { event(e); }));
        if (std::fprintf(events, "{\"kind\":\"configuration\",\"ram_base\":%llu,\"ram_end\":%llu,\"table_limit\":384}\n",
            (unsigned long long)base, (unsigned long long)end) < 0)
            throw std::runtime_error("observer configuration write failed");
    }
    ~MemoryProof() { if (events) std::fclose(events); }
    static void file(const std::filesystem::path &path, const uint8_t *data, size_t n) {
        FILE *f = std::fopen(path.c_str(), "wx");
        if (!f) throw std::runtime_error("cannot create observer table");
        bool ok = std::fwrite(data, 1, n, f) == n;
        int closed = std::fclose(f);
        if (!ok || closed) throw std::runtime_error("observer table write failed");
    }
    void snapshot(const Snapshot &s) {
        std::string stem = "submit-" + std::to_string(s.ordinal);
        file(directory / (stem + "-root.bin"), s.root.data(), s.root.size());
        for (const auto &t : s.tables)
            file(directory / (stem + "-l1-" + std::to_string(t.region) + ".bin"), t.bytes.data(), t.bytes.size());
        FILE *f = std::fopen((directory / (stem + ".json")).c_str(), "wx");
        if (!f) throw std::runtime_error("cannot create observer snapshot metadata");
        bool ok = std::fprintf(f, "{\"ordinal\":%llu,\"vector_satp\":%u,\"instruction_satp\":%u,\"staging\":[",
            (unsigned long long)s.ordinal, s.vector_satp, s.instruction_satp) >= 0;
        const char *separator = "";
        for (const auto &r : s.staging) {
            ok &= std::fprintf(f, "%s[%u,%u]", separator, r.first, r.second) >= 0;
            separator = ",";
        }
        ok &= std::fprintf(f, "],\"tables\":[") >= 0; separator = "";
        for (const auto &t : s.tables) {
            ok &= std::fprintf(f, "%s{\"region\":%u,\"physical\":%llu}", separator,
                t.region, (unsigned long long)t.physical) >= 0; separator = ",";
        }
        ok &= std::fprintf(f, "],\"status\":\"PRE_SUBMIT_SNAPSHOT_REQUIRES_LATER_B_AND_TERMINAL\"}\n") >= 0;
        int closed = std::fclose(f);
        if (!ok || closed) throw std::runtime_error("observer snapshot metadata write failed");
    }
    void event(const Event &e) {
        if (std::fprintf(events,
            "{\"kind\":\"%s\",\"cycle\":%llu,\"address\":%llu,\"id\":%llu,\"length\":%u,\"size\":%u,"
            "\"burst\":%u,\"cache\":%u,\"protection\":%u,\"qos\":%u,\"lock\":%u,\"strobe\":%u,"
            "\"write_word\":%u,\"write_last\":%u,\"reply_word\":%u,\"reply_id\":%llu,\"response\":%u,\"last\":%u}\n",
            e.kind, (unsigned long long)e.cycle, (unsigned long long)e.address.address,
            (unsigned long long)e.address.id, e.address.length, e.address.size, e.address.burst,
            e.address.cache, e.address.protection, e.address.qos, unsigned(e.address.lock), e.write.strobe,
            Observer::word(e.write.data.data()), unsigned(e.write.last), Observer::word(e.reply.data.data()),
            (unsigned long long)e.reply.id, e.reply.response, unsigned(e.reply.last)) < 0)
            throw std::runtime_error("observer event write failed");
    }
    void finish() {
        observer->finish();
        const auto &c = observer->counts();
        if (std::fprintf(events,
            "{\"kind\":\"terminal\",\"status\":\"COMPLETE_DRAINED\",\"private_submits\":%llu,\"global_submits\":%llu,"
            "\"pops\":%llu,\"mmio_writes\":%llu,\"mmio_b\":%llu,\"mmio_reads\":%llu,\"mmio_r\":%llu,"
            "\"ar\":%llu,\"r\":%llu,\"aw\":%llu,\"w\":%llu,\"b\":%llu,\"dut_read_bytes\":%llu,"
            "\"dut_write_bytes\":%llu,\"observer_read_bytes\":%llu,\"stalled_mmio\":%llu,\"stalled_lower\":%llu}\n",
            (unsigned long long)c.private_submits, (unsigned long long)c.global_submits, (unsigned long long)c.pops,
            (unsigned long long)c.mmio_writes, (unsigned long long)c.mmio_b, (unsigned long long)c.mmio_reads,
            (unsigned long long)c.mmio_r, (unsigned long long)c.ar, (unsigned long long)c.r,
            (unsigned long long)c.aw, (unsigned long long)c.w, (unsigned long long)c.b,
            (unsigned long long)c.dut_read_bytes, (unsigned long long)c.dut_write_bytes,
            (unsigned long long)c.observer_read_bytes, (unsigned long long)c.stalled_mmio,
            (unsigned long long)c.stalled_lower) < 0 || std::fflush(events))
            throw std::runtime_error("observer terminal write failed");
        std::fprintf(stderr, "ARTI_NATIVE_MEMORY_OBSERVER status=COMPLETE_DRAINED privateSubmits=%llu observerBytes=%llu dutReadBytes=%llu dutWriteBytes=%llu\n",
            (unsigned long long)c.private_submits, (unsigned long long)c.observer_read_bytes,
            (unsigned long long)c.dut_read_bytes, (unsigned long long)c.dut_write_bytes);
    }
};
std::unique_ptr<MemoryProof> g_memory_proof;

[[noreturn]] void memory_proof_failure(const std::exception &e) {
    std::fprintf(stderr, "ARTI_NATIVE_MEMORY_OBSERVER status=FAIL error=%s\n", e.what());
    std::fflush(nullptr); std::_Exit(1);
}
} // namespace

static void native_memory_observer_finish() {
    try { if (g_memory_proof) g_memory_proof->finish(); }
    catch (const std::exception &e) { memory_proof_failure(e); }
}
static void native_memory_observer_init() {
    const char *path = std::getenv("ARTI_NATIVE_MEMORY_PROOF");
    if (!path) return;
    try {
        if (!*path) throw std::runtime_error("empty observer directory");
        g_memory_proof.reset(new MemoryProof(path));
        if (std::atexit(native_memory_observer_finish)) throw std::runtime_error("observer exit registration failed");
    } catch (const std::exception &e) { memory_proof_failure(e); }
}
static void native_memory_observer_edge() {
    if (!g_memory_proof) return;
    try {
        Bus mmio, lower;
#define OBS_ADDRESS(b, member, prefix) do { \
    (b).member.valid = g_rtl->prefix##valid; (b).member.ready = g_rtl->prefix##ready; \
    (b).member.address = g_rtl->prefix##addr; (b).member.size = g_rtl->prefix##size; \
    (b).member.length = g_rtl->prefix##len; (b).member.burst = g_rtl->prefix##burst; \
} while (0)
        OBS_ADDRESS(mmio, aw, io_s_axi_aw); OBS_ADDRESS(mmio, ar, io_s_axi_ar);
        OBS_ADDRESS(lower, aw, io_m_axi_aw); OBS_ADDRESS(lower, ar, io_m_axi_ar);
#undef OBS_ADDRESS
#define OBS_ATTRIBUTES(member, prefix) do { \
    lower.member.id = g_rtl->prefix##id; lower.member.cache = g_rtl->prefix##cache; \
    lower.member.protection = g_rtl->prefix##prot; lower.member.qos = g_rtl->prefix##qos; \
    lower.member.lock = g_rtl->prefix##lock; \
} while (0)
        OBS_ATTRIBUTES(aw, io_m_axi_aw); OBS_ATTRIBUTES(ar, io_m_axi_ar);
#undef OBS_ATTRIBUTES
#define OBS_WRITE(b, prefix) do { \
    (b).w.valid = g_rtl->prefix##wvalid; (b).w.ready = g_rtl->prefix##wready; \
    (b).w.strobe = g_rtl->prefix##wstrb; (b).w.last = g_rtl->prefix##wlast; \
} while (0)
        OBS_WRITE(mmio, io_s_axi_); OBS_WRITE(lower, io_m_axi_);
#undef OBS_WRITE
#define OBS_REPLY(b, member, prefix) do { \
    (b).member.valid = g_rtl->prefix##valid; (b).member.ready = g_rtl->prefix##ready; \
    (b).member.response = g_rtl->prefix##resp; \
} while (0)
        OBS_REPLY(mmio, b, io_s_axi_b); OBS_REPLY(mmio, r, io_s_axi_r);
        OBS_REPLY(lower, b, io_m_axi_b); OBS_REPLY(lower, r, io_m_axi_r);
#undef OBS_REPLY
        mmio.r.last = g_rtl->io_s_axi_rlast; lower.r.last = g_rtl->io_m_axi_rlast;
        lower.r.id = g_rtl->io_m_axi_rid; lower.b.id = g_rtl->io_m_axi_bid;
        for (unsigned i = 0; i < 4; i++) {
            mmio.w.data[i] = uint8_t(g_rtl->io_s_axi_wdata >> (8*i));
            mmio.r.data[i] = uint8_t(g_rtl->io_s_axi_rdata >> (8*i));
        }
        for (unsigned i = 0; i < 32; i++) {
            lower.w.data[i] = uint8_t(g_rtl->io_m_axi_wdata[i/4] >> (8*(i%4)));
            lower.r.data[i] = uint8_t(g_rtl->io_m_axi_rdata[i/4] >> (8*(i%4)));
        }
        g_memory_proof->observer->edge(mmio, lower);
    } catch (const std::exception &e) { memory_proof_failure(e); }
}
