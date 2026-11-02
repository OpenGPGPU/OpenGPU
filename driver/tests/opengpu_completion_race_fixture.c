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
typedef uint64_t u64;
#define OPENGPU_FAULT_ABORTED 32u
#define OPENGPU_FAULT_TIMEOUT 64u
#define OPENGPU_FAULT_RESET_ISSUED 128u
#define OPENGPU_FAULT_COMPLETION_ERROR 1u
#define OPENGPU_FAULT_COMMAND_ID_MISMATCH 2u
#define OPENGPU_FAULT_OPCODE_MISMATCH 4u
#define OPENGPU_FAULT_SUCCESS_MISMATCH 8u
#define OPENGPU_FAULT_BYTES_MISMATCH 16u
#define spin_lock_irqsave(lock,flags) do { (flags)=0; pthread_mutex_lock(lock); } while (0)
#define spin_unlock_irqrestore(lock,flags) do { (void)(flags); pthread_mutex_unlock(lock); } while (0)
#define dev_err(...) ((void)0)
struct dma_fence { int signals, error, puts; };
struct opengpu_device {
    struct {
        u32 capabilities;
        pthread_mutex_t fence_lock;
        unsigned long long regs_size;
        struct dma_fence *unified_fence;
        u32 unified_command_id, unified_opcode;
        u64 unified_expected_bytes;
        struct {
            bool tessellation_valid;
            struct opengpu_tessellation_completion tessellation;
        } last_fault;
        int timeout_work, completion_work, poll_work;
    } hw;
    void *dev;
    atomic_int slot, pops, reads_after_pop, status_observers;
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
        return 17u | (7u << 8) | (3u << 11) | (1u << 16);
    if (reg == GPU_REG_UCMD_COMPLETION_BYTES_LO || reg == GPU_REG_UCMD_COMPLETION_BYTES_HI)
        return 0;
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
    (void)error; (void)flags; (void)actual_id; (void)opcode; (void)expected_id;
    (void)expected_opcode; (void)status; (void)processed; (void)expected;
    g->hw.last_fault.tessellation_valid = false;
}
static void dma_fence_set_error(struct dma_fence *f, int error) { f->error = error; }
static void dma_fence_signal_locked(struct dma_fence *f) { f->signals++; }
static void dma_fence_put(struct dma_fence *f) { f->puts++; }
static void cancel_delayed_work(int *work) { (void)work; }
static void cancel_delayed_work_sync(int *work) { (void)work; }
static void opengpu_hw_unified_reset_request(struct opengpu_device *g) { (void)g; }
static void opengpu_hw_complete(struct opengpu_device *g, int error) { (void)g; (void)error; }
#include "drain_under_test.inc"

struct worker { struct opengpu_device *g; atomic_int *ready, *go; bool result, abort; };
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
    for (int i = 0; i < 32; i++) {
        struct opengpu_device g = {0};
        struct dma_fence f = {0};
        pthread_t a, b;
        atomic_int ready = 0, go = 0;
        struct worker x = {&g, &ready, &go, false, false};
        struct worker y = {&g, &ready, &go, false, i < 16};
        assert(pthread_mutex_init(&g.hw.fence_lock, NULL) == 0);
        g.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
        g.hw.regs_size = 0x1b0;
        g.hw.unified_fence = &f;
        g.hw.unified_command_id = 17;
        g.hw.unified_opcode = 15;
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
