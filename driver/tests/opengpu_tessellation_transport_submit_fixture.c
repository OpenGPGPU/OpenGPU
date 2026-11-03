/* The production command writer and tessellation transport, with mock MMIO. */
#define main opengpu_compute_transport_regressions
#include "opengpu_compute_texture_submit_fixture.c"
#undef main
#include "opengpu_tessellation_job_validate.h"

struct opengpu_buffer { void *cpu; u64 dma; size_t size; };
struct opengpu_tessellation_scope { u32 flags, program, cookie; };
static unsigned barriers, invalidations;
static int fail_invalidation, execution_busy;
static const struct opengpu_buffer *invalidated[8];
#define mutex_lock(p) do { assert(*(p) == 0); *(p) = 1; } while (0)
#define mutex_unlock(p) do { assert(*(p) == 1); *(p) = 0; } while (0)
#define dma_wmb() (barriers++)
static int opengpu_hw_invalidate_buffer_locked(struct opengpu_device *g,
                                                const struct opengpu_buffer *b)
{
    assert(g->hw.submit_lock && barriers && invalidations < 8);
    invalidated[invalidations++] = b;
    return fail_invalidation == (int)invalidations ? -EIO : 0;
}
static bool opengpu_hw_execution_busy(struct opengpu_device *g)
{ (void)g; return execution_busy; }
#include "tessellation_under_test.inc"

static void put(unsigned char *p, unsigned word, u32 value)
{
    for (unsigned i = 0; i < 4; ++i)
        p[word * 4 + i] = (unsigned char)(value >> (i * 8));
}
static void release_fence(struct opengpu_device *g, struct dma_fence *f)
{
    assert(f && f == g->hw.unified_fence);
    dma_fence_put(f);
    g->hw.unified_fence = NULL;
}

int main(void)
{
    unsigned char bytes[OPENGPU_NATIVE_TESSELLATION_JOB_BYTES] = {0};
    struct opengpu_device g = {0};
    struct opengpu_buffer job = { .cpu = bytes, .dma = 0x1000, .size = sizeof(bytes) };
    struct opengpu_buffer frame = { .cpu = &g, .dma = 0x2000, .size = 2048 };
    struct opengpu_buffer code = { .cpu = &g, .dma = 0x3000, .size = 64 };
    const struct opengpu_buffer *snapshots[] = { &frame, &code };
    struct opengpu_tessellation_scope scope = {
        .flags = GPU_UCMD_FLAG_RASTER_DISCARD | GPU_UCMD_FLAG_FEEDBACK_CAPTURE,
        .program = 9, .cookie = 11,
    };
    struct dma_fence *f = (struct dma_fence *)&g;
    const u64 end = 0x100000000ull;
    const u32 base = 0xfffffe00u, context = 0x71000007u;

    assert(opengpu_compute_transport_regressions() == 0);
    g.regs[GPU_REG_UCMD_STATUS / 4] = GPU_UCMD_STATUS_READY;
    put(bytes, 0, OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC);
    put(bytes, 1, sizeof(bytes)); put(bytes, 2, OPENGPU_NATIVE_TESSELLATION_JOB_VERSION);
    put(bytes, 3, 16); put(bytes, 4, 16); put(bytes, 5, 16); put(bytes, 6, 4); put(bytes, 7, 4);
    put(bytes, 21, 0x4000); put(bytes, 22, 0x4800);
    put(bytes, 24, context); put(bytes, 25, 9); put(bytes, 26, 8);

    assert(opengpu_hw_tessellation_async(&g, &job, base, end, snapshots, 2, NULL, NULL, &f) == -EOPNOTSUPP);
    assert(!f && !barriers && !invalidations && !g.kicks);
    g.hw.tessellation_transport = true;
    assert(opengpu_hw_tessellation_async(&g, &job, base + 1, end, snapshots, 2, NULL, NULL, &f) == -EINVAL);
    assert(opengpu_hw_tessellation_async(&g, &job, base, end + 1, snapshots, 2, NULL, NULL, &f) == -EINVAL);
    assert(opengpu_hw_tessellation_async(&g, &job, base, end, NULL, 2, NULL, NULL, &f) == -EINVAL);
    assert(opengpu_hw_tessellation_async(&g, &job, base, end, snapshots, 2, &scope, NULL, &f) == -EOPNOTSUPP);
    scope.flags = GPU_UCMD_FLAG_WAIT_EVENT;
    assert(opengpu_hw_tessellation_async(&g, &job, base, end, snapshots, 2, &scope, NULL, &f) == -EINVAL);
    put(bytes, 0, 0);
    assert(opengpu_hw_tessellation_async(&g, &job, base, end, snapshots, 2, NULL, NULL, &f) == -EINVAL);
    assert(!f && !barriers && !invalidations && !g.kicks);
    put(bytes, 0, OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC);

    assert(opengpu_hw_tessellation_async(&g, &job, base, end, snapshots, 2, NULL, NULL, &f) == 0);
    assert(barriers == 1 && invalidations == 3 && invalidated[0] == &job &&
           invalidated[1] == &frame && invalidated[2] == &code && g.kicks == 1);
    assert(g.regs[GPU_REG_UCMD_OPCODE / 4] == GPU_UCMD_OP_TESSELLATION);
    assert(g.regs[GPU_REG_UCMD_SOURCE / 4] == base && !g.regs[GPU_REG_UCMD_DESTINATION / 4]);
    assert(g.regs[GPU_REG_UCMD_BYTES / 4] == 512 && g.regs[GPU_REG_UCMD_PATTERN / 4] == 1);
    assert(g.regs[GPU_REG_UCMD_WIDTH / 4] == context && !g.regs[GPU_REG_UCMD_FLAGS / 4]);
    assert(!g.regs[GPU_REG_UCMD_HEIGHT / 4] && !g.regs[GPU_REG_UCMD_SOURCE_STRIDE / 4]);
    assert(g.hw.unified_expected_bytes == 0 && !g.hw.submit_lock);
    release_fence(&g, f); f = NULL;

    g.hw.tessellation_feedback_transport = true;
    scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD | GPU_UCMD_FLAG_FEEDBACK_CAPTURE;
    invalidations = 0;
    assert(opengpu_hw_tessellation_async(&g, &job, 0x4000, 0x4200, snapshots, 2, &scope, NULL, &f) == 0);
    assert(g.regs[GPU_REG_UCMD_SOURCE / 4] == 0x4000 && g.regs[GPU_REG_UCMD_DESTINATION / 4] == 0x4200);
    assert(!g.regs[GPU_REG_UCMD_PATTERN / 4] && g.regs[GPU_REG_UCMD_WIDTH / 4] == context);
    assert(g.regs[GPU_REG_UCMD_FLAGS / 4] == scope.flags && g.regs[GPU_REG_UCMD_HEIGHT / 4] == 9);
    assert(g.regs[GPU_REG_UCMD_SOURCE_STRIDE / 4] == 11 && g.kicks == 2);
    release_fence(&g, f); f = NULL;

    invalidations = 0; fail_invalidation = 2;
    assert(opengpu_hw_tessellation_async(&g, &job, 0x4000, 0x4200, snapshots, 2, NULL, NULL, &f) == -EIO);
    assert(!f && g.kicks == 2 && !g.hw.submit_lock);
    fail_invalidation = 0; invalidations = 0; execution_busy = 1;
    assert(opengpu_hw_tessellation_async(&g, &job, 0x4000, 0x4200, snapshots, 2, NULL, NULL, &f) == -EBUSY);
    assert(!f && g.kicks == 2 && !g.hw.submit_lock);
    execution_busy = 0; invalidations = 0; g.idle_error = -ETIMEDOUT;
    assert(opengpu_hw_tessellation_async(&g, &job, 0x4000, 0x4200, snapshots, 2, NULL, NULL, &f) == -ETIMEDOUT);
    assert(!f && g.kicks == 2 && !g.hw.submit_lock);
    g.idle_error = 0;
    const u32 other_domains[] = { OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC,
                                  OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC };
    for (unsigned i = 0; i < sizeof(other_domains) / sizeof(other_domains[0]); ++i) {
        put(bytes, 0, other_domains[i]); invalidations = 0;
        assert(opengpu_hw_tessellation_async(&g, &job, 0x4000, 0x4200, snapshots, 2, NULL, NULL, &f) == 0);
        assert(g.regs[GPU_REG_UCMD_WIDTH / 4] == context);
        release_fence(&g, f); f = NULL;
    }
    assert(opengpu_hw_unified_submit_locked(&g, NULL, NULL, GPU_UCMD_OP_INVALIDATE,
        0x1000, 0, 64, 0, 0, 64, 0, 0, 0, 64, NULL, 0, &f) == 0);
    assert(g.regs[GPU_REG_UCMD_WIDTH / 4] == 64 && g.hw.unified_expected_bytes == 64);
    release_fence(&g, f); f = NULL;
    struct opengpu_command_events invalid = { .flags = GPU_UCMD_FLAG_WAIT_EVENT, .wait_event = 7 };
    const u32 kicks = g.kicks;
    assert(opengpu_hw_unified_submit_locked(&g, NULL, &invalid, GPU_UCMD_OP_TESSELLATION,
        0x4000, 0x4200, 512, 0, 0, context, 0, 0, 0, 0, NULL, 0, &f) == -EINVAL);
    invalid.flags = GPU_UCMD_FLAG_RASTER_DISCARD; invalid.wait_event = 0;
    assert(opengpu_hw_unified_submit_locked(&g, NULL, &invalid, GPU_UCMD_OP_INVALIDATE,
        0x1000, 0, 64, 0, 0, 64, 0, 0, 0, 64, NULL, 0, &f) == -EINVAL);
    assert(!f && g.kicks == kicks);
    return 0;
}
