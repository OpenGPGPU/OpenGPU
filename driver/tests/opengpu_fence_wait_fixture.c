/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <errno.h>
#include <limits.h>

#define ERESTARTSYS 512
#define min_t(type, a, b) ((type)(a) < (type)(b) ? (type)(a) : (type)(b))
#define max_t(type, a, b) ((type)(a) > (type)(b) ? (type)(a) : (type)(b))
#define msecs_to_jiffies(ms) ((unsigned long)(ms))
#define jiffies_to_msecs(ticks) ((unsigned long)(ticks))
#define time_after_eq(a, b) ((long)((a) - (b)) >= 0)
#define current NULL
static unsigned long jiffies;
static unsigned int fence_slice_ms;
static bool pending;
struct opengpu_device { int ticks; unsigned long tick_cost; bool tick_signals; };
struct dma_fence { bool signaled; int status; };
struct event { long result; unsigned long advance; bool signal, set_pending; };
static struct event events[8];
static unsigned long requests[8];
static unsigned int event_count, wait_count;
static struct dma_fence *ticked_fence;

static bool dma_fence_is_signaled(struct dma_fence *f) { return f->signaled; }
static int dma_fence_get_status(struct dma_fence *f) { return f->status; }
static bool signal_pending(void *task) { (void)task; return pending; }
static void cond_resched(void) { }
static void opengpu_hw_progress_tick(struct opengpu_device *g)
{
    g->ticks++;
    jiffies += g->tick_cost;
    if (g->tick_signals)
        ticked_fence->signaled = true;
}
static long dma_fence_wait_timeout(struct dma_fence *f, bool interruptible,
                                   unsigned long timeout)
{
    struct event e;
    (void)interruptible;
    assert(wait_count < event_count);
    requests[wait_count] = timeout;
    e = events[wait_count++];
    jiffies += e.advance;
    if (e.signal) f->signaled = true;
    if (e.set_pending) pending = true;
    return e.result;
}
#include "fence_wait_under_test.inc"

static void reset(struct opengpu_device *g, struct dma_fence *f, unsigned slice)
{
    *g = (struct opengpu_device){0};
    *f = (struct dma_fence){.status = 1};
    jiffies = 0;
    pending = false;
    fence_slice_ms = slice;
    event_count = wait_count = 0;
    ticked_fence = f;
}
static void add(long result, unsigned long advance, bool signal, bool set_pending)
{
    events[event_count++] = (struct event){result, advance, signal, set_pending};
}
int main(void)
{
    struct opengpu_device gpu;
    struct dma_fence fence;

    reset(&gpu, &fence, 0);
    add(1, 1, true, true);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 5, false) == 0);
    assert(wait_count == 1 && gpu.ticks == 0);
    reset(&gpu, &fence, 0);
    add(-ERESTARTSYS, 0, false, true);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 5, true) == -ERESTARTSYS);
    reset(&gpu, &fence, 4);
    add(1, 1, true, false);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 2, false) == 0);
    assert(requests[0] == 2 && gpu.ticks == 0);
    reset(&gpu, &fence, 4);
    add(0, 4, false, true);
    add(2, 2, true, false);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 10, false) == 0);
    assert(wait_count == 2 && gpu.ticks == 1);
    reset(&gpu, &fence, 4);
    add(0, 4, false, true);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 10, true) == -ERESTARTSYS);
    reset(&gpu, &fence, 4);
    gpu.tick_signals = true;
    add(0, 4, false, true);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 10, true) == 0);
    reset(&gpu, &fence, 4);
    gpu.tick_cost = 3;
    add(0, 4, false, false);
    add(0, 4, false, false);
    add(0, 1, false, false);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 15, false) == -ETIMEDOUT);
    assert(wait_count == 3 && requests[0] == 4 && requests[1] == 4 && requests[2] == 1);
    reset(&gpu, &fence, 4);
    gpu.tick_signals = true;
    add(0, 4, false, false);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 4, false) == -ETIMEDOUT);
    assert(gpu.ticks == 0);
    reset(&gpu, &fence, 4);
    add(0, 4, true, false);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 4, false) == 0);
    assert(gpu.ticks == 0);
    reset(&gpu, &fence, 4);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 0, false) == -ETIMEDOUT);
    assert(wait_count == 0);
    reset(&gpu, &fence, 4);
    fence.signaled = true;
    fence.status = -EIO;
    assert(opengpu_hw_fence_wait(&gpu, &fence, 0, false) == -EIO);
    reset(&gpu, &fence, 4);
    jiffies = ULONG_MAX - 1;
    add(1, 1, true, false);
    assert(opengpu_hw_fence_wait(&gpu, &fence, 4, false) == 0);
    puts("fence wait: signal semantics, completion and deadline PASS");
    return 0;
}
