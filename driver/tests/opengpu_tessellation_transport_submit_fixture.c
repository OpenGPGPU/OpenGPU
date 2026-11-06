/* The production command writer and tessellation transport, with mock MMIO. */
#define main opengpu_compute_transport_regressions
#include "opengpu_compute_texture_submit_fixture.c"
#undef main
#include "opengpu_tessellation_job_validate.h"
#include <string.h>

struct opengpu_buffer { void *cpu; u64 dma; size_t size; };
struct opengpu_tessellation_scope { u32 flags, program, cookie; };
struct opengpu_tessellation_descriptors {
    const struct opengpu_buffer *job, *frame, *draws, *states;
    const struct opengpu_buffer *fragment_code;
    u32 fragment_code_va;
};
static unsigned barriers, invalidations;
static int fail_invalidation, execution_busy;
static const struct opengpu_buffer *invalidated[8];
static unsigned verifications;
static int fail_verification;
static struct { u64 va, pa; size_t size; const struct opengpu_vm *vm; } verified[4];
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
static int opengpu_mmu_verify_snapshot_locked(struct opengpu_device *g,
    const struct opengpu_vm *vm, u64 va, u64 pa, size_t size)
{
    assert(g->hw.submit_lock && g->idle_calls && !invalidations);
    if (verifications < 4) {
        verified[verifications].va = va; verified[verifications].pa = pa;
        verified[verifications].size = size; verified[verifications].vm = vm;
    }
    ++verifications;
    if ((int)verifications == fail_verification) return -EACCES;
    return !vm && va != pa ? -EFAULT : 0;
}
static int opengpu_mmu_verify_code_snapshot_locked(struct opengpu_device *g,
    const struct opengpu_vm *vm, u64 va, u64 pa, size_t size)
{
    return opengpu_mmu_verify_snapshot_locked(g, vm, va, pa, size);
}
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
    enum { DRAW_STRIDE = OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES + 64 };
    unsigned char bytes[OPENGPU_NATIVE_TESSELLATION_JOB_BYTES] = {0};
    unsigned char frame_bytes[OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES] = {0};
    unsigned char draw_bytes[DRAW_STRIDE + OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES] = {0};
    struct opengpu_device g = {0};
    struct opengpu_vm vm = {0};
    struct opengpu_buffer job = { .cpu = bytes, .dma = 0x1000, .size = sizeof(bytes) };
    struct opengpu_buffer frame = { .cpu = frame_bytes, .dma = 0x2000, .size = sizeof(frame_bytes) };
    struct opengpu_buffer draws = { .cpu = draw_bytes, .dma = 0x5000, .size = sizeof(draw_bytes) };
    struct opengpu_buffer code = { .cpu = &g, .dma = 0x3000, .size = 64 };
    const struct opengpu_buffer *snapshots[] = { &code };
    struct opengpu_tessellation_descriptors descriptors = { .job = &job, .frame = &frame };
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
    put(frame_bytes, 0, OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_MAGIC);
    put(frame_bytes, 1, sizeof(frame_bytes)); put(frame_bytes, 2, OPENGPU_NATIVE_TESSELLATION_JOB_VERSION);
    put(frame_bytes, 3, 16); put(frame_bytes, 4, 16); put(frame_bytes, 5, 16);
    put(frame_bytes, 6, 4); put(frame_bytes, 7, 4);
    put(frame_bytes, OPENGPU_TESS_FRAME_VERSION, 1);
    const unsigned int identity_words[] = { OPENGPU_TESS_FRAME_POOL_IDENTITY,
        OPENGPU_TESS_FRAME_ARENA_IDENTITY, OPENGPU_TESS_FRAME_COLOR_IDENTITY,
        OPENGPU_TESS_FRAME_STATE_IDENTITY };
    for (unsigned i = 0; i < sizeof(identity_words) / sizeof(identity_words[0]); ++i) {
        put(frame_bytes, identity_words[i], context);
        put(frame_bytes, identity_words[i] + 1, 9);
        put(frame_bytes, identity_words[i] + 2, 8);
    }

    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EOPNOTSUPP);
    assert(!f && !barriers && !invalidations && !g.kicks);
    g.hw.tessellation_transport = true;
    g.hw.tessellation_engines = 2;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base + 1, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end + 1, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, NULL, 2, NULL, &vm, &f) == -EINVAL);
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, &scope, &vm, &f) == -EOPNOTSUPP);
    scope.flags = GPU_UCMD_FLAG_WAIT_EVENT;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, &scope, &vm, &f) == -EINVAL);
    put(bytes, 0, 0);
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    assert(!f && !barriers && !invalidations && !g.kicks);
    put(bytes, 0, OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC);
    g.hw.tessellation_engines = 0;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    g.hw.tessellation_engines = 5;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    g.hw.tessellation_engines = 2;
    frame.size--;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    frame.size++;
    put(frame_bytes, 511, 1);
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    {
        struct opengpu_tessellation_job_view result, before;
        memset(&result, 0xa5, sizeof(result)); memcpy(&before, &result, sizeof(result));
        assert(!opengpu_tessellation_snapshots_precheck(bytes, sizeof(bytes), base, end,
            frame_bytes, sizeof(frame_bytes), NULL, 0, 2, &result));
        assert(!memcmp(&result, &before, sizeof(result)));
        assert(!opengpu_tessellation_snapshots_precheck(bytes, sizeof(bytes), base, end,
            frame_bytes, sizeof(frame_bytes), NULL, 0, 2, NULL));
    }
    put(frame_bytes, 511, 0);
    put(frame_bytes, OPENGPU_TESS_FRAME_ARENA_IDENTITY, context ^ 1u);
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    put(frame_bytes, OPENGPU_TESS_FRAME_ARENA_IDENTITY, context);
    put(frame_bytes, OPENGPU_TESS_FRAME_POOL_BASE, 0x4000);
    put(frame_bytes, OPENGPU_TESS_FRAME_POOL_LIMIT, 0x4040);
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    put(frame_bytes, OPENGPU_TESS_FRAME_POOL_BASE, 0);
    put(frame_bytes, OPENGPU_TESS_FRAME_POOL_LIMIT, 0);
    assert(!f && !barriers && !invalidations && !g.kicks);

    verifications = 0; fail_verification = 1;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EACCES);
    assert(!f && verifications == 1 && !barriers && !invalidations && !g.kicks);
    verifications = 0; fail_verification = 2;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == -EACCES);
    assert(!f && verifications == 2 && !barriers && !invalidations && !g.kicks);
    verifications = 0; fail_verification = 0;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, NULL, &f) == -EFAULT);
    assert(!f && !barriers && !invalidations && !g.kicks);
    verifications = 0;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, base, end, snapshots, 1, NULL, &vm, &f) == 0);
    assert(verifications == 2 && verified[0].va == base && verified[0].pa == job.dma &&
           verified[0].size == sizeof(bytes) && verified[0].vm == &vm &&
           verified[1].va == 0x4000 && verified[1].pa == frame.dma &&
           verified[1].size == sizeof(frame_bytes) && verified[1].vm == &vm);
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
    assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, &scope, &vm, &f) == 0);
    assert(g.regs[GPU_REG_UCMD_SOURCE / 4] == 0x4000 && g.regs[GPU_REG_UCMD_DESTINATION / 4] == 0x4200);
    assert(!g.regs[GPU_REG_UCMD_PATTERN / 4] && g.regs[GPU_REG_UCMD_WIDTH / 4] == context);
    assert(g.regs[GPU_REG_UCMD_FLAGS / 4] == scope.flags && g.regs[GPU_REG_UCMD_HEIGHT / 4] == 9);
    assert(g.regs[GPU_REG_UCMD_SOURCE_STRIDE / 4] == 11 && g.kicks == 2);
    release_fence(&g, f); f = NULL;

    invalidations = 0; fail_invalidation = 2;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EIO);
    assert(!f && g.kicks == 2 && !g.hw.submit_lock);
    fail_invalidation = 0; invalidations = 0; execution_busy = 1;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EBUSY);
    assert(!f && g.kicks == 2 && !g.hw.submit_lock);
    execution_busy = 0; invalidations = 0; g.idle_error = -ETIMEDOUT;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -ETIMEDOUT);
    assert(!f && g.kicks == 2 && !g.hw.submit_lock);
    g.idle_error = 0;
    put(bytes, 16, 0x8000); put(bytes, 17, 2); put(bytes, 18, 0x9040); put(bytes, 20, DRAW_STRIDE);
    const u32 job_domains[] = { OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC };
    const u32 draw_domains[] = { OPENGPU_NATIVE_TESSELLATION_SEQUENCE_MAGIC,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_MAGIC };
    const u32 versions[] = { OPENGPU_NATIVE_TESSELLATION_SEQUENCE_VERSION,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_VERSION, OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_VERSION };
    for (unsigned i = 0; i < sizeof(job_domains) / sizeof(job_domains[0]); ++i) {
        put(bytes, 0, job_domains[i]);
        for (unsigned j = 0; j < 2; ++j) {
            unsigned char *draw = draw_bytes + j * DRAW_STRIDE;
            put(draw, 0, draw_domains[i]); put(draw, 1, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES);
            put(draw, 2, versions[i]);
            put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD, context);
            put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_GENERATION_WORD, 9);
            put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_PASS_WORD, 8);
        }
        invalidations = 0;
        descriptors.draws = NULL;
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EINVAL);
        descriptors.draws = &draws;
        draws.size--;
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EINVAL);
        draws.size++;
        put(draw_bytes + DRAW_STRIDE, 511, 1);
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EINVAL);
        put(draw_bytes + DRAW_STRIDE, 511, 0);
        put(draw_bytes + DRAW_STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD, context ^ 1u);
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EINVAL);
        put(draw_bytes + DRAW_STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD, context);
        put(draw_bytes + DRAW_STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 1);
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EINVAL);
        put(draw_bytes + DRAW_STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 0);
        assert(!f && !invalidations);
        verifications = 0; fail_verification = 3;
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EACCES);
        assert(!f && verifications == 3 && !invalidations && g.kicks == 2 + i);
        verifications = 0; fail_verification = 0;
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == 0);
        assert(verifications == 3 && verified[2].va == 0x8000 && verified[2].pa == draws.dma &&
               verified[2].size == sizeof(draw_bytes) && verified[2].vm == &vm);
        assert(invalidations == 4 && invalidated[0] == &job && invalidated[1] == &frame &&
               invalidated[2] == &draws && invalidated[3] == &code);
        assert(g.regs[GPU_REG_UCMD_WIDTH / 4] == context);
        release_fence(&g, f); f = NULL;
    }
    {
        unsigned char state_bytes[512] = {0};
        struct opengpu_buffer states = { .cpu = state_bytes, .dma = 0x6000, .size = sizeof(state_bytes) };
        descriptors.states = &states;
        put(frame_bytes, OPENGPU_TESS_FRAME_STATE_BASE, 0xfffffe00u);
        put(frame_bytes, OPENGPU_TESS_FRAME_STATE_LIMIT, 0);
        put(frame_bytes, OPENGPU_TESS_FRAME_STATE_LIMIT + 1, 1);
        invalidations = 0; verifications = 0; states.size--;
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EINVAL);
        assert(!f && !invalidations);
        states.size++; verifications = 0; fail_verification = 4;
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EACCES);
        assert(!f && verifications == 4 && !invalidations);
        fail_verification = 0; verifications = 0;
        assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == 0);
        assert(verifications == 4 && verified[3].va == 0xfffffe00u &&
               verified[3].pa == states.dma && verified[3].size == sizeof(state_bytes));
        assert(invalidations == 5 && invalidated[3] == &states && invalidated[4] == &code);
        release_fence(&g, f); f = NULL;
        descriptors.states = NULL;
        put(frame_bytes, OPENGPU_TESS_FRAME_STATE_BASE, 0);
        put(frame_bytes, OPENGPU_TESS_FRAME_STATE_LIMIT + 1, 0);
    }
    put(bytes, 16, 0xfffff800u); put(bytes, 17, 1); put(bytes, 18, 0);
    put(bytes, 19, 1); put(bytes, 20, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES);
    invalidations = 0;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == 0);
    assert(invalidations == 4 && invalidated[2] == &draws);
    release_fence(&g, f); f = NULL;
    put(bytes, 18, 64);
    invalidations = 0;
    assert(opengpu_hw_tessellation_async(&g, &descriptors, 0x4000, 0x4200, snapshots, 1, NULL, &vm, &f) == -EINVAL);
    assert(!f && !invalidations);
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
