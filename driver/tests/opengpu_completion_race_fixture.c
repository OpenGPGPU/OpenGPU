#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
#include "opengpu_tessellation_completion.h"
#include "opengpu_tessellation_joint_host.h"
#include "opengpu_native_completion.h"
#include "gpu_abi.h"
#include "opengpu_unified_completion.h"
typedef uint64_t u64;
#define OPENGPU_FAULT_ABORTED 32u
#define OPENGPU_FAULT_TIMEOUT 64u
#define OPENGPU_FAULT_RESET_ISSUED 128u
#define OPENGPU_FAULT_COMPLETION_ERROR 1u
#define OPENGPU_FAULT_COMMAND_ID_MISMATCH 2u
#define OPENGPU_FAULT_OPCODE_MISMATCH 4u
#define OPENGPU_FAULT_SUCCESS_MISMATCH 8u
#define OPENGPU_FAULT_BYTES_MISMATCH 16u
#define OPENGPU_DRAW_WAIT_MS 100u
#define OPENGPU_HW_POLL_INTERVAL_MS 1u
#define spin_lock_irqsave(lock,flags) do { (flags)=0; pthread_mutex_lock(lock); } while (0)
#define spin_unlock_irqrestore(lock,flags) do { (void)(flags); pthread_mutex_unlock(lock); } while (0)
#define dev_err(...) ((void)0)
struct dma_fence { int signals, error, puts; };
struct opengpu_device {
    struct {
        u32 capabilities;
        pthread_mutex_t fence_lock;
        unsigned long long regs_size;
        struct dma_fence *unified_fence, *active_fence;
        u32 unified_command_id, unified_opcode;
        u32 unified_completion_delay_ms, active_completion_delay_ms;
        unsigned int aborting;
        u64 unified_expected_bytes;
        void *native_pending_owner;
        struct opengpu_tessellation_joint_completion *joint_pending_completion;
        struct { bool available; } native_sources;
        u32 native_context, native_pass, native_generation;
        struct {
            bool native_valid;
            struct opengpu_native_completion native;
            bool tessellation_valid;
            struct opengpu_tessellation_completion tessellation;
        } last_fault;
        int timeout_work, completion_work, poll_work;
    } hw;
    void *dev;
    atomic_int slot, pops, reads_after_pop, status_observers, doorbells;
    u32 completion_override;
    u64 completion_data;
    u32 recorded_fault_flags;
    u32 words[12], native_words[12];
    unsigned native_reads;
    u32 joint_words[16], joint_profile[4];
    unsigned joint_reads, joint_profile_reads;
};

static u32 opengpu_reg_read(struct opengpu_device *g, u32 reg)
{
    if (reg == GPU_REG_UCMD_STATUS) {
        u32 present = atomic_load(&g->slot);
        struct timespec delay = {0, 1000000};
        atomic_fetch_add(&g->status_observers, 1);
        /* Give the other caller a chance to observe the same held slot. */
        for (int i = 0; i < 50 && atomic_load(&g->status_observers) < 2; i++)
            nanosleep(&delay, NULL);
        return present ? GPU_UCMD_STATUS_COMPLETION : 0;
    }
    if (reg == GPU_REG_UCMD_COMPLETION)
        return g->completion_override ? g->completion_override :
            17u | (7u << 8) | (3u << 11) | (1u << 16);
    if (reg == GPU_REG_UCMD_COMPLETION_BYTES_LO)
        return (u32)g->completion_data;
    if (reg == GPU_REG_UCMD_COMPLETION_BYTES_HI)
        return (u32)(g->completion_data >> 32);
    if (reg >= 0x550 && reg < 0x560) {
        ++g->joint_profile_reads;
        return g->joint_profile[(reg - 0x550) / 4];
    }
    if (g->hw.joint_pending_completion &&
        ((reg >= 0x180 && reg < 0x1b0) || (reg >= 0x540 && reg < 0x550))) {
        assert(atomic_load(&g->slot));
        ++g->joint_reads;
        return g->joint_words[reg < 0x540 ? (reg - 0x180) / 4 : 12 + (reg - 0x540) / 4];
    }
    if (g->hw.unified_opcode == GPU_UCMD_OP_NATIVE_BINNED_RENDER &&
        reg >= OPENGPU_NATIVE_BINNED_COMPLETION_BASE &&
        reg < OPENGPU_NATIVE_BINNED_COMPLETION_BASE + 48) {
        assert(atomic_load(&g->slot));
        ++g->native_reads;
        return g->native_words[(reg - OPENGPU_NATIVE_BINNED_COMPLETION_BASE) / 4];
    }
    if (reg >= 0x180 && reg < 0x1b0) {
        if (!atomic_load(&g->slot))
            atomic_fetch_add(&g->reads_after_pop, 1);
        return g->words[(reg - 0x180) / 4];
    }
    assert(0);
    return 0;
}
static u32 opengpu_mmio_read_callback(void *g, u32 reg) { return opengpu_reg_read(g, reg); }
static void opengpu_reg_write(struct opengpu_device *g, u32 reg, u32 value)
{
    if (reg == GPU_REG_IRQ)
        return;
    if (reg == GPU_REG_UCMD_SUBMIT) {
        assert(value == 1);
        assert(__atomic_load_n(&g->hw.timeout_work, __ATOMIC_SEQ_CST) == OPENGPU_DRAW_WAIT_MS);
        assert(__atomic_load_n(&g->hw.poll_work, __ATOMIC_SEQ_CST) == OPENGPU_HW_POLL_INTERVAL_MS);
        atomic_store(&g->slot, 1);
        atomic_fetch_add(&g->doorbells, 1);
        return;
    }
    assert(reg == GPU_REG_UCMD_COMPLETION_POP && value == 1);
    atomic_fetch_add(&g->pops, 1);
    atomic_store(&g->slot, 0);
}
static int opengpu_hw_unified_result_error(u32 opcode, u32 status)
{
    (void)opcode;
    return status ? -EIO : 0;
}
static void opengpu_hw_record_fault_locked(struct opengpu_device *g, int error,
    u32 flags, u32 actual_id, u32 opcode, u32 expected_id, u32 expected_opcode,
    u32 status, u64 processed, u64 expected)
{
    (void)error; (void)actual_id; (void)opcode; (void)expected_id;
    (void)expected_opcode; (void)status; (void)processed; (void)expected;
    g->hw.last_fault.tessellation_valid = false;
    g->hw.last_fault.native_valid = false;
    g->recorded_fault_flags = flags;
}
static void dma_fence_set_error(struct dma_fence *f, int error) { f->error = error; }
static void dma_fence_signal_locked(struct dma_fence *f) { f->signals++; }
static void dma_fence_put(struct dma_fence *f) { f->puts++; }
static void cancel_delayed_work(int *work) { __atomic_store_n(work, 0, __ATOMIC_SEQ_CST); }
static atomic_bool block_cancel, cancel_entered, release_cancel;
static void cancel_delayed_work_sync(int *work)
{
    if (atomic_load(&block_cancel)) {
        atomic_store(&cancel_entered, true);
        while (!atomic_load(&release_cancel)) sched_yield();
    }
    cancel_delayed_work(work);
}
#define system_dfl_wq NULL
#define msecs_to_jiffies(ms) (ms)
static void mod_delayed_work(void *queue, int *work, u32 delay)
{
    (void)queue;
    __atomic_store_n(work, (int)delay, __ATOMIC_SEQ_CST);
}
static void opengpu_hw_unified_reset_request(struct opengpu_device *g) { (void)g; }
#include "drain_under_test.inc"

static void test_joint_profile(void)
{
    struct opengpu_device g = {
        .joint_profile = {0x474a5001, 0x7ff, 0x08100207, 4},
    };
    struct opengpu_native_source_limits native = {
        .engines = 4, .lanes = 16, .warps = 8, .available = true,
    };
    struct opengpu_protected_tessellation_joint_profile profile = {0};
    const u32 flags = OPENGPU_COMPUTE_RESOURCE_NATIVE_PROTECTED_SOURCES_V1 |
        OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_V1;
    const u32 caps = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE |
        GPU_CAP_COMPUTE_SCALAR_FPU;

    native.frame.words[4] = (16u << 16) | (16u << 21) | (16u << 26);
    native.frame.words[6] = (4u << 21) | (4u << 24);
    native.frame.words[7] = 2;
    assert(opengpu_read_tessellation_joint_profile(0, caps, 0, NULL, false, false,
        &g, NULL, &profile) && !profile.available && !g.joint_profile_reads);
    assert(!opengpu_read_tessellation_joint_profile(flags, caps, 0x55f, &native, true, true,
        &g, opengpu_mmio_read_callback, &profile) && !g.joint_profile_reads);
    assert(!opengpu_read_tessellation_joint_profile(flags | OPENGPU_COMPUTE_RESOURCE_TESSELLATION_COMMANDS,
        caps, 0x560, &native, true, true, &g, opengpu_mmio_read_callback, &profile) &&
        !g.joint_profile_reads);
    assert(opengpu_read_tessellation_joint_profile(flags, caps, 0x560, &native, true, true,
        &g, opengpu_mmio_read_callback, &profile) && g.joint_profile_reads == 4);
    assert(profile.available && profile.version == 1 && profile.domains == 7 &&
        profile.compute_units == 2 && profile.lanes == 16 && profile.warps == 8 && profile.engines == 4);
    assert(profile.scalar_fpu && profile.advanced_blend && profile.sample_inputs &&
        profile.transform_feedback && profile.owned_patch_reads && profile.whole_owner_retirement);
    assert(!opengpu_read_tessellation_joint_profile(flags, caps, 0x560, &native, true, false,
        &g, opengpu_mmio_read_callback, &profile) && profile.sample_inputs);
    g.joint_profile[1] ^= 1u;
    assert(!opengpu_read_tessellation_joint_profile(flags, caps, 0x560, &native, true, true,
        &g, opengpu_mmio_read_callback, &profile) && profile.available);
    g.joint_profile[1] ^= 1u;
    g.joint_profile[3] = 1;
    assert(!opengpu_read_tessellation_joint_profile(flags, caps, 0x560, &native, true, true,
        &g, opengpu_mmio_read_callback, &profile) && profile.engines == 4);
}

static void test_joint_completion(void)
{
    for (unsigned mode = 0; mode < 9; ++mode) {
        struct opengpu_tessellation_joint_completion result = {0};
        struct dma_fence fence = {0};
        struct opengpu_device g = {
            .hw.capabilities = GPU_CAP_UNIFIED_COMMANDS,
            .hw.regs_size = mode == 7 ? 0x54f : 0x550,
            .hw.unified_fence = &fence, .hw.unified_command_id = 17, .hw.unified_opcode = 15,
            .hw.native_context = 42, .hw.native_pass = 8, .hw.native_generation = 9,
            .hw.joint_pending_completion = &result,
            .completion_data = 0x100000040ull,
            .joint_words = {0x474e5a02, 0x40, 0, 42, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19},
        };
        const u32 status = mode == 1 ? 3u : mode == 2 ? 4u : 0u;

        assert(!pthread_mutex_init(&g.hw.fence_lock, NULL));
        g.completion_override = (mode == 3 ? 18u : 17u) | (7u << 8) | (1u << 16) |
            (status << 11) | ((!status && mode != 8) ? (1u << 15) : 0u);
        g.joint_words[1] |= status;
        if (mode == 1) {
            g.joint_words[1] |= 6u << 3;
            g.joint_words[2] = 0xfedcba00u;
        }
        if (mode == 4)
            g.joint_words[4]++;
        if (mode == 5)
            g.joint_words[0] = OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC;
        if (mode == 6)
            g.joint_words[1] |= 1u << 7;
        atomic_store(&g.slot, 1);
        atomic_store(&g.status_observers, 2);
        assert(opengpu_hw_unified_drain(&g) && !g.hw.joint_pending_completion);
        assert(atomic_load(&g.pops) == 1 && fence.signals == 1 && fence.puts == 1);
        assert(fence.error == (mode == 0 ? 0 : mode == 2 ? -ECANCELED : -EIO));
        assert(result.valid == (mode <= 2));
        if (result.valid) {
            assert(g.joint_reads == 16 && result.status == status && result.clear_only);
            assert(result.context == 42 && result.pass == 8 && result.generation == 9);
            assert(result.fault_stage == (mode == 1 ? 6u : 0u));
            assert(result.fault_address == (mode == 1 ? 0xfedcba00u : 0u));
            assert(result.read_bytes == 0x100000040ull && result.captured_entries == 10 &&
                result.retired_ranges == 11 && result.retired_patches == ((13ull << 32) | 12) &&
                result.published == ((15ull << 32) | 14) && result.stored_bytes == ((17ull << 32) | 16) &&
                result.resolved_bytes == ((19ull << 32) | 18));
        } else {
            assert(g.joint_reads == (mode == 4 || mode == 6 ? 12u : mode == 5 ? 1u : 0u));
            assert(g.recorded_fault_flags & OPENGPU_FAULT_BYTES_MISMATCH);
        }
        assert(!g.hw.last_fault.tessellation_valid && !g.hw.last_fault.native_valid);
        assert(!pthread_mutex_destroy(&g.hw.fence_lock));
    }
}

struct worker { struct opengpu_device *g; atomic_int *ready, *go; bool result, abort; };
struct quiesce_worker { struct opengpu_device *g; atomic_bool done; int result; };
static void *quiesce_timeout(void *arg)
{
    struct quiesce_worker *w = arg;
    w->result = opengpu_hw_unified_quiesce_timeout(w->g);
    atomic_store(&w->done, true);
    return NULL;
}
static void *drain_immediately(void *arg)
{
    struct opengpu_device *g = arg;
    while (!atomic_load(&g->doorbells)) sched_yield();
    assert(opengpu_hw_unified_drain(g));
    return NULL;
}
static void *run(void *arg)
{
    struct worker *w = arg;
    atomic_fetch_add(w->ready, 1);
    while (!atomic_load(w->go))
        sched_yield();
    if (w->abort)
        opengpu_hw_abort(w->g, -ECANCELED);
    else
        w->result = opengpu_hw_unified_drain(w->g);
    return NULL;
}

int main(void)
{
    /* This is the production drain body, including its held-slot lock and POP.
     * A native source-profile owner is necessary even on a large aperture. */
    for (unsigned scenario = 0; scenario < 11; ++scenario) {
        struct opengpu_device g = {0}; struct dma_fence f = {0};
        assert(!pthread_mutex_init(&g.hw.fence_lock, NULL));
        g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
        g.hw.regs_size = 0x500;
        g.hw.unified_fence = &f; g.hw.unified_command_id = 17;
        g.hw.unified_opcode = GPU_UCMD_OP_NATIVE_BINNED_RENDER;
        g.hw.native_pending_owner = &g;
        g.hw.native_sources.available = true;
        g.hw.native_context = 7; g.hw.native_pass = 8; g.hw.native_generation = 9;
        g.native_words[0] = OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC;
        g.native_words[6] = 7; g.native_words[7] = 8; g.native_words[8] = 9;
        g.completion_override = 17u | (7u << 8) | (1u << 15);
        g.completion_data = 128;
        if (scenario == 1) g.hw.native_pending_owner = NULL; /* old GBJ */
        if (scenario == 2) g.hw.native_sources.available = false;
        if (scenario == 3) g.native_words[8] = 10; /* wrong generation */
        if (scenario == 4) g.completion_data = 65;
        if (scenario == 5) { /* legal early error can have no decoded identity */
            g.completion_override = 17u | (7u << 8) | (3u << 11);
            g.native_words[1] = 3; g.native_words[6] = g.native_words[7] = g.native_words[8] = 0;
        }
        if (scenario == 6) g.completion_override = 18u | (7u << 8) | (1u << 15);
        if (scenario == 7) g.hw.regs_size = OPENGPU_NATIVE_BINNED_COMPLETION_BASE + 44;
        if (scenario == 8) { g.hw.native_pending_owner = NULL; g.completion_data = 0; }
        if (scenario == 9) g.native_words[11] = 1;
        if (scenario == 10) g.completion_data = 1ULL << 33; /* bounded scan+execution telemetry */
        atomic_store(&g.slot, 1);
        assert(opengpu_hw_unified_drain(&g));
        bool success = scenario == 0 || scenario == 8 || scenario == 10;
        if (f.error != (success ? 0 : -EIO) || f.signals != 1 || f.puts != 1)
            fprintf(stderr,"native scenario=%u error=%d flags=%u reads=%u signals=%d puts=%d\n",scenario,f.error,g.recorded_fault_flags,g.native_reads,f.signals,f.puts);
        assert(f.error == (success ? 0 : -EIO) && f.signals == 1 && f.puts == 1);
        unsigned reads = scenario == 1 || scenario == 2 || scenario == 4 || scenario == 6 ||
            scenario == 7 || scenario == 8 ? 0 : 12;
        assert(g.native_reads == reads && atomic_load(&g.pops) == 1);
        assert(!g.hw.native_pending_owner && !opengpu_hw_unified_drain(&g));
        assert(g.hw.last_fault.native_valid == (scenario == 5));
        if (scenario == 5) assert(g.hw.last_fault.native.append_read_bytes == 128 &&
            g.hw.last_fault.native.info.words[1] == 3);
        assert(!pthread_mutex_destroy(&g.hw.fence_lock));
    }
    /* Actual completion drain: successful visibility queries carry flags in
     * the bytes window, unlike ordinary DMA byte counts. */
    for (u32 opcode = GPU_UCMD_OP_QUERY_BEGIN; opcode <= GPU_UCMD_OP_QUERY_RESULT; opcode++) {
        const u64 data[] = { 0, 2, 3, 1, 4, 1ull << 32 };
        for (unsigned i = 0; i < sizeof(data)/sizeof(data[0]); i++) {
            struct opengpu_device g = {0}; struct dma_fence f = {0};
            assert(pthread_mutex_init(&g.hw.fence_lock, NULL) == 0);
            g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
            g.hw.unified_fence = &f; g.hw.unified_command_id = 17;
            g.hw.unified_opcode = opcode; g.hw.unified_expected_bytes = 64;
            g.completion_override = 17u | ((opcode & 7u) << 8) |
                (1u << 15) | ((opcode >> 3) << 16);
            g.completion_data = data[i]; atomic_store(&g.slot, 1);
            assert(opengpu_hw_unified_drain(&g));
            assert(f.signals == 1 && f.puts == 1);
            assert(f.error == (i < 3 ? 0 : -EIO));
            assert(!!(g.recorded_fault_flags & OPENGPU_FAULT_BYTES_MISMATCH) == (i >= 3));
            assert(pthread_mutex_destroy(&g.hw.fence_lock) == 0);
        }
    }
    {
        struct opengpu_device g = {0}; struct dma_fence f = {0};
        assert(pthread_mutex_init(&g.hw.fence_lock, NULL) == 0);
        g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
        g.hw.unified_fence = &f; g.hw.unified_command_id = 17;
        g.hw.unified_opcode = GPU_UCMD_OP_COPY; g.hw.unified_expected_bytes = 64;
        g.completion_override = 17u | (GPU_UCMD_OP_COPY << 8) | (1u << 15);
        g.completion_data = 63; atomic_store(&g.slot, 1);
        assert(opengpu_hw_unified_drain(&g) && f.error == -EIO);
        assert(g.recorded_fault_flags & OPENGPU_FAULT_BYTES_MISMATCH);
        assert(pthread_mutex_destroy(&g.hw.fence_lock) == 0);
    }
    {
        struct opengpu_device g = {0};
        struct dma_fence f = {0}, other = {0};
        struct quiesce_worker wait = { .g = &g };
        pthread_t irq, waiter;
        assert(pthread_mutex_init(&g.hw.fence_lock, NULL) == 0);
        g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
        g.hw.unified_fence = &f;
        g.hw.unified_command_id = 17;
        g.hw.unified_opcode = 7;
        g.completion_override = 17u | (7u << 8) | (1u << 15);
        g.hw.timeout_work = 100;
        assert(opengpu_hw_unified_quiesce_timeout(&g) == -EBUSY);
        assert(g.hw.timeout_work == 100);
        g.hw.unified_fence = NULL;
        g.hw.active_fence = &f;
        assert(opengpu_hw_unified_quiesce_timeout(&g) == -EBUSY);
        assert(g.hw.timeout_work == 100);
        g.hw.active_fence = NULL;
        g.hw.aborting = 1;
        assert(opengpu_hw_unified_quiesce_timeout(&g) == -EBUSY);
        assert(g.hw.timeout_work == 100);
        g.hw.aborting = 0;
        atomic_store(&block_cancel, true);
        assert(pthread_create(&waiter, NULL, quiesce_timeout, &wait) == 0);
        while (!atomic_load(&cancel_entered)) sched_yield();
        assert(!atomic_load(&wait.done));
        atomic_store(&release_cancel, true);
        assert(pthread_join(waiter, NULL) == 0 && wait.result == 0);
        atomic_store(&block_cancel, false);
        assert(g.hw.timeout_work == 0);
        g.hw.unified_fence = &f;
        opengpu_hw_unified_kick(&g, &other);
        g.hw.aborting = 1;
        opengpu_hw_unified_kick(&g, &f);
        assert(atomic_load(&g.doorbells) == 0 && g.hw.timeout_work == 0);
        g.hw.aborting = 0;
        assert(pthread_create(&irq, NULL, drain_immediately, &g) == 0);
        opengpu_hw_unified_kick(&g, &f);
        assert(pthread_join(irq, NULL) == 0);
        assert(atomic_load(&g.doorbells) == 1 && f.signals == 1 && f.puts == 1);
        assert(__atomic_load_n(&g.hw.timeout_work, __ATOMIC_SEQ_CST) == 0);
        assert(pthread_mutex_destroy(&g.hw.fence_lock) == 0);
    }
    /* Explicitly exercise both expiry and cancellation after the hardware
     * slot has been consumed but before the delayed fence has signaled. */
    for (int scenario = 0; scenario < 4; scenario++) {
        bool cancel = scenario & 1;
        bool success = scenario & 2;
        struct opengpu_device g = {0};
        struct dma_fence f = {0};
        assert(pthread_mutex_init(&g.hw.fence_lock, NULL) == 0);
        g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
        g.hw.unified_fence = &f;
        g.hw.unified_command_id = 17;
        g.hw.unified_opcode = success ? 7 : 15;
        if (success) g.completion_override = 17u | (7u << 8) | (1u << 15);
        g.hw.unified_completion_delay_ms = 50;
        atomic_store(&g.slot, 1);
        assert(opengpu_hw_unified_drain(&g));
        assert(g.hw.active_fence == &f && f.signals == 0 && f.puts == 0);
        assert(__atomic_load_n(&g.hw.completion_work, __ATOMIC_SEQ_CST) == 50);
        if (cancel)
            opengpu_hw_abort(&g, -ECANCELED);
        else
            opengpu_hw_complete(&g, 0);
        opengpu_hw_complete(&g, 0); /* a late worker cannot signal twice */
        assert(f.signals == 1 && f.puts == 1);
        assert(f.error == (cancel ? -ECANCELED : success ? 0 : -EIO));
        assert(pthread_mutex_destroy(&g.hw.fence_lock) == 0);
    }
    for (int i = 0; i < 192; i++) {
        bool joint = i >= 128;
        bool native = i >= 64 && !joint;
        struct opengpu_device g = {0};
        struct dma_fence f = {0};
        struct opengpu_tessellation_joint_completion joint_result = {0};
        pthread_t a, b;
        atomic_int ready = 0, go = 0;
        struct worker x = {&g, &ready, &go, false, false};
        struct worker y = {&g, &ready, &go, false, (i % 32) < 16};
        assert(pthread_mutex_init(&g.hw.fence_lock, NULL) == 0);
        g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
        g.hw.regs_size = joint ? 0x550 : 0x1b0;
        g.hw.unified_fence = &f;
        g.hw.unified_command_id = 17;
        g.hw.unified_opcode = native ? 7 : 15;
        g.hw.unified_completion_delay_ms = (i % 64) >= 32 ? 50 : 0;
        if (native) {
            g.hw.native_pending_owner = &g; g.hw.native_sources.available = true;
            g.native_words[0] = OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC;
            g.native_words[1] = 3; g.native_words[6] = 42;
            g.completion_override = 17u | (7u << 8) | (3u << 11);
        }
        if (joint) {
            g.hw.joint_pending_completion = &joint_result;
            g.hw.native_context = 42;
            g.joint_words[0] = OPENGPU_TESS_JOINT_HOST_COMPLETION_MAGIC;
            g.joint_words[1] = 3u | (6u << 3);
            g.joint_words[2] = 0x1240u;
            g.joint_words[3] = 42;
        }
        g.words[0] = OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC;
        g.words[1] = 3;
        g.words[6] = 42;
        atomic_store(&g.slot, 1);
        assert(pthread_create(&a, NULL, run, &x) == 0);
        assert(pthread_create(&b, NULL, run, &y) == 0);
        while (atomic_load(&ready) != 2)
            sched_yield();
        atomic_store(&go, 1);
        assert(pthread_join(a, NULL) == 0);
        assert(pthread_join(b, NULL) == 0);
        if (g.hw.active_fence) {
            assert((i % 64) >= 32 && !y.abort && f.signals == 0 && f.puts == 0);
            assert(__atomic_load_n(&g.hw.completion_work, __ATOMIC_SEQ_CST) == 50);
            opengpu_hw_complete(&g, 0);
        }
        bool captured = joint ? (joint_result.valid && joint_result.fault_address == 0x1240u &&
            g.joint_reads == 16) : native ? (g.hw.last_fault.native_valid &&
            g.hw.last_fault.native.info.words[6] == 42) :
            (g.hw.last_fault.tessellation_valid && g.hw.last_fault.tessellation.context == 42);
        if (atomic_load(&g.pops) != 1 || atomic_load(&g.reads_after_pop) != 0 ||
            f.signals != 1 || f.puts != 1 || f.error == 0 ||
            (!y.abort && (!captured || x.result == y.result)) ||
            (y.abort && x.result && !captured)) {
            fprintf(stderr, "FAIL pops=%d reads_after_pop=%d signals=%d puts=%d snapshot=%d results=%d,%d\n",
                atomic_load(&g.pops), atomic_load(&g.reads_after_pop), f.signals,
                f.puts, g.hw.last_fault.tessellation_valid, x.result, y.result);
            return 1;
        }
        assert(pthread_mutex_destroy(&g.hw.fence_lock) == 0);
    }
    test_joint_profile();
    test_joint_completion();
    puts("completion race: PASS");
    return 0;
}
