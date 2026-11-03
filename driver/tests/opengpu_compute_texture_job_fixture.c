/* The actual compute job branch, using mock VM mapping and hardware submit. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include "opengpu_kernarg_va.h"
typedef uint64_t dma_addr_t;
typedef uint64_t u64;
#define OPENGPU_SCHED_COMPUTE 1
#define OPENGPU_SCHED_TESSELLATION 7
#define OPENGPU_MMU_PAGE_SIZE 4096
#define OPENGPU_MMU_POLICY_UNCACHED 2
#define upper_32_bits(v) ((uint64_t)(v) >> 32)
#define ERR_PTR(e) ((struct dma_fence *)(intptr_t)(e))
struct dma_fence { int dummy; };
struct opengpu_vm { int dummy; };
struct opengpu_render_context { struct opengpu_vm vm; };
struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_kernel_launch { dma_addr_t kernel_pc; bool texture_enabled, storage_enabled, image_enabled, counter_enabled; struct { uint32_t root; } texture; struct { uint32_t base; uint64_t limit; } storage; struct { uint32_t base; uint64_t limit; } image; struct { uint32_t base; uint64_t limit; } counter; };
struct opengpu_command_events { int dummy; };
struct opengpu_device { int dummy; };
struct opengpu_sched_job {
    int type; struct opengpu_device *gpu; struct opengpu_vm *vm;
    struct opengpu_render_context *context; struct opengpu_buffer shader, texture_records, storage_records, image_records, counter_records;
    struct opengpu_kernel_launch kernel; struct opengpu_command_events events;
};
static int mappings, tracks, submits, map_error, track_error, tess_runs;
static dma_addr_t mapped_va, mapped_pa; static size_t mapped_size;
static struct dma_fence fence;
static struct dma_fence *opengpu_tessellation_job_run(struct opengpu_sched_job *j)
{ assert(j->type == OPENGPU_SCHED_TESSELLATION); tess_runs++; return &fence; }
static int opengpu_binding_map_code(struct opengpu_device *g, struct opengpu_render_context *c,
    dma_addr_t dma, size_t size, unsigned slot, dma_addr_t *va)
{ (void)g; (void)c; (void)size; (void)slot; *va = dma + 0x10000; return 0; }
static int opengpu_job_track_mapping(struct opengpu_sched_job *j, dma_addr_t va, size_t size)
{ (void)j; (void)va; (void)size; tracks++; return track_error; }
static int opengpu_mmu_vm_map(struct opengpu_device *g, struct opengpu_vm *v,
    dma_addr_t va, dma_addr_t pa, size_t size, unsigned policy)
{ (void)g; (void)v; assert(policy == OPENGPU_MMU_POLICY_UNCACHED); mappings++; mapped_va=va; mapped_pa=pa; mapped_size=size; return map_error; }
static int opengpu_hw_compute_async(struct opengpu_device *g, const struct opengpu_kernel_launch *l,
    const struct opengpu_buffer *s, const struct opengpu_buffer *t, const struct opengpu_buffer *storage, const struct opengpu_buffer *image, const struct opengpu_buffer *counter,
    const struct opengpu_command_events *e, const struct opengpu_vm *v, struct dma_fence **out)
{ (void)g; (void)l; (void)s; (void)t; (void)storage; (void)image; (void)counter; (void)e; (void)v; submits++; *out=&fence; return 0; }
#include "job_under_test.inc"
int main(void)
{
    struct opengpu_device gpu = {0}; struct opengpu_render_context ctx = {0};
    struct opengpu_sched_job j = { .type=OPENGPU_SCHED_COMPUTE, .gpu=&gpu,
        .texture_records = { .cpu=&gpu, .dma=0x12000, .size=512 },
        .kernel = { .texture_enabled=true, .texture.root=0x12000 } };
    j.type = OPENGPU_SCHED_TESSELLATION;
    assert(opengpu_job_run(&j) == &fence && tess_runs == 1 && !submits);
    j.type = OPENGPU_SCHED_COMPUTE;
    assert(opengpu_job_run(&j) == &fence && submits == 1 && !mappings);
    j.kernel.texture.root++; assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 1);
    j.vm=&ctx.vm; j.context=&ctx; j.kernel.texture.root=OPENGPU_COMMAND_VA_BASE+2*OPENGPU_COMMAND_VA_STRIDE;
    assert(opengpu_job_run(&j) == &fence && submits == 2 && mappings == 1 && tracks == 1);
    assert(mapped_va == j.kernel.texture.root && mapped_pa == 0x12000 && mapped_size == 4096);
    j.kernel.texture.root++; assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && mappings == 1);
    j.kernel.texture.root--; map_error=-ENOMEM;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOMEM) && submits == 2 && tracks == 1);
    map_error=0; track_error=-ENOSPC;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOSPC) && submits == 2);
    track_error=0; j.texture_records.cpu=NULL;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 2);
    j.texture_records.cpu=&gpu; j.texture_records.size=127;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 2);
    j.kernel.texture_enabled=false;
    assert(opengpu_job_run(&j) == &fence && submits == 3);
    j.kernel.storage_enabled=true;
    j.storage_records=(struct opengpu_buffer) { .cpu=&gpu, .dma=0x20000, .size=256 };
    j.kernel.storage.base=OPENGPU_COMMAND_VA_BASE+3*OPENGPU_COMMAND_VA_STRIDE;
    j.kernel.storage.limit=(u64)j.kernel.storage.base+256;
    assert(opengpu_job_run(&j) == &fence && submits == 4 && mappings == 4);
    assert(mapped_va == j.kernel.storage.base && mapped_pa == 0x20000 && mapped_size == 4096);
    map_error=-ENOMEM;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOMEM) && submits == 4);
    map_error=0; track_error=-ENOSPC;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOSPC) && submits == 4);
    track_error=0;
    j.kernel.storage.limit--; assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 4);
    j.kernel.storage.limit++; j.storage_records.size=320;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 4);
    j.storage_records.size=256;
    j.kernel.texture_enabled=true; j.texture_records.size=512;
    j.kernel.texture.root=OPENGPU_COMMAND_VA_BASE+2*OPENGPU_COMMAND_VA_STRIDE;
    assert(opengpu_job_run(&j) == &fence && submits == 5);
    j.kernel.texture_enabled=false; j.vm=NULL; j.kernel.storage.base=0x20000; j.kernel.storage.limit=0x20100;
    assert(opengpu_job_run(&j) == &fence && submits == 6);
    j.kernel.storage_enabled=false; j.vm=&ctx.vm;
    j.kernel.image_enabled=true;
    j.image_records=(struct opengpu_buffer) { .cpu=&gpu, .dma=0x30000, .size=1024 };
    j.kernel.image.base=OPENGPU_COMMAND_VA_BASE+4*OPENGPU_COMMAND_VA_STRIDE;
    j.kernel.image.limit=(u64)j.kernel.image.base+1024;
    assert(opengpu_job_run(&j) == &fence && submits == 7);
    assert(mapped_va == j.kernel.image.base && mapped_pa == 0x30000 && mapped_size == 4096);
    map_error=-ENOMEM;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOMEM) && submits == 7);
    map_error=0; track_error=-ENOSPC;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOSPC) && submits == 7);
    track_error=0;
    j.kernel.image.limit--; assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 7);
    j.kernel.image.limit++; j.image_records.size=1280;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 7);
    j.image_records.size=1024; j.vm=NULL; j.kernel.image.base=0x30000; j.kernel.image.limit=0x30400;
    assert(opengpu_job_run(&j) == &fence && submits == 8);
    j.kernel.image_enabled=false; j.vm=&ctx.vm;
    j.kernel.counter_enabled=true;
    j.counter_records=(struct opengpu_buffer) { .cpu=&gpu, .dma=0x40000, .size=64 };
    j.kernel.counter.base=OPENGPU_COMMAND_VA_BASE+5*OPENGPU_COMMAND_VA_STRIDE;
    j.kernel.counter.limit=(u64)j.kernel.counter.base+64;
    assert(opengpu_job_run(&j) == &fence && submits == 9);
    assert(mapped_va == j.kernel.counter.base && mapped_pa == 0x40000 && mapped_size == 4096);
    map_error=-ENOMEM;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOMEM) && submits == 9);
    map_error=0; track_error=-ENOSPC;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOSPC) && submits == 9);
    track_error=0; j.kernel.counter.limit--;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 9);
    j.kernel.counter.limit++; j.counter_records.size=128;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 9);
    j.counter_records.size=64; j.vm=NULL; j.kernel.counter.base=0x40000; j.kernel.counter.limit=0x40040;
    assert(opengpu_job_run(&j) == &fence && submits == 10);
    return 0;
}
