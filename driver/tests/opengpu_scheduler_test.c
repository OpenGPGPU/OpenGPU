/* Exercise the production scheduler's dependency ownership with kernel stubs. */
#include <assert.h>
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#define OPENGPU_SCHEDULER_H
#define container_of(p, t, member) ((t *)((char *)(p) - offsetof(t, member)))
#define DRM_GPU_SCHED_STAT_RESET 1
#define DRM_SCHED_PRIORITY_COUNT 1
#define OPENGPU_DRAW_WAIT_MS 1
#define msecs_to_jiffies(x) (x)
#define dev_err(...) ((void)0)
typedef uint32_t u32;
typedef uint64_t u64;
struct dma_fence { u64 seqno; };
struct dma_resv { int fence_count; };
struct drm_gem_object { struct dma_resv *resv; int refs; };
struct drm_sched_entity { int pushed; };
struct drm_sched_fence { struct dma_fence finished; };
struct drm_sched_job { struct drm_sched_fence *s_fence; };
struct drm_syncobj { int unused; };
struct drm_file { u64 client_id; };
struct opengpu_vm { int unused; };
struct opengpu_buffer { int unused; };
struct opengpu_device { void *dev; struct { int scheduler; } compute; };
struct opengpu_render_context { struct drm_sched_entity entity; struct dma_fence *last_fence; struct opengpu_vm vm; };
struct opengpu_file { int unused; };
struct opengpu_job_temporary_mapping { u64 va; size_t size; };
struct opengpu_sched_job {
    struct drm_sched_job base; struct opengpu_device *gpu;
    struct opengpu_render_context *context; struct drm_gem_object **objects; u32 object_count;
    struct opengpu_job_temporary_mapping temporary_mappings[8]; u32 temporary_mapping_count;
    struct opengpu_buffer depth, vertex_shader, shader, render_desc, commands;
};
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage, signal_usage; };
enum drm_gpu_sched_stat { test_reset = DRM_GPU_SCHED_STAT_RESET };
struct drm_sched_backend_ops {
    struct dma_fence *(*run_job)(struct drm_sched_job *);
    enum drm_gpu_sched_stat (*timedout_job)(struct drm_sched_job *);
    void (*free_job)(struct drm_sched_job *);
};
struct drm_sched_init_args { const struct drm_sched_backend_ops *ops; int num_rqs, credit_limit, hang_limit, timeout; const char *name; void *dev; };
static int init_error, dep_error, dep_calls, cleanup_count, free_count;
static struct drm_sched_fence sched_fence;
static struct dma_fence *opengpu_job_run(struct opengpu_sched_job *j) { (void)j; return NULL; }
static void opengpu_hw_abort(struct opengpu_device *g, int e) { (void)g; (void)e; }
static void opengpu_mmu_vm_unmap(struct opengpu_device *g, struct opengpu_vm *v, u64 a, size_t z) { (void)g; (void)v; (void)a; (void)z; }
static void opengpu_buffer_free(struct opengpu_device *g, struct opengpu_buffer *b) { (void)g; (void)b; free_count++; }
static void drm_sched_job_cleanup(struct drm_sched_job *j) { (void)j; cleanup_count++; }
static void drm_gem_object_put(struct drm_gem_object *o) { o->refs--; }
static void drm_gem_object_get(struct drm_gem_object *o) { o->refs++; }
static int drm_sched_job_init(struct drm_sched_job *j, struct drm_sched_entity *e, int c, struct opengpu_file *f, u64 id)
{ (void)e; (void)c; (void)f; (void)id; j->s_fence = &sched_fence; return init_error; }
static int drm_sched_job_add_syncobj_dependency(struct drm_sched_job *j, struct drm_file *f, u32 id, int p)
{ (void)j; (void)f; (void)id; (void)p; return dep_error; }
static int drm_sched_job_add_resv_dependencies(struct drm_sched_job *j, struct dma_resv *r, int u)
{ (void)j; (void)r; (void)u; return dep_error && ++dep_calls == 10 ? dep_error : 0; }
static void drm_sched_job_arm(struct drm_sched_job *j) { (void)j; }
static struct dma_fence *dma_fence_get(struct dma_fence *f) { return f; }
static void dma_fence_put(struct dma_fence *f) { (void)f; }
static void dma_resv_add_fence(struct dma_resv *r, struct dma_fence *f, int u) { (void)f; (void)u; r->fence_count++; }
static void drm_syncobj_replace_fence(struct drm_syncobj *s, struct dma_fence *f) { (void)s; (void)f; }
static void drm_sched_entity_push_job(struct drm_sched_job *j) { (void)j; }
static int drm_sched_init(int *s, const struct drm_sched_init_args *a) { (void)s; (void)a; return 0; }
static void drm_sched_fini(int *s) { (void)s; }
#include "../opengpu_scheduler.c"
int main(void)
{
    struct opengpu_device gpu = {0}; struct opengpu_file file = {0};
    struct drm_file drm = {0}; struct opengpu_render_context context = {0};
    struct dma_resv resv[32] = {0}; struct drm_gem_object objs[32] = {0};
    struct opengpu_sched_dep deps[32] = {0};
    struct opengpu_sched_job *job;
    for (u32 i = 0; i < 32; i++) { objs[i].resv = &resv[i]; deps[i].object = &objs[i]; }
    job = calloc(1, sizeof(*job)); assert(job); job->gpu = &gpu;
    assert(opengpu_sched_job_submit(&file, &drm, &context, job, 0, NULL, deps, 32, NULL) == 0);
    assert(job->object_count == 32);
    for (u32 i = 0; i < 32; i++) assert(objs[i].refs == 1 && resv[i].fence_count == 1);
    opengpu_sched_free_job(&job->base);
    for (u32 i = 0; i < 32; i++) assert(objs[i].refs == 0);
    assert(cleanup_count == 1 && free_count == 5);
    job = calloc(1, sizeof(*job)); assert(job);
    test_alloc_fail = 1;
    assert(opengpu_sched_job_submit(&file, &drm, &context, job, 0, NULL, deps, 32, NULL) == -ENOMEM);
    test_alloc_fail = 0; init_error = -EINVAL;
    assert(opengpu_sched_job_submit(&file, &drm, &context, job, 0, NULL, deps, 32, NULL) == -EINVAL);
    assert(job->objects == NULL); init_error = 0; dep_error = -EINVAL;
    assert(opengpu_sched_job_submit(&file, &drm, &context, job, 0, NULL, deps, 32, NULL) == -EINVAL);
    assert(job->objects == NULL && cleanup_count == 2);
    for (u32 i = 0; i < 32; i++) assert(objs[i].refs == 0 && resv[i].fence_count == 1);
    dep_error = 0;
    assert(opengpu_sched_job_submit(&file, &drm, &context, job, 0, NULL, NULL, 0, NULL) == 0);
    assert(job->object_count == 0 && job->objects == NULL);
    job->gpu = &gpu;
    opengpu_sched_free_job(&job->base);
    return 0;
}
