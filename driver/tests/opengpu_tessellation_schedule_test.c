/* Production scheduler, snapshot preparation, MMU and transport with a fake device. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/mutex.h>

#define OPENGPU_DEVICE_H
#define OPENGPU_SCHEDULER_H
typedef uint32_t u32;
typedef uint64_t u64;
typedef uint64_t dma_addr_t;
#include "opengpu_asid.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_tessellation_job_validate.h"

#define OPENGPU_MMU_PAGE_SIZE 4096u
#define OPENGPU_MMU_MAX_TABLES 16u
#define OPENGPU_VM_MAX_TABLES 192u
#define OPENGPU_MMU_POLICY_CACHED 0u
#define OPENGPU_MMU_POLICY_UNCACHED 2u
#define GPU_CAP_UNIFIED_COMMANDS 1u
#define GPU_UCMD_OP_TESSELLATION 15u
#define GPU_UCMD_FLAG_RASTER_DISCARD (1u << 3)
#define GPU_UCMD_FLAG_FEEDBACK_CAPTURE (1u << 4)
#define GFP_KERNEL 0
#define WRITE_ONCE(x, v) ((x) = (v))
#define ERR_PTR(e) ((struct dma_fence *)(intptr_t)(e))
#define container_of(p, t, member) ((t *)((char *)(p) - offsetof(t, member)))
#define lower_32_bits(v) ((u32)(v))
#define dev_err(...) ((void)0)
#define DRM_GPU_SCHED_STAT_RESET 1
#define DRM_SCHED_PRIORITY_COUNT 1
#define OPENGPU_DRAW_WAIT_MS 1
#define msecs_to_jiffies(x) (x)

struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_vm {
    struct opengpu_buffer root, l1[OPENGPU_VM_MAX_TABLES];
    u32 asid, l1_region[OPENGPU_VM_MAX_TABLES], l1_count;
    bool enabled;
};
struct opengpu_mmu {
    struct mutex lock;
    struct opengpu_buffer root, l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES], l1_count;
    struct opengpu_asid_pool asids;
    bool enabled;
};
struct opengpu_device {
    void *dev;
    struct { int scheduler; } compute;
    struct { struct mutex submit_lock; u32 capabilities, tessellation_engines;
        bool wedged, tessellation_transport, tessellation_feedback_transport; } hw;
    struct opengpu_mmu mmu;
};
struct dma_fence { u64 seqno; int refs; };
struct drm_sched_fence { struct dma_fence finished; };
struct drm_sched_job { struct drm_sched_fence *s_fence; };
struct drm_sched_entity { int unused; };
struct dma_resv { int fences, held; };
#define dma_resv_assert_held(r) assert((r)->held)
struct drm_gem_object { struct dma_resv *resv; int refs; };
struct drm_file { u64 client_id; };
struct drm_syncobj { int replaced; };
struct opengpu_file { struct opengpu_device *gpu; struct mutex lock; };
struct opengpu_render_context {
    struct opengpu_device *gpu;
    struct opengpu_vm vm;
    struct drm_sched_entity entity;
    struct dma_fence *last_fence;
    u32 id;
    int refs;
};
struct opengpu_tessellation_scope { u32 flags, program, cookie; };
struct opengpu_tessellation_descriptors {
    const struct opengpu_buffer *job, *frame, *draws;
};
struct opengpu_command_events { u32 flags, wait_event, signal_event; };
struct opengpu_kernel_launch;
enum { OPENGPU_SCHED_RENDER, OPENGPU_SCHED_TESSELLATION = 7 };
#define OPENGPU_MAX_TEMP_MAPPINGS 8
struct opengpu_job_temporary_mapping { dma_addr_t va; size_t size; };
struct opengpu_sched_job {
    struct drm_sched_job base;
    struct opengpu_device *gpu;
    int type;
    const struct opengpu_vm *vm;
    struct opengpu_render_context *context;
    struct opengpu_buffer tess_job, tess_frame, tess_draws;
    u32 tess_job_va, tess_frame_va, tess_draws_va;
    struct opengpu_tessellation_scope tess_scope;
    struct opengpu_buffer depth, vertex_shader, shader, texture_records,
        storage_records, image_records, counter_records, render_desc, commands;
    struct drm_gem_object **objects;
    u32 object_count, temporary_mapping_count;
    struct opengpu_job_temporary_mapping temporary_mappings[OPENGPU_MAX_TEMP_MAPPINGS];
};
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage, signal_usage; };
struct opengpu_tessellation_job_input {
    const void *job; size_t job_size;
    const void *frame; size_t frame_size;
    const void *draws; size_t draws_size;
    struct opengpu_tessellation_scope scope;
};
enum drm_gpu_sched_stat { test_reset = DRM_GPU_SCHED_STAT_RESET };
struct drm_sched_backend_ops {
    struct dma_fence *(*run_job)(struct drm_sched_job *);
    enum drm_gpu_sched_stat (*timedout_job)(struct drm_sched_job *);
    void (*free_job)(struct drm_sched_job *);
};
struct drm_sched_init_args { const struct drm_sched_backend_ops *ops;
    int num_rqs, credit_limit, hang_limit, timeout; const char *name; void *dev; };

static int live_allocations, fail_alloc_after = -1, busy, invalidations, kicks, barriers;
static int fail_submit, init_error, dep_error, pushed, cleanup_count;
static dma_addr_t next_dma = 0x10000000;
static struct opengpu_sched_job *last_job;
static struct dma_fence hardware_fence;
static struct drm_sched_fence scheduler_fences[32];
static u32 submitted_va, submitted_context;

static void *kzalloc(size_t n, int flags) { (void)flags; return calloc(1, n); }
static void *kcalloc(size_t n, size_t size, int flags) { (void)flags; return calloc(n, size); }
static void kfree(void *p) { free(p); }
#define kvcalloc(n, size, flags) calloc((n), (size))
#define kvfree(p) free(p)
#define dma_wmb() (++barriers)

static int opengpu_buffer_alloc(struct opengpu_device *gpu,
                                struct opengpu_buffer *b, size_t size)
{
    (void)gpu;
    if (!fail_alloc_after) return -ENOMEM;
    if (fail_alloc_after > 0) --fail_alloc_after;
    b->cpu = calloc(1, size);
    assert(b->cpu);
    b->size = size;
    b->dma = next_dma;
    next_dma += (size + 4095u) & ~(u64)4095u;
    ++live_allocations;
    return 0;
}
static void opengpu_buffer_free(struct opengpu_device *gpu, struct opengpu_buffer *b)
{
    (void)gpu;
    if (!b->cpu) return;
    assert(!busy);
    free(b->cpu); b->cpu = NULL; --live_allocations;
}
static int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu)
{ assert(gpu->hw.submit_lock.held); return busy ? -EBUSY : 0; }
static int opengpu_hw_flush_tlbs(struct opengpu_device *gpu)
{ assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held); return 0; }
static int opengpu_hw_flush_tlb_asid(struct opengpu_device *gpu, u32 asid)
{ assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held && asid); return 0; }
static int opengpu_hw_activate_vm(struct opengpu_device *gpu, const struct opengpu_vm *vm)
{ (void)gpu; (void)vm; return 0; }
static int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root)
{ (void)gpu; assert(root); return 0; }
#include "../opengpu_mmu.c"

static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{ (void)gpu; return busy; }
static int opengpu_hw_invalidate_buffer_locked(struct opengpu_device *gpu,
                                                const struct opengpu_buffer *buffer)
{ assert(gpu->hw.submit_lock.held && buffer && buffer->cpu); ++invalidations; return 0; }
static int opengpu_hw_unified_submit_locked(struct opengpu_device *gpu,
    const struct opengpu_kernel_launch *launch, const struct opengpu_command_events *events,
    u32 opcode, u32 source, u32 destination, u32 bytes, u32 pattern, u32 sample_mode,
    u32 width, u32 height, u32 source_stride, u32 destination_stride, u64 expected,
    const struct opengpu_vm *vm, u32 delay, struct dma_fence **out)
{
    assert(gpu->hw.submit_lock.held && !launch && vm && opcode == GPU_UCMD_OP_TESSELLATION);
    assert(bytes == 512 && destination == source + bytes && !pattern && !sample_mode &&
           !destination_stride && !expected && !delay);
    (void)events; (void)height; (void)source_stride;
    submitted_va = source; submitted_context = width;
    if (fail_submit) return fail_submit;
    ++kicks; busy = 1; *out = &hardware_fence; return 0;
}
#include "tessellation_under_test.inc"
#include "job_track_mapping_under_test.inc"

static int opengpu_sched_job_submit(struct opengpu_file *, struct drm_file *,
    struct opengpu_render_context *, struct opengpu_sched_job *, u32,
    struct drm_syncobj *, const struct opengpu_sched_dep *, u32, u64 *);
#include "../opengpu_tessellation_job.c"

static struct dma_fence *opengpu_job_run(struct opengpu_sched_job *job)
{ assert(job->type == OPENGPU_SCHED_TESSELLATION); return opengpu_tessellation_job_run(job); }
static void opengpu_context_get(struct opengpu_render_context *c) { ++c->refs; }
static void opengpu_context_put(struct opengpu_render_context *c) { assert(c->refs > 0); --c->refs; }
static void opengpu_hw_abort(struct opengpu_device *g, int e) { (void)g; (void)e; busy = 0; }
static void drm_sched_job_cleanup(struct drm_sched_job *j) { (void)j; ++cleanup_count; }
static void drm_gem_object_put(struct drm_gem_object *o) { --o->refs; }
static void drm_gem_object_get(struct drm_gem_object *o) { ++o->refs; }
static int drm_sched_job_init(struct drm_sched_job *j, struct drm_sched_entity *e,
    int c, struct opengpu_file *f, u64 id)
{ (void)e; (void)c; (void)f; (void)id; j->s_fence = &scheduler_fences[pushed]; return init_error; }
static int drm_sched_job_add_syncobj_dependency(struct drm_sched_job *j, struct drm_file *f, u32 id, int p)
{ (void)j; (void)f; (void)id; (void)p; return dep_error; }
static int drm_sched_job_add_resv_dependencies(struct drm_sched_job *j, struct dma_resv *r, int u)
{ (void)j; (void)r; (void)u; return dep_error; }
static void drm_sched_job_arm(struct drm_sched_job *j) { j->s_fence->finished.seqno = pushed + 1; }
static struct dma_fence *dma_fence_get(struct dma_fence *f) { if (f) ++f->refs; return f; }
static void dma_fence_put(struct dma_fence *f) { if (f) --f->refs; }
static void dma_resv_add_fence(struct dma_resv *r, struct dma_fence *f, int u)
{ (void)f; (void)u; ++r->fences; }
static void drm_syncobj_replace_fence(struct drm_syncobj *s, struct dma_fence *f)
{ (void)f; ++s->replaced; }
static void drm_sched_entity_push_job(struct drm_sched_job *base)
{ last_job = container_of(base, struct opengpu_sched_job, base); ++pushed; }
static int drm_sched_init(int *s, const struct drm_sched_init_args *a) { (void)s; (void)a; return 0; }
static void drm_sched_fini(int *s) { (void)s; }
#include "../opengpu_scheduler.c"

static void put(void *cpu, unsigned word, u32 value) { tess_put32(cpu, word, value); }
static int verify(struct opengpu_device *gpu, struct opengpu_vm *vm,
                  dma_addr_t va, dma_addr_t pa, size_t bytes)
{
    int ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_mmu_verify_snapshot_locked(gpu, vm, va, pa, bytes);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}
static void make_records(unsigned char *job, unsigned char *frame, unsigned char *draws,
                         u32 context, u32 domain, u32 count, u32 stride)
{
    static const u32 job_magic[] = { OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC };
    static const u32 draw_magic[] = { OPENGPU_NATIVE_TESSELLATION_SEQUENCE_MAGIC,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_MAGIC };
    const unsigned ids[] = { OPENGPU_TESS_FRAME_POOL_IDENTITY, OPENGPU_TESS_FRAME_ARENA_IDENTITY,
                             OPENGPU_TESS_FRAME_COLOR_IDENTITY, OPENGPU_TESS_FRAME_STATE_IDENTITY };
    memset(job, 0, 512); memset(frame, 0, 2048);
    if (count) memset(draws, 0, (count - 1u) * stride + 2048);
    put(job, 0, job_magic[domain]); put(job, 1, 512); put(job, 2, 1);
    put(frame, 0, OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_MAGIC); put(frame, 1, 2048); put(frame, 2, 1);
    for (unsigned i = 3; i <= 7; ++i) { put(job, i, i <= 5 ? 16 : 4); put(frame, i, i <= 5 ? 16 : 4); }
    put(job, 17, count); put(job, 20, stride); put(job, 24, context); put(job, 25, 9); put(job, 26, 8);
    put(frame, OPENGPU_TESS_FRAME_VERSION, 1);
    for (unsigned i = 0; i < sizeof(ids) / sizeof(ids[0]); ++i) {
        put(frame, ids[i], context); put(frame, ids[i] + 1, 9); put(frame, ids[i] + 2, 8);
    }
    for (u32 i = 0; i < count; ++i) {
        unsigned char *d = draws + i * stride;
        put(d, 0, draw_magic[domain]); put(d, 1, 2048); put(d, 2, domain + 1);
        put(d, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD, context);
        put(d, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_GENERATION_WORD, 9);
        put(d, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_PASS_WORD, 8);
    }
}

static void read_record(const char *path, void *data, size_t bytes)
{
    FILE *f = fopen(path, "rb");
    assert(f && fread(data, 1, bytes, f) == bytes && fgetc(f) == EOF);
    assert(!fclose(f));
}

static int test_host_fixture(const char *job_path, const char *frame_path,
                             const char *draw_path)
{
    unsigned char job[512], frame[2048], draw[2048];
    struct opengpu_device gpu = { .hw = { .capabilities = GPU_CAP_UNIFIED_COMMANDS,
        .tessellation_transport = true, .tessellation_engines = 2 } };
    struct opengpu_render_context context = { .gpu = &gpu, .refs = 1 };
    struct opengpu_file file = { .gpu = &gpu }; struct drm_file drm = {0};
    struct opengpu_tessellation_job_input input = { .job = job, .job_size = sizeof(job),
        .frame = frame, .frame_size = sizeof(frame), .draws = draw, .draws_size = sizeof(draw) };
    read_record(job_path, job, sizeof(job)); read_record(frame_path, frame, sizeof(frame));
    read_record(draw_path, draw, sizeof(draw));
    context.id = opengpu_tessellation_job_le32(job + 24 * 4);
    assert(opengpu_tessellation_job_le32(job + 17 * 4) == 1);
    assert(opengpu_tessellation_job_le32(draw + OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD * 4) > 0);
    mutex_init(&file.lock); mutex_lock(&file.lock);
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu, &context.vm));
    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, NULL, 0, NULL));
    struct opengpu_sched_job *queued = last_job;
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    assert(kicks == 1 && queued->temporary_mapping_count == 3 && submitted_context == context.id);
    busy = 0; opengpu_sched_free_job(&queued->base);
    assert(context.refs == 1);
    mutex_unlock(&file.lock);
    opengpu_mmu_vm_destroy(&gpu, &context.vm); opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts("host-generated tessellation records accepted by scheduler and MMU PASS");
    return 0;
}

int main(int argc, char **argv)
{
    if (argc == 4)
        return test_host_fixture(argv[1], argv[2], argv[3]);
    assert(argc == 1);
    enum { STRIDE = 2112, DRAW_BYTES = STRIDE + 2048 };
    unsigned char job[512], frame[2048], draws[DRAW_BYTES];
    struct opengpu_device gpu = { .hw = { .capabilities = GPU_CAP_UNIFIED_COMMANDS,
        .tessellation_transport = true, .tessellation_engines = 2 } };
    struct opengpu_render_context context = { .gpu = &gpu, .id = 7, .refs = 1 };
    struct opengpu_file file = { .gpu = &gpu };
    struct drm_file drm = {0}; struct drm_syncobj sync = {0};
    struct dma_resv resv = { .held = 1 }; struct drm_gem_object object = { .resv = &resv };
    struct opengpu_sched_dep dep = { .object = &object };
    struct opengpu_tessellation_job_input input = {
        .job = job, .job_size = sizeof(job), .frame = frame, .frame_size = sizeof(frame),
        .draws = draws, .draws_size = sizeof(draws) };
    u64 seqno = 0;
    mutex_init(&file.lock); mutex_lock(&file.lock);
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu, &context.vm));
    const int baseline = live_allocations;
    for (u32 domain = 0; domain < 3; ++domain) {
        make_records(job, frame, draws, context.id, domain, 2, STRIDE);
        assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, &sync, &dep, 1, &seqno));
        struct opengpu_sched_job *queued = last_job;
        assert(queued && seqno == domain + 1 && object.refs == 1 && context.refs == 2);
        assert(queued->tess_job.cpu != job && queued->tess_frame.cpu != frame && queued->tess_draws.cpu != draws);
        memset(job, 0, sizeof(job)); memset(frame, 0, sizeof(frame)); memset(draws, 0, sizeof(draws));
        assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
        assert(queued->temporary_mapping_count == 3 && invalidations == (int)(3 * (domain + 1)));
        assert(kicks == (int)(domain + 1) && submitted_va == queued->tess_job_va && submitted_context == context.id);
        assert(busy && queued->tess_job.cpu && queued->tess_frame.cpu && queued->tess_draws.cpu);
        dma_addr_t retired_va = queued->tess_job_va, retired_dma = queued->tess_job.dma;
        assert(!verify(&gpu, &context.vm, retired_va, retired_dma, 512));
        opengpu_context_put(&context); /* a closing owner cannot free the in-flight VM */
        assert(context.refs == 1 && object.refs == 1);
        busy = 0;
        opengpu_sched_free_job(&queued->base);
        assert(context.refs == 0 && object.refs == 0 && live_allocations >= baseline);
        assert(verify(&gpu, &context.vm, retired_va, retired_dma, 512) == -EACCES);
        context.refs = 1;
    }
    assert(sync.replaced == 3 && resv.fences == 3 && cleanup_count == 3);
    make_records(job, frame, draws, context.id, 0, 2, STRIDE);
    put(frame, OPENGPU_TESS_FRAME_ARENA_IDENTITY, context.id + 1);
    int before = live_allocations;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EINVAL);
    assert(live_allocations == before && pushed == 3 && !object.refs && context.refs == 1);
    make_records(job, frame, draws, context.id, 0, 2, STRIDE);
    input.draws_size--;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EINVAL);
    input.draws_size++;
    put(job, 17, 0xffffffffu); put(job, 20, 0xffffffc0u);
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EINVAL);
    make_records(job, frame, draws, context.id, 0, 2, STRIDE);
    input.scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EOPNOTSUPP);
    input.scope.flags = 1u;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EINVAL);
    input.scope.flags = 0;
    for (int fail = 0; fail < 3; ++fail) {
        fail_alloc_after = fail;
        assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -ENOMEM);
        assert(live_allocations == before && pushed == 3);
    }
    fail_alloc_after = -1;
    assert(live_allocations == before && pushed == 3);
    dep_error = -EIO;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EIO);
    dep_error = 0;
    assert(live_allocations == before && pushed == 3 && !object.refs && context.refs == 1);
    init_error = -EIO;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EIO);
    init_error = 0;
    assert(live_allocations == before && pushed == 3 && !object.refs && context.refs == 1);
    dma_addr_t saved_dma = next_dma;
    next_dma = 1ULL << 32;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -ERANGE);
    next_dma = saved_dma;
    assert(live_allocations == before && pushed == 3);
    context.vm.enabled = false;
    assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL) == -EOPNOTSUPP);
    context.vm.enabled = true;

    /* Empty draw arrays use only two private mappings. */
    make_records(job, frame, draws, context.id, 0, 0, 0);
    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL));
    struct opengpu_sched_job *queued = last_job;
    assert(!queued->tess_draws.cpu && !queued->tess_draws_va);
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    assert(queued->temporary_mapping_count == 2 && kicks == 4 && invalidations == 11);
    busy = 0; opengpu_sched_free_job(&queued->base);
    assert(!object.refs && context.refs == 1);

    /* An occupied frame window must survive a failed second mapping. */
    make_records(job, frame, draws, context.id, 0, 2, STRIDE);
    const dma_addr_t frame_va = OPENGPU_COMMAND_VA_BASE + 6 * OPENGPU_COMMAND_VA_STRIDE;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, frame_va, 0x30000000, 4096, 0));
    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL));
    queued = last_job;
    assert(opengpu_sched_run_job(&queued->base) == ERR_PTR(-EEXIST));
    assert(queued->temporary_mapping_count == 1 && kicks == 4 && invalidations == 11);
    opengpu_sched_free_job(&queued->base);
    u32 *frame_l1 = vm_l1_find(&context.vm, frame_va / MMU_SUPERPAGE_SIZE)->cpu;
    assert(frame_l1[(frame_va % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE] == mmu_leaf_pte_private(0x30000000, 0));
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, frame_va, 4096));

    /* A writable alias of a copied snapshot is rejected before invalidation. */
    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL));
    queued = last_job;
    const dma_addr_t alias = 0xb3000000;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, alias, queued->tess_job.dma, 4096, 0));
    assert(opengpu_sched_run_job(&queued->base) == ERR_PTR(-EACCES));
    assert(queued->temporary_mapping_count == 3 && kicks == 4 && invalidations == 11);
    opengpu_sched_free_job(&queued->base);
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, alias, 4096));

    /* A command publication failure still revokes every acquired mapping. */
    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL));
    queued = last_job; fail_submit = -EIO;
    assert(opengpu_sched_run_job(&queued->base) == ERR_PTR(-EIO));
    assert(queued->temporary_mapping_count == 3 && kicks == 4 && invalidations == 14);
    fail_submit = 0; opengpu_sched_free_job(&queued->base);
    assert(!object.refs && context.refs == 1 && !gpu.hw.wedged);

    /* Abort drains before retirement releases the mappings and allocations. */
    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL));
    queued = last_job;
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence && busy);
    assert(opengpu_sched_timedout_job(&queued->base) == DRM_GPU_SCHED_STAT_RESET && !busy);
    opengpu_sched_free_job(&queued->base);
    assert(!object.refs && context.refs == 1);

    /* A failed drain cannot release memory that hardware may still access. */
    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &dep, 1, NULL));
    queued = last_job;
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence && busy);
    before = live_allocations;
    opengpu_sched_free_job(&queued->base);
    assert(gpu.hw.wedged && busy && live_allocations == before &&
           context.refs == 2 && object.refs == 1 && queued->tess_job.cpu &&
           queued->tess_frame.cpu && queued->tess_draws.cpu);
    /* A simulated external reset makes it safe for this test to reclaim it. */
    busy = 0; gpu.hw.wedged = false;
    for (u32 i = 0; i < queued->temporary_mapping_count; ++i)
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm,
            queued->temporary_mappings[i].va, queued->temporary_mappings[i].size));
    opengpu_tessellation_job_release(queued);
    drm_gem_object_put(queued->objects[0]); kfree(queued->objects);
    opengpu_context_put(&context); kfree(queued);
    assert(!object.refs && context.refs == 1);
    mutex_unlock(&file.lock);
    opengpu_mmu_vm_destroy(&gpu, &context.vm);
    opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts("tessellation scheduler: copied snapshots, private mappings, submission and retirement PASS");
    return 0;
}
