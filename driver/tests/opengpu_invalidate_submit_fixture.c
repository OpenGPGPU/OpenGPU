/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>

typedef uint32_t u32;
typedef uint64_t u64;
#define GPU_CAP_UNIFIED_COMMANDS 1u
#define GPU_UCMD_OP_INVALIDATE 7u
#define OPENGPU_DRAW_WAIT_MS 100u
#define mutex_lock(m) assert(pthread_mutex_lock(m) == 0)
#define mutex_unlock(m) assert(pthread_mutex_unlock(m) == 0)
struct opengpu_command_events { int unused; };
struct opengpu_kernel_launch { int unused; };
struct opengpu_vm { int unused; };
struct dma_fence { int puts; };
struct opengpu_device {
    struct {
        unsigned capabilities;
        pthread_mutex_t submit_lock;
    } hw;
    atomic_bool busy, waiting, completing, release_completion;
    atomic_bool contender_attempting, contender_entered;
    int wait_error, fence_error, wait_calls, submissions;
    bool interposed;
    struct dma_fence fence;
};

static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{
    return atomic_load(&gpu->busy);
}

static int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu)
{
    assert(pthread_mutex_trylock(&gpu->hw.submit_lock) == EBUSY);
    gpu->wait_calls++;
    atomic_store(&gpu->waiting, true);
    while (atomic_load(&gpu->busy))
        sched_yield();
    return gpu->wait_error;
}

static int opengpu_hw_unified_submit_locked(
    struct opengpu_device *gpu, const struct opengpu_kernel_launch *launch,
    const struct opengpu_command_events *events, u32 opcode, u32 source,
    u32 destination, u32 bytes, u32 pattern, u32 sample_mode, u32 height,
    u32 source_stride, u32 destination_stride, u64 expected_bytes,
    const struct opengpu_vm *vm, struct dma_fence **out_fence)
{
    assert(pthread_mutex_trylock(&gpu->hw.submit_lock) == EBUSY);
    assert(!atomic_load(&gpu->busy));
    assert(!launch && !events && !vm && opcode == GPU_UCMD_OP_INVALIDATE);
    assert(source == 64 && destination == 0 && bytes == 64 && expected_bytes == 64);
    assert(!pattern && !sample_mode && !height && !source_stride && !destination_stride);
    gpu->interposed = atomic_load(&gpu->contender_entered);
    gpu->submissions++;
    *out_fence = &gpu->fence;
    return 0;
}

static int opengpu_hw_fence_wait(struct opengpu_device *gpu,
                                 struct dma_fence *fence,
                                 unsigned timeout_ms, bool interruptible)
{
    assert(pthread_mutex_trylock(&gpu->hw.submit_lock) == EBUSY);
    assert(fence == &gpu->fence && timeout_ms == OPENGPU_DRAW_WAIT_MS + 100);
    (void)interruptible;
    atomic_store(&gpu->completing, true);
    while (!atomic_load(&gpu->release_completion))
        sched_yield();
    return gpu->fence_error;
}

static void dma_fence_put(struct dma_fence *fence)
{
    fence->puts++;
}

#include "invalidate_submit_under_test.inc"

struct request { struct opengpu_device *gpu; int result; };
static void *wait_and_submit(void *arg)
{
    struct request *r = arg;
    r->result = opengpu_hw_invalidate_sync(r->gpu, 64, 64, true);
    return NULL;
}

static void *contend(void *arg)
{
    struct opengpu_device *gpu = arg;
    atomic_store(&gpu->contender_attempting, true);
    mutex_lock(&gpu->hw.submit_lock);
    atomic_store(&gpu->contender_entered, true);
    mutex_unlock(&gpu->hw.submit_lock);
    return NULL;
}

static void await(atomic_bool *flag)
{
    unsigned i;
    for (i = 0; i < 1000000 && !atomic_load(flag); i++)
        sched_yield();
    assert(atomic_load(flag));
}

int main(void)
{
    struct opengpu_device gpu = {0};
    struct dma_fence *fence = &gpu.fence;
    struct request r = {&gpu, -1};
    pthread_t waiter, competitor;

    assert(!pthread_mutex_init(&gpu.hw.submit_lock, NULL));
    atomic_init(&gpu.busy, true);
    atomic_init(&gpu.waiting, false);
    atomic_init(&gpu.completing, false);
    atomic_init(&gpu.release_completion, false);
    atomic_init(&gpu.contender_attempting, false);
    atomic_init(&gpu.contender_entered, false);
    gpu.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
    assert(opengpu_hw_invalidate_async(&gpu, 64, 64, NULL, NULL, &fence) == -EBUSY);
    assert(!fence && !gpu.submissions);
    assert(opengpu_hw_invalidate_async(&gpu, 64, 64, NULL, NULL, NULL) == -EINVAL);
    assert(opengpu_hw_invalidate_sync(&gpu, 63, 64, false) == -ERANGE);
    assert(opengpu_hw_invalidate_sync(&gpu, 0xffffffc0u, 128, false) == -ERANGE);
    gpu.hw.capabilities = 0;
    assert(opengpu_hw_invalidate_sync(&gpu, 64, 64, false) == -EOPNOTSUPP);
    gpu.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;

    assert(!pthread_create(&waiter, NULL, wait_and_submit, &r));
    await(&gpu.waiting);
    assert(!pthread_create(&competitor, NULL, contend, &gpu));
    await(&gpu.contender_attempting);
    assert(pthread_mutex_trylock(&gpu.hw.submit_lock) == EBUSY);
    assert(!atomic_load(&gpu.contender_entered));
    atomic_store(&gpu.busy, false);
    await(&gpu.completing);
    assert(pthread_mutex_trylock(&gpu.hw.submit_lock) == EBUSY);
    assert(!atomic_load(&gpu.contender_entered));
    atomic_store(&gpu.release_completion, true);
    assert(!pthread_join(waiter, NULL));
    assert(!pthread_join(competitor, NULL));
    assert(r.result == 0 && gpu.fence.puts == 1);
    assert(gpu.submissions == 1 && !gpu.interposed);
    assert(atomic_load(&gpu.contender_entered));

    /* The scheduler-facing form still submits without the idle waiter. */
    atomic_store(&gpu.contender_entered, false);
    fence = NULL;
    assert(opengpu_hw_invalidate_async(&gpu, 64, 64, NULL, NULL, &fence) == 0);
    assert(fence == &gpu.fence && gpu.submissions == 2);
    dma_fence_put(fence);
    gpu.wait_error = -ETIMEDOUT;
    assert(opengpu_hw_invalidate_sync(&gpu, 64, 64, false) == -ETIMEDOUT);
    assert(gpu.submissions == 2 && gpu.fence.puts == 2);

    /* A failed fence wait must attempt to drain before releasing the lock. */
    gpu.wait_error = 0;
    gpu.wait_calls = 0;
    gpu.fence_error = -EINTR;
    assert(opengpu_hw_invalidate_sync(&gpu, 64, 64, false) == -EINTR);
    assert(gpu.wait_calls == 2 && gpu.submissions == 3 && gpu.fence.puts == 3);
    assert(!pthread_mutex_destroy(&gpu.hw.submit_lock));
    puts("invalidate submission: busy, completion ordering, errors and ranges PASS");
    return 0;
}
