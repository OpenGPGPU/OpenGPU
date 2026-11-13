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
#define GPU_CAP_UNIFIED_COMMANDS (1u << 6)
#define GPU_UCMD_OP_TESSELLATION 15u
#define GPU_UCMD_FLAG_RASTER_DISCARD (1u << 3)
#define GPU_UCMD_FLAG_FEEDBACK_CAPTURE (1u << 4)
#define GFP_KERNEL 0
#define WRITE_ONCE(x, v) ((x) = (v))
#define ERR_PTR(e) ((struct dma_fence *)(intptr_t)(e))
#define container_of(p, t, member) ((t *)((char *)(p) - offsetof(t, member)))
#define lower_32_bits(v) ((u32)(v))
#define upper_32_bits(v) ((u32)((u64)(v) >> 32))
#define dev_err(...) ((void)0)
#define DRM_GPU_SCHED_STAT_RESET 1
#define DRM_SCHED_PRIORITY_COUNT 1
#define OPENGPU_DRAW_WAIT_MS 1
#define OPENGPU_MAX_RESOURCE_BINDINGS 42u
#define OPENGPU_RESOURCE_SHADER 1u
#define OPENGPU_RESOURCE_VERTEX_BUFFER 4u
#define OPENGPU_RESOURCE_VERTEX_SHADER 5u
#define OPENGPU_RESOURCE_COMPUTE_SHADER 7u
#define OPENGPU_RESOURCE_COMPUTE_STORAGE 10u
#define msecs_to_jiffies(x) (x)
enum { DMA_RESV_USAGE_KERNEL, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ,
       DMA_RESV_USAGE_BOOKKEEP };

struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_snapshot_lease {
    struct opengpu_snapshot_lease *next;
    const struct opengpu_vm *owner;
    dma_addr_t base, va;
    size_t size;
    bool active, mapped;
};
struct opengpu_vm {
    struct opengpu_vm *next;
    struct opengpu_buffer root, l1[OPENGPU_VM_MAX_TABLES];
    u32 asid, l1_region[OPENGPU_VM_MAX_TABLES], l1_count;
    bool enabled;
};
struct opengpu_mmu {
    struct mutex lock;
    struct opengpu_buffer root, l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES], l1_count;
    struct opengpu_asid_pool asids;
    struct opengpu_vm *vms;
    struct opengpu_snapshot_lease *snapshot_leases;
    bool enabled;
};
struct opengpu_device {
    void *dev;
    struct { int scheduler; } compute;
    struct { struct mutex submit_lock; u32 capabilities, tessellation_engines;
        bool wedged, tessellation_transport, tessellation_feedback_transport,
             tessellation_virtual_images, native_advanced_blend_transport; } hw;
    struct opengpu_mmu mmu;
};
struct dma_fence { u64 seqno; int refs; };
struct drm_sched_fence { struct dma_fence finished; };
struct drm_sched_job { struct drm_sched_fence *s_fence; };
struct drm_sched_entity { int unused; };
struct dma_resv { int fences, held, wait_usage, signal_usage; };
#define dma_resv_assert_held(r) assert((r)->held)
struct drm_gem_object { struct dma_resv *resv; void *import_attach; int refs; };
struct opengpu_resource_binding {
    struct drm_gem_object *object;
    dma_addr_t dma, va;
    u64 size, offset;
    u32 type;
};
struct drm_file { u64 client_id; };
struct drm_syncobj { int replaced; };
struct opengpu_file { struct opengpu_device *gpu; struct mutex lock; };
struct opengpu_render_context {
    struct opengpu_device *gpu;
    struct opengpu_vm vm;
    struct drm_sched_entity entity;
    struct dma_fence *last_fence;
    struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_BINDINGS];
    u32 id;
    int refs;
};
struct opengpu_tessellation_scope { u32 flags, program, cookie; };
struct opengpu_tessellation_descriptors {
    const struct opengpu_buffer *job, *frame, *draws, *states;
    const struct opengpu_buffer *fragment_code;
    u32 fragment_code_va;
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
    struct opengpu_buffer tess_job, tess_frame, tess_draws, tess_states, tess_fragment_code;
    u32 tess_job_va, tess_frame_va, tess_draws_va, tess_states_va, tess_fragment_code_va;
    struct opengpu_tessellation_scope tess_scope;
    struct opengpu_buffer depth, vertex_shader, shader, texture_records,
        storage_records, image_records, counter_records, uniform_snapshot, indirect_snapshot, render_desc, commands;
    struct drm_gem_object **objects;
    u32 object_count, temporary_mapping_count;
    struct opengpu_job_temporary_mapping temporary_mappings[OPENGPU_MAX_TEMP_MAPPINGS];
};
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage, signal_usage; };
struct opengpu_tessellation_job_input {
    const void *job; size_t job_size;
    const void *frame; size_t frame_size;
    const void *draws; size_t draws_size;
    const void *states; size_t states_size;
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
/* Never reuse a slot while the context can retain its completion fence. */
static struct drm_sched_fence scheduler_fences[128];
static u32 submitted_va, submitted_context;
static struct {
    u32 opcode, source, destination, bytes, pattern, sample_mode;
    u32 width, height, source_stride, destination_stride, flags;
} submitted_command;
static struct { u32 address, value; } satp_writes[2];
static u32 satp_write_count;
static bool capture_fixture;
static struct { u32 address, value; } tlb_writes[64];
static u32 tlb_write_count;
static struct { u64 dma, size; } snapshot_invalidations[8];
static u32 snapshot_invalidation_count;

static void *kzalloc(size_t n, int flags) { (void)flags; return calloc(1, n); }
static void *kcalloc(size_t n, size_t size, int flags) { (void)flags; return calloc(n, size); }
static void kfree(void *p) { free(p); }
static int fail_kv_after = -1, live_kv;
static void *kvcalloc(size_t n, size_t size, int flags)
{
    void *p;
    (void)flags;
    if (!fail_kv_after) return NULL;
    if (fail_kv_after > 0) --fail_kv_after;
    p = calloc(n, size); if (p) ++live_kv;
    return p;
}
static void kvfree(void *p) { if (p) { assert(live_kv > 0); --live_kv; free(p); } }
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
static void opengpu_reg_write(struct opengpu_device *gpu, u32 address, u32 value)
{
    (void)gpu;
    if (address == 0x154) {
        if (capture_fixture) {
            assert(tlb_write_count < sizeof(tlb_writes) / sizeof(tlb_writes[0]));
            tlb_writes[tlb_write_count].address = address;
            tlb_writes[tlb_write_count++].value = value;
        }
        return;
    }
    /* Each actual activation/detach writes a complete vector/instruction
     * pair, including VM destruction and final Bare shutdown. */
    if(address==0x14c){assert(!satp_write_count || satp_write_count==2);satp_write_count=0;}
    assert(satp_write_count < 2);
    satp_writes[satp_write_count].address = address;
    satp_writes[satp_write_count++].value = value;
}
/* Actual driver SATP packing and VM selection, with captured register writes. */
#include "satp_under_test.inc"
static int opengpu_hw_activate_vm(struct opengpu_device *gpu, const struct opengpu_vm *vm)
{ satp_write_count = 0; return opengpu_hw_activate_vm_locked(gpu, vm); }
static int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root)
{ (void)gpu; assert(root); return 0; }
#include "../opengpu_mmu.c"

static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{ (void)gpu; return busy; }
static int opengpu_hw_invalidate_buffer_locked(struct opengpu_device *gpu,
                                                const struct opengpu_buffer *buffer)
{
    assert(gpu->hw.submit_lock.held && buffer && buffer->cpu);
    if (capture_fixture) {
        assert(snapshot_invalidation_count < sizeof(snapshot_invalidations) / sizeof(snapshot_invalidations[0]));
        snapshot_invalidations[snapshot_invalidation_count].dma = buffer->dma;
        snapshot_invalidations[snapshot_invalidation_count++].size = buffer->size;
    }
    ++invalidations;
    return 0;
}
static int opengpu_hw_unified_submit_locked(struct opengpu_device *gpu,
    const struct opengpu_kernel_launch *launch, const struct opengpu_command_events *events,
    u32 opcode, u32 source, u32 destination, u32 bytes, u32 pattern, u32 sample_mode,
    u32 width, u32 height, u32 source_stride, u32 destination_stride, u64 expected,
    const struct opengpu_vm *vm, u32 delay, struct dma_fence **out)
{
    assert(gpu->hw.submit_lock.held && !launch && vm && opcode == GPU_UCMD_OP_TESSELLATION);
    assert(bytes == 512 && destination == source + bytes && !pattern && !sample_mode &&
           !destination_stride && !expected && !delay);
    submitted_command.opcode = opcode; submitted_command.source = source;
    submitted_command.destination = destination; submitted_command.bytes = bytes;
    submitted_command.pattern = pattern; submitted_command.sample_mode = sample_mode;
    submitted_command.width = width; submitted_command.height = height;
    submitted_command.source_stride = source_stride;
    submitted_command.destination_stride = destination_stride;
    submitted_command.flags = events ? events->flags : 0;
    submitted_va = source; submitted_context = width;
    if (fail_submit) return fail_submit;
    satp_write_count = 0;
    assert(!opengpu_hw_activate_vm_locked(gpu, vm));
    ++kicks; busy = 1; *out = &hardware_fence; return 0;
}
#define READ_ONCE(x) (x)
#define time_after(a,b) ((long)((a)-(b)) > 0)
static unsigned long jiffies;
static void opengpu_hw_progress_tick(struct opengpu_device *gpu)
{ assert(!gpu->hw.submit_lock.held); ++jiffies; }
static void cond_resched(void) { }
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
{
    (void)e; (void)c; (void)f; (void)id;
    assert(pushed >= 0 && (size_t)pushed < sizeof(scheduler_fences) / sizeof(scheduler_fences[0]));
    assert(!scheduler_fences[pushed].finished.refs);
    j->s_fence = &scheduler_fences[pushed];
    return init_error;
}
static int drm_sched_job_add_syncobj_dependency(struct drm_sched_job *j, struct drm_file *f, u32 id, int p)
{ (void)j; (void)f; (void)id; (void)p; return dep_error; }
static int drm_sched_job_add_resv_dependencies(struct drm_sched_job *j, struct dma_resv *r, int u)
{ (void)j; r->wait_usage = u; return dep_error; }
static void drm_sched_job_arm(struct drm_sched_job *j) { j->s_fence->finished.seqno = pushed + 1; }
static struct dma_fence *dma_fence_get(struct dma_fence *f) { if (f) ++f->refs; return f; }
static void dma_fence_put(struct dma_fence *f) { if (f) --f->refs; }
static void release_context_fence(struct opengpu_render_context *context)
{
    dma_fence_put(context->last_fence);
    context->last_fence = NULL;
    for (size_t i = 0; i < sizeof(scheduler_fences) / sizeof(scheduler_fences[0]); ++i)
        assert(!scheduler_fences[i].finished.refs);
}
static void dma_resv_add_fence(struct dma_resv *r, struct dma_fence *f, int u)
{ (void)f; r->signal_usage = u; ++r->fences; }
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

static void make_patch_input(unsigned char *draw, u32 domain, u32 va)
{
    const u32 vertices = domain ?
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD :
        OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD;
    const u32 components = domain ?
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD :
        OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD;
    const u32 stride = domain ? OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_TEMPLATE_STRIDE_WORD :
        OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_STRIDE_WORD;

    put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 2);
    put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_PATCHSTRIDE_WORD, 32);
    put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_INPUT_ALLOCATIONBASE_WORD, va);
    put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_INPUT_ALLOCATIONLIMIT_WORD, va + 64);
    put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_INPUT_BASE_WORD, va);
    put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_INPUT_LIMIT_WORD, va + 64);
    put(draw, vertices, 1); put(draw, components, 1); put(draw, stride, 4);
}

static void make_zero_input_draw(unsigned char *draw, u32 domain, u32 count)
{
    static const u32 vertices[] = {
        OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD,
        OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD,
    };

    put(draw, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, count);
    put(draw, vertices[domain], 1);
}

static void make_bound_frame(unsigned char *frame, u32 va, u32 image_base, u32 engines)
{
#define RESOLVE_BASE(i, b, l, e) b,
#define RESOLVE_LIMIT(i, b, l, e) l,
#define RESOLVE_ENABLE(i, b, l, e) e,
    static const u32 rb[] = { OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(RESOLVE_BASE) };
    static const u32 rl[] = { OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(RESOLVE_LIMIT) };
    static const u32 re[] = { OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(RESOLVE_ENABLE) };
#undef RESOLVE_BASE
#undef RESOLVE_LIMIT
#undef RESOLVE_ENABLE

    put(frame, OPENGPU_TESS_FRAME_ARENA_BASE, va);
    put(frame, OPENGPU_TESS_FRAME_ARENA_LIMIT, va + 0x400);
    put(frame, OPENGPU_TESS_FRAME_POOL_BASE, va + 0x400);
    put(frame, OPENGPU_TESS_FRAME_POOL_LIMIT, va + 0x800);
    put(frame, OPENGPU_TESS_FRAME_DIRECTORY_BASE, va + 0x800);
    put(frame, OPENGPU_TESS_FRAME_DIRECTORY_LIMIT, va + 0x900);
    for (u32 i = 0; i < engines; ++i) {
        put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 3 * i, va + 0x900 + 0x100 * i);
        put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 3 * i + 1, va + 0xa00 + 0x100 * i);
    }
    put(frame, OPENGPU_TESS_FRAME_STATE_BASE, va + 0x1000);
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, va + 0x1200);
    put(frame, OPENGPU_TESS_FRAME_STATE_VERSION, 1);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_SLOT_STRIDE, 64);
    for (u32 i = 0; i < 6; ++i) {
        if (i < 4)
            put(frame, OPENGPU_TESS_FRAME_COLOR_ENABLED + i * OPENGPU_TESS_FRAME_COLOR_STRIDE, 1);
        else
            put(frame, i == 4 ? OPENGPU_TESS_FRAME_DEPTH_FORMAT : OPENGPU_TESS_FRAME_STENCIL_FORMAT, 1);
        put(frame, OPENGPU_TESS_FRAME_VIEW_BASE + i * OPENGPU_TESS_FRAME_VIEW_STRIDE, image_base + 0x1400 + 0x80 * i);
        put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT + i * OPENGPU_TESS_FRAME_VIEW_STRIDE, image_base + 0x1440 + 0x80 * i);
        put(frame, OPENGPU_TESS_FRAME_VIEW_STORE + i * OPENGPU_TESS_FRAME_VIEW_STRIDE, 1);
        put(frame, rb[i], image_base + 0x2000 + 0x80 * i);
        put(frame, rl[i], image_base + 0x2040 + 0x80 * i);
        put(frame, re[i], 1);
    }
}

static void make_fragment_state(unsigned char *state, const unsigned char *frame, u32 engines, u32 handle)
{
    static const u32 alloc_bases[] = { OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_BASE_WORD,
        OPENGPU_TESS_STATE_SCRATCH1_ALLOCATION_BASE_WORD, OPENGPU_TESS_STATE_SCRATCH2_ALLOCATION_BASE_WORD,
        OPENGPU_TESS_STATE_SCRATCH3_ALLOCATION_BASE_WORD };
    static const u32 alloc_limits[] = { OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD,
        OPENGPU_TESS_STATE_SCRATCH1_ALLOCATION_LIMIT_WORD, OPENGPU_TESS_STATE_SCRATCH2_ALLOCATION_LIMIT_WORD,
        OPENGPU_TESS_STATE_SCRATCH3_ALLOCATION_LIMIT_WORD };
    static const u32 bases[] = { OPENGPU_TESS_STATE_SCRATCH0_BASE_WORD, OPENGPU_TESS_STATE_SCRATCH1_BASE_WORD,
        OPENGPU_TESS_STATE_SCRATCH2_BASE_WORD, OPENGPU_TESS_STATE_SCRATCH3_BASE_WORD };
    static const u32 limits[] = { OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD, OPENGPU_TESS_STATE_SCRATCH1_LIMIT_WORD,
        OPENGPU_TESS_STATE_SCRATCH2_LIMIT_WORD, OPENGPU_TESS_STATE_SCRATCH3_LIMIT_WORD };
    u32 context = tess_word(frame, OPENGPU_TESS_FRAME_STATE_IDENTITY);
    u32 generation = tess_word(frame, OPENGPU_TESS_FRAME_STATE_IDENTITY + 1);
    u32 pass = tess_word(frame, OPENGPU_TESS_FRAME_STATE_IDENTITY + 2);

    memset(state, 0, 512);
    put(state, 0, 0x47465301); put(state, 1, 512); put(state, 2, 1); put(state, 3, 4);
    put(state, 4, context); put(state, 5, pass); put(state, 6, generation);
    put(state, 7, handle);
    put(state, OPENGPU_TESS_STATE_ENGINE_COUNT_WORD, engines);
    put(state, OPENGPU_TESS_STATE_IDENTITY_CONTEXT_WORD, context);
    put(state, OPENGPU_TESS_STATE_IDENTITY_GENERATION_WORD, generation);
    put(state, OPENGPU_TESS_STATE_IDENTITY_PASS_WORD, pass);
    for (u32 i = 0; i < engines; ++i) {
        u32 start = tess_word(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 3 * i) +
                    handle * tess_word(frame, OPENGPU_TESS_FRAME_SCRATCH_SLOT_STRIDE);
        put(state, alloc_bases[i], start); put(state, alloc_limits[i], start + 64);
        put(state, bases[i], start); put(state, limits[i], start + 64);
    }
}

static void read_record(const char *path, void *data, size_t bytes)
{
    FILE *f = fopen(path, "rb");
    assert(f && fread(data, 1, bytes, f) == bytes && fgetc(f) == EOF);
    assert(!fclose(f));
}

static void make_rooted_records(unsigned char *job, unsigned char *frame,
    unsigned char *draws, unsigned char *states, u32 context, u32 domain, u32 stride,
    u32 frame_va, u32 root_va, u32 draw, u32 root)
{
    static const u32 versions[] = { OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD,
                                   OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD };
    static const u32 addresses[] = { OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD,
                                    OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD };

    make_records(job, frame, draws, context, domain, 2, stride);
    make_bound_frame(frame, frame_va, frame_va, 2);
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, frame_va + 0x1400);
    for (u32 i = 0; i < 2; ++i) {
        make_zero_input_draw(draws + i * stride, domain, 1);
        put(draws + i * stride, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, i);
        make_fragment_state(states + i * 512, frame, 2, i);
    }
    put(states + draw * 512, versions[root], 1);
    put(states + draw * 512, addresses[root], root_va + 0x80);
}

static void reject_rooted_job(struct opengpu_file *file, struct drm_file *drm,
    struct opengpu_render_context *context, struct opengpu_tessellation_job_input *input,
    const struct opengpu_sched_dep *deps, u32 count)
{
    int refs[OPENGPU_MAX_RESOURCE_BINDINGS], fences[OPENGPU_MAX_RESOURCE_BINDINGS];
    const int allocations = live_allocations, push = pushed, doorbells = kicks;
    const int invalidates = invalidations, context_refs = context->refs;

    assert(count <= OPENGPU_MAX_RESOURCE_BINDINGS);
    for (u32 i = 0; i < count; ++i) {
        refs[i] = deps[i].object->refs;
        fences[i] = deps[i].object->resv->fences;
    }
    assert(opengpu_tessellation_sched_submit(file, drm, context, input,
        0, NULL, deps, count, NULL) == -EINVAL);
    assert(live_allocations == allocations && pushed == push && kicks == doorbells &&
           invalidations == invalidates && context->refs == context_refs);
    for (u32 i = 0; i < count; ++i)
        assert(deps[i].object->refs == refs[i] && deps[i].object->resv->fences == fences[i]);
}

#include "opengpu_tessellation_host_export.h"

/* Arbitrary byte patterns test capture, not an instruction-set interpreter. */
static void make_fragment_code_plans(struct opengpu_tessellation_fragment_code_plan *plans,
                                     unsigned char code[3][20])
{
    for (u32 i = 0; i < 3; ++i) {
        for (u32 word = 0; word < 5; ++word)
            put(code[i], word, 0x9e3779b9u * (1u + word + i * 5u));
        plans[i] = (struct opengpu_tessellation_fragment_code_plan) {
            .state_handle = i, .code = code[i], .bytes = 20, .entry_offset = 4,
            .expected_base = OPENGPU_CODE_VA_BASE + OPENGPU_TESS_FRAGMENT_CODE_SLOT * OPENGPU_CODE_VA_STRIDE + 64u * i,
        };
    }
}

static int test_host_fixture(const char *job_path, const char *frame_path,
    const char *draw_path, const char *state_path,
    const char *resource_directory, const char *export_directory, bool uniform_owned, bool texture_owned,
    bool texture_host)
{
    unsigned char job[512], frame[2048], draw[2048], states[1024];
    unsigned char roots[4096], code[TESS_HOST_CODE_BYTES], patch[4096];
    unsigned char *frame_data = NULL;
    struct opengpu_device gpu = { .hw = { .capabilities = GPU_CAP_UNIFIED_COMMANDS,
        .tessellation_transport = true, .tessellation_engines = 2,
        .tessellation_virtual_images = true } };
    struct opengpu_render_context context = { .gpu = &gpu, .refs = 1 };
    struct opengpu_file file = { .gpu = &gpu }; struct drm_file drm = {0};
    struct dma_resv resv = { .held = 1 }, frame_resv = { .held = 1 };
    struct dma_resv root_resv = { .held = 1 };
    struct drm_gem_object root_object = { .resv = &root_resv };
    struct drm_gem_object object = { .resv = &resv }, frame_object = { .resv = &frame_resv };
    struct opengpu_resource_binding binding = { .object = &object,
        .type = OPENGPU_RESOURCE_VERTEX_BUFFER, .dma = 0x30000000,
        .va = OPENGPU_VERTEX_VA_BASE + OPENGPU_VERTEX_VA_STRIDE, .size = 4096 };
    struct opengpu_resource_binding frame_binding = { .object = &frame_object,
        .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .dma = 0x31000000,
        .va = TESS_HOST_FRAME_VA, .size = texture_host ? 0x40000 : 0x30000 };
    struct opengpu_resource_binding root_binding = { .object = &root_object,
        .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .dma = TESS_HOST_ROOT_DMA,
        .va = TESS_HOST_ROOT_VA, .size = sizeof(roots) };
    const struct opengpu_sched_dep deps[] = {
        { &object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
        { &frame_object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE },
        { &root_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
    };
    const u32 dep_count = export_directory ? 3 : 2;
    struct opengpu_tessellation_job_input input = { .job = job, .job_size = sizeof(job),
        .frame = frame, .frame_size = sizeof(frame), .draws = draw, .draws_size = sizeof(draw),
        .states = states, .states_size = sizeof(states) };
    struct opengpu_tessellation_uniform_plan uniform_plan = {
        .state_handle = 0, .active_mask = 1, .default_present = 1,
        .default_view = { 14, 0xb04, 4 },
    };
    struct opengpu_tessellation_texture_level texture_levels[2] = {
        { .view = { .width = 1, .height = 1, .depth = 1, .layers = 1,
            .row_pitch = 4, .block_bytes = 4 } },
    };
    struct opengpu_tessellation_texture_binding texture_bindings[32] = {{0}};
    texture_bindings[texture_host ? 31 : 0] = (struct opengpu_tessellation_texture_binding) {
        .present = 1, .data = { 14, 0xa00, 64 },
        .image = { .format = OPENGPU_TEXTURE_FORMAT_R32F,
            .width = 1, .height = 1, .depth = 1, .layers = 1 },
        .sampler = { .max_lod_bits = 0x41700000, .compare_function = 3 },
        .levels = texture_levels, .level_count = 1,
    };
    struct opengpu_tessellation_texture_plan texture_plan = { 0, texture_host ? 32 : 1, texture_bindings };
    /* This fixture's fragment instructions are PIC (no embedded PC address).
     * Its control/evaluation code keeps the older trusted mapping. */
    struct opengpu_tessellation_fragment_code_plan fragment_code = {
        .state_handle = 0, .code = code + 0x1000, .bytes = 0x1000,
        .expected_base = OPENGPU_CODE_VA_BASE + OPENGPU_TESS_FRAGMENT_CODE_SLOT * OPENGPU_CODE_VA_STRIDE,
    };
    if (texture_host) {
        struct opengpu_tessellation_texture_binding *binding = &texture_bindings[31];
        binding->data.bytes = 128; binding->image.width = binding->image.height = 2;
        binding->image.max_level = 1; binding->sampler.min_filter = 2; binding->level_count = 2;
        texture_levels[0].view.width = texture_levels[0].view.height = 2;
        texture_levels[0].view.row_pitch = 8;
        texture_levels[1] = (struct opengpu_tessellation_texture_level) {
            .offset = 64, .view = { .level = 1, .width = 1, .height = 1, .depth = 1,
                .layers = 1, .row_pitch = 4, .block_bytes = 4 } };
    }
    uniform_plan.bindings[0] = (struct opengpu_tessellation_uniform_view) { 14, 0xb00, 4 };
    read_record(job_path, job, sizeof(job)); read_record(frame_path, frame, sizeof(frame));
    read_record(draw_path, draw, sizeof(draw));
    read_record(state_path, states, sizeof(states));
    capture_fixture = export_directory != NULL;
    if (export_directory) {
        assert(resource_directory);
        fixture_read(resource_directory, "resources.bin", roots, sizeof(roots));
        fixture_read(resource_directory, "code.bin", code, sizeof(code));
        fixture_read(resource_directory, "patch.bin", patch, sizeof(patch));
        frame_data = calloc(1, frame_binding.size);
        assert(frame_data);
        if (texture_host) for (u32 i = 0; i < 6; ++i) {
            size_t base = 0x30000 + 4096 * i, bytes = i == 5 ? 16 : 64;
            memset(frame_data + base - 64, 0xa5, 4096 + 64);
            memset(frame_data + base, 0xc3, bytes);
        }
        assert(tess_word(states, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD) == 1 &&
            tess_word(states, OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD) == TESS_HOST_ROOT_VA &&
            tess_word(states, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD) == 1 &&
            tess_word(states, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD) == TESS_HOST_ROOT_VA + 128);
    }
    context.id = opengpu_tessellation_job_le32(job + 24 * 4);
    assert(opengpu_tessellation_job_le32(job + 17 * 4) == 1);
    assert(opengpu_tessellation_job_le32(draw + OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD * 4) > 0);
    mutex_init(&file.lock); mutex_lock(&file.lock);
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu, &context.vm));
    /* The real fixture's patch range is relocated to the driver's vertex VA. */
    assert(opengpu_tessellation_job_le32(draw + 18 * 4) == 0x4f7000 &&
           opengpu_tessellation_job_le32(draw + 19 * 4) == 0x4f7040 &&
           opengpu_tessellation_job_le32(draw + 21 * 4) == 0x4f7000 &&
           opengpu_tessellation_job_le32(draw + 22 * 4) == 0x4f7040);
    put(draw, 18, binding.va); put(draw, 19, binding.va + 64);
    put(draw, 21, binding.va); put(draw, 22, binding.va + 64);
    /* GNQ images and other frame resources all use context VAs, independently
     * of the shared native GNJ image profile. */
    put(frame, OPENGPU_TESS_FRAME_ARENA_BASE, frame_binding.va);
    put(frame, OPENGPU_TESS_FRAME_ARENA_LIMIT, frame_binding.va + 0x10000);
    put(frame, OPENGPU_TESS_FRAME_POOL_BASE, frame_binding.va + 0x10000);
    put(frame, OPENGPU_TESS_FRAME_POOL_LIMIT, frame_binding.va + 0x20000);
    put(frame, OPENGPU_TESS_FRAME_DIRECTORY_BASE, frame_binding.va + 0x20000);
    put(frame, OPENGPU_TESS_FRAME_DIRECTORY_LIMIT, frame_binding.va + 0x20080);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE, frame_binding.va + 0x21000);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 1, frame_binding.va + (texture_host ? 0x25000 : 0x23000));
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 3, frame_binding.va + (texture_host ? 0x25000 : 0x23000));
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 4, frame_binding.va + (texture_host ? 0x29000 : 0x25000));
    put(frame, OPENGPU_TESS_FRAME_STATE_BASE, frame_binding.va + (texture_host ? 0x2a000 : 0x26000));
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, frame_binding.va + (texture_host ? 0x2a200 : 0x26400));
    put(frame, OPENGPU_TESS_FRAME_VIEW_BASE, frame_binding.va + 0x27000);
    put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT, frame_binding.va + 0x27040);
    put(frame, OPENGPU_TESS_FRAME_VIEW_BASE + 3, frame_binding.va + 0x27000);
    put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT + 5, frame_binding.va + 0x27040);
    if (texture_host) for (u32 i = 0; i < 6; ++i) {
        u32 start = frame_binding.va + 0x30000 + 4096 * i, bytes = i == 5 ? 16 : 64;
        u32 field = OPENGPU_TESS_FRAME_VIEW_BASE + i * OPENGPU_TESS_FRAME_VIEW_STRIDE;
        put(frame, field, start); put(frame, field + 1, start + 64); put(frame, field + 2, 0);
        put(frame, field + 3, start); put(frame, field + 6, start + bytes); put(frame, field + 7, 0);
    }
    /* Preserve the real GFS1 payload while relocating its two scratch views. */
    {
        const u32 ab[] = { OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_BASE_WORD,
                           OPENGPU_TESS_STATE_SCRATCH1_ALLOCATION_BASE_WORD };
        const u32 al[] = { OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD,
                           OPENGPU_TESS_STATE_SCRATCH1_ALLOCATION_LIMIT_WORD };
        const u32 b[] = { OPENGPU_TESS_STATE_SCRATCH0_BASE_WORD, OPENGPU_TESS_STATE_SCRATCH1_BASE_WORD };
        const u32 l[] = { OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD, OPENGPU_TESS_STATE_SCRATCH1_LIMIT_WORD };
        for (u32 i = 0; i < 2; ++i) {
            u32 old_base = tess_word(states, b[i]);
            u32 old_limit = tess_word(states, l[i]);
            u32 new_base = tess_word(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 3 * i);
            assert(tess_word(states, ab[i]) == old_base && tess_word(states, al[i]) == old_limit &&
                   !tess_word(states, al[i] + 1) && !tess_word(states, l[i] + 1) &&
                   old_limit >= old_base && old_limit - old_base <= (texture_host ? 16384u : 4096u));
            put(states, ab[i], new_base); put(states, al[i], new_base + old_limit - old_base);
            put(states, b[i], new_base); put(states, l[i], new_base + old_limit - old_base);
        }
    }
    context.bindings[0] = &binding;
    context.bindings[12] = &frame_binding;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, binding.va, binding.dma, binding.size, 0));
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, frame_binding.va, frame_binding.dma, frame_binding.size, 0));
    if (export_directory) {
        context.bindings[13] = &root_binding;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, root_binding.va,
            root_binding.dma, root_binding.size, OPENGPU_MMU_POLICY_UNCACHED));
        /* Explicit trusted fixture code, not arbitrary-code admission. */
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, TESS_HOST_CODE_VA,
            TESS_HOST_CODE_DMA, sizeof(code), OPENGPU_MMU_POLICY_UNCACHED));
        reject_rooted_job(&file, &drm, &context, &input, deps, 2);
    }
    if (texture_owned) {
        assert(export_directory && uniform_owned);
        if (texture_host) {
            /* The native fault-overlay shrinks only level1's allocation limit
             * to base+3. The corresponding kernel plan must fail before queue. */
            int allocations = live_allocations, metadata = live_kv, pending = pushed;
            int doorbells = kicks, invalidates = invalidations, publishes = barriers;
            int object_fences = resv.fences, frame_fences = frame_resv.fences;
            int root_fences = root_resv.fences;
            texture_bindings[31].data.bytes = 67;
            assert(opengpu_tessellation_resources_sched_submit(&file, &drm, &context, &input,
                &uniform_plan, 1, &texture_plan, 1, &fragment_code, 1, 0, NULL, deps, dep_count, NULL) == -EINVAL);
            assert(live_allocations == allocations && live_kv == metadata && pushed == pending &&
                kicks == doorbells && invalidations == invalidates && barriers == publishes &&
                resv.fences == object_fences && frame_resv.fences == frame_fences && root_resv.fences == root_fences &&
                context.refs == 1 && !root_object.refs && !object.refs && !frame_object.refs && !context.last_fence);
            texture_bindings[31].data.bytes = 128;
        }
        assert(!opengpu_tessellation_resources_sched_submit(&file, &drm, &context, &input,
            &uniform_plan, 1, &texture_plan, 1, &fragment_code, 1, 0, NULL, deps, dep_count, NULL));
        memset(&uniform_plan, 0, sizeof(uniform_plan));
        memset(&texture_plan, 0, sizeof(texture_plan));
        memset(texture_bindings, 0, sizeof(texture_bindings));
        memset(texture_levels, 0, sizeof(texture_levels));
    } else if (uniform_owned) {
        assert(export_directory);
        assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
            &uniform_plan, 1, 0, NULL, deps, dep_count, NULL));
        memset(&uniform_plan, 0, sizeof(uniform_plan));
    } else {
        assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, dep_count, NULL));
    }
    struct opengpu_sched_job *queued = last_job;
    if (export_directory) {
        assert(root_object.refs == 1);
        memset(job, 0, sizeof(job)); memset(frame, 0, sizeof(frame));
        memset(draw, 0, sizeof(draw)); memset(states, 0, sizeof(states));
    }
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    assert(kicks == 1 && queued->temporary_mapping_count == (texture_owned ? 5u : 4u) && submitted_context == context.id);
    assert(queued->tess_states.cpu && queued->tess_states.size == (texture_host ? 512 : sizeof(states)));
    if (export_directory)
        fixture_export(export_directory, &context, queued, patch, frame_data,
            roots, code, &binding, &frame_binding, uniform_owned, texture_owned, texture_host);
    capture_fixture = false;
    busy = 0; opengpu_sched_free_job(&queued->base);
    assert(context.refs == 1 && !object.refs && !frame_object.refs && !root_object.refs);
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, binding.va, binding.size));
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, frame_binding.va, frame_binding.size));
    if (export_directory) {
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, root_binding.va, root_binding.size));
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, TESS_HOST_CODE_VA, sizeof(code)));
        free(frame_data);
    }
    release_context_fence(&context);
    mutex_unlock(&file.lock);
    opengpu_mmu_vm_destroy(&gpu, &context.vm); opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts(export_directory ?
        "C-prepared rooted snapshots exported after scheduler/MMU admission and retirement PASS (no RTL execution)" :
        "host-generated tessellation records accepted by scheduler and MMU PASS");
    return 0;
}

#include "opengpu_tessellation_uniform_schedule_test.h"
#include "opengpu_tessellation_texture_schedule_test.h"
#include "opengpu_tessellation_code_schedule_test.h"

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "--owned-code"))
        return test_owned_fragment_code();
    if (argc == 2 && !strcmp(argv[1], "--owned-texture"))
        return test_owned_texture();
    if (argc == 2 && !strcmp(argv[1], "--owned-uniform"))
        return test_owned_uniform();
    if (argc == 4 && !strcmp(argv[1], "--seed-export-fixture")) {
        unsigned long domain = strtoul(argv[3], NULL, 10);
        assert(domain < 3);
        fixture_seed(argv[2], (u32)domain);
        return 0;
    }
    if (argc == 4 && (!strcmp(argv[1], "--export-rooted") || !strcmp(argv[1], "--export-owned-uniform") ||
        !strcmp(argv[1], "--export-owned-texture") || !strcmp(argv[1], "--export-owned-texture-host"))) {
        char paths[4][4096];
        const char *names[] = { "job.bin", "frame.bin", "draws.bin", "states.bin" };
        for (u32 i = 0; i < 4; ++i)
            fixture_path(paths[i], sizeof(paths[i]), argv[2], names[i]);
        return test_host_fixture(paths[0], paths[1], paths[2], paths[3], argv[2], argv[3],
            strcmp(argv[1], "--export-rooted") != 0, !strcmp(argv[1], "--export-owned-texture") ||
                !strcmp(argv[1], "--export-owned-texture-host"), !strcmp(argv[1], "--export-owned-texture-host"));
    }
    if (argc == 5)
        return test_host_fixture(argv[1], argv[2], argv[3], argv[4], NULL, NULL, false, false, false);
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

    /* Every active patch allocation must be backed by a vertex binding and a
     * read dependency, including each draw in a strided, multi-draw job.
     * These source-only jobs discard rasterization and need no GFS state. */
    {
        struct dma_resv input_resv = { .held = 1 }, second_resv = { .held = 1 };
        struct drm_gem_object first = { .resv = &input_resv }, second = { .resv = &second_resv };
        struct opengpu_resource_binding a = { .object = &first, .type = OPENGPU_RESOURCE_VERTEX_BUFFER,
            .va = OPENGPU_VERTEX_VA_BASE + OPENGPU_VERTEX_VA_STRIDE, .dma = 0x31000000, .size = 4096 };
        struct opengpu_resource_binding b = { .object = &second, .type = OPENGPU_RESOURCE_VERTEX_BUFFER,
            .va = OPENGPU_VERTEX_VA_BASE + 2 * OPENGPU_VERTEX_VA_STRIDE, .dma = 0x31001000, .size = 4096 };
        struct opengpu_sched_dep inputs[] = {
            { &first, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
            { &second, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
        };
        input.scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD;
        gpu.hw.tessellation_feedback_transport = true;
        context.bindings[0] = &a; context.bindings[1] = &b;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, 0));
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, b.va, b.dma, b.size, 0));
        for (u32 domain = 0; domain < 3; ++domain) {
            make_records(job, frame, draws, context.id, domain, 2, STRIDE);
            make_patch_input(draws, domain, a.va);
            make_patch_input(draws + STRIDE, domain, b.va);
            before = live_allocations;
            int before_push = pushed;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, NULL, 0, NULL) == -EINVAL);
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 1, NULL) == -EINVAL);
            a.size = 63;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            a.size = 4096; a.type = 1;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            a.type = OPENGPU_RESOURCE_VERTEX_BUFFER; a.va = 0;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            a.va = OPENGPU_VERTEX_VA_BASE + OPENGPU_VERTEX_VA_STRIDE + 64;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            a.va -= 64; a.size = ~(u64)0;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            a.size = 4096;
            a.object = &object; /* A fence on a different object is insufficient. */
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            a.object = &first;
            inputs[0].wait_usage = DMA_RESV_USAGE_KERNEL;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            inputs[0].wait_usage = DMA_RESV_USAGE_WRITE;
            inputs[0].signal_usage = DMA_RESV_USAGE_BOOKKEEP;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL) == -EINVAL);
            inputs[0].signal_usage = DMA_RESV_USAGE_READ;
            assert(live_allocations == before && pushed == before_push && !first.refs && !second.refs);
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 2, NULL));
            queued = last_job;
            assert(first.refs == 1 && second.refs == 1 && context.refs == 2);
            assert(input_resv.wait_usage == DMA_RESV_USAGE_WRITE &&
                   input_resv.signal_usage == DMA_RESV_USAGE_READ &&
                   second_resv.wait_usage == DMA_RESV_USAGE_WRITE &&
                   second_resv.signal_usage == DMA_RESV_USAGE_READ);
            memset(draws, 0, sizeof(draws)); /* enqueue checked its own immutable copy */
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            assert(busy && first.refs == 1 && second.refs == 1);
            busy = 0; opengpu_sched_free_job(&queued->base);
            assert(!first.refs && !second.refs && context.refs == 1);

            /* A nonempty sequence with zero input components issues no patch
             * reads and does not require the second vertex dependency. */
            make_records(job, frame, draws, context.id, domain, 2, STRIDE);
            make_patch_input(draws, domain, a.va);
            make_patch_input(draws + STRIDE, domain, b.va);
            put(draws + STRIDE, domain ?
                OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD :
                OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD, 0);
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, inputs, 1, NULL));
            queued = last_job;
            assert(first.refs == 1 && !second.refs);
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            assert(!first.refs && !second.refs && context.refs == 1);
        }
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size));
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, b.va, b.size));
        context.bindings[0] = NULL; context.bindings[1] = NULL;
        input.scope.flags = 0;
        gpu.hw.tessellation_feedback_transport = false;
    }

    /* A nonempty rasterized sequence needs an immutable GFS snapshot even
     * when it has no patch-input reads. Check both strided draw positions,
     * fail before enqueue or any MMIO, then reuse the same context. */
    for (u32 domain = 0; domain < 3; ++domain) {
        for (u32 active_draw = 0; active_draw < 2; ++active_draw) {
            make_records(job, frame, draws, context.id, domain, 2, STRIDE);
            make_zero_input_draw(draws + active_draw * STRIDE, domain, 1);
            const int before_allocations = live_allocations, before_push = pushed;
            const int before_kicks = kicks, before_invalidations = invalidations;
            const int before_fences = resv.fences;
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context,
                &input, 0, NULL, &dep, 1, NULL) == -EINVAL);
            assert(live_allocations == before_allocations && pushed == before_push &&
                   kicks == before_kicks && invalidations == before_invalidations &&
                   resv.fences == before_fences &&
                   !object.refs && context.refs == 1 && !gpu.hw.wedged);

            /* Zero patch count does not reference a fragment state. */
            put(draws + active_draw * STRIDE,
                OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 0);
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context,
                &input, 0, NULL, &dep, 1, NULL));
            queued = last_job;
            assert(object.refs == 1 && context.refs == 2 && !queued->tess_states.cpu);
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            assert(!object.refs && context.refs == 1 && !gpu.hw.wedged);
        }
    }

    /* A final bus line ending at 4 GiB must not wrap to an empty range. */
    {
        struct tess_frame_span range[1];
        u32 count = 0;
        memset(frame, 0, sizeof(frame));
        put(frame, OPENGPU_TESS_FRAME_VIEW_BASE, 0xffffffc0u);
        put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT + 1, 1);
        assert(!tess_add_frame_span(frame, OPENGPU_TESS_FRAME_VIEW_BASE,
            OPENGPU_TESS_FRAME_VIEW_LIMIT, true, true, true, range, &count));
        assert(count == 1 && range[0].base == 0xffffffc0u &&
               range[0].end == 0x100000000ULL);
        put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT, 64);
        assert(tess_add_frame_span(frame, OPENGPU_TESS_FRAME_VIEW_BASE,
            OPENGPU_TESS_FRAME_VIEW_LIMIT, true, true, true, range, &count) == -EINVAL);
    }

    /* Frame resources and patch inputs are retained with write/read fences;
     * check all six attachments, resolves and both image addressing modes. */
    {
        struct dma_resv patch_resv = { .held = 1 }, frame_resv = { .held = 1 };
        unsigned char states[512];
        struct drm_gem_object patch_object = { .resv = &patch_resv }, frame_object = { .resv = &frame_resv };
        struct opengpu_resource_binding patch = { .object = &patch_object,
            .type = OPENGPU_RESOURCE_VERTEX_BUFFER, .va = OPENGPU_VERTEX_VA_BASE + OPENGPU_VERTEX_VA_STRIDE,
            .dma = 0x32000000, .size = 4096 };
        struct opengpu_resource_binding frame_binding = { .object = &frame_object,
            .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .va = OPENGPU_STORAGE_VA_BASE,
            .dma = 0x33000000, .size = 0x4000 };
        struct opengpu_sched_dep deps[] = {
            { &patch_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
            { &frame_object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE },
        };
        context.bindings[0] = &patch; context.bindings[12] = &frame_binding;
        input.states = states; input.states_size = sizeof(states);
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, patch.va, patch.dma, patch.size, 0));
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, frame_binding.va, frame_binding.dma, frame_binding.size, 0));
        gpu.hw.tessellation_engines = 4;
        for (u32 mode = 0; mode < 2; ++mode) {
            gpu.hw.tessellation_virtual_images = mode;
            for (u32 domain = 0; domain < 3; ++domain) {
                u32 image_base = mode ? frame_binding.va : frame_binding.dma;
                make_records(job, frame, draws, context.id, domain, 1, STRIDE);
                make_patch_input(draws, domain, patch.va);
                make_bound_frame(frame, frame_binding.va, image_base, 4);
                make_fragment_state(states, frame, 4, 0);
                /* New native advanced GFS admission happens before enqueue.
                 * Failures cannot publish fences, retain objects or kick GPU
                 * work. Exercise all three patch domains and image modes. */
                {
                    const int allocations = live_allocations, submissions = pushed, executions = kicks;
                    const u32 vm_tables = context.vm.l1_count, global_tables = gpu.mmu.l1_count;
                    const int patch_fences = patch_resv.fences, frame_fences = frame_resv.fences;
                    const u32 version_word = OPENGPU_NATIVE_ADVANCED_BLEND_GFS_PAYLOAD_VERSION_WORD;
                    const u32 blend_word = OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BLEND_VERSION_WORD;
                    const u32 control_word = OPENGPU_NATIVE_ADVANCED_BLEND_GFS_BUFFER0_WORD;
                    const u32 type_word = OPENGPU_NATIVE_ADVANCED_BLEND_GFS_COLOR_TYPE0_WORD;
                    put(states, version_word, 3); put(states, blend_word, 3);
                    put(states, control_word, (1u << 27) | (15u << 23) | 1u);
                    gpu.hw.native_advanced_blend_transport = false;
                    for (u32 bad = 0; bad < 11; ++bad) {
                        gpu.hw.native_advanced_blend_transport = bad != 0;
                        if (bad == 1) put(states, version_word, 1);
                        if (bad == 2) put(states, version_word, 2);
                        if (bad == 3) put(states, blend_word, 2);
                        if (bad == 4) put(states, control_word, (15u << 28) | (1u << 27) | 1u);
                        if (bad == 5) put(states, type_word, 1);
                        if (bad == 6) put(frame, OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_FORMAT0_WORD, 9);
                        if (bad >= 7) {
                            static const u32 unknown[][2] = {{3, 0}, {3, 4}, {0, 3}, {4, 3}};
                            /* Keep the body canonical even for a legacy tag,
                             * so this rejects unknown versions themselves. */
                            put(states, control_word, 0);
                            put(states, version_word, unknown[bad - 7][0]);
                            put(states, blend_word, unknown[bad - 7][1]);
                        }
                        assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                        assert(live_allocations == allocations && pushed == submissions && kicks == executions);
                        assert(!patch_object.refs && !frame_object.refs && context.refs == 1);
                        assert(patch_resv.fences == patch_fences && frame_resv.fences == frame_fences);
                        put(states, version_word, 3); put(states, blend_word, 3); put(states, type_word, 0);
                        put(states, control_word, (1u << 27) | (15u << 23) | 1u);
                        put(frame, OPENGPU_NATIVE_ADVANCED_BLEND_FRAME_COLOR_FORMAT0_WORD, 0);
                    }
                    for (u32 equation = 0; equation <= 14; equation += 14) {
                        put(states, control_word, (equation << 28) | (1u << 27) | (15u << 23) | 1u);
                        assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
                        queued = last_job;
                        assert(tess_word(queued->tess_states.cpu, control_word) ==
                               ((equation << 28) | (1u << 27) | (15u << 23) | 1u));
                        assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
                        const dma_addr_t state_va = queued->tess_states_va, state_dma = queued->tess_states.dma;
                        busy = 0; opengpu_sched_free_job(&queued->base);
                        /* First use may retain empty context-owned page-table
                         * pages. Every job allocation and live PTE must retire. */
                        assert(live_allocations == allocations + (int)(context.vm.l1_count - vm_tables) +
                               (int)(gpu.mmu.l1_count - global_tables));
                        assert(!patch_object.refs && !frame_object.refs && context.refs == 1);
                        assert(verify(&gpu, &context.vm, state_va, state_dma, 512u) == -EACCES);
                    }
                    gpu.hw.native_advanced_blend_transport = false;
                    make_fragment_state(states, frame, 4, 0);
                }
                before = live_allocations;
                int before_push = pushed;
                input.states_size--;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                input.states_size++;
                input.states = NULL;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                input.states = states;
                /* The copied state is checked before it can be queued. */
                put(states, 15, 1);
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                put(states, 15, 0);
                put(states, OPENGPU_TESS_STATE_IDENTITY_CONTEXT_WORD, context.id + 1);
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                put(states, OPENGPU_TESS_STATE_IDENTITY_CONTEXT_WORD, context.id);
                put(draws, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, 1);
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                put(draws, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, 0);
                put(states, OPENGPU_TESS_STATE_SCRATCH0_BASE_WORD, frame_binding.va + 0x940);
                put(states, OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD, frame_binding.va + 0x980);
                put(states, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD, frame_binding.va + 0x980);
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                make_fragment_state(states, frame, 4, 0);
                fail_alloc_after = 2;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -ENOMEM);
                fail_alloc_after = -1;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 1, NULL) == -EINVAL);
                deps[1].wait_usage = DMA_RESV_USAGE_WRITE;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                deps[1].wait_usage = DMA_RESV_USAGE_READ; deps[1].signal_usage = DMA_RESV_USAGE_READ;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                deps[1].signal_usage = DMA_RESV_USAGE_WRITE;
                frame_object.import_attach = &frame_resv;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                frame_object.import_attach = NULL; patch_object.import_attach = &patch_resv;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                patch_object.import_attach = NULL;
                put(frame, OPENGPU_TESS_FRAME_POOL_BASE, frame_binding.va + 64);
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                put(frame, OPENGPU_TESS_FRAME_POOL_BASE, frame_binding.va + 0x400);
                put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT, image_base + frame_binding.size + 64);
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT, image_base + 0x1440);
                gpu.hw.tessellation_virtual_images = !mode;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                gpu.hw.tessellation_virtual_images = mode;
                /* Neither frame nor patch backing may alias a copied snapshot. */
                saved_dma = next_dma; next_dma = frame_binding.dma;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                next_dma = patch.dma;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                /* The state copy is allocated after job and frame snapshots. */
                next_dma = frame_binding.dma - 2 * 4096;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                next_dma = patch.dma - 2 * 4096;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                next_dma = saved_dma;
                /* A physically aliased allocation can have a distinct GEM and VA. */
                patch.dma = frame_binding.dma;
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                patch.dma = 0x32000000;
                patch.object = &frame_object; /* Same backing and offset, different VAs. */
                assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
                patch.object = &patch_object;
                assert(live_allocations == before && pushed == before_push && !patch_object.refs && !frame_object.refs);
                assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
                queued = last_job;
                assert(patch_object.refs == 1 && frame_object.refs == 1 && context.refs == 2);
                assert(frame_resv.wait_usage == DMA_RESV_USAGE_READ && frame_resv.signal_usage == DMA_RESV_USAGE_WRITE);
                assert(queued->tess_states.cpu && queued->tess_states.cpu != states &&
                       queued->tess_states.size == sizeof(states) &&
                       !memcmp(queued->tess_states.cpu, states, sizeof(states)));
                assert(tess_word(queued->tess_frame.cpu, OPENGPU_TESS_FRAME_STATE_BASE) == queued->tess_states_va);
                memset(frame, 0, sizeof(frame)); memset(draws, 0, sizeof(draws)); memset(states, 0, sizeof(states));
                assert(tess_word(queued->tess_states.cpu, 0) == 0x47465301u);
                assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
                assert(queued->temporary_mapping_count == 4 &&
                       !verify(&gpu, &context.vm, queued->tess_states_va,
                               queued->tess_states.dma, queued->tess_states.size));
                dma_addr_t state_va = queued->tess_states_va, state_dma = queued->tess_states.dma;
                busy = 0; opengpu_sched_free_job(&queued->base);
                assert(verify(&gpu, &context.vm, state_va, state_dma, sizeof(states)) == -EACCES);
                assert(!patch_object.refs && !frame_object.refs && context.refs == 1);
            }
        }
        /* Two strided draws may select distinct entries. An unused table slot
         * need not contain a valid state until a nonempty draw references it. */
        {
            unsigned char two_states[1024];
            make_records(job, frame, draws, context.id, 0, 2, STRIDE);
            make_patch_input(draws, 0, patch.va);
            make_patch_input(draws + STRIDE, 0, patch.va);
            put(draws + STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, 1);
            make_bound_frame(frame, frame_binding.va, frame_binding.va, 4);
            put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, frame_binding.va + 0x1400);
            make_fragment_state(two_states, frame, 4, 0);
            make_fragment_state(two_states + 512, frame, 4, 1);
            input.states = two_states; input.states_size = sizeof(two_states);
            put(two_states + 512, 15, 1);
            assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL) == -EINVAL);
            put(draws + STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 0);
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
            queued = last_job;
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            put(draws + STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 2);
            put(two_states + 512, 15, 0);
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
            queued = last_job;
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            /* Discard-only work does not require fragment-state admission. */
            put(two_states, 15, 1);
            input.scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD;
            gpu.hw.tessellation_feedback_transport = true;
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
            queued = last_job;
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            input.scope.flags = 0; gpu.hw.tessellation_feedback_transport = false;
            input.states = states; input.states_size = sizeof(states);
        }
        /* A writable mapping of a state snapshot must fail before publication. */
        make_records(job, frame, draws, context.id, 0, 1, STRIDE);
        make_patch_input(draws, 0, patch.va);
        make_bound_frame(frame, frame_binding.va, frame_binding.va, 4);
        /* The old arena address need not remain bound after copying its bytes. */
        put(frame, OPENGPU_TESS_FRAME_STATE_BASE, 0x24000000);
        put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, 0x24000200);
        make_fragment_state(states, frame, 4, 0);
        assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
        queued = last_job;
        const dma_addr_t state_alias = 0xb3000000;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, state_alias, queued->tess_states.dma, 4096, 0));
        const int before_kicks = kicks, before_invalidations = invalidations;
        assert(opengpu_sched_run_job(&queued->base) == ERR_PTR(-EACCES));
        assert(queued->temporary_mapping_count == 4 && kicks == before_kicks &&
               invalidations == before_invalidations);
        opengpu_sched_free_job(&queued->base);
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, state_alias, 4096));

        /* A read-only image waits for a writer but does not publish a write. */
        make_records(job, frame, draws, context.id, 0, 0, 0);
        input.states = NULL; input.states_size = 0;
        put(frame, OPENGPU_TESS_FRAME_COLOR_ENABLED, 1);
        put(frame, OPENGPU_TESS_FRAME_VIEW_BASE, frame_binding.va + 0x1400);
        put(frame, OPENGPU_TESS_FRAME_VIEW_LIMIT, frame_binding.va + 0x1440);
        deps[1].wait_usage = DMA_RESV_USAGE_WRITE; deps[1].signal_usage = DMA_RESV_USAGE_READ;
        assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &deps[1], 1, NULL));
        queued = last_job;
        assert(frame_object.refs == 1 && frame_resv.signal_usage == DMA_RESV_USAGE_READ);
        assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
        busy = 0; opengpu_sched_free_job(&queued->base);
        put(frame, OPENGPU_TESS_FRAME_VIEW_STORE, 1);
        assert(opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, &deps[1], 1, NULL) == -EINVAL);
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, patch.va, patch.size));
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, frame_binding.va, frame_binding.size));
        context.bindings[0] = NULL; context.bindings[12] = NULL;
    }
    /* Root envelopes are ABI-sized, independent of pointer value zero, and
     * retain 33-bit exclusive ends. These are address-only checks. */
    {
        unsigned char state[512] = {0};
        const u32 versions[] = { OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD };
        const u32 addresses[] = { OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD };
        const u32 sizes[] = { OPENGPU_TESS_TEXTURE_ROOT_BYTES, OPENGPU_TESS_UNIFORM_ROOT_BYTES };
        struct tess_frame_span range;
        for (u32 root = 0; root < 2; ++root) {
            assert(tess_state_root_span(state, versions[root], addresses[root], sizes[root], &range) == 0);
            put(state, addresses[root], 64);
            assert(tess_state_root_span(state, versions[root], addresses[root], sizes[root], &range) == -EINVAL);
            put(state, addresses[root], 0); put(state, versions[root], 1);
            assert(tess_state_root_span(state, versions[root], addresses[root], sizes[root], &range) == 1);
            assert(!range.base && range.end == sizes[root] && !range.write && !range.physical);
            put(state, addresses[root], (u32)(0x100000000ULL - sizes[root]));
            assert(tess_state_root_span(state, versions[root], addresses[root], sizes[root], &range) == 1);
            assert(range.end == 0x100000000ULL);
            if (sizes[root] > 64) {
                put(state, addresses[root], 0xffffffc0u);
                assert(tess_state_root_span(state, versions[root], addresses[root], sizes[root], &range) == -EINVAL);
            }
            put(state, addresses[root], 0xffffffc1u);
            assert(tess_state_root_span(state, versions[root], addresses[root], sizes[root], &range) == -EINVAL);
            put(state, addresses[root], 0);
            for (u32 version = 2; version < 4; ++version) {
                put(state, versions[root], version);
                assert(tess_state_root_span(state, versions[root], addresses[root], sizes[root], &range) == -EINVAL);
            }
            put(state, versions[root], 0);
        }
    }

    /* Rooted GFS admission witnesses. The fake device does not read the root
     * contents or execute resource instructions. Both strided draws read no
     * patch input; every enabled root still needs its own checked backing. */
    {
        unsigned char states[1024];
        const u32 versions[] = { OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD };
        const u32 addresses[] = { OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD };
        const u32 sizes[] = { OPENGPU_TESS_TEXTURE_ROOT_BYTES, OPENGPU_TESS_UNIFORM_ROOT_BYTES };
        struct dma_resv frame_resv = { .held = 1 }, root_resv = { .held = 1 };
        struct drm_gem_object frame_object = { .resv = &frame_resv }, root_object = { .resv = &root_resv };
        struct opengpu_resource_binding frame_binding = { .object = &frame_object,
            .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .va = OPENGPU_STORAGE_VA_BASE,
            .dma = 0x34000000, .size = 0x4000 };
        struct opengpu_resource_binding root_binding = { .object = &root_object,
            .type = OPENGPU_RESOURCE_COMPUTE_STORAGE,
            .va = OPENGPU_STORAGE_VA_BASE + OPENGPU_STORAGE_VA_STRIDE,
            .dma = 0x35000000, .size = 4096 };
        struct opengpu_sched_dep deps[] = {
            { &frame_object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE },
            { &root_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
        };
        const u32 root_va = root_binding.va;
        const dma_addr_t root_dma = root_binding.dma;
        context.bindings[12] = &frame_binding; context.bindings[13] = &root_binding;
        gpu.hw.tessellation_engines = 2; gpu.hw.tessellation_virtual_images = true;
        input.states = states; input.states_size = sizeof(states);
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, frame_binding.va, frame_binding.dma, frame_binding.size, 0));
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, root_binding.va, root_binding.dma, root_binding.size, 0));
        for (u32 domain = 0; domain < 3; ++domain)
            for (u32 draw = 0; draw < 2; ++draw)
                for (u32 root = 0; root < 2; ++root) {
                    unsigned char *state = states + draw * 512;
                    make_rooted_records(job, frame, draws, states, context.id, domain, STRIDE,
                                        frame_binding.va, root_va, draw, root);
                    reject_rooted_job(&file, &drm, &context, &input, deps, 1); /* missing root fence */
                    context.bindings[13] = NULL;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    context.bindings[13] = &root_binding;
                    root_binding.type = OPENGPU_RESOURCE_VERTEX_BUFFER;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    root_binding.type = OPENGPU_RESOURCE_COMPUTE_STORAGE;
                    root_object.import_attach = &root_resv;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    root_object.import_attach = NULL; root_binding.va = 0;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    root_binding.va = root_va; root_binding.dma = 0;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    root_binding.dma = root_dma; root_binding.size = 0x80 + sizes[root] - 1;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    root_binding.size = 4096; root_binding.offset = ~(u64)0;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    root_binding.offset = 0;
                    deps[1].wait_usage = DMA_RESV_USAGE_KERNEL;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    deps[1].wait_usage = DMA_RESV_USAGE_WRITE; deps[1].signal_usage = DMA_RESV_USAGE_BOOKKEEP;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    deps[1].signal_usage = DMA_RESV_USAGE_READ;
                    put(state, versions[root], 2);
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    put(state, versions[root], 0); /* disabled address must also be zero */
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    put(state, versions[root], 1); put(state, addresses[root], root_va + 0x81);
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    put(state, addresses[root], root_dma + 0x80); /* PA is not a root VA */
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    put(state, addresses[root], root_va + 0x80);
                    root_binding.dma = frame_binding.dma; /* distinct objects, same physical bytes */
                    reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    root_binding.dma = root_dma; root_binding.object = &frame_object;
                    reject_rooted_job(&file, &drm, &context, &input, deps, 1); /* same GEM offsets, distinct DMA */
                    root_binding.object = &root_object;
                    saved_dma = next_dma;
                    for (u32 snapshot = 0; snapshot < 4; ++snapshot) {
                        next_dma = root_dma - snapshot * 4096;
                        reject_rooted_job(&file, &drm, &context, &input, deps, 2);
                    }
                    next_dma = saved_dma;
                    assert(!root_object.refs && !frame_object.refs);
                    assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
                    queued = last_job;
                    assert(root_object.refs == 1 && frame_object.refs == 1 && context.refs == 2);
                    assert(root_resv.wait_usage == DMA_RESV_USAGE_WRITE && root_resv.signal_usage == DMA_RESV_USAGE_READ);
                    put(state, addresses[root], 0); /* only the copied GFS selects the root after enqueue */
                    assert(tess_word((unsigned char *)queued->tess_states.cpu + draw * 512,
                                     addresses[root]) == root_va + 0x80);
                    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
                    assert(root_object.refs == 1 && busy);
                    busy = 0; opengpu_sched_free_job(&queued->base);
                    assert(!root_object.refs && !frame_object.refs && context.refs == 1);
                }
        /* Root inspection skips zero-count draws and unreferenced states. */
        for (u32 domain = 0; domain < 3; ++domain) {
            make_rooted_records(job, frame, draws, states, context.id, domain, STRIDE,
                                frame_binding.va, root_va, 1, 0);
            put(states + 512, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD, 2);
            put(draws + STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 0);
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 1, NULL));
            queued = last_job; assert(!root_object.refs);
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            /* The existing discard-only contract also skips fragment roots. */
            put(draws + STRIDE, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 1);
            input.scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD; gpu.hw.tessellation_feedback_transport = true;
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 1, NULL));
            queued = last_job; assert(!root_object.refs);
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            input.scope.flags = 0; gpu.hw.tessellation_feedback_transport = false;
        }
        /* Both states may share the same read-only texture and uniform roots. */
        make_rooted_records(job, frame, draws, states, context.id, 0, STRIDE,
                            frame_binding.va, root_va, 0, 0);
        for (u32 i = 0; i < 2; ++i) {
            put(states + i * 512, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD, 1);
            put(states + i * 512, OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD, root_va + 0x80);
            put(states + i * 512, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD, 1);
            put(states + i * 512, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD, root_va + 0x100);
        }
        assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
        queued = last_job; assert(root_object.refs == 1);
        assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
        busy = 0; opengpu_sched_free_job(&queued->base);
        /* A root may also share read-only patch input backing at another VA. */
        {
            struct opengpu_resource_binding patch = { .object = &root_object,
                .type = OPENGPU_RESOURCE_VERTEX_BUFFER,
                .va = OPENGPU_VERTEX_VA_BASE + OPENGPU_VERTEX_VA_STRIDE,
                .dma = root_dma, .size = 4096 };
            make_rooted_records(job, frame, draws, states, context.id, 0, STRIDE,
                                frame_binding.va, root_va, 0, 1);
            make_patch_input(draws, 0, patch.va + 0x80);
            context.bindings[0] = &patch;
            assert(!opengpu_mmu_vm_map(&gpu, &context.vm, patch.va, patch.dma, patch.size, 0));
            assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
            queued = last_job; assert(root_object.refs == 1);
            assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
            busy = 0; opengpu_sched_free_job(&queued->base);
            assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, patch.va, patch.size));
            context.bindings[0] = NULL;
        }
        /* A read-only image view may overlap a read-only uniform root. */
        make_rooted_records(job, frame, draws, states, context.id, 0, STRIDE,
                            frame_binding.va, root_va, 0, 1);
        put(states, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD, frame_binding.va + 0x1400);
        put(frame, OPENGPU_TESS_FRAME_VIEW_STORE, 0);
        /* The generated layout's first resolve-enable word is extracted here. */
#define FIRST_RESOLVE_ENABLE(i, b, l, e) e,
        {
            static const u32 enables[] = { OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(FIRST_RESOLVE_ENABLE) };
            put(frame, enables[0], 0);
        }
#undef FIRST_RESOLVE_ENABLE
        assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 1, NULL));
        queued = last_job;
        assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
        busy = 0; opengpu_sched_free_job(&queued->base);
        /* Valid roots ending exactly at 4 GiB pass the complete admission. */
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, root_binding.va, root_binding.size));
        root_binding.va = 0xfffff000u;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, root_binding.va, root_binding.dma, root_binding.size, 0));
        for (u32 domain = 0; domain < 3; ++domain)
            for (u32 root = 0; root < 2; ++root) {
                make_rooted_records(job, frame, draws, states, context.id, domain, STRIDE,
                                    frame_binding.va, root_binding.va, 0, root);
                put(states, addresses[root], (u32)(0x100000000ULL - sizes[root]));
                assert(!opengpu_tessellation_sched_submit(&file, &drm, &context, &input, 0, NULL, deps, 2, NULL));
                queued = last_job;
                assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
                busy = 0; opengpu_sched_free_job(&queued->base);
            }
        assert(!root_object.refs && !frame_object.refs && context.refs == 1);
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, root_binding.va, root_binding.size));
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, frame_binding.va, frame_binding.size));
        context.bindings[12] = NULL; context.bindings[13] = NULL;
        input.states = NULL; input.states_size = 0;
    }
    release_context_fence(&context);
    mutex_unlock(&file.lock);
    opengpu_mmu_vm_destroy(&gpu, &context.vm);
    opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    printf("tessellation scheduler: copied snapshots, private mappings, submission and retirement PASS (jobs=%d fenceCapacity=%zu)\n",
           pushed, sizeof(scheduler_fences) / sizeof(scheduler_fences[0]));
    return 0;
}
