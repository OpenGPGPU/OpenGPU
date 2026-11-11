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
        struct {
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
    u32 words[12];
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
    for (int i = 0; i < 64; i++) {
        struct opengpu_device g = {0};
        struct dma_fence f = {0};
        pthread_t a, b;
        atomic_int ready = 0, go = 0;
        struct worker x = {&g, &ready, &go, false, false};
        struct worker y = {&g, &ready, &go, false, (i % 32) < 16};
        assert(pthread_mutex_init(&g.hw.fence_lock, NULL) == 0);
        g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
        g.hw.regs_size = 0x1b0;
        g.hw.unified_fence = &f;
        g.hw.unified_command_id = 17;
        g.hw.unified_opcode = 15;
        g.hw.unified_completion_delay_ms = i >= 32 ? 50 : 0;
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
            assert(i >= 32 && !y.abort && f.signals == 0 && f.puts == 0);
            assert(__atomic_load_n(&g.hw.completion_work, __ATOMIC_SEQ_CST) == 50);
            opengpu_hw_complete(&g, 0);
        }
        if (atomic_load(&g.pops) != 1 || atomic_load(&g.reads_after_pop) != 0 ||
            f.signals != 1 || f.puts != 1 || f.error == 0 ||
            (!y.abort && (!g.hw.last_fault.tessellation_valid ||
                          g.hw.last_fault.tessellation.context != 42 || x.result == y.result)) ||
            (y.abort && x.result && (!g.hw.last_fault.tessellation_valid ||
                                      g.hw.last_fault.tessellation.context != 42))) {
            fprintf(stderr, "FAIL pops=%d reads_after_pop=%d signals=%d puts=%d snapshot=%d results=%d,%d\n",
                atomic_load(&g.pops), atomic_load(&g.reads_after_pop), f.signals,
                f.puts, g.hw.last_fault.tessellation_valid, x.result, y.result);
            return 1;
        }
        assert(pthread_mutex_destroy(&g.hw.fence_lock) == 0);
    }
    puts("completion race: PASS");
    return 0;
}
