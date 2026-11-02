/* Production unified submit, with mock MMIO, VM and fence primitives. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include "opengpu_compute_texture_command.h"
typedef uint64_t u64;
#define GFP_KERNEL 0
#define READ_ONCE(v) (v)
#define lower_32_bits(v) ((u32)(v))
#define spin_lock_irqsave(lock, flags) do { (void)(lock); (flags) = 0; } while (0)
#define spin_unlock_irqrestore(lock, flags) do { (void)(lock); (void)(flags); } while (0)
struct dma_fence { int unused; };
struct opengpu_fence { struct dma_fence base; };
struct opengpu_vm { int unused; };
struct opengpu_kernel_launch {
    u64 kernel_pc, kernarg; u32 grid[3], local[3]; bool texture_enabled;
    struct opengpu_compute_texture_command_context texture;
};
struct opengpu_command_events { u32 flags, wait_event, signal_event; };
struct opengpu_device {
    struct { bool compute_texture_transport, wedged, reset_pending;
        struct dma_fence *unified_fence, *active_fence;
        u32 aborting, unified_command_id, unified_opcode, unified_completion_delay_ms;
        u64 unified_expected_bytes, fence_context, fence_seqno; int fence_lock;
    } hw;
    u32 regs[1024], texture_writes, kicks, captured[5];
    bool activated;
};
static const int opengpu_fence_ops;
static void *kzalloc(size_t n, int flags) { (void)flags; return calloc(1, n); }
static void dma_fence_init(struct dma_fence *f, const int *o, int *l, u64 c, u64 s)
{ (void)f; (void)o; (void)l; (void)c; (void)s; }
static struct dma_fence *dma_fence_get(struct dma_fence *f) { return f; }
static void dma_fence_put(struct dma_fence *f) { free(f); }
static int opengpu_hw_unified_quiesce_timeout(struct opengpu_device *g) { (void)g; return 0; }
static int opengpu_hw_activate_vm_locked(struct opengpu_device *g, const struct opengpu_vm *v)
{ (void)v; g->activated = true; return 0; }
static u32 opengpu_reg_read(struct opengpu_device *g, u32 a) { return g->regs[a / 4]; }
static void opengpu_reg_write(struct opengpu_device *g, u32 a, u32 v)
{ assert(g->activated); g->regs[a / 4] = v; if (a >= 0x480 && a < 0x494) g->texture_writes++; }
static void opengpu_mmio_write_callback(void *g, u32 a, u32 v) { opengpu_reg_write(g, a, v); }
static u32 opengpu_next_job_id(struct opengpu_device *g) { (void)g; return 9; }
static void opengpu_hw_unified_kick(struct opengpu_device *g, struct dma_fence *f)
{ (void)f; for (u32 i = 0; i < 5; ++i) g->captured[i] = g->regs[(0x480 / 4) + i]; g->kicks++; }
#include "submit_under_test.inc"
static int submit(struct opengpu_device *g, const struct opengpu_kernel_launch *launch, u32 opcode)
{
    struct dma_fence *f = NULL;
    int ret = opengpu_hw_unified_submit_locked(g, launch, NULL, opcode,
        0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, 0, &f);
    if (!ret) { assert(f == g->hw.unified_fence); dma_fence_put(f); g->hw.unified_fence = NULL; }
    else assert(!f);
    return ret;
}
int main(void)
{
    struct opengpu_device g = {0};
    struct opengpu_kernel_launch l = { .texture_enabled = true,
        .texture = { .version = 1, .root = 0x1000, .context = 7, .pass = 11, .generation = 13 } };
    g.regs[GPU_REG_UCMD_STATUS / 4] = GPU_UCMD_STATUS_READY;
    assert(submit(&g, &l, GPU_UCMD_OP_KERNEL) == -EINVAL && !g.kicks && !g.texture_writes);
    g.hw.compute_texture_transport = true; l.texture.root++;
    assert(submit(&g, &l, GPU_UCMD_OP_KERNEL) == -EINVAL && !g.kicks && !g.texture_writes);
    l.texture.root--;
    assert(submit(&g, &l, GPU_UCMD_OP_INVALIDATE) == -EINVAL && !g.kicks && !g.texture_writes);
    g.hw.reset_pending = true;
    assert(submit(&g, &l, GPU_UCMD_OP_KERNEL) == -EBUSY && !g.kicks && !g.texture_writes);
    g.hw.reset_pending = false;
    assert(submit(&g, &l, GPU_UCMD_OP_KERNEL) == 0);
    assert(g.kicks == 1 && g.texture_writes == 5 && g.captured[0] == 0x101);
    assert(g.captured[1] == 0x1000 && g.captured[2] == 7 && g.captured[3] == 11 && g.captured[4] == 13);
    l.texture.context = 42;
    assert(submit(&g, &l, GPU_UCMD_OP_KERNEL) == 0 && g.captured[2] == 42);
    assert(submit(&g, NULL, GPU_UCMD_OP_KERNEL) == 0 && g.captured[0] == 0);
    assert(g.kicks == 3 && g.texture_writes == 11);
    g.hw.compute_texture_transport = false;
    assert(submit(&g, NULL, GPU_UCMD_OP_KERNEL) == 0 && g.texture_writes == 11);
    return 0;
}
